'use strict';
// §8.4 partner event ingress. One callable, one batch of at most 20 signed events, one
// explicit result per event. Per event, in order: schema, owner/installation binding,
// duplicate-digest comparison (only a byte-identical original attempt is ALREADY_ACCEPTED),
// active key binding, signature, current partnership consent and recipient key revision,
// then one Firestore transaction keyed by (uid, installationId, eventId) that writes the
// immutable ledger record and either the per-installation mailbox + notification job or a
// durable fanout task. An existing event is checked before the key/consent checks so a
// lost-response retry of an accepted event never asks the client to regenerate ciphertext.
//
// Authorization is server-owned throughout: the recipient uid comes from the registered
// recipient-key document (never from the wire), and the pair is re-read inside the
// transaction. RECIPIENT_KEY_STALE is a definitive no-effect result carrying the currently
// authorized recipient keys, and is the only result besides ACCEPTED/ALREADY_ACCEPTED that
// is safe to act on; a transient failure is RETRYABLE and must be retried with the same
// signed bytes.

const { getFirestore } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');

const { consumeLimit } = require('./rate-window');
const { pairIdOf } = require('./pair-ids');
const {
  COLLECTIONS,
  RESULT,
  KIND,
  MAX_BATCH_EVENTS,
  MAX_BATCH_BYTES,
  validateSubmission,
  verifyEventSignature,
  digest,
  canonicalJson,
} = require('./partner-events');

const REGION = 'us-central1';

const INGEST_WINDOW_MS = 60 * 60 * 1000;
const INGEST_LIMIT_MAX = 240;

// R08 (plan line 96): the acknowledging transaction must hold the ledger plus every
// mailbox/notification record, or a durable fanout task. Firestore caps a transaction at
// 500 writes, so up to 20 recipients stay inline (41 writes); above that one
// partnerFanoutTasks record is the durable work record, materialized by Phase 5. The 200
// cap is a protocol bound, not a consent decision (§16 open question 5).
const MAX_INLINE_RECIPIENTS = 20;
const MAX_RECIPIENTS = 200;

// §8.6: BLOCK_ATTEMPT pushes coalesce per recipient installation in a rolling 60 s window.
// The group id is shared with the Phase 5 notifier so ingest and fanout materialization
// stamp identical groups.
const { blockGroupId } = require('./partner-notify-logic');

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const REQUEST_KEYS = ['installationId', 'events'];

/**
 * Currently authorized recipient keys for `uid`: every ACTIVE recipient key of every
 * active partner. Two single-field queries, no composite index needed.
 */
async function currentAuthorizedKeys(tx, db, uid) {
  const pairings = await tx.get(db.collection('pairings').where('members', 'array-contains', uid));
  const keys = [];
  for (const doc of pairings.docs) {
    if (doc.get('status') !== 'active') continue;
    const partnerUid = doc.get('uidA') === uid ? doc.get('uidB') : doc.get('uidA');
    if (typeof partnerUid !== 'string' || partnerUid === uid) continue;
    const recipientKeys = await tx.get(
      db.collection(COLLECTIONS.recipientKeys).where('ownerUid', '==', partnerUid),
    );
    for (const keyDoc of recipientKeys.docs) {
      if (keyDoc.get('status') !== 'ACTIVE') continue;
      keys.push({
        keyId: keyDoc.id,
        revision: keyDoc.get('revision'),
        installationId: keyDoc.get('installationId'),
      });
    }
  }
  return keys;
}

/** §8.4 contact: at most once per accepted batch, only when the liveness row is this device. */
async function touchContact(db, uid, installationId, at) {
  const ref = db.doc(`${COLLECTIONS.deviceLiveness}/${installationId}`);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || snap.get('ownerUid') !== uid) return;
    const last = snap.get('lastAcceptedContactAt');
    if (typeof last === 'number' && last >= at) return;
    tx.set(ref, { lastAcceptedContactAt: at }, { merge: true });
  });
}

/**
 * One event, one transaction. Never throws for an expected outcome — every path returns a
 * result object so a partially accepted batch reports per-event codes. Only an unexpected
 * Firestore failure propagates, and the caller maps it to RETRYABLE.
 */
