'use strict';
// DMS deadline processor + durable notification suite — the real handlers against the Firestore
// emulator (plan §7 Step 8 / §8.10-§8.13). Named without `.test.js` on purpose: plain
// `npm test` stays emulator-free, so this file runs only via `npm run test:dms`.
//
// What this proves, mapped to the plan's Step 8 validation list:
//   stale delayed messages          — `_processDueRow` CAS on generation/receipt/NOTIFIED
//   concurrent workers              — two full passes race one due row → exactly one send
//   CAS duplicate transitions       — the same, at the state-row level
//   transaction crash               — transition commits with the outbox row; dispatch resumes
//   queue failure                   — an abandoned DISPATCHING claim is requeued by lease
//   scheduler outage                — no record means no rebase; receipts survive catch-up
//   ingestion outage                — open holds the pass; ended rebases to max(last, end)+6h
//   correlated-client outage        — the fleet breaker holds dispatch, release is bounded
//   controlled catch-up             — dispatch_batch bounds what one release can release
// The 100k-install load/soak half lives in `test/dms-soak.js` (`npm run test:dms-soak`).

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, Timestamp, FieldValue } = require('firebase-admin/firestore');
const { HttpsError } = require('firebase-functions/v2/https');

const {
  createSetAvailabilityConsentHandler,
  createReadPartnerStatusHandler,
  createRecordIngestionOutageHandler,
} = require('../dms');
const { createSubmitHeartbeatHandler } = require('../heartbeat');
const { DMS_PARTNER_PUSH_MS, MIN_ACCEPT_INTERVAL_MS } = require('../heartbeat-logic');
const {
  _createDmsWorkerHandler,
  _processDueRow,
  _processProbeRow,
  _buildDmsMessage,
  _buildLivenessProbeMessage,
} = require('../dms-processor');
const {
  NOTICE_AVAILABILITY,
  NOTICE_RECOVERY,
  NOTICE_PENDING,
  NOTICE_DISPATCHING,
  NOTICE_SENT,
  NOTICE_SUPPRESSED,
  NOTICE_FAILED_TERMINAL,
  noticeDocId,
} = require('../dms-logic');

const PROJECT_ID = 'demo-myexodus-dms';
const MON = 'monitored-uid';
const WATCH = 'watcher-uid';
const THIRD = 'third-uid';
const PAIR_ID = `${MON}_${WATCH}`;
const INSTALL = 'install-mon-1';
const WATCH_INSTALL = 'install-watch-1';

const NOW = Date.UTC(2026, 9, 21, 12, 0, 0);
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

const silentLog = { info() {}, warn() {}, error() {} };

let app;
let db;
let clockMs;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'dms-emulator-suite');
  db = getFirestore(app);
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  clockMs = NOW;
  for (const collection of [
    'users',
    'pairings',
    'dms_install_state',
    'dms_notification_outbox',
    'dms_ingestion_outages',
    'config',
  ]) {
    await db.recursiveDelete(db.collection(collection));
  }
});

// ---------------------------------------------------------------------------
// fixtures

const pairRef = () => db.doc(`pairings/${PAIR_ID}`);
const stateRef = (uid = MON, installId = INSTALL) => db.doc(`dms_install_state/${uid}_${installId}`);
const outboxRef = (installId, generation, type = NOTICE_AVAILABILITY) =>
  db.doc(`dms_notification_outbox/${noticeDocId(installId, PAIR_ID, generation, type)}`);
const configRef = () => db.doc('config/dms');

async function seedPairing({ status = 'active', consent = true, consentFor = MON } = {}) {
  const doc = {
    uidA: MON,
    uidB: WATCH,
    members: [MON, WATCH],
    status,
    pairingEpoch: 1,
    proofs: {},
    createdAt: Timestamp.fromMillis(NOW - 48 * HOUR),
  };
  if (consent) doc.availabilityConsent = { [consentFor]: { enabled: true, updatedAtMs: NOW } };
  await pairRef().set(doc);
}

async function register(uid, installId, extra = {}) {
  await db.doc(`users/${uid}/push_installations/${installId}`).set({
    provider: 'fcm',
    token: `token-${installId}`,
    platform: 'android',
    appVersion: '1.0',
    updatedAt: Timestamp.fromMillis(clockMs),
    ...extra,
  });
}

async function seedState({
  uid = MON,
  installId = INSTALL,
  lastReceivedAtMs = NOW - 7 * HOUR,
  generation = 1,
  extra = {},
} = {}) {
  await stateRef(uid, installId).set({
    uid,
    install_id: installId,
    last_counter: 1,
    liveness_generation: generation,
    last_received_at: Timestamp.fromMillis(lastReceivedAtMs),
    last_received_at_ms: lastReceivedAtMs,
    next_deadline_at: Timestamp.fromMillis(lastReceivedAtMs + DMS_PARTNER_PUSH_MS),
    last_notified_generation: 0,
    outage_generation: 0,
    notice_state: 'NONE',
    updated_at: Timestamp.fromMillis(lastReceivedAtMs),
    ...extra,
  });
}

function pushRecorder({ failTimes = 0, invalidInstallations = [] } = {}) {
  const sends = [];
  const sendPush = async (args) => {
    sends.push(args);
    if (sends.length <= failTimes) {
      throw Object.assign(new Error('provider down'), { code: 'unavailable' });
    }
    return { providerAccepted: args.installs.length, invalidInstallations };
  };
  return { sends, sendPush };
}

function probeRecorder({ failTimes = 0, code = 'unavailable' } = {}) {
  const sends = [];
  const sendProbe = async (args) => {
    sends.push(args);
    if (sends.length <= failTimes) {
      throw Object.assign(new Error('provider down'), { code });
    }
  };
  return { sends, sendProbe };
}

const workerWith = (sendPush, sendProbe = async () => {}) => _createDmsWorkerHandler({
  db,
  now: () => clockMs,
  sendPush,
  sendProbe,
  log: silentLog,
});

const consentHandler = () => createSetAvailabilityConsentHandler({ db, now: () => clockMs });
const statusHandler = () => createReadPartnerStatusHandler({ db, now: () => clockMs });
const outageHandler = () => createRecordIngestionOutageHandler({ db, now: () => clockMs });
const heartbeatHandler = () => createSubmitHeartbeatHandler({ db, now: () => clockMs });

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

// ---------------------------------------------------------------------------
// setAvailabilityConsent

test('consent: only the caller’s scope is written and a repeat is a no-op', async () => {
  await seedPairing({ consent: false });
  await pairRef().update({ [`availabilityConsent.${WATCH}`]: { enabled: false, updatedAtMs: 1 } });
  const handler = consentHandler();

  const first = await handler({ auth: { uid: MON }, data: { pairId: PAIR_ID, enabled: true } });
  assert.deepStrictEqual(first, { pairId: PAIR_ID, enabled: true, changed: true });

  const doc = (await pairRef().get()).data();
  assert.deepStrictEqual(doc.availabilityConsent[MON], { enabled: true, updatedAtMs: NOW });
  // The shallow-merge trap: the partner's entry must survive the monitored member's write.
  assert.deepStrictEqual(doc.availabilityConsent[WATCH], { enabled: false, updatedAtMs: 1 });

  clockMs += 5 * MINUTE;
  const again = await handler({ auth: { uid: MON }, data: { pairId: PAIR_ID, enabled: true } });
  assert.deepStrictEqual(again, { pairId: PAIR_ID, enabled: true, changed: false });
  assert.strictEqual((await pairRef().get()).data().availabilityConsent[MON].updatedAtMs, NOW);
});

