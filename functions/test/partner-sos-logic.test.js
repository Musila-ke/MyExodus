'use strict';
// Partner SOS pure-logic suite (plan §12.1 T-F2/T-F3/T-F6/T-F7/T-F8 boundary arithmetic and
// the §8.4 input contract). Runs under plain `npm test` — no emulator. The transactional
// behaviors (T-F1/T-F4/T-F5/T-F10/T-F13/T-F14) live in test/partner-sos.emulator.js.

const { test } = require('node:test');
const assert = require('node:assert');

const {
  NUDGE_COOLDOWN_MS,
  NUDGE_DAILY_CAP,
  NUDGE_ENVELOPE_MAX,
  NUDGE_RETENTION_MS,
  validateSendNudgeInput,
  dayKeyOf,
  secondsUntilNairobiMidnight,
  decideNudgeAcceptance,
  classifyExistingNudge,
} = require('../partner-sos-logic');

const UUID = '3f2b8c1a-9d4e-4f6a-8b7c-1e2d3f4a5b6c';
const ENVELOPE = 'eyJ2IjoxLCJraW5kIjoibnVkZ2UifQ=='; // {"v":1,"kind":"nudge"}

// 2026-10-03T12:00:00Z — midday UTC, safely inside one Nairobi day.
const NOON_UTC = Date.UTC(2026, 9, 3, 12, 0, 0);

test('constants match the plan §9/§4.3 values', () => {
  assert.strictEqual(NUDGE_COOLDOWN_MS, 10 * 60 * 1000);
  assert.strictEqual(NUDGE_DAILY_CAP, 12);
  assert.strictEqual(NUDGE_ENVELOPE_MAX, 1024);
  assert.strictEqual(NUDGE_RETENTION_MS, 7 * 24 * 60 * 60 * 1000);
});

// --- §8.4 input contract ---

test('valid input passes', () => {
  assert.deepStrictEqual(
    validateSendNudgeInput({ pairId: 'a_b', nudgeId: UUID, envelope: ENVELOPE }),
    { ok: true },
  );
});

test('unknown or missing fields are rejected', () => {
  const base = { pairId: 'a_b', nudgeId: UUID, envelope: ENVELOPE };
  assert.strictEqual(validateSendNudgeInput({ ...base, extra: 1 }).ok, false);
  assert.strictEqual(validateSendNudgeInput({ pairId: 'a_b', nudgeId: UUID }).ok, false);
  assert.strictEqual(validateSendNudgeInput('nope').ok, false);
  assert.strictEqual(validateSendNudgeInput(null).ok, false);
  assert.strictEqual(validateSendNudgeInput([base]).ok, false);
});

test('pairId is a bounded non-empty string', () => {
  assert.strictEqual(validateSendNudgeInput({ pairId: '', nudgeId: UUID, envelope: ENVELOPE }).ok, false);
  assert.strictEqual(
    validateSendNudgeInput({ pairId: 'a'.repeat(129), nudgeId: UUID, envelope: ENVELOPE }).ok,
    false,
  );
  assert.strictEqual(validateSendNudgeInput({ pairId: 7, nudgeId: UUID, envelope: ENVELOPE }).ok, false);
});

test('nudgeId must be UUIDv4', () => {
  const bad = [
    'not-a-uuid',
    '3f2b8c1a-9d4e-1f6a-8b7c-1e2d3f4a5b6c', // version nibble 1
    '3f2b8c1a-9d4e-4f6a-7b7c-1e2d3f4a5b6c', // variant nibble 7
    UUID.slice(0, -1),
    `${UUID}x`,
    '../' + UUID,
    'a'.repeat(36),
  ];
  for (const nudgeId of bad) {
    assert.strictEqual(
      validateSendNudgeInput({ pairId: 'a_b', nudgeId, envelope: ENVELOPE }).ok,
      false,
      `must reject ${nudgeId}`,
    );
  }
});

test('envelope must be bounded well-formed base64', () => {
  const withEnvelope = (envelope) => validateSendNudgeInput({ pairId: 'a_b', nudgeId: UUID, envelope });
  assert.strictEqual(withEnvelope('A'.repeat(NUDGE_ENVELOPE_MAX)).ok, true);
  assert.strictEqual(withEnvelope('A'.repeat(NUDGE_ENVELOPE_MAX + 4)).ok, false);
  assert.strictEqual(withEnvelope('').ok, false);
  assert.strictEqual(withEnvelope('AAA').ok, false); // %4 !== 0
  assert.strictEqual(withEnvelope('AA-A').ok, false); // '-' is base64url, not standard
  assert.strictEqual(withEnvelope(42).ok, false);
});

// --- §8.4 Nairobi calendar math (Kenya is UTC+3 year-round, no DST) ---

test('dayKeyOf flips exactly at Nairobi midnight (21:00 UTC)', () => {
  assert.strictEqual(dayKeyOf(Date.UTC(2026, 9, 3, 20, 59, 59)), '2026-10-03');
  assert.strictEqual(dayKeyOf(Date.UTC(2026, 9, 3, 21, 0, 0)), '2026-10-04');
  assert.strictEqual(dayKeyOf(NOON_UTC), '2026-10-03');
});

