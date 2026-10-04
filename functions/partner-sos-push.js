'use strict';
// Partner SOS dispatch engine (plan §6.4/§6.5, §8.5-§8.10): the `onNudgeQueueCreated`
// trigger and `pushNudge` — lease acquisition, block/pair suppression, target snapshot,
// FID multicast, lease-checked finalization. Pure decisions (backoff, FCM classification,
// terminal-state choice) live in ./partner-sos-push-logic.js; the messaging client is
// injected so the emulator suite can drive the full flow with scripted FCM responses.

const crypto = require('node:crypto');
const { FieldValue } = require('firebase-admin/firestore');
const { onDocumentCreated } = require('firebase-functions/v2/firestore');
const { logger } = require('firebase-functions');

const { hashForLog } = require('./partner-sos');
const {
  LEASE_MS,
  FCM_FID_BATCH_MAX,
  isTerminalStatus,
  leaseDecision,
  decideFinalization,
  classifyFcmError,
  normalizeFcmCode,
  isNudgeExpired,
} = require('./partner-sos-push-logic');

const REGION = 'us-central1';

// §4.6 FCM message contract, verbatim. The generic copy and the two data keys are the
// complete plaintext on the wire — no ciphertext, no pair/uid, no habit information.
const ANDROID_PACKAGE = 'com.example.myexodus'; // §3.4 mapping (applicationId)
const NOTIFICATION_TITLE = 'Accountability Check-in';
const NOTIFICATION_BODY = 'Your partner reached out — check in when you can.';
// `_v3`: Android cannot raise an existing channel's importance in place, and an app can even
// silently LOWER its own channel (device-confirmed 2026-10-04 — the pre-fix client dented v2
// 4→3 on foreground receipts), so a dented id is retired and a fresh one takes HIGH.
const CHANNEL_ID = 'partner_nudges_v3';
const NOTIFICATION_TAG = 'partner_nudge';
const FCM_TTL_MS = 3_600_000;

// Deviation from the plan's FID-targeted send, flagged 2026-10-04: FCM FID targeting only
// addresses installations that re-registered via register()/onRegistered() (FCM SDK 25.1+);
// with this app's legacy getToken() path every FID send returns installation-id-not-registered
// on the rig while the same installations' full registration tokens deliver. Token targeting
// is still supported during FCM's deprecation window; revisit when the client migrates.
function nudgeFcmMessage(tokens, nudgeId) {
  return {
    tokens,
    notification: { title: NOTIFICATION_TITLE, body: NOTIFICATION_BODY },
    data: { type: 'partner_nudge', nudge_id: nudgeId },
    android: {
      priority: 'high',
      ttl: FCM_TTL_MS,
      restrictedPackageName: ANDROID_PACKAGE,
      notification: {
        channelId: CHANNEL_ID,
        tag: NOTIFICATION_TAG,
        visibility: 'private',
      },
    },
  };
}

/**
 * `now`/`rand` are injectable for deterministic tests (house seam pattern; the emulator
 * suite drives lease expiry by advancing `now`).
 */