test('consent: non-members, closed relationships, bad payloads and anonymous callers are refused', async () => {
  await seedPairing({ consent: false });
  const handler = consentHandler();
  await expectRejection(
    handler({ auth: { uid: THIRD }, data: { pairId: PAIR_ID, enabled: true } }),
    'permission-denied',
    'RELATIONSHIP_UNAVAILABLE',
  );
  await expectRejection(
    handler({ auth: { uid: `nobody_${PAIR_ID}` }, data: { pairId: PAIR_ID, enabled: true } }),
    'permission-denied',
    'RELATIONSHIP_UNAVAILABLE',
  );
  await expectRejection(
    handler({ auth: { uid: MON }, data: { pairId: 'missing_pair', enabled: true } }),
    'permission-denied',
    'RELATIONSHIP_UNAVAILABLE',
  );
  await expectRejection(handler({ data: { pairId: PAIR_ID, enabled: true } }), 'unauthenticated');
  await expectRejection(
    handler({ auth: { uid: MON }, data: { pairId: PAIR_ID, enabled: 'yes' } }),
    'invalid-argument',
    'INVALID_REQUEST',
  );
  await expectRejection(
    handler({ auth: { uid: MON }, data: { pairId: PAIR_ID } }),
    'invalid-argument',
    'INVALID_REQUEST',
  );

  await pairRef().update({ status: 'terminated' });
  await expectRejection(
    handler({ auth: { uid: MON }, data: { pairId: PAIR_ID, enabled: false } }),
    'permission-denied',
    'RELATIONSHIP_UNAVAILABLE',
  );
  assert.strictEqual((await pairRef().get()).data().availabilityConsent, undefined);
});

// ---------------------------------------------------------------------------
// readPartnerStatus

test('readPartnerStatus: consent is fail-closed and the response carries coarse facts only', async () => {
  await seedPairing({ consent: false });
  const handler = statusHandler();

  const notConsented = await handler({ auth: { uid: WATCH }, data: { pairId: PAIR_ID } });
  assert.deepStrictEqual(notConsented, {
    pairId: PAIR_ID,
    consent: false,
    connection: 'NO_DATA',
    bucket: 'NO_DATA',
  });
  assert.deepStrictEqual(Object.keys(notConsented).sort(), ['bucket', 'connection', 'consent', 'pairId']);

  await seedPairing({ consent: true });
  const noState = await handler({ auth: { uid: WATCH }, data: { pairId: PAIR_ID } });
  assert.deepStrictEqual(noState, {
    pairId: PAIR_ID,
    consent: true,
    connection: 'NO_DATA',
    bucket: 'NO_DATA',
  });
});

test('readPartnerStatus: the 45/90-minute display states and coarse buckets derive at read time', async () => {
  await seedPairing();
  const handler = statusHandler();
  const at = async (lastReceivedAtMs) => {
    await seedState({ lastReceivedAtMs });
    const reply = await handler({ auth: { uid: WATCH }, data: { pairId: PAIR_ID } });
    return `${reply.connection}/${reply.bucket}`;
  };

  assert.strictEqual(await at(NOW - 44 * MINUTE), 'CHECKING_IN/WITHIN_1H');
  assert.strictEqual(await at(NOW - 45 * MINUTE), 'LATE/WITHIN_1H');
  assert.strictEqual(await at(NOW - 89 * MINUTE), 'LATE/HOURS_1_TO_6');
  assert.strictEqual(await at(NOW - 90 * MINUTE), 'NOT_CHECKING_IN/HOURS_1_TO_6');
  assert.strictEqual(await at(NOW - 6 * HOUR), 'NOT_CHECKING_IN/OVER_6H');
});

test('readPartnerStatus: the freshest installation of the monitored user wins', async () => {
  await seedPairing();
  await seedState({ installId: 'install-mon-a', lastReceivedAtMs: NOW - 7 * HOUR });
  await seedState({ installId: 'install-mon-b', lastReceivedAtMs: NOW - 5 * MINUTE });

  const reply = await statusHandler()({ auth: { uid: WATCH }, data: { pairId: PAIR_ID } });
  assert.strictEqual(reply.connection, 'CHECKING_IN');
  assert.strictEqual(reply.bucket, 'WITHIN_1H');
});

test('readPartnerStatus: an overlapping ingestion outage rebases; a bare gap does not', async () => {
  await seedPairing();
  const handler = statusHandler();

  // No outage record: seven hours of silence is seven hours of silence.
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  assert.strictEqual(
    (await handler({ auth: { uid: WATCH }, data: { pairId: PAIR_ID } })).connection,
    'NOT_CHECKING_IN',
  );

  // An operator-recorded ingestion outage covering the silence: the device was not late,
  // the server was not listening. Effective baseline = outage end.
  await db.collection('dms_ingestion_outages').doc('outage-1').set({
    service: 'submitHeartbeat',
    region: 'us-central1',
    started_at_ms: NOW - 6 * HOUR - 30 * MINUTE,
    ended_at_ms: NOW - 10 * MINUTE,
    created_at_ms: NOW - 6 * HOUR,
    closed_at_ms: NOW - 5 * MINUTE,
  });
  const rebased = await handler({ auth: { uid: WATCH }, data: { pairId: PAIR_ID } });
  assert.strictEqual(rebased.connection, 'CHECKING_IN');
  assert.strictEqual(rebased.bucket, 'WITHIN_1H');
});

test('readPartnerStatus: only a member of an active relationship may read', async () => {
  await seedPairing();
  await seedState();
  const handler = statusHandler();
  await expectRejection(
    handler({ auth: { uid: THIRD }, data: { pairId: PAIR_ID } }),
    'permission-denied',
    'RELATIONSHIP_UNAVAILABLE',
  );
  await pairRef().update({ status: 'terminated' });
  await expectRejection(
    handler({ auth: { uid: WATCH }, data: { pairId: PAIR_ID } }),
    'permission-denied',
    'RELATIONSHIP_UNAVAILABLE',
  );
  await expectRejection(handler({ data: { pairId: PAIR_ID } }), 'unauthenticated');
});

// ---------------------------------------------------------------------------
// recordIngestionOutage

test('recordIngestionOutage: operator-gated, one open record, idempotent close', async () => {
  await configRef().set({ operator_uids: [MON] });
  const handler = outageHandler();

  await expectRejection(
    handler({ auth: { uid: WATCH }, data: { startedAtMs: NOW - HOUR } }),
    'permission-denied',
    'NOT_OPERATOR',
  );

  const started = await handler({ auth: { uid: MON }, data: { service: 'submitHeartbeat' } });
  assert.strictEqual(started.ongoing, true);
  assert.strictEqual(started.startedAtMs, NOW);

  await expectRejection(
    handler({ auth: { uid: MON }, data: { startedAtMs: NOW } }),
    'failed-precondition',
    'OUTAGE_ALREADY_OPEN',
  );

  clockMs += 10 * MINUTE;
  const closed = await handler({ auth: { uid: MON }, data: { outageId: started.outageId } });
  assert.strictEqual(closed.endedAtMs, NOW + 10 * MINUTE);
  assert.strictEqual(closed.changed, true);

  clockMs += 10 * MINUTE;
  const again = await handler({ auth: { uid: MON }, data: { outageId: started.outageId } });
  assert.deepStrictEqual(again, { outageId: started.outageId, endedAtMs: NOW + 10 * MINUTE, changed: false });

  const row = (await db.doc(`dms_ingestion_outages/${started.outageId}`).get()).data();
  assert.strictEqual(row.started_at_ms, NOW);
  assert.strictEqual(row.ended_at_ms, NOW + 10 * MINUTE);

  await expectRejection(
    handler({ auth: { uid: MON }, data: { startedAtMs: NOW + HOUR, endedAtMs: NOW } }),
    'invalid-argument',
    'INVALID_INTERVAL',
  );
  await expectRejection(
    handler({ auth: { uid: MON }, data: { outageId: 'missing' } }),
    'not-found',
    'OUTAGE_NOT_FOUND',
  );
});

