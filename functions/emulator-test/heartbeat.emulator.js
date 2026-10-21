'use strict';
// `submitHeartbeat` transactional suite — the real handler against the Firestore emulator
// (plan §7 Step 7 / §8.10 / §9). Named without `.test.js` on purpose: plain `npm test` stays
// emulator-free, so this file runs only via `npm run test:heartbeat`.
//
// `firebase emulators:exec` exports FIRESTORE_EMULATOR_HOST for this process, so a second admin
// app bound to it talks to the emulator. `createSubmitHeartbeatHandler` is the same handler
// `submitHeartbeat` deploys; `now` is injected so the rate floor and deadline rebase are exact.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { HttpsError } = require('firebase-functions/v2/https');

const { createSubmitHeartbeatHandler } = require('../heartbeat');
const { DMS_PARTNER_PUSH_MS, MIN_ACCEPT_INTERVAL_MS } = require('../heartbeat-logic');

const PROJECT_ID = 'demo-myexodus-heartbeat';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';
const CAROL = 'carol-uid';
const INSTALL = 'fid-1234567890';
const BOB_INSTALL = 'fid-bob-1234';

const NOW = Date.UTC(2026, 9, 21, 12, 0, 0);

let app;
let db;
let clockMs;
let handler;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'heartbeat-emulator-suite');
  db = getFirestore(app);
  clockMs = NOW;
  handler = createSubmitHeartbeatHandler({ db, now: () => clockMs });
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  clockMs = NOW;
  await db.recursiveDelete(db.collection('users'));
  await db.recursiveDelete(db.collection('dms_install_state'));
});

const register = (uid = ALICE, installId = INSTALL) =>
  db.doc(`users/${uid}/push_installations/${installId}`).set({
    installId,
    platform: 'android',
    registeredAt: new Date(),
  });

const stateRef = (uid = ALICE, installId = INSTALL) => db.doc(`dms_install_state/${uid}_${installId}`);

const call = (data = { installId: INSTALL, counter: 1 }, uid = ALICE) =>
  handler({ auth: uid ? { uid } : undefined, data });

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

test('an unauthenticated caller is refused before anything is read', async () => {
  await expectRejection(call({ installId: INSTALL, counter: 1 }, null), 'unauthenticated');
  await expectRejection(call(undefined, null), 'unauthenticated');
});

test('malformed payloads are invalid-argument, not server errors', async () => {
  for (const data of [
    null,
    {},
    { installId: '', counter: 1 },
    { installId: 'short', counter: 1 },
    { installId: 'has space', counter: 1 },
    { installId: 'slash/inside', counter: 1 },
    { installId: 'x'.repeat(129), counter: 1 },
    { installId: INSTALL, counter: 0 },
    { installId: INSTALL, counter: -1 },
    { installId: INSTALL, counter: 1.5 },
    { installId: INSTALL, counter: '2' },
    { installId: INSTALL, counter: null },
  ]) {
    await expectRejection(call(data), 'invalid-argument', 'INVALID_REQUEST');
  }
});

test('an installation the caller never registered is refused and leaves no state', async () => {
  await expectRejection(call(), 'permission-denied', 'INSTALL_NOT_REGISTERED');
  const row = await stateRef().get();
  assert.strictEqual(row.exists, false);
});

test('another user’s registered installation cannot be claimed with auth alone', async () => {
  await register(BOB, BOB_INSTALL);
  await expectRejection(
    call({ installId: BOB_INSTALL, counter: 1 }, ALICE),
    'permission-denied',
    'INSTALL_NOT_REGISTERED',
  );
  assert.strictEqual((await stateRef(ALICE, BOB_INSTALL).get()).exists, false);
  assert.strictEqual((await stateRef(BOB, BOB_INSTALL).get()).exists, false);
});

test('the first beat writes the full §8.10 row and a six-hour deadline', async () => {
  await register();
  const reply = await call({ installId: INSTALL, counter: 1 });

  assert.deepStrictEqual(reply, { accepted: true, serverCounter: 1, generation: 1 });

  const row = (await stateRef().get()).data();
  assert.strictEqual(row.uid, ALICE);
  assert.strictEqual(row.install_id, INSTALL);
  assert.strictEqual(row.last_counter, 1);
  assert.strictEqual(row.liveness_generation, 1);
  assert.strictEqual(row.last_received_at_ms, NOW);
  assert.strictEqual(row.last_received_at.toMillis(), NOW);
  assert.strictEqual(row.next_deadline_at.toMillis(), NOW + DMS_PARTNER_PUSH_MS);
  assert.strictEqual(row.last_notified_generation, 0);
  assert.strictEqual(row.updated_at.toMillis(), NOW);

  // The registration row is read, never rewritten by a heartbeat.
  assert.strictEqual((await db.doc(`users/${ALICE}/push_installations/${INSTALL}`).get()).exists, true);
});

test('a newer counter at the floor advances the generation and rebases the deadline', async () => {
  await register();
  await call({ installId: INSTALL, counter: 4 });
  clockMs += MIN_ACCEPT_INTERVAL_MS;

  const reply = await call({ installId: INSTALL, counter: 5 });
  assert.deepStrictEqual(reply, { accepted: true, serverCounter: 5, generation: 2 });

  const row = (await stateRef().get()).data();
  assert.strictEqual(row.last_received_at_ms, clockMs);
  assert.strictEqual(row.next_deadline_at.toMillis(), clockMs + DMS_PARTNER_PUSH_MS);
});

