'use strict';
// Partner SOS dispatch suite — the real `pushNudge` engine against the Firestore emulator
// with a scripted FCM client (plan §12.1 T-F11/T-F12/T-F15/T-F17/T-F18/T-F21-T-F26, target
// snapshot immutability, invalid-registration removal, payload-failure path). Run via
// `npm run test:sos`; plain `npm test` must stay emulator-free.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');

const { createPushNudge, nudgeFcmMessage } = require('../partner-sos-push');

// Same reasoning as the sendNudge suite: `node --test` runs emulator files in parallel
// processes, so each file gets its own project namespace to keep beforeEach clears apart.
const PROJECT_ID = 'demo-myexodus-sos-push';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const PAIR = 'alice-uid_bob-uid';
// Far future/past anchors; `now` is injected so lease windows are exact.
const FIXED_NOW = 1_800_000_000_000;

let app;
let db;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'sos-push-emulator-suite');
  db = getFirestore(app);
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  await db.recursiveDelete(db.collection('pairings'));
  await db.recursiveDelete(db.collection('nudgePushQueue'));
  // Device registrations persist across tests otherwise, and a leftover eligible device
  // leaks into the snapshot query of any later test (caught by T-F23).
  await db.recursiveDelete(db.collection('users'));
});

const nudgeId = () => crypto.randomUUID();

/** Scripted FCM double: records messages, resolves each FID per the script callback. */
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
const fcmError = (code) => ({ success: false, error: { code: `messaging/${code}` } });

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
    targetDeviceCount: 0,
    sentDeviceCount: 0,
    ...overrides,
  });
}

async function seedQueue(id, overrides = {}) {
  await db.doc(`nudgePushQueue/${id}`).set({
    pairId: PAIR,
    createdAtMs: FIXED_NOW,
    nextAttemptAtMs: FIXED_NOW,
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
    token: `tok-${installId}`,
    platform: 'android',
    appVersion: '1.0',
    ...fields,
  });
}

const engine = (messaging, now = () => FIXED_NOW) =>
  createPushNudge({ db, messaging, now, rand: () => 0.5 });

const nudgeOf = async (id) => (await db.doc(`pairings/${PAIR}/nudges/${id}`).get()).data();
const queueOf = async (id) => db.doc(`nudgePushQueue/${id}`).get();

// --- T-F21: full success ---

test('T-F21: all targets accepted -> sent, counts updated, queue deleted atomically', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'], sentInstallIds: [] });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [ok]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'sent');

  const nudge = await nudgeOf(id);
  assert.strictEqual(nudge.status, 'sent');
  assert.ok(nudge.terminalAt);
  assert.strictEqual(nudge.targetDeviceCount, 1);
  assert.strictEqual(nudge.sentDeviceCount, 1);
  assert.strictEqual(nudge.pushAttempts, 1);
  assert.ok(nudge.lastPushAtMs);
  assert.strictEqual((await queueOf(id)).exists, false);

  // §4.6 message contract, asserted on the wire payload the engine built. Token targeting
  // since the 2026-10-04 deviation (see nudgeFcmMessage): the doc id remains the ledger key,
  // the stored registration token is what FCM receives. D16 (2026-10-23): data-only, carrying
  // the pair id the receiving phone resolves to its local partner label — no notification
  // block, no generic copy on the wire.
  const message = messaging.calls[0];
  assert.deepStrictEqual(message.tokens, ['tok-d1']);
  assert.strictEqual(message.data.type, 'partner_nudge');
  assert.strictEqual(message.data.nudge_id, id);
  assert.strictEqual(message.data.pair_id, PAIR);
  assert.strictEqual(message.android.priority, 'high');
  assert.strictEqual(message.android.ttl, 3_600_000);
  assert.strictEqual(message.android.restrictedPackageName, 'com.example.myexodus');
  assert.strictEqual(message.notification, undefined);
  assert.strictEqual(message.android.notification, undefined);
  // A missing pair id (hand-written queue doc) degrades to the generic copy on the client.
  assert.strictEqual(nudgeFcmMessage(['x'], 'n', undefined).data.pair_id, undefined);
});

test('the first dispatch snapshots eligible installations and freezes the target set', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id); // empty targetInstallIds -> snapshot now
  await seedDevice('d1');
  await seedDevice('d2');
  await seedDevice('d3', BOB, { pushEligible: false });
  const messaging = scriptedMessaging((m) => m.tokens.map(() => ok));

  await engine(messaging)(PAIR, id);
  assert.deepStrictEqual([...messaging.calls[0].tokens].sort(), ['tok-d1', 'tok-d2']);

  // A later registration (d4) must never join this nudge; a retry re-reads the snapshot.
  const id2 = nudgeId();
  await seedNudge(id2);
  await seedQueue(id2, { targetInstallIds: ['d1'], sentInstallIds: [] });
  await seedDevice('d4');
  const messaging2 = scriptedMessaging(() => [ok]);
  await engine(messaging2)(PAIR, id2);
  assert.deepStrictEqual(messaging2.calls[0].tokens, ['tok-d1']);
});

