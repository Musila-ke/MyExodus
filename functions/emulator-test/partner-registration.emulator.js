'use strict';
// Device registration/rotation suite (§8.3) — the real handlers against the Firestore
// emulator. Named without `.test.js` on purpose: `npm test` stays emulator-free, so this
// file runs only via `npm run test:partner-registration`.
//
// The hardware verifier is injected: the emulator cannot produce a Google-rooted chain
// (ADR #96), and attestation.js has its own unit suite. What is under test here is the
// registration protocol around it — challenge scoping and consumption, proof of
// possession, intent/state transitions, rotation bookkeeping, the unattested rules and the
// exactly-once transaction. The registration message bytes are rebuilt independently in
// this file (not with the server's canonicalizer) so the wire format Phase 3's Kotlin
// writer must produce is pinned by a second implementation.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { HttpsError } = require('firebase-functions/v2/https');

const {
  createRegisterDeviceChallengeHandler,
  createRegisterOrRotateDeviceHandler,
  APP_PACKAGE,
  APP_SIGNATURE_SHA256,
} = require('../partner-registration');

const PROJECT_ID = 'demo-myexodus-partner-registration';
const ALICE = 'alice-uid';
const INSTALL = '3f0f2a0e-7c33-4b7a-9e1f-2a1b3c4d5e6f';
const INSTALL_2 = '8a7b6c5d-4e3f-4a2b-8c1d-0e9f8a7b6c5d';

const NOW = Date.UTC(2026, 10, 11, 12, 0, 0);

let app;
let db;
let clockMs;
let requestSeq;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'partner-registration-emulator-suite');
  db = getFirestore(app);
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  clockMs = NOW;
  requestSeq = 0;
  for (const collection of [
    'partnerDeviceSigningRegistrations', 'partnerDeviceChallenges', 'partnerRecipientKeys',
    'idempotency', 'rate-limits', 'deletionJobs',
  ]) {
    await db.recursiveDelete(db.collection(collection));
  }
});

function handlers({ verifier, revocation } = {}) {
  return {
    challenge: createRegisterDeviceChallengeHandler({ db, now: () => clockMs }),
    register: createRegisterOrRotateDeviceHandler({
      db,
      now: () => clockMs,
      attestationVerifier: verifier || (() => ({ ok: true, securityLevel: 2 })),
      revocationSource: revocation || (async () => ({})),
    }),
  };
}

const call = (handler, data, uid = ALICE) =>
  handler({ auth: uid ? { uid } : undefined, data });

const nextRequestId = () => `req-${String(++requestSeq).padStart(8, '0')}`;

function makeDevice() {
  const signing = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const recipient = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const signingSpki = signing.publicKey.export({ type: 'spki', format: 'der' });
  const recipientSpki = recipient.publicKey.export({ type: 'spki', format: 'der' });
  return {
    signing,
    signingPublicKey: signingSpki.toString('base64'),
    keyId: crypto.createHash('sha256').update(signingSpki).digest('base64url'),
    recipientPublicKey: recipientSpki.toString('base64'),
    recipientKeyId: crypto.createHash('sha256').update(recipientSpki).digest('base64url'),
  };
}

/** The exact signed bytes, independent of the server's canonicalizer. */
function registrationMessage({ ownerUid, installationId, keyId, challenge }) {
  return Buffer.from(
    `{"challenge":${JSON.stringify(challenge)}` +
    `,"installationId":${JSON.stringify(installationId)}` +
    `,"keyId":${JSON.stringify(keyId)}` +
    `,"ownerUid":${JSON.stringify(ownerUid)},"v":1}`,
    'utf8',
  );
}

const signRegistration = (device, fields) =>
  crypto.sign('sha256', registrationMessage(fields), device.signing.privateKey).toString('base64url');

async function issueChallenge(h, { uid = ALICE, installationId = INSTALL, intent = 'ENROLL' } = {}) {
  return call(h.challenge, { installationId, intent }, uid);
}