test('a replayed or lost counter is refused with the server counter and changes nothing', async () => {
  await register();
  await call({ installId: INSTALL, counter: 7 });
  clockMs += 60_000;
  const before = (await stateRef().get()).data();

  for (const replay of [7, 6, 1]) {
    const reply = await call({ installId: INSTALL, counter: replay });
    assert.deepStrictEqual(reply, { accepted: false, serverCounter: 7, generation: 1 });
  }

  const after = (await stateRef().get()).data();
  assert.strictEqual(after.last_counter, before.last_counter);
  assert.strictEqual(after.liveness_generation, before.liveness_generation);
  assert.strictEqual(after.last_received_at_ms, before.last_received_at_ms);
  assert.strictEqual(after.next_deadline_at.toMillis(), before.next_deadline_at.toMillis());
});

test('a beat inside the 30 s floor is refused without touching liveness', async () => {
  await register();
  await call({ installId: INSTALL, counter: 2 });
  clockMs += MIN_ACCEPT_INTERVAL_MS - 1;
  const before = (await stateRef().get()).data();

  const reply = await call({ installId: INSTALL, counter: 3 });
  assert.deepStrictEqual(reply, { accepted: false, serverCounter: 2, generation: 1 });

  const after = (await stateRef().get()).data();
  assert.strictEqual(after.last_received_at_ms, before.last_received_at_ms);
  assert.strictEqual(after.next_deadline_at.toMillis(), before.next_deadline_at.toMillis());
});

test('an accepted beat never resets the deadline processor’s notification watermark', async () => {
  await register();
  await stateRef().set({
    uid: ALICE,
    install_id: INSTALL,
    last_counter: 1,
    liveness_generation: 1,
    last_received_at: new Date(NOW - 60_000),
    last_received_at_ms: NOW - 60_000,
    next_deadline_at: new Date(NOW + DMS_PARTNER_PUSH_MS - 60_000),
    last_notified_generation: 2,
    updated_at: new Date(NOW - 60_000),
  });

  await call({ installId: INSTALL, counter: 2 });
  const row = (await stateRef().get()).data();
  assert.strictEqual(row.last_counter, 2);
  assert.strictEqual(row.last_notified_generation, 2);
});

test('two identities never share a counter namespace', async () => {
  await register(ALICE);
  await register(BOB);

  assert.deepStrictEqual(await call({ installId: INSTALL, counter: 9 }, ALICE), {
    accepted: true, serverCounter: 9, generation: 1,
  });
  const bobFirst = await call({ installId: INSTALL, counter: 1 }, BOB);
  assert.deepStrictEqual(bobFirst, { accepted: true, serverCounter: 1, generation: 1 });

  assert.strictEqual((await stateRef(ALICE).get()).data().last_counter, 9);
  assert.strictEqual((await stateRef(BOB).get()).data().last_counter, 1);
});

test('a rotated identity starts a fresh counter instead of locking out (§9)', async () => {
  await register(ALICE);
  await call({ installId: INSTALL, counter: 12 });
  clockMs += 60_000;

  await register(CAROL);
  const reply = await call({ installId: INSTALL, counter: 1 }, CAROL);
  assert.deepStrictEqual(reply, { accepted: true, serverCounter: 1, generation: 1 });
  assert.strictEqual((await stateRef(ALICE).get()).data().last_counter, 12);
});

test('concurrent beats never lose an update and never stretch the generation', async () => {
  await register();
  await call({ installId: INSTALL, counter: 1 });

  // One beat per 31 s so the floor never masks the concurrency behavior.
  const replies = [];
  const pending = [];
  for (let counter = 2; counter <= 11; counter++) {
    clockMs = NOW + (counter - 1) * MIN_ACCEPT_INTERVAL_MS + counter;
    pending.push(call({ installId: INSTALL, counter }).then((r) => replies.push({ counter, r })));
  }
  await Promise.all(pending);
  clockMs = NOW + 60 * 60_000; // beyond every burst timestamp

  const accepted = replies.filter(({ r }) => r.accepted).map(({ counter }) => counter);
  assert.ok(accepted.length >= 1, 'at least one concurrent beat must commit');
  assert.ok(accepted.includes(11), 'the newest counter can never be starved by a retry');

  const row = (await stateRef().get()).data();
  assert.strictEqual(row.last_counter, Math.max(...accepted));
  assert.strictEqual(row.liveness_generation, 1 + accepted.length);

  // The server counter is the resync anchor: a refusal always names a counter at or above the
  // refused one, so the client can jump forward instead of guessing (§9).
  for (const { counter, r } of replies) {
    if (!r.accepted) assert.ok(r.serverCounter >= counter, `refusal for ${counter} named ${r.serverCounter}`);
  }

  // A following honest beat still lands, proving the row never wedged.
  const next = await call({ installId: INSTALL, counter: 12 });
  assert.strictEqual(next.accepted, true);
  assert.strictEqual(next.serverCounter, 12);
});
