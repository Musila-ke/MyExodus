'use strict';
// Pair/profile callable suite — the real handlers against the Firestore emulator (account
// plan §7 Release 0 step 2 / §8.8-§8.10). Run via `npm run test:lifecycle`.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const { HttpsError } = require('firebase-functions/v2/https');

const {
  createInitializeUserProfileHandler,
  createCreatePairInvitationHandler,
  createRespondToPairInvitationHandler,
  createTerminatePairHandler,
  createGetClientCompatibilityHandler,
} = require('../pair-lifecycle');
const { INVITATION_LIMIT, MAX_INVITATION_TTL_MS } = require('../pair-lifecycle-logic');

const PROJECT_ID = 'demo-myexodus-lifecycle';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';
const PAIR = 'alice-uid_bob-uid';
const TOKEN_ID = 'a'.repeat(64);
const NOW = Date.UTC(2026, 9, 29, 12, 0, 0);

let app;
let db;
let clockMs;
let initializeProfile;
let createInvitation;
let respondInvitation;
let terminatePair;
let compatibility;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'pair-lifecycle-emulator-suite');
  db = getFirestore(app);
  clockMs = NOW;
  initializeProfile = createInitializeUserProfileHandler({ db });
  createInvitation = createCreatePairInvitationHandler({ db, now: () => clockMs });
  respondInvitation = createRespondToPairInvitationHandler({ db, now: () => clockMs });
  terminatePair = createTerminatePairHandler({ db });
  compatibility = createGetClientCompatibilityHandler({ db });
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  clockMs = NOW;
  await db.recursiveDelete(db.collection('users'));
  await db.recursiveDelete(db.collection('deletionJobs'));
  await db.recursiveDelete(db.collection('pairings'));
  await db.recursiveDelete(db.collection('pairing_tokens'));
  await db.recursiveDelete(db.collection('rate-limits'));
  await db.recursiveDelete(db.collection('clientCompatibility'));
});

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

const invitation = (overrides = {}) => ({
  tokenId: TOKEN_ID,
  expiresAtEpochMillis: NOW + 10 * 60 * 1000,
  payloadB64: 'AQID',
  ...overrides,
});

const response = (overrides = {}) => ({
  tokenId: TOKEN_ID,
  partnerPayloadB64: 'BAUG',
  partnerSignatureB64: 'BwgJ',
  ...overrides,
});

const auth = (uid) => ({ auth: uid ? { uid } : undefined });

const seedActiveProfile = (uid) =>
  db.doc(`users/${uid}`).set({ status: 'ACTIVE', profileVersion: 1, schemaVersion: 1 });

// --- initializeUserProfile ---

test('profile initialization creates one ACTIVE profile and is idempotent', async () => {
  const first = await initializeProfile({ ...auth(ALICE), data: {} });
  assert.deepStrictEqual(first, { status: 'ACTIVE', created: true });
  const memoVersion = (await db.doc(`users/${ALICE}`).get()).get('profileVersion');
  assert.strictEqual(memoVersion, 1);

  const second = await initializeProfile({ ...auth(ALICE), data: {} });
  assert.deepStrictEqual(second, { status: 'ACTIVE', created: false });
});

test('profile initialization refuses after a deletion tombstone and never resurrects', async () => {
  await db.doc(`deletionJobs/${ALICE}`).set({ status: 'PENDING' });
  await expectRejection(initializeProfile({ ...auth(ALICE), data: {} }), 'permission-denied', 'ACCOUNT_DELETING');
  assert.strictEqual((await db.doc(`users/${ALICE}`).get()).exists, false);

  await db.doc(`deletionJobs/${ALICE}`).delete();
  const pending = await initializeProfile({ ...auth(ALICE), data: {} });
  assert.strictEqual(pending.created, true);
  await db.doc(`users/${ALICE}`).set({ status: 'PENDING_DELETION', profileVersion: 2 });
  const after = await initializeProfile({ ...auth(ALICE), data: {} });
  assert.deepStrictEqual(after, { status: 'PENDING_DELETION', created: false });
});

test('profile initialization requires authentication', async () => {
  await expectRejection(initializeProfile({ auth: undefined, data: {} }), 'unauthenticated');
});

// --- createPairInvitation ---