// --- T-F23: no eligible device ---

test('T-F23: zero eligible devices -> no_eligible_device, queue deleted, nudge retained', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id);
  await seedDevice('d1', BOB, { pushEligible: false });
  const messaging = scriptedMessaging(() => [ok]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'no_eligible_device');
  assert.strictEqual(messaging.calls.length, 0);

  const nudge = await nudgeOf(id);
  assert.strictEqual(nudge.status, 'no_eligible_device');
  assert.strictEqual(nudge.targetDeviceCount, 0);
  assert.strictEqual((await queueOf(id)).exists, false);
});

// --- T-F22/T-F24: permanent failures + invalid-registration cleanup ---

test('T-F22: one sent + one invalid -> partial; the invalid registration is deleted', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1', 'd2'] });
  await seedDevice('d1');
  await seedDevice('d2');
  const messaging = scriptedMessaging((m) =>
    m.tokens.map((tok) => (tok === 'tok-d2' ? fcmError('registration-token-not-registered') : ok)),
  );

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'partial');
  const nudge = await nudgeOf(id);
  assert.strictEqual(nudge.status, 'partial');
  assert.strictEqual(nudge.sentDeviceCount, 1);
  assert.strictEqual(nudge.lastPushErrorCode, 'registration-token-not-registered');
  assert.strictEqual((await queueOf(id)).exists, false);
  assert.strictEqual((await db.doc(`users/${BOB}/push_installations/d2`).get()).exists, false);
  assert.strictEqual((await db.doc(`users/${BOB}/push_installations/d1`).get()).exists, true);
});

test('T-F24: all targets terminally invalid -> failed; every invalid registration removed', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1', 'd2'] });
  await seedDevice('d1');
  await seedDevice('d2');
  const messaging = scriptedMessaging((m) => m.tokens.map(() => fcmError('installation-id-not-registered')));

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'failed');
  assert.strictEqual((await nudgeOf(id)).status, 'failed');
  assert.strictEqual((await queueOf(id)).exists, false);
});

test('a vanished or ineligible device is dropped terminally, not retried', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['gone', 'd1'] });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [ok]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'partial', 'the missing target counts as permanently unsuccessful');
  assert.deepStrictEqual(messaging.calls[0].tokens, ['tok-d1']);
  assert.strictEqual((await nudgeOf(id)).sentDeviceCount, 1);
});

test('a payload-class FCM failure is terminal engineering failure, never a blind retry', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'] });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [fcmError('invalid-payload')]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'failed');
  assert.strictEqual((await nudgeOf(id)).status, 'failed');
  assert.strictEqual((await queueOf(id)).exists, false);
});

// --- T-F25/T-F26: retry scheduling and the attempt budget ---

test('T-F25: a transient failure keeps the queue with a future nextAttemptAtMs (60s backoff)', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'] });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [fcmError('server-unavailable')]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'retry_scheduled');

  const queue = (await queueOf(id)).data();
  assert.strictEqual(queue.leaseId, null);
  assert.strictEqual(queue.leaseUntilMs, null);
  assert.strictEqual(queue.attemptCount, 1);
  assert.strictEqual(queue.nextAttemptAtMs, FIXED_NOW + 60_000);
  const nudge = await nudgeOf(id);
  assert.strictEqual(nudge.status, 'pending');
  assert.strictEqual(nudge.lastPushErrorCode, 'server-unavailable');
});

test('T-F25 (mid-budget): successes on a retry accumulate into sentInstallIds', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1', 'd2'], sentInstallIds: ['d1'], attemptCount: 1 });
  await seedDevice('d2');
  const messaging = scriptedMessaging(() => [fcmError('message-rate-exceeded')]);

  await engine(messaging)(PAIR, id);
  const queue = (await queueOf(id)).data();
  assert.deepStrictEqual(queue.sentInstallIds, ['d1']);
  assert.strictEqual(queue.attemptCount, 2);
  assert.strictEqual(queue.nextAttemptAtMs, FIXED_NOW + 120_000, 'second backoff step');
});

test('T-F26: the fourth failed attempt is terminal and deletes the queue (nudge_stuck)', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'], attemptCount: 3 });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [fcmError('internal-error')]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'failed');
  const nudge = await nudgeOf(id);
  assert.strictEqual(nudge.status, 'failed');
  assert.strictEqual(nudge.pushAttempts, 4);
  assert.strictEqual((await queueOf(id)).exists, false);
});

