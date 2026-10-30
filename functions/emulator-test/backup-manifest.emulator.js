'use strict';
// Backup reservation / manifest-commit suite — the real handlers against the Firestore and
// Storage emulators (account plan §7 Release 2 step 14 / §6.4 items 11-14 / §8.6).
// Run via `npm run test:backup-upload`.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const { getStorage } = require('firebase-admin/storage');
const { HttpsError } = require('firebase-functions/v2/https');

const {
  createBeginBackupHandler,
  createExtendBackupReservationHandler,
  createCommitBackupManifestHandler,
  createCleanupBackupOrphansHandler,
} = require('../backup-manifest');
const {
  ENVELOPE_OVERHEAD_BYTES,
  MAX_COMPRESSED_BYTES,
  MAX_OBJECT_BYTES,
  ORPHAN_GRACE_MS,
  RESERVATION_MAX_MS,
  RESERVATION_TTL_MS,
  PATHS,
} = require('../backup-manifest-logic');

const PROJECT_ID = 'demo-myexodus-backup-upload';
const BUCKET = `${PROJECT_ID}.appspot.com`;
const USER = 'backup-upload-user-uid';

let app;
let db;
let bucket;
let begin;
let extend;
let commit;
let cleanup;
let nowMs;

const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const newBackupId = () => crypto.randomBytes(16).toString('hex');
const newBytes = (size = 4096) => crypto.randomBytes(size);
const auth = (uid = USER, provider = 'google.com', extra = {}) => ({
  auth: { uid, token: { firebase: { sign_in_provider: provider }, ...extra } },
});
const googleAuth = (uid = USER) => auth(uid);
const anonymousAuth = (uid = USER) => auth(uid, 'anonymous');

