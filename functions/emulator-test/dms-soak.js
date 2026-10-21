'use strict';
// Step 8 validation: "100k-install load/soak test against the chosen queue/deadline
// implementation." The deadline queue is the authoritative database, so this seeds 100,000
// install state rows, then drives the real worker passes and measures that:
//
// The 1% that are due get their full partner graph (pairing + partner installation) because
// they are what the pass joins; the 99% future rows are queue bulk only — no pass can touch
// them before their deadline, which is exactly the property under test.
//
//   * the due query never returns more than `due_batch` rows (no full-table scan),
//   * every pass completes in bounded time while 100k rows sit in the collection,
//   * only the due rows transition; future deadlines are untouched,
//   * the outbox is written once per transition and dispatch accepts each notice once.
//
// Run: `npm run test:dms-soak` (fixed at 100k) or `SOAK_INSTALLS=2000 npm run test:dms-soak`
// for a fast smoke. Emulator only — the script refuses to run without FIRESTORE_EMULATOR_HOST.

const assert = require('node:assert');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');

const { _createDmsWorkerHandler } = require('../dms-processor');
const { DMS_PARTNER_PUSH_MS } = require('../heartbeat-logic');

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  console.error('Refusing to run outside the Firestore emulator (FIRESTORE_EMULATOR_HOST unset).');
  process.exit(2);
}

const PROJECT_ID = 'demo-myexodus-dms-soak';
const TOTAL = Number(process.env.SOAK_INSTALLS) || 100000;
const DUE = Math.max(1, Math.round(TOTAL * 0.01));
const DUE_BATCH = 100;
const NOW = Date.now();
const silentLog = { info() {}, warn() {}, error() {} };

function installIdOf(i) {
  return `install-${i}`;
}

function pairingOf(i) {
  const monitored = `monitored-${i}`;
  const watcher = `watcher-${i}`;
  return { monitored, watcher, pairId: `${monitored}_${watcher}` };
}

async function seed(db) {
  const startedAt = Date.now();
  let batch = db.batch();
  let pending = 0;
  const flush = async () => {
    if (pending === 0) return;
    await batch.commit();
    batch = db.batch();
    pending = 0;
  };

  for (let i = 0; i < TOTAL; i++) {
    const { monitored, watcher, pairId } = pairingOf(i);
    const installId = installIdOf(i);
    const due = i < DUE;
    const lastReceivedAtMs = due ? NOW - 7 * 60 * 60 * 1000 : NOW - 10 * 60 * 1000;

    if (due) {
      batch.set(db.doc(`pairings/${pairId}`), {
        uidA: monitored,
        uidB: watcher,
        members: [monitored, watcher],
        status: 'active',
        pairingEpoch: 1,
        availabilityConsent: { [monitored]: { enabled: true, updatedAtMs: NOW } },
        createdAt: Timestamp.fromMillis(NOW - 48 * 60 * 60 * 1000),
      });
      batch.set(db.doc(`users/${watcher}/push_installations/install-watch-${i}`), {
        provider: 'fcm',
        token: `soak-token-${i}`,
        platform: 'android',
        appVersion: '1.0',
        updatedAt: Timestamp.fromMillis(NOW),
      });
    }
    batch.set(db.doc(`dms_install_state/${monitored}_${installId}`), {
      uid: monitored,
      install_id: installId,
      last_counter: 1,
      liveness_generation: 1,
      last_received_at: Timestamp.fromMillis(lastReceivedAtMs),
      last_received_at_ms: lastReceivedAtMs,
      next_deadline_at: Timestamp.fromMillis(lastReceivedAtMs + DMS_PARTNER_PUSH_MS),
      last_notified_generation: 0,
      outage_generation: 0,
      notice_state: 'NONE',
      updated_at: Timestamp.fromMillis(lastReceivedAtMs),
    });
    pending += due ? 3 : 1;

    if (pending >= 450) {
      await flush();
      if ((i + 1) % 20000 < 3) {
        console.log(`  seeded ${i + 1}/${TOTAL} installs (${((Date.now() - startedAt) / 1000).toFixed(1)}s)`);
      }
    }
  }
  await flush();
  return Date.now() - startedAt;
}