// ---------------------------------------------------------------------------
// worker: transition + outbox

test('worker: a row short of six hours is untouched', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({ lastReceivedAtMs: NOW - 6 * HOUR + MINUTE });
  const recorder = pushRecorder();

  const result = await workerWith(recorder.sendPush)();
  assert.strictEqual(result.due, 0);
  assert.strictEqual(result.notified, 0);
  assert.strictEqual(recorder.sends.length, 0);
  const state = (await stateRef().get()).data();
  assert.strictEqual(state.notice_state, 'NONE');
  assert.strictEqual((await db.collection('dms_notification_outbox').get()).size, 0);
});

test('worker: the six-hour deadline commits the transition and its outbox row, then sends', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({ lastReceivedAtMs: NOW - 6 * HOUR - MINUTE });
  const recorder = pushRecorder();

  const result = await workerWith(recorder.sendPush)();
  assert.strictEqual(result.due, 1);
  assert.strictEqual(result.notified, 1);
  assert.strictEqual(result.outboxCreated, 1);
  assert.strictEqual(result.sent, 1);

  const state = (await stateRef().get()).data();
  assert.strictEqual(state.notice_state, 'NOTIFIED');
  assert.strictEqual(state.outage_generation, 1);
  assert.strictEqual(state.last_notified_generation, 1);
  assert.strictEqual(state.notified_at_ms, NOW);
  assert.strictEqual(state.next_deadline_at, undefined, 'a notified row leaves the due index');

  const row = (await outboxRef(INSTALL, 1).get()).data();
  assert.strictEqual(row.status, NOTICE_SENT);
  assert.strictEqual(row.expected_notified_generation, 1);
  assert.strictEqual(row.recipient_uid, WATCH);
  assert.strictEqual(row.monitored_uid, MON);
  assert.strictEqual(row.relationship_id, PAIR_ID);
  assert.strictEqual(row.notice_type, NOTICE_AVAILABILITY);
  assert.strictEqual(row.provider_accepted, 1);

  assert.strictEqual(recorder.sends.length, 1);
  assert.strictEqual(recorder.sends[0].row.notice_type, NOTICE_AVAILABILITY);
  assert.deepStrictEqual(recorder.sends[0].installs.map((i) => i.id), [WATCH_INSTALL]);

  // A second pass neither re-notifies nor re-sends.
  const second = await workerWith(recorder.sendPush)();
  assert.strictEqual(second.due, 0);
  assert.strictEqual(recorder.sends.length, 1);
});

test('worker: stale delayed jobs are CAS no-ops (generation, receipt, already notified)', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  const dueSnapshot = await stateRef().get();
  const asDueDoc = { ref: stateRef(), data: () => dueSnapshot.data() };

  // A heartbeat landed between the due query and the transaction.
  await stateRef().update({
    liveness_generation: 2,
    last_received_at_ms: NOW - 5 * MINUTE,
    last_received_at: Timestamp.fromMillis(NOW - 5 * MINUTE),
    next_deadline_at: Timestamp.fromMillis(NOW - 5 * MINUTE + DMS_PARTNER_PUSH_MS),
  });
  assert.deepStrictEqual(
    await _processDueRow(db, asDueDoc, NOW),
    { status: 'stale', reason: 'GENERATION_MOVED' },
  );

  // Receipt moved without a generation bump (defensive: writers always bump both).
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR, generation: 2 });
  const receiptSnapshot = await stateRef().get();
  await stateRef().update({ last_received_at_ms: NOW - 10 * MINUTE });
  assert.deepStrictEqual(
    await _processDueRow(db, { ref: stateRef(), data: () => receiptSnapshot.data() }, NOW),
    { status: 'stale', reason: 'RECEIPT_MOVED' },
  );

  // The transition already committed before a duplicate job arrived.
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR, generation: 3 });
  const notifiedSnapshot = await stateRef().get();
  await stateRef().update({ notice_state: 'NOTIFIED' });
  assert.deepStrictEqual(
    await _processDueRow(db, { ref: stateRef(), data: () => notifiedSnapshot.data() }, NOW),
    { status: 'stale', reason: 'ALREADY_NOTIFIED' },
  );

  assert.strictEqual((await db.collection('dms_notification_outbox').get()).size, 0);
});

test('worker: two concurrent passes produce exactly one notification and one send', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  const recorder = pushRecorder();

  const [a, b] = await Promise.all([
    workerWith(recorder.sendPush)(),
    workerWith(recorder.sendPush)(),
  ]);

  assert.strictEqual(a.notified + b.notified, 1, 'exactly one pass may transition');
  assert.strictEqual(recorder.sends.length, 1, 'exactly one provider call');
  assert.strictEqual((await db.collection('dms_notification_outbox').get()).size, 1);
  assert.strictEqual((await stateRef().get()).data().notice_state, 'NOTIFIED');
});

test('worker: batch bounds cap a pass and the rest catch up on later passes', async () => {
  await seedPairing();
  await configRef().set({ due_batch: 1 });
  await register(WATCH, WATCH_INSTALL);
  const installs = ['install-mon-a', 'install-mon-b', 'install-mon-c'];
  for (const installId of installs) {
    await seedState({ installId, lastReceivedAtMs: NOW - 7 * HOUR });
  }
  const recorder = pushRecorder();
  const worker = workerWith(recorder.sendPush);

  const passes = [];
  for (let i = 0; i < 3; i++) passes.push(await worker());
  assert.deepStrictEqual(passes.map((p) => p.notified), [1, 1, 1]);
  assert.strictEqual(recorder.sends.length, 3);
  for (const installId of installs) {
    const row = (await outboxRef(installId, 1).get()).data();
    assert.strictEqual(row.status, NOTICE_SENT);
  }
});

// ---------------------------------------------------------------------------
// worker: dispatch-time revalidation

test('dispatch: consent revoked between transition and dispatch suppresses the notice', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  await configRef().set({ dispatch_hold: true });
  const recorder = pushRecorder();

  const held = await workerWith(recorder.sendPush)();
  assert.strictEqual(held.outboxCreated, 1);
  assert.strictEqual(held.dispatchHeld, true);
  assert.strictEqual((await outboxRef(INSTALL, 1).get()).data().status, NOTICE_PENDING);

  await consentHandler()({ auth: { uid: MON }, data: { pairId: PAIR_ID, enabled: false } });
  await configRef().set({ dispatch_hold: false }, { merge: true });

  const released = await workerWith(recorder.sendPush)();
  assert.strictEqual(released.suppressed, 1);
  const row = (await outboxRef(INSTALL, 1).get()).data();
  assert.strictEqual(row.status, NOTICE_SUPPRESSED);
  assert.strictEqual(row.suppressed_reason, 'CONSENT_REVOKED');
  assert.strictEqual(recorder.sends.length, 0);
});

