'use strict';
// `appendUserEvent` transactional suite — the real handler against the Firestore emulator
// (account plan §7 Release 0 step 2 / §8.8). Named without `.test.js` on purpose: plain
// `npm test` stays emulator-free; run via `npm run test:append`.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const { HttpsError } = require('firebase-functions/v2/https');

const { createAppendUserEventHandler } = require('../event-append');
const { UID_BUCKET, PAIR_BUCKET } = require('../event-buckets');

const PROJECT_ID = 'demo-myexodus-append';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';
const PAIR = 'alice-uid_bob-uid';
const EVENT = '3f2b8c1a-9d4e-4f6a-8b7c-1e2d3f4a5b6c';

const NOW = Date.UTC(2026, 9, 29, 12, 0, 0);

let app;
let db;
let clockMs;
let handler;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'event-append-emulator-suite');
  db = getFirestore(app);
  clockMs = NOW;
  handler = createAppendUserEventHandler({ db, now: () => clockMs });
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  clockMs = NOW;
  await db.recursiveDelete(db.collection('mailboxes'));
  await db.recursiveDelete(db.collection('pairings'));
  await db.recursiveDelete(db.collection('users'));
  await db.recursiveDelete(db.collection('eventRateLimits'));
  await db.recursiveDelete(db.collection('deletionJobs'));
  await db.recursiveDelete(db.collection('config'));
  await db.doc(`pairings/${PAIR}`).set({ members: [ALICE, BOB], status: 'active' });
  await db.doc(`users/${ALICE}`).set({ status: 'ACTIVE' });
  await db.doc(`users/${BOB}`).set({ status: 'ACTIVE' });
});

const input = (overrides = {}) => ({
  pairId: PAIR,
  eventId: EVENT,
  senderId: 'sender-key-id',
  keyEpoch: 2,
  ciphertextB64: 'AAECAw==',
  ...overrides,
});

const call = (data = input(), uid = ALICE) => handler({ auth: uid ? { uid } : undefined, data });

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

const messageDoc = () => db.doc(`mailboxes/${BOB}/messages/${EVENT}`);
const userBucket = () => db.doc(`eventRateLimits/user_${ALICE}`);
const pairBucket = () => db.doc(`eventRateLimits/pair_${PAIR}`);

test('an authorized append writes the shipped mailbox shape and charges both buckets', async () => {
  const reply = await call();
  assert.deepStrictEqual(reply, { status: 'appended' });

  const doc = (await messageDoc().get()).data();
  assert.strictEqual(doc.senderUid, ALICE);
  assert.strictEqual(doc.senderId, 'sender-key-id');
  assert.strictEqual(doc.pairId, PAIR);
  assert.strictEqual(doc.payload, 'AAECAw==');
  assert.strictEqual(doc.keyEpoch, 2);
  assert.strictEqual(doc.recordKind, 'USER_ENCRYPTED');
  assert.strictEqual(doc.schemaVersion, 1);
  assert.ok(doc.timestamp instanceof Timestamp);

  const user = (await userBucket().get()).data();
  assert.strictEqual(user.events, UID_BUCKET.eventCapacity - 1);
  assert.strictEqual(user.bytes, UID_BUCKET.byteCapacity - 8);
  const pair = (await pairBucket().get()).data();
  assert.strictEqual(pair.events, PAIR_BUCKET.eventCapacity - 1);
});

test('an identical retry is idempotent success and charges nothing', async () => {
  await call();
  const before = (await userBucket().get()).data();

  const replay = await call();
  assert.deepStrictEqual(replay, { status: 'replayed' });
  const afterRetry = (await userBucket().get()).data();
  assert.strictEqual(afterRetry.events, before.events);
  assert.strictEqual(afterRetry.bytes, before.bytes);
});

test('a different body under the same event id is EVENT_ID_CONFLICT, never an overwrite', async () => {
  await call();
  await expectRejection(
    call(input({ ciphertextB64: 'BAUGBw==' })),
    'already-exists',
    'EVENT_ID_CONFLICT',
  );
  assert.strictEqual((await messageDoc().get()).get('payload'), 'AAECAw==');
});

test('unauthenticated and malformed requests are rejected before any write', async () => {
  await expectRejection(call(input(), null), 'unauthenticated');
  await expectRejection(call(input({ ciphertextB64: 'not base64!' })), 'invalid-argument', 'CIPHERTEXT_INVALID');
  await expectRejection(call({ ...input(), extra: true }), 'invalid-argument', 'FIELD_ALLOWLIST');
  assert.strictEqual((await messageDoc().get()).exists, false);
});

test('a non-member cannot append even with a valid pair id', async () => {
  await expectRejection(call(input(), CAROL), 'permission-denied', 'NOT_A_PAIR_MEMBER');
});

test('unknown and terminated pairs are refused for new events', async () => {
  await expectRejection(call(input({ pairId: 'alice-uid_carol-uid' })), 'permission-denied', 'PAIR_NOT_FOUND');

  await db.doc(`pairings/${PAIR}`).set({ members: [ALICE, BOB], status: 'terminated' });
  await expectRejection(call(), 'permission-denied', 'PAIR_NOT_ACTIVE');
});

test('a committed event still replays after the pair terminates (timeout-after-commit retry)', async () => {
  await call();
  await db.doc(`pairings/${PAIR}`).set({ members: [ALICE, BOB], status: 'terminated' });
  assert.deepStrictEqual(await call(), { status: 'replayed' });
});

test('the active-profile requirement and the deletion tombstone block new events', async () => {
  await db.doc(`users/${ALICE}`).delete();
  await expectRejection(call(), 'failed-precondition', 'PROFILE_NOT_ACTIVE');

  await db.doc(`users/${ALICE}`).set({ status: 'PENDING_DELETION' });
  await expectRejection(call(), 'failed-precondition', 'PROFILE_NOT_ACTIVE');

  await db.doc(`users/${ALICE}`).set({ status: 'ACTIVE' });
  await db.doc(`deletionJobs/${ALICE}`).set({ status: 'PENDING' });
  await expectRejection(call(), 'permission-denied', 'ACCOUNT_DELETING');
});

test('the operator kill switch stops new events but keeps committed replays valid', async () => {
  await call();
  await db.doc('config/features').set({ partnerAccountabilityEnabled: false });
  await expectRejection(
    call(input({ eventId: '9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d' })),
    'permission-denied',
    'FEATURE_DISABLED',
  );
  // A timeout-after-commit retry of an already-stored event is not a new send.
  assert.deepStrictEqual(await call(), { status: 'replayed' });
});

test('an exhausted bucket reports a bounded retryAfterMs and writes nothing', async () => {
  await userBucket().set({ events: 0, bytes: 0, updatedAt: Timestamp.fromMillis(NOW) });
  const error = await expectRejection(call(), 'resource-exhausted', 'RATE_LIMITED');
  assert.ok(error.details && error.details.retryAfterMs > 0);
  assert.ok(error.details.retryAfterMs <= 60_000);
  assert.strictEqual((await messageDoc().get()).exists, false);

  // 2 events/s and 10 MiB/min: half a second refills one event and ~87 KB.
  clockMs = NOW + 500;
  assert.deepStrictEqual(await call(), { status: 'appended' });
});
