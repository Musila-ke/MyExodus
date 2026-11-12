'use strict';
// `appendUserEvent` callable (plan §7 Release 0 step 2 / §8.8): the ONLY user-event write
// path after Release 0. It preserves the shipped E2EE wire format — the event document is
// the same mailbox message the relay and receivers already understand (recipient-addressed
// `mailboxes/{recipientUid}/messages/{eventId}`, fields senderId/senderUid/pairId/payload/
// keyEpoch/timestamp) — while the server derives the sender and recipient from auth and
// pair membership, validates the exact outer schema, and charges transactional per-UID and
// per-pair token buckets before an Admin SDK create.
//
// The recipient address is derived from `pairings/{pairId}.members`, never from the client
// (plan §8.8 "Do not accept arbitrary participant UIDs from an untrusted client").
//
// A retry after a committed-but-unacknowledged write is safe: the event ID is stable, and
// an existing document with identical immutable fields returns idempotent success without
// charging either bucket. A different body under the same ID is `EVENT_ID_CONFLICT`.
//
// AAD discipline (§9.4): log lines carry short hashes only — never uid, pairId, eventId or
// ciphertext in clear.

const { getFirestore, FieldValue, Timestamp } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');
const crypto = require('node:crypto');

const { UID_BUCKET, PAIR_BUCKET, decideCharge } = require('./event-buckets');
const { COLLECTIONS } = require('./partner-events');
const {
  RECORD_KIND,
  SCHEMA_VERSION,
  validateAppendInput,
  classifyExistingEvent,
} = require('./event-append-logic');

const REGION = 'us-central1';

/** §9.4: short deterministic hash prefix for operational correlation. */
function hashForLog(value) {
  return crypto.createHash('sha256').update(value).digest('hex').slice(0, 12);
}

/**
 * Test seam (house pattern, cf. `_createSendNudgeHandler`): the raw handler with an injected
 * db and clock, driven directly by the emulator suite. `createAppendUserEvent` wraps the same
 * handler in the deployed `onCall` configuration.
 */
