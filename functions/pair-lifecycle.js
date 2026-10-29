'use strict';
// Release 0 pair/profile callables (plan §7 step 2 / §8.8 / §8.10). The shipped ceremony is
// preserved — the client still mints the typed-code/QR token and stores only SHA-256(token)
// in the document id, and `registerPairing` remains the pair activate/create authority —
// but every write around it becomes server-mediated:
//
//   createPairInvitation     initiator registers the invitation payload (server-set rate
//                            limit, server-enforced expiry ceiling, Admin write)
//   respondToPairInvitation  partner response onto the token doc (server-validated
//                            single-use, expiry and self-response instead of rules)
//   terminatePair            member-only, idempotent active -> terminated + deterministic
//                            `system_events` entry (clients can no longer write the pair)
//   initializeUserProfile    creates the minimal ACTIVE profile every protected callable
//                            requires; never recreates after a deletion tombstone
//   getClientCompatibility   server-published minimum version + update message (§8.10)
//
// Plan deviations, recorded in `app/docs/account-release0-inventory.md`: the token is
// client-minted (60-bit typed-code capacity, 10-minute TTL — owner-approved UX) rather than
// server-minted 128-bit/24h. Server-side rate limiting, single-use and TTL are unchanged.
//
// AAD discipline (§9.4): log lines carry short hashes only.

const { getFirestore, FieldValue, Timestamp } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');
const crypto = require('node:crypto');

const {
  isValidPairId,
  validateInvitationInput,
  validateResponseInput,
  advanceInvitationWindow,
  INVITATION_LIMIT,
} = require('./pair-lifecycle-logic');

const REGION = 'us-central1';

function hashForLog(value) {
  return crypto.createHash('sha256').update(value).digest('hex').slice(0, 12);
}

// §8.2/§8.9: a missing profile or any status other than ACTIVE denies operations, and the
// deletion tombstone wins over a still-valid ID token. All reads stay inside the caller's
// transaction — the result must be consistent with the write that follows it.
async function readActiveProfile(tx, db, uid) {
  const [deletionSnap, profileSnap] = await Promise.all([
    tx.get(db.doc(`deletionJobs/${uid}`)),
    tx.get(db.doc(`users/${uid}`)),
  ]);
  if (deletionSnap.exists) {
    throw new HttpsError('permission-denied', 'ACCOUNT_DELETING');
  }
  if (!profileSnap.exists || profileSnap.get('status') !== 'ACTIVE') {
    throw new HttpsError('failed-precondition', 'PROFILE_NOT_ACTIVE');
  }
}

