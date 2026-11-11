'use strict';
// §8.3 device signing-key registration and rotation. Two callables:
//
//   registerDeviceChallenge — issues a one-time, five-minute challenge scoped to
//     (uid, installationId, intent). Only the SHA-256 of the 32 random bytes is stored.
//   registerOrRotateDevice — consumes the challenge atomically with the registration,
//     verifies proof of possession over the JCS registration message, evaluates the
//     hardware attestation chain server-side (RKP-aware, dual official roots, revocation,
//     application binding, verified boot) and writes the signing registration plus the
//     recipient encryption-key registry entry. A chain that is PRESENTED but invalid is
//     always an error (§8.3.6) — only an explicitly requested unattested enrollment may
//     skip attestation, and an existing ACTIVE_HARDWARE registration needs an explicit
//     acknowledgement before it may be lowered.
//
// Identity is server-derived throughout: the signing key ID is recomputed from the SPKI
// and must equal the claimed ID (the ID is also inside the signed message), and the
// recipient key ID is recomputed from its SPKI and never accepted from the request.
//
// The registration message signed by the device is RFC 8785 JCS of
//   {v:1, ownerUid, installationId, keyId, challenge}
// over the challenge's base64url string. Phase 3's Kotlin recorder must emit exactly
// these bytes.

const crypto = require('node:crypto');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');

const { consumeLimit } = require('./rate-window');
const {
  IDEMPOTENCY_RETENTION_MS,
  isValidRequestId,
  isWithinRequestSizeLimit,
  canonicalJson,
} = require('./pairing-guards');
const { verifyAttestation, fetchRevocationEntries } = require('./attestation');
const { COLLECTIONS } = require('./partner-events');

const REGION = 'us-central1';

const CHALLENGE_TTL_MS = 5 * 60 * 1000;
const CHALLENGE_WINDOW_MS = 60 * 60 * 1000;
const CHALLENGE_LIMIT_MAX = 20;
const REGISTRATION_WINDOW_MS = 60 * 60 * 1000;
const REGISTRATION_LIMIT_MAX = 10;

const INTENTS = Object.freeze({ ENROLL: 'ENROLL', ROTATE: 'ROTATE' });
const REGISTRATION_MESSAGE_VERSION = 1;
const ROTATION_AUDIT_MAX = 20;

// §8.3.4/§8.3.5: the attested key must belong to this package and be signed with the
// release certificate. Both are public release identity, not secrets.
const APP_PACKAGE = 'com.example.myexodus';
const APP_SIGNATURE_SHA256 = '8f88188070952dde4c490a01ce8aa0407e9172ee4bbc035693ea795ab598854b';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
// base64url(SHA-256(SPKI DER)) is always 43 characters, unpadded.
const KEY_ID = /^[A-Za-z0-9_-]{43}$/;
const CHALLENGE_VALUE = /^[A-Za-z0-9_-]{43}$/;

const sha256Hex = (value) => crypto.createHash('sha256').update(value).digest('hex');

/** Parses a base64 SPKI DER as an EC P-256 key; null when it is anything else. */
function p256Spki(text) {
  if (typeof text !== 'string' || text.length === 0 || text.length > 1024) return null;
  try {
    const der = Buffer.from(text, 'base64');
    const key = crypto.createPublicKey({ key: der, format: 'der', type: 'spki' });
    const details = key.asymmetricKeyDetails || {};
    if (key.asymmetricKeyType !== 'ec' || details.namedCurve !== 'prime256v1') return null;
    return { der, key, keyId: crypto.createHash('sha256').update(der).digest('base64url') };
  } catch {
    return null;
  }
}

function registrationMessage({ ownerUid, installationId, keyId, challenge }) {
  return Buffer.from(canonicalJson({
    v: REGISTRATION_MESSAGE_VERSION, ownerUid, installationId, keyId, challenge,
  }), 'utf8');
}