function createPushNudge({ db, messaging, now = () => Date.now(), rand = Math.random }) {
  const queueRefOf = (nudgeId) => db.doc(`nudgePushQueue/${nudgeId}`);
  const nudgeRefOf = (pairId, nudgeId) => db.doc(`pairings/${pairId}/nudges/${nudgeId}`);
  const deviceRefOf = (uid, installId) => db.doc(`users/${uid}/push_installations/${installId}`);

  /** §8.6 lease acquisition — atomic; returns the claim or why dispatch must stop. */
  function claimLease(pairId, nudgeId, nowMs) {
    return db.runTransaction(async (tx) => {
      const queueRef = queueRefOf(nudgeId);
      const queueSnap = await tx.get(queueRef);
      if (!queueSnap.exists) {
        // §9.2 invariant: a queue is deleted only alongside a terminal nudge (same
        // transaction), so an absent queue under a still-pending nudge means the durable
        // record was lost (e.g. TTL swept it while retries were stalled). Never silently
        // ignored — the caller emits `nudge_queue_missing` — and repaired by recreating
        // the queue document. The doc id is the nudge id, so concurrent repairs collapse
        // onto one document and the creation re-fires onDocumentCreated.
        const nudgeSnap = await tx.get(nudgeRefOf(pairId, nudgeId));
        if (!nudgeSnap.exists || isTerminalStatus(nudgeSnap.get('status'))) {
          return { outcome: 'missing' };
        }
        const nudge = nudgeSnap.data();
        tx.set(queueRef, {
          pairId,
          createdAtMs: nowMs,
          expiresAt: nudge.expiresAt || null,
          nextAttemptAtMs: nowMs,
          // Repair is not a fresh budget: preserve the attempts already spent.
          attemptCount: typeof nudge.pushAttempts === 'number' ? nudge.pushAttempts : 0,
          leaseId: null,
          leaseUntilMs: null,
          targetInstallIds: [],
          sentInstallIds: [],
        });
        return { outcome: 'queue_repaired' };
      }
      const nudgeSnap = await tx.get(nudgeRefOf(pairId, nudgeId));
      if (!nudgeSnap.exists) {
        // Queue without its nudge: invariant violation, remove the orphan rather than send.
        tx.delete(queueRef);
        return { outcome: 'orphan_deleted' };
      }
      const nudge = nudgeSnap.data();
      if (isTerminalStatus(nudge.status)) {
        // Already finalized by another worker: the queue is stale cleanup.
        tx.delete(queueRef);
        return { outcome: 'terminal_cleaned' };
      }
      const queue = queueSnap.data();
      if (leaseDecision({ queue, nowMs }) === 'not_owner') return { outcome: 'not_owner' };
      const leaseId = crypto.randomUUID();
      const attemptCount = (typeof queue.attemptCount === 'number' ? queue.attemptCount : 0) + 1;
      tx.update(queueRef, { leaseId, leaseUntilMs: nowMs + LEASE_MS, attemptCount });
      tx.update(nudgeRefOf(pairId, nudgeId), { status: 'pending', pushAttempts: attemptCount });
      return { outcome: 'claimed', leaseId, attemptCount, queue, nudge };
    });
  }

  /** §8.10: every post-send write is lease-checked; a stale worker writes nothing. */
  function leaseCheckedWrite(pairId, nudgeId, leaseId, apply) {
    return db.runTransaction(async (tx) => {
      const queueRef = queueRefOf(nudgeId);
      const queueSnap = await tx.get(queueRef);
      if (!queueSnap.exists || queueSnap.get('leaseId') !== leaseId) return false;
      apply(tx, queueRef, nudgeRefOf(pairId, nudgeId));
      return true;
    });
  }

  /** Terminal finalization: status + counts on the nudge, queue deleted, same transaction. */
  function finalizeTerminal(pairId, nudgeId, leaseId, patch) {
    return leaseCheckedWrite(pairId, nudgeId, leaseId, (tx, queueRef, nudgeRef) => {
      tx.update(nudgeRef, patch);
      tx.delete(queueRef);
    });
  }

  /** §8.9: a confirmed invalid registration is deleted; never retried for any nudge. */
  async function removeInvalidRegistrations(recipientUid, installIds, nudgeId) {
    for (const installId of installIds) {
      try {
        await deviceRefOf(recipientUid, installId).delete();
      } catch (err) {
        logger.warn('nudge_push_result', {
          nudgeHash: hashForLog(nudgeId),
          installHash: hashForLog(installId),
          outcome: 'invalid_registration_delete_failed',
        });
      }
    }
  }

  async function pushNudge(pairId, nudgeId) {
    const nowMs = now();
    const claim = await claimLease(pairId, nudgeId, nowMs);
    if (claim.outcome === 'queue_repaired') {
      // §9.2/§14.1: invariant violation surfaced as an alertable event; dispatch resumes
      // via the recreated queue's creation event or the next repair sweep.
      logger.error('nudge_queue_missing', { nudgeHash: hashForLog(nudgeId) });
      return claim;
    }
    if (claim.outcome !== 'claimed') return claim;

    const { leaseId, attemptCount, nudge } = claim;
    const recipientUid = nudge.recipientUid;
    logger.info('nudge_dispatch_claimed', {
      nudgeHash: hashForLog(nudgeId),
      attempt: attemptCount,
    });

    // §6.4 step 4-5: pair + block state read AFTER lease acquisition; suppression is
    // silent from the sender's side by design (server-side suppression, AD-16).
    const [pairSnap, settingsSnap] = await Promise.all([
      db.doc(`pairings/${pairId}`).get(),
      db.doc(`pairings/${pairId}/nudgeSettings/${recipientUid}`).get(),
    ]);
    const pairActive = pairSnap.exists && pairSnap.get('status') === 'active';
    const blocked = settingsSnap.exists && settingsSnap.get('blocked') === true;
    if (!pairActive || blocked) {
      const wrote = await finalizeTerminal(pairId, nudgeId, leaseId, {
        status: 'suppressed',
        suppressedAt: FieldValue.serverTimestamp(),
        terminalAt: FieldValue.serverTimestamp(),
      });
      if (wrote) {
        logger.info('nudge_suppressed', {
          nudgeHash: hashForLog(nudgeId),
          reason: blocked ? 'recipient_blocked' : 'pair_inactive',
        });
      }
      return { outcome: 'suppressed' };
    }

    // §6.6: a queue that outlived the nudge's `expiresAt` is finalized, never dispatched.
    // Suppressed states were already handled above, so "according to current state" leaves
    // a live unblocked pair: the nudge is a terminal failure and nothing goes on the wire.
    if (isNudgeExpired(nudge.expiresAt, nowMs)) {
      const wrote = await finalizeTerminal(pairId, nudgeId, leaseId, {
        status: 'failed',
        terminalAt: FieldValue.serverTimestamp(),
      });
      if (wrote) {
        logger.info('nudge_status_final', {
          nudgeHash: hashForLog(nudgeId),
          status: 'failed',
          reason: 'expired',
          attempt: attemptCount,
        });
      }
      return { outcome: wrote ? 'failed' : 'lease_lost' };
    }

    // §6.4 steps 6-7: target snapshot on first dispatch only (§5 AD-10 — later device
    // registrations never join an already-accepted nudge).
    let targets = Array.isArray(claim.queue.targetInstallIds) ? claim.queue.targetInstallIds : [];
    if (targets.length === 0) {
      const devices = await db
        .collection(`users/${recipientUid}/push_installations`)
        .where('pushEligible', '==', true)
        .get();
      targets = devices.docs.map((d) => d.id);
      const wrote = await leaseCheckedWrite(pairId, nudgeId, leaseId, (tx, queueRef, nudgeRef) => {
        tx.update(queueRef, { targetInstallIds: targets });
        tx.update(nudgeRef, { targetDeviceCount: targets.length });
      });
      if (!wrote) return { outcome: 'lease_lost' };
      if (targets.length === 0) {
        const finalized = await finalizeTerminal(pairId, nudgeId, leaseId, {
          status: 'no_eligible_device',
          terminalAt: FieldValue.serverTimestamp(),
          targetDeviceCount: 0,
        });
        if (finalized) {
          logger.info('nudge_status_final', {
            nudgeHash: hashForLog(nudgeId),
            status: 'no_eligible_device',
            targetCount: 0,
            sentCount: 0,
            attempt: attemptCount,
          });
        }
        return { outcome: 'no_eligible_device' };
      }
    }

    // §6.4 steps 8-11: pending = snapshot - sent; vanished/ineligible devices are dropped
    // terminally for this nudge (they can never be re-added).
    const alreadySent = Array.isArray(claim.queue.sentInstallIds) ? claim.queue.sentInstallIds : [];
    const pendingIds = targets.filter((id) => !alreadySent.includes(id));
    const deviceSnaps = await Promise.all(
      pendingIds.map((id) => deviceRefOf(recipientUid, id).get()),
    );
    // §6.4 step 11: the doc id is the FID (§4.3 registry delta) and stays the ledger key;
    // the FCM call targets the stored registration token (see nudgeFcmMessage deviation note).
    const eligible = pendingIds
      .map((id, i) => ({ fid: id, snap: deviceSnaps[i] }))
      .filter((t) => t.snap.exists && t.snap.get('pushEligible') === true);
    const newlySent = [];
    const invalidInstallIds = [];
    // An eligible row with no token can never be addressed — same terminal class as an FCM
    // invalid target, so it rides the same removal path.
    const sendable = [];
    for (const t of eligible) {
      if (typeof t.snap.get('token') === 'string' && t.snap.get('token').length > 0) sendable.push(t);
      else invalidInstallIds.push(t.fid);
    }
    let transientCount = 0;
    let firstErrorCode = null;
    let payloadInvalid = false;

    for (let i = 0; i < sendable.length; i += FCM_FID_BATCH_MAX) {
      const batch = sendable.slice(i, i + FCM_FID_BATCH_MAX);
      try {
        const response = await messaging.sendEachForMulticast(
          nudgeFcmMessage(batch.map((t) => t.snap.get('token')), nudgeId),
        );
        response.responses.forEach((result, idx) => {
          if (result.success) {
            newlySent.push(batch[idx].fid);
            return;
          }
          const kind = classifyFcmError(result.error);
          if (kind === 'invalid_target') invalidInstallIds.push(batch[idx].fid);
          else if (kind === 'payload') payloadInvalid = true;
          else transientCount += 1;
          if (!firstErrorCode) firstErrorCode = normalizeFcmCode(result.error);
        });
      } catch (err) {
        // Request-level failure (auth, quota, the API being down, a bad payload): the whole
        // batch is unresolved. Payload-class failures are engineering defects (§8.9).
        const kind = classifyFcmError(err);
        if (kind === 'payload') payloadInvalid = true;
        else transientCount += batch.length;
        if (!firstErrorCode) firstErrorCode = normalizeFcmCode(err);
      }
    }

    await removeInvalidRegistrations(recipientUid, invalidInstallIds, nudgeId);

    const sentCount = alreadySent.length + newlySent.length;
    const decision = decideFinalization({
      attemptCount,
      targetCount: targets.length,
      sentCount,
      transientCount,
      payloadInvalid,
      rand,
    });

    logger.info('nudge_push_result', {
      nudgeHash: hashForLog(nudgeId),
      attempt: attemptCount,
      targetCount: targets.length,
      sentCount,
      transientCount,
      invalidCount: invalidInstallIds.length,
      errorCode: firstErrorCode,
      outcome: decision.action,
    });

    if (decision.action === 'retry') {
      const nextAttemptAtMs = nowMs + decision.delayMs;
      const wrote = await leaseCheckedWrite(pairId, nudgeId, leaseId, (tx, queueRef, nudgeRef) => {
        const queuePatch = { leaseId: null, leaseUntilMs: null, nextAttemptAtMs };
        if (newlySent.length > 0) queuePatch.sentInstallIds = FieldValue.arrayUnion(...newlySent);
        tx.update(queueRef, queuePatch);
        tx.update(nudgeRef, {
          targetDeviceCount: targets.length,
          sentDeviceCount: sentCount,
          lastPushAtMs: nowMs,
          lastPushErrorCode: firstErrorCode,
        });
      });
      return { outcome: wrote ? 'retry_scheduled' : 'lease_lost' };
    }

    const wrote = await finalizeTerminal(pairId, nudgeId, leaseId, {
      status: decision.status,
      terminalAt: FieldValue.serverTimestamp(),
      targetDeviceCount: targets.length,
      sentDeviceCount: sentCount,
      lastPushAtMs: nowMs,
      lastPushErrorCode: firstErrorCode,
    });
    if (wrote) {
      if (payloadInvalid) {
        logger.error('nudge_push_payload_invalid', {
          nudgeHash: hashForLog(nudgeId),
          errorCode: firstErrorCode,
        });
      }
      if (decision.stuck) {
        logger.warn('nudge_stuck', {
          nudgeHash: hashForLog(nudgeId),
          attempt: attemptCount,
          targetCount: targets.length,
          sentCount,
          errorCode: firstErrorCode,
        });
      }
      logger.info('nudge_status_final', {
        nudgeHash: hashForLog(nudgeId),
        status: decision.status,
        targetCount: targets.length,
        sentCount,
        attempt: attemptCount,
      });
    }
    return { outcome: wrote ? decision.status : 'lease_lost' };
  }

  return pushNudge;
}

