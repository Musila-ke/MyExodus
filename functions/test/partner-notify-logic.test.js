'use strict';
// §8.6 Phase 5 notifier decisions: claim/coalesce/skip, backoff, outcome reconciliation and
// fanout materialization. Pure node checks — the Firestore wiring is exercised on-device.

const test = require('node:test');
const assert = require('node:assert/strict');

const {
  STATUS,
  LEASE_MS,
  MAX_ATTEMPTS,
  MAX_BACKOFF_MS,
  BASE_BACKOFF_MS,
  blockGroupId,
  claimDecision,
  backoffMs,
  reconcileOutcome,
  isLeaseExpired,
  fanoutWrites,
} = require('../partner-notify-logic');

const NOW = 1_800_000_000_000;

function job(overrides = {}) {
  return {
    status: STATUS.PENDING,
    attempts: 0,
    nextAttemptAt: NOW - 1,
    groupId: null,
    kind: 'RULE_CHANGE_ATTEMPT',
    ...overrides,
  };
}

test('claimDecision claims a due PENDING job and leases it', () => {
  const decision = claimDecision({ job: job(), window: null, now: NOW });
  assert.equal(decision.action, 'CLAIM');
  assert.equal(decision.attempts, 1);
  assert.equal(decision.leaseUntil, NOW + LEASE_MS);
  assert.equal(decision.coalescing, false);
});

test('claimDecision skips a job that is not due, already sent, or already leased', () => {
  assert.equal(claimDecision({ job: job({ nextAttemptAt: NOW + 1 }), window: null, now: NOW }).action, 'SKIP');
  assert.equal(claimDecision({ job: job({ status: STATUS.SENT_TO_FCM }), window: null, now: NOW }).action, 'SKIP');
  assert.equal(claimDecision({
    job: job({ status: STATUS.LEASED, leaseUntil: NOW + 10 }),
    window: null,
    now: NOW,
  }).action, 'SKIP');
});

test('claimDecision reclaims an expired lease', () => {
  const decision = claimDecision({
    job: job({ status: STATUS.LEASED, leaseUntil: NOW, attempts: 2 }),
    window: null,
    now: NOW,
  });
  assert.equal(decision.action, 'CLAIM');
  assert.equal(decision.attempts, 3);
});

test('BLOCK_ATTEMPT inside the rolling window coalesces into the window group', () => {
  const block = job({ kind: 'BLOCK_ATTEMPT', groupId: 'blk:a:b:1' });
  const decision = claimDecision({
    job: block,
    window: { lastPushAt: NOW - 30_000, groupId: 'blk:a:b:0' },
    now: NOW,
  });
  assert.equal(decision.action, 'COALESCE');
  assert.equal(decision.groupId, 'blk:a:b:0');
});

test('BLOCK_ATTEMPT outside the window claims; non-block kinds ignore the window', () => {
  const block = job({ kind: 'BLOCK_ATTEMPT', groupId: 'blk:a:b:1' });
  assert.equal(claimDecision({
    job: block,
    window: { lastPushAt: NOW - 60_000, groupId: 'blk:a:b:0' },
    now: NOW,
  }).action, 'CLAIM');

  const rule = job({ kind: 'RULE_CHANGE_ATTEMPT', groupId: null });
  assert.equal(claimDecision({
    job: rule,
    window: { lastPushAt: NOW - 1_000, groupId: 'x' },
    now: NOW,
  }).action, 'CLAIM');
});

test('a job at the attempt ceiling is claimed as exhausted, never sent', () => {
  const decision = claimDecision({ job: job({ attempts: MAX_ATTEMPTS }), window: null, now: NOW });
  assert.equal(decision.action, 'CLAIM');
  assert.equal(decision.exhausted, true);
});

test('backoff doubles from 30 s and caps at 30 minutes', () => {
  assert.equal(backoffMs(1), BASE_BACKOFF_MS);
  assert.equal(backoffMs(2), BASE_BACKOFF_MS * 2);
  assert.equal(backoffMs(8), 30 * 60 * 1000);
  assert.equal(backoffMs(20), MAX_BACKOFF_MS);
});

test('ACCEPTED marks SENT_TO_FCM with server time and keeps the window reservation', () => {
  const { patch, releaseWindow, pruneRegistration } =
    reconcileOutcome('ACCEPTED', { now: NOW, attempts: 1 });
  assert.equal(patch.status, STATUS.SENT_TO_FCM);
  assert.equal(patch.sentAt, NOW);
  assert.equal(releaseWindow, false);
  assert.equal(pruneRegistration, false);
});