function createRegisterDeviceChallengeHandler({ db, now = () => Date.now() }) {
  return async function registerDeviceChallengeHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const data = request.data;
    if (data === null || typeof data !== 'object' || Array.isArray(data)) {
      throw new HttpsError('invalid-argument', 'REQUEST_INVALID');
    }
    if (!isWithinRequestSizeLimit(data)) {
      throw new HttpsError('invalid-argument', 'REQUEST_TOO_LARGE');
    }
    if (typeof data.installationId !== 'string' || !UUID_V4.test(data.installationId)) {
      throw new HttpsError('invalid-argument', 'INSTALLATION_ID_INVALID');
    }
    if (data.intent !== INTENTS.ENROLL && data.intent !== INTENTS.ROTATE) {
      throw new HttpsError('invalid-argument', 'INTENT_INVALID');
    }
    const deletion = await db.doc(`deletionJobs/${auth.uid}`).get();
    if (deletion.exists) throw new HttpsError('permission-denied', 'ACCOUNT_DELETING');

    const at = now();
    const allowed = await consumeLimit(
      db, `rate-limits/partnerChallenge_${auth.uid}`,
      CHALLENGE_LIMIT_MAX, CHALLENGE_WINDOW_MS, at,
    );
    if (!allowed) throw new HttpsError('resource-exhausted', 'RATE_LIMITED');

    const challenge = crypto.randomBytes(32);
    const challengeId = sha256Hex(`${auth.uid}:${data.installationId}:${data.intent}`);
    await db.collection(COLLECTIONS.deviceChallenges).doc(challengeId).set({
      ownerUid: auth.uid,
      installationId: data.installationId,
      intent: data.intent,
      // The raw 32 bytes, so the stored value is independent of the transport encoding.
      challengeHashHex: sha256Hex(challenge),
      issuedAt: at,
      expiresAt: at + CHALLENGE_TTL_MS,
      consumedAt: null,
    });

    return {
      challengeId,
      challenge: challenge.toString('base64url'),
      expiresAt: at + CHALLENGE_TTL_MS,
    };
  };
}