test('invitation registration stores only the hash-addressed document', async () => {
  await seedActiveProfile(ALICE);
  const reply = await createInvitation({ ...auth(ALICE), data: invitation() });
  assert.deepStrictEqual(reply, { status: 'registered', replayed: false });

  const doc = (await db.doc(`pairing_tokens/${TOKEN_ID}`).get()).data();
  assert.strictEqual(doc.creatorUid, ALICE);
  assert.strictEqual(doc.expiresAt.toMillis(), NOW + 10 * 60 * 1000);
  assert.deepStrictEqual([...doc.payload], [1, 2, 3]);
  assert.ok(doc.createdAt instanceof Timestamp);
  const window = (await db.doc(`rate-limits/invitation_${ALICE}`).get()).get('window');
  assert.deepStrictEqual(window, { start: NOW, count: 1 });
});

test('re-registering the identical invitation replays without charging the window', async () => {
  await seedActiveProfile(ALICE);
  await createInvitation({ ...auth(ALICE), data: invitation() });
  const replay = await createInvitation({ ...auth(ALICE), data: invitation() });
  assert.deepStrictEqual(replay, { status: 'registered', replayed: true });
  const window = (await db.doc(`rate-limits/invitation_${ALICE}`).get()).get('window');
  assert.strictEqual(window.count, 1);

  await expectRejection(
    createInvitation({ ...auth(ALICE), data: invitation({ payloadB64: 'BAUG' }) }),
    'already-exists',
    'INVITATION_ID_CONFLICT',
  );
});

test('invitation expiry bounds are enforced server-side', async () => {
  await expectRejection(
    createInvitation({ ...auth(ALICE), data: invitation({ expiresAtEpochMillis: NOW }) }),
    'invalid-argument',
    'EXPIRY_INVALID',
  );
  await expectRejection(
    createInvitation({
      ...auth(ALICE),
      data: invitation({ expiresAtEpochMillis: NOW + MAX_INVITATION_TTL_MS + 1 }),
    }),
    'invalid-argument',
    'EXPIRY_TOO_FAR',
  );
});

test('the per-UID invitation window throttles the eleventh attempt', async () => {
  await seedActiveProfile(ALICE);
  await db.doc(`rate-limits/invitation_${ALICE}`).set({
    window: { start: NOW, count: INVITATION_LIMIT },
  });
  const error = await expectRejection(
    createInvitation({ ...auth(ALICE), data: invitation() }),
    'resource-exhausted',
    'INVITATION_RATE_LIMITED',
  );
  assert.ok(error.details.retryAfterMs > 0);
  assert.strictEqual((await db.doc(`pairing_tokens/${TOKEN_ID}`).get()).exists, false);
});

// --- respondToPairInvitation ---

const seedInvitation = async () => {
  await db.doc(`pairing_tokens/${TOKEN_ID}`).set({
    creatorUid: ALICE,
    expiresAt: Timestamp.fromMillis(NOW + 10 * 60 * 1000),
    payload: Buffer.from([1, 2, 3]),
  });
};

test('a partner response is single-use and pinned to the caller', async () => {
  await seedInvitation();
  await seedActiveProfile(BOB);
  await seedActiveProfile(CAROL);
  const reply = await respondInvitation({ ...auth(BOB), data: response() });
  assert.deepStrictEqual(reply, { status: 'written' });

  const doc = (await db.doc(`pairing_tokens/${TOKEN_ID}`).get()).data();
  assert.strictEqual(doc.partnerUid, BOB);
  assert.deepStrictEqual([...doc.partnerPayload], [4, 5, 6]);
  assert.deepStrictEqual([...doc.partnerSignature], [7, 8, 9]);

  await expectRejection(
    respondInvitation({ ...auth(CAROL), data: response() }),
    'failed-precondition',
    'TOKEN_ALREADY_USED',
  );
  assert.strictEqual((await db.doc(`pairing_tokens/${TOKEN_ID}`).get()).get('partnerUid'), BOB);
});

test('the initiator cannot respond to their own invitation', async () => {
  await seedInvitation();
  await seedActiveProfile(ALICE);
  await expectRejection(
    respondInvitation({ ...auth(ALICE), data: response() }),
    'invalid-argument',
    'SELF_RESPONSE',
  );
});

test('missing and expired invitations are refused', async () => {
  await seedActiveProfile(BOB);
  await expectRejection(respondInvitation({ ...auth(BOB), data: response() }), 'not-found', 'TOKEN_NOT_FOUND');

  await db.doc(`pairing_tokens/${TOKEN_ID}`).set({
    creatorUid: ALICE,
    expiresAt: Timestamp.fromMillis(NOW - 1),
    payload: Buffer.from([1]),
  });
  await expectRejection(
    respondInvitation({ ...auth(BOB), data: response() }),
    'failed-precondition',
    'TOKEN_EXPIRED',
  );
});

