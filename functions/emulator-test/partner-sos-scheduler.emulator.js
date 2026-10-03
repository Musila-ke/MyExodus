'use strict';
// Partner SOS repair-scheduler suite (plan §8.11/§6.6, §12.1 T-F16/T-F19/T-F20, §9.2
// repair): the real sweep handler against the Firestore emulator, driving the real
// `pushNudge` engine with a scripted FCM client. Run via `npm run test:sos`.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');

const { createPushNudge } = require('../partner-sos-push');
const { createResumePendingNudgesHandler } = require('../partner-sos-scheduler');

// Own project namespace: `node --test` runs emulator files in parallel, and
// clearFirestore-style cleanup would otherwise cross files in one project.
const PROJECT_ID = 'demo-myexodus-sos-scheduler';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const PAIR = 'alice-uid_bob-uid';
const FIXED_NOW = 1_800_000_000_000;
const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

let app;
let db;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'sos-scheduler-emulator-suite');
  db = getFirestore(app);
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  await db.recursiveDelete(db.collection('pairings'));
  await db.recursiveDelete(db.collection('nudgePushQueue'));
  await db.recursiveDelete(db.collection('users'));
});

const nudgeId = () => crypto.randomUUID();

function scriptedMessaging(script) {
  const calls = [];
  return {
    calls,
    async sendEachForMulticast(message) {
      calls.push(message);
      const responses = await script(message);
      return {
        responses,
        successCount: responses.filter((r) => r.success).length,
        failureCount: responses.filter((r) => !r.success).length,
      };
    },
  };
}

const ok = { success: true };

async function seedPair(status = 'active') {
  await db.doc(`pairings/${PAIR}`).set({ members: [ALICE, BOB], status });
}

async function seedNudge(id, overrides = {}) {
  await db.doc(`pairings/${PAIR}/nudges/${id}`).set({
    senderUid: ALICE,
    recipientUid: BOB,
    envelope: 'AAECAw==',
    envelopeVersion: 1,
    status: 'pending',
    createdAtMs: FIXED_NOW - 10 * 60 * 1000,
    expiresAt: Timestamp.fromMillis(FIXED_NOW + RETENTION_MS),
    pushAttempts: 0,
    targetDeviceCount: 0,
    sentDeviceCount: 0,
    ...overrides,
  });
}

async function seedQueue(id, overrides = {}) {
  await db.doc(`nudgePushQueue/${id}`).set({
    pairId: PAIR,
    createdAtMs: FIXED_NOW - 10 * 60 * 1000,
    expiresAt: Timestamp.fromMillis(FIXED_NOW + RETENTION_MS),
    nextAttemptAtMs: FIXED_NOW - 60_000, // due
    attemptCount: 0,
    leaseId: null,
    leaseUntilMs: null,
    targetInstallIds: [],
    sentInstallIds: [],
    ...overrides,
  });
}

async function seedDevice(installId, uid = BOB, fields = { pushEligible: true }) {
  await db.doc(`users/${uid}/push_installations/${installId}`).set({
    provider: 'fcm',
    token: 'tok',
    platform: 'android',
    appVersion: '1.0',
    ...fields,
  });
}

const engine = (messaging) =>
  createPushNudge({ db, messaging, now: () => FIXED_NOW, rand: () => 0.5 });
const scheduler = (messaging) =>
  createResumePendingNudgesHandler({ db, pushNudge: engine(messaging), now: () => FIXED_NOW });

const nudgeOf = async (id) => (await db.doc(`pairings/${PAIR}/nudges/${id}`).get()).data();
const queueOf = async (id) => db.doc(`nudgePushQueue/${id}`).get();

// --- T-F19/T-F20: the two crash windows ---

test('T-F19: crash before FCM — the due queue is repaired by the sweep', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'] });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [ok]);

  const summary = await scheduler(messaging)();
  assert.strictEqual(summary.due, 1);
  assert.strictEqual(summary.resumed, 1);
  assert.strictEqual(summary.errored, 0);
  assert.strictEqual((await nudgeOf(id)).status, 'sent');
  assert.strictEqual((await queueOf(id)).exists, false);
  assert.strictEqual(messaging.calls.length, 1);
  assert.deepStrictEqual(messaging.calls[0].fids, ['d1']);
});

test('T-F20: crash after FCM acceptance before finalization — expired lease reclaimed, resend tolerated', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id, { pushAttempts: 1 });
  await seedQueue(id, {
    targetInstallIds: ['d1'],
    sentInstallIds: [], // the finalization write never landed, so nothing is marked sent
    attemptCount: 1,
    leaseId: 'dead-worker',
    leaseUntilMs: FIXED_NOW - 1_000, // the crashed worker's lease
  });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [ok]);

  const summary = await scheduler(messaging)();
  assert.strictEqual(summary.resumed, 1);
  assert.strictEqual(messaging.calls.length, 1, 'at-least-once: the un-finalized nudge is re-sent');
  const nudge = await nudgeOf(id);
  assert.strictEqual(nudge.status, 'sent');
  assert.strictEqual(nudge.pushAttempts, 2);
  assert.strictEqual((await queueOf(id)).exists, false);
});

// --- query contract ---

test('a future nextAttemptAtMs is not picked up and nothing is touched', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'], nextAttemptAtMs: FIXED_NOW + 60_000 });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [ok]);

  const summary = await scheduler(messaging)();
  assert.strictEqual(summary.due, 0);
  assert.strictEqual(messaging.calls.length, 0);
  assert.strictEqual((await nudgeOf(id)).status, 'pending');
  assert.strictEqual((await queueOf(id)).exists, true);
});

