'use strict';
// `submitHeartbeat` callable (partner event-delivery plan §7 Step 7 / §8.10). One
// authenticated, App Check-enforced call that refreshes a device's liveness state and nothing
// else. The payload is `{installId, counter}` — no monitoring status, no permissions, no
// categories (§8.10); the encrypted monitoring snapshot travels on the relay instead.
//
// Identity: the caller must already have registered this installation under their own uid
// (`users/{uid}/push_installations/{installId}` — AD-18a). The FID is not a secret, so auth
// alone cannot prove the caller owns it; the registration row is the one binding the server
// already trusts, and it prevents one user from touching another's DMS state.
//
// The document id is `(uid, installId)`-scoped (see heartbeat-logic.js), which is what makes
// the counter namespace survive identity rotation: a new uid is a new document and a fresh
// counter, never a lockout (§9).

const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');

const {
  stateDocId,
  isValidInstallId,
  isValidCounter,
  decideHeartbeat,
} = require('./heartbeat-logic');
const {
  consentingPartner,
  noticeDocId,
  LATE_AFTER_MS,
  NOTICE_AVAILABILITY,
  NOTICE_RECOVERY,
  NOTICE_PENDING,
  NOTICE_DISPATCHING,
  NOTICE_SENT,
  NOTICE_SUPPRESSED,
} = require('./dms-logic');

const REGION = 'us-central1';
const MAX_RELATIONSHIPS_PER_INSTALL = 50;

/**
 * Test seam (house pattern, cf. `_createSendNudgeHandler`): the raw handler with an injected
 * db and clock, driven directly by the emulator suite. `createSubmitHeartbeat` wraps it in the
 * deployed `onCall` configuration.
 */
