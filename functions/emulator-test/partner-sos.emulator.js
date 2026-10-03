'use strict';
// Partner SOS transactional suite — the real `sendNudge` handler against the Firestore
// emulator (plan §12.1 T-F1/T-F4/T-F5/T-F6/T-F7/T-F10/T-F13/T-F14, plus the §8.4 document
// shapes and gates). Named without `.test.js` on purpose: plain `npm test` must stay
// emulator-free, so this file runs only via `npm run test:sos` (firebase emulators:exec).
//
// `firebase emulators:exec` exports FIRESTORE_EMULATOR_HOST for this process, so a second
// admin app bound to it talks to the emulator while `index.js`'s default app is never
// touched. `createSendNudgeHandler` is the same handler `sendNudge` deploys.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { HttpsError } = require('firebase-functions/v2/https');

const { createSendNudgeHandler } = require('../partner-sos');
const { NUDGE_COOLDOWN_MS, NUDGE_RETENTION_MS, dayKeyOf } = require('../partner-sos-logic');

const PROJECT_ID = 'demo-myexodus-sos';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';
const PAIR = 'alice-uid_bob-uid';
const ENVELOPE = 'eyJ2IjoxLCJraW5kIjoibnVkZ2UifQ==';

let app;
let db;
let handler;

const nudgeId = () => crypto.randomUUID();

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'sos-emulator-suite');
  db = getFirestore(app);
  handler = createSendNudgeHandler({ db });
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  await db.recursiveDelete(db.collection('pairings'));
  await db.recursiveDelete(db.collection('nudgePushQueue'));
  await db.recursiveDelete(db.collection('config'));
});

const seedPair = async (status = 'active', members = [ALICE, BOB], pairId = PAIR) => {
  await db.doc(`pairings/${pairId}`).set({ members, status, createdAt: new Date() });
};

const seedEnabled = async (enabled = true) => {
  await db.doc('config/features').set({ partnerSosEnabled: enabled });
};

const seedBlocked = async (uid = BOB, pairId = PAIR) => {
  await db.doc(`pairings/${pairId}/nudgeSettings/${uid}`).set({ blocked: true, updatedAt: new Date() });
};

const seedState = async (uid, fields, pairId = PAIR) => {
  await db.doc(`pairings/${pairId}/nudgeState/${uid}`).set(fields);
};

const call = (data, uid = ALICE) => handler({ auth: uid ? { uid } : undefined, data });

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

const nudgeDoc = (id) => db.doc(`pairings/${PAIR}/nudges/${id}`);
const queueDoc = (id) => db.doc(`nudgePushQueue/${id}`);

// --- T-F1: first nudge ---

test('T-F1: first nudge is accepted; nudge + queue + rate state land atomically', async () => {
  await seedPair();
  await seedEnabled();
  const id = nudgeId();

  const result = await call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE });
  assert.deepStrictEqual(result, { status: 'accepted', nudgeId: id });

  const nudge = (await nudgeDoc(id).get()).data();
  assert.strictEqual(nudge.senderUid, ALICE);
  assert.strictEqual(nudge.recipientUid, BOB);
  assert.strictEqual(nudge.envelope, ENVELOPE);
  assert.strictEqual(nudge.envelopeVersion, 1);
  assert.strictEqual(nudge.status, 'pending');
  assert.strictEqual(nudge.ackedAt, null);
  assert.strictEqual(nudge.suppressedAt, null);
  assert.strictEqual(nudge.pushAttempts, 0);
  assert.strictEqual(typeof nudge.createdAtMs, 'number');
  assert.ok(Math.abs(nudge.createdAtMs - Date.now()) < 5000, 'createdAtMs is server-side now');
  const ttl = nudge.expiresAt.toMillis() - nudge.createdAtMs;
  assert.strictEqual(ttl, NUDGE_RETENTION_MS);

  const queue = (await queueDoc(id).get()).data();
  assert.strictEqual(queue.pairId, PAIR);
  assert.strictEqual(queue.nextAttemptAtMs, queue.createdAtMs);
  assert.strictEqual(queue.attemptCount, 0);
  assert.strictEqual(queue.leaseId, null);
  assert.strictEqual(queue.leaseUntilMs, null);
  assert.deepStrictEqual(queue.targetInstallIds, []);
  assert.deepStrictEqual(queue.sentInstallIds, []);

  const state = (await db.doc(`pairings/${PAIR}/nudgeState/${ALICE}`).get()).data();
  assert.strictEqual(state.lastNudgeId, id);
  assert.strictEqual(state.dayKey, dayKeyOf(Date.now()));
  assert.strictEqual(state.dayCount, 1);
});

// --- T-F2/T-F3: cooldown (deterministic seeds instead of waiting 10 minutes) ---

