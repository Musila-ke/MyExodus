'use strict';
// §8.6 partner notification dispatch. Two entry points preserve both mechanisms the plan
// requires: an onDocumentCreated trigger for prompt delivery and an every-minute sweeper
// that also materializes durable fanout tasks and reclaims expired leases. Claims run in a
// transaction keyed by the job document; the per-installation window document is written by
// the winner inside that same transaction, which serializes concurrent triggers so no more
// than one push goes to an installation per rolling 60 s.
//
// The push is data-only and generic: an opaque event id, never ciphertext, activity, or
// user-entered text (§8.6 privacy copy rule). The mailbox record is the durable truth —
// every job outcome, including a coalesced or ineligible push, leaves it untouched.

const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { onDocumentCreated } = require('firebase-functions/v2/firestore');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { logger } = require('firebase-functions');

const { dispatchViaProvider, isTokenInvalid, UnknownProviderError } = require('./dispatch');
const { deferWhileIndexBuilds } = require('./index-build-guard');
const { COLLECTIONS } = require('./partner-events');
const {
  STATUS,
  BLOCK_KIND,
  claimDecision,
  reconcileOutcome,
  isLeaseExpired,
  fanoutWrites,
} = require('./partner-notify-logic');

const REGION = 'us-central1';
const WINDOW_COLLECTION = 'partnerNotificationWindows';
const SWEEP_JOB_LIMIT = 50;
const SWEEP_FANOUT_LIMIT = 20;

function windowRef(db, job) {
  return db.doc(`${WINDOW_COLLECTION}/${job.recipientUid}:${job.recipientInstallationId}`);
}

function wakeData(job) {
  return { v: '1', type: 'PARTNER_EVENT', eventId: job.eventId };
}

/**
 * One claim, one send, one reconcile. Returns the outcome for the sweeper's logging. Any
 * thrown error leaves the row LEASED; the lease expiry path retries it.
 */
async function dispatchJob({ db, jobId, now = () => Date.now() }) {
  const jobRef = db.doc(`${COLLECTIONS.notificationOutbox}/${jobId}`);

  const claim = await db.runTransaction(async (tx) => {
    const jobSnap = await tx.get(jobRef);
    if (!jobSnap.exists) return null;
    const job = jobSnap.data();
    const at = now();

    const coalescing = job.kind === BLOCK_KIND || (job.groupId !== null && job.groupId !== undefined);
    const readable = job.status === STATUS.PENDING || isLeaseExpired(job, at);
    // Only coalescing kinds read/write the window; reading it inside the transaction is
    // what makes a concurrent claim for the same installation conflict and retry.
    const winRef = coalescing && readable ? windowRef(db, job) : null;
    const winSnap = winRef ? await tx.get(winRef) : null;
    const window = winSnap && winSnap.exists ? winSnap.data() : null;

    const decision = claimDecision({ job, window, now: at });
    if (decision.action === 'SKIP') return null;
    if (decision.action === 'COALESCE') {
      tx.set(jobRef, {
        status: STATUS.SENT_TO_FCM,
        sentAt: at,
        leaseUntil: null,
        suppressed: 'COALESCED',
        coalescedInto: decision.groupId,
        lastErrorCode: null,
      }, { merge: true });
      return { action: 'COALESCE' };
    }
    if (decision.exhausted) {
      tx.set(jobRef, {
        status: STATUS.FAILED_PERMANENT,
        attempts: decision.attempts,
        terminalAt: at,
        leaseUntil: null,
        lastErrorCode: 'ATTEMPTS_EXHAUSTED',
      }, { merge: true });
      return { action: 'EXHAUSTED' };
    }
    tx.set(jobRef, {
      status: STATUS.LEASED,
      attempts: decision.attempts,
      leaseUntil: decision.leaseUntil,
    }, { merge: true });
    if (decision.coalescing && winRef) {
      // Reservation, not a confirmed push: a failed send releases it (below), so a retry is
      // never coalesced against a push that never happened.
      tx.set(winRef, {
        lastPushAt: at,
        groupId: job.groupId,
        reservationId: jobId,
      }, { merge: false });
    }
    return { action: 'CLAIM', job, attempts: decision.attempts, coalescing: decision.coalescing };
  });

  if (!claim || claim.action !== 'CLAIM') return claim ? claim.action : 'SKIP';
  const { job, attempts, coalescing } = claim;

  const regRef = db.doc(`users/${job.recipientUid}/push_installations/${job.recipientInstallationId}`);
  let outcome = 'SEND_FAILED';
  try {
    const regSnap = await regRef.get();
    if (!regSnap.exists) {
      outcome = 'REGISTRATION_UNAVAILABLE';
    } else {
      const reg = regSnap.data();
      const provider = typeof reg.provider === 'string' ? reg.provider : 'fcm';
      if (reg.stale === true) {
        outcome = 'REGISTRATION_UNAVAILABLE';
      } else if (reg.pushEligible === false) {
        outcome = 'NOT_PUSH_ELIGIBLE';
      } else if (reg.registrationKind !== undefined && reg.registrationKind !== 'TOKEN') {
        // §8.6: an FID must never be handed to the token-addressing API.
        outcome = 'REGISTRATION_UNAVAILABLE';
      } else if (typeof reg.token !== 'string' || reg.token.length === 0) {
        outcome = 'REGISTRATION_UNAVAILABLE';
      } else {
        try {
          await dispatchViaProvider(
            { provider, token: reg.token },
            wakeData(job),
          );
          outcome = 'ACCEPTED';
        } catch (e) {
          if (e instanceof UnknownProviderError) outcome = 'UNKNOWN_PROVIDER';
          else if (isTokenInvalid(provider, e)) outcome = 'INVALID_REGISTRATION';
          else outcome = 'SEND_FAILED';
        }
      }
    }
  } catch (e) {
    outcome = 'SEND_FAILED';
    logger.warn('partnerNotify registration read failed', {
      jobId, reason: e && e.message ? e.message : 'unknown',
    });
  }

  const at = now();
  const { patch, releaseWindow, pruneRegistration } = reconcileOutcome(outcome, {
    now: at,
    attempts,
  });
  await jobRef.set(patch, { merge: true });

  if (pruneRegistration) {
    try {
      await regRef.set({
        stale: true,
        staleAt: at,
        token: FieldValue.delete(),
      }, { merge: true });
    } catch (e) {
      logger.warn('partnerNotify registration prune failed', {
        jobId, reason: e && e.message ? e.message : 'unknown',
      });
    }
  }

  if (releaseWindow && coalescing) {
    // Only the reservation owner may clear it; a successful concurrent push keeps its slot.
    const winRef = windowRef(db, job);
    try {
      await db.runTransaction(async (tx) => {
        const snap = await tx.get(winRef);
        if (!snap.exists) return;
        if (snap.get('reservationId') !== jobId) return;
        tx.delete(winRef);
      });
    } catch (e) {
      logger.warn('partnerNotify window release failed', {
        jobId, reason: e && e.message ? e.message : 'unknown',
      });
    }
  }

  logger.info('partnerNotify', { jobId, outcome, attempts });
  return outcome;
}