test('secondsUntilNairobiMidnight counts down to 21:00 UTC', () => {
  assert.strictEqual(secondsUntilNairobiMidnight(Date.UTC(2026, 9, 3, 20, 59, 59)), 1);
  assert.strictEqual(secondsUntilNairobiMidnight(Date.UTC(2026, 9, 3, 21, 0, 0)), 86400);
  // 00:00 UTC is 03:00 Nairobi → 21 hours left.
  assert.strictEqual(secondsUntilNairobiMidnight(Date.UTC(2026, 9, 3, 0, 0, 0)), 75600);
});

// --- FR-4 sliding cooldown (T-F2 / T-F3) ---

test('cooldown: +9 min blocked with the exact remaining seconds; +10 min allowed', () => {
  const state = { lastAcceptedAtMs: NOON_UTC, dayKey: '2026-10-03', dayCount: 1 };
  const at9 = decideNudgeAcceptance({ state, nowMs: NOON_UTC + 9 * 60 * 1000 });
  assert.deepStrictEqual(at9, { allowed: false, reason: 'cooldown', retryAfterSeconds: 60 });

  // Boundary: a tap at exactly 10 minutes is eligible (FR-4).
  const at10 = decideNudgeAcceptance({ state, nowMs: NOON_UTC + NUDGE_COOLDOWN_MS });
  assert.strictEqual(at10.allowed, true);
  assert.strictEqual(at10.dayCount, 2);

  const at10minus1 = decideNudgeAcceptance({ state, nowMs: NOON_UTC + NUDGE_COOLDOWN_MS - 1 });
  assert.deepStrictEqual(at10minus1, { allowed: false, reason: 'cooldown', retryAfterSeconds: 1 });
});

test('a fresh sender (no state) is allowed and starts dayCount at 1', () => {
  const decision = decideNudgeAcceptance({ state: null, nowMs: NOON_UTC });
  assert.deepStrictEqual(decision, { allowed: true, dayKey: '2026-10-03', dayCount: 1 });
});

// --- FR-5 Nairobi daily cap (T-F8) ---

test('daily cap: the 13th accepted nudge in a Nairobi day is rejected', () => {
  const state = { lastAcceptedAtMs: NOON_UTC - NUDGE_COOLDOWN_MS, dayKey: '2026-10-03', dayCount: NUDGE_DAILY_CAP };
  const decision = decideNudgeAcceptance({ state, nowMs: NOON_UTC });
  assert.strictEqual(decision.allowed, false);
  assert.strictEqual(decision.reason, 'daily_cap');
  assert.strictEqual(decision.retryAfterSeconds, secondsUntilNairobiMidnight(NOON_UTC));

  const almost = decideNudgeAcceptance({
    state: { ...state, dayCount: NUDGE_DAILY_CAP - 1 },
    nowMs: NOON_UTC,
  });
  assert.strictEqual(almost.allowed, true);
  assert.strictEqual(almost.dayCount, NUDGE_DAILY_CAP); // the 12th is accepted
});

test('the cap resets when the Nairobi dayKey rolls over', () => {
  const state = { lastAcceptedAtMs: NOON_UTC - NUDGE_COOLDOWN_MS, dayKey: '2026-10-02', dayCount: NUDGE_DAILY_CAP };
  const decision = decideNudgeAcceptance({ state, nowMs: NOON_UTC });
  assert.strictEqual(decision.allowed, true);
  assert.strictEqual(decision.dayCount, 1);
});

test('cooldown is checked before the cap (plan §6.2 ordering)', () => {
  const state = { lastAcceptedAtMs: NOON_UTC, dayKey: '2026-10-03', dayCount: NUDGE_DAILY_CAP };
  const decision = decideNudgeAcceptance({ state, nowMs: NOON_UTC + 1000 });
  assert.strictEqual(decision.reason, 'cooldown');
});

// --- §6.2 step 12 nudge-id idempotency (T-F6 / T-F7) ---

test('classifyExistingNudge: replay, envelope mismatch, sender mismatch', () => {
  const existing = { senderUid: 'alice', envelope: ENVELOPE };
  assert.deepStrictEqual(
    classifyExistingNudge({ existing, senderUid: 'alice', envelope: ENVELOPE }),
    { outcome: 'replay' },
  );
  assert.deepStrictEqual(
    classifyExistingNudge({ existing, senderUid: 'alice', envelope: 'AAAA' }),
    { outcome: 'envelope_mismatch' },
  );
  assert.deepStrictEqual(
    classifyExistingNudge({ existing, senderUid: 'bob', envelope: ENVELOPE }),
    { outcome: 'sender_mismatch' },
  );
  // Sender identity is checked first: a different sender with a different envelope is
  // still a sender mismatch (T-F7's permission-denied wins over T-F6's invalid-argument).
  assert.deepStrictEqual(
    classifyExistingNudge({ existing, senderUid: 'bob', envelope: 'AAAA' }),
    { outcome: 'sender_mismatch' },
  );
});