test('NOT_PUSH_ELIGIBLE finishes the job without a push and releases the reservation', () => {
  const { patch, releaseWindow } = reconcileOutcome('NOT_PUSH_ELIGIBLE', { now: NOW, attempts: 1 });
  assert.equal(patch.status, STATUS.SENT_TO_FCM);
  assert.equal(patch.suppressed, 'NOT_PUSH_ELIGIBLE');
  assert.equal(releaseWindow, true);
});

test('an invalid registration is terminal and prunes the registration', () => {
  const { patch, pruneRegistration } = reconcileOutcome('INVALID_REGISTRATION', { now: NOW, attempts: 1 });
  assert.equal(patch.status, STATUS.FAILED_PERMANENT);
  assert.equal(pruneRegistration, true);
});

test('a missing registration fails the job permanently without touching the mailbox', () => {
  const { patch, pruneRegistration } = reconcileOutcome('REGISTRATION_UNAVAILABLE', { now: NOW, attempts: 1 });
  assert.equal(patch.status, STATUS.FAILED_PERMANENT);
  assert.equal(pruneRegistration, false);
});

test('a transient failure re-queues with backoff and releases the reservation', () => {
  const { patch, releaseWindow } = reconcileOutcome('SEND_FAILED', { now: NOW, attempts: 2 });
  assert.equal(patch.status, STATUS.PENDING);
  assert.equal(patch.nextAttemptAt, NOW + BASE_BACKOFF_MS * 2);
  assert.equal(releaseWindow, true);
});

test('a transient failure at the attempt ceiling is terminal', () => {
  const { patch } = reconcileOutcome('SEND_FAILED', { now: NOW, attempts: MAX_ATTEMPTS });
  assert.equal(patch.status, STATUS.FAILED_PERMANENT);
  assert.equal(patch.lastErrorCode, 'ATTEMPTS_EXHAUSTED');
});

test('isLeaseExpired only matches a LEASED row at or past its lease', () => {
  assert.equal(isLeaseExpired({ status: STATUS.LEASED, leaseUntil: NOW }, NOW), true);
  assert.equal(isLeaseExpired({ status: STATUS.LEASED, leaseUntil: NOW + 1 }, NOW), false);
  assert.equal(isLeaseExpired({ status: STATUS.PENDING, leaseUntil: NOW }, NOW), false);
});

test('fanoutWrites produces one mailbox and one job per recipient with AAD inputs', () => {
  const task = {
    eventId: 'evt-1',
    ownerUid: 'owner-1',
    installationId: 'install-owner',
    kind: 'BLOCK_ATTEMPT',
    createdAt: NOW - 5_000,
    recipients: [
      {
        recipientUid: 'uid-a',
        recipientInstallationId: 'inst-a',
        recipientKeyId: 'key-a',
        recipientKeyRevision: 3,
        payload: 'cipher',
        encoding: 'jcs+e2ee',
        pairId: 'pair-1',
      },
      {
        recipientUid: 'uid-b',
        recipientInstallationId: 'inst-b',
        recipientKeyId: 'key-b',
        recipientKeyRevision: 1,
        payload: 'cipher2',
        encoding: 'jcs+e2ee',
        pairId: 'pair-2',
      },
    ],
  };
  const collections = {
    mailboxes: 'partnerMailboxes',
    notificationOutbox: 'partnerNotificationOutbox',
  };

  const { writes, recipientCount } = fanoutWrites(task, collections, NOW);

  assert.equal(recipientCount, 2);
  assert.equal(writes.length, 4);
  const mailbox = writes[0];
  assert.equal(mailbox.path, 'partnerMailboxes/uid-a/messages/evt-1__inst-a');
  assert.equal(mailbox.data.installationId, 'install-owner');
  assert.equal(mailbox.data.kind, 'BLOCK_ATTEMPT');
  assert.equal(mailbox.data.recipientInstallationId, 'inst-a');
  assert.equal(mailbox.data.createdAt, NOW - 5_000);
  const jobWrite = writes[1];
  assert.equal(jobWrite.path, 'partnerNotificationOutbox/evt-1__inst-a');
  assert.equal(jobWrite.data.status, STATUS.PENDING);
  assert.equal(jobWrite.data.nextAttemptAt, NOW);
  assert.equal(jobWrite.data.groupId, blockGroupId('uid-a', 'inst-a', NOW - 5_000));
});

test('fanoutWrites skips malformed recipient entries', () => {
  const task = {
    eventId: 'evt-2',
    ownerUid: 'owner-1',
    installationId: 'install-owner',
    kind: 'CHECK_IN',
    createdAt: NOW,
    recipients: [{ recipientUid: 'uid-a' }, null],
  };
  const { writes, recipientCount } = fanoutWrites(
    task,
    { mailboxes: 'm', notificationOutbox: 'n' },
    NOW,
  );
  assert.equal(writes.length, 0);
  assert.equal(recipientCount, 0);
});