function createSubmitHeartbeatHandler({ db, now = () => Date.now() }) {
  return async function submitHeartbeatHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const installId = request.data && request.data.installId;
    const counter = request.data && request.data.counter;
    if (!isValidInstallId(installId) || !isValidCounter(counter)) {
      logger.warn('submitHeartbeat rejected', { reason: 'INVALID_REQUEST' });
      throw new HttpsError('invalid-argument', 'INVALID_REQUEST');
    }

    const nowMs = now();
    const installRef = db.doc(`users/${auth.uid}/push_installations/${installId}`);
    const stateRef = db.doc(`dms_install_state/${stateDocId(auth.uid, installId)}`);

    return db.runTransaction(async (tx) => {
      // All reads before any write (Firestore transaction contract).
      const [installSnap, stateSnap] = await Promise.all([
        tx.get(installRef),
        tx.get(stateRef),
      ]);
      if (!installSnap.exists) {
        logger.warn('submitHeartbeat rejected', { reason: 'INSTALL_NOT_REGISTERED' });
        throw new HttpsError('permission-denied', 'INSTALL_NOT_REGISTERED');
      }
      const previous = stateSnap.exists ? stateSnap.data() : null;
      const decision = decideHeartbeat(previous, counter, nowMs);

      if (decision.outcome === 'ACCEPTED') {
        // Recovery notices (§4): a fresh beat after a SENT notice restores liveness and owes
        // each consenting relationship at most one generic recovery update. The outbox rows
        // are written in THIS transaction — the same one that clears notice_state — so a crash
        // can never leave a recovered device silently "notified" (or a stale recovery queued).
        const wasNotified = previous != null && previous.notice_state === 'NOTIFIED';
        const outageGeneration = previous ? Number(previous.outage_generation) || 0 : 0;
        let recoveryRows = 0;
        if (wasNotified) {
          const generation = outageGeneration;
          const pairsSnap = await tx.get(
            db.collection('pairings')
              .where('members', 'array-contains', auth.uid)
              .limit(MAX_RELATIONSHIPS_PER_INSTALL),
          );
          const candidates = [];
          for (const pairDoc of pairsSnap.docs) {
            const partner = consentingPartner(pairDoc.data(), auth.uid);
            if (!partner) continue;
            const noticeRef = db.doc(
              `dms_notification_outbox/${noticeDocId(installId, pairDoc.id, generation, NOTICE_AVAILABILITY)}`,
            );
            const recoveryRef = db.doc(
              `dms_notification_outbox/${noticeDocId(installId, pairDoc.id, generation, NOTICE_RECOVERY)}`,
            );
            const [noticeSnap, recoverySnap] = await Promise.all([
              tx.get(noticeRef),
              tx.get(recoveryRef),
            ]);
            candidates.push({ pairId: pairDoc.id, partner, noticeRef, noticeSnap, recoveryRef, recoverySnap });
          }
          // All reads are done; now the writes.
          for (const candidate of candidates) {
            const notice = candidate.noticeSnap.exists ? candidate.noticeSnap.data() : null;
            const status = notice ? notice.status : null;
            if (status === NOTICE_PENDING) {
              // The notice never left; it must not leave after the device came back. Its
              // deterministic id is never reused for this outage generation.
              tx.update(candidate.noticeRef, {
                status: NOTICE_SUPPRESSED,
                suppressed_reason: 'RECOVERED_BEFORE_DISPATCH',
                updated_at_ms: nowMs,
              });
              continue;
            }
            const possiblySent = status === NOTICE_SENT ||
              (status === NOTICE_DISPATCHING &&
                Number(notice.lease_until_ms) > nowMs);
            if (!possiblySent || candidate.recoverySnap.exists) continue;
            tx.set(candidate.recoveryRef, {
              install_id: installId,
              state_doc_id: stateRef.id,
              monitored_uid: auth.uid,
              relationship_id: candidate.pairId,
              recipient_uid: candidate.partner.recipientUid,
              outage_generation: generation,
              notice_type: NOTICE_RECOVERY,
              status: NOTICE_PENDING,
              attempts: 0,
              next_attempt_at_ms: nowMs,
              created_at_ms: nowMs,
            });
            recoveryRows += 1;
          }
        }

        const stateUpdate = {
          uid: auth.uid,
          install_id: installId,
          last_counter: decision.serverCounter,
          liveness_generation: decision.generation,
          last_received_at: Timestamp.fromMillis(decision.lastReceivedAtMs),
          last_received_at_ms: decision.lastReceivedAtMs,
          next_deadline_at: Timestamp.fromMillis(decision.nextDeadlineAtMs),
          // §8.9: the silent liveness probe is due 45 minutes after this accepted beat. The
          // worker compare-and-sets `late_reminder_generation` (historical field name); a
          // fresh beat moves the deadline and makes any in-flight probe claim a no-op.
          late_deadline_at: Timestamp.fromMillis(decision.lastReceivedAtMs + LATE_AFTER_MS),
          // The deadline processor owns the generation; a heartbeat carries it forward and
          // clears the notice flag, which is what ends the outage.
          last_notified_generation: previous ? previous.last_notified_generation || 0 : 0,
          outage_generation: outageGeneration,
          notice_state: wasNotified ? 'NONE' : (previous && previous.notice_state) || 'NONE',
          updated_at: Timestamp.fromMillis(nowMs),
        };
        if (wasNotified) stateUpdate.recovered_at_ms = nowMs;
        tx.set(stateRef, stateUpdate, { merge: true });
        if (wasNotified) {
          logger.info('heartbeat recovered a notified install', { recoveryRows });
        }
      }
      return {
        accepted: decision.outcome === 'ACCEPTED',
        serverCounter: decision.serverCounter,
        generation: decision.generation,
      };
    });
  };
}

function createSubmitHeartbeat({ db = getFirestore(), now } = {}) {
  const handler = createSubmitHeartbeatHandler(now ? { db, now } : { db });
  return onCall(
    {
      region: REGION,
      // §8.10: the same App Check boundary as the pairing callable — this writes server-side
      // availability state that partners may act on.
      enforceAppCheck: true,
      maxInstances: 10,
      // Smaller than pairing's 30 s: one transaction, two reads.
      timeoutSeconds: 15,
    },
    handler,
  );
}

exports.createSubmitHeartbeat = createSubmitHeartbeat;
exports.createSubmitHeartbeatHandler = createSubmitHeartbeatHandler;