/** Fanout task → mailbox + job records, all in one transaction (200 recipients = 401 writes). */
async function materializeFanoutTask({ db, taskRef, now = () => Date.now() }) {
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(taskRef);
    if (!snap.exists || snap.get('status') !== 'PENDING') return 0;
    const task = snap.data();
    const at = now();
    const { writes, recipientCount } = fanoutWrites(task, COLLECTIONS, at);
    for (const write of writes) {
      tx.set(db.doc(write.path), write.data, { merge: false });
    }
    tx.set(taskRef, { status: 'DONE', materializedAt: at }, { merge: true });
    return recipientCount;
  });
}

async function runPartnerNotifySweep({ db, now = () => Date.now() }) {
  const features = await db.doc('config/features').get();
  if (features.exists && features.data().partnerAccountabilityEnabled === false) return;

  const at = now();

  const tasks = await db.collection(COLLECTIONS.fanoutTasks)
    .where('status', '==', 'PENDING')
    .limit(SWEEP_FANOUT_LIMIT)
    .get();
  for (const task of tasks.docs) {
    try {
      await materializeFanoutTask({ db, taskRef: task.ref, now });
    } catch (e) {
      logger.warn('partnerNotify fanout materialization failed', {
        taskId: task.id, reason: e && e.message ? e.message : 'unknown',
      });
    }
  }

  const due = await db.collection(COLLECTIONS.notificationOutbox)
    .where('status', '==', STATUS.PENDING)
    .where('nextAttemptAt', '<=', at)
    .limit(SWEEP_JOB_LIMIT)
    .get();
  for (const doc of due.docs) {
    try {
      await dispatchJob({ db, jobId: doc.id, now });
    } catch (e) {
      logger.warn('partnerNotify sweep dispatch failed', {
        jobId: doc.id, reason: e && e.message ? e.message : 'unknown',
      });
    }
  }

  // A killed process leaves a LEASED row; the lease expiry makes it claimable again.
  const leased = await db.collection(COLLECTIONS.notificationOutbox)
    .where('status', '==', STATUS.LEASED)
    .where('leaseUntil', '<=', at)
    .limit(SWEEP_JOB_LIMIT)
    .get();
  for (const doc of leased.docs) {
    try {
      await dispatchJob({ db, jobId: doc.id, now });
    } catch (e) {
      logger.warn('partnerNotify lease reclaim failed', {
        jobId: doc.id, reason: e && e.message ? e.message : 'unknown',
      });
    }
  }
}

function createPartnerNotifyTrigger({ db = getFirestore() } = {}) {
  return onDocumentCreated(
    {
      document: `${COLLECTIONS.notificationOutbox}/{jobId}`,
      region: REGION,
      maxInstances: 10,
    },
    async (event) => {
      try {
        await dispatchJob({ db, jobId: event.params.jobId });
      } catch (e) {
        // The sweeper is the recovery path; a thrown trigger would only retry the same job.
        logger.warn('partnerNotify trigger dispatch failed', {
          jobId: event.params.jobId,
          reason: e && e.message ? e.message : 'unknown',
        });
      }
    },
  );
}

function createPartnerNotifySweep({ db = getFirestore() } = {}) {
  return onSchedule(
    {
      schedule: 'every 1 minutes',
      region: REGION,
      maxInstances: 1,
      timeoutSeconds: 300,
    },
    deferWhileIndexBuilds(() => runPartnerNotifySweep({ db })),
  );
}

exports.dispatchJob = dispatchJob;
exports.materializeFanoutTask = materializeFanoutTask;
exports.runPartnerNotifySweep = runPartnerNotifySweep;
exports.createPartnerNotifyTrigger = createPartnerNotifyTrigger;
exports.createPartnerNotifySweep = createPartnerNotifySweep;
