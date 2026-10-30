'use strict';
// Account plan §6.8 / §7 step 16: account and guest deletion.
//
//   requestAccountDeletion   recent-auth-verified acceptance; writes the durable tombstone
//                            (`deletionJobs/{uid}`) and PENDING_DELETION profile, disables
//                            and revokes the Auth user, then enqueues the worker. Every
//                            effect after the transaction is retried by reconciliation —
//                            the callable only ever reports "accepted".
//   requestGuestDataDeletion the §6.8.9 double-confirmed path for the authenticated
//                            anonymous UID; same tombstone + worker.
//   processAccountDeletion   the Cloud Tasks worker: pairs → Storage → private rows →
//                            profile tree → Auth user → verify → COMPLETED. Idempotent
//                            at every step, so Cloud Tasks retries and duplicate
//                            reconciler enqueues are harmless.
//   reconcileDeletionJobs    every 15 min: re-enqueue stale jobs, purge 30-day tombstones,
//                            alert on stale unfinalized guest transitions (never delete).
//   onAuthUserDeleted        out-of-band backstop: a user deleted outside the client flow
//                            (console, Admin SDK) still gets a job and the same worker.
//
// AAD discipline (§9.4): log lines carry short hashes only, never uids or tokens.

const { getFirestore, FieldValue, FieldPath, Timestamp } = require('firebase-admin/firestore');
const { getAuth } = require('firebase-admin/auth');
const { getStorage } = require('firebase-admin/storage');
const { getFunctions } = require('firebase-admin/functions');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { onTaskDispatched } = require('firebase-functions/v2/tasks');
const functionsV1 = require('firebase-functions/v1');
const { logger } = require('firebase-functions');
const crypto = require('node:crypto');

const { isRecentAuthentication, signInProviderOf } = require('./guest-transition-logic');
const {
  KINDS,
  PHASES,
  STALE_JOB_MS,
  MAX_REQUEUES,
  isValidUid,
  validateAccountDeletionInput,
  validateGuestDeletionInput,
  needsPhase,
  isComplete,
  shouldPurgeTombstone,
  shouldReconcile,
  terminationReasonOf,
  isStaleTransition,
} = require('./account-deletion-logic');

const REGION = 'us-central1';
const WORKER_NAME = 'processAccountDeletion';
const BATCH = 400;
/** Backup objects live under `backups/{uid}/` on the default bucket (backup-manifest-logic). */
const STORAGE_PREFIX = (uid) => `backups/${uid}/`;

function hashForLog(value) {
  return crypto.createHash('sha256').update(String(value)).digest('hex').slice(0, 12);
}

function isNotFound(error) {
  return !!error && (error.code === 'auth/user-not-found' || error.code === 404 || error.code === '404');
}

/**
 * §6.8.2: the raw ID token is verified with revocation checking, `auth_time` must be within
 * five minutes, and it must belong to the caller. `request.auth` alone is not evidence of a
 * recent sign-in — its token was minted whenever the session last refreshed.
 */
async function requireRecentAuthentication(request, authAdmin, nowMs) {
  const header = request.rawRequest && request.rawRequest.headers
    ? request.rawRequest.headers.authorization
    : undefined;
  const raw = typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!raw) throw new HttpsError('unauthenticated', 'RAW_TOKEN_REQUIRED');
  let decoded;
  try {
    decoded = await authAdmin.verifyIdToken(raw, true);
  } catch (e) {
    throw new HttpsError('unauthenticated', 'TOKEN_INVALID');
  }
  if (decoded.uid !== request.auth.uid) {
    throw new HttpsError('permission-denied', 'UID_MISMATCH');
  }
  if (!isRecentAuthentication(decoded, Math.floor(nowMs / 1000))) {
    throw new HttpsError('failed-precondition', 'REAUTH_REQUIRED');
  }
  const provider =
    decoded.firebase && decoded.firebase.sign_in_provider
      ? decoded.firebase.sign_in_provider
      : undefined;
  return { decoded, provider };
}

async function disableAndRevoke(authAdmin, uid) {
  try {
    await authAdmin.updateUser(uid, { disabled: true });
  } catch (e) {
    if (!isNotFound(e)) throw e;
  }
  try {
    await authAdmin.revokeRefreshTokens(uid);
  } catch (e) {
    if (!isNotFound(e)) throw e;
  }
}