function createRegisterOrRotateDeviceHandler({
  db, now = () => Date.now(),
  attestationVerifier = verifyAttestation,
  revocationSource = fetchRevocationEntries,
}) {
  return async function registerOrRotateDeviceHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const data = request.data;
    if (data === null || typeof data !== 'object' || Array.isArray(data)) {
      throw new HttpsError('invalid-argument', 'REQUEST_INVALID');
    }
    if (!isWithinRequestSizeLimit(data)) {
      throw new HttpsError('invalid-argument', 'REQUEST_TOO_LARGE');
    }
    if (!isValidRequestId(data.requestId)) {
      throw new HttpsError('invalid-argument', 'REQUEST_ID_INVALID');
    }
    if (typeof data.challengeId !== 'string' || !/^[0-9a-f]{64}$/.test(data.challengeId)) {
      throw new HttpsError('invalid-argument', 'CHALLENGE_ID_INVALID');
    }
    if (data.intent !== INTENTS.ENROLL && data.intent !== INTENTS.ROTATE) {
      throw new HttpsError('invalid-argument', 'INTENT_INVALID');
    }
    if (typeof data.installationId !== 'string' || !UUID_V4.test(data.installationId)) {
      throw new HttpsError('invalid-argument', 'INSTALLATION_ID_INVALID');
    }
    if (typeof data.keyId !== 'string' || !KEY_ID.test(data.keyId)) {
      throw new HttpsError('invalid-argument', 'KEY_ID_INVALID');
    }
    if (typeof data.challenge !== 'string' || !CHALLENGE_VALUE.test(data.challenge)) {
      throw new HttpsError('invalid-argument', 'CHALLENGE_INVALID');
    }
    if (typeof data.registrationSignature !== 'string' ||
        !/^[A-Za-z0-9_-]{64,128}$/.test(data.registrationSignature)) {
      throw new HttpsError('invalid-argument', 'SIGNATURE_INVALID');
    }

    // §9 idempotency: the replay check precedes rate limiting and every state transition.
    const payload = { ...data };
    delete payload.requestId;
    const requestHash = sha256Hex(`registerOrRotateDevice\n${canonicalJson(payload)}`);
    const idempotencyRef = db.doc(`idempotency/${data.requestId}`);
    const cached = await idempotencyRef.get();
    if (cached.exists) {
      if (cached.get('requestHash') !== requestHash) {
        throw new HttpsError('invalid-argument', 'REQUEST_ID_REUSED');
      }
      return cached.get('result');
    }

    const signing = p256Spki(data.signingPublicKey);
    const recipient = p256Spki(data.recipientPublicKey);
    if (!signing || !recipient) throw new HttpsError('invalid-argument', 'PUBLIC_KEY_INVALID');
    if (signing.keyId !== data.keyId) throw new HttpsError('invalid-argument', 'KEY_ID_MISMATCH');

    const message = registrationMessage({
      ownerUid: auth.uid,
      installationId: data.installationId,
      keyId: data.keyId,
      challenge: data.challenge,
    });
    let possession = false;
    try {
      possession = crypto.verify(
        'sha256', message, signing.key, Buffer.from(data.registrationSignature, 'base64url'),
      );
    } catch {
      possession = false;
    }
    if (!possession) throw new HttpsError('invalid-argument', 'SIGNATURE_INVALID');

    // §8.3.5/§8.3.6: a presented chain is authoritative and must validate. An absent chain
    // is only acceptable as an explicit unattested enrollment.
    const presented = Array.isArray(data.attestationChain) && data.attestationChain.length > 0;
    let integrityTier;
    if (presented) {
      if (data.attestationChallenge !== data.challenge) {
        throw new HttpsError('invalid-argument', 'ATTESTATION_CHALLENGE_MISMATCH');
      }
      let revocationEntries;
      try {
        revocationEntries = await revocationSource({ now: now() });
      } catch (e) {
        logger.warn('registerOrRotateDevice revocation unavailable', {
          reason: e && e.message ? e.message : 'unknown',
        });
        throw new HttpsError('unavailable', 'REVOCATION_UNAVAILABLE');
      }
      let verification;
      try {
        verification = attestationVerifier(
          {
            signingPublicKey: data.signingPublicKey,
            attestationChain: data.attestationChain,
            attestationChallenge: data.attestationChallenge,
          },
          undefined,
          now(),
          {
            applicationId: {
              packageName: APP_PACKAGE,
              signatureDigestSha256: APP_SIGNATURE_SHA256,
            },
            requireVerifiedBoot: true,
            revocationEntries,
          },
        );
      } catch {
        verification = { ok: false, reason: 'ATTESTATION_INVALID' };
      }
      if (!verification.ok) {
        logger.warn('registerOrRotateDevice rejected', { reason: verification.reason });
        throw new HttpsError('invalid-argument', verification.reason);
      }
      integrityTier = 'ACTIVE_HARDWARE';
    } else {
      if (data.allowUnattested !== true) {
        throw new HttpsError('invalid-argument', 'ATTESTATION_REQUIRED');
      }
      integrityTier = 'ACTIVE_UNATTESTED';
    }

    const at = now();
    const allowed = await consumeLimit(
      db, `rate-limits/partnerRegistration_${auth.uid}`,
      REGISTRATION_LIMIT_MAX, REGISTRATION_WINDOW_MS, at,
    );
    if (!allowed) throw new HttpsError('resource-exhausted', 'RATE_LIMITED');

    const regRef = db.doc(`${COLLECTIONS.deviceRegistrations}/${auth.uid}:${data.installationId}`);
    const challengeRef = db.collection(COLLECTIONS.deviceChallenges).doc(data.challengeId);
    const recipientKeyRef = db.doc(`${COLLECTIONS.recipientKeys}/${recipient.keyId}`);

    const result = await db.runTransaction(async (tx) => {
      const [challengeSnap, regSnap, deletionSnap] = await Promise.all([
        tx.get(challengeRef),
        tx.get(regRef),
        tx.get(db.doc(`deletionJobs/${auth.uid}`)),
      ]);
      if (deletionSnap.exists) throw new HttpsError('permission-denied', 'ACCOUNT_DELETING');

      const existing = regSnap.exists ? regSnap.data() : null;
      const existingActive = Boolean(existing && existing.status === 'ACTIVE');

      if (!challengeSnap.exists) throw new HttpsError('invalid-argument', 'CHALLENGE_INVALID');
      const stored = challengeSnap.data();
      if (stored.ownerUid !== auth.uid || stored.installationId !== data.installationId ||
          stored.intent !== data.intent ||
          stored.challengeHashHex !== sha256Hex(Buffer.from(data.challenge, 'base64url'))) {
        throw new HttpsError('invalid-argument', 'CHALLENGE_INVALID');
      }
      if (stored.consumedAt !== null && stored.consumedAt !== undefined) {
        // A lost-response retry whose idempotency cache write failed: the same key is
        // already committed, so report it instead of failing a completed registration.
        if (existingActive && existing.signingKeyId === data.keyId &&
            existing.recipientKeyId === recipient.keyId) {
          return {
            installationId: data.installationId,
            keyId: existing.signingKeyId,
            recipientKeyId: existing.recipientKeyId,
            integrityTier: existing.integrityTier,
            registrationRevision: existing.registrationRevision,
            status: 'ACTIVE',
          };
        }
        throw new HttpsError('invalid-argument', 'CHALLENGE_INVALID');
      }
      if (stored.expiresAt <= at) throw new HttpsError('invalid-argument', 'CHALLENGE_EXPIRED');

      if (data.intent === INTENTS.ENROLL && existingActive &&
          existing.signingKeyId !== data.keyId) {
        throw new HttpsError('failed-precondition', 'ROTATION_REQUIRED');
      }
      if (data.intent === INTENTS.ROTATE && !existingActive) {
        throw new HttpsError('failed-precondition', 'ROTATION_NOT_ENROLLED');
      }
      // §8.3.6: ACTIVE_HARDWARE never silently downgrades.
      if (integrityTier === 'ACTIVE_UNATTESTED' && existingActive &&
          existing.integrityTier === 'ACTIVE_HARDWARE' &&
          data.acknowledgeHardwareDowngrade !== true) {
        throw new HttpsError('failed-precondition', 'HARDWARE_DOWNGRADE_NOT_ACKNOWLEDGED');
      }

      const revision = existingActive ? (existing.registrationRevision || 0) + 1 : 1;
      const audit = Array.isArray(existing && existing.rotationAudit)
        ? existing.rotationAudit.slice(-(ROTATION_AUDIT_MAX - 1))
        : [];
      if (existing) {
        audit.push({
          fromSigningKeyId: existing.signingKeyId,
          fromRecipientKeyId: existing.recipientKeyId,
          toSigningKeyId: data.keyId,
          toRecipientKeyId: recipient.keyId,
          integrityTier,
          at,
        });
      }

      tx.set(challengeRef, { consumedAt: at }, { merge: true });
      tx.set(regRef, {
        ownerUid: auth.uid,
        installationId: data.installationId,
        signingKeyId: data.keyId,
        signingSpkiDerBase64: data.signingPublicKey,
        recipientKeyId: recipient.keyId,
        integrityTier,
        registrationRevision: revision,
        status: 'ACTIVE',
        activatedAt: existing ? existing.activatedAt : at,
        revokedAt: null,
        lastRotatedAt: at,
        rotationAudit: audit,
      }, { merge: false });
      tx.set(recipientKeyRef, {
        ownerUid: auth.uid,
        installationId: data.installationId,
        keyId: recipient.keyId,
        algorithm: 'ECDH-P256',
        spkiDerBase64: data.recipientPublicKey,
        revision,
        status: 'ACTIVE',
        registeredAt: at,
        revokedAt: null,
        replacedByKeyId: null,
      }, { merge: false });
      if (existing && existing.recipientKeyId && existing.recipientKeyId !== recipient.keyId) {
        tx.set(
          db.doc(`${COLLECTIONS.recipientKeys}/${existing.recipientKeyId}`),
          { status: 'REVOKED', revokedAt: at, replacedByKeyId: recipient.keyId },
          { merge: true },
        );
      }
      return {
        installationId: data.installationId,
        keyId: data.keyId,
        recipientKeyId: recipient.keyId,
        integrityTier,
        registrationRevision: revision,
        status: 'ACTIVE',
      };
    });

    try {
      await idempotencyRef.set({
        functionName: 'registerOrRotateDevice',
        requestHash,
        result,
        createdAt: Timestamp.now(),
        expiresAt: Timestamp.fromMillis(Date.now() + IDEMPOTENCY_RETENTION_MS),
      });
    } catch (e) {
      logger.warn('idempotency record write failed', {
        reason: e && e.message ? e.message : 'unknown',
      });
    }

    logger.info('registerOrRotateDevice', {
      installationId: data.installationId,
      integrityTier,
      intent: data.intent,
      revision: result.registrationRevision,
    });
    return result;
  };
}