test('dispatch: a relationship closed after the transition suppresses the notice', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  await configRef().set({ dispatch_hold: true });
  const recorder = pushRecorder();
  await workerWith(recorder.sendPush)();

  await pairRef().update({ status: 'terminated' });
  await configRef().set({ dispatch_hold: false }, { merge: true });
  const released = await workerWith(recorder.sendPush)();
  assert.strictEqual(released.suppressed, 1);
  assert.strictEqual((await outboxRef(INSTALL, 1).get()).data().suppressed_reason, 'RELATIONSHIP_CLOSED');
  assert.strictEqual(recorder.sends.length, 0);
});

test('dispatch: no eligible installation suppresses instead of retrying forever', async () => {
  await seedPairing();
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  await configRef().set({ dispatch_hold: true });
  const recorder = pushRecorder();
  await workerWith(recorder.sendPush)();

  // An installation the provider path must ignore: opted out of push, and a stale one.
  await register(WATCH, WATCH_INSTALL, { pushEligible: false });
  await register(WATCH, 'install-watch-old', {
    updatedAt: Timestamp.fromMillis(NOW - 100 * 24 * HOUR),
  });
  await configRef().set({ dispatch_hold: false }, { merge: true });

  const released = await workerWith(recorder.sendPush)();
  assert.strictEqual(released.suppressed, 1);
  assert.strictEqual((await outboxRef(INSTALL, 1).get()).data().suppressed_reason, 'NO_INSTALLATION');
  assert.strictEqual(recorder.sends.length, 0);
});

test('dispatch: provider failures back off and terminate inside the attempt bound', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  await configRef().set({ dispatch_hold: true });
  const recorder = pushRecorder({ failTimes: 6 });
  await workerWith(recorder.sendPush)();
  await configRef().set({ dispatch_hold: false }, { merge: true });

  const worker = workerWith(recorder.sendPush);
  await worker();
  let row = (await outboxRef(INSTALL, 1).get()).data();
  assert.strictEqual(row.status, NOTICE_PENDING);
  assert.strictEqual(row.attempts, 1);
  assert.strictEqual(row.next_attempt_at_ms, NOW + MINUTE);
  assert.strictEqual(row.last_error_code, 'unavailable');

  // Not due yet: a pass before the backoff boundary must not call the provider.
  await worker();
  assert.strictEqual(recorder.sends.length, 1);

  for (let attempt = 2; attempt <= 6; attempt++) {
    clockMs = (await outboxRef(INSTALL, 1).get()).data().next_attempt_at_ms;
    await worker();
  }

  row = (await outboxRef(INSTALL, 1).get()).data();
  assert.strictEqual(row.status, NOTICE_FAILED_TERMINAL);
  assert.strictEqual(row.attempts, 6);
  assert.strictEqual(recorder.sends.length, 6);

  clockMs += 24 * HOUR;
  await worker();
  assert.strictEqual(recorder.sends.length, 6, 'a terminal row is never retried');
});

test('dispatch: an abandoned DISPATCHING claim is requeued after its lease and retried', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({
    lastReceivedAtMs: NOW - 7 * HOUR,
    extra: { notice_state: 'NOTIFIED', outage_generation: 1, last_notified_generation: 1 },
  });
  await outboxRef(INSTALL, 1).set({
    install_id: INSTALL,
    state_doc_id: stateRef().id,
    monitored_uid: MON,
    relationship_id: PAIR_ID,
    recipient_uid: WATCH,
    outage_generation: 1,
    notice_type: NOTICE_AVAILABILITY,
    expected_notified_generation: 1,
    status: NOTICE_DISPATCHING,
    attempts: 1,
    lease_until_ms: NOW - 1,
    dispatch_started_at_ms: NOW - 10 * MINUTE,
    next_attempt_at_ms: NOW - 10 * MINUTE,
    created_at_ms: NOW - 10 * MINUTE,
  });
  const recorder = pushRecorder();

  const result = await workerWith(recorder.sendPush)();
  assert.strictEqual(result.requeued, 1);
  assert.strictEqual(result.sent, 1);
  assert.strictEqual((await outboxRef(INSTALL, 1).get()).data().status, NOTICE_SENT);
});

test('dispatch: invalid provider tokens remove the token and tombstone the install, not the monitor', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  const recorder = pushRecorder({ invalidInstallations: [WATCH_INSTALL] });

  await workerWith(recorder.sendPush)();
  const install = (await db.doc(`users/${WATCH}/push_installations/${WATCH_INSTALL}`).get()).data();
  assert.strictEqual(install.stale, true);
  assert.ok(install.staleAt instanceof Timestamp);
  // §7 Step 9: the provider-invalid token is REMOVED, not retained. The tombstone is not
  // evidence of uninstall — a fresh registration rewrites token + clears stale.
  assert.strictEqual(install.token, undefined, 'invalid token must be removed');
  assert.strictEqual((await outboxRef(INSTALL, 1).get()).data().status, NOTICE_SENT);
  // The monitored user's own rows are untouched.
  assert.strictEqual((await stateRef().get()).data().notice_state, 'NOTIFIED');
});

// ---------------------------------------------------------------------------
// Silent liveness probe (§4 / §8.9)

test('liveness probe: one silent wake to the monitored install per liveness generation', async () => {
  await register(MON, INSTALL);
  const reply = await heartbeatHandler()({ auth: { uid: MON }, data: { installId: INSTALL, counter: 1 } });
  assert.strictEqual(reply.accepted, true);
  assert.strictEqual(
    (await stateRef().get()).data().late_deadline_at.toMillis(),
    NOW + 45 * MINUTE,
    'the probe window starts at the server receipt time',
  );

  const probes = probeRecorder();
  const worker = workerWith(pushRecorder().sendPush, probes.sendProbe);

  clockMs = NOW + 45 * MINUTE - 1;
  assert.strictEqual((await worker()).lateReminded, 0);

  clockMs = NOW + 45 * MINUTE;
  const first = await worker();
  assert.strictEqual(first.lateReminded, 1);
  assert.strictEqual(probes.sends.length, 1);
  assert.strictEqual(probes.sends[0].installation.id, INSTALL);
  assert.strictEqual(probes.sends[0].installation.token, `token-${INSTALL}`);
  const state = (await stateRef().get()).data();
  assert.strictEqual(state.late_reminder_generation, 1);
  assert.strictEqual(state.late_deadline_at, undefined);

  // Same generation, hours later: never a second nudge.
  clockMs += HOUR;
  assert.strictEqual((await worker()).lateReminded, 0);
  assert.strictEqual(probes.sends.length, 1);

  // A fresh accepted beat opens a new generation and a new 45-minute window.
  clockMs += MIN_ACCEPT_INTERVAL_MS + 1;
  const beatAt = clockMs;
  const secondBeat = await heartbeatHandler()({ auth: { uid: MON }, data: { installId: INSTALL, counter: 2 } });
  assert.strictEqual(secondBeat.accepted, true);
  assert.strictEqual((await stateRef().get()).data().late_reminder_generation, 1, 'carried, not reset');
  clockMs = beatAt + 45 * MINUTE;
  assert.strictEqual((await worker()).lateReminded, 1);
  assert.strictEqual(probes.sends.length, 2);
  assert.strictEqual((await stateRef().get()).data().late_reminder_generation, 2);
});