/** §6.8.4: enqueue failures never fail acceptance — the durable job is the source of truth. */
async function enqueueDeletionTask(uid) {
  const queue = getFunctions().taskQueue(`locations/${REGION}/functions/${WORKER_NAME}`);
  await queue.enqueue({ uid }, { dispatchDeadlineSeconds: 600, scheduleDelaySeconds: 0 });
}

async function bestEffortAfterAcceptance({ authAdmin, enqueueTask, uid, kind }) {
  try {
    await disableAndRevoke(authAdmin, uid);
  } catch (e) {
    logger.error('deletion disable/revoke failed; reconciliation will retry', {
      uidHash: hashForLog(uid),
      kind,
      reason: e && e.message ? e.message : 'unknown',
    });
  }
  try {
    await enqueueTask(uid);
  } catch (e) {
    logger.error('deletion task enqueue failed; reconciliation will retry', {
      uidHash: hashForLog(uid),
      kind,
      reason: e && e.message ? e.message : 'unknown',
    });
  }
}

function createRequestAccountDeletionHandler({
  db,
  authAdmin,
  enqueueTask = enqueueDeletionTask,
  now = () => Date.now(),
}) {
  return async function requestAccountDeletionHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const validation = validateAccountDeletionInput(request.data);
    if (!validation.ok) {
      logger.warn('requestAccountDeletion rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }

    const { decoded, provider } = await requireRecentAuthentication(request, authAdmin, now());
    if (provider === 'anonymous') {
      throw new HttpsError('failed-precondition', 'PERSISTENT_ACCOUNT_REQUIRED');
    }
    if (provider === 'password' && decoded.email_verified !== true) {
      throw new HttpsError('failed-precondition', 'EMAIL_NOT_VERIFIED');
    }

    const uid = auth.uid;
    const existingPhase = await db.runTransaction(async (tx) => {
      const jobRef = db.doc(`deletionJobs/${uid}`);
      const profileRef = db.doc(`users/${uid}`);
      const [jobSnap, profileSnap] = await Promise.all([tx.get(jobRef), tx.get(profileRef)]);
      if (jobSnap.exists) return jobSnap.get('phase') || PHASES.QUEUED;

      // §6.8.3: tombstone + PENDING_DELETION in one transaction, before any side effect.
      tx.set(jobRef, {
        uid,
        kind: KINDS.ACCOUNT,
        operationId: crypto.randomUUID(),
        phase: PHASES.QUEUED,
        requeueCount: 0,
        requestedAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      if (profileSnap.exists) {
        tx.set(profileRef, {
          status: 'PENDING_DELETION',
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true });
      }
      return null;
    });

    await bestEffortAfterAcceptance({ authAdmin, enqueueTask, uid, kind: KINDS.ACCOUNT });
    logger.info('account deletion accepted', {
      uidHash: hashForLog(uid),
      replay: existingPhase !== null,
    });
    return { state: existingPhase === null ? PHASES.QUEUED : existingPhase };
  };
}

function createRequestGuestDataDeletionHandler({
  db,
  authAdmin,
  enqueueTask = enqueueDeletionTask,
}) {
  return async function requestGuestDataDeletionHandler(request) {
    const auth = request.auth;
    if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

    const validation = validateGuestDeletionInput(request.data);
    if (!validation.ok) {
      logger.warn('requestGuestDataDeletion rejected', { reason: validation.reason });
      throw new HttpsError('invalid-argument', validation.reason);
    }

    if (signInProviderOf(auth) !== 'anonymous') {
      throw new HttpsError('permission-denied', 'ANONYMOUS_ONLY');
    }

    const uid = auth.uid;
    const existingPhase = await db.runTransaction(async (tx) => {
      const jobRef = db.doc(`deletionJobs/${uid}`);
      const jobSnap = await tx.get(jobRef);
      if (jobSnap.exists) return jobSnap.get('phase') || PHASES.QUEUED;
      tx.set(jobRef, {
        uid,
        kind: KINDS.GUEST,
        operationId: crypto.randomUUID(),
        phase: PHASES.QUEUED,
        requeueCount: 0,
        requestedAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return null;
    });

    await bestEffortAfterAcceptance({ authAdmin, enqueueTask, uid, kind: KINDS.GUEST });
    logger.info('guest data deletion accepted', {
      uidHash: hashForLog(uid),
      replay: existingPhase !== null,
    });
    return { state: existingPhase === null ? PHASES.QUEUED : existingPhase };
  };
}

// --- worker ------------------------------------------------------------------------------

async function deleteDocs(db, docs) {
  for (let i = 0; i < docs.length; i += BATCH) {
    const batch = db.batch();
    docs.slice(i, i + BATCH).forEach((doc) => batch.delete(doc.ref || doc));
    await batch.commit();
  }
}

/** §6.8.5: every pair containing the uid, whatever its status. */
async function purgePairs(db, uid, kind) {
  const pairs = await db.collection('pairings').where('members', 'array-contains', uid).get();
  const reason = terminationReasonOf(kind);
  for (const pairDoc of pairs.docs) {
    const pairId = pairDoc.id;
    await db.runTransaction(async (tx) => {
      const ref = db.doc(`pairings/${pairId}`);
      const snap = await tx.get(ref);
      if (!snap.exists) return;

      const patch = {};
      if (snap.get('status') !== 'terminated') {
        patch.status = 'terminated';
        patch.terminatedAt = FieldValue.serverTimestamp();
        patch.terminatedBy = uid;
        patch.terminationReason = reason;
      }
      if (Object.keys(patch).length > 0) {
        tx.set(ref, patch, { merge: true });
      }
      // §6.8.5/§6.8.8: scrub the deleted participant's private fields. Pair documents
      // carry no display names (verified against every writer); proofs and the availability
      // consent entry are the only uid-keyed maps. A merge-set would keep the nested key,
      // so the deletion goes through an explicit field path.
      const scrub = [];
      for (const field of ['proofs', 'availabilityConsent']) {
        const value = snap.get(field);
        if (value && typeof value === 'object' && Object.prototype.hasOwnProperty.call(value, uid)) {
          scrub.push(new FieldPath(field, uid), FieldValue.delete());
        }
      }
      if (scrub.length > 0) {
        tx.update(ref, ...scrub);
      }
      if (patch.status === 'terminated') {
        // Deterministic id: a retried worker can never duplicate the lifecycle event.
        tx.set(db.doc(`pairings/${pairId}/system_events/pair_terminated`), {
          type: 'PAIR_TERMINATED',
          actorUid: uid,
          reason,
          createdAt: FieldValue.serverTimestamp(),
        });
      }
    });

    // The deleted participant's private nudges and per-uid nudge state. The partner's
    // records (settings, state, received nudges) stay untouched.
    await db.doc(`pairings/${pairId}/nudgeSettings/${uid}`).delete();
    await db.doc(`pairings/${pairId}/nudgeState/${uid}`).delete();
    for (const field of ['senderUid', 'recipientUid']) {
      const nudges = await db.collection(`pairings/${pairId}/nudges`).where(field, '==', uid).get();
      for (const nudge of nudges.docs) {
        await db.doc(`nudgePushQueue/${nudge.id}`).delete();
        await db.recursiveDelete(nudge.ref);
      }
    }
    await db.doc(`eventRateLimits/pair_${pairId}`).delete();
  }
}

/** §6.8.6: enumerate object storage separately; Firestore deletes never cascade there. */
async function purgeStorage(bucket, uid) {
  const [files] = await bucket.getFiles({ prefix: STORAGE_PREFIX(uid) });
  for (const file of files) {
    const [metadata] = await file.getMetadata();
    await file.delete({
      ifGenerationMatch: String(metadata.generation),
      ignoreNotFound: true,
    });
  }
  return files.length;
}

/** §6.8.6: private rows and server bookkeeping that carry the uid. */
async function purgePrivateRows(db, uid) {
  // Mailbox: collect message ids first, then take the relay records with each message so no
  // installation-level delivery record survives under a vanished message (AD-10).
  const messages = await db.collection(`mailboxes/${uid}/messages`).get();
  for (const message of messages.docs) {
    await db.recursiveDelete(db.doc(`relay-dispatch/${message.id}`));
  }
  await db.recursiveDelete(db.doc(`mailboxes/${uid}`));

  await db.recursiveDelete(db.doc(`backupReservations/${uid}`));

  for (const collection of [
    db.collection('dms_install_state').where('uid', '==', uid),
    db.collection('dms_notification_outbox').where('monitored_uid', '==', uid),
    db.collection('dms_notification_outbox').where('recipient_uid', '==', uid),
    db.collection('guestTransitions').where('guestUid', '==', uid),
    db.collection('pairing_tokens').where('creatorUid', '==', uid),
    // A token the uid responded to carries its response material; the invitation cannot
    // complete after the account is gone, so the transport document goes too.
    db.collection('pairing_tokens').where('partnerUid', '==', uid),
  ]) {
    await deleteDocs(db, (await collection.get()).docs);
  }
  await db.doc(`guestTransitionLeases/${uid}`).delete();

  for (const docPath of [
    `rate-limits/pairing_${uid}`,
    `rate-limits/invitation_${uid}`,
    `rate-limits/guest_transition_${uid}`,
    `eventRateLimits/user_${uid}`,
  ]) {
    await db.doc(docPath).delete();
  }
  // `idempotency` records carry no uid field and are purged by their own 24h retention
  // (cleanup.js); they hold only hashes, a pair id and the committed status.
}

async function deleteAuthUser(authAdmin, uid) {
  try {
    await authAdmin.deleteUser(uid);
  } catch (e) {
    // §6.8.7: already gone is the completed state.
    if (!isNotFound(e)) throw e;
  }
}

/** §6.8.7: verify the cleanup stages before declaring COMPLETED. */
async function verifyPurge(db, bucket, uid) {
  const [profile, mailbox, reservations] = await Promise.all([
    db.doc(`users/${uid}`).get(),
    db.doc(`mailboxes/${uid}`).get(),
    db.doc(`backupReservations/${uid}`).get(),
  ]);
  if (profile.exists || mailbox.exists || reservations.exists) return false;

  const [files] = await bucket.getFiles({ prefix: STORAGE_PREFIX(uid), maxResults: 1 });
  if (files.length > 0) return false;

  const pairs = await db.collection('pairings').where('members', 'array-contains', uid).get();
  for (const pair of pairs.docs) {
    if (pair.get('status') !== 'terminated') return false;
  }
  const devices = await db.collection('dms_install_state').where('uid', '==', uid).limit(1).get();
  if (!devices.empty) return false;
  const outbox = await db.collection('dms_notification_outbox')
    .where('monitored_uid', '==', uid).limit(1).get();
  if (!outbox.empty) return false;
  return true;
}

function createProcessAccountDeletionHandler({
  db,
  authAdmin,
  bucket,
  now = () => Date.now(),
}) {
  return async function processAccountDeletionHandler(request) {
    const payload = request && request.data ? request.data : {};
    const uid = payload.uid;
    if (!isValidUid(uid)) throw new Error('INVALID_TASK_PAYLOAD');

    const jobRef = db.doc(`deletionJobs/${uid}`);
    const jobSnap = await jobRef.get();
    if (!jobSnap.exists) {
      // The tombstone was purged after completion; the Auth user is already gone.
      logger.info('deletion task without job', { uidHash: hashForLog(uid) });
      return { state: 'NO_JOB' };
    }
    const job = jobSnap.data();
    const kind = job.kind || KINDS.ACCOUNT;
    if (isComplete(job)) return { state: PHASES.COMPLETED };

    // §6.8.4: retry the disable/revoke stage on every run; it is idempotent and must hold
    // even when acceptance-time calls were lost.
    await disableAndRevoke(authAdmin, uid);

    if (needsPhase(job.phase, PHASES.PAIRS)) {
      await purgePairs(db, uid, kind);
      await jobRef.set({ phase: PHASES.PAIRS, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    }
    if (needsPhase(job.phase, PHASES.STORAGE)) {
      await purgeStorage(bucket, uid);
      await jobRef.set({ phase: PHASES.STORAGE, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    }
    if (needsPhase(job.phase, PHASES.PRIVATE)) {
      await purgePrivateRows(db, uid);
      await jobRef.set({ phase: PHASES.PRIVATE, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    }
    if (needsPhase(job.phase, PHASES.PROFILE)) {
      await db.recursiveDelete(db.doc(`users/${uid}`));
      await jobRef.set({ phase: PHASES.PROFILE, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    }
    if (needsPhase(job.phase, PHASES.AUTH)) {
      await deleteAuthUser(authAdmin, uid);
      await jobRef.set({ phase: PHASES.AUTH, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    }

    // §6.8.7: final sweep after Auth deletion — an upload, nudge or profile repair that was
    // already in flight when the tombstone landed must not survive the job. Each pass is
    // idempotent and only touches rows still carrying the uid.
    await purgePairs(db, uid, kind);
    await purgeStorage(bucket, uid);
    await purgePrivateRows(db, uid);
    await db.recursiveDelete(db.doc(`users/${uid}`));
    if (!(await verifyPurge(db, bucket, uid))) {
      logger.error('deletion purge incomplete; task will retry', { uidHash: hashForLog(uid) });
      throw new Error('PURGE_INCOMPLETE');
    }

    await jobRef.set({
      phase: PHASES.COMPLETED,
      completedAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
    logger.info('account deletion completed', { uidHash: hashForLog(uid), kind });
    return { state: PHASES.COMPLETED };
  };
}

// --- reconciler + backstop ---------------------------------------------------------------

/** §14: "deleted" can only be true while the Auth user is gone. */
async function authUserExists(authAdmin, uid) {
  try {
    await authAdmin.getUser(uid);
    return true;
  } catch (e) {
    if (isNotFound(e)) return false;
    throw e;
  }
}

function queuedJob(uid, kind) {
  return {
    uid,
    kind,
    operationId: crypto.randomUUID(),
    phase: PHASES.QUEUED,
    requeueCount: 0,
    requestedAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp(),
  };
}

async function enqueueOrLog(enqueueTask, uid) {
  try {
    await enqueueTask(uid);
    return true;
  } catch (e) {
    logger.error('deletion requeue failed', {
      uidHash: hashForLog(uid),
      reason: e && e.message ? e.message : 'unknown',
    });
    return false;
  }
}

function createReconcileDeletionJobsHandler({
  db,
  authAdmin,
  enqueueTask = enqueueDeletionTask,
  now = () => Date.now(),
}) {
  return async function reconcileDeletionJobsHandler() {
    const nowMs = now();
    const stale = await db.collection('deletionJobs')
      .where('updatedAt', '<', Timestamp.fromMillis(nowMs - STALE_JOB_MS))
      .orderBy('updatedAt')
      .limit(50)
      .get();

    let requeued = 0;
    let purged = 0;
    let mismatches = 0;
    for (const doc of stale.docs) {
      const job = doc.data();
      if (isComplete(job)) {
        // §6.8.10/§14: a COMPLETED tombstone whose Auth user lives again is a lie — the uid
        // was recreated out of band after "deletion". Replace the tombstone with a fresh job
        // rather than trusting it, and let the normal worker purge and delete Auth again.
        let live;
        try {
          live = await authUserExists(authAdmin, doc.id);
        } catch (e) {
          logger.error('auth profile mismatch check failed', {
            uidHash: hashForLog(doc.id),
            reason: e && e.message ? e.message : 'unknown',
          });
          continue;
        }
        if (live) {
          await doc.ref.set(queuedJob(doc.id, job.kind || KINDS.ACCOUNT));
          if (await enqueueOrLog(enqueueTask, doc.id)) requeued += 1;
          logger.error('auth profile mismatch: completed job with live auth', {
            uidHash: hashForLog(doc.id),
          });
          mismatches += 1;
          continue;
        }
        if (shouldPurgeTombstone(job, nowMs)) {
          // §6.8.8: the 30-day tombstone ages out only after the Auth user is gone.
          await db.recursiveDelete(doc.ref);
          purged += 1;
        }
        continue;
      }
      if (!shouldReconcile(job, nowMs)) continue;
      const requeueCount = (job.requeueCount || 0) + 1;
      if (await enqueueOrLog(enqueueTask, doc.id)) requeued += 1;
      await doc.ref.set({
        requeueCount,
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: true });
      if (requeueCount === MAX_REQUEUES) {
        logger.error('deletion job stuck; manual intervention required', {
          uidHash: hashForLog(doc.id),
          phase: job.phase,
          requeueCount,
        });
      }
    }

    // §14: the acceptance transaction always writes the profile and the job together, so a
    // PENDING_DELETION profile with no job is a damaged/operator-made state that would deny
    // every cloud operation forever. Converge it by creating the job that should exist.
    const pending = await db.collection('users')
      .where('status', '==', 'PENDING_DELETION')
      .limit(50)
      .get();
    for (const doc of pending.docs) {
      try {
        if ((await db.doc(`deletionJobs/${doc.id}`).get()).exists) continue;
        // Only the account path sets PENDING_DELETION; guest deletion never writes a profile.
        await db.doc(`deletionJobs/${doc.id}`).set(queuedJob(doc.id, KINDS.ACCOUNT));
        if (await enqueueOrLog(enqueueTask, doc.id)) requeued += 1;
        logger.error('auth profile mismatch: pending profile without job', {
          uidHash: hashForLog(doc.id),
        });
        mismatches += 1;
      } catch (e) {
        logger.error('auth profile mismatch check failed', {
          uidHash: hashForLog(doc.id),
          reason: e && e.message ? e.message : 'unknown',
        });
      }
    }

    // §6.8.10: alert on stale unfinalized guest transitions — never delete the record, the
    // capability hash is the guest's only proof and the lease state machine owns its fate.
    const prepared = await db.collection('guestTransitions').where('state', '==', 'PREPARED').limit(100).get();
    const staleTransitions = prepared.docs.filter((doc) => isStaleTransition(doc.data(), nowMs));
    if (staleTransitions.length > 0) {
      logger.error('stale unfinalized guest transitions', {
        count: staleTransitions.length,
        ids: staleTransitions.slice(0, 10).map((doc) => hashForLog(doc.id)),
      });
    }

    if (requeued + purged + mismatches > 0) {
      logger.info('deletion reconciliation', { scanned: stale.size, requeued, purged, mismatches });
    }
  };
}

function createAuthDeletionBackstopHandler({ db, enqueueTask = enqueueDeletionTask }) {
  return async function authDeletionBackstopHandler(user) {
    const uid = user && user.uid;
    if (!isValidUid(uid)) return;
    const jobRef = db.doc(`deletionJobs/${uid}`);
    const jobSnap = await jobRef.get();
    if (jobSnap.exists && isComplete(jobSnap.data())) return;
    if (!jobSnap.exists) {
      await jobRef.set({
        uid,
        kind: KINDS.AUTH_BACKSTOP,
        operationId: crypto.randomUUID(),
        phase: PHASES.QUEUED,
        requeueCount: 0,
        requestedAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
    try {
      await enqueueTask(uid);
    } catch (e) {
      // The durable job is enough: the 15-minute reconciler re-enqueues it.
      logger.error('auth deletion backstop enqueue failed', {
        uidHash: hashForLog(uid),
        reason: e && e.message ? e.message : 'unknown',
      });
    }
  };
}

// --- deployed wrappers -------------------------------------------------------------------

function createRequestAccountDeletion({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 60 },
    createRequestAccountDeletionHandler({
      db: db || getFirestore(),
      authAdmin: getAuth(),
    }),
  );
}

function createRequestGuestDataDeletion({ db } = {}) {
  return onCall(
    { region: REGION, enforceAppCheck: true, maxInstances: 10, timeoutSeconds: 60 },
    createRequestGuestDataDeletionHandler({
      db: db || getFirestore(),
      authAdmin: getAuth(),
    }),
  );
}

function createProcessAccountDeletion({ db, authAdmin, bucket } = {}) {
  return onTaskDispatched(
    {
      region: REGION,
      retryConfig: { maxAttempts: 12, maxRetrySeconds: 3600 },
      rateLimits: { maxConcurrentDispatches: 5 },
      timeoutSeconds: 300,
    },
    createProcessAccountDeletionHandler({
      db: db || getFirestore(),
      authAdmin: authAdmin || getAuth(),
      bucket: bucket || getStorage().bucket(),
    }),
  );
}

function createReconcileDeletionJobs({ db, authAdmin } = {}) {
  return onSchedule(
    {
      region: REGION,
      schedule: '*/15 * * * *',
      timeZone: 'UTC',
      maxInstances: 1,
      timeoutSeconds: 300,
    },
    createReconcileDeletionJobsHandler({
      db: db || getFirestore(),
      authAdmin: authAdmin || getAuth(),
    }),
  );
}

function createAuthDeletionBackstop({ db } = {}) {
  return functionsV1
    .runWith({ region: REGION })
    .auth.user()
    .onDelete(createAuthDeletionBackstopHandler({ db: db || getFirestore() }));
}

module.exports = {
  createRequestAccountDeletion,
  createRequestGuestDataDeletion,
  createProcessAccountDeletion,
  createReconcileDeletionJobs,
  createAuthDeletionBackstop,
  createRequestAccountDeletionHandler,
  createRequestGuestDataDeletionHandler,
  createProcessAccountDeletionHandler,
  createReconcileDeletionJobsHandler,
  createAuthDeletionBackstopHandler,
  enqueueDeletionTask,
};
