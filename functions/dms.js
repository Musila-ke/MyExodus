'use strict';
// DMS relationship contracts (partner event-delivery plan §7 Step 8 / §8.10-§8.11):
//
//   setAvailabilityConsent  — the explicit `DEVICE_AVAILABILITY_STATUS` scope. Written by the
//                             monitored member on the AUTHORITATIVE pairing document; the
//                             dispatch recheck joins this exact source (§4 consent).
//   readPartnerStatus       — coarse availability derived at read time, with the documented
//                             ingestion-outage compensation; never a precise timestamp.
//   recordIngestionOutage   — the operator half of the compensation contract: an ingestion
//                             outage (heartbeats not arriving) is a server-recorded fact, not
//                             something a client or a silent gap can assert.
//
// Every handler is created by a `create...Handler({db, now})` seam so the emulator suite drives
// the same code the deployed callables run (house pattern, cf. heartbeat.js).

const { getFirestore } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');

const {
  PARTNER_PUSH_AFTER_MS,
  activePartner,
  consentEnabled,
  deriveAvailability,
  isOperator,
  isValidOutageInterval,
} = require('./dms-logic');

const REGION = 'us-central1';
const DMS_CONFIG_DOC = 'config/dms';
const MAX_PAIR_ID_LENGTH = 200;
// A monitored user may legitimately hold a few installations; the read is bounded regardless.
const MAX_INSTALL_STATES_PER_USER = 20;