async function registrationRequest(h, {
  device = makeDevice(), uid = ALICE, installationId = INSTALL, intent = 'ENROLL', extra = {},
} = {}) {
  const { challengeId, challenge } = await issueChallenge(h, { uid, installationId, intent });
  return {
    device,
    data: {
      requestId: nextRequestId(),
      challengeId,
      intent,
      installationId,
      keyId: device.keyId,
      signingPublicKey: device.signingPublicKey,
      recipientPublicKey: device.recipientPublicKey,
      challenge,
      registrationSignature: signRegistration(device, {
        ownerUid: uid, installationId, keyId: device.keyId, challenge,
      }),
      allowUnattested: true,
      ...extra,
    },
  };
}

async function expectRejection(promise, code, message) {
  try {
    await promise;
    assert.fail(`expected HttpsError(${code})`);
  } catch (e) {
    assert.ok(e instanceof HttpsError, `expected HttpsError, got ${e && e.constructor && e.constructor.name}`);
    assert.strictEqual(e.code, code, `expected code ${code}, got ${e.code}: ${e.message}`);
    if (message) assert.strictEqual(e.message, message);
    return e;
  }
}

const regPath = (uid = ALICE, installationId = INSTALL) =>
  `partnerDeviceSigningRegistrations/${uid}:${installationId}`;

test('an unauthenticated caller is refused', async () => {
  const h = handlers();
  await expectRejection(call(h.challenge, { installationId: INSTALL, intent: 'ENROLL' }, null),
    'unauthenticated');
  await expectRejection(call(h.register, {}, null), 'unauthenticated');
});

test('challenge issue stores only the hash, expires in five minutes, and rejects bad intents', async () => {
  const h = handlers();
  const reply = await issueChallenge(h);

  assert.strictEqual(typeof reply.challengeId, 'string');
  assert.strictEqual(reply.challenge.length, 43);
  assert.strictEqual(reply.expiresAt, NOW + 5 * 60 * 1000);

  const stored = (await db.doc(`partnerDeviceChallenges/${reply.challengeId}`).get()).data();
  assert.strictEqual(stored.ownerUid, ALICE);
  assert.strictEqual(stored.installationId, INSTALL);
  assert.strictEqual(stored.intent, 'ENROLL');
  assert.strictEqual(stored.challengeHashHex,
    crypto.createHash('sha256').update(Buffer.from(reply.challenge, 'base64url')).digest('hex'));
  assert.strictEqual(stored.consumedAt, null);
  assert.notStrictEqual(stored.challengeHashHex, reply.challenge);

  for (const data of [
    { installationId: 'not-a-uuid', intent: 'ENROLL' },
    { installationId: INSTALL, intent: 'OTHER' },
    { installationId: INSTALL },
    null,
  ]) {
    await expectRejection(call(h.challenge, data), 'invalid-argument');
  }
});

test('a valid unattested enrollment writes the registration and the recipient key', async () => {
  const h = handlers();
  const { device, data } = await registrationRequest(h);
  const result = await call(h.register, data);

  assert.deepStrictEqual(result, {
    installationId: INSTALL,
    keyId: device.keyId,
    recipientKeyId: device.recipientKeyId,
    integrityTier: 'ACTIVE_UNATTESTED',
    registrationRevision: 1,
    status: 'ACTIVE',
  });

  const registration = (await db.doc(regPath()).get()).data();
  assert.strictEqual(registration.ownerUid, ALICE);
  assert.strictEqual(registration.signingKeyId, device.keyId);
  assert.strictEqual(registration.signingSpkiDerBase64, device.signingPublicKey);
  assert.strictEqual(registration.recipientKeyId, device.recipientKeyId);
  assert.strictEqual(registration.status, 'ACTIVE');
  assert.strictEqual(registration.activatedAt, NOW);
  assert.deepStrictEqual(registration.rotationAudit, []);

  const recipientKey = (await db.doc(`partnerRecipientKeys/${device.recipientKeyId}`).get()).data();
  assert.strictEqual(recipientKey.ownerUid, ALICE);
  assert.strictEqual(recipientKey.status, 'ACTIVE');
  assert.strictEqual(recipientKey.algorithm, 'ECDH-P256');

  const challenge = (await db.doc(`partnerDeviceChallenges/${data.challengeId}`).get()).data();
  assert.strictEqual(challenge.consumedAt, NOW);
});

