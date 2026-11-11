'use strict';
// `submitDeviceContact` + the five-minute liveness evaluator (partner event-delivery plan §7
// Phase 6 / §8.7). The signed contact is the only liveness authority; this module never runs a
// second dead-man's switch. An accepted contact bridges into the legacy `dms_install_state`
// liveness fields (never its counter) so the existing six-hour DMS notices keep working while
// the `device_liveness_alerts_v1_enabled` rollout flag is off.
//
// Every decision timestamp here is a server receipt time from the injected clock. The device's
// self-report is stored verbatim as an allowlisted snapshot and is never treated as proof of
// the underlying system state (§8.7: "server-authoritative reports of self-reported state").
//
// Idempotency: contacts are latest-wins on a separate persisted counter (replays write
// nothing); probes and alerts are claimed in transactions against server timestamps, so
// overlapping scheduler runs converge instead of duplicating.

const crypto = require('node:crypto');
const { getFirestore, Timestamp, FieldValue } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { logger } = require('firebase-functions');

const { canonicalJson } = require('./pairing-guards');
const { p256Spki } = require('./partner-registration');
const { stateDocId } = require('./heartbeat-logic');
const {
  consentingPartner,
  noticeDocId,
  normalizeConfig,
  NOTICE_PENDING,
  NOTICE_DISPATCHING,
  NOTICE_SENT,
  NOTICE_SUPPRESSED,
  NOTICE_AVAILABILITY,
  NOTICE_RECOVERY,
  LATE_AFTER_MS,
  PARTNER_PUSH_AFTER_MS,
} = require('./dms-logic');
const {
  dispatchOutbox,
  requeueExpiredClaims,
  sendDmsPush,
} = require('./dms-processor');
const { isTokenInvalid } = require('./dispatch');
const { deferWhileIndexBuilds } = require('./index-build-guard');
const {
  PROBE_AFTER_MS,
  PROBE_WINDOW_MS,
  ALERT_GRACE_MS,
  LIVENESS_COLLECTION,
  livenessDocId,
  probePriority,
  STATUS_ACTIVE,
  STATUS_CONTACT_STALE,
  STATUS_UNAVAILABLE,
  NOTICE_DEVICE_UNAVAILABLE,
  NOTICE_DEVICE_RECONNECTED,
  NOTICE_ENFORCEMENT_DEGRADED,
  NOTICE_ENFORCEMENT_RESTORED,
  validateContact,
  contactMessage,
  storedEnforcement,
  contactAcceptance,
  evaluationDecision,
  fleetIncident,
} = require('./device-liveness-logic');

const REGION = 'us-central1';
const LIVENESS = LIVENESS_COLLECTION;
const REGISTRATIONS = 'partnerDeviceSigningRegistrations';
const OUTBOX = 'dms_notification_outbox';
const FEATURES_DOC = 'config/features';
const DMS_CONFIG_DOC = 'config/dms';
const INCIDENT_DOC = 'config/device_liveness_incident';
const ANDROID_PACKAGE = 'com.example.myexodus';
const PROBE_COLLAPSE_KEY = 'liveness-probe';
const PROBE_TTL_MS = PROBE_WINDOW_MS;
const MAX_RELATIONSHIPS_PER_INSTALL = 50;
const INCIDENT_MIN_INSTALLATIONS = 5;
const SIGNATURE = /^[A-Za-z0-9_-]{64,128}$/;

function errorCodeOf(error) {
  const code = error && typeof error.code === 'string' ? error.code : '';
  return code ? code.slice(0, 64) : 'UNKNOWN';
}

/** Registration uses this exact parser; anything not EC P-256 is not a signing key. */
function verifyContactSignature({ signingSpkiDerBase64, message, signature }) {
  try {
    const signing = p256Spki(signingSpkiDerBase64);
    if (!signing) return false;
    return crypto.verify(
      'sha256', message, signing.key, Buffer.from(signature, 'base64url'),
    );
  } catch {
    return false;
  }
}

function contactSignatureMessage(contact) {
  return Buffer.from(canonicalJson(contactMessage(contact)), 'utf8');
}

function livenessRefFor(db, uid, installationId) {
  return db.doc(`${LIVENESS}/${livenessDocId(uid, installationId)}`);
}

