'use strict';
// Partner SOS dispatch pure decisions (plan §4.5/§4.6/§6.4/§6.5, §8.6-§8.10): lease
// claimability, FCM error classification, retry backoff, terminal-state decision. No
// Firestore and no FCM here — `partner-sos-push.js` owns the transactions and the send.

const LEASE_MS = 90_000; // §4.5: lease duration is 90 seconds
const MAX_PUSH_ATTEMPTS = 4; // §6.5: four dispatch claims, not four RPC retries
const BACKOFF_BASE_MS = 60_000; // attempt 1 failure: >= 60s; 120s; 240s; then terminal
const JITTER_RATIO = 0.25; // §6.5: +/- 25% on every delay
const FCM_FID_BATCH_MAX = 500; // §8.8

const TERMINAL_STATUSES = ['sent', 'partial', 'suppressed', 'no_eligible_device', 'failed'];

function isTerminalStatus(status) {
  return TERMINAL_STATUSES.includes(status);
}

/**
 * §4.5: a dispatcher with an unexpired lease it does not own must not send. An expired
 * lease (or none) is claimable; a missing queue is the trigger's duplicate-delivery case.
 */
function leaseDecision({ queue, nowMs }) {
  if (!queue) return 'missing';
  if (queue.leaseId && typeof queue.leaseUntilMs === 'number' && queue.leaseUntilMs > nowMs) {
    return 'not_owner';
  }
  return 'claimable';
}

/** §6.5 schedule with +/-25% jitter; `rand` injectable for deterministic tests. */
function backoffDelayMs(attemptCount, rand = Math.random) {
  const base = BACKOFF_BASE_MS * Math.pow(2, Math.max(0, attemptCount - 1));
  return Math.round(base * (1 + (rand() * 2 - 1) * JITTER_RATIO));
}

/**
 * §8.9 taxonomy, over the SDK's per-message/per-request error codes (the `messaging/`
 * prefix is stripped by `normalizeFcmCode` before this runs):
 *  - invalid target: delete the registration, terminal for that target;
 *  - quota/rate: transient, >= 60s floor (caller takes max with the backoff);
 *  - payload/engineering: never blind-retried, fails the nudge;
 *  - everything else (server unavailable/internal/unknown/auth): transient.
 */
const INVALID_TARGET_CODES = [
  'registration-token-not-registered',
  'installation-id-not-registered',
  'invalid-registration-token',
  'invalid-recipient',
];
const QUOTA_CODES = [
  'message-rate-exceeded',
  'device-message-rate-exceeded',
  'topics-message-rate-exceeded',
  'topics-subscription-rate-exceeded',
];
const PAYLOAD_CODES = [
  'invalid-payload',
  'invalid-data-payload-key',
  'payload-size-limit-exceeded',
  'invalid-argument',
  'invalid-options',
  'invalid-package-name',
];

function normalizeFcmCode(error) {
  if (!error) return null;
  const raw = (error.code || (error.info && error.info.code) || '').toString();
  return raw.replace(/^messaging\//, '') || null;
}

function classifyFcmError(error) {
  const code = normalizeFcmCode(error);
  if (code && INVALID_TARGET_CODES.includes(code)) return 'invalid_target';
  if (code && QUOTA_CODES.includes(code)) return 'quota';
  if (code && PAYLOAD_CODES.includes(code)) return 'payload';
  return 'transient';
}

/**
 * §6.4 steps 20-24 in one decision: given the dispatch outcome, either terminate the nudge
 * (delete the queue) or schedule a retry. `transientCount` is targets still pending after
 * this attempt; `payloadInvalid` is the engineering-failure flag for the whole nudge.
 * Caller guarantees targetCount >= 1 (zero targets is the separate no_eligible_device path).
 */
function decideFinalization({ attemptCount, targetCount, sentCount, transientCount, payloadInvalid, rand }) {
  if (payloadInvalid) {
    return { action: 'terminal', status: 'failed', stuck: false, reason: 'payload_invalid' };
  }
  if (transientCount === 0) {
    const status = sentCount === targetCount ? 'sent' : sentCount > 0 ? 'partial' : 'failed';
    return { action: 'terminal', status, stuck: false };
  }
  if (attemptCount >= MAX_PUSH_ATTEMPTS) {
    // Budget exhausted with names still pending: terminal, favouring what did land.
    return {
      action: 'terminal',
      status: sentCount > 0 ? 'partial' : 'failed',
      stuck: true,
      reason: 'attempt_budget_exhausted',
    };
  }
  return { action: 'retry', delayMs: backoffDelayMs(attemptCount, rand) };
}

/**
 * §6.6: a queue that outlived the nudge's `expiresAt` is finalized, never dispatched. A
 * missing `expiresAt` is treated as not expired — an unknown value must not silently drop
 * pending work. Accepts a Firestore Timestamp or an epoch-ms number.
 */
function isNudgeExpired(expiresAtValue, nowMs) {
  if (expiresAtValue === null || expiresAtValue === undefined) return false;
  const ms = typeof expiresAtValue.toMillis === 'function'
    ? expiresAtValue.toMillis()
    : Number(expiresAtValue);
  return Number.isFinite(ms) && ms <= nowMs;
}

module.exports = {
  LEASE_MS,
  MAX_PUSH_ATTEMPTS,
  BACKOFF_BASE_MS,
  FCM_FID_BATCH_MAX,
  TERMINAL_STATUSES,
  isTerminalStatus,
  leaseDecision,
  backoffDelayMs,
  classifyFcmError,
  normalizeFcmCode,
  decideFinalization,
  isNudgeExpired,
};
