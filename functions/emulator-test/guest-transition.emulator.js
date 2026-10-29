'use strict';
// Guest-transition callable suite — the real handlers against the Firestore emulator
// (account plan §7 Release 1 step 9 / §4.2 / §8.4). Run via `npm run test:transition`.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { HttpsError } = require('firebase-functions/v2/https');

const {
  createPrepareGuestTransitionHandler,
  createFinalizeGuestTransitionHandler,
  createCancelGuestTransitionHandler,
} = require('../guest-transition');
const { LEASE_MS, PREPARE_LIMIT_WINDOW_MS } = require('../guest-transition-logic');

const PROJECT_ID = 'demo-myexodus-transition';
const GATE = 'serverFeatureGates/guestTransition';
const NOW = Date.UTC(2026, 9, 29, 12, 0, 0);

const GUEST = 'guest-uid';
const GUEST_2 = 'guest-2-uid';
const PARTNER_A = 'partner-a-uid';
const PARTNER_B = 'partner-b-uid';
const PARTNER_C = 'partner-c-uid';
const TARGET = 'target-uid';
const OTHER_TARGET = 'other-target-uid';

let app;
let db;
let clockMs;
let prepare;
let finalize;
let cancel;

const idFor = (i) => crypto.createHash('sha256').update(`id-${i}`).digest('base64url').slice(0, 22);
const capFor = (i) => crypto.createHash('sha256').update(`cap-${i}`).digest('base64url');

const ID0 = idFor(0);
const CAP0 = capFor(0);
const transition = (overrides = {}) => ({
  transitionId: ID0,
  capability: CAP0,
  intent: 'IMPORT',
  ...overrides,
});

const authAs = (uid, provider, extraToken = {}) => ({
  auth: { uid, token: { firebase: { sign_in_provider: provider }, ...extraToken } },
});
const guestAuth = (uid = GUEST) => authAs(uid, 'anonymous');
const googleAuth = (uid) => authAs(uid, 'google.com');

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

const seedProfile = (uid) =>
  db.doc(`users/${uid}`).set({ status: 'ACTIVE', profileVersion: 1, schemaVersion: 1 });
const seedPair = (pairId, members, status = 'active') =>
  db.doc(`pairings/${pairId}`).set({ members, status });

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'guest-transition-emulator-suite');
  db = getFirestore(app);
  clockMs = NOW;
  prepare = createPrepareGuestTransitionHandler({ db, now: () => clockMs });
  finalize = createFinalizeGuestTransitionHandler({ db, now: () => clockMs });
  cancel = createCancelGuestTransitionHandler({ db, now: () => clockMs });
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  clockMs = NOW;
  for (const collection of [
    'guestTransitions',
    'guestTransitionLeases',
    'deletionJobs',
    'pairings',
    'rate-limits',
    'users',
    'serverFeatureGates',
  ]) {
    await db.recursiveDelete(db.collection(collection));
  }
  await db.doc(GATE).set({ enabled: true });
});

// --- prepareGuestTransition ---

test('prepare stores the hash-only record, the lease, and one window attempt', async () => {
  const result = await prepare({ ...guestAuth(), data: transition() });
  assert.deepStrictEqual(result, { state: 'PREPARED', intent: 'IMPORT', expiresAtMs: NOW + LEASE_MS });

  const record = (await db.doc(`guestTransitions/${ID0}`).get()).data();
  assert.strictEqual(record.guestUid, GUEST);
  assert.strictEqual(record.state, 'PREPARED');
  assert.strictEqual(record.intent, 'IMPORT');
  assert.strictEqual(record.capabilityHash.length, 64);
  assert.notStrictEqual(record.capability, CAP0);
  assert.strictEqual(record.expiresAt.toMillis(), NOW + LEASE_MS);

  const lease = (await db.doc(`guestTransitionLeases/${GUEST}`).get()).data();
  assert.deepStrictEqual({ transitionId: lease.transitionId, state: lease.state }, {
    transitionId: ID0,
    state: 'PREPARED',
  });

  const rate = (await db.doc(`rate-limits/guest_transition_${GUEST}`).get()).data();
  assert.deepStrictEqual(rate.attempts, [NOW]);
});

test('prepare is anonymous-only, and a deleted account cannot prepare', async () => {
  await expectRejection(
    prepare({ ...googleAuth(TARGET), data: transition() }),
    'permission-denied',
    'ANONYMOUS_ONLY',
  );
  await db.doc(`deletionJobs/${GUEST}`).set({ status: 'PENDING' });
  await expectRejection(prepare({ ...guestAuth(), data: transition() }), 'permission-denied', 'ACCOUNT_DELETING');
});