function livenessNoticeRow({
  installationId, ownerUid, relationshipId, recipientUid, generation, noticeType, nowMs,
}) {
  return {
    install_id: installationId,
    // Same composite id as the registration document, so a device re-registering under
    // another account can never inherit the prior owner's episode.
    state_doc_id: livenessDocId(ownerUid, installationId),
    monitored_uid: ownerUid,
    relationship_id: relationshipId,
    recipient_uid: recipientUid,
    outage_generation: generation,
    notice_type: noticeType,
    status: NOTICE_PENDING,
    attempts: 0,
    next_attempt_at_ms: nowMs,
    created_at_ms: nowMs,
  };
}

async function consentingRelationships(tx, db, uid) {
  const snap = await tx.get(
    db.collection('pairings')
      .where('members', 'array-contains', uid)
      .limit(MAX_RELATIONSHIPS_PER_INSTALL),
  );
  return snap.docs
    .map((doc) => ({ relationshipId: doc.id, partner: consentingPartner(doc.data(), uid) }))
    .filter((relationship) => relationship.partner != null);
}

/**
 * Handles the recovery fan-out the legacy accepted-heartbeat path performs when a previously
 * notified install comes back. Reads only — the caller writes after every read is done.
 */
async function legacyRecoveryCandidates(tx, db, uid, installationId, generation) {
  const relationships = await consentingRelationships(tx, db, uid);
  const candidates = [];
  for (const relationship of relationships) {
    const noticeRef = db.doc(
      `${OUTBOX}/${noticeDocId(installationId, relationship.relationshipId, generation, NOTICE_AVAILABILITY)}`,
    );
    const recoveryRef = db.doc(
      `${OUTBOX}/${noticeDocId(installationId, relationship.relationshipId, generation, NOTICE_RECOVERY)}`,
    );
    const [noticeSnap, recoverySnap] = await Promise.all([tx.get(noticeRef), tx.get(recoveryRef)]);
    candidates.push({ relationship, noticeRef, recoveryRef, noticeSnap, recoverySnap });
  }
  return candidates;
}

/**
 * Test seam (house pattern): injected db/clock, driven directly by the emulator suite.
 */