test('liveness probe: a notifications-off install is still probed (data-only wake)', async () => {
  // `pushEligible` describes notification capability; the probe posts nothing, so it must
  // not inherit that gate (the partner six-hour notice still honours it).
  await register(MON, INSTALL, { pushEligible: false });
  await heartbeatHandler()({ auth: { uid: MON }, data: { installId: INSTALL, counter: 1 } });
  const probes = probeRecorder();
  const worker = workerWith(pushRecorder().sendPush, probes.sendProbe);

  clockMs = NOW + 45 * MINUTE;
  assert.strictEqual((await worker()).lateReminded, 1);
  assert.strictEqual(probes.sends.length, 1);
  assert.strictEqual(probes.sends[0].installation.pushEligible, false);
});

test('liveness probe: stale claims are CAS no-ops (generation moved, already probed)', async () => {
  await seedState({
    lastReceivedAtMs: NOW - 60 * MINUTE,
    extra: { late_deadline_at: Timestamp.fromMillis(NOW - 15 * MINUTE) },
  });
  const dueSnapshot = await stateRef().get();
  await stateRef().update({ liveness_generation: 2, last_received_at_ms: NOW - 5 * MINUTE });
  assert.deepStrictEqual(
    await _processProbeRow(db, { ref: stateRef(), data: () => dueSnapshot.data() }, NOW, async () => {}),
    { status: 'stale', reason: 'GENERATION_MOVED' },
  );

  await seedState({
    lastReceivedAtMs: NOW - 60 * MINUTE,
    generation: 2,
    extra: {
      late_deadline_at: Timestamp.fromMillis(NOW - 15 * MINUTE),
      late_reminder_generation: 2,
    },
  });
  const remindedSnapshot = await stateRef().get();
  assert.deepStrictEqual(
    await _processProbeRow(db, { ref: stateRef(), data: () => remindedSnapshot.data() }, NOW, async () => {}),
    { status: 'stale', reason: 'ALREADY_PROBED' },
  );
});

test('liveness probe: an outage inside the window rebases; an outage after it does not', async () => {
  await register(MON, INSTALL);
  await seedState({
    lastReceivedAtMs: NOW - 60 * MINUTE,
    extra: { late_deadline_at: Timestamp.fromMillis(NOW - 15 * MINUTE) },
  });
  await db.doc('dms_ingestion_outages/out-in-window').set({
    started_at_ms: NOW - 50 * MINUTE,
    ended_at_ms: NOW - 10 * MINUTE,
  });
  const probes = probeRecorder();
  const worker = workerWith(pushRecorder().sendPush, probes.sendProbe);

  const rebased = await worker();
  assert.strictEqual(rebased.lateReminded, 0);
  assert.strictEqual(rebased.lateDeferred, 1);
  assert.strictEqual(
    (await stateRef().get()).data().late_deadline_at.toMillis(),
    NOW - 10 * MINUTE + 45 * MINUTE,
    'base = max(last_received, outage_end), then + 45m',
  );

  clockMs = NOW + 35 * MINUTE;
  assert.strictEqual((await worker()).lateReminded, 1);
  assert.strictEqual(probes.sends.length, 1);

  // An outage that began AFTER the LATE deadline cannot have hidden that transition.
  await seedState({
    uid: MON,
    installId: 'install-mon-2',
    lastReceivedAtMs: NOW - 7 * HOUR,
    extra: { late_deadline_at: Timestamp.fromMillis(NOW - 6 * HOUR - 15 * MINUTE) },
  });
  await register(MON, 'install-mon-2');
  await db.doc('dms_ingestion_outages/out-after-window').set({
    started_at_ms: NOW - 3 * HOUR,
    ended_at_ms: NOW - 1 * HOUR,
  });
  clockMs = NOW + 45 * MINUTE;
  const later = await worker();
  assert.strictEqual(later.lateReminded, 1);
  assert.deepStrictEqual(probes.sends.map((s) => s.installation.id), [INSTALL, 'install-mon-2']);
});

test('liveness probe: an UNREGISTERED token is removed and the probe is never retried', async () => {
  await register(MON, INSTALL);
  await seedState({
    lastReceivedAtMs: NOW - 60 * MINUTE,
    extra: { late_deadline_at: Timestamp.fromMillis(NOW - 15 * MINUTE) },
  });
  const probes = probeRecorder({
    failTimes: 1,
    code: 'messaging/registration-token-not-registered',
  });

  const result = await workerWith(pushRecorder().sendPush, probes.sendProbe)();
  assert.strictEqual(result.lateReminded, 1);
  const install = (await db.doc(`users/${MON}/push_installations/${INSTALL}`).get()).data();
  assert.strictEqual(install.stale, true);
  assert.strictEqual(install.token, undefined);

  clockMs += HOUR;
  await workerWith(pushRecorder().sendPush, probes.sendProbe)();
  assert.strictEqual(probes.sends.length, 1, 'at-most-once: no retry after a provider failure');
});

test('liveness probe: a provider-confirmed dead token notifies the partner immediately', async () => {
  await seedPairing();
  await register(MON, INSTALL);
  await register(WATCH, WATCH_INSTALL);
  await seedState({
    lastReceivedAtMs: NOW - 60 * MINUTE,
    extra: { late_deadline_at: Timestamp.fromMillis(NOW - 15 * MINUTE) },
  });
  const probes = probeRecorder({
    failTimes: 1,
    code: 'messaging/registration-token-not-registered',
  });
  const pushes = pushRecorder();

  const result = await workerWith(pushes.sendPush, probes.sendProbe)();
  assert.strictEqual(result.lateReminded, 1);
  assert.strictEqual(result.lateNotified, 1, 'the uninstall signal became a notice in this pass');
  assert.strictEqual(result.notified, 1);
  assert.strictEqual(result.outboxCreated, 1);
  assert.strictEqual(result.sent, 1, 'same-pass dispatch: the partner is told in minutes, not days');

  const state = (await stateRef().get()).data();
  assert.strictEqual(state.notice_state, 'NOTIFIED');
  assert.strictEqual(state.last_notified_generation, 1);
  assert.strictEqual(state.outage_generation, 1);
  assert.strictEqual(state.next_deadline_at, undefined, 'the six-hour deadline cannot duplicate');
  assert.strictEqual(state.late_deadline_at, undefined, 'no further probes');

  const row = (await outboxRef(INSTALL, 1).get()).data();
  assert.strictEqual(row.status, NOTICE_SENT);
  assert.strictEqual(row.notice_type, NOTICE_AVAILABILITY);
  assert.strictEqual(row.expected_notified_generation, 1);
  assert.strictEqual(pushes.sends[0].row.notice_type, NOTICE_AVAILABILITY);
  assert.deepStrictEqual(pushes.sends[0].installs.map((i) => i.id), [WATCH_INSTALL]);

  clockMs += HOUR;
  await workerWith(pushes.sendPush, probes.sendProbe)();
  assert.strictEqual(pushes.sends.length, 1, 'never a second notice for the same outage');
});

