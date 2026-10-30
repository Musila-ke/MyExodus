'use strict';
// Account plan §6.8 / §7 step 16: pure helpers for the deletion callables and the cleanup
// worker — confirmation phrases, job phases, retention and validation. No Firestore, no
// Admin SDK, so `node --test` covers the rules without an emulator.

// §6.8.1: the phrase is typed into the UI and re-sent verbatim; the server never accepts a
// boolean "confirmed" flag.
const ACCOUNT_CONFIRMATION = 'DELETE MY ACCOUNT';
const GUEST_CONFIRMATION = 'DELETE MY DATA';

const KINDS = Object.freeze({
  ACCOUNT: 'ACCOUNT_DELETE',
  GUEST: 'GUEST_DELETE',
  GUEST_TRANSITION: 'GUEST_TRANSITION_CLEANUP',
  AUTH_BACKSTOP: 'AUTH_DELETION_BACKSTOP',
});

// §6.8.7: phases run strictly in this order; the job's `phase` field names the last phase
// that FINISHED (QUEUED means none has). A retried task skips every finished phase.
const PHASES = Object.freeze({
  QUEUED: 'QUEUED',
  PAIRS: 'PAIRS',
  STORAGE: 'STORAGE',
  PRIVATE: 'PRIVATE',
  PROFILE: 'PROFILE',
  AUTH: 'AUTH',
  COMPLETED: 'COMPLETED',
});
const PHASE_ORDER = Object.freeze([
  PHASES.QUEUED,
  PHASES.PAIRS,
  PHASES.STORAGE,
  PHASES.PRIVATE,
  PHASES.PROFILE,
  PHASES.AUTH,
  PHASES.COMPLETED,
]);

/** §6.8.8: the minimal tombstone survives 30 days, then the reconciler purges it. */
const TOMBSTONE_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;
/** A job untouched this long is considered lost (enqueue response dropped, worker stuck). */
const STALE_JOB_MS = 10 * 60 * 1000;
/** §6.8.10: unfinalized guest transitions older than the lease are alerted, never deleted. */
const STALE_TRANSITION_MS = 24 * 60 * 60 * 1000;
/** Requeue cap so a permanently failing job alerts instead of spinning forever. */
const MAX_REQUEUES = 12;

// The uid becomes a document id and a task payload field. Firebase uids are alphanumeric
// with `_`/`-`; rejecting everything else also rules out '/' path injection and '.', '..'.
const UID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;

function isValidUid(value) {
  return typeof value === 'string' && UID_PATTERN.test(value);
}

/** Exact field allowlist, same discipline as the guest-transition callables. */
function hasExactKeys(data, keys) {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) return false;
  const actual = Object.keys(data).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((k, i) => k === expected[i]);
}

function validateAccountDeletionInput(data) {
  if (!hasExactKeys(data, ['confirmation'])) return { ok: false, reason: 'INVALID_INPUT' };
  if (data.confirmation !== ACCOUNT_CONFIRMATION) return { ok: false, reason: 'CONFIRMATION_MISMATCH' };
  return { ok: true };
}

function validateGuestDeletionInput(data) {
  if (!hasExactKeys(data, ['confirmation'])) return { ok: false, reason: 'INVALID_INPUT' };
  if (data.confirmation !== GUEST_CONFIRMATION) return { ok: false, reason: 'CONFIRMATION_MISMATCH' };
  return { ok: true };
}

function phaseIndexOf(phase) {
  const index = PHASE_ORDER.indexOf(phase);
  return index < 0 ? 0 : index;
}

/** True when `target` still has to run: phases only ever move forward. */
function needsPhase(current, target) {
  return phaseIndexOf(current) < phaseIndexOf(target);
}

function isValidPhase(phase) {
  return PHASE_ORDER.includes(phase);
}

function isComplete(job) {
  return !!job && job.phase === PHASES.COMPLETED;
}

/** §6.8.8: only a completed tombstone ages out; pending jobs are never discarded. */
function shouldPurgeTombstone(job, nowMs) {
  if (!isComplete(job)) return false;
  const completedAtMs = job.completedAt && typeof job.completedAt.toMillis === 'function'
    ? job.completedAt.toMillis()
    : 0;
  return completedAtMs > 0 && nowMs - completedAtMs > TOMBSTONE_RETENTION_MS;
}

function shouldReconcile(job, nowMs) {
  if (!job || isComplete(job)) return false;
  if ((job.requeueCount || 0) >= MAX_REQUEUES) return false;
  const updatedAtMs = job.updatedAt && typeof job.updatedAt.toMillis === 'function'
    ? job.updatedAt.toMillis()
    : 0;
  return updatedAtMs === 0 || nowMs - updatedAtMs >= STALE_JOB_MS;
}

/** §6.8.5: the shared pair tombstone records why the relationship ended. */
function terminationReasonOf(kind) {
  return kind === KINDS.ACCOUNT ? 'ACCOUNT_DELETED' : 'GUEST_ACCOUNT_DELETION';
}

function isStaleTransition(record, nowMs) {
  if (!record || record.state !== 'PREPARED') return false;
  const createdAtMs = record.createdAt && typeof record.createdAt.toMillis === 'function'
    ? record.createdAt.toMillis()
    : 0;
  return createdAtMs > 0 && nowMs - createdAtMs > STALE_TRANSITION_MS;
}

module.exports = {
  ACCOUNT_CONFIRMATION,
  GUEST_CONFIRMATION,
  KINDS,
  PHASES,
  PHASE_ORDER,
  TOMBSTONE_RETENTION_MS,
  STALE_JOB_MS,
  STALE_TRANSITION_MS,
  MAX_REQUEUES,
  UID_PATTERN,
  isValidUid,
  validateAccountDeletionInput,
  validateGuestDeletionInput,
  phaseIndexOf,
  needsPhase,
  isValidPhase,
  isComplete,
  shouldPurgeTombstone,
  shouldReconcile,
  terminationReasonOf,
  isStaleTransition,
};