function createSubmitDeviceContactHandler({ db, now = () => Date.now() }) {
  return async function submitDeviceContactHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const data = request.data;
    const { error, contact } = validateContact(data);
    if (error) {
      logger.warn('submitDeviceContact rejected', { reason: error });
      throw new HttpsError('invalid-argument', error);
    }
    if (contact.ownerUid !== auth.uid) {
      // Unauthenticated installation/UID mismatch: rejected without touching liveness.
      logger.warn('submitDeviceContact rejected', { reason: 'OWNER_UID_MISMATCH' });
      throw new HttpsError('permission-denied', 'OWNER_UID_MISMATCH');
    }
    const signature = data.contactSignature;
    if (typeof signature !== 'string' || !SIGNATURE.test(signature)) {
      throw new HttpsError('invalid-argument', 'SIGNATURE_INVALID');
    }

    const regRef = db.doc(`${REGISTRATIONS}/${auth.uid}:${contact.installationId}`);
    const regSnap = await regRef.get();
    const registration = regSnap.exists ? regSnap.data() : null;
    if (!registration || registration.status !== 'ACTIVE' ||
        registration.ownerUid !== auth.uid ||
        registration.installationId !== contact.installationId ||
        registration.signingKeyId !== contact.keyId) {
      throw new HttpsError('permission-denied', 'INSTALL_NOT_REGISTERED');
    }
    if (!verifyContactSignature({
      signingSpkiDerBase64: registration.signingSpkiDerBase64,
      message: contactSignatureMessage(contact),
      signature,
    })) {
      logger.warn('submitDeviceContact rejected', { reason: 'SIGNATURE_MISMATCH' });
      throw new HttpsError('permission-denied', 'SIGNATURE_MISMATCH');
    }

    const nowMs = now();
    const livenessRef = livenessRefFor(db, auth.uid, contact.installationId);
    const legacyRef = db.doc(`dms_install_state/${stateDocId(auth.uid, contact.installationId)}`);

    return db.runTransaction(async (tx) => {
      const [deletionSnap, freshRegSnap, livenessSnap, legacySnap] = await Promise.all([
        tx.get(db.doc(`deletionJobs/${auth.uid}`)),
        tx.get(regRef),
        tx.get(livenessRef),
        tx.get(legacyRef),
      ]);
      if (deletionSnap.exists) throw new HttpsError('permission-denied', 'ACCOUNT_DELETING');
      const freshReg = freshRegSnap.exists ? freshRegSnap.data() : null;
      if (!freshReg || freshReg.status !== 'ACTIVE' || freshReg.signingKeyId !== contact.keyId) {
        throw new HttpsError('permission-denied', 'INSTALL_NOT_REGISTERED');
      }

      const previous = livenessSnap.exists ? livenessSnap.data() : null;
      const decision = contactAcceptance(previous, contact, nowMs);
      if (decision.outcome === 'STALE') {
        return { outcome: 'STALE', serverCounter: decision.serverCounter };
      }
      if (decision.outcome === 'INVALID_PROBE') {
        logger.info('submitDeviceContact invalid probe', { installationId: contact.installationId });
        return { outcome: 'INVALID_PROBE' };
      }

      const legacy = legacySnap.exists ? legacySnap.data() : null;
      const wasNotified = legacy != null && legacy.notice_state === 'NOTIFIED';
      const outageGeneration = legacy ? Number(legacy.outage_generation) || 0 : 0;
      const needsFanout = Boolean(
        decision.enforcementNotice || decision.reconnectNotice || wasNotified,
      );
      const relationships = needsFanout
        ? await consentingRelationships(tx, db, auth.uid)
        : [];
      const recoveryCandidates = wasNotified
        ? await legacyRecoveryCandidates(tx, db, auth.uid, contact.installationId, outageGeneration)
        : [];

      // --- Reads are done; every write below is part of this one transaction. -------------
      tx.set(livenessRef, {
        ownerUid: auth.uid,
        installationId: contact.installationId,
        enrolledAtMs: previous && previous.enrolledAtMs ? previous.enrolledAtMs : nowMs,
        lastAcceptedContactAtMs: nowMs,
        lastHeartbeatCounter: contact.heartbeatCounter,
        lastDeviceReportedAtMs: contact.deviceReportedAtMs,
        lastObservedEnforcement: storedEnforcement(contact.enforcement, nowMs),
        enforcementState: decision.enforcementState,
        missingCapabilities: decision.missingCapabilities,
        status: STATUS_ACTIVE,
        outageEpisodeId: null,
        pendingProbeId: null,
        probeSentAtMs: null,
        probeDeadlineAtMs: null,
        alertedAtMs: null,
        restoredAtMs: decision.reconnectNotice
          ? nowMs
          : (previous && previous.restoredAtMs) || null,
        suppressionReason: null,
        earliestAlertAtMs: decision.earliestAlertAtMs,
        updatedAtMs: nowMs,
      }, { merge: true });

      let outboxCreated = 0;
      const enqueue = (noticeType, generation) => {
        for (const relationship of relationships) {
          tx.set(
            db.doc(`${OUTBOX}/${noticeDocId(
              contact.installationId,
              relationship.relationshipId,
              generation,
              noticeType,
            )}`),
            livenessNoticeRow({
              installationId: contact.installationId,
              ownerUid: auth.uid,
              relationshipId: relationship.relationshipId,
              recipientUid: relationship.partner.recipientUid,
              generation,
              noticeType,
              nowMs,
            }),
          );
          outboxCreated += 1;
        }
      };
      if (decision.enforcementNotice) {
        // Keyed on the accepted counter: one deterministic row per transition contact.
        enqueue(decision.enforcementNotice, contact.heartbeatCounter);
      }
      if (decision.reconnectNotice) {
        enqueue(decision.reconnectNotice, decision.outageEpisodeId);
      }

      if (wasNotified) {
        // Mirrors heartbeat.js §4: a pending six-hour notice must not leave after the device
        // came back; a possibly-sent one owes exactly one recovery row per relationship.
        for (const candidate of recoveryCandidates) {
          const notice = candidate.noticeSnap.exists ? candidate.noticeSnap.data() : null;
          const status = notice ? notice.status : null;
          if (status === NOTICE_PENDING) {
            tx.update(candidate.noticeRef, {
              status: NOTICE_SUPPRESSED,
              suppressed_reason: 'RECOVERED_BEFORE_DISPATCH',
              updated_at_ms: nowMs,
            });
            continue;
          }
          const possiblySent = status === NOTICE_SENT ||
            (status === NOTICE_DISPATCHING && Number(notice.lease_until_ms) > nowMs);
          if (!possiblySent || candidate.recoverySnap.exists) continue;
          tx.set(candidate.recoveryRef, {
            install_id: contact.installationId,
            state_doc_id: legacyRef.id,
            monitored_uid: auth.uid,
            relationship_id: candidate.relationship.relationshipId,
            recipient_uid: candidate.relationship.partner.recipientUid,
            outage_generation: outageGeneration,
            notice_type: NOTICE_RECOVERY,
            status: NOTICE_PENDING,
            attempts: 0,
            next_attempt_at_ms: nowMs,
            created_at_ms: nowMs,
          });
          outboxCreated += 1;
        }
      }

      // Legacy DMS bridge: the signed contact is proof of life for the existing six-hour
      // notice path too, so the flag-off window does not starve it. The legacy counter
      // (`last_counter`) is a different namespace and is deliberately untouched.
      const legacyUpdate = {
        uid: auth.uid,
        install_id: contact.installationId,
        liveness_generation: legacy ? (Number(legacy.liveness_generation) || 0) + 1 : 1,
        last_received_at: Timestamp.fromMillis(nowMs),
        last_received_at_ms: nowMs,
        next_deadline_at: Timestamp.fromMillis(nowMs + PARTNER_PUSH_AFTER_MS),
        late_deadline_at: Timestamp.fromMillis(nowMs + LATE_AFTER_MS),
        last_notified_generation: legacy ? legacy.last_notified_generation || 0 : 0,
        outage_generation: outageGeneration,
        notice_state: 'NONE',
        updated_at: Timestamp.fromMillis(nowMs),
      };
      if (wasNotified) legacyUpdate.recovered_at_ms = nowMs;
      tx.set(legacyRef, legacyUpdate, { merge: true });

      return {
        outcome: 'ACCEPTED',
        serverCounter: decision.serverCounter,
        enforcementState: decision.enforcementState,
        enforcementNotice: decision.enforcementNotice,
        reconnectNotice: decision.reconnectNotice,
        outboxCreated,
      };
    });
  };
}

