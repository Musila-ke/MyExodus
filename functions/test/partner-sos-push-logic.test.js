'use strict';
// Partner SOS dispatch pure-logic suite (plan §6.5 retry policy, §8.9 FCM taxonomy,
// §8.10/§4.5 lease and terminal-state decisions). Runs under plain `npm test`. The
// end-to-end dispatch flow is in emulator-test/partner-sos-push.emulator.js.

const { test } = require('node:test');
const assert = require('node:assert');

const {
  LEASE_MS,
  MAX_PUSH_ATTEMPTS,
  BACKOFF_BASE_MS,
  FCM_FID_BATCH_MAX,
  isTerminalStatus,
  leaseDecision,
  backoffDelayMs,
  classifyFcmError,
  normalizeFcmCode,
  decideFinalization,
  isNudgeExpired,
} = require('../partner-sos-push-logic');

test('constants match the plan (§4.5/§6.5/§8.8)', () => {
  assert.strictEqual(LEASE_MS, 90_000);
  assert.strictEqual(MAX_PUSH_ATTEMPTS, 4);
  assert.strictEqual(BACKOFF_BASE_MS, 60_000);
  assert.strictEqual(FCM_FID_BATCH_MAX, 500);
});

// --- §4.5 lease claimability ---

test('leaseDecision: missing queue, claimable windows, and an active foreign lease', () => {
  const now = 1_000_000;
  assert.strictEqual(leaseDecision({ queue: null, nowMs: now }), 'missing');
  assert.strictEqual(leaseDecision({ queue: { leaseId: null, leaseUntilMs: null }, nowMs: now }), 'claimable');
  assert.strictEqual(
    leaseDecision({ queue: { leaseId: 'other', leaseUntilMs: now - 1 }, nowMs: now }),
    'claimable',
    'expired lease is reclaimable',
  );
  assert.strictEqual(
    leaseDecision({ queue: { leaseId: 'other', leaseUntilMs: now + 1 }, nowMs: now }),
    'not_owner',
  );
  // Boundary: leaseUntilMs == now is already expired (a new lease requires <= now).
  assert.strictEqual(leaseDecision({ queue: { leaseId: 'other', leaseUntilMs: now }, nowMs: now }), 'claimable');
});

// --- §6.5 backoff with jitter ---

test('backoff schedule is 60s/120s/240s with +/-25% jitter', () => {
  const noJitter = () => 0.5;
  assert.strictEqual(backoffDelayMs(1, noJitter), 60_000);
  assert.strictEqual(backoffDelayMs(2, noJitter), 120_000);
  assert.strictEqual(backoffDelayMs(3, noJitter), 240_000);

  assert.strictEqual(backoffDelayMs(1, () => 1), 75_000); // +25%
  assert.strictEqual(backoffDelayMs(1, () => 0), 45_000); // -25%

  for (let i = 0; i < 200; i += 1) {
    const delay = backoffDelayMs(2, Math.random);
    assert.ok(delay >= 90_000 && delay <= 150_000, `jitter must stay within +/-25%: ${delay}`);
  }
});

// --- §8.9 FCM error taxonomy ---

test('normalizeFcmCode strips the messaging/ prefix and reads error.info', () => {
  assert.strictEqual(normalizeFcmCode({ code: 'messaging/server-unavailable' }), 'server-unavailable');
  assert.strictEqual(normalizeFcmCode({ info: { code: 'registration-token-not-registered' } }), 'registration-token-not-registered');
  assert.strictEqual(normalizeFcmCode(null), null);
});