test('T-F16 (scheduler half): a live lease from the trigger path makes the sweep a no-op', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, {
    targetInstallIds: ['d1'],
    leaseId: 'trigger-owner',
    leaseUntilMs: FIXED_NOW + 30_000,
  });
  const messaging = scriptedMessaging(() => [ok]);

  const summary = await scheduler(messaging)();
  assert.strictEqual(summary.due, 1);
  assert.strictEqual(summary.skipped, 1);
  assert.strictEqual(messaging.calls.length, 0);
  const queue = (await queueOf(id)).data();
  assert.strictEqual(queue.leaseId, 'trigger-owner', 'the sweep never touches a foreign lease');
  assert.strictEqual(queue.leaseUntilMs, FIXED_NOW + 30_000);
});

test('the sweep takes the 50 oldest due queues, in nextAttemptAtMs order', async () => {
  const ids = [];
  for (let i = 0; i < 51; i += 1) {
    const id = nudgeId();
    ids.push(id);
    // i=50 has the highest nextAttemptAtMs: the one left over for the next tick.
    await seedQueue(id, { nextAttemptAtMs: FIXED_NOW - 51 + i });
  }
  const messaging = scriptedMessaging(() => [ok]);

  const summary = await scheduler(messaging)();
  assert.strictEqual(summary.due, 50);
  assert.strictEqual(summary.skipped, 50, 'all are orphans (queue without nudge)');
  const remaining = await db.collection('nudgePushQueue').get();
  assert.strictEqual(remaining.size, 1);
  assert.strictEqual(remaining.docs[0].id, ids[50]);
});

// --- §6.6 expiry finalization ---

test('§6.6: a queue past the nudge expiry is finalized failed, never dispatched', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id, { expiresAt: Timestamp.fromMillis(FIXED_NOW - 1) });
  await seedQueue(id, { targetInstallIds: ['d1'] });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [ok]);

  const summary = await scheduler(messaging)();
  assert.strictEqual(summary.resumed, 1);
  assert.strictEqual(messaging.calls.length, 0, 'expired work never reaches FCM');
  assert.strictEqual((await nudgeOf(id)).status, 'failed');
  assert.strictEqual((await queueOf(id)).exists, false);
});

test('§6.6: an expired queue under a revoked pair finalizes suppressed instead', async () => {
  await seedPair('terminated');
  const id = nudgeId();
  await seedNudge(id, { expiresAt: Timestamp.fromMillis(FIXED_NOW - 1) });
  await seedQueue(id, { targetInstallIds: ['d1'] });
  const messaging = scriptedMessaging(() => [ok]);

  await scheduler(messaging)();
  assert.strictEqual(messaging.calls.length, 0);
  assert.strictEqual((await nudgeOf(id)).status, 'suppressed');
  assert.strictEqual((await queueOf(id)).exists, false);
});

// --- §9.2: queue missing while the nudge is pending ---

test('§9.2: pending nudge without its queue is repaired, then dispatched by the next sweep', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id, { pushAttempts: 2 });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [ok]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'queue_repaired');
  const queue = (await queueOf(id)).data();
  assert.ok(queue, 'the repair recreated the queue document');
  assert.strictEqual(queue.nextAttemptAtMs, FIXED_NOW);
  assert.strictEqual(queue.attemptCount, 2, 'attempt budget is preserved, not reset');
  assert.strictEqual(queue.leaseId, null);
  assert.strictEqual((await nudgeOf(id)).status, 'pending', 'repair does not dispatch by itself');
  assert.strictEqual(messaging.calls.length, 0);

  const summary = await scheduler(messaging)();
  assert.strictEqual(summary.due, 1);
  assert.strictEqual((await nudgeOf(id)).status, 'sent');
  assert.strictEqual(messaging.calls.length, 1);
  assert.strictEqual((await queueOf(id)).exists, false);
});

test('a queue is not repaired when the nudge is already terminal (normal cleanup)', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id, { status: 'sent' });
  const messaging = scriptedMessaging(() => [ok]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'missing');
  assert.strictEqual((await queueOf(id)).exists, false);
  assert.strictEqual(messaging.calls.length, 0);
});

test('§9.2: repair also fires past expiry — the sweep then finalizes it failed, never sent', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id, { expiresAt: Timestamp.fromMillis(FIXED_NOW - 1) });
  const messaging = scriptedMessaging(() => [ok]);

  assert.strictEqual((await engine(messaging)(PAIR, id)).outcome, 'queue_repaired');
  await scheduler(messaging)();
  assert.strictEqual(messaging.calls.length, 0);
  assert.strictEqual((await nudgeOf(id)).status, 'failed');
  assert.strictEqual((await queueOf(id)).exists, false);
});

// --- sweep resilience ---

test('a malformed queue document is logged and does not stop the sweep', async () => {
  await seedPair();
  const badId = nudgeId();
  await seedQueue(badId, { pairId: 42 });
  const goodId = nudgeId();
  await seedNudge(goodId);
  await seedQueue(goodId, { targetInstallIds: ['d1'] });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [ok]);

  const summary = await scheduler(messaging)();
  assert.strictEqual(summary.due, 2);
  assert.strictEqual(summary.errored, 1);
  assert.strictEqual(summary.resumed, 1);
  assert.strictEqual((await nudgeOf(goodId)).status, 'sent');
  assert.strictEqual((await queueOf(badId)).exists, true, 'the poison queue stays for review');
});
