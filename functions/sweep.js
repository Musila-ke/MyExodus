'use strict';
// Plan §8 Step 6 / AD-10c: the single source of retry truth for push dispatch. Every two
// minutes, sweep /relay-dispatch/*/installations/* for records still in PENDING or
// FAILED_RETRYABLE with attempts < 8 and re-invoke the provider for exactly those records.
// The relay trigger's event retry is disabled (Step 5) precisely because whole-event retry
// cannot express per-installation partial failure and would re-run rate limiting.
// The pass decision lives in dispatch-logic.js (pure, node-tested); this file is wiring.

const { getFirestore } = require('firebase-admin/firestore');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { logger } = require('firebase-functions');

const { attemptDispatch, markPushInstallationGone } = require('./dispatch-record');
require('./fcm-transport'); // registers the 'fcm' transport (AD-04)
const { MAX_ATTEMPTS } = require('./relay-logic');
const { createSweepPass } = require('./dispatch-logic');
const { deferWhileIndexBuilds } = require('./index-build-guard');

const REGION = 'us-central1';
const db = getFirestore();
// Bounds per-run cost; the next run two minutes later picks up any remainder (AD-20a spirit).
const SWEEP_BATCH = 200;

const runSweep = createSweepPass({
  listRetryable: async () => {
    const stale = await db.collectionGroup('installations')
      .where('status', 'in', ['PENDING', 'FAILED_RETRYABLE'])
      .where('attempts', '<', MAX_ATTEMPTS)
      .limit(SWEEP_BATCH)
      .get();
    return stale.docs.map((doc) => ({
      ref: doc.ref,
      recipientUid: doc.data().recipientUid,
      messageId: doc.ref.parent.parent.id,
      installationId: doc.id,
    }));
  },
  getInstallation: async (recipientUid, installationId) => {
    const snap = await db.doc(`users/${recipientUid}/push_installations/${installationId}`).get();
    return snap.exists ? { id: installationId, ...snap.data() } : null;
  },
  markGone: markPushInstallationGone,
  attempt: attemptDispatch,
  logger,
});

exports.retryDispatchSweep = onSchedule(
  {
    region: REGION,
    schedule: '*/2 * * * *',
    timeZone: 'UTC',
    maxInstances: 1, // singleton sweep; overlapping runs would dispatch the same records twice
    timeoutSeconds: 120,
  },
  deferWhileIndexBuilds(runSweep),
);