function createRegisterDeviceChallenge({ db = getFirestore(), now } = {}) {
  return onCall(
    {
      region: REGION,
      enforceAppCheck: true,
      maxInstances: 10,
      timeoutSeconds: 15,
    },
    createRegisterDeviceChallengeHandler(now ? { db, now } : { db }),
  );
}

function createRegisterOrRotateDevice({
  db = getFirestore(), now, attestationVerifier, revocationSource,
} = {}) {
  const options = { db };
  if (now) options.now = now;
  if (attestationVerifier) options.attestationVerifier = attestationVerifier;
  if (revocationSource) options.revocationSource = revocationSource;
  return onCall(
    {
      region: REGION,
      enforceAppCheck: true,
      maxInstances: 10,
      // One registration transaction plus one attestation verification.
      timeoutSeconds: 30,
    },
    createRegisterOrRotateDeviceHandler(options),
  );
}

exports.p256Spki = p256Spki;
exports.createRegisterDeviceChallenge = createRegisterDeviceChallenge;
exports.createRegisterDeviceChallengeHandler = createRegisterDeviceChallengeHandler;
exports.createRegisterOrRotateDevice = createRegisterOrRotateDevice;
exports.createRegisterOrRotateDeviceHandler = createRegisterOrRotateDeviceHandler;
exports.APP_PACKAGE = APP_PACKAGE;
exports.APP_SIGNATURE_SHA256 = APP_SIGNATURE_SHA256;
exports.INTENTS = INTENTS;