function createAppendUserEventHandler({ db, now = () => Date.now() }) {
  return async function appendUserEventHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const validation = validateAppendInput(request.data);
    if (!validation.ok) {
      logger.warn('appendUserEvent rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }
    const input = request.data;
    const nowMs = now();
    const chargeBytes = Buffer.byteLength(input.ciphertextB64, 'utf8');

    const reply = await db.runTransaction(async (tx) => {
      // Active pair + membership first: `members` is the authoritative membership source
      // (written only by registerPairing, AD-02) and derives the mailbox recipient.
      const pairSnap = await tx.get(db.doc(`pairings/${input.pairId}`));
      if (!pairSnap.exists) {
        throw new HttpsError('permission-denied', 'PAIR_NOT_FOUND');
      }
      const members = pairSnap.get('members');
      if (!Array.isArray(members) || !members.includes(auth.uid)) {
        throw new HttpsError('permission-denied', 'NOT_A_PAIR_MEMBER');
      }
      const recipientUid = members.find((member) => member !== auth.uid);
      if (!recipientUid) {
        throw new HttpsError('failed-precondition', 'PAIR_MEMBERS_INVALID');
      }
      const messageRef = db.doc(`mailboxes/${recipientUid}/messages/${input.eventId}`);

      // Replay check precedes every live gate so a committed event stays retrievable after
      // the pair terminates or the sender's profile changes (plan §8.8: timeout-after-commit
      // retries are safe).
      const messageSnap = await tx.get(messageRef);
      if (messageSnap.exists) {
        if (classifyExistingEvent(messageSnap.data(), input, auth.uid) === 'replayed') {
          return { status: 'replayed' };
        }
        logger.warn('appendUserEvent rejected', {
          reason: 'EVENT_ID_CONFLICT',
          eventHash: hashForLog(input.eventId),
        });
        throw new HttpsError('already-exists', 'EVENT_ID_CONFLICT');
      }

      // Plan §8.8/§8.9: active sender profile and no deletion tombstone. The operator kill
      // switch (ADR #207) lived in the mailbox create rule until Release 0 step 3 denied
      // direct creates; the same live gate moves here so switch-off still stops NEW sends
      // while stored data stays intact.
      const registrationRef = input.installationId
        ? db.doc(`${COLLECTIONS.deviceRegistrations}/${auth.uid}:${input.installationId}`)
        : null;
      const [profileSnap, deletionSnap, featuresSnap, userBucketSnap, pairBucketSnap,
        registrationSnap] = await Promise.all([
        tx.get(db.doc(`users/${auth.uid}`)),
        tx.get(db.doc(`deletionJobs/${auth.uid}`)),
        tx.get(db.doc('config/features')),
        tx.get(db.doc(`eventRateLimits/user_${auth.uid}`)),
        tx.get(db.doc(`eventRateLimits/pair_${input.pairId}`)),
        registrationRef ? tx.get(registrationRef) : null,
      ]);
      if (featuresSnap.exists && featuresSnap.get('partnerAccountabilityEnabled') === false) {
        throw new HttpsError('permission-denied', 'FEATURE_DISABLED');
      }
      // §13.3/§8.3.8 dual-acceptance: a sender that identifies a migrated installation is
      // refused on the legacy path — an installation never moves back to the unsigned
      // transport after registering. Old APKs omit the field and stay supported for the
      // documented compatibility window.
      if (registrationSnap && registrationSnap.exists) {
        logger.warn('appendUserEvent rejected', { reason: 'SIGNED_TRANSPORT_REQUIRED' });
        throw new HttpsError('failed-precondition', 'SIGNED_TRANSPORT_REQUIRED');
      }
      if (deletionSnap.exists) {
        throw new HttpsError('permission-denied', 'ACCOUNT_DELETING');
      }
      if (!profileSnap.exists || profileSnap.get('status') !== 'ACTIVE') {
        throw new HttpsError('failed-precondition', 'PROFILE_NOT_ACTIVE');
      }
      if (pairSnap.get('status') !== 'active') {
        throw new HttpsError('permission-denied', 'PAIR_NOT_ACTIVE');
      }

      const bucketOf = (snap) => (snap.exists
        ? {
            events: snap.get('events'),
            bytes: snap.get('bytes'),
            updatedAt: snap.get('updatedAt') ? snap.get('updatedAt').toMillis() : undefined,
          }
        : null);
      const userCharge = decideCharge(bucketOf(userBucketSnap), UID_BUCKET, chargeBytes, nowMs);
      const pairCharge = decideCharge(bucketOf(pairBucketSnap), PAIR_BUCKET, chargeBytes, nowMs);
      if (!userCharge.allowed || !pairCharge.allowed) {
        const retryAfterMs = Math.max(
          userCharge.allowed ? 0 : userCharge.retryAfterMs,
          pairCharge.allowed ? 0 : pairCharge.retryAfterMs,
        );
        throw new HttpsError('resource-exhausted', 'RATE_LIMITED', { retryAfterMs });
      }

      // Writes: the immutable event document plus both charged buckets, one transaction.
      tx.set(messageRef, {
        senderId: input.senderId,
        senderUid: auth.uid,
        pairId: input.pairId,
        payload: input.ciphertextB64,
        keyEpoch: input.keyEpoch,
        timestamp: FieldValue.serverTimestamp(),
        recordKind: RECORD_KIND,
        schemaVersion: SCHEMA_VERSION,
      });
      const bucketDoc = (next) => ({
        events: next.events,
        bytes: next.bytes,
        updatedAt: Timestamp.fromMillis(next.updatedAt),
      });
      tx.set(db.doc(`eventRateLimits/user_${auth.uid}`), bucketDoc(userCharge.next));
      tx.set(db.doc(`eventRateLimits/pair_${input.pairId}`), bucketDoc(pairCharge.next));
      return { status: 'appended' };
    });
    // §13.5 legacy-usage metric: Cloud Logging counts this line. Hashed uid only, no event
    // content (AAD discipline §9.4); `installationIdentified` separates migrated clients
    // that still legitimately use the legacy path for not-yet-migrated kinds.
    if (reply.status === 'appended') {
      logger.info('legacyUserEventAccepted', {
        senderHash: hashForLog(auth.uid),
        installationIdentified: Boolean(input.installationId),
      });
    }
    return reply;
  };
}

function createAppendUserEvent({ db } = {}) {
  return onCall(
    {
      region: REGION,
      // AD-03 / task #21: this is a write path with server-side cost, same App Check
      // posture as registerPairing. The client SDK attaches the token automatically.
      enforceAppCheck: true,
      // AD-20a backpressure boundary until OQ-1 peak-event volume is answered.
      maxInstances: 10,
      timeoutSeconds: 30,
    },
    createAppendUserEventHandler({ db: db || getFirestore() }),
  );
}

module.exports = { createAppendUserEvent, createAppendUserEventHandler };