test('a replayed requestId returns the committed result; a changed payload is refused', async () => {
  const h = handlers();
  const { data } = await registrationRequest(h);
  const first = await call(h.register, data);
  clockMs += 1000;
  const before = (await db.doc(regPath()).get()).data();

  assert.deepStrictEqual(await call(h.register, { ...data }), first);
  const after = (await db.doc(regPath()).get()).data();
  assert.strictEqual(after.registrationRevision, before.registrationRevision);
  assert.strictEqual(after.lastRotatedAt, before.lastRotatedAt);

  await expectRejection(call(h.register, { ...data, force: true }), 'invalid-argument', 'REQUEST_ID_REUSED');
});

test('a consumed challenge with the same committed key heals instead of failing', async () => {
  const h = handlers();
  const { device, data } = await registrationRequest(h);
  const first = await call(h.register, data);

  // Same challenge, new requestId: the registration already committed (e.g. the
  // idempotency cache write was lost), so the retry must report success.
  const replay = { ...data, requestId: nextRequestId() };
  assert.deepStrictEqual(await call(h.register, replay), first);
});

test('proof of possession, key identity and challenge possession are enforced', async () => {
  const h = handlers();
  const { device, data } = await registrationRequest(h);

  await expectRejection(call(h.register, { ...data, registrationSignature: 'A'.repeat(86) }),
    'invalid-argument', 'SIGNATURE_INVALID');

  const other = makeDevice();
  await expectRejection(
    call(h.register, { ...data, signingPublicKey: other.signingPublicKey }),
    'invalid-argument', 'KEY_ID_MISMATCH',
  );

  // Re-signed so possession passes: the stored challenge hash is what must reject it.
  const otherChallenge = crypto.randomBytes(32).toString('base64url');
  await expectRejection(
    call(h.register, {
      ...data,
      challenge: otherChallenge,
      registrationSignature: signRegistration(device, {
        ownerUid: ALICE, installationId: INSTALL, keyId: device.keyId, challenge: otherChallenge,
      }),
    }),
    'invalid-argument', 'CHALLENGE_INVALID',
  );

  // A registration doc was never created by any of the failed attempts.
  assert.strictEqual((await db.doc(regPath()).get()).exists, false);
});

test('an expired challenge is refused', async () => {
  const h = handlers();
  const { data } = await registrationRequest(h);
  clockMs += 5 * 60 * 1000;
  await expectRejection(call(h.register, data), 'invalid-argument', 'CHALLENGE_EXPIRED');
  assert.strictEqual((await db.doc(regPath()).get()).exists, false);
});

test('a challenge is bound to the intent it was issued for', async () => {
  const h = handlers();
  const enrolled = await registrationRequest(h);
  await call(h.register, enrolled.data);

  for (const [issued, presented] of [['ROTATE', 'ENROLL'], ['ENROLL', 'ROTATE']]) {
    const device = makeDevice();
    const { challengeId, challenge } = await issueChallenge(h, { intent: issued });
    await expectRejection(
      call(h.register, {
        requestId: nextRequestId(),
        challengeId,
        challenge,
        intent: presented,
        installationId: INSTALL,
        keyId: device.keyId,
        signingPublicKey: device.signingPublicKey,
        recipientPublicKey: device.recipientPublicKey,
        registrationSignature: signRegistration(device, {
          ownerUid: ALICE, installationId: INSTALL, keyId: device.keyId, challenge,
        }),
        allowUnattested: true,
      }),
      'invalid-argument', 'CHALLENGE_INVALID',
    );
  }

  // The failed attempts never replaced the committed key.
  assert.strictEqual(
    (await db.doc(regPath()).get()).data().signingKeyId, enrolled.device.keyId,
  );
});