async function processEvent({ db, uid, installationId, wire, at }) {
  const issue = validateSubmission(wire);
  if (issue) return { code: RESULT.PERMANENT_REJECTED, issue };

  const event = wire.event;
  if (event.ownerUid !== uid) {
    return { code: RESULT.PERMANENT_REJECTED, reason: 'OWNER_UID_MISMATCH' };
  }
  if (event.installationId !== installationId) {
    return { code: RESULT.PERMANENT_REJECTED, reason: 'INSTALLATION_MISMATCH' };
  }

  const eventDigest = digest(event);
  const regRef = db.doc(`${COLLECTIONS.deviceRegistrations}/${uid}:${installationId}`);
  const ledgerRef = db.doc(
    `${COLLECTIONS.events}/${uid}/installations/${installationId}/events/${event.eventId}`,
  );

  return db.runTransaction(async (tx) => {
    const [regSnap, ledgerSnap] = await Promise.all([tx.get(regRef), tx.get(ledgerRef)]);

    // An existing event is compared first (§8.4): an accepted event stays accepted even if
    // the key has rotated since; a different digest under the same ID is an attack, never
    // a retry.
    if (ledgerSnap.exists) {
      return ledgerSnap.get('digestSha256') === eventDigest
        ? { code: RESULT.ALREADY_ACCEPTED }
        : {
          code: RESULT.PERMANENT_REJECTED,
          reason: 'EVENT_ID_CONFLICT',
          security: 'DUPLICATE_EVENT_ID_CONFLICT',
        };
    }

    const registration = regSnap.exists ? regSnap.data() : null;
    if (!registration || registration.status !== 'ACTIVE' ||
        registration.signingKeyId !== wire.keyId) {
      return { code: RESULT.REENROLL_REQUIRED };
    }
    if (!verifyEventSignature(event, wire.signature, registration.signingSpkiDerBase64)) {
      return {
        code: RESULT.PERMANENT_REJECTED,
        reason: 'SIGNATURE_INVALID',
        security: 'EVENT_SIGNATURE_INVALID',
      };
    }

    const keySnaps = await Promise.all(event.recipientEnvelopes.map(
      (envelope) => tx.get(
        db.doc(`${COLLECTIONS.recipientKeys}/${envelope.recipientEncryptionKeyId}`),
      ),
    ));
    const recipientUids = new Set();
    for (const snap of keySnaps) {
      if (snap.exists && snap.get('status') === 'ACTIVE' &&
          typeof snap.get('ownerUid') === 'string' && snap.get('ownerUid') !== uid) {
        recipientUids.add(snap.get('ownerUid'));
      }
    }
    const pairingSnaps = new Map();
    for (const recipientUid of recipientUids) {
      pairingSnaps.set(
        recipientUid,
        await tx.get(db.doc(`pairings/${pairIdOf(uid, recipientUid)}`)),
      );
    }

    const entries = [];
    let stale = false;
    event.recipientEnvelopes.forEach((envelope, index) => {
      const keySnap = keySnaps[index];
      if (!keySnap.exists || keySnap.get('status') !== 'ACTIVE') {
        stale = true;
        return;
      }
      const recipientUid = keySnap.get('ownerUid');
      const pairing = pairingSnaps.get(recipientUid);
      // A revoked partnership is a stale registry view too: the client must re-encrypt for
      // its remaining partners, which is exactly RECIPIENT_KEY_STALE's contract. Using
      // PERMANENT_REJECTED here would strand the event with no allowed regeneration path.
      if (recipientUid === uid || !pairing || !pairing.exists || pairing.get('status') !== 'active') {
        stale = true;
        return;
      }
      entries.push({ envelope, key: keySnap.data(), recipientUid });
    });

    if (stale) {
      return {
        code: RESULT.RECIPIENT_KEY_STALE,
        currentRecipientKeys: await currentAuthorizedKeys(tx, db, uid),
      };
    }
    if (entries.length > MAX_RECIPIENTS) {
      return { code: RESULT.PERMANENT_REJECTED, reason: 'RECIPIENTS_TOO_MANY' };
    }

    tx.set(ledgerRef, {
      eventId: event.eventId,
      ownerUid: uid,
      installationId,
      sequence: event.sequence,
      kind: event.kind,
      deviceOccurredAtMs: event.deviceOccurredAtMs,
      safeMetadata: event.metadata || {},
      recipientKeyIds: entries.map((entry) => entry.envelope.recipientEncryptionKeyId),
      digestSha256: eventDigest,
      signingKeyId: wire.keyId,
      signature: wire.signature,
      receivedAt: at,
      status: 'ACCEPTED',
    }, { merge: false });

    if (entries.length > MAX_INLINE_RECIPIENTS) {
      tx.set(db.doc(`${COLLECTIONS.fanoutTasks}/${event.eventId}`), {
        eventId: event.eventId,
        ownerUid: uid,
        installationId,
        kind: event.kind,
        sequence: event.sequence,
        recipients: entries.map((entry) => ({
          recipientUid: entry.recipientUid,
          recipientInstallationId: entry.key.installationId,
          recipientKeyId: entry.envelope.recipientEncryptionKeyId,
          recipientKeyRevision: entry.key.revision,
          payload: entry.envelope.payload,
          encoding: entry.envelope.encoding,
          pairId: pairIdOf(uid, entry.recipientUid),
        })),
        status: 'PENDING',
        createdAt: at,
      }, { merge: false });
      return { code: RESULT.ACCEPTED, fanout: 'DEFERRED' };
    }

    for (const entry of entries) {
      const recipientInstallationId = entry.key.installationId;
      // §8.6: one mailbox record ↔ one notification job, both keyed by
      // (eventId, recipient installation). A recipient account with two enrolled devices
      // gets two mailbox records, each readable only by the device holding that key.
      tx.set(
        db.doc(`${COLLECTIONS.mailboxes}/${entry.recipientUid}/messages/${event.eventId}__${recipientInstallationId}`),
        {
          ownerUid: uid,
          // §8.2 AAD inputs: the recipient needs the sender's installation id and kind to
          // reconstruct PartnerEventEnvelopeAad. Without them the payload is undecryptable.
          installationId,
          kind: event.kind,
          eventId: event.eventId,
          // §13.3: the recipient places a staged event in the shared sequence space; the
          // body cannot carry its own eventually-assigned outbox sequence.
          sequence: event.sequence,
          recipientInstallationId,
          recipientKeyId: entry.envelope.recipientEncryptionKeyId,
          recipientKeyRevision: entry.key.revision,
          payload: entry.envelope.payload,
          encoding: entry.envelope.encoding,
          pairId: pairIdOf(uid, entry.recipientUid),
          createdAt: at,
        },
        { merge: false },
      );
      tx.set(
        db.doc(`${COLLECTIONS.notificationOutbox}/${event.eventId}__${recipientInstallationId}`),
        {
          eventId: event.eventId,
          recipientUid: entry.recipientUid,
          recipientInstallationId,
          kind: event.kind,
          groupId: event.kind === KIND.BLOCK_ATTEMPT
            ? blockGroupId(entry.recipientUid, recipientInstallationId, at)
            : null,
          status: 'PENDING',
          attempts: 0,
          nextAttemptAt: at,
          leaseUntil: null,
          sentAt: null,
          createdAt: at,
        },
        { merge: false },
      );
    }
    return { code: RESULT.ACCEPTED };
  });
}