test('pair invitation callables require an ACTIVE profile and refuse deleting accounts', async () => {
  await expectRejection(
    createInvitation({ ...auth(ALICE), data: invitation() }),
    'failed-precondition',
    'PROFILE_NOT_ACTIVE',
  );
  await db.doc(`users/${ALICE}`).set({ status: 'PENDING_DELETION', profileVersion: 2 });
  await expectRejection(
    createInvitation({ ...auth(ALICE), data: invitation() }),
    'failed-precondition',
    'PROFILE_NOT_ACTIVE',
  );
  await seedActiveProfile(ALICE);
  await db.doc(`deletionJobs/${ALICE}`).set({ status: 'PENDING' });
  await expectRejection(
    createInvitation({ ...auth(ALICE), data: invitation() }),
    'permission-denied',
    'ACCOUNT_DELETING',
  );
  await expectRejection(
    respondInvitation({ ...auth(BOB), data: response() }),
    'failed-precondition',
    'PROFILE_NOT_ACTIVE',
  );
});

// --- terminatePair ---

const seedPair = (status = 'active') =>
  db.doc(`pairings/${PAIR}`).set({ members: [ALICE, BOB], status, pairingEpoch: 1 });

test('termination flips the pair and writes one deterministic system event', async () => {
  await seedPair();
  const reply = await terminatePair({ ...auth(ALICE), data: { pairId: PAIR } });
  assert.deepStrictEqual(reply, { status: 'terminated' });

  const pair = (await db.doc(`pairings/${PAIR}`).get()).data();
  assert.strictEqual(pair.status, 'terminated');
  assert.deepStrictEqual(pair.members, [ALICE, BOB]);
  assert.strictEqual(pair.pairingEpoch, 1); // untouched

  const event = (await db.doc(`pairings/${PAIR}/system_events/pair_terminated`).get()).data();
  assert.strictEqual(event.type, 'PAIR_TERMINATED');
  assert.strictEqual(event.actorUid, ALICE);
  assert.ok(event.createdAt instanceof Timestamp);
});

test('termination is idempotent — the mutual-unpair loser succeeds and never duplicates', async () => {
  await seedPair();
  await terminatePair({ ...auth(ALICE), data: { pairId: PAIR } });
  const firstEvent = (await db.doc(`pairings/${PAIR}/system_events/pair_terminated`).get()).data();

  const loser = await terminatePair({ ...auth(BOB), data: { pairId: PAIR } });
  assert.deepStrictEqual(loser, { status: 'terminated' });
  const secondEvent = (await db.doc(`pairings/${PAIR}/system_events/pair_terminated`).get()).data();
  assert.strictEqual(secondEvent.createdAt.toMillis(), firstEvent.createdAt.toMillis());
  assert.strictEqual(secondEvent.actorUid, ALICE);
});

test('a missing pair is already terminal; non-members, pending pairs and deleting accounts are refused', async () => {
  // Missing is the state the caller asked for — idempotent success, like the old client's
  // NOT_FOUND -> true mapping.
  assert.deepStrictEqual(
    await terminatePair({ ...auth(ALICE), data: { pairId: PAIR } }),
    { status: 'not_found' },
  );

  await seedPair();
  await expectRejection(
    terminatePair({ ...auth(CAROL), data: { pairId: PAIR } }),
    'permission-denied',
    'NOT_A_PAIR_MEMBER',
  );
  await seedPair('pending');
  await expectRejection(
    terminatePair({ ...auth(ALICE), data: { pairId: PAIR } }),
    'failed-precondition',
    'PAIR_NOT_ACTIVE',
  );
  await seedPair();
  await db.doc(`deletionJobs/${ALICE}`).set({ status: 'PENDING' });
  await expectRejection(
    terminatePair({ ...auth(ALICE), data: { pairId: PAIR } }),
    'permission-denied',
    'ACCOUNT_DELETING',
  );
});

// --- getClientCompatibility ---

test('compatibility metadata is published from the server-owned document', async () => {
  assert.deepStrictEqual(await compatibility({ data: {} }), {
    minimumVersionCode: null,
    updateMessage: null,
  });
  await db.doc('clientCompatibility/current').set({ minimumVersionCode: 12, updateMessage: 'Update to continue.' });
  assert.deepStrictEqual(await compatibility({ data: {} }), {
    minimumVersionCode: 12,
    updateMessage: 'Update to continue.',
  });
});
