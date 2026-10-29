'use strict';
// Account plan §7 Release 1 step 9 / §8.4 / §4.2: pure helpers for the guest-transition
// callables — input shapes, the capability hash, the lease state machine, the rolling
// preparation window and the recent-auth claim check. Kept free of Firestore so `node --test`
// covers the rules without an emulator.

const crypto = require('node:crypto');

// The transition ID becomes a document id, so '/' (path injection) and the reserved '.'/'..'
// ids must be impossible. The client sends 16 random bytes as unpadded base64url (22 chars);
// the range leaves room without accepting arbitrary strings.
const TRANSITION_ID_PATTERN = /^[A-Za-z0-9_-]{16,64}$/;

// §8.4: a 256-bit one-time capability, unpadded base64url — exactly 43 characters. The
// server stores only its SHA-256, never the value.
const CAPABILITY_PATTERN = /^[A-Za-z0-9_-]{43}$/;

const INTENTS = new Set(['IMPORT', 'DISCARD']);

const STATES = Object.freeze({
  PREPARED: 'PREPARED',
  EXPIRED_UNFINALIZED: 'EXPIRED_UNFINALIZED',
  FINALIZED: 'FINALIZED',
  CANCELLED: 'CANCELLED',
});

/** §4.2: the normal preparation lease. Expiry never mutates pairs or deletes the hash. */
const LEASE_MS = 24 * 60 * 60 * 1000;

/** §4.2: five preparation attempts per guest UID per rolling 24 hours. */
const PREPARE_LIMIT_WINDOW_MS = 24 * 60 * 60 * 1000;
const PREPARE_LIMIT_MAX = 5;

/** §8.4: an expired record may be finalized only after a fresh target reauthentication. */
const REAUTH_MAX_AGE_SECONDS = 300;

function isValidTransitionId(value) {
  return typeof value === 'string' && TRANSITION_ID_PATTERN.test(value);
}

function isValidCapability(value) {
  return typeof value === 'string' && CAPABILITY_PATTERN.test(value);
}

function isValidIntent(value) {
  return INTENTS.has(value);
}

/** §8.4: the server stores only the capability hash, bound to the transition id and uid. */
function capabilityHashOf(capability) {
  return crypto.createHash('sha256').update(capability, 'utf8').digest('hex');
}

/** Constant-time comparison of two hex-encoded hashes. */
function hashesEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  const left = Buffer.from(a, 'hex');
  const right = Buffer.from(b, 'hex');
  if (left.length === 0 || left.length !== right.length) return false;
  return crypto.timingSafeEqual(left, right);
}

/** Exact field allowlist — an unknown field is a malformed request, not something to ignore. */
function hasExactKeys(data, keys) {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) return false;
  const actual = Object.keys(data).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((k, i) => k === expected[i]);
}

function validatePrepareInput(data) {
  if (!hasExactKeys(data, ['transitionId', 'capability', 'intent'])) return { ok: false, reason: 'INVALID_INPUT' };
  if (!isValidTransitionId(data.transitionId)) return { ok: false, reason: 'INVALID_TRANSITION_ID' };
  if (!isValidCapability(data.capability)) return { ok: false, reason: 'INVALID_CAPABILITY' };
  if (!isValidIntent(data.intent)) return { ok: false, reason: 'INVALID_INTENT' };
  return { ok: true };
}

function validateFinalizeInput(data) {
  if (!hasExactKeys(data, ['transitionId', 'capability', 'intent'])) return { ok: false, reason: 'INVALID_INPUT' };
  if (!isValidTransitionId(data.transitionId)) return { ok: false, reason: 'INVALID_TRANSITION_ID' };
  if (!isValidCapability(data.capability)) return { ok: false, reason: 'INVALID_CAPABILITY' };
  if (!isValidIntent(data.intent)) return { ok: false, reason: 'INVALID_INTENT' };
  return { ok: true };
}

function validateCancelInput(data) {
  if (!hasExactKeys(data, ['transitionId', 'capability'])) return { ok: false, reason: 'INVALID_INPUT' };
  if (!isValidTransitionId(data.transitionId)) return { ok: false, reason: 'INVALID_TRANSITION_ID' };
  if (!isValidCapability(data.capability)) return { ok: false, reason: 'INVALID_CAPABILITY' };
  return { ok: true };
}

/**
 * §4.2: a lease that elapsed becomes EXPIRED_UNFINALIZED — not deleted, not finalized. The
 * transition is evaluated lazily whenever the record is read, so no scheduled job is needed
 * for correctness (the Release 3 reconciler only alerts on stale records).
 */
function effectiveState(state, expiresAtMs, nowMs) {
  if (state === STATES.PREPARED && nowMs >= expiresAtMs) return STATES.EXPIRED_UNFINALIZED;
  return state;
}

/**
 * §4.2: five preparation attempts per guest UID per rolling 24 hours. Stores the attempt
 * timestamps in the server-owned rate document; a replay of an existing transition never
 * reaches this function (it is detected first), so retries cannot exhaust the budget.
 * Timestamps in the future are discarded (clock-rollback guard, same spirit as AD-16).
 */
function advancePrepareWindow(data, nowMs) {
  const attempts = data && Array.isArray(data.attempts)
    ? data.attempts.filter((at) => typeof at === 'number' && at <= nowMs && nowMs - at < PREPARE_LIMIT_WINDOW_MS)
    : [];
  if (attempts.length >= PREPARE_LIMIT_MAX) {
    return { allowed: false, retryAfterMs: attempts[0] + PREPARE_LIMIT_WINDOW_MS - nowMs };
  }
  return { allowed: true, window: { attempts: [...attempts, nowMs] } };
}

/**
 * §8.4: server-verified `auth_time` no older than five minutes. The claim is the original
 * authentication time (not the token issue time), so `getIdToken(true)` alone never qualifies;
 * the client must actually reauthenticate with the provider. A missing claim fails closed.
 */
function isRecentAuthentication(token, nowSeconds) {
  const authTime = token && token.auth_time;
  if (typeof authTime !== 'number' || !Number.isFinite(authTime)) return false;
  const age = nowSeconds - authTime;
  return age <= REAUTH_MAX_AGE_SECONDS && age >= -60;
}

/** §10.7: prepare/finalize need the right session kind; a missing claim fails closed. */
function signInProviderOf(auth) {
  return (auth && auth.token && auth.token.firebase && auth.token.firebase.sign_in_provider) || undefined;
}

module.exports = {
  TRANSITION_ID_PATTERN,
  CAPABILITY_PATTERN,
  STATES,
  LEASE_MS,
  PREPARE_LIMIT_WINDOW_MS,
  PREPARE_LIMIT_MAX,
  REAUTH_MAX_AGE_SECONDS,
  isValidTransitionId,
  isValidCapability,
  isValidIntent,
  capabilityHashOf,
  hashesEqual,
  validatePrepareInput,
  validateFinalizeInput,
  validateCancelInput,
  effectiveState,
  advancePrepareWindow,
  isRecentAuthentication,
  signInProviderOf,
};