function createSubmitDeviceContact({ db = getFirestore(), now } = {}) {
  const handler = createSubmitDeviceContactHandler(now ? { db, now } : { db });
  return onCall(
    {
      region: REGION,
      // Same App Check boundary as every server-state writer: liveness gates partner alerts.
      enforceAppCheck: true,
      maxInstances: 10,
      timeoutSeconds: 15,
    },
    handler,
  );
}

// --- five-minute evaluator -----------------------------------------------------------------

function buildContactProbeMessage(installation, probeId, priority) {
  return {
    token: installation.token,
    data: { v: '1', type: 'LIVENESS_PROBE', probeId },
    android: {
      priority,
      ttl: PROBE_TTL_MS,
      collapseKey: PROBE_COLLAPSE_KEY,
      restrictedPackageName: ANDROID_PACKAGE,
    },
  };
}

/** One attempt, one installation. Data-only: the OS shows nothing unless the app callback
 *  chooses its own generic status notification (§8.7). */
async function sendContactProbePush({ installation, probeId, priority }) {
  const { getMessaging } = require('firebase-admin/messaging');
  await getMessaging().send(buildContactProbeMessage(installation, probeId, priority));
}

async function probeTarget(db, uid, installationId) {
  const snap = await db.doc(`users/${uid}/push_installations/${installationId}`).get();
  if (!snap.exists) return null;
  const data = snap.data();
  if (data.provider !== 'fcm' || data.pushEligible === false || !data.token) return null;
  return { id: installationId, token: data.token };
}

async function markPushInstallationStale(db, uid, installationId) {
  await db.doc(`users/${uid}/push_installations/${installationId}`).set({
    stale: true,
    staleAt: FieldValue.serverTimestamp(),
    token: FieldValue.delete(),
  }, { merge: true });
}

async function alertEpisode(tx, db, docRef, state, decision, nowMs) {
  const relationships = await consentingRelationships(tx, db, state.ownerUid);
  tx.update(docRef, {
    status: STATUS_UNAVAILABLE,
    alertedAtMs: nowMs,
    suppressionReason: null,
    updatedAtMs: nowMs,
  });
  // Consent is re-checked here, at the same transaction that publishes the episode: a
  // relationship revoked before dispatch is simply absent from the fan-out.
  for (const relationship of relationships) {
    tx.set(
      db.doc(`${OUTBOX}/${noticeDocId(
        state.installationId,
        relationship.relationshipId,
        decision.outageEpisodeId,
        NOTICE_DEVICE_UNAVAILABLE,
      )}`),
      livenessNoticeRow({
        installationId: state.installationId,
        ownerUid: state.ownerUid,
        relationshipId: relationship.relationshipId,
        recipientUid: relationship.partner.recipientUid,
        generation: decision.outageEpisodeId,
        noticeType: NOTICE_DEVICE_UNAVAILABLE,
        nowMs,
      }),
    );
  }
  return { alerted: true, outboxCreated: relationships.length };
}