test('ENROLL cannot replace an ACTIVE key, and ROTATE requires an enrollment', async () => {
  const h = handlers();
  const rotateBefore = await registrationRequest(h, { intent: 'ROTATE' });
  await expectRejection(call(h.register, rotateBefore.data), 'failed-precondition', 'ROTATION_NOT_ENROLLED');

  const { data } = await registrationRequest(h);
  await call(h.register, data);

  const different = makeDevice();
  const replacement = await registrationRequest(h, { device: different, intent: 'ENROLL' });
  await expectRejection(call(h.register, replacement.data), 'failed-precondition', 'ROTATION_REQUIRED');
  assert.strictEqual(
    (await db.doc(regPath()).get()).data().signingKeyId, data.keyId,
  );
});

test('rotation revokes the previous recipient key and records the audit entry', async () => {
  const h = handlers();
  const first = await registrationRequest(h);
  await call(h.register, first.data);

  const secondDevice = makeDevice();
  const second = await registrationRequest(h, { device: secondDevice, intent: 'ROTATE' });
  const result = await call(h.register, second.data);
  assert.strictEqual(result.registrationRevision, 2);
  assert.strictEqual(result.keyId, secondDevice.keyId);

  const oldKey = (await db.doc(`partnerRecipientKeys/${first.device.recipientKeyId}`).get()).data();
  assert.strictEqual(oldKey.status, 'REVOKED');
  assert.strictEqual(oldKey.revokedAt, NOW);
  assert.strictEqual(oldKey.replacedByKeyId, secondDevice.recipientKeyId);

  const registration = (await db.doc(regPath()).get()).data();
  assert.strictEqual(registration.signingKeyId, secondDevice.keyId);
  assert.strictEqual(registration.recipientKeyId, secondDevice.recipientKeyId);
  assert.strictEqual(registration.registrationRevision, 2);
  assert.deepStrictEqual(registration.rotationAudit, [{
    fromSigningKeyId: first.device.keyId,
    fromRecipientKeyId: first.device.recipientKeyId,
    toSigningKeyId: secondDevice.keyId,
    toRecipientKeyId: secondDevice.recipientKeyId,
    integrityTier: 'ACTIVE_UNATTESTED',
    at: NOW,
  }]);
});

test('the attestation verifier receives the pinned application binding and boot/revocation options', async () => {
  const h = handlers();
  const evidence = [];
  const verifier = (proof, anchors, at, options) => {
    evidence.push({ proof, anchors, at, options });
    return { ok: true, securityLevel: 2 };
  };
  const hw = handlers({ verifier, revocation: async () => ({ abc123: { status: 'REVOKED', expires: null } }) });

  const { data } = await registrationRequest(hw, {
    extra: {
      allowUnattested: false,
      attestationChain: ['Zm9v', 'YmFy'],
      attestationChallenge: null,
    },
  });
  const challenge = data.challenge;
  const result = await call(hw.register, { ...data, attestationChallenge: challenge });

  assert.strictEqual(result.integrityTier, 'ACTIVE_HARDWARE');
  assert.strictEqual(evidence.length, 1);
  assert.deepStrictEqual(evidence[0].proof, {
    signingPublicKey: data.signingPublicKey,
    attestationChain: ['Zm9v', 'YmFy'],
    attestationChallenge: challenge,
  });
  assert.strictEqual(evidence[0].at, NOW);
  assert.deepStrictEqual(evidence[0].options.applicationId, {
    packageName: APP_PACKAGE,
    signatureDigestSha256: APP_SIGNATURE_SHA256,
  });
  assert.strictEqual(evidence[0].options.requireVerifiedBoot, true);
  assert.deepStrictEqual(evidence[0].options.revocationEntries,
    { abc123: { status: 'REVOKED', expires: null } });
});