test('prepare rejects malformed input before touching state', async () => {
  await expectRejection(
    prepare({ ...guestAuth(), data: transition({ transitionId: '../guestTransitions/x' }) }),
    'invalid-argument',
    'INVALID_TRANSITION_ID',
  );
  await expectRejection(
    prepare({ ...guestAuth(), data: transition({ capability: 'short' }) }),
    'invalid-argument',
    'INVALID_CAPABILITY',
  );
  await expectRejection(
    prepare({ ...guestAuth(), data: transition({ intent: 'KEEP' }) }),
    'invalid-argument',
    'INVALID_INTENT',
  );
  await expectRejection(
    prepare({ ...guestAuth(), data: { ...transition(), extra: true } }),
    'invalid-argument',
    'INVALID_INPUT',
  );
});

test('a repeated prepare replays the stored state and consumes no window attempt', async () => {
  const first = await prepare({ ...guestAuth(), data: transition() });
  const second = await prepare({ ...guestAuth(), data: transition() });
  assert.deepStrictEqual(second, first);

  const rate = (await db.doc(`rate-limits/guest_transition_${GUEST}`).get()).data();
  assert.deepStrictEqual(rate.attempts, [NOW]);

  await expectRejection(
    prepare({ ...guestAuth(), data: transition({ intent: 'DISCARD' }) }),
    'failed-precondition',
    'INTENT_MISMATCH',
  );
  await expectRejection(
    prepare({ ...guestAuth(), data: transition({ capability: capFor(9) }) }),
    'already-exists',
    'TRANSITION_ID_CONFLICT',
  );
});

test('one unfinalized transition per guest uid, but different guests stay independent', async () => {
  await prepare({ ...guestAuth(), data: transition() });
  await expectRejection(
    prepare({ ...guestAuth(), data: transition({ transitionId: idFor(1), capability: capFor(1) }) }),
    'failed-precondition',
    'TRANSITION_ALREADY_PREPARED',
  );
  const other = await prepare({
    ...guestAuth(GUEST_2),
    data: transition({ transitionId: idFor(2), capability: capFor(2) }),
  });
  assert.strictEqual(other.state, 'PREPARED');
});

test('the gate blocks only new preparations — replays keep working with the gate off', async () => {
  await db.doc(GATE).delete();
  await expectRejection(
    prepare({ ...guestAuth(), data: transition() }),
    'failed-precondition',
    'GUEST_TRANSITION_DISABLED',
  );

  await db.doc(GATE).set({ enabled: true });
  await prepare({ ...guestAuth(), data: transition() });
  await db.doc(GATE).set({ enabled: false });

  const replay = await prepare({ ...guestAuth(), data: transition() });
  assert.strictEqual(replay.state, 'PREPARED');
  await expectRejection(
    prepare({ ...guestAuth(), data: transition({ transitionId: idFor(3), capability: capFor(3) }) }),
    'failed-precondition',
    'TRANSITION_ALREADY_PREPARED',
  );
});

test('an elapsed lease is written through as EXPIRED_UNFINALIZED and keeps the hash', async () => {
  await prepare({ ...guestAuth(), data: transition() });
  clockMs = NOW + LEASE_MS + 1;
  const replay = await prepare({ ...guestAuth(), data: transition() });
  assert.strictEqual(replay.state, 'EXPIRED_UNFINALIZED');

  const record = (await db.doc(`guestTransitions/${ID0}`).get()).data();
  assert.strictEqual(record.state, 'EXPIRED_UNFINALIZED');
  assert.strictEqual(typeof record.capabilityHash, 'string');
  const lease = (await db.doc(`guestTransitionLeases/${GUEST}`).get()).data();
  assert.strictEqual(lease.state, 'EXPIRED_UNFINALIZED');
});

test('five new preparations per rolling 24h, and replays never hit the limit', async () => {
  for (let i = 0; i < 5; i += 1) {
    await prepare({ ...guestAuth(), data: transition({ transitionId: idFor(10 + i), capability: capFor(10 + i) }) });
    await cancel({ ...guestAuth(), data: { transitionId: idFor(10 + i), capability: capFor(10 + i) } });
    clockMs += 1000;
  }
  const blocked = await expectRejection(
    prepare({ ...guestAuth(), data: transition({ transitionId: idFor(20), capability: capFor(20) }) }),
    'resource-exhausted',
    'TRANSITION_RATE_LIMITED',
  );
  assert.strictEqual(blocked.details.retryAfterMs, PREPARE_LIMIT_WINDOW_MS - 5000);

  const replay = await prepare({ ...guestAuth(), data: transition({ transitionId: idFor(10), capability: capFor(10) }) });
  assert.strictEqual(replay.state, 'CANCELLED');
});