// --- T-F15/T-F16/T-F17/T-F18: the lease ---

test('T-F15/16: a live lease from another worker blocks the second dispatcher entirely', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'] });
  await seedDevice('d1');

  // First worker holds the lease mid-send (gate); second runs while it is held.
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const messaging = scriptedMessaging(async () => {
    await gate;
    return [ok];
  });
  const push = engine(messaging);
  const first = push(PAIR, id);
  for (let i = 0; i < 200; i += 1) {
    // Deterministic wait: the first worker has claimed once the lease is visible.
    const snap = await queueOf(id);
    if (snap.exists && snap.get('leaseId')) break;
    await new Promise((r) => setTimeout(r, 10));
  }
  assert.ok((await queueOf(id)).get('leaseId'), 'first worker must have claimed');
  const second = await push(PAIR, id);
  assert.strictEqual(second.outcome, 'not_owner');
  release();
  assert.strictEqual((await first).outcome, 'sent');
  assert.strictEqual(messaging.calls.length, 1, 'only the lease owner may send');
});

test('T-F17: an expired lease is reclaimed and the nudge completes', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'], leaseId: 'stale-worker', leaseUntilMs: FIXED_NOW - 1 });
  await seedDevice('d1');
  const messaging = scriptedMessaging(() => [ok]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'sent');
  assert.strictEqual(messaging.calls.length, 1);
});

test('T-F18: a worker whose lease was stolen mid-send writes nothing', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'] });
  await seedDevice('d1');
  // While "this worker" is inside the FCM call, another worker (post-expiry) re-claims.
  const messaging = scriptedMessaging(async () => {
    await db.doc(`nudgePushQueue/${id}`).update({ leaseId: 'usurper', leaseUntilMs: FIXED_NOW + 90_000 });
    return [ok];
  });

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'lease_lost');
  const nudge = await nudgeOf(id);
  assert.strictEqual(nudge.status, 'pending', 'stale finalization must not write the status');
  assert.strictEqual(nudge.sentDeviceCount, 0);
  const queue = (await queueOf(id)).data();
  assert.strictEqual(queue.leaseId, 'usurper', 'the usurper still owns the queue');
});

// --- T-F11/T-F12: suppression at dispatch ---

test('T-F11: block enabled after acceptance suppresses without any FCM send', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'] });
  await db.doc(`pairings/${PAIR}/nudgeSettings/${BOB}`).set({ blocked: true, updatedAt: new Date() });
  const messaging = scriptedMessaging(() => [ok]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'suppressed');
  assert.strictEqual(messaging.calls.length, 0);
  const nudge = await nudgeOf(id);
  assert.strictEqual(nudge.status, 'suppressed');
  assert.ok(nudge.suppressedAt);
  assert.ok(nudge.terminalAt);
  assert.strictEqual((await queueOf(id)).exists, false);
});

test('T-F12: a revoked pair suppresses at dispatch', async () => {
  await seedPair('terminated');
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'] });
  const messaging = scriptedMessaging(() => [ok]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'suppressed');
  assert.strictEqual(messaging.calls.length, 0);
  assert.strictEqual((await nudgeOf(id)).status, 'suppressed');
});

test('an unblocked nudge sends normally (settings absent vs blocked:false)', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id);
  await seedQueue(id, { targetInstallIds: ['d1'] });
  await seedDevice('d1');
  await db.doc(`pairings/${PAIR}/nudgeSettings/${BOB}`).set({ blocked: false, updatedAt: new Date() });
  const messaging = scriptedMessaging(() => [ok]);

  assert.strictEqual((await engine(messaging)(PAIR, id)).outcome, 'sent');
});

// --- queue hygiene ---

test('duplicate delivery after finalization is a no-op, never a resend', async () => {
  await seedPair();
  const id = nudgeId();
  await seedNudge(id, { status: 'sent' });
  await seedQueue(id, { targetInstallIds: ['d1'] }); // stale leftover
  const messaging = scriptedMessaging(() => [ok]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'terminal_cleaned');
  assert.strictEqual(messaging.calls.length, 0);
  assert.strictEqual((await queueOf(id)).exists, false);
});

test('a queue document whose nudge vanished is deleted as an orphan', async () => {
  await seedPair();
  const id = nudgeId();
  await seedQueue(id);
  const messaging = scriptedMessaging(() => [ok]);

  const result = await engine(messaging)(PAIR, id);
  assert.strictEqual(result.outcome, 'orphan_deleted');
  assert.strictEqual((await queueOf(id)).exists, false);
});