test('T-F2: +9 min is rate_limited/cooldown and mutates nothing', async () => {
  await seedPair();
  await seedEnabled();
  await seedState(ALICE, {
    lastAcceptedAtMs: Date.now() - 9 * 60 * 1000,
    lastNudgeId: nudgeId(),
    dayKey: dayKeyOf(Date.now()),
    dayCount: 3,
  });
  const id = nudgeId();

  const error = await expectRejection(
    call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE }),
    'resource-exhausted',
    'rate_limited',
  );
  assert.strictEqual(error.details.reason, 'cooldown');
  assert.ok(error.details.retryAfterSeconds >= 1 && error.details.retryAfterSeconds <= 60);

  assert.strictEqual((await nudgeDoc(id).get()).exists, false);
  assert.strictEqual((await queueDoc(id).get()).exists, false);
  const state = (await db.doc(`pairings/${PAIR}/nudgeState/${ALICE}`).get()).data();
  assert.strictEqual(state.dayCount, 3, 'dayCount must not change on rejection');
});

test('T-F3: a tap at exactly +10 min is eligible', async () => {
  await seedPair();
  await seedEnabled();
  await seedState(ALICE, {
    lastAcceptedAtMs: Date.now() - NUDGE_COOLDOWN_MS,
    lastNudgeId: nudgeId(),
    dayKey: dayKeyOf(Date.now()),
    dayCount: 3,
  });

  const result = await call({ pairId: PAIR, nudgeId: nudgeId(), envelope: ENVELOPE });
  assert.strictEqual(result.status, 'accepted');
  const state = (await db.doc(`pairings/${PAIR}/nudgeState/${ALICE}`).get()).data();
  assert.strictEqual(state.dayCount, 4);
});

// --- T-F4/T-F5: same nudgeId+envelope replays without a second cooldown ---

test('T-F5: replay while pending is accepted and consumes nothing', async () => {
  await seedPair();
  await seedEnabled();
  const id = nudgeId();
  await call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE });

  const result = await call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE });
  assert.deepStrictEqual(result, { status: 'accepted', nudgeId: id });

  const state = (await db.doc(`pairings/${PAIR}/nudgeState/${ALICE}`).get()).data();
  assert.strictEqual(state.dayCount, 1, 'replay must not consume the cap again');
});

test('T-F4: replay after dispatch (status sent) is still accepted', async () => {
  await seedPair();
  await seedEnabled();
  const id = nudgeId();
  await call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE });
  await nudgeDoc(id).update({ status: 'sent', terminalAt: new Date() });

  const result = await call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE });
  assert.deepStrictEqual(result, { status: 'accepted', nudgeId: id });
  assert.strictEqual((await queueDoc(id).get()).exists, true, 'queue untouched by replay');
});

// --- T-F6/T-F7: id reuse by content / by sender ---

test('T-F6: same nudgeId with a different envelope is invalid-argument', async () => {
  await seedPair();
  await seedEnabled();
  const id = nudgeId();
  await call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE });

  await expectRejection(
    call({ pairId: PAIR, nudgeId: id, envelope: 'eyJ2IjoxLCJraW5kIjoib3RoZXIifQ==' }),
    'invalid-argument',
    'nudge_id_reuse',
  );
});

test('T-F7: same nudgeId from the other member is permission-denied', async () => {
  await seedPair();
  await seedEnabled();
  const id = nudgeId();
  await call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE });

  // Bob IS a member of the pair — the rejection is the sender mismatch, not membership.
  await expectRejection(
    call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE }, BOB),
    'permission-denied',
    'nudge_sender_mismatch',
  );
});

// --- T-F8: daily cap ---

test('T-F8: the 13th accepted nudge in a Nairobi day is rate_limited/daily_cap', async () => {
  await seedPair();
  await seedEnabled();
  await seedState(ALICE, {
    lastAcceptedAtMs: Date.now() - NUDGE_COOLDOWN_MS,
    lastNudgeId: nudgeId(),
    dayKey: dayKeyOf(Date.now()),
    dayCount: 12,
  });

  const error = await expectRejection(
    call({ pairId: PAIR, nudgeId: nudgeId(), envelope: ENVELOPE }),
    'resource-exhausted',
    'rate_limited',
  );
  assert.strictEqual(error.details.reason, 'daily_cap');
  assert.ok(error.details.retryAfterSeconds > 0 && error.details.retryAfterSeconds <= 86400);
});

// --- T-F9: A<->B directionality (independent rate streams) ---

test('T-F9: Bob nudging Alice has his own independent rate state', async () => {
  await seedPair();
  await seedEnabled();
  await call({ pairId: PAIR, nudgeId: nudgeId(), envelope: ENVELOPE }, ALICE);

  const result = await call({ pairId: PAIR, nudgeId: nudgeId(), envelope: ENVELOPE }, BOB);
  assert.strictEqual(result.status, 'accepted');

  const bobsState = (await db.doc(`pairings/${PAIR}/nudgeState/${BOB}`).get()).data();
  assert.strictEqual(bobsState.dayCount, 1);
  const alicesState = (await db.doc(`pairings/${PAIR}/nudgeState/${ALICE}`).get()).data();
  assert.strictEqual(alicesState.dayCount, 1, 'Alice is unaffected by Bob nudging');
});