/**
 * §8.5: Firestore `onDocumentCreated` on `/nudgePushQueue/{nudgeId}`. Duplicate delivery is
 * tolerable — `claimLease` makes only one worker the sender; a missing document (already
 * finalized) is a plain no-op.
 */
function createOnNudgeQueueCreated({ pushNudge }) {
  return onDocumentCreated(
    {
      document: 'nudgePushQueue/{nudgeId}',
      region: REGION,
      memory: '256MiB',
      timeoutSeconds: 60,
      maxInstances: 20,
    },
    async (event) => {
      const snap = event.data;
      if (!snap) return;
      const pairId = snap.get('pairId');
      if (typeof pairId !== 'string' || pairId.length === 0) {
        logger.warn('nudge_push_result', {
          nudgeHash: hashForLog(snap.id),
          outcome: 'malformed_queue_document',
        });
        return;
      }
      try {
        await pushNudge(pairId, snap.id);
      } catch (err) {
        // §9.2 crash-during-dispatch: the lease expires and the repair scheduler reclaims
        // the queue — never let a transient error turn into an unbounded trigger retry storm.
        logger.error('nudge_push_result', {
          nudgeHash: hashForLog(snap.id),
          outcome: 'trigger_error',
          errorCode: err && err.code ? err.code : 'unknown',
        });
      }
    },
  );
}

module.exports = { createPushNudge, createOnNudgeQueueCreated, nudgeFcmMessage, ANDROID_PACKAGE };