test('liveness probe: a live sibling install supersedes the immediate notice', async () => {
  await seedPairing();
  await register(MON, INSTALL);
  await register(WATCH, WATCH_INSTALL);
  await seedState({
    lastReceivedAtMs: NOW - 60 * MINUTE,
    extra: { late_deadline_at: Timestamp.fromMillis(NOW - 15 * MINUTE) },
  });
  await seedState({ installId: 'install-mon-2', lastReceivedAtMs: NOW - 5 * MINUTE, generation: 2 });
  const probes = probeRecorder({
    failTimes: 1,
    code: 'messaging/registration-token-not-registered',
  });
  const pushes = pushRecorder();

  const result = await workerWith(pushes.sendPush, probes.sendProbe)();
  assert.strictEqual(result.lateReminded, 1);
  assert.strictEqual(result.lateNotified, 0);
  assert.strictEqual(result.notified, 0);
  assert.strictEqual(result.superseded, 1);
  assert.strictEqual(pushes.sends.length, 0, 'a replaced phone is not partner news');

  const state = (await stateRef().get()).data();
  assert.strictEqual(state.notice_state, 'SUPERSEDED');
  assert.strictEqual(state.next_deadline_at, undefined);
  assert.strictEqual(state.late_deadline_at, undefined);
  assert.strictEqual((await outboxRef(INSTALL, 1).get()).exists, false);
  assert.strictEqual(
    (await stateRef(MON, 'install-mon-2').get()).data().notice_state,
    'NONE',
    'the live install is untouched',
  );
});

test('deadline: a live sibling install supersedes the six-hour row instead of notifying', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  await seedState({ installId: 'install-mon-2', lastReceivedAtMs: NOW - 5 * MINUTE, generation: 2 });
  const pushes = pushRecorder();

  const result = await workerWith(pushes.sendPush)();
  assert.strictEqual(result.notified, 0);
  assert.strictEqual(result.superseded, 1);
  assert.strictEqual(pushes.sends.length, 0);
  const state = (await stateRef().get()).data();
  assert.strictEqual(state.notice_state, 'SUPERSEDED');
  assert.strictEqual((await outboxRef(INSTALL, 1).get()).exists, false);
});

// Owner devices 2026-11-12: one phone registers TWO install rows — its FID (submitHeartbeat)
// and the owner-bound service id (the Phase 6 contact bridge) — under ONE FCM token. On a real
// uninstall both fall silent together, and each row's probe saw the other's fresher beat as a
// "live sibling" and superseded BOTH: the partner was never told. Same token is the same
// device, so the notice must fire exactly once and the twin row must be cancelled.
test('liveness probe: the same phone’s two install rows produce one immediate notice', async () => {
  await seedPairing();
  await register(MON, INSTALL);
  await register(MON, 'install-mon-fid', { token: `token-${INSTALL}` });
  await register(WATCH, WATCH_INSTALL);
  await seedState({
    lastReceivedAtMs: NOW - 60 * MINUTE,
    extra: { late_deadline_at: Timestamp.fromMillis(NOW - 15 * MINUTE) },
  });
  await seedState({
    installId: 'install-mon-fid',
    lastReceivedAtMs: NOW - 60 * MINUTE + 3000,
    generation: 2,
    extra: { late_deadline_at: Timestamp.fromMillis(NOW - 15 * MINUTE) },
  });
  const probes = probeRecorder({ failTimes: 2, code: 'messaging/registration-token-not-registered' });
  const pushes = pushRecorder();

  const result = await workerWith(pushes.sendPush, probes.sendProbe)();
  assert.strictEqual(result.lateNotified, 1, 'the phone is gone: the partner is told');
  assert.strictEqual(result.outboxCreated, 1);
  assert.strictEqual(result.sent, 1);
  assert.strictEqual(pushes.sends.length, 1, 'one partner notification for one outage');

  const states = [
    (await stateRef().get()).data(),
    (await stateRef(MON, 'install-mon-fid').get()).data(),
  ];
  assert.strictEqual(states.filter((s) => s.notice_state === 'NOTIFIED').length, 1);
  assert.strictEqual(states.filter((s) => s.notice_state === 'SUPERSEDED').length, 1,
    'the same phone’s other row is cancelled, never paged twice');
  assert.ok(states.every((s) => s.late_deadline_at === undefined && s.next_deadline_at === undefined));

  clockMs += HOUR;
  await workerWith(pushes.sendPush, probes.sendProbe)();
  assert.strictEqual(pushes.sends.length, 1, 'never a second notice for the same outage');
});

test('deadline: the same phone’s two install rows produce one six-hour notice', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await register(MON, INSTALL);
  await register(MON, 'install-mon-fid', { token: `token-${INSTALL}` });
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  // A beat a hair fresher than its twin's: under the old rule this counted as a live sibling.
  await seedState({
    installId: 'install-mon-fid',
    lastReceivedAtMs: NOW - 6 * HOUR + 30_000,
    generation: 2,
  });
  const pushes = pushRecorder();

  const result = await workerWith(pushes.sendPush)();
  assert.strictEqual(result.notified, 1, 'one outage, one transition');
  assert.strictEqual(result.outboxCreated, 1);
  assert.strictEqual(pushes.sends.length, 1, 'one partner notification');
  assert.strictEqual((await outboxRef(INSTALL, 1).get()).exists, true);
  assert.strictEqual((await outboxRef('install-mon-fid', 1).get()).exists, false,
    'the phone’s other row never writes a second outbox row');
  const states = [
    (await stateRef().get()).data(),
    (await stateRef(MON, 'install-mon-fid').get()).data(),
  ];
  assert.strictEqual(states.filter((s) => s.notice_state === 'NOTIFIED').length, 1);
  assert.strictEqual(states.filter((s) => s.notice_state === 'SUPERSEDED').length, 1);
});

test('heartbeat: an accepted beat revives a superseded row cleanly', async () => {
  await seedPairing();
  await register(MON, INSTALL);
  await seedState({
    lastReceivedAtMs: NOW - 60 * MINUTE,
    extra: { notice_state: 'SUPERSEDED' },
  });
  await stateRef().update({
    next_deadline_at: FieldValue.delete(),
    late_deadline_at: FieldValue.delete(),
  });

  const reply = await heartbeatHandler()({ auth: { uid: MON }, data: { installId: INSTALL, counter: 2 } });
  assert.strictEqual(reply.accepted, true);
  const state = (await stateRef().get()).data();
  assert.strictEqual(state.notice_state, 'NONE');
  assert.strictEqual(state.next_deadline_at.toMillis(), NOW + DMS_PARTNER_PUSH_MS);
  assert.strictEqual(state.late_deadline_at.toMillis(), NOW + 45 * MINUTE);
  assert.strictEqual(state.recovered_at_ms, undefined, 'no one was notified, so no recovery is owed');
  assert.strictEqual((await outboxRef(INSTALL, 1, NOTICE_RECOVERY).get()).exists, false);
});