function beginInput(backupId, overrides = {}) {
  return {
    backupId,
    schemaVersion: 20,
    compressedBytes: 4096,
    objectBytes: 4096 + ENVELOPE_OVERHEAD_BYTES,
    baseRevision: null,
    baseSha256: null,
    replaceCurrent: false,
    replaceRevision: null,
    replaceSha256: null,
    ...overrides,
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

const seedProfile = (uid = USER, status = 'ACTIVE') =>
  db.doc(`users/${uid}`).set({ status, profileVersion: 1, schemaVersion: 1 });
const seedGate = (enabled = true) =>
  db.doc('serverFeatureGates/backupUpload').set({ enabled });

async function seedCurrent(revision, sha, backupId = newBackupId()) {
  const objectPath = PATHS.object(USER, backupId);
  await db.doc(PATHS.manifest(USER, backupId)).set({
    backupId,
    revision,
    sha256: sha,
    objectPath,
    generation: '1',
    objectBytes: 4096 + ENVELOPE_OVERHEAD_BYTES,
    compressedBytes: 4096,
    schemaVersion: 20,
    envelopeVersion: 1,
    baseRevision: null,
    baseSha256: null,
  });
  await db.doc(PATHS.pointer(USER)).set({
    backupId,
    revision,
    sha256: sha,
    objectPath,
    generation: '1',
    objectBytes: 4096 + ENVELOPE_OVERHEAD_BYTES,
    schemaVersion: 20,
  });
  return { revision, sha256: sha, backupId, objectPath };
}

async function upload(objectPath, bytes, metadata = {}) {
  await bucket.file(objectPath).save(bytes, {
    resumable: false,
    contentType: 'application/octet-stream',
    ...metadata,
  });
}

async function beginAndUpload(input, bytes = newBytes(input.objectBytes)) {
  const reservation = await begin({ ...googleAuth(), data: input });
  await upload(reservation.objectPath, bytes);
  return { reservation, bytes, sha: sha256(bytes) };
}

async function clearAll() {
  nowMs = Date.now();
  for (const collection of ['users', 'deletionJobs', 'backupReservations', 'serverFeatureGates']) {
    await db.recursiveDelete(db.collection(collection));
  }
  const group = await db.collectionGroup('reservations').get();
  await Promise.all(group.docs.map((doc) => doc.ref.delete()));
  const [files] = await bucket.getFiles({ prefix: 'backups/' });
  await Promise.all(files.map((file) => file.delete({ ignoreNotFound: true })));
}

before(() => {
  app = initializeApp({ projectId: PROJECT_ID, storageBucket: BUCKET }, 'backup-manifest-emulator-suite');
  db = getFirestore(app);
  bucket = getStorage(app).bucket(BUCKET);
  const now = () => nowMs;
  begin = createBeginBackupHandler({ db, now });
  extend = createExtendBackupReservationHandler({ db, now });
  commit = createCommitBackupManifestHandler({ db, bucket, now });
  cleanup = createCleanupBackupOrphansHandler({ db, bucket, now });
});

after(async () => {
  await deleteApp(app);
});

beforeEach(clearAll);

test('begin requires a persistent, verified, active account and an enabled server gate', async () => {
  await seedGate(true);
  await expectRejection(
    begin({ ...anonymousAuth(), data: beginInput(newBackupId()) }),
    'failed-precondition',
    'PERSISTENT_ACCOUNT_REQUIRED',
  );
  await expectRejection(
    begin({ ...auth(USER, 'password'), data: beginInput(newBackupId()) }),
    'failed-precondition',
    'EMAIL_VERIFICATION_REQUIRED',
  );
  await expectRejection(
    begin({ ...googleAuth(), data: beginInput(newBackupId()) }),
    'failed-precondition',
    'PROFILE_NOT_ACTIVE',
  );
  await seedProfile();
  await db.doc('serverFeatureGates/backupUpload').delete();
  await expectRejection(begin({ ...googleAuth(), data: beginInput(newBackupId()) }), 'failed-precondition', 'FEATURE_DISABLED');
  await seedGate(false);
  await expectRejection(begin({ ...googleAuth(), data: beginInput(newBackupId()) }), 'failed-precondition', 'FEATURE_DISABLED');
  await db.doc(`deletionJobs/${USER}`).set({ state: 'PENDING' });
  await seedGate(true);
  await expectRejection(begin({ ...googleAuth(), data: beginInput(newBackupId()) }), 'permission-denied', 'ACCOUNT_DELETING');
});

test('begin writes the reservation, is idempotent on replay and rejects a reused id', async () => {
  await seedProfile();
  await seedGate(true);
  const input = beginInput(newBackupId());

  const created = await begin({ ...googleAuth(), data: input });
  assert.strictEqual(created.backupId, input.backupId);
  assert.strictEqual(created.objectPath, PATHS.object(USER, input.backupId));
  assert.strictEqual(created.expiresAtMs, nowMs + RESERVATION_TTL_MS);
  assert.strictEqual(created.replayed, false);

  const reservation = (await db.doc(PATHS.reservation(USER, input.backupId)).get()).data();
  assert.strictEqual(reservation.objectPath, created.objectPath);
  assert.strictEqual(reservation.objectBytes, input.objectBytes);
  assert.strictEqual(reservation.baseRevision, null);
  assert.strictEqual(reservation.expiresAt.toMillis(), created.expiresAtMs);
  const ledger = (await db.doc(PATHS.ledger(USER)).get()).data();
  assert.strictEqual(ledger.attempts.length, 1);
  assert.strictEqual(ledger.outstanding.backupId, input.backupId);

  const replayed = await begin({ ...googleAuth(), data: input });
  assert.strictEqual(replayed.replayed, true);
  assert.strictEqual(replayed.expiresAtMs, created.expiresAtMs);
  assert.strictEqual((await db.doc(PATHS.ledger(USER)).get()).get('attempts').length, 1);

  await expectRejection(
    begin({ ...googleAuth(), data: { ...input, schemaVersion: 21 } }),
    'invalid-argument',
    'BACKUP_INVALID_REQUEST',
  );
});

test('begin names every base divergence and binds replacement to the exact pointer', async () => {
  await seedProfile();
  await seedGate(true);
  const current = await seedCurrent(2, sha256(Buffer.from('current')));

  await expectRejection(begin({ ...googleAuth(), data: beginInput(newBackupId()) }), 'failed-precondition', 'BACKUP_BASE_REQUIRED');
  await expectRejection(
    begin({ ...googleAuth(), data: beginInput(newBackupId(), { baseRevision: 3, baseSha256: current.sha256 }) }),
    'failed-precondition',
    'REMOTE_BACKUP_ROLLBACK',
  );
  await expectRejection(
    begin({ ...googleAuth(), data: beginInput(newBackupId(), { baseRevision: 1, baseSha256: current.sha256 }) }),
    'failed-precondition',
    'REMOTE_BACKUP_AHEAD',
  );
  await expectRejection(
    begin({ ...googleAuth(), data: beginInput(newBackupId(), { baseRevision: 2, baseSha256: sha256(Buffer.from('other')) }) }),
    'failed-precondition',
    'REMOTE_BACKUP_EQUIVOCATION',
  );
  await expectRejection(
    begin({
      ...googleAuth(),
      data: beginInput(newBackupId(), {
        replaceCurrent: true, replaceRevision: 1, replaceSha256: current.sha256,
      }),
    }),
    'failed-precondition',
    'BACKUP_CONFLICT',
  );
  const matched = await begin({
    ...googleAuth(),
    data: beginInput(newBackupId(), { baseRevision: 2, baseSha256: current.sha256 }),
  });
  assert.strictEqual(matched.replayed, false);
  assert.strictEqual((await db.doc(PATHS.reservation(USER, matched.backupId)).get()).get('baseRevision'), 2);
});

test('a local base with no server pointer is a rollback, not a first backup', async () => {
  await seedProfile();
  await seedGate(true);
  await expectRejection(
    begin({ ...googleAuth(), data: beginInput(newBackupId(), { baseRevision: 1, baseSha256: sha256(Buffer.from('x')) }) }),
    'failed-precondition',
    'REMOTE_BACKUP_ROLLBACK',
  );
});

test('five reservations per rolling window, one outstanding, the sixth is refused', async () => {
  await seedProfile();
  await seedGate(true);
  for (let i = 0; i < 5; i += 1) {
    await begin({ ...googleAuth(), data: beginInput(newBackupId()) });
    nowMs += 1000;
  }
  const ledger = (await db.doc(PATHS.ledger(USER)).get()).data();
  assert.strictEqual(ledger.attempts.length, 5);
  const live = await db.collection('backupReservations').doc(USER).collection('reservations').get();
  assert.strictEqual(live.size, 1, 'an abandoned outstanding reservation is superseded, never left live');

  const error = await expectRejection(
    begin({ ...googleAuth(), data: beginInput(newBackupId()) }),
    'resource-exhausted',
    'BACKUP_RESERVATION_LIMIT',
  );
  assert.ok(error.details && error.details.retryAfterMs > 0);
});

test('extend refreshes inactivity at most once per heartbeat and never past seven days', async () => {
  await seedProfile();
  await seedGate(true);
  const created = await begin({ ...googleAuth(), data: beginInput(newBackupId()) });
  assert.strictEqual(created.expiresAtMs, nowMs + RESERVATION_TTL_MS);
  const ceiling = nowMs + RESERVATION_MAX_MS;

  nowMs += 20 * 60 * 1000;
  const first = await extend({ ...googleAuth(), data: { backupId: created.backupId } });
  assert.strictEqual(first.extended, true);
  assert.strictEqual(first.expiresAtMs, nowMs + 30 * 60 * 1000);

  nowMs += 5 * 60 * 1000;
  const insideWindow = await extend({ ...googleAuth(), data: { backupId: created.backupId } });
  assert.strictEqual(insideWindow.extended, false);
  assert.strictEqual(insideWindow.expiresAtMs, first.expiresAtMs);

  nowMs = ceiling - 5 * 60 * 1000;
  await db.doc(PATHS.reservation(USER, created.backupId)).update({
    expiresAt: Timestamp.fromMillis(nowMs + 5 * 60 * 1000),
    expiresAtMs: nowMs + 5 * 60 * 1000,
    heartbeatAtMs: nowMs - 11 * 60 * 1000,
  });
  const capped = await extend({ ...googleAuth(), data: { backupId: created.backupId } });
  assert.strictEqual(capped.extended, true);
  assert.strictEqual(capped.expiresAtMs, ceiling);

  nowMs = ceiling;
  await expectRejection(
    extend({ ...googleAuth(), data: { backupId: created.backupId } }),
    'failed-precondition',
    'BACKUP_RESERVATION_EXPIRED',
  );
});

test('extend on a missing reservation fails closed', async () => {
  await seedProfile();
  await expectRejection(
    extend({ ...googleAuth(), data: { backupId: newBackupId() } }),
    'failed-precondition',
    'BACKUP_RESERVATION_EXPIRED',
  );
});

test('commit verifies the stored object and moves the pointer atomically', async () => {
  await seedProfile();
  await seedGate(true);
  const input = beginInput(newBackupId(), { compressedBytes: 2048, objectBytes: 2048 + ENVELOPE_OVERHEAD_BYTES });
  const { reservation, bytes, sha } = await beginAndUpload(input);

  const result = await commit({
    ...googleAuth(),
    data: { backupId: input.backupId, sha256: sha, objectBytes: input.objectBytes },
  });
  assert.deepStrictEqual(
    { revision: result.revision, sha256: result.sha256, replayed: result.replayed },
    { revision: 1, sha256: sha, replayed: false },
  );

  const pointer = (await db.doc(PATHS.pointer(USER)).get()).data();
  assert.strictEqual(pointer.backupId, input.backupId);
  assert.strictEqual(pointer.revision, 1);
  assert.strictEqual(pointer.sha256, sha);
  assert.strictEqual(pointer.objectPath, reservation.objectPath);
  const manifest = (await db.doc(PATHS.manifest(USER, input.backupId)).get()).data();
  assert.strictEqual(manifest.revision, 1);
  assert.strictEqual(manifest.sha256, sha);
  assert.strictEqual(manifest.objectBytes, input.objectBytes);
  assert.ok(manifest.generation);
  assert.strictEqual((await db.doc(PATHS.reservation(USER, input.backupId)).get()).exists, false);
  assert.strictEqual((await db.doc(PATHS.ledger(USER)).get()).get('outstanding'), null);

  const replay = await commit({
    ...googleAuth(),
    data: { backupId: input.backupId, sha256: sha, objectBytes: input.objectBytes },
  });
  assert.strictEqual(replay.replayed, true);
  assert.strictEqual(replay.revision, 1);
  assert.strictEqual((await db.doc(PATHS.pointer(USER)).get()).get('revision'), 1);
  assert.strictEqual(bytes.length, input.objectBytes);
});

test('commit rejects a missing, mismatched or mislabelled object', async () => {
  await seedProfile();
  await seedGate(true);
  const input = beginInput(newBackupId(), { compressedBytes: 1024, objectBytes: 1024 + ENVELOPE_OVERHEAD_BYTES });
  const { reservation, bytes, sha } = await beginAndUpload(input);

  await expectRejection(
    commit({ ...googleAuth(), data: { backupId: input.backupId, sha256: sha256(Buffer.from('wrong')), objectBytes: input.objectBytes } }),
    'failed-precondition',
    'BACKUP_OBJECT_MISMATCH',
  );
  await expectRejection(
    commit({ ...googleAuth(), data: { backupId: input.backupId, sha256: sha, objectBytes: input.objectBytes + 1 } }),
    'failed-precondition',
    'BACKUP_OBJECT_MISMATCH',
  );

  await upload(reservation.objectPath, bytes, { contentType: 'text/plain' });
  await expectRejection(
    commit({ ...googleAuth(), data: { backupId: input.backupId, sha256: sha, objectBytes: input.objectBytes } }),
    'failed-precondition',
    'BACKUP_OBJECT_MISMATCH',
  );

  await bucket.file(reservation.objectPath).delete();
  await expectRejection(
    commit({ ...googleAuth(), data: { backupId: input.backupId, sha256: sha, objectBytes: input.objectBytes } }),
    'failed-precondition',
    'BACKUP_OBJECT_MISSING',
  );
  assert.strictEqual((await db.doc(PATHS.pointer(USER)).get()).exists, false);
});

test('a commit against a stale anchor is a conflict and never overwrites the pointer', async () => {
  await seedProfile();
  await seedGate(true);
  const first = await beginAndUpload(beginInput(newBackupId(), { compressedBytes: 512, objectBytes: 512 + ENVELOPE_OVERHEAD_BYTES }));
  await commit({ ...googleAuth(), data: { backupId: first.reservation.backupId, sha256: first.sha, objectBytes: 512 + ENVELOPE_OVERHEAD_BYTES } });

  const secondInput = beginInput(newBackupId(), {
    compressedBytes: 512,
    objectBytes: 512 + ENVELOPE_OVERHEAD_BYTES,
    baseRevision: 1,
    baseSha256: first.sha,
  });
  const second = await beginAndUpload(secondInput);

  const moved = await seedCurrent(2, sha256(Buffer.from('moved')));
  await expectRejection(
    commit({ ...googleAuth(), data: { backupId: second.reservation.backupId, sha256: second.sha, objectBytes: secondInput.objectBytes } }),
    'aborted',
    'BACKUP_CONFLICT',
  );
  const pointer = (await db.doc(PATHS.pointer(USER)).get()).data();
  assert.strictEqual(pointer.revision, 2);
  assert.strictEqual(pointer.sha256, moved.sha256);
  assert.strictEqual((await db.doc(PATHS.reservation(USER, second.reservation.backupId)).get()).exists, true);
});

test('replaceCurrent commits only against the exact revision and hash shown to the user', async () => {
  await seedProfile();
  await seedGate(true);
  const current = await seedCurrent(1, sha256(Buffer.from('original')));
  const input = beginInput(newBackupId(), {
    compressedBytes: 700,
    objectBytes: 700 + ENVELOPE_OVERHEAD_BYTES,
    replaceCurrent: true,
    replaceRevision: current.revision,
    replaceSha256: current.sha256,
  });
  const { reservation, sha } = await beginAndUpload(input);

  const result = await commit({
    ...googleAuth(),
    data: { backupId: input.backupId, sha256: sha, objectBytes: input.objectBytes },
  });
  assert.strictEqual(result.revision, 2);
  const pointer = (await db.doc(PATHS.pointer(USER)).get()).data();
  assert.strictEqual(pointer.backupId, input.backupId);
  assert.strictEqual(pointer.sha256, sha);
  assert.strictEqual(pointer.objectPath, reservation.objectPath);
});

test('commit re-reads the account and the gate', async () => {
  await seedProfile();
  await seedGate(true);
  const input = beginInput(newBackupId(), { compressedBytes: 256, objectBytes: 256 + ENVELOPE_OVERHEAD_BYTES });
  const { reservation, sha } = await beginAndUpload(input);
  await seedGate(false);
  await expectRejection(
    commit({ ...googleAuth(), data: { backupId: input.backupId, sha256: sha, objectBytes: input.objectBytes } }),
    'failed-precondition',
    'FEATURE_DISABLED',
  );
  await seedGate(true);
  await db.doc(`users/${USER}`).set({ status: 'PENDING_DELETION', profileVersion: 1 });
  await expectRejection(
    commit({ ...googleAuth(), data: { backupId: input.backupId, sha256: sha, objectBytes: input.objectBytes } }),
    'failed-precondition',
    'PROFILE_NOT_ACTIVE',
  );
  assert.strictEqual((await db.doc(PATHS.reservation(USER, reservation.backupId)).get()).exists, true);
});

test('retention keeps the current pointer and the two most recent prior snapshots', async () => {
  await seedProfile();
  await seedGate(true);
  const ids = [];
  const shas = [];
  let baseRevision = null;
  let baseSha = null;
  for (let i = 1; i <= 4; i += 1) {
    const input = beginInput(newBackupId(), {
      compressedBytes: 128,
      objectBytes: 128 + ENVELOPE_OVERHEAD_BYTES,
      baseRevision,
      baseSha256: baseSha,
    });
    const { reservation, sha } = await beginAndUpload(input);
    await commit({ ...googleAuth(), data: { backupId: input.backupId, sha256: sha, objectBytes: input.objectBytes } });
    ids.push(input.backupId);
    shas.push(sha);
    baseRevision = i;
    baseSha = sha;
    assert.strictEqual(reservation.objectPath, PATHS.object(USER, input.backupId));
  }

  const manifestIds = (await db.collection(PATHS.manifests(USER)).get()).docs.map((doc) => doc.id).sort();
  assert.deepStrictEqual(manifestIds, ids.slice(1).sort());
  const [files] = await bucket.getFiles({ prefix: 'backups/' });
  const paths = files.map((file) => file.name).sort();
  assert.deepStrictEqual(paths, ids.slice(1).map((id) => PATHS.object(USER, id)).sort());
  assert.strictEqual((await db.doc(PATHS.pointer(USER)).get()).get('revision'), 4);
  assert.strictEqual((await db.doc(PATHS.pointer(USER)).get()).get('sha256'), shas[3]);
});

test('orphan cleanup respects the 24-hour grace and never deletes a committed object', async () => {
  await seedProfile();
  await seedGate(true);

  const committed = await beginAndUpload(beginInput(newBackupId(), { compressedBytes: 100, objectBytes: 100 + ENVELOPE_OVERHEAD_BYTES }));
  await commit({ ...googleAuth(), data: { backupId: committed.reservation.backupId, sha256: committed.sha, objectBytes: 100 + ENVELOPE_OVERHEAD_BYTES } });
  const fresh = await beginAndUpload(beginInput(newBackupId(), {
    compressedBytes: 100,
    objectBytes: 100 + ENVELOPE_OVERHEAD_BYTES,
    baseRevision: 1,
    baseSha256: committed.sha,
  }));

  // One outstanding reservation is the production invariant; seed the two expired shapes
  // directly so cleanup is exercised against abandoned and committed objects at once.
  const old = Timestamp.fromMillis(nowMs - ORPHAN_GRACE_MS - 60 * 60 * 1000);
  const recent = Timestamp.fromMillis(nowMs - 60 * 60 * 1000);
  const abandonedId = newBackupId();
  const abandonedPath = PATHS.object(USER, abandonedId);
  await upload(abandonedPath, newBytes(100));
  const expiredReservation = (backupId, objectPath, expiresAt) => ({
    uid: USER,
    backupId,
    objectPath,
    objectBytes: 100,
    expiresAt,
    expiresAtMs: expiresAt.toMillis(),
  });
  await db.doc(PATHS.reservation(USER, abandonedId)).set(expiredReservation(abandonedId, abandonedPath, old));
  await db.doc(PATHS.reservation(USER, fresh.reservation.backupId)).update({ expiresAt: recent, expiresAtMs: recent.toMillis() });
  await db.doc(PATHS.reservation(USER, committed.reservation.backupId))
    .set(expiredReservation(committed.reservation.backupId, committed.reservation.objectPath, old));

  await cleanup();

  assert.strictEqual((await db.doc(PATHS.reservation(USER, abandonedId)).get()).exists, false);
  await assert.rejects(bucket.file(abandonedPath).getMetadata());
  assert.strictEqual((await db.doc(PATHS.reservation(USER, fresh.reservation.backupId)).get()).exists, true);
  assert.ok((await bucket.file(fresh.reservation.objectPath).getMetadata())[0]);
  assert.strictEqual((await db.doc(PATHS.reservation(USER, committed.reservation.backupId)).get()).exists, false);
  assert.ok((await bucket.file(committed.reservation.objectPath).getMetadata())[0], 'a committed object is never orphaned');
});

test('begin accepts the largest legal object and rejects anything over the cap', async () => {
  await seedProfile();
  await seedGate(true);
  await expectRejection(
    begin({
      ...googleAuth(),
      data: beginInput(newBackupId(), {
        compressedBytes: MAX_COMPRESSED_BYTES + 1,
        objectBytes: MAX_COMPRESSED_BYTES + 1 + ENVELOPE_OVERHEAD_BYTES,
      }),
    }),
    'invalid-argument',
    'BACKUP_TOO_LARGE',
  );
  const ok = await begin({
    ...googleAuth(),
    data: beginInput(newBackupId(), {
      compressedBytes: MAX_COMPRESSED_BYTES,
      objectBytes: MAX_COMPRESSED_BYTES + ENVELOPE_OVERHEAD_BYTES,
    }),
  });
  assert.strictEqual(ok.replayed, false);
  assert.ok(MAX_COMPRESSED_BYTES + ENVELOPE_OVERHEAD_BYTES < MAX_OBJECT_BYTES);
});
