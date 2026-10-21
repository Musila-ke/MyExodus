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

const REGION = 'us-central1';

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
        tx.set(stateRef, {
          uid: auth.uid,
          install_id: installId,
          last_counter: decision.serverCounter,
          liveness_generation: decision.generation,
          last_received_at: Timestamp.fromMillis(decision.lastReceivedAtMs),
          last_received_at_ms: decision.lastReceivedAtMs,
          next_deadline_at: Timestamp.fromMillis(decision.nextDeadlineAtMs),
          // The deadline processor owns this; a heartbeat never edits it.
          last_notified_generation: previous ? previous.last_notified_generation || 0 : 0,
          updated_at: Timestamp.fromMillis(nowMs),
        }, { merge: true });
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
