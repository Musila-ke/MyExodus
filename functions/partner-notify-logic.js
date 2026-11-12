'use strict';
// §8.6 partner notification decisions, pure and node-tested. One job per (event, recipient
// installation); every job is claimed through the caller's Firestore transaction, and the
// rolling 60 s window is only written by a winner, so two concurrent trigger instances
// cannot both push to the same installation.

const STATUS = Object.freeze({
  PENDING: 'PENDING',
  LEASED: 'LEASED',
  SENT_TO_FCM: 'SENT_TO_FCM',
  FAILED_PERMANENT: 'FAILED_PERMANENT',
});

const LEASE_MS = 30 * 1000;
const COALESCE_WINDOW_MS = 60 * 1000;
const MAX_ATTEMPTS = 8;
const BASE_BACKOFF_MS = 30 * 1000;
const MAX_BACKOFF_MS = 30 * 60 * 1000;

const BLOCK_KIND = 'BLOCK_ATTEMPT';
const BLOCK_GROUP_MS = 60 * 1000;

function blockGroupId(recipientUid, installationId, at) {
  return `blk:${recipientUid}:${installationId}:${Math.floor(at / BLOCK_GROUP_MS)}`;
}

/**
 * SKIP: nothing to do (already handled, or not due yet).
 * COALESCE: a push already went to this installation inside the rolling window, so this
 *   job is finished without sending; the mailbox record stays and the client dedupes.
 * CLAIM: lease the job; the caller writes the window reservation in the same transaction,
 *   which is what serializes concurrent claims per installation.
 */
function claimDecision({ job, window, now }) {
  if (!job) return { action: 'SKIP' };
  // An expired LEASE is reclaimable: the previous process was killed mid-send.
  if (job.status !== STATUS.PENDING && !isLeaseExpired(job, now)) return { action: 'SKIP' };
  if (typeof job.nextAttemptAt === 'number' && job.nextAttemptAt > now) return { action: 'SKIP' };
  const attempts = Number.isInteger(job.attempts) ? job.attempts : 0;
  if (attempts >= MAX_ATTEMPTS) {
    return { action: 'CLAIM', attempts: attempts + 1, leaseUntil: now + LEASE_MS, coalescing: false, exhausted: true };
  }
  const coalescing = job.groupId !== null && job.groupId !== undefined;
  if (coalescing && window && typeof window.lastPushAt === 'number' &&
      now - window.lastPushAt < COALESCE_WINDOW_MS) {
    return { action: 'COALESCE', groupId: typeof window.groupId === 'string' ? window.groupId : null };
  }
  return {
    action: 'CLAIM',
    attempts: attempts + 1,
    leaseUntil: now + LEASE_MS,
    coalescing,
    exhausted: false,
  };
}

function backoffMs(attempt) {
  const exponent = Math.min(Math.max(attempt - 1, 0), 20);
  return Math.min(BASE_BACKOFF_MS * (2 ** exponent), MAX_BACKOFF_MS);
}

/**
 * Maps a send outcome to the job patch. `accepted` means FCM took the message; everything
 * else either retries or ends the job. The mailbox record is never touched by any outcome.
 */