function requireAuth(request) {
  if (!request.auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');
  return request.auth.uid;
}

function readPairId(request) {
  const pairId = request.data && request.data.pairId;
  if (typeof pairId !== 'string' || pairId.length === 0 || pairId.length > MAX_PAIR_ID_LENGTH) {
    throw new HttpsError('invalid-argument', 'INVALID_REQUEST');
  }
  return pairId;
}

/**
 * Writes one member's availability scope. Idempotent: the same state returns `changed: false`
 * without a write, which is what lets the client converge on every sync pass instead of
 * keeping its own durable acknowledgement copy (§4: revocation is effective server-side the
 * moment this call lands; offline it is pending until then).
 *
 * The write is a dotted-path update — a shallow `merge: true` of a nested map would erase the
 * other member's consent entry, the exact Firestore merge trap already documented in the
 * pairing record.
 */
function createSetAvailabilityConsentHandler({ db, now = () => Date.now() }) {
  return async function setAvailabilityConsentHandler(request) {
    const uid = requireAuth(request);
    const pairId = readPairId(request);
    const enabled = request.data && request.data.enabled;
    if (typeof enabled !== 'boolean') {
      throw new HttpsError('invalid-argument', 'INVALID_REQUEST');
    }
    const pairRef = db.doc(`pairings/${pairId}`);
    const nowMs = now();

    return db.runTransaction(async (tx) => {
      const snap = await tx.get(pairRef);
      if (!snap.exists) throw new HttpsError('permission-denied', 'RELATIONSHIP_UNAVAILABLE');
      const pairing = snap.data();
      if (
        pairing.status !== 'active' ||
        !Array.isArray(pairing.members) ||
        !pairing.members.includes(uid)
      ) {
        throw new HttpsError('permission-denied', 'RELATIONSHIP_UNAVAILABLE');
      }

      const existing = pairing.availabilityConsent && pairing.availabilityConsent[uid];
      if (existing && existing.enabled === enabled) {
        return { pairId, enabled, changed: false };
      }
      tx.update(pairRef, {
        [`availabilityConsent.${uid}`]: { enabled, updatedAtMs: nowMs },
      });
      logger.info('availability consent updated', { pairId, enabled });
      return { pairId, enabled, changed: true };
    });
  };
}

/**
 * §8.10 "Read partner status": the caller asks about the OTHER member of an active
 * relationship. Derivation is at read time (§4) and outage-compensated, so a later sweep is
 * never needed for the displayed state. Response carries the coarse state/bucket only — no
 * receipt time, no IP, no installation id, no monitoring snapshot.
 */
function createReadPartnerStatusHandler({ db, now = () => Date.now() }) {
  return async function readPartnerStatusHandler(request) {
    const uid = requireAuth(request);
    const pairId = readPairId(request);
    const nowMs = now();

    const pairSnap = await db.doc(`pairings/${pairId}`).get();
    if (!pairSnap.exists) throw new HttpsError('permission-denied', 'RELATIONSHIP_UNAVAILABLE');
    const pairing = pairSnap.data();
    const monitoredUid = activePartner(pairing, uid);
    if (!monitoredUid) throw new HttpsError('permission-denied', 'RELATIONSHIP_UNAVAILABLE');

    if (!consentEnabled(pairing, monitoredUid)) {
      return {
        pairId,
        consent: false,
        connection: 'NO_DATA',
        bucket: 'NO_DATA',
      };
    }

    const states = await db.collection('dms_install_state')
      .where('uid', '==', monitoredUid)
      .limit(MAX_INSTALL_STATES_PER_USER)
      .get();
    const receipts = states.docs
      .map((doc) => Number(doc.get('last_received_at_ms')) || 0)
      .filter((ms) => ms > 0);
    if (receipts.length === 0) {
      return { pairId, consent: true, connection: 'NO_DATA', bucket: 'NO_DATA' };
    }

    // The latest ENDED ingestion outage that could overlap any of this user's deadlines
    // (start <= last + 6h covers only those whose deadline the outage could have consumed).
    const earliestReceipt = Math.min(...receipts);
    const outages = await db.collection('dms_ingestion_outages')
      .where('ended_at_ms', '>=', earliestReceipt)
      .orderBy('ended_at_ms', 'desc')
      .limit(1)
      .get();
    let outage = null;
    if (!outages.empty) {
      const data = outages.docs[0].data();
      const start = Number(data.started_at_ms) || 0;
      const end = Number(data.ended_at_ms) || 0;
      if (end > 0 && start > 0) outage = { start, end };
    }

    const effectiveLast = Math.max(
      ...receipts.map((last) => {
        const deadline = last + PARTNER_PUSH_AFTER_MS;
        const overlaps = outage && outage.start <= deadline && outage.end >= last;
        return overlaps ? Math.max(last, outage.end) : last;
      }),
    );

    const { connection, bucket } = deriveAvailability(effectiveLast, nowMs);
    return { pairId, consent: true, connection, bucket };
  };
}

/**
 * Operator-recorded heartbeat-ingestion outage (§4, §8.11). Starting opens a record; a later
 * call with `outageId` closes it. The deadline worker holds dispatch while one is open and
 * rebases affected installs to `max(last_received_at, outage_end) + threshold` after it ends.
 * Scheduler-only outages are NOT recorded here — they never change a receipt time.
 *
 * Authority is the deployment-configured operator list (`config/dms.operator_uids`), fail
 * closed. App Check carries the client boundary; this gate is the human boundary.
 */
function createRecordIngestionOutageHandler({ db, now = () => Date.now() }) {
  return async function recordIngestionOutageHandler(request) {
    const uid = requireAuth(request);
    const configSnap = await db.doc(DMS_CONFIG_DOC).get();
    if (!isOperator(configSnap.exists ? configSnap.data() : null, uid)) {
      logger.warn('recordIngestionOutage rejected', { reason: 'NOT_OPERATOR' });
      throw new HttpsError('permission-denied', 'NOT_OPERATOR');
    }
    const data = request.data || {};
    const nowMs = now();

    if (typeof data.outageId === 'string' && data.outageId.length > 0) {
      const ref = db.doc(`dms_ingestion_outages/${data.outageId}`);
      return db.runTransaction(async (tx) => {
        const snap = await tx.get(ref);
        if (!snap.exists) throw new HttpsError('not-found', 'OUTAGE_NOT_FOUND');
        const existing = snap.data();
        if (Number(existing.ended_at_ms) > 0) {
          return { outageId: data.outageId, endedAtMs: existing.ended_at_ms, changed: false };
        }
        const endedAtMs = Number.isInteger(data.endedAtMs) ? data.endedAtMs : nowMs;
        if (!isValidOutageInterval(Number(existing.started_at_ms), endedAtMs)) {
          throw new HttpsError('invalid-argument', 'INVALID_INTERVAL');
        }
        tx.update(ref, { ended_at_ms: endedAtMs, closed_at_ms: nowMs, closed_by: uid });
        logger.info('ingestion outage closed', { outageId: data.outageId, endedAtMs });
        return { outageId: data.outageId, endedAtMs, changed: true };
      });
    }

    const service = typeof data.service === 'string' && data.service.length > 0
      ? data.service.slice(0, 64)
      : 'submitHeartbeat';
    const region = typeof data.region === 'string' && data.region.length > 0
      ? data.region.slice(0, 64)
      : REGION;
    const startedAtMs = Number.isInteger(data.startedAtMs) ? data.startedAtMs : nowMs;
    const endedAtMs = Number.isInteger(data.endedAtMs) ? data.endedAtMs : null;
    const note = typeof data.note === 'string' ? data.note.slice(0, 512) : null;
    if (!isValidOutageInterval(startedAtMs, endedAtMs)) {
      throw new HttpsError('invalid-argument', 'INVALID_INTERVAL');
    }

    const open = await db.collection('dms_ingestion_outages')
      .where('ended_at_ms', '==', null)
      .limit(1)
      .get();
    if (!open.empty) {
      // One open record at a time: two overlapping "open" intervals make affected installs
      // ambiguous, and the compensation is defined against one relevant outage end.
      throw new HttpsError('failed-precondition', 'OUTAGE_ALREADY_OPEN');
    }

    const ref = db.collection('dms_ingestion_outages').doc();
    await ref.set({
      service,
      region,
      started_at_ms: startedAtMs,
      ended_at_ms: endedAtMs,
      note,
      created_by: uid,
      created_at_ms: nowMs,
      closed_at_ms: endedAtMs !== null ? nowMs : null,
      closed_by: endedAtMs !== null ? uid : null,
    });
    logger.info('ingestion outage recorded', {
      outageId: ref.id,
      service,
      region,
      ongoing: endedAtMs === null,
    });
    return { outageId: ref.id, startedAtMs, endedAtMs, ongoing: endedAtMs === null };
  };
}

// Lazy `getFirestore()` per invocation: test processes require this module before any admin
// app exists (same reason as the schedule wrapper in dms-processor.js).
const CALLABLE_OPTIONS = Object.freeze({
  region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 15,
});
const OUTAGE_OPTIONS = Object.freeze({
  region: REGION, enforceAppCheck: true, maxInstances: 2, timeoutSeconds: 15,
});

function deployedCallable(options, handlerFactory) {
  return onCall(options, (request) => handlerFactory({ db: getFirestore() })(request));
}

exports.setAvailabilityConsent = deployedCallable(
  CALLABLE_OPTIONS,
  createSetAvailabilityConsentHandler,
);
exports.readPartnerStatus = deployedCallable(CALLABLE_OPTIONS, createReadPartnerStatusHandler);
exports.recordIngestionOutage = deployedCallable(
  OUTAGE_OPTIONS,
  createRecordIngestionOutageHandler,
);

exports.createSetAvailabilityConsentHandler = createSetAvailabilityConsentHandler;
exports.createReadPartnerStatusHandler = createReadPartnerStatusHandler;
exports.createRecordIngestionOutageHandler = createRecordIngestionOutageHandler;
