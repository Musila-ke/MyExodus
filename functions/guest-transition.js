'use strict';
// Account plan §7 Release 1 step 9 / §8.4 / §4.2 / §6.2: the guest-transition callables.
//
//   prepareGuestTransition   guest-only; stores the capability hash + intent and a 24-hour
//                            lease. Never mutates pairs, profiles or Auth state.
//   finalizeGuestTransition  target-account-only; consumes the capability once, terminates
//                            every active guest pair with a deterministic system event and
//                            enqueues the shared guest-cleanup job. Atomic in one Firestore
//                            transaction, so it is one-time and idempotent by construction.
//   cancelGuestTransition    guest-only, permanent; deletes the lease so a new preparation
//                            can start. Expiry alone never mutates anything.
//
// Feature-gate discipline (§8.2): only a NEW preparation checks the gate. Cancellation and
// finalization of an already-prepared transition must keep working when the gate is off.
//
// Logging (§10.14): transition ids and uids appear only as short hashes; the capability and
// its hash are never logged.

const crypto = require('node:crypto');
const { getFirestore, FieldValue, Timestamp } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');

const { readActiveProfile } = require('./pair-lifecycle');
const {
  STATES,
  LEASE_MS,
  capabilityHashOf,
  hashesEqual,
  validatePrepareInput,
  validateFinalizeInput,
  validateCancelInput,
  effectiveState,
  advancePrepareWindow,
  isRecentAuthentication,
  signInProviderOf,
} = require('./guest-transition-logic');

const REGION = 'us-central1';
const GATE_DOC = 'serverFeatureGates/guestTransition';

function hashForLog(value) {
  return crypto.createHash('sha256').update(value).digest('hex').slice(0, 12);
}

function requireAuth(request) {
  if (!request.auth) throw new HttpsError('unauthenticated', 'sign-in required');
  return request.auth;
}

function expiresAtMsOf(record) {
  const value = record && record.expiresAt;
  return value && typeof value.toMillis === 'function' ? value.toMillis() : 0;
}