// --- finalizeGuestTransition ---

async function seedTransitionWorld() {
  await seedProfile(TARGET);
  await seedProfile(OTHER_TARGET);
  await seedPair(`pair-a-${GUEST}`, [GUEST, PARTNER_A]);
  await seedPair(`pair-b-${GUEST}`, [GUEST, PARTNER_B]);
  await seedPair(`pair-c-${GUEST}`, [GUEST, PARTNER_C], 'terminated');
  await prepare({ ...guestAuth(), data: transition() });
}

test('finalize terminates each active guest pair once, emits deterministic events, enqueues cleanup', async () => {
  await seedTransitionWorld();
  const result = await finalize({ ...googleAuth(TARGET), data: transition() });
  assert.strictEqual(result.state, 'FINALIZED');
  assert.strictEqual(result.intent, 'IMPORT');
  assert.deepStrictEqual([...result.terminatedPairIds].sort(), [`pair-a-${GUEST}`, `pair-b-${GUEST}`]);
  assert.strictEqual(result.cleanupJobId, GUEST);

  for (const pairId of [`pair-a-${GUEST}`, `pair-b-${GUEST}`]) {
    assert.strictEqual((await db.doc(`pairings/${pairId}`).get()).get('status'), 'terminated');
    const event = (await db.doc(`pairings/${pairId}/system_events/guest_transition_terminated`).get()).data();
    assert.strictEqual(event.type, 'PAIR_TERMINATED');
    assert.strictEqual(event.actorUid, GUEST);
    assert.strictEqual(event.reason, 'GUEST_ACCOUNT_TRANSITION');
  }
  // A non-active pair gets no event.
  const inactiveEvent = await db.doc(`pairings/pair-c-${GUEST}/system_events/guest_transition_terminated`).get();
  assert.strictEqual(inactiveEvent.exists, false);

  const job = (await db.doc(`deletionJobs/${GUEST}`).get()).data();
  assert.strictEqual(job.kind, 'GUEST_TRANSITION_CLEANUP');
  assert.strictEqual(job.phase, 'QUEUED');
  assert.strictEqual(job.transitionId, ID0);
  assert.strictEqual(job.targetUid, TARGET);

  const record = (await db.doc(`guestTransitions/${ID0}`).get()).data();
  assert.strictEqual(record.state, 'FINALIZED');
  assert.strictEqual(record.targetUid, TARGET);
  assert.strictEqual(record.capabilityHash, undefined);
  assert.strictEqual((await db.doc(`guestTransitionLeases/${GUEST}`).get()).get('state'), 'FINALIZED');

  const retry = await finalize({ ...googleAuth(TARGET), data: transition() });
  assert.deepStrictEqual(retry, {
    state: 'FINALIZED',
    intent: 'IMPORT',
    terminatedPairIds: result.terminatedPairIds,
    cleanupJobId: GUEST,
  });
  const events = await db.collection(`pairings/pair-a-${GUEST}/system_events`).get();
  assert.strictEqual(events.size, 1);

  await expectRejection(
    finalize({ ...googleAuth(OTHER_TARGET), data: transition() }),
    'permission-denied',
    'TRANSITION_OWNED_BY_ANOTHER_ACCOUNT',
  );
});

test('finalize refuses anonymous callers, bad capabilities, bad intents, and unknown ids', async () => {
  await seedTransitionWorld();
  await expectRejection(
    finalize({ ...guestAuth(TARGET), data: transition() }),
    'failed-precondition',
    'PERSISTENT_ACCOUNT_REQUIRED',
  );
  await expectRejection(
    finalize({ ...googleAuth(TARGET), data: transition({ capability: capFor(99) }) }),
    'permission-denied',
    'INVALID_CAPABILITY',
  );
  await expectRejection(
    finalize({ ...googleAuth(TARGET), data: transition({ intent: 'DISCARD' }) }),
    'failed-precondition',
    'INTENT_MISMATCH',
  );
  await expectRejection(
    finalize({ ...googleAuth(TARGET), data: transition({ transitionId: idFor(77) }) }),
    'not-found',
    'TRANSITION_NOT_FOUND',
  );
});