test('worker: immediate uninstall notices count toward the fleet breaker', async () => {
  await seedPairing();
  await register(MON, INSTALL);
  await register(WATCH, WATCH_INSTALL);
  await seedState({
    lastReceivedAtMs: NOW - 60 * MINUTE,
    extra: { late_deadline_at: Timestamp.fromMillis(NOW - 15 * MINUTE) },
  });
  await configRef().set({ late_burst_threshold: 1 });
  const probes = probeRecorder({
    failTimes: 1,
    code: 'messaging/registration-token-not-registered',
  });
  const pushes = pushRecorder();

  const result = await workerWith(pushes.sendPush, probes.sendProbe)();
  assert.strictEqual(result.lateNotified, 1);
  assert.strictEqual(result.breakerOpened, true);
  assert.strictEqual(result.dispatchHeld, true);
  assert.strictEqual(result.sent, 0, 'a burst of dead tokens is an incident, not a fan-out');
  assert.strictEqual(pushes.sends.length, 0);
  assert.strictEqual((await outboxRef(INSTALL, 1).get()).data().status, NOTICE_PENDING);
  const config = (await configRef().get()).data();
  assert.strictEqual(config.dispatch_hold, true);
  assert.strictEqual(config.incident_reason, 'LATE_BURST');
});

// ---------------------------------------------------------------------------
// heartbeat recovery

test('heartbeat: after a sent notice it queues one recovery and clears the outage', async () => {
  await seedPairing();
  await register(MON, INSTALL);
  await register(WATCH, WATCH_INSTALL);
  await seedState({
    lastReceivedAtMs: NOW - 7 * HOUR,
    generation: 5,
    extra: { notice_state: 'NOTIFIED', outage_generation: 1, last_notified_generation: 5 },
  });
  await outboxRef(INSTALL, 1).set({
    install_id: INSTALL,
    state_doc_id: stateRef().id,
    monitored_uid: MON,
    relationship_id: PAIR_ID,
    recipient_uid: WATCH,
    outage_generation: 1,
    notice_type: NOTICE_AVAILABILITY,
    expected_notified_generation: 5,
    status: NOTICE_SENT,
    attempts: 1,
    next_attempt_at_ms: NOW - 7 * HOUR,
    created_at_ms: NOW - 7 * HOUR,
  });

  const reply = await heartbeatHandler()({ auth: { uid: MON }, data: { installId: INSTALL, counter: 2 } });
  assert.strictEqual(reply.accepted, true);

  const recovery = (await outboxRef(INSTALL, 1, NOTICE_RECOVERY).get()).data();
  assert.strictEqual(recovery.status, NOTICE_PENDING);
  assert.strictEqual(recovery.notice_type, NOTICE_RECOVERY);
  assert.strictEqual(recovery.recipient_uid, WATCH);
  assert.strictEqual(recovery.outage_generation, 1);

  const state = (await stateRef().get()).data();
  assert.strictEqual(state.notice_state, 'NONE');
  assert.strictEqual(state.recovered_at_ms, NOW);

  const recorder = pushRecorder();
  await workerWith(recorder.sendPush)();
  assert.strictEqual(recorder.sends.length, 1);
  assert.strictEqual(recorder.sends[0].row.notice_type, NOTICE_RECOVERY);
  assert.strictEqual((await outboxRef(INSTALL, 1, NOTICE_RECOVERY).get()).data().status, NOTICE_SENT);

  // A later beat is not a second recovery.
  clockMs += MIN_ACCEPT_INTERVAL_MS + 1;
  await heartbeatHandler()({ auth: { uid: MON }, data: { installId: INSTALL, counter: 3 } });
  assert.strictEqual((await outboxRef(INSTALL, 1, NOTICE_RECOVERY).get()).data().status, NOTICE_SENT);
});

test('heartbeat: a still-unsent notice is suppressed, and no recovery is invented', async () => {
  await seedPairing();
  await register(MON, INSTALL);
  await seedState({
    lastReceivedAtMs: NOW - 7 * HOUR,
    generation: 5,
    extra: { notice_state: 'NOTIFIED', outage_generation: 1, last_notified_generation: 5 },
  });
  await outboxRef(INSTALL, 1).set({
    install_id: INSTALL,
    state_doc_id: stateRef().id,
    monitored_uid: MON,
    relationship_id: PAIR_ID,
    recipient_uid: WATCH,
    outage_generation: 1,
    notice_type: NOTICE_AVAILABILITY,
    expected_notified_generation: 5,
    status: NOTICE_PENDING,
    attempts: 0,
    next_attempt_at_ms: NOW,
    created_at_ms: NOW - HOUR,
  });

  await heartbeatHandler()({ auth: { uid: MON }, data: { installId: INSTALL, counter: 2 } });

  const availability = (await outboxRef(INSTALL, 1).get()).data();
  assert.strictEqual(availability.status, NOTICE_SUPPRESSED);
  assert.strictEqual(availability.suppressed_reason, 'RECOVERED_BEFORE_DISPATCH');
  assert.strictEqual((await outboxRef(INSTALL, 1, NOTICE_RECOVERY).get()).exists, false);
  assert.strictEqual((await stateRef().get()).data().notice_state, 'NONE');
});

test('heartbeat: an in-flight notice with a live lease counts as sent', async () => {
  await seedPairing();
  await register(MON, INSTALL);
  await seedState({
    lastReceivedAtMs: NOW - 7 * HOUR,
    generation: 5,
    extra: { notice_state: 'NOTIFIED', outage_generation: 1, last_notified_generation: 5 },
  });
  await outboxRef(INSTALL, 1).set({
    install_id: INSTALL,
    state_doc_id: stateRef().id,
    monitored_uid: MON,
    relationship_id: PAIR_ID,
    recipient_uid: WATCH,
    outage_generation: 1,
    notice_type: NOTICE_AVAILABILITY,
    expected_notified_generation: 5,
    status: NOTICE_DISPATCHING,
    attempts: 1,
    lease_until_ms: NOW + MINUTE,
    next_attempt_at_ms: NOW - HOUR,
    created_at_ms: NOW - HOUR,
  });

  await heartbeatHandler()({ auth: { uid: MON }, data: { installId: INSTALL, counter: 2 } });
  assert.strictEqual((await outboxRef(INSTALL, 1, NOTICE_RECOVERY).get()).exists, true);
});

// ---------------------------------------------------------------------------
// outages, breaker, config, payload

test('worker: an open ingestion outage holds transitions and dispatch', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  await db.collection('dms_ingestion_outages').doc('open-1').set({
    service: 'submitHeartbeat',
    region: 'us-central1',
    started_at_ms: NOW - HOUR,
    ended_at_ms: null,
  });
  const recorder = pushRecorder();

  const result = await workerWith(recorder.sendPush)();
  assert.deepStrictEqual(result, { held: 'INGESTION_OUTAGE' });
  assert.strictEqual(recorder.sends.length, 0);
  assert.strictEqual((await stateRef().get()).data().notice_state, 'NONE');
  assert.strictEqual((await db.collection('dms_notification_outbox').get()).size, 0);
});

test('worker: an ended outage that consumed the deadline rebases to outage end + 6h', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  await db.collection('dms_ingestion_outages').doc('closed-1').set({
    service: 'submitHeartbeat',
    region: 'us-central1',
    started_at_ms: NOW - 6 * HOUR - 30 * MINUTE,
    ended_at_ms: NOW - 2 * HOUR,
  });
  const recorder = pushRecorder();

  const result = await workerWith(recorder.sendPush)();
  assert.strictEqual(result.deferred, 1);
  assert.strictEqual(result.notified, 0);
  const state = (await stateRef().get()).data();
  assert.strictEqual(state.notice_state, 'NONE');
  assert.strictEqual(state.next_deadline_at.toMillis(), NOW + 4 * HOUR);
  assert.strictEqual(state.deadline_rebased_at_ms, NOW);
  assert.strictEqual(recorder.sends.length, 0);

  // Silent until the rebased deadline — the outage time is not counted against the device.
  clockMs = NOW + 4 * HOUR - MINUTE;
  assert.strictEqual((await workerWith(recorder.sendPush)()).due, 0);
  clockMs = NOW + 4 * HOUR;
  assert.strictEqual((await workerWith(recorder.sendPush)()).notified, 1);
});

