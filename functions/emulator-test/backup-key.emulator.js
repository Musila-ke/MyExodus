'use strict';
// Backup key-bundle callable suite — the real handlers against the Firestore emulator
// (account plan §7 Release 2 step 12 / §6.4-§6.5 / §8.5). Run via `npm run test:backup-key`.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { HttpsError } = require('firebase-functions/v2/https');

const {
  createInitializeBackupKeyBundleHandler,
  createUpdateBackupKeyBundleHandler,
} = require('../backup-key');
const { ARGON2, BUNDLE_FORMAT_VERSION } = require('../backup-key-logic');

const PROJECT_ID = 'demo-myexodus-backup-key';
const USER = 'backup-user-uid';
const OTHER = 'other-backup-user-uid';

let app;
let db;
let initialize;
let update;

const b64 = (bytes) => crypto.randomBytes(bytes).toString('base64');

function bundle(seed = '') {
  const h = (label, bytes) =>
    Buffer.concat([
      crypto.createHash('sha256').update(`${seed}:${label}`).digest(),
      crypto.createHash('sha256').update(`${seed}:${label}:2`).digest(),
    ])
      .subarray(0, bytes)
      .toString('base64');
  return {
    bundleFormatVersion: BUNDLE_FORMAT_VERSION,
    keyId: h('keyId', 16),
    argon2: { ...ARGON2 },
    passphraseSalt: h('passphraseSalt', 16),
    passphraseWrapper: { nonce: h('passphraseNonce', 12), ciphertext: h('passphraseWrap', 48) },
    recoveryKeyId: h('recoveryKeyId', 16),
    recoveryHkdfSalt: h('recoveryHkdfSalt', 16),
    recoveryWrapper: { nonce: h('recoveryNonce', 12), ciphertext: h('recoveryWrap', 48) },
  };
}

const authAs = (uid, provider, extraToken = {}) => ({
  auth: { uid, token: { firebase: { sign_in_provider: provider }, ...extraToken } },
});
const googleAuth = (uid = USER) => authAs(uid, 'google.com');
const verifiedPasswordAuth = (uid = USER) =>
  authAs(uid, 'password', { email_verified: true });
const anonymousAuth = (uid = USER) => authAs(uid, 'anonymous');

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

const seedProfile = (uid, status = 'ACTIVE') =>
  db.doc(`users/${uid}`).set({ status, profileVersion: 1, schemaVersion: 1 });

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'backup-key-emulator-suite');
  db = getFirestore(app);
  initialize = createInitializeBackupKeyBundleHandler({ db });
  update = createUpdateBackupKeyBundleHandler({ db });
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  for (const collection of ['users', 'deletionJobs']) {
    await db.recursiveDelete(db.collection(collection));
  }
});

test('initialize stores the wrapped bundle at revision 1 with server timestamps', async () => {
  await seedProfile(USER);
  const body = bundle('init');

  const result = await initialize({ ...googleAuth(), data: { bundle: body } });

  assert.strictEqual(result.revision, 1);
  assert.strictEqual(result.replayed, false);
  assert.deepStrictEqual(result.argon2, ARGON2);
  const stored = (await db.doc(`users/${USER}/backupControl/keyBundle`).get()).data();
  assert.strictEqual(stored.revision, 1);
  assert.strictEqual(stored.keyId, body.keyId);
  assert.strictEqual(stored.passphraseWrapper.ciphertext, body.passphraseWrapper.ciphertext);
  assert.ok(stored.createdAt && stored.updatedAt);
});

test('a byte-identical replay is idempotent and adds no revision', async () => {
  await seedProfile(USER);
  const body = bundle('replay');
  const first = await initialize({ ...googleAuth(), data: { bundle: body } });
  const before = (await db.doc(`users/${USER}/backupControl/keyBundle`).get()).data();

  const replay = await initialize({ ...googleAuth(), data: { bundle: body } });

  assert.strictEqual(first.revision, 1);
  assert.strictEqual(replay.revision, 1);
  assert.strictEqual(replay.replayed, true);
  const after = (await db.doc(`users/${USER}/backupControl/keyBundle`).get()).data();
  assert.strictEqual(after.createdAt.toMillis(), before.createdAt.toMillis());
  assert.strictEqual(after.updatedAt.toMillis(), before.updatedAt.toMillis());
});

test('a different bundle against an existing one is refused, never overwritten', async () => {
  await seedProfile(USER);
  await initialize({ ...googleAuth(), data: { bundle: bundle('first') } });

  await expectRejection(
    initialize({ ...googleAuth(), data: { bundle: bundle('second') } }),
    'already-exists',
    'ALREADY_INITIALIZED',
  );
});