function createIngestPartnerEventsHandler({ db, now = () => Date.now() }) {
  return async function ingestPartnerEventsHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    // §8.7 `delivery_ingest_v1_enabled`: paused ingest is retryable maintenance, never a
    // dropped event. The client keeps its exact signed bytes and resubmits unchanged, so
    // nothing is acknowledged and nothing is lost while paused. Unset/true = enabled.
    const featuresSnap = await db.doc('config/features').get();
    if (featuresSnap.exists && featuresSnap.data().delivery_ingest_v1_enabled === false) {
      throw new HttpsError('unavailable', 'MAINTENANCE');
    }

    const data = request.data;
    if (data === null || typeof data !== 'object' || Array.isArray(data)) {
      throw new HttpsError('invalid-argument', 'REQUEST_INVALID');
    }
    if (Object.keys(data).some((key) => !REQUEST_KEYS.includes(key))) {
      throw new HttpsError('invalid-argument', 'REQUEST_INVALID');
    }
    if (typeof data.installationId !== 'string' || !UUID_V4.test(data.installationId)) {
      throw new HttpsError('invalid-argument', 'INSTALLATION_ID_INVALID');
    }
    const events = data.events;
    if (!Array.isArray(events) || events.length === 0) {
      throw new HttpsError('invalid-argument', 'BATCH_INVALID');
    }
    if (events.length > MAX_BATCH_EVENTS) {
      throw new HttpsError('invalid-argument', 'TOO_LARGE');
    }
    // §8.2 body ceiling. Malformed entries are judged per event below; this only bounds the
    // bytes an otherwise-shaped batch may carry.
    let totalBytes = 0;
    for (const wire of events) {
      if (wire === null || typeof wire !== 'object' || wire.event === null ||
          typeof wire.event !== 'object') continue;
      try {
        totalBytes += Buffer.byteLength(canonicalJson(wire.event), 'utf8');
      } catch {
        // canonicalJson refuses values a JSON callable cannot have carried anyway.
      }
    }
    if (totalBytes > MAX_BATCH_BYTES) throw new HttpsError('invalid-argument', 'TOO_LARGE');

    const deletion = await db.doc(`deletionJobs/${auth.uid}`).get();
    if (deletion.exists) throw new HttpsError('permission-denied', 'ACCOUNT_DELETING');

    const at = now();
    const allowed = await consumeLimit(
      db, `rate-limits/partnerIngest_${auth.uid}_${data.installationId}`,
      INGEST_LIMIT_MAX, INGEST_WINDOW_MS, at,
    );
    if (!allowed) throw new HttpsError('resource-exhausted', 'RATE_LIMITED');

    const results = [];
    let accepted = 0;
    for (let index = 0; index < events.length; index += 1) {
      const wire = events[index];
      let result;
      try {
        result = await processEvent({
          db, uid: auth.uid, installationId: data.installationId, wire, at,
        });
      } catch (e) {
        logger.warn('ingestPartnerEvents transient failure', {
          reason: e && e.message ? e.message : 'unknown',
        });
        result = { code: RESULT.RETRYABLE };
      }
      if (result.code === RESULT.ACCEPTED) accepted += 1;
      if (result.security) {
        logger.warn('ingestPartnerEvents security rejection', {
          reason: result.security,
          uid: auth.uid,
          installationId: data.installationId,
          eventId: wire && wire.event && typeof wire.event.eventId === 'string'
            ? wire.event.eventId
            : null,
        });
      }
      const item = { index, code: result.code };
      if (wire !== null && typeof wire === 'object' && wire.event !== null &&
          typeof wire.event === 'object' && typeof wire.event.eventId === 'string') {
        item.eventId = wire.event.eventId;
      }
      if (result.issue) item.issue = result.issue;
      if (result.reason) item.reason = result.reason;
      if (result.currentRecipientKeys) item.currentRecipientKeys = result.currentRecipientKeys;
      if (result.fanout) item.fanout = result.fanout;
      results.push(item);
    }

    if (accepted > 0) {
      try {
        await touchContact(db, auth.uid, data.installationId, at);
      } catch (e) {
        // Contact is advisory; the events are already durably committed.
        logger.warn('ingestPartnerEvents contact update failed', {
          reason: e && e.message ? e.message : 'unknown',
        });
      }
    }

    logger.info('ingestPartnerEvents', {
      count: events.length,
      accepted,
      installationId: data.installationId,
    });
    return { results };
  };
}

function createIngestPartnerEvents({ db = getFirestore(), now } = {}) {
  return onCall(
    {
      region: REGION,
      enforceAppCheck: true,
      maxInstances: 10,
      // One transaction per event, at most 20.
      timeoutSeconds: 30,
    },
    createIngestPartnerEventsHandler(now ? { db, now } : { db }),
  );
}

exports.createIngestPartnerEvents = createIngestPartnerEvents;
exports.createIngestPartnerEventsHandler = createIngestPartnerEventsHandler;
exports.MAX_INLINE_RECIPIENTS = MAX_INLINE_RECIPIENTS;
exports.MAX_RECIPIENTS = MAX_RECIPIENTS;