async function main() {
  const app = initializeApp({ projectId: PROJECT_ID }, 'dms-soak');
  const db = getFirestore(app);
  const sendPush = async ({ installs }) => ({
    providerAccepted: installs.length,
    invalidInstallations: [],
  });
  const worker = _createDmsWorkerHandler({ db, now: () => NOW, sendPush, log: silentLog });

  try {
    console.log(`Seeding ${TOTAL} installs (${DUE} due) into ${PROJECT_ID}…`);
    const seedMs = await seed(db);
    console.log(`Seeded in ${(seedMs / 1000).toFixed(1)}s`);

    // The exact query the worker runs, timed on its own against the 100k-row collection.
    const queryStart = Date.now();
    const dueSnap = await db.collection('dms_install_state')
      .where('next_deadline_at', '<=', Timestamp.fromMillis(NOW))
      .orderBy('next_deadline_at')
      .limit(DUE_BATCH)
      .get();
    const queryMs = Date.now() - queryStart;
    assert.strictEqual(
      dueSnap.size,
      Math.min(DUE, DUE_BATCH),
      'the due query must fill a batch, never scan',
    );
    console.log(`Due query: ${dueSnap.size} rows in ${queryMs}ms`);

    const passTimes = [];
    let notified = 0;
    let outboxCreated = 0;
    const maxPasses = DUE + 5;
    let passes = 0;
    while (notified < DUE && passes < maxPasses) {
      const startedAt = Date.now();
      const result = await worker();
      passTimes.push(Date.now() - startedAt);
      passes += 1;
      assert.ok(result.due <= DUE_BATCH, `pass ${passes} saw ${result.due} due rows (cap ${DUE_BATCH})`);
      notified += result.notified;
      outboxCreated += result.outboxCreated;
      if (passes % 5 === 0) console.log(`  pass ${passes}: notified=${notified}/${DUE}`);
    }

    // One more pass proves the queue drained: nothing due, nothing new sent.
    const drained = await worker();
    assert.strictEqual(drained.due, 0, 'the queue must be empty after the catch-up');
    assert.strictEqual(drained.sent, 0);

    assert.strictEqual(notified, DUE, 'every due install must transition exactly once');
    assert.strictEqual(outboxCreated, DUE, 'exactly one outbox row per transition');

    const notifiedCount = await db.collection('dms_install_state')
      .where('notice_state', '==', 'NOTIFIED')
      .count()
      .get();
    assert.strictEqual(notifiedCount.data().count, DUE, 'no future row may have transitioned');

    const outboxCount = await db.collection('dms_notification_outbox').count().get();
    assert.strictEqual(outboxCount.data().count, DUE);

    // A future row is still untouched, with its original deadline.
    const futureIndex = TOTAL - 1;
    const { monitored } = pairingOf(futureIndex);
    const future = (await db.doc(`dms_install_state/${monitored}_${installIdOf(futureIndex)}`).get()).data();
    assert.strictEqual(future.notice_state, 'NONE');
    assert.strictEqual(
      future.next_deadline_at.toMillis(),
      NOW - 10 * 60 * 1000 + DMS_PARTNER_PUSH_MS,
    );

    const sorted = [...passTimes].sort((a, b) => a - b);
    const p95 = sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.95) - 1)];
    const median = sorted[Math.floor(sorted.length / 2)];
    console.log(JSON.stringify({
      installs: TOTAL,
      due: DUE,
      seedSeconds: Number((seedMs / 1000).toFixed(1)),
      dueQueryMs: queryMs,
      passes,
      passMedianMs: median,
      passP95Ms: p95,
      passMaxMs: sorted[sorted.length - 1],
      notified,
      outboxCreated,
    }, null, 2));

    // Bounded behavior, not a hard benchmark: a pass that degrades toward minutes would mean
    // the worker had drifted into a scan.
    assert.ok(p95 < 60_000, `p95 pass time ${p95}ms exceeded 60s`);
    console.log('SOAK PASS');
  } finally {
    await deleteApp(app);
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
