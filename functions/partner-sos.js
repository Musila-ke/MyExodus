'use strict';
// Partner SOS `sendNudge` callable (V1 Req 35, plan §8.4 / §6.2). Plain CommonJS in house
// style (D3): the pure decisions live in ./partner-sos-logic.js, this module owns the
// single-transaction acceptance path.
//
// The callable never invokes FCM (plan §6.2 step 22) — acceptance is durable Firestore state
// plus a queue document; dispatch is the trigger/scheduler's job (§8.5-§8.11).
//
// AAD discipline (§9.4): every log line carries short hashes only — never uid, pairId,
// nudgeId, envelope, or AAD in clear.

const { getFirestore, FieldValue, Timestamp } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');
const crypto = require('node:crypto');

const {
  NUDGE_RETENTION_MS,
  validateSendNudgeInput,
  decideNudgeAcceptance,
  classifyExistingNudge,
} = require('./partner-sos-logic');

// D4: Firestore lives in nam5; Functions cannot deploy there. us-central1 sits inside the
// nam5 footprint — same region constant as every other function in this repo.
const REGION = 'us-central1';

/** §9.4: short deterministic hash prefix for operational correlation. */
function hashForLog(value) {
  return crypto.createHash('sha256').update(value).digest('hex').slice(0, 12);
}

/**
 * Test seam (house pattern, cf. `_createRegisterPairing`): the raw handler with an injected
 * Firestore db, invoked directly by `test/partner-sos.emulator.js` against the emulator.
 * `createSendNudge` below wraps the same handler in the deployed `onCall` configuration.
 */