test('classifyFcmError: invalid targets, quota, payload engineering, everything else transient', () => {
  const code = (c) => ({ code: `messaging/${c}` });
  assert.strictEqual(classifyFcmError(code('registration-token-not-registered')), 'invalid_target');
  assert.strictEqual(classifyFcmError(code('installation-id-not-registered')), 'invalid_target');
  assert.strictEqual(classifyFcmError(code('invalid-registration-token')), 'invalid_target');
  assert.strictEqual(classifyFcmError(code('invalid-recipient')), 'invalid_target');

  assert.strictEqual(classifyFcmError(code('message-rate-exceeded')), 'quota');
  assert.strictEqual(classifyFcmError(code('device-message-rate-exceeded')), 'quota');

  assert.strictEqual(classifyFcmError(code('invalid-payload')), 'payload');
  assert.strictEqual(classifyFcmError(code('payload-size-limit-exceeded')), 'payload');
  assert.strictEqual(classifyFcmError(code('invalid-package-name')), 'payload');

  assert.strictEqual(classifyFcmError(code('server-unavailable')), 'transient');
  assert.strictEqual(classifyFcmError(code('internal-error')), 'transient');
  assert.strictEqual(classifyFcmError(code('unknown-error')), 'transient');
  assert.strictEqual(classifyFcmError(code('mismatched-credential')), 'transient');
  assert.strictEqual(classifyFcmError(code('a-code-from-the-future')), 'transient');
  assert.strictEqual(classifyFcmError(null), 'transient');
});

// --- §6.4 steps 20-24 terminal/retry decision ---

test('decideFinalization: resolved outcomes (T-F21..T-F24)', () => {
  const base = { attemptCount: 1, targetCount: 2, transientCount: 0, payloadInvalid: false };
  assert.deepStrictEqual(decideFinalization({ ...base, sentCount: 2 }), {
    action: 'terminal', status: 'sent', stuck: false,
  });
  assert.deepStrictEqual(decideFinalization({ ...base, sentCount: 1 }), {
    action: 'terminal', status: 'partial', stuck: false,
  });
  assert.deepStrictEqual(decideFinalization({ ...base, sentCount: 0 }), {
    action: 'terminal', status: 'failed', stuck: false,
  });
});

test('decideFinalization: transient failures retry with backoff until the 4th attempt', () => {
  const pending = { targetCount: 1, sentCount: 0, transientCount: 1, payloadInvalid: false };
  const first = decideFinalization({ ...pending, attemptCount: 1, rand: () => 0.5 });
  assert.deepStrictEqual(first, { action: 'retry', delayMs: 60_000 });
  assert.strictEqual(decideFinalization({ ...pending, attemptCount: 3, rand: () => 0.5 }).delayMs, 240_000);

  // T-F26: the fourth failed attempt is terminal, favouring partial when anything landed.
  assert.deepStrictEqual(decideFinalization({ ...pending, attemptCount: 4, rand: () => 0.5 }), {
    action: 'terminal', status: 'failed', stuck: true, reason: 'attempt_budget_exhausted',
  });
  assert.deepStrictEqual(
    decideFinalization({ ...pending, attemptCount: 4, sentCount: 1, targetCount: 2, rand: () => 0.5 }),
    { action: 'terminal', status: 'partial', stuck: true, reason: 'attempt_budget_exhausted' },
  );
});

test('decideFinalization: payload failures are terminal engineering defects, never stuck-retried', () => {
  const decision = decideFinalization({
    attemptCount: 1, targetCount: 3, sentCount: 0, transientCount: 3, payloadInvalid: true,
  });
  assert.deepStrictEqual(decision, { action: 'terminal', status: 'failed', stuck: false, reason: 'payload_invalid' });
});

test('isTerminalStatus covers exactly the six statuses minus pending', () => {
  for (const status of ['sent', 'partial', 'suppressed', 'no_eligible_device', 'failed']) {
    assert.strictEqual(isTerminalStatus(status), true, status);
  }
  assert.strictEqual(isTerminalStatus('pending'), false);
});

// --- §6.6 queue expiry ---

test('isNudgeExpired: Timestamps, epoch-ms, boundary, and unknown values', () => {
  const now = 1_000_000;
  const ts = (ms) => ({ toMillis: () => ms });
  assert.strictEqual(isNudgeExpired(ts(now - 1), now), true);
  assert.strictEqual(isNudgeExpired(ts(now), now), true, 'boundary: expiresAt == now is expired');
  assert.strictEqual(isNudgeExpired(ts(now + 1), now), false);
  assert.strictEqual(isNudgeExpired(now - 1, now), true, 'epoch-ms numbers work too');
  assert.strictEqual(isNudgeExpired(now + 1, now), false);
  assert.strictEqual(isNudgeExpired(null, now), false, 'unknown must not drop pending work');
  assert.strictEqual(isNudgeExpired(undefined, now), false);
  assert.strictEqual(isNudgeExpired('garbage', now), false, 'non-numeric stays not-expired');
});