test('a presented chain is authoritative: failures never silently downgrade', async () => {
  const failing = handlers({ verifier: () => ({ ok: false, reason: 'CHAIN_UNTRUSTED' }) });
  const { data } = await registrationRequest(failing, {
    extra: { allowUnattested: true, attestationChain: ['Zm9v'], attestationChallenge: null },
  });
  await expectRejection(
    call(failing.register, { ...data, attestationChallenge: data.challenge }),
    'invalid-argument', 'CHAIN_UNTRUSTED',
  );
  assert.strictEqual((await db.doc(regPath()).get()).exists, false);

  const offline = handlers({ revocation: async () => { throw new Error('offline'); } });
  const next = await registrationRequest(offline, {
    extra: { allowUnattested: true, attestationChain: ['Zm9v'], attestationChallenge: null },
  });
  await expectRejection(
    call(offline.register, { ...next.data, attestationChallenge: next.data.challenge }),
    'unavailable', 'REVOCATION_UNAVAILABLE',
  );

  const mismatched = handlers();
  const third = await registrationRequest(mismatched, {
    extra: { allowUnattested: true, attestationChain: ['Zm9v'], attestationChallenge: null },
  });
  await expectRejection(
    call(mismatched.register, {
      ...third.data, attestationChallenge: crypto.randomBytes(32).toString('base64url'),
    }),
    'invalid-argument', 'ATTESTATION_CHALLENGE_MISMATCH',
  );
});

test('skipping attestation requires the explicit opt-in', async () => {
  const h = handlers();
  const { data } = await registrationRequest(h, { extra: { allowUnattested: false } });
  await expectRejection(call(h.register, data), 'invalid-argument', 'ATTESTATION_REQUIRED');
});

test('ACTIVE_HARDWARE never silently downgrades', async () => {
  const h = handlers();
  const hardware = await registrationRequest(h, {
    extra: { allowUnattested: false, attestationChain: ['Zm9v'], attestationChallenge: null },
  });
  await call(h.register, { ...hardware.data, attestationChallenge: hardware.data.challenge });
  assert.strictEqual(
    (await db.doc(regPath()).get()).data().integrityTier, 'ACTIVE_HARDWARE',
  );

  const unattested = await registrationRequest(h, { intent: 'ROTATE' });
  await expectRejection(call(h.register, unattested.data),
    'failed-precondition', 'HARDWARE_DOWNGRADE_NOT_ACKNOWLEDGED');

  const acknowledged = await registrationRequest(h, {
    intent: 'ROTATE', extra: { acknowledgeHardwareDowngrade: true },
  });
  const result = await call(h.register, acknowledged.data);
  assert.strictEqual(result.integrityTier, 'ACTIVE_UNATTESTED');
});

test('an account being deleted cannot register', async () => {
  const h = handlers();
  const { data } = await registrationRequest(h);
  await db.doc(`deletionJobs/${ALICE}`).set({ status: 'QUEUED' });
  await expectRejection(call(h.register, data), 'permission-denied', 'ACCOUNT_DELETING');
  await expectRejection(
    call(h.challenge, { installationId: INSTALL, intent: 'ENROLL' }),
    'permission-denied', 'ACCOUNT_DELETING',
  );
});

test('challenge and registration rate limits engage per uid', async () => {
  const h = handlers();
  for (let i = 0; i < 20; i += 1) await issueChallenge(h);
  await expectRejection(issueChallenge(h), 'resource-exhausted', 'RATE_LIMITED');

  clockMs += 60 * 60 * 1000;
  const device = makeDevice();
  for (let i = 0; i < 10; i += 1) {
    const { data } = await registrationRequest(h, { device });
    await call(h.register, data);
  }
  const eleventh = await registrationRequest(h, { device });
  await expectRejection(call(h.register, eleventh.data), 'resource-exhausted', 'RATE_LIMITED');
});

test('registrations are scoped to (uid, installation)', async () => {
  const h = handlers();
  const a = await registrationRequest(h);
  await call(h.register, a.data);

  const b = await registrationRequest(h, { installationId: INSTALL_2 });
  await call(h.register, b.data);

  assert.strictEqual((await db.doc(regPath()).get()).data().signingKeyId, a.device.keyId);
  assert.strictEqual(
    (await db.doc(regPath(ALICE, INSTALL_2)).get()).data().signingKeyId, b.device.keyId,
  );
});
