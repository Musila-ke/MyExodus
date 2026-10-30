'use strict';
// Account plan §6.8 / §7 step 16: pure rules for deletion. No emulator.

const { test } = require('node:test');
const assert = require('node:assert');

const {
  ACCOUNT_CONFIRMATION,
  GUEST_CONFIRMATION,
  KINDS,
  PHASES,
  TOMBSTONE_RETENTION_MS,
  STALE_JOB_MS,
  STALE_TRANSITION_MS,
  MAX_REQUEUES,
  isValidUid,
  validateAccountDeletionInput,
  validateGuestDeletionInput,
  needsPhase,
  isValidPhase,
  isComplete,
  shouldPurgeTombstone,
  shouldReconcile,
  terminationReasonOf,
  isStaleTransition,
} = require('../account-deletion-logic');

const NOW = Date.UTC(2026, 10, 1, 12, 0, 0);
const at = (ms) => ({ toMillis: () => ms });

test('uid validation accepts Firebase uids and rejects path-like input', () => {
  assert.ok(isValidUid('abcDEF123_-'));
  assert.ok(!isValidUid(''));
  assert.ok(!isValidUid('a/b'));
  assert.ok(!isValidUid('..'));
  assert.ok(!isValidUid('a'.repeat(129)));
  assert.ok(!isValidUid(null));
});

test('the confirmation phrase is exact and the payload has no extra keys', () => {
  assert.deepStrictEqual(validateAccountDeletionInput({ confirmation: ACCOUNT_CONFIRMATION }), { ok: true });
  assert.strictEqual(validateAccountDeletionInput({ confirmation: GUEST_CONFIRMATION }).reason, 'CONFIRMATION_MISMATCH');
  assert.strictEqual(validateAccountDeletionInput({ confirmation: 'delete my account' }).reason, 'CONFIRMATION_MISMATCH');
  assert.strictEqual(validateAccountDeletionInput({ confirmation: 'yes' }).reason, 'CONFIRMATION_MISMATCH');
  assert.strictEqual(
    validateAccountDeletionInput({ confirmation: ACCOUNT_CONFIRMATION, force: true }).reason,
    'INVALID_INPUT',
  );
  assert.strictEqual(validateAccountDeletionInput(null).reason, 'INVALID_INPUT');
  assert.strictEqual(validateAccountDeletionInput([]).reason, 'INVALID_INPUT');

  assert.deepStrictEqual(validateGuestDeletionInput({ confirmation: GUEST_CONFIRMATION }), { ok: true });
  assert.strictEqual(validateGuestDeletionInput({ confirmation: ACCOUNT_CONFIRMATION }).reason, 'CONFIRMATION_MISMATCH');
});

test('phases only move forward, and COMPLETED is never re-run', () => {
  assert.ok(needsPhase(PHASES.QUEUED, PHASES.PAIRS));
  assert.ok(needsPhase(PHASES.PAIRS, PHASES.STORAGE));
  assert.ok(!needsPhase(PHASES.PRIVATE, PHASES.STORAGE));
  assert.ok(!needsPhase(PHASES.COMPLETED, PHASES.AUTH));
  // An unknown phase fails open into a full (idempotent) cleanup pass rather than skipping.
  assert.ok(needsPhase('unknown', PHASES.PAIRS));
  assert.ok(isValidPhase(PHASES.COMPLETED));
  assert.ok(!isValidPhase('RUNNING'));
  assert.ok(isComplete({ phase: PHASES.COMPLETED }));
  assert.ok(!isComplete({ phase: PHASES.AUTH }));
  assert.ok(!isComplete(null));
});

test('a completed tombstone is purged after 30 days only', () => {
  const completed = { phase: PHASES.COMPLETED, completedAt: at(NOW - TOMBSTONE_RETENTION_MS - 1) };
  const fresh = { phase: PHASES.COMPLETED, completedAt: at(NOW - TOMBSTONE_RETENTION_MS + 60_000) };
  const pending = { phase: PHASES.AUTH, completedAt: at(NOW - TOMBSTONE_RETENTION_MS - 1) };
  assert.ok(shouldPurgeTombstone(completed, NOW));
  assert.ok(!shouldPurgeTombstone(fresh, NOW));
  assert.ok(!shouldPurgeTombstone(pending, NOW));
  assert.ok(!shouldPurgeTombstone({ phase: PHASES.COMPLETED }, NOW));
});

test('reconciliation re-enqueues stale pending jobs and stops after the cap', () => {
  const stale = { phase: PHASES.PAIRS, updatedAt: at(NOW - STALE_JOB_MS - 1) };
  const fresh = { phase: PHASES.PAIRS, updatedAt: at(NOW - STALE_JOB_MS + 1000) };
  const missing = { phase: PHASES.QUEUED };
  const capped = { phase: PHASES.PAIRS, updatedAt: at(0), requeueCount: MAX_REQUEUES };
  const done = { phase: PHASES.COMPLETED, updatedAt: at(0) };
  assert.ok(shouldReconcile(stale, NOW));
  assert.ok(!shouldReconcile(fresh, NOW));
  assert.ok(shouldReconcile(missing, NOW));
  assert.ok(!shouldReconcile(capped, NOW));
  assert.ok(!shouldReconcile(done, NOW));
  assert.ok(!shouldReconcile(null, NOW));
});

test('pair termination reasons distinguish account from guest deletion', () => {
  assert.strictEqual(terminationReasonOf(KINDS.ACCOUNT), 'ACCOUNT_DELETED');
  assert.strictEqual(terminationReasonOf(KINDS.GUEST), 'GUEST_ACCOUNT_DELETION');
  assert.strictEqual(terminationReasonOf(KINDS.GUEST_TRANSITION), 'GUEST_ACCOUNT_DELETION');
  assert.strictEqual(terminationReasonOf(KINDS.AUTH_BACKSTOP), 'GUEST_ACCOUNT_DELETION');
});

test('only old unfinalized transitions count as stale', () => {
  assert.ok(isStaleTransition({ state: 'PREPARED', createdAt: at(NOW - STALE_TRANSITION_MS - 1) }, NOW));
  assert.ok(!isStaleTransition({ state: 'PREPARED', createdAt: at(NOW - STALE_TRANSITION_MS + 1000) }, NOW));
  assert.ok(!isStaleTransition({ state: 'FINALIZED', createdAt: at(0) }, NOW));
  assert.ok(!isStaleTransition({ state: 'PREPARED' }, NOW));
  assert.ok(!isStaleTransition(null, NOW));
});
