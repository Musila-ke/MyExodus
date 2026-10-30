'use strict';
// Account plan §8.4 / §4.2: pure rules for the guest transition. No emulator.

const { test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const {
  STATES,
  LEASE_MS,
  PREPARE_LIMIT_MAX,
  PREPARE_LIMIT_WINDOW_MS,
  isValidTransitionId,
  isValidCapability,
  isValidIntent,
  validatePrepareInput,
  validateFinalizeInput,
  validateCancelInput,
  capabilityHashOf,
  hashesEqual,
  effectiveState,
  advancePrepareWindow,
  isRecentAuthentication,
  signInProviderOf,
} = require('../guest-transition-logic');

const ID = 'AbCdEfGhIjKlMnOpQrStUv'; // 22 chars, base64url alphabet
const CAP = crypto.randomBytes(32).toString('base64url'); // exactly 43 chars
const NOW = Date.UTC(2026, 9, 29, 12, 0, 0);

test('transition id: accepts 16-64 base64url chars and rejects path shapes', () => {
  assert.strictEqual(isValidTransitionId(ID), true);
  assert.strictEqual(isValidTransitionId('a'.repeat(16)), true);
  assert.strictEqual(isValidTransitionId('a'.repeat(64)), true);
  assert.strictEqual(isValidTransitionId('a'.repeat(15)), false);
  assert.strictEqual(isValidTransitionId('a'.repeat(65)), false);
  assert.strictEqual(isValidTransitionId('../guestTransitions'), false);
  assert.strictEqual(isValidTransitionId('abc/def'), false);
  assert.strictEqual(isValidTransitionId('.'), false);
  assert.strictEqual(isValidTransitionId('..'), false);
  assert.strictEqual(isValidTransitionId(42), false);
  assert.strictEqual(isValidTransitionId(null), false);
});

test('capability: exactly 43 base64url characters, nothing else', () => {
  assert.strictEqual(CAP.length, 43);
  assert.strictEqual(isValidCapability(CAP), true);
  assert.strictEqual(isValidCapability(CAP.slice(0, 42)), false);
  assert.strictEqual(isValidCapability(`${CAP}A`), false);
  assert.strictEqual(isValidCapability(`${CAP.slice(0, 42)}+`), false);
  assert.strictEqual(isValidCapability(undefined), false);
});

test('input validation uses exact-key allowlists', () => {
  const prepare = { transitionId: ID, capability: CAP, intent: 'IMPORT' };
  assert.deepStrictEqual(validatePrepareInput(prepare), { ok: true });
  assert.strictEqual(validatePrepareInput({ ...prepare, extra: 1 }).ok, false);
  const { capability, ...missing } = prepare;
  assert.strictEqual(validatePrepareInput(missing).ok, false);
  assert.strictEqual(validatePrepareInput(null).ok, false);
  assert.strictEqual(validatePrepareInput([]).ok, false);
  assert.strictEqual(validatePrepareInput({ ...prepare, intent: 'KEEP' }).reason, 'INVALID_INTENT');
  assert.strictEqual(isValidIntent('DISCARD'), true);
  assert.strictEqual(isValidIntent('import'), false);

  assert.deepStrictEqual(validateFinalizeInput(prepare), { ok: true });
  assert.deepStrictEqual(validateCancelInput({ transitionId: ID, capability: CAP }), { ok: true });
  assert.strictEqual(validateCancelInput(prepare).ok, false);
});

test('capability hash is deterministic SHA-256 hex and constant-time comparable', () => {
  const hash = capabilityHashOf(CAP);
  assert.strictEqual(hash.length, 64);
  assert.strictEqual(hash, capabilityHashOf(CAP));
  assert.strictEqual(hash, crypto.createHash('sha256').update(CAP, 'utf8').digest('hex'));
  assert.strictEqual(hashesEqual(hash, capabilityHashOf(CAP)), true);
  const flipped = `${CAP.slice(0, 42)}${CAP[42] === 'A' ? 'B' : 'A'}`;
  assert.strictEqual(hashesEqual(hash, capabilityHashOf(flipped)), false);
  assert.strictEqual(hashesEqual(hash, hash.slice(0, 63)), false);
  assert.strictEqual(hashesEqual(null, hash), false);
});

test('effectiveState marks only an elapsed PREPARED lease, lazily and non-destructively', () => {
  const expires = NOW + LEASE_MS;
  assert.strictEqual(effectiveState(STATES.PREPARED, expires, NOW), STATES.PREPARED);
  assert.strictEqual(effectiveState(STATES.PREPARED, expires, expires - 1), STATES.PREPARED);
  assert.strictEqual(effectiveState(STATES.PREPARED, expires, expires), STATES.EXPIRED_UNFINALIZED);
  assert.strictEqual(effectiveState(STATES.PREPARED, expires, expires + 1), STATES.EXPIRED_UNFINALIZED);
  assert.strictEqual(effectiveState(STATES.CANCELLED, 0, NOW), STATES.CANCELLED);
  assert.strictEqual(effectiveState(STATES.FINALIZED, 0, NOW), STATES.FINALIZED);
});

test('rolling window: five attempts per 24h, future timestamps discarded, old pruned', () => {
  let data = null;
  for (let i = 0; i < PREPARE_LIMIT_MAX; i += 1) {
    const result = advancePrepareWindow(data, NOW + i);
    assert.strictEqual(result.allowed, true);
    data = result.window;
  }
  assert.strictEqual(data.attempts.length, PREPARE_LIMIT_MAX);

  const blocked = advancePrepareWindow(data, NOW + 10);
  assert.strictEqual(blocked.allowed, false);
  assert.strictEqual(blocked.retryAfterMs, data.attempts[0] + PREPARE_LIMIT_WINDOW_MS - (NOW + 10));

  // An attempt at/after the window edge frees exactly one slot.
  const later = NOW + PREPARE_LIMIT_WINDOW_MS;
  const allowedAgain = advancePrepareWindow(data, later);
  assert.strictEqual(allowedAgain.allowed, true);
  assert.strictEqual(allowedAgain.window.attempts.length, PREPARE_LIMIT_MAX);
  assert.strictEqual(allowedAgain.window.attempts.includes(NOW), false);

  const futureOnly = advancePrepareWindow({ attempts: [NOW + 5_000, NOW - 10] }, NOW);
  assert.deepStrictEqual(futureOnly.window.attempts, [NOW - 10, NOW]);

  assert.deepStrictEqual(advancePrepareWindow({ attempts: 'nope' }, NOW).window.attempts, [NOW]);
  assert.deepStrictEqual(advancePrepareWindow(undefined, NOW).window.attempts, [NOW]);
});

test('recent authentication: auth_time within 300s, small future skew tolerated, else closed', () => {
  assert.strictEqual(isRecentAuthentication({ auth_time: NOW / 1000 }, NOW / 1000 + 300), true);
  assert.strictEqual(isRecentAuthentication({ auth_time: NOW / 1000 }, NOW / 1000 + 301), false);
  assert.strictEqual(isRecentAuthentication({ auth_time: NOW / 1000 }, NOW / 1000 - 60), true);
  assert.strictEqual(isRecentAuthentication({ auth_time: NOW / 1000 }, NOW / 1000 - 61), false);
  assert.strictEqual(isRecentAuthentication({ auth_time: String(NOW / 1000) }, NOW / 1000), false);
  assert.strictEqual(isRecentAuthentication({}, NOW / 1000), false);
  assert.strictEqual(isRecentAuthentication(null, NOW / 1000), false);
});

test('sign-in provider fails closed when the claim is absent', () => {
  const claim = (value) => ({ token: { firebase: { sign_in_provider: value } } });
  assert.strictEqual(signInProviderOf(claim('anonymous')), 'anonymous');
  assert.strictEqual(signInProviderOf(claim('google.com')), 'google.com');
  assert.strictEqual(signInProviderOf({ token: {} }), undefined);
  assert.strictEqual(signInProviderOf({ token: { firebase: {} } }), undefined);
  assert.strictEqual(signInProviderOf({}), undefined);
  assert.strictEqual(signInProviderOf(null), undefined);
});