// --- T-F10 (+FR-5): blocked recipient at acceptance ---

test('T-F10: blocked recipient -> accepted, suppressed, no queue, cap consumed', async () => {
  await seedPair();
  await seedEnabled();
  await seedBlocked(BOB);
  const id = nudgeId();

  const result = await call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE });
  assert.deepStrictEqual(result, { status: 'accepted', nudgeId: id });

  const nudge = (await nudgeDoc(id).get()).data();
  assert.strictEqual(nudge.status, 'suppressed');
  assert.ok(nudge.suppressedAt, 'suppressedAt must be stamped');
  assert.strictEqual((await queueDoc(id).get()).exists, false, 'no queue for suppressed nudge');

  const state = (await db.doc(`pairings/${PAIR}/nudgeState/${ALICE}`).get()).data();
  assert.strictEqual(state.dayCount, 1, 'suppressed nudges consume the cap (FR-5)');
});

// --- T-F13/T-F14: concurrency ---

test('T-F13: ten concurrent distinct nudgeIds -> exactly one acceptance', async () => {
  await seedPair();
  await seedEnabled();
  const ids = Array.from({ length: 10 }, () => nudgeId());

  const results = await Promise.allSettled(
    ids.map((id) => call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE })),
  );
  const accepted = results.filter(
    (r) => r.status === 'fulfilled' && r.value.status === 'accepted',
  );
  const limited = results.filter(
    (r) => r.status === 'rejected' && r.reason.details && r.reason.details.reason === 'cooldown',
  );
  assert.strictEqual(accepted.length, 1);
  assert.strictEqual(limited.length, 9);

  const state = (await db.doc(`pairings/${PAIR}/nudgeState/${ALICE}`).get()).data();
  assert.strictEqual(state.dayCount, 1);
});

test('T-F14: concurrent same-nudgeId calls -> one nudge, one queue, one cap unit', async () => {
  await seedPair();
  await seedEnabled();
  const id = nudgeId();

  const results = await Promise.all(
    Array.from({ length: 5 }, () => call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE })),
  );
  for (const result of results) assert.deepStrictEqual(result, { status: 'accepted', nudgeId: id });

  const queueDocs = await db.collection('nudgePushQueue').get();
  assert.strictEqual(queueDocs.size, 1);
  const nudges = await db.collection(`pairings/${PAIR}/nudges`).get();
  assert.strictEqual(nudges.size, 1);
  const state = (await db.doc(`pairings/${PAIR}/nudgeState/${ALICE}`).get()).data();
  assert.strictEqual(state.dayCount, 1);
});

// --- gates: feature flag, pair state, membership, input, auth ---

test('feature_disabled: a missing or false flag rejects with failed-precondition and no writes', async () => {
  await seedPair();
  const id = nudgeId();
  await expectRejection(
    call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE }),
    'failed-precondition',
    'feature_disabled',
  );
  await seedEnabled(false);
  await expectRejection(
    call({ pairId: PAIR, nudgeId: id, envelope: ENVELOPE }),
    'failed-precondition',
    'feature_disabled',
  );
  assert.strictEqual((await nudgeDoc(id).get()).exists, false);
  assert.strictEqual((await db.doc(`pairings/${PAIR}/nudgeState/${ALICE}`).get()).exists, false);
});

test('terminated pair and non-member callers are denied', async () => {
  await seedEnabled();
  await seedPair('terminated');
  await expectRejection(
    call({ pairId: PAIR, nudgeId: nudgeId(), envelope: ENVELOPE }),
    'permission-denied',
    'pair_not_active',
  );

  await db.recursiveDelete(db.collection('pairings'));
  await seedEnabled();
  await seedPair('active');
  await expectRejection(
    call({ pairId: PAIR, nudgeId: nudgeId(), envelope: ENVELOPE }, CAROL),
    'permission-denied',
    'not_a_pair_member',
  );
});

test('unauthenticated and malformed requests are rejected before any read', async () => {
  await expectRejection(call({ pairId: PAIR, nudgeId: nudgeId(), envelope: ENVELOPE }, null), 'unauthenticated');
  await expectRejection(
    call({ pairId: PAIR, nudgeId: nudgeId(), envelope: ENVELOPE, extra: 1 }),
    'invalid-argument',
    'INPUT_UNKNOWN_OR_MISSING_FIELDS',
  );
  await expectRejection(
    call({ pairId: PAIR, nudgeId: 'not-a-uuid', envelope: ENVELOPE }),
    'invalid-argument',
    'NUDGE_ID_INVALID',
  );
  await expectRejection(
    call({ pairId: PAIR, nudgeId: nudgeId(), envelope: 'x'.repeat(1028) }),
    'invalid-argument',
    'ENVELOPE_INVALID',
  );
});