/** One installation: claim the evaluator decision, then (only if a probe was allocated) send
 *  the provider wake outside the transaction — FCM never holds a Firestore transaction open. */
async function evaluateOne({ db, dueDoc, nowMs, incidentActive, sendProbe }) {
  const claim = await db.runTransaction(async (tx) => {
    const snap = await tx.get(dueDoc.ref);
    if (!snap.exists) return { skip: true };
    const state = snap.data();
    const decision = evaluationDecision({ state, nowMs, incidentActive });
    if (decision.action === 'ALLOCATE_PROBE') {
      tx.update(dueDoc.ref, {
        status: STATUS_CONTACT_STALE,
        outageEpisodeId: decision.outageEpisodeId,
        pendingProbeId: decision.probeId,
        probeSentAtMs: nowMs,
        probeDeadlineAtMs: nowMs + PROBE_WINDOW_MS,
        lastProbeSentAtMs: nowMs,
        suppressionReason: null,
        updatedAtMs: nowMs,
      });
      return {
        probe: {
          uid: state.ownerUid,
          installationId: state.installationId,
          probeId: decision.probeId,
          priority: decision.probePriority,
        },
      };
    }
    if (decision.action === 'ALERT') {
      const outcome = await alertEpisode(tx, db, dueDoc.ref, state, decision, nowMs);
      return { ...outcome, outageEpisodeId: decision.outageEpisodeId };
    }
    if (decision.action === 'SUPPRESS') {
      if (state.suppressionReason !== 'FLEET_INCIDENT') {
        tx.update(dueDoc.ref, {
          status: STATUS_CONTACT_STALE,
          suppressionReason: 'FLEET_INCIDENT',
          updatedAtMs: nowMs,
        });
      }
      return { suppressed: true };
    }
    return { quiet: true, reason: decision.reason };
  });

  if (!claim.probe) return claim;
  const installation = await probeTarget(db, claim.probe.uid, claim.probe.installationId);
  if (!installation) return { probed: false, reason: 'NO_PUSH_INSTALLATION' };
  try {
    await sendProbe({
      installation,
      probeId: claim.probe.probeId,
      priority: claim.probe.priority,
    });
    return { probed: true, probePriority: claim.probe.priority };
  } catch (error) {
    if (isTokenInvalid('fcm', error)) {
      await markPushInstallationStale(db, claim.probe.uid, claim.probe.installationId);
    }
    logger.warn('liveness probe send failed', {
      installationId: claim.probe.installationId,
      reason: errorCodeOf(error),
    });
    // The probe stays claimed; its 20-minute window elapses and the grace gate remains the
    // alert path. FCM is best-effort (§8.7) — never silently re-probe on every pass.
    return { probed: false, reason: 'PROBE_SEND_FAILED' };
  }
}

async function findOpenIngestionOutage(db) {
  const snap = await db.collection('dms_ingestion_outages')
    .where('ended_at_ms', '==', null)
    .limit(1)
    .get();
  return snap.empty ? null : snap.docs[0].id;
}

/**
 * Test seam. One pass = bounded stale query + bounded outbox dispatch. The legacy
 * `dmsDeadlineWorker` returns early while this flag is on, so this pass also owns dispatch.
 */