function createInitializeUserProfileHandler({ db }) {
  return async function initializeUserProfileHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    return db.runTransaction(async (tx) => {
      const profileRef = db.doc(`users/${auth.uid}`);
      const [deletionSnap, profileSnap] = await Promise.all([
        tx.get(db.doc(`deletionJobs/${auth.uid}`)),
        tx.get(profileRef),
      ]);
      // §8.9: the durable tombstone wins — a still-valid ID token must never resurrect a
      // profile once deletion was accepted.
      if (deletionSnap.exists) {
        throw new HttpsError('permission-denied', 'ACCOUNT_DELETING');
      }
      if (profileSnap.exists) {
        return { status: profileSnap.get('status'), created: false };
      }
      tx.set(profileRef, {
        status: 'ACTIVE',
        profileVersion: 1,
        schemaVersion: 1,
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return { status: 'ACTIVE', created: true };
    });
  };
}

function createCreatePairInvitationHandler({ db, now = () => Date.now() }) {
  return async function createPairInvitationHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const nowMs = now();
    const validation = validateInvitationInput(request.data, nowMs);
    if (!validation.ok) {
      logger.warn('createPairInvitation rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    const { tokenId, expiresAtEpochMillis, payloadB64 } = request.data;
    const payload = Buffer.from(payloadB64, 'base64');

    return db.runTransaction(async (tx) => {
      const tokenRef = db.doc(`pairing_tokens/${tokenId}`);
      const windowRef = db.doc(`rate-limits/invitation_${auth.uid}`);
      const [tokenSnap, windowSnap] = await Promise.all([
        tx.get(tokenRef),
        tx.get(windowRef),
        readActiveProfile(tx, db, auth.uid),
      ]);

      // Idempotent replay: the same initiator re-registering the same token body succeeds
      // without charging the window again. Anything else under the same document id is a
      // conflict — an invitation document is never overwritten.
      if (tokenSnap.exists) {
        const same =
          tokenSnap.get('creatorUid') === auth.uid &&
          tokenSnap.get('expiresAt') &&
          tokenSnap.get('expiresAt').toMillis() === expiresAtEpochMillis &&
          Buffer.compare(tokenSnap.get('payload') || Buffer.alloc(0), payload) === 0;
        if (same) return { status: 'registered', replayed: true };
        throw new HttpsError('already-exists', 'INVITATION_ID_CONFLICT');
      }

      const window = advanceInvitationWindow(windowSnap.exists ? windowSnap.data() : null, nowMs);
      if (!window.allowed) {
        logger.warn('createPairInvitation throttled', { uidHash: hashForLog(auth.uid) });
        throw new HttpsError('resource-exhausted', 'INVITATION_RATE_LIMITED', {
          retryAfterMs: window.retryAfterMs,
        });
      }

      tx.set(tokenRef, {
        creatorUid: auth.uid,
        expiresAt: Timestamp.fromMillis(expiresAtEpochMillis),
        payload,
        createdAt: FieldValue.serverTimestamp(),
      });
      tx.set(windowRef, { window: window.window });
      return { status: 'registered', replayed: false };
    });
  };
}

function createRespondToPairInvitationHandler({ db, now = () => Date.now() }) {
  return async function respondToPairInvitationHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const validation = validateResponseInput(request.data);
    if (!validation.ok) {
      logger.warn('respondToPairInvitation rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    const { tokenId, partnerPayloadB64, partnerSignatureB64 } = request.data;
    const nowMs = now();

    return db.runTransaction(async (tx) => {
      const tokenRef = db.doc(`pairing_tokens/${tokenId}`);
      const tokenSnap = await tx.get(tokenRef);
      await readActiveProfile(tx, db, auth.uid);
      if (!tokenSnap.exists) {
        throw new HttpsError('not-found', 'TOKEN_NOT_FOUND');
      }
      if (tokenSnap.get('creatorUid') === auth.uid) {
        // A token document is never the initiator's own response slot.
        throw new HttpsError('invalid-argument', 'SELF_RESPONSE');
      }
      const expiresAt = tokenSnap.get('expiresAt');
      if (!expiresAt || expiresAt.toMillis() <= nowMs) {
        throw new HttpsError('failed-precondition', 'TOKEN_EXPIRED');
      }
      if (tokenSnap.get('partnerUid')) {
        throw new HttpsError('failed-precondition', 'TOKEN_ALREADY_USED');
      }
      tx.update(tokenRef, {
        partnerUid: auth.uid,
        partnerPayload: Buffer.from(partnerPayloadB64, 'base64'),
        partnerSignature: Buffer.from(partnerSignatureB64, 'base64'),
        respondedAt: FieldValue.serverTimestamp(),
      });
      return { status: 'written' };
    });
  };
}

function createTerminatePairHandler({ db }) {
  return async function terminatePairHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const data = request.data;
    const pairId = data && data.pairId;
    if (data === null || typeof data !== 'object' || Array.isArray(data) ||
        Object.keys(data).length !== 1 || !isValidPairId(pairId)) {
      logger.warn('terminatePair rejected', { reason: 'INVALID_INPUT' });
      throw new HttpsError('invalid-argument', 'INVALID_INPUT');
    }

    return db.runTransaction(async (tx) => {
      const pairRef = db.doc(`pairings/${pairId}`);
      const [pairSnap, deletionSnap] = await Promise.all([
        tx.get(pairRef),
        tx.get(db.doc(`deletionJobs/${auth.uid}`)),
      ]);
      if (deletionSnap.exists) {
        throw new HttpsError('permission-denied', 'ACCOUNT_DELETING');
      }
      if (!pairSnap.exists) {
        // Already gone is the terminal state the client asked for.
        return { status: 'not_found' };
      }
      const members = pairSnap.get('members');
      if (!Array.isArray(members) || !members.includes(auth.uid)) {
        throw new HttpsError('permission-denied', 'NOT_A_PAIR_MEMBER');
      }
      const status = pairSnap.get('status');
      if (status === 'terminated') {
        // Idempotent: the mutual-unpair loser and every retry land here.
        return { status: 'terminated' };
      }
      if (status !== 'active') {
        throw new HttpsError('failed-precondition', 'PAIR_NOT_ACTIVE');
      }
      tx.set(pairRef, { status: 'terminated' }, { merge: true });
      // §9: deterministic document id — a retried termination never appends duplicates.
      tx.set(db.doc(`pairings/${pairId}/system_events/pair_terminated`), {
        type: 'PAIR_TERMINATED',
        actorUid: auth.uid,
        createdAt: FieldValue.serverTimestamp(),
      });
      return { status: 'terminated' };
    });
  };
}

function createGetClientCompatibilityHandler({ db }) {
  return async function getClientCompatibilityHandler() {
    const snap = await db.doc('clientCompatibility/current').get();
    return {
      minimumVersionCode: snap.exists ? snap.get('minimumVersionCode') ?? null : null,
      updateMessage: snap.exists ? snap.get('updateMessage') ?? null : null,
    };
  };
}

// Deployed wrappers. App Check is enforced on every authenticated mutation; the
// compatibility read is deliberately open (§8.10: public read) so an outdated client can
// still learn why it is being refused.
function createInitializeUserProfile({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createInitializeUserProfileHandler({ db: db || getFirestore() }),
  );
}

function createCreatePairInvitation({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createCreatePairInvitationHandler({ db: db || getFirestore() }),
  );
}

function createRespondToPairInvitation({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createRespondToPairInvitationHandler({ db: db || getFirestore() }),
  );
}

function createTerminatePair({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 30 },
    createTerminatePairHandler({ db: db || getFirestore() }),
  );
}

function createGetClientCompatibility({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: false, maxInstances: 10, timeoutSeconds: 10 },
    createGetClientCompatibilityHandler({ db: db || getFirestore() }),
  );
}

module.exports = {
  createInitializeUserProfile,
  createCreatePairInvitation,
  createRespondToPairInvitation,
  createTerminatePair,
  createGetClientCompatibility,
  createInitializeUserProfileHandler,
  createCreatePairInvitationHandler,
  createRespondToPairInvitationHandler,
  createTerminatePairHandler,
  createGetClientCompatibilityHandler,
  INVITATION_LIMIT,
};