function createPrepareGuestTransitionHandler({ db, now = () => Date.now() }) {
  return async function prepareGuestTransitionHandler(request) {
    const auth = requireAuth(request);
    const validation = validatePrepareInput(request.data);
    if (!validation.ok) {
      logger.warn('prepareGuestTransition rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    // §8.4: preparation belongs to the anonymous session it will later terminate.
    if (signInProviderOf(auth) !== 'anonymous') {
      logger.warn('prepareGuestTransition rejected', { reason: 'ANONYMOUS_ONLY' });
      throw new HttpsError('permission-denied', 'ANONYMOUS_ONLY');
    }
    const { transitionId, capability, intent } = request.data;
    const capabilityHash = capabilityHashOf(capability);
    const nowMs = now();

    const transitionRef = db.doc(`guestTransitions/${transitionId}`);
    const leaseRef = db.doc(`guestTransitionLeases/${auth.uid}`);
    const rateRef = db.doc(`rate-limits/guest_transition_${auth.uid}`);

    return db.runTransaction(async (tx) => {
      const [transitionSnap, leaseSnap, deletionSnap, rateSnap] = await Promise.all([
        tx.get(transitionRef),
        tx.get(leaseRef),
        tx.get(db.doc(`deletionJobs/${auth.uid}`)),
        tx.get(rateRef),
      ]);
      if (deletionSnap.exists) throw new HttpsError('permission-denied', 'ACCOUNT_DELETING');

      // A repeated request with the same id/capability replays the stored state (§4.2); it
      // consumes no window attempt and never creates a second record.
      if (transitionSnap.exists) {
        const record = transitionSnap.data();
        const same =
          record.guestUid === auth.uid && hashesEqual(record.capabilityHash, capabilityHash);
        if (!same) {
          logger.warn('prepareGuestTransition rejected', { reason: 'TRANSITION_ID_CONFLICT' });
          throw new HttpsError('already-exists', 'TRANSITION_ID_CONFLICT');
        }
        if (record.intent !== intent) throw new HttpsError('failed-precondition', 'INTENT_MISMATCH');
        const state = effectiveState(record.state, expiresAtMsOf(record), nowMs);
        // §8.4 idempotency: a cancelled record replays as CANCELLED so the client can start a
        // fresh preparation; the hash-only record is retained, so this replay stays authenticated.
        if (state === STATES.FINALIZED) throw new HttpsError('failed-precondition', 'TRANSITION_FINALIZED');
        if (state !== record.state) {
          // §8.4: expiry marks the record but retains the hash-only recovery data.
          tx.set(transitionRef, { state, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
          tx.set(leaseRef, { state, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
          logger.info('prepareGuestTransition expired', { transitionIdHash: hashForLog(transitionId) });
        }
        return { state, intent: record.intent, expiresAtMs: expiresAtMsOf(record) };
      }

      // §4.2: one unfinalized transition per guest UID. The lease is a separate server-only
      // document because Firestore transactions read documents, not queries — so this
      // invariant is transaction-enforced instead of query-checked. Checked before the gate:
      // the existing transition (not the kill switch) is the actionable answer for this caller.
      if (leaseSnap.exists) {
        logger.warn('prepareGuestTransition rejected', { reason: 'TRANSITION_ALREADY_PREPARED' });
        throw new HttpsError('failed-precondition', 'TRANSITION_ALREADY_PREPARED');
      }
      // Only a new preparation is gated (§8.2) — the replay above must keep working even if
      // the gate was flipped off while the client retried.
      const gateSnap = await tx.get(db.doc(GATE_DOC));
      if (!gateSnap.exists || gateSnap.get('enabled') !== true) {
        logger.warn('prepareGuestTransition rejected', { reason: 'GUEST_TRANSITION_DISABLED' });
        throw new HttpsError('failed-precondition', 'GUEST_TRANSITION_DISABLED');
      }

      const window = advancePrepareWindow(rateSnap.exists ? rateSnap.data() : null, nowMs);
      if (!window.allowed) {
        logger.warn('prepareGuestTransition throttled', { uidHash: hashForLog(auth.uid) });
        throw new HttpsError('resource-exhausted', 'TRANSITION_RATE_LIMITED', {
          retryAfterMs: window.retryAfterMs,
        });
      }

      const expiresAtMs = nowMs + LEASE_MS;
      tx.set(transitionRef, {
        guestUid: auth.uid,
        state: STATES.PREPARED,
        intent,
        capabilityHash,
        expiresAt: Timestamp.fromMillis(expiresAtMs),
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      tx.set(leaseRef, {
        transitionId,
        guestUid: auth.uid,
        state: STATES.PREPARED,
        expiresAt: Timestamp.fromMillis(expiresAtMs),
        updatedAt: FieldValue.serverTimestamp(),
      });
      tx.set(rateRef, { attempts: window.window.attempts });
      logger.info('prepareGuestTransition', {
        transitionIdHash: hashForLog(transitionId),
        uidHash: hashForLog(auth.uid),
        intent,
      });
      return { state: STATES.PREPARED, intent, expiresAtMs };
    });
  };
}

function createFinalizeGuestTransitionHandler({ db, now = () => Date.now() }) {
  return async function finalizeGuestTransitionHandler(request) {
    const auth = requireAuth(request);
    const validation = validateFinalizeInput(request.data);
    if (!validation.ok) {
      logger.warn('finalizeGuestTransition rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    if (signInProviderOf(auth) === 'anonymous') {
      logger.warn('finalizeGuestTransition rejected', { reason: 'PERSISTENT_ACCOUNT_REQUIRED' });
      throw new HttpsError('failed-precondition', 'PERSISTENT_ACCOUNT_REQUIRED');
    }
    const { transitionId, capability, intent } = request.data;
    const capabilityHash = capabilityHashOf(capability);
    const nowMs = now();

    const transitionRef = db.doc(`guestTransitions/${transitionId}`);
    const head = await transitionRef.get();
    if (!head.exists) throw new HttpsError('not-found', 'TRANSITION_NOT_FOUND');
    const guestUid = head.get('guestUid');

    // Transactions take document reads only, so the candidate pair set is queried first and
    // every document is re-read and re-checked inside the transaction. A pair activated after
    // this query is intentionally left to the shared cleanup worker (R3), which removes every
    // pair containing the guest uid regardless of status.
    const candidates = await db.collection('pairings').where('members', 'array-contains', guestUid).get();
    const candidateRefs = candidates.docs.map((doc) => doc.ref);
    const leaseRef = db.doc(`guestTransitionLeases/${guestUid}`);
    const cleanupRef = db.doc(`deletionJobs/${guestUid}`);

    return db.runTransaction(async (tx) => {
      const transitionSnap = await tx.get(transitionRef);
      if (!transitionSnap.exists) throw new HttpsError('not-found', 'TRANSITION_NOT_FOUND');
      const record = transitionSnap.data();
      if (record.guestUid !== guestUid) throw new HttpsError('failed-precondition', 'TRANSITION_STATE_INVALID');
      if (guestUid === auth.uid) throw new HttpsError('failed-precondition', 'SAME_IDENTITY');

      const state = effectiveState(record.state, expiresAtMsOf(record), nowMs);
      if (state === STATES.FINALIZED) {
        // The capability was consumed; the record is bound to its target from here on.
        if (record.targetUid !== auth.uid) {
          throw new HttpsError('permission-denied', 'TRANSITION_OWNED_BY_ANOTHER_ACCOUNT');
        }
        return {
          state: STATES.FINALIZED,
          intent: record.intent,
          terminatedPairIds: record.terminatedPairIds || [],
          cleanupJobId: record.cleanupJobId || null,
        };
      }
      if (state === STATES.CANCELLED) throw new HttpsError('failed-precondition', 'TRANSITION_CANCELLED');
      if (state !== STATES.PREPARED && state !== STATES.EXPIRED_UNFINALIZED) {
        throw new HttpsError('failed-precondition', 'TRANSITION_STATE_INVALID');
      }
      if (!hashesEqual(record.capabilityHash, capabilityHash)) {
        logger.warn('finalizeGuestTransition rejected', { reason: 'INVALID_CAPABILITY' });
        throw new HttpsError('permission-denied', 'INVALID_CAPABILITY');
      }
      if (record.intent !== intent) throw new HttpsError('failed-precondition', 'INTENT_MISMATCH');

      await readActiveProfile(tx, db, auth.uid);
      if (state === STATES.EXPIRED_UNFINALIZED &&
          !isRecentAuthentication(auth.token, Math.floor(nowMs / 1000))) {
        logger.info('finalizeGuestTransition needs reauthentication', { uidHash: hashForLog(auth.uid) });
        throw new HttpsError('failed-precondition', 'REAUTH_REQUIRED');
      }

      const [cleanupSnap] = await Promise.all([tx.get(cleanupRef)]);
      const pairSnaps = candidateRefs.length > 0 ? await tx.getAll(...candidateRefs) : [];
      const terminatedPairIds = [];
      for (const snap of pairSnaps) {
        if (!snap.exists) continue;
        const members = snap.get('members');
        if (!Array.isArray(members) || !members.includes(guestUid)) continue;
        if (snap.get('status') !== 'active') continue;
        terminatedPairIds.push(snap.id);
        tx.set(db.doc(`pairings/${snap.id}`), { status: 'terminated' }, { merge: true });
        // Deterministic id: a retried finalize (or a racing cleanup) can never duplicate it.
        tx.set(db.doc(`pairings/${snap.id}/system_events/guest_transition_terminated`), {
          type: 'PAIR_TERMINATED',
          actorUid: guestUid,
          reason: 'GUEST_ACCOUNT_TRANSITION',
          createdAt: FieldValue.serverTimestamp(),
        });
      }

      const cleanupJobId = cleanupRef.id;
      if (!cleanupSnap.exists) {
        // §6.2 step 9: private guest records are removed only after finalization. The shared
        // worker (Release 3) consumes this job; creating it here is the durable handoff.
        tx.set(cleanupRef, {
          uid: guestUid,
          kind: 'GUEST_TRANSITION_CLEANUP',
          operationId: transitionId,
          phase: 'QUEUED',
          transitionId,
          targetUid: auth.uid,
          createdAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp(),
        });
      }

      // One atomic commit consumes the capability (the hash is dropped) and applies every
      // effect, so there is no persisted FINALIZING window a second caller could duplicate.
      tx.set(transitionRef, {
        guestUid,
        state: STATES.FINALIZED,
        intent: record.intent,
        targetUid: auth.uid,
        terminatedPairIds,
        cleanupJobId,
        createdAt: record.createdAt,
        finalizedAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      tx.set(leaseRef, {
        transitionId,
        guestUid,
        state: STATES.FINALIZED,
        updatedAt: FieldValue.serverTimestamp(),
      });

      logger.info('finalizeGuestTransition', {
        transitionIdHash: hashForLog(transitionId),
        guestUidHash: hashForLog(guestUid),
        targetUidHash: hashForLog(auth.uid),
        pairsTerminated: terminatedPairIds.length,
        cleanupCreated: !cleanupSnap.exists,
      });
      return { state: STATES.FINALIZED, intent: record.intent, terminatedPairIds, cleanupJobId };
    });
  };
}

function createCancelGuestTransitionHandler({ db, now = () => Date.now() }) {
  return async function cancelGuestTransitionHandler(request) {
    const auth = requireAuth(request);
    const validation = validateCancelInput(request.data);
    if (!validation.ok) {
      logger.warn('cancelGuestTransition rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    if (signInProviderOf(auth) !== 'anonymous') {
      throw new HttpsError('permission-denied', 'ANONYMOUS_ONLY');
    }
    const { transitionId, capability } = request.data;
    const capabilityHash = capabilityHashOf(capability);
    const nowMs = now();

    const transitionRef = db.doc(`guestTransitions/${transitionId}`);

    return db.runTransaction(async (tx) => {
      const snap = await tx.get(transitionRef);
      if (!snap.exists) throw new HttpsError('not-found', 'TRANSITION_NOT_FOUND');
      const record = snap.data();
      if (record.guestUid !== auth.uid) {
        throw new HttpsError('permission-denied', 'NOT_TRANSITION_OWNER');
      }
      // Terminal state is judged for the owner before the capability: finalize consumes the
      // hash, so a retried cancel must report FINALIZED rather than an absent-hash mismatch.
      const state = effectiveState(record.state, expiresAtMsOf(record), nowMs);
      if (state === STATES.CANCELLED) return { state: STATES.CANCELLED };
      if (state === STATES.FINALIZED) throw new HttpsError('failed-precondition', 'TRANSITION_FINALIZED');
      if (!hashesEqual(record.capabilityHash, capabilityHash)) {
        throw new HttpsError('permission-denied', 'INVALID_CAPABILITY');
      }

      // Cancellation is final and keeps the hash-only record for audit; the lease is freed so
      // a fresh preparation (new id, new capability) can start.
      tx.set(transitionRef, {
        state: STATES.CANCELLED,
        cancelledAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: true });
      tx.delete(db.doc(`guestTransitionLeases/${auth.uid}`));
      logger.info('cancelGuestTransition', {
        transitionIdHash: hashForLog(transitionId),
        uidHash: hashForLog(auth.uid),
      });
      return { state: STATES.CANCELLED };
    });
  };
}

function createPrepareGuestTransition({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createPrepareGuestTransitionHandler({ db: db || getFirestore() }),
  );
}

function createFinalizeGuestTransition({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createFinalizeGuestTransitionHandler({ db: db || getFirestore() }),
  );
}

function createCancelGuestTransition({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createCancelGuestTransitionHandler({ db: db || getFirestore() }),
  );
}

module.exports = {
  createPrepareGuestTransition,
  createFinalizeGuestTransition,
  createCancelGuestTransition,
  createPrepareGuestTransitionHandler,
  createFinalizeGuestTransitionHandler,
  createCancelGuestTransitionHandler,
};