function reconcileOutcome(outcome, { now, attempts }) {
  switch (outcome) {
    case 'ACCEPTED':
      return {
        patch: { status: STATUS.SENT_TO_FCM, sentAt: now, leaseUntil: null, lastErrorCode: null },
        releaseWindow: false,
        pruneRegistration: false,
      };
    case 'NOT_PUSH_ELIGIBLE':
      // §8.6: permission denied retains the mailbox; the push is simply not retried.
      return {
        patch: {
          status: STATUS.SENT_TO_FCM,
          sentAt: now,
          leaseUntil: null,
          suppressed: 'NOT_PUSH_ELIGIBLE',
        },
        releaseWindow: true,
        pruneRegistration: false,
      };
    case 'INVALID_REGISTRATION':
      return {
        patch: {
          status: STATUS.FAILED_PERMANENT,
          terminalAt: now,
          leaseUntil: null,
          lastErrorCode: 'INVALID_REGISTRATION',
        },
        releaseWindow: true,
        pruneRegistration: true,
      };
    case 'REGISTRATION_UNAVAILABLE':
    case 'UNKNOWN_PROVIDER':
      return {
        patch: {
          status: STATUS.FAILED_PERMANENT,
          terminalAt: now,
          leaseUntil: null,
          lastErrorCode: outcome,
        },
        releaseWindow: true,
        pruneRegistration: false,
      };
    default: {
      // Transient. Once attempts are exhausted a push that never reached FCM is terminal;
      // the mailbox and its client-side retrieval still deliver the record.
      if (attempts >= MAX_ATTEMPTS) {
        return {
          patch: {
            status: STATUS.FAILED_PERMANENT,
            terminalAt: now,
            leaseUntil: null,
            lastErrorCode: 'ATTEMPTS_EXHAUSTED',
          },
          releaseWindow: true,
          pruneRegistration: false,
        };
      }
      return {
        patch: {
          status: STATUS.PENDING,
          nextAttemptAt: now + backoffMs(attempts),
          leaseUntil: null,
          lastErrorCode: 'SEND_FAILED',
        },
        releaseWindow: true,
        pruneRegistration: false,
      };
    }
  }
}

/** Expired leases are reclaimable; the claim itself re-checks status inside the transaction. */
function isLeaseExpired(job, now) {
  return job && job.status === STATUS.LEASED &&
    typeof job.leaseUntil === 'number' && job.leaseUntil <= now;
}

/**
 * A fanout task is materialized into one mailbox record and one notification job per
 * recipient, then marked DONE. Writes are capped at 2 × 200 recipients + 1, inside
 * Firestore's 500-write batch limit, so one task never needs a second pass.
 */
function fanoutWrites(task, collections, now) {
  const recipients = Array.isArray(task.recipients) ? task.recipients : [];
  const acceptedAt = typeof task.createdAt === 'number' ? task.createdAt : now;
  const writes = [];
  for (const entry of recipients) {
    if (!entry || typeof entry !== 'object' ||
        typeof entry.recipientUid !== 'string' ||
        typeof entry.recipientInstallationId !== 'string') continue;
    const messageId = `${task.eventId}__${entry.recipientInstallationId}`;
    writes.push({
      path: `${collections.mailboxes}/${entry.recipientUid}/messages/${messageId}`,
      data: {
        ownerUid: task.ownerUid,
        installationId: task.installationId,
        kind: task.kind,
        eventId: task.eventId,
        // §13.3: the sender's staged outbox sequence, needed to place the event in the
        // recipient's local sequence space. Written by ingest; 0 for a pre-field task.
        sequence: typeof task.sequence === 'number' ? task.sequence : 0,
        recipientInstallationId: entry.recipientInstallationId,
        recipientKeyId: entry.recipientKeyId,
        recipientKeyRevision: entry.recipientKeyRevision,
        payload: entry.payload,
        encoding: entry.encoding,
        pairId: entry.pairId,
        createdAt: acceptedAt,
      },
    });
    writes.push({
      path: `${collections.notificationOutbox}/${messageId}`,
      data: {
        eventId: task.eventId,
        recipientUid: entry.recipientUid,
        recipientInstallationId: entry.recipientInstallationId,
        kind: task.kind,
        groupId: task.kind === BLOCK_KIND
          ? blockGroupId(entry.recipientUid, entry.recipientInstallationId, acceptedAt)
          : null,
        status: STATUS.PENDING,
        attempts: 0,
        nextAttemptAt: now,
        leaseUntil: null,
        sentAt: null,
        createdAt: acceptedAt,
      },
    });
  }
  return { writes, recipientCount: writes.length / 2 };
}

module.exports = {
  STATUS,
  LEASE_MS,
  COALESCE_WINDOW_MS,
  MAX_ATTEMPTS,
  BASE_BACKOFF_MS,
  MAX_BACKOFF_MS,
  BLOCK_KIND,
  blockGroupId,
  claimDecision,
  backoffMs,
  reconcileOutcome,
  isLeaseExpired,
  fanoutWrites,
};