test('initialize requires a persistent, verified, active account without a deletion job', async () => {
  await seedProfile(USER);
  await expectRejection(
    initialize({ ...anonymousAuth(), data: { bundle: bundle('anon') } }),
    'failed-precondition',
    'PERSISTENT_ACCOUNT_REQUIRED',
  );
  await expectRejection(
    initialize({
      ...authAs(USER, 'password', { email_verified: false }),
      data: { bundle: bundle('unverified') },
    }),
    'failed-precondition',
    'EMAIL_VERIFICATION_REQUIRED',
  );
  await expectRejection(
    initialize({ ...googleAuth('no-profile-uid'), data: { bundle: bundle('profile') } }),
    'failed-precondition',
    'PROFILE_NOT_ACTIVE',
  );
  await db.doc(`users/${USER}`).set({ status: 'PENDING_DELETION' });
  await expectRejection(
    initialize({ ...verifiedPasswordAuth(), data: { bundle: bundle('pending') } }),
    'failed-precondition',
    'PROFILE_NOT_ACTIVE',
  );
  await seedProfile(USER);
  await db.doc(`deletionJobs/${USER}`).set({ phase: 'QUEUED' });
  await expectRejection(
    initialize({ ...verifiedPasswordAuth(), data: { bundle: bundle('deleting') } }),
    'permission-denied',
    'ACCOUNT_DELETING',
  );
});

test('update advances the revision and keeps the bundle identity fields', async () => {
  await seedProfile(USER);
  const first = bundle('rotate');
  await initialize({ ...googleAuth(), data: { bundle: first } });

  const rewrapped = {
    ...first,
    passphraseSalt: b64(16),
    passphraseWrapper: { nonce: b64(12), ciphertext: b64(48) },
  };
  const result = await update({
    ...googleAuth(),
    data: { expectedRevision: 1, bundle: rewrapped },
  });

  assert.strictEqual(result.revision, 2);
  const stored = (await db.doc(`users/${USER}/backupControl/keyBundle`).get()).data();
  assert.strictEqual(stored.revision, 2);
  assert.strictEqual(stored.passphraseSalt, rewrapped.passphraseSalt);
  assert.strictEqual(stored.recoveryWrapper.ciphertext, first.recoveryWrapper.ciphertext);
  assert.strictEqual(stored.keyId, first.keyId);
});

test('a stale expected revision is an aborted CAS conflict with the current revision', async () => {
  await seedProfile(USER);
  const first = bundle('cas');
  await initialize({ ...googleAuth(), data: { bundle: first } });
  await update({
    ...googleAuth(),
    data: {
      expectedRevision: 1,
      bundle: { ...first, passphraseSalt: b64(16), passphraseWrapper: { nonce: b64(12), ciphertext: b64(48) } },
    },
  });

  const error = await expectRejection(
    update({
      ...googleAuth(),
      data: {
        expectedRevision: 1,
        bundle: { ...first, passphraseSalt: b64(16), passphraseWrapper: { nonce: b64(12), ciphertext: b64(48) } },
      },
    }),
    'aborted',
    'REVISION_CONFLICT',
  );
  assert.deepStrictEqual(error.details, { currentRevision: 2 });
  const stored = (await db.doc(`users/${USER}/backupControl/keyBundle`).get()).data();
  assert.strictEqual(stored.revision, 2);
});

test('update refuses to change the ABK identity, envelope or KDF parameters', async () => {
  await seedProfile(USER);
  const first = bundle('identity');
  await initialize({ ...googleAuth(), data: { bundle: first } });

  await expectRejection(
    update({ ...googleAuth(), data: { expectedRevision: 1, bundle: { ...first, keyId: b64(16) } } }),
    'failed-precondition',
    'BUNDLE_IDENTITY_CHANGED',
  );
  await expectRejection(
    update({
      ...googleAuth(),
      data: { expectedRevision: 1, bundle: { ...first, bundleFormatVersion: 2 } },
    }),
    'invalid-argument',
    'UNSUPPORTED_BUNDLE_VERSION',
  );
  await expectRejection(
    update({
      ...googleAuth(),
      data: { expectedRevision: 1, bundle: { ...first, argon2: { ...ARGON2, memoryKiB: 1024 } } },
    }),
    'invalid-argument',
    'UNSUPPORTED_KDF_PARAMETERS',
  );
});

test('update without an initialized bundle is a precondition failure', async () => {
  await seedProfile(USER);
  await expectRejection(
    update({ ...googleAuth(), data: { expectedRevision: 1, bundle: bundle('none') } }),
    'failed-precondition',
    'NOT_INITIALIZED',
  );
});

test('each UID owns exactly its own bundle document', async () => {
  await seedProfile(USER);
  await seedProfile(OTHER);
  const mine = bundle('mine');
  const theirs = bundle('theirs');

  await initialize({ ...googleAuth(USER), data: { bundle: mine } });
  await initialize({ ...googleAuth(OTHER), data: { bundle: theirs } });

  const storedMine = (await db.doc(`users/${USER}/backupControl/keyBundle`).get()).data();
  const storedTheirs = (await db.doc(`users/${OTHER}/backupControl/keyBundle`).get()).data();
  assert.strictEqual(storedMine.keyId, mine.keyId);
  assert.strictEqual(storedTheirs.keyId, theirs.keyId);
});