test('worker: a scheduler outage leaves receipt times alone and catches up in bounds', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  const lastReceipt = NOW - 10 * HOUR;
  await seedState({ lastReceivedAtMs: lastReceipt });
  await configRef().set({ due_batch: 10 });
  const recorder = pushRecorder();

  // The receipt is ten hours old; the worker simply was not running. First pass after recovery
  // notifies immediately (no rebase — only a recorded INGESTION outage rebases), and the
  // original receipt time is preserved verbatim.
  const result = await workerWith(recorder.sendPush)();
  assert.strictEqual(result.notified, 1);
  const state = (await stateRef().get()).data();
  assert.strictEqual(state.last_received_at_ms, lastReceipt);
  assert.strictEqual(state.notified_at_ms, NOW);
});

test('worker: a fleet late burst opens the breaker; release is bounded and catch-up is controlled', async () => {
  await seedPairing();
  await register(WATCH, WATCH_INSTALL);
  await configRef().set({ late_burst_threshold: 2, dispatch_batch: 2 });
  const installs = ['install-burst-a', 'install-burst-b', 'install-burst-c'];
  for (const installId of installs) {
    await seedState({ installId, lastReceivedAtMs: NOW - 7 * HOUR });
  }
  const recorder = pushRecorder();

  const burst = await workerWith(recorder.sendPush)();
  assert.strictEqual(burst.notified, 3);
  assert.strictEqual(burst.breakerOpened, true);
  assert.strictEqual(burst.dispatchHeld, true);
  assert.strictEqual(recorder.sends.length, 0, 'a burst is an incident, not a fan-out');

  const config = (await configRef().get()).data();
  assert.strictEqual(config.dispatch_hold, true);
  assert.strictEqual(config.incident_reason, 'LATE_BURST');
  assert.strictEqual(config.incident_count, 3);

  // Operator release at a controlled rate: one bounded batch per pass, never the whole backlog.
  await configRef().set({ dispatch_hold: false }, { merge: true });
  const first = await workerWith(recorder.sendPush)();
  assert.strictEqual(first.sent, 2);
  assert.strictEqual(recorder.sends.length, 2);

  const second = await workerWith(recorder.sendPush)();
  assert.strictEqual(second.sent, 1);
  assert.strictEqual(recorder.sends.length, 3);
  for (const installId of installs) {
    assert.strictEqual((await outboxRef(installId, 1).get()).data().status, NOTICE_SENT);
  }
});

test('worker: a disabled deployment performs no reads that could transition state', async () => {
  await seedPairing();
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  await configRef().set({ enabled: false });
  const recorder = pushRecorder();

  assert.deepStrictEqual(await workerWith(recorder.sendPush)(), { disabled: true });
  assert.strictEqual((await stateRef().get()).data().notice_state, 'NONE');
  assert.strictEqual(recorder.sends.length, 0);
});

test('worker: the partner-accountability switch stops transitions and dispatch without touching state', async () => {
  await seedPairing();
  await seedState({ lastReceivedAtMs: NOW - 7 * HOUR });
  await db.doc('config/features').set({ partnerAccountabilityEnabled: false });
  const recorder = pushRecorder();

  assert.deepStrictEqual(await workerWith(recorder.sendPush)(), { disabled: 'PARTNER_ACCOUNTABILITY' });
  assert.strictEqual((await stateRef().get()).data().notice_state, 'NONE');
  assert.strictEqual(recorder.sends.length, 0);
});

test('payload: the wire message is data-only with local lookup keys — the OS renders nothing', () => {
  const availability = _buildDmsMessage(
    { notice_type: NOTICE_AVAILABILITY, relationship_id: PAIR_ID }, ['tok-a'],
  );
  // Owner ruling 2026-11-12 (D16 precedent): the app renders the named copy and owns the tap.
  assert.strictEqual(availability.notification, undefined, 'no provider-rendered copy');
  assert.strictEqual(availability.android.notification, undefined, 'no provider channel');
  assert.deepStrictEqual(availability.data, {
    v: '1',
    type: 'PARTNER_AVAILABILITY',
    pairId: PAIR_ID,
    noticeType: NOTICE_AVAILABILITY,
  });
  assert.deepStrictEqual(Object.keys(availability.data).sort(), ['noticeType', 'pairId', 'type', 'v']);
  assert.strictEqual(availability.android.priority, 'high');
  assert.strictEqual(availability.android.restrictedPackageName, 'com.example.myexodus');
  assert.strictEqual(availability.android.ttl, 60 * 60 * 1000);
  assert.match(availability.android.collapseKey, /^avail-[0-9a-f]{16}$/);
  assert.deepStrictEqual(availability.tokens, ['tok-a']);

  const recovery = _buildDmsMessage(
    { notice_type: NOTICE_RECOVERY, relationship_id: PAIR_ID }, ['tok-b'],
  );
  assert.strictEqual(recovery.data.noticeType, NOTICE_RECOVERY, 'the app picks the recovered copy');
  assert.strictEqual(
    recovery.android.collapseKey,
    availability.android.collapseKey,
    'a recovery supersedes the queued silence notice for the same relationship',
  );

  const otherPair = _buildDmsMessage(
    { notice_type: NOTICE_AVAILABILITY, relationship_id: `${MON}_${THIRD}` }, ['tok-c'],
  );
  assert.notStrictEqual(otherPair.android.collapseKey, availability.android.collapseKey);

  // Provider metadata never names the pair; the data keys are this phone's own lookup keys.
  const metadata = JSON.stringify([
    availability.android, recovery.android, otherPair.android,
  ]);
  for (const secret of [MON, WATCH, THIRD, INSTALL, WATCH_INSTALL, PAIR_ID]) {
    assert.ok(!metadata.includes(secret), `provider metadata leaks ${secret}`);
  }
});

test('payload: the liveness probe is data-only, high priority, and shows nothing', () => {
  const message = _buildLivenessProbeMessage({ token: 'tok-mon' });
  assert.strictEqual(message.token, 'tok-mon');
  assert.strictEqual(message.notification, undefined, 'no visible copy: check-in is automatic');
  assert.deepStrictEqual(message.data, { v: '1', type: 'LIVENESS_PROBE' });
  assert.strictEqual(message.android.priority, 'high', 'a Doze-deferred wake cannot do its job');
  assert.strictEqual(message.android.ttl, 6 * 60 * 60 * 1000);
  assert.strictEqual(message.android.collapseKey, 'liveness-probe');
  assert.strictEqual(message.android.restrictedPackageName, 'com.example.myexodus');
  assert.strictEqual(message.android.notification, undefined, 'no channel: nothing is displayed');
  const wire = JSON.stringify(message);
  for (const secret of [MON, WATCH, INSTALL, PAIR_ID]) {
    assert.ok(!wire.includes(secret), `probe payload leaks ${secret}`);
  }
});