function createSendNudgeHandler({ db }) {
  return async function sendNudgeHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const validation = validateSendNudgeInput(request.data);
    if (!validation.ok) {
      logger.warn('sendNudge rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    const { pairId, nudgeId, envelope } = request.data;

    // §8.4: one request-wide nowMs, captured before the transaction — retries inside the
    // transaction must not see a moving clock.
    const nowMs = Date.now();

    const outcome = await db.runTransaction(async (tx) => {
      // §6.2 step 3 / AD-20: the kill switch is read inside the transaction — never cached,
      // so it takes effect on the very next request.
      const configSnap = await tx.get(db.doc('config/features'));
      if (!configSnap.exists || configSnap.get('partnerSosEnabled') !== true) {
        throw new HttpsError('failed-precondition', 'feature_disabled');
      }

      // Steps 4-5: active pair + membership. The pair document is written only by
      // registerPairing (AD-02), so `members` is authoritative.
      const pairSnap = await tx.get(db.doc(`pairings/${pairId}`));
      if (!pairSnap.exists) throw new HttpsError('permission-denied', 'pair_not_found');
      const members = pairSnap.get('members');
      if (!Array.isArray(members) || !members.includes(auth.uid)) {
        throw new HttpsError('permission-denied', 'not_a_pair_member');
      }
      const recipientUid = members.find((m) => m !== auth.uid);

      // Step 7: recipient block state (absent document = not blocked).
      const settingsSnap = await tx.get(
        db.doc(`pairings/${pairId}/nudgeSettings/${recipientUid}`),
      );
      const blocked = settingsSnap.exists && settingsSnap.get('blocked') === true;

      // Step 8: the sole rate-limit state for this (pairId, senderUid) stream.
      const stateRef = db.doc(`pairings/${pairId}/nudgeState/${auth.uid}`);
      const stateSnap = await tx.get(stateRef);

      // Step 9: idempotency/integrity reads. Queue doc id === nudge id, so both are
      // addressed with the one client-generated UUID.
      const nudgeRef = db.doc(`pairings/${pairId}/nudges/${nudgeId}`);
      const nudgeSnap = await tx.get(nudgeRef);
      const queueRef = db.doc(`nudgePushQueue/${nudgeId}`);
      const queueSnap = await tx.get(queueRef);

      // Step 11: pair must still be active for acceptance (a terminated pair makes every
      // further partner write impossible — Req 29; replayed nudge ids included).
      if (pairSnap.get('status') !== 'active') {
        throw new HttpsError('permission-denied', 'pair_not_active');
      }

      // Step 12: existing nudge document — replay ok, mismatches rejected, nothing written.
      if (nudgeSnap.exists) {
        const cls = classifyExistingNudge({
          existing: nudgeSnap.data(),
          senderUid: auth.uid,
          envelope,
        });
        if (cls.outcome === 'sender_mismatch') {
          throw new HttpsError('permission-denied', 'nudge_sender_mismatch');
        }
        if (cls.outcome === 'envelope_mismatch') {
          throw new HttpsError('invalid-argument', 'nudge_id_reuse');
        }
        return { replay: true };
      }
      // Same-transaction writes make this unreachable; if it ever fires, something wrote a
      // queue document outside the callable and the nudge must not silently overwrite it.
      if (queueSnap.exists) {
        logger.error('nudge_state_conflict', { nudgeHash: hashForLog(nudgeId) });
        throw new HttpsError('internal', 'state_conflict');
      }

      // Steps 13-16: cooldown then daily cap; a rejection writes nothing.
      const rate = decideNudgeAcceptance({ state: stateSnap.exists ? stateSnap.data() : null, nowMs });
      if (!rate.allowed) return { rateLimited: rate };

      // Step 17: rate state, replaced wholesale.
      tx.set(
        stateRef,
        {
          lastAcceptedAtMs: nowMs,
          lastNudgeId: nudgeId,
          dayKey: rate.dayKey,
          dayCount: rate.dayCount,
        },
        { merge: false },
      );

      // Steps 18-20: the authoritative nudge record; blocked-at-acceptance is suppressed
      // with no queue document (§6.2 step 19), everything else queues for dispatch.
      const expiresAt = Timestamp.fromMillis(nowMs + NUDGE_RETENTION_MS);
      tx.set(
        nudgeRef,
        {
          senderUid: auth.uid,
          recipientUid,
          envelope,
          envelopeVersion: 1,
          createdAt: FieldValue.serverTimestamp(),
          createdAtMs: nowMs,
          expiresAt,
          status: blocked ? 'suppressed' : 'pending',
          suppressedAt: blocked ? FieldValue.serverTimestamp() : null,
          ackedAt: null,
          pushAttempts: 0,
          lastPushAtMs: null,
          targetDeviceCount: 0,
          sentDeviceCount: 0,
          lastPushErrorCode: null,
          terminalAt: null,
        },
        { merge: false },
      );
      if (!blocked) {
        tx.set(
          queueRef,
          {
            pairId,
            createdAtMs: nowMs,
            expiresAt,
            nextAttemptAtMs: nowMs,
            attemptCount: 0,
            leaseId: null,
            leaseUntilMs: null,
            targetInstallIds: [],
            sentInstallIds: [],
          },
          { merge: false },
        );
      }
      return { suppressed: blocked };
    });

    const safe = { nudgeHash: hashForLog(nudgeId), pairHash: hashForLog(pairId) };
    if (outcome.rateLimited) {
      logger.info('nudge_rate_limited', {
        ...safe,
        reason: outcome.rateLimited.reason,
        retryAfterSeconds: outcome.rateLimited.retryAfterSeconds,
      });
      throw new HttpsError('resource-exhausted', 'rate_limited', {
        reason: outcome.rateLimited.reason,
        retryAfterSeconds: outcome.rateLimited.retryAfterSeconds,
      });
    }
    if (outcome.replay) {
      logger.info('nudge_replay', safe);
    } else if (outcome.suppressed) {
      logger.info('nudge_suppressed', { ...safe, reason: 'recipient_blocked' });
    } else {
      logger.info('nudge_accepted', safe);
    }
    return { status: 'accepted', nudgeId };
  };
}

/** §8.4 function configuration, verbatim: 2nd gen onCall, flag-gated by the transaction. */
function createSendNudge({ db } = {}) {
  const firestore = db || getFirestore();
  return onCall(
    {
      region: REGION,
      memory: '256MiB',
      timeoutSeconds: 15,
      minInstances: 0,
      maxInstances: 20,
      enforceAppCheck: true,
    },
    createSendNudgeHandler({ db: firestore }),
  );
}

module.exports = { createSendNudge, createSendNudgeHandler, hashForLog };