function createDeviceLivenessWorkerHandler({
  db,
  now = () => Date.now(),
  sendProbe = sendContactProbePush,
  sendPush = sendDmsPush,
  log = logger,
}) {
  return async function deviceLivenessWorkerHandler() {
    const nowMs = now();
    const featuresSnap = await db.doc(FEATURES_DOC).get();
    const features = featuresSnap.exists ? featuresSnap.data() : {};
    if (features.device_liveness_alerts_v1_enabled !== true) {
      return { disabled: 'LIVENESS_V1_OFF' };
    }
    if (features.partnerAccountabilityEnabled === false) {
      return { disabled: 'PARTNER_ACCOUNTABILITY' };
    }
    const configSnap = await db.doc(DMS_CONFIG_DOC).get();
    const config = normalizeConfig(configSnap.exists ? configSnap.data() : null);
    if (!config.enabled) return { disabled: true };

    const openOutageId = await findOpenIngestionOutage(db);
    const incidentSnap = await db.doc(INCIDENT_DOC).get();
    const incident = incidentSnap.exists ? incidentSnap.data() : {};
    const staleCutoffMs = nowMs - PROBE_AFTER_MS;
    const staleSample = await db.collection(LIVENESS)
      .where('lastAcceptedContactAtMs', '<=', staleCutoffMs)
      .limit(INCIDENT_MIN_INSTALLATIONS)
      .get();
    // §8.7: correlation alone is not an incident; a backend failure must corroborate it.
    const corroboratingFailure = Boolean(openOutageId) || config.dispatchHold;
    const autoIncident = fleetIncident({
      corroboratingFailure,
      staleInstallations: staleSample.size,
      threshold: INCIDENT_MIN_INSTALLATIONS,
    });
    if (autoIncident && incident.active !== true) {
      await db.doc(INCIDENT_DOC).set({
        active: true,
        detectedAtMs: nowMs,
        staleInstallations: staleSample.size,
        corroboratingFailure: openOutageId ? 'INGESTION_OUTAGE' : 'DISPATCH_HOLD',
      }, { merge: true });
      log.error('device liveness fleet incident opened', {
        staleInstallations: staleSample.size,
        corroboratingFailure: openOutageId ? 'INGESTION_OUTAGE' : 'DISPATCH_HOLD',
      });
    } else if (!autoIncident && incident.active === true) {
      await db.doc(INCIDENT_DOC).set({ active: false, recoveredAtMs: nowMs }, { merge: true });
      log.info('device liveness fleet incident recovered');
    }
    // Operator hold is retained independently and is never auto-cleared.
    const incidentActive = autoIncident || incident.operatorHold === true;

    if (openOutageId) {
      // §8.7: an ingestion outage pauses absence-based evaluation only. Explicit enforcement
      // notices were already written by accepted contacts and must still reach the partner.
      const requeued = await requeueExpiredClaims(db, nowMs);
      const dispatched = config.dispatchHold
        ? { sent: 0, suppressed: 0, failed: 0, held: true }
        : await dispatchOutbox({ db, nowMs, config, sendPush });
      log.warn('device liveness held: ingestion outage open', {
        outageId: openOutageId,
        incidentActive,
        ...dispatched,
      });
      return { held: 'INGESTION_OUTAGE', incidentActive, requeued, ...dispatched };
    }

    const due = await db.collection(LIVENESS)
      .where('lastAcceptedContactAtMs', '<=', staleCutoffMs)
      .orderBy('lastAcceptedContactAtMs')
      .limit(config.dueBatch)
      .get();

    let probed = 0;
    let alerted = 0;
    let incidentSuppressed = 0;
    let quiet = 0;
    for (const doc of due.docs) {
      const outcome = await evaluateOne({ db, dueDoc: doc, nowMs, incidentActive, sendProbe });
      if (outcome.probed) {
        probed += 1;
      } else if (outcome.alerted) {
        alerted += 1;
      } else if (outcome.suppressed) {
        incidentSuppressed += 1;
      } else {
        quiet += 1;
      }
    }

    const requeued = await requeueExpiredClaims(db, nowMs);
    const dispatched = config.dispatchHold
      ? { sent: 0, suppressed: 0, failed: 0, held: true }
      : await dispatchOutbox({ db, nowMs, config, sendPush });

    log.info('device liveness worker pass', {
      stale: due.size,
      probed,
      alerted,
      incidentSuppressed,
      quiet,
      requeued,
      incidentActive,
      ...dispatched,
    });
    return {
      stale: due.size,
      probed,
      alerted,
      incidentSuppressed,
      quiet,
      requeued,
      incidentActive,
      ...dispatched,
    };
  };
}

exports.createSubmitDeviceContact = createSubmitDeviceContact;
exports.createSubmitDeviceContactHandler = createSubmitDeviceContactHandler;
exports.createDeviceLivenessWorkerHandler = createDeviceLivenessWorkerHandler;
exports.deviceLivenessWorker = onSchedule(
  {
    region: REGION,
    schedule: '*/5 * * * *',
    timeZone: 'UTC',
    // Singleton like the DMS worker; transaction CAS makes overlaps safe either way.
    maxInstances: 1,
    timeoutSeconds: 300,
  },
  deferWhileIndexBuilds((event) =>
    createDeviceLivenessWorkerHandler({ db: getFirestore() })(event)),
);
exports._buildContactProbeMessage = buildContactProbeMessage;
exports._evaluateOne = evaluateOne;