test('an expired unfinalized transition needs a fresh target reauthentication', async () => {
  await seedTransitionWorld();
  clockMs = NOW + LEASE_MS + 1000;
  await expectRejection(
    finalize({ ...googleAuth(TARGET), data: transition() }),
    'failed-precondition',
    'REAUTH_REQUIRED',
  );
  const withRecentAuth = authAs(TARGET, 'google.com', { auth_time: Math.floor(clockMs / 1000) });
  const result = await finalize({ ...withRecentAuth, data: transition() });
  assert.strictEqual(result.state, 'FINALIZED');
  // auth_time just outside the five-minute freshness window fails closed again on a fresh record.
  assert.strictEqual(await db.doc(`guestTransitions/${ID0}`).get().then((s) => s.get('state')), 'FINALIZED');
});

test('finalize requires an active target profile and refuses a self-transition', async () => {
  await prepare({ ...guestAuth(), data: transition() });
  await expectRejection(
    finalize({ ...googleAuth(TARGET), data: transition() }),
    'failed-precondition',
    'PROFILE_NOT_ACTIVE',
  );

  await seedProfile(TARGET);
  await db.doc(`deletionJobs/${TARGET}`).set({ status: 'PENDING' });
  await expectRejection(
    finalize({ ...googleAuth(TARGET), data: transition() }),
    'permission-denied',
    'ACCOUNT_DELETING',
  );

  await db.doc(`deletionJobs/${TARGET}`).delete();
  await expectRejection(
    finalize({ ...googleAuth(GUEST), data: transition() }),
    'failed-precondition',
    'SAME_IDENTITY',
  );
});

test('an existing cleanup job is reused instead of overwritten', async () => {
  await seedTransitionWorld();
  await db.doc(`deletionJobs/${GUEST}`).set({ kind: 'GUEST_DELETE', phase: 'RUNNING' });
  const result = await finalize({ ...googleAuth(TARGET), data: transition() });
  assert.strictEqual(result.cleanupJobId, GUEST);
  const job = (await db.doc(`deletionJobs/${GUEST}`).get()).data();
  assert.strictEqual(job.kind, 'GUEST_DELETE');
  assert.strictEqual(job.phase, 'RUNNING');
});

// --- cancelGuestTransition ---

test('cancel is permanent, keeps the hash-only record, and frees the lease', async () => {
  await prepare({ ...guestAuth(), data: transition() });
  const result = await cancel({ ...guestAuth(), data: { transitionId: ID0, capability: CAP0 } });
  assert.deepStrictEqual(result, { state: 'CANCELLED' });

  const record = (await db.doc(`guestTransitions/${ID0}`).get()).data();
  assert.strictEqual(record.state, 'CANCELLED');
  assert.strictEqual(typeof record.capabilityHash, 'string');
  assert.strictEqual((await db.doc(`guestTransitionLeases/${GUEST}`).get()).exists, false);

  const again = await cancel({ ...guestAuth(), data: { transitionId: ID0, capability: CAP0 } });
  assert.deepStrictEqual(again, { state: 'CANCELLED' });

  const fresh = await prepare({
    ...guestAuth(),
    data: transition({ transitionId: idFor(5), capability: capFor(5) }),
  });
  assert.strictEqual(fresh.state, 'PREPARED');
});

test('cancel verifies ownership, capability and session kind', async () => {
  await prepare({ ...guestAuth(), data: transition() });
  await expectRejection(
    cancel({ ...guestAuth(GUEST_2), data: { transitionId: ID0, capability: CAP0 } }),
    'permission-denied',
    'NOT_TRANSITION_OWNER',
  );
  await expectRejection(
    cancel({ ...guestAuth(), data: { transitionId: ID0, capability: capFor(42) } }),
    'permission-denied',
    'INVALID_CAPABILITY',
  );
  await expectRejection(
    cancel({ ...googleAuth(GUEST), data: { transitionId: ID0, capability: CAP0 } }),
    'permission-denied',
    'ANONYMOUS_ONLY',
  );
});

test('cancel works on an expired transition and with the gate off, but never after finalize', async () => {
  await prepare({ ...guestAuth(), data: transition() });
  await db.doc(GATE).delete();
  clockMs = NOW + LEASE_MS + 1;
  const expiredCancel = await cancel({ ...guestAuth(), data: { transitionId: ID0, capability: CAP0 } });
  assert.deepStrictEqual(expiredCancel, { state: 'CANCELLED' });

  await db.doc(GATE).set({ enabled: true });
  await prepare({ ...guestAuth(), data: transition({ transitionId: idFor(6), capability: capFor(6) }) });
  await seedProfile(TARGET);
  await finalize({ ...googleAuth(TARGET), data: transition({ transitionId: idFor(6), capability: capFor(6) }) });
  await expectRejection(
    cancel({ ...guestAuth(), data: { transitionId: idFor(6), capability: capFor(6) } }),
    'failed-precondition',
    'TRANSITION_FINALIZED',
  );
});
