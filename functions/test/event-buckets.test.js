'use strict';
// §8.8 event token-bucket math — pure, no emulator. Run with `npm test` in functions/.

const { test } = require('node:test');
const assert = require('node:assert');

const { UID_BUCKET, PAIR_BUCKET, MAX_RETRY_AFTER_MS, decideCharge } = require('../event-buckets');

const T0 = 1_760_000_000_000;

test('a missing bucket starts full and a charge debits both dimensions', () => {
  const decision = decideCharge(null, UID_BUCKET, 1000, T0);
  assert.strictEqual(decision.allowed, true);
  assert.deepStrictEqual(decision.next, {
    events: UID_BUCKET.eventCapacity - 1,
    bytes: UID_BUCKET.byteCapacity - 1000,
    updatedAt: T0,
  });
});

test('refill is time-based, rate-limited per dimension, and clamps at capacity', () => {
  const spent = { events: 0, bytes: 0, updatedAt: T0 };
  // One second later: +2 events, +10 MiB/60s per UID.
  const oneSecond = decideCharge(spent, UID_BUCKET, 0, T0 + 1000);
  assert.strictEqual(oneSecond.allowed, true);
  assert.strictEqual(oneSecond.next.events, 2 - 1);
  // 10 MiB/minute sustained — one second refills ~174 KB, not the whole burst.
  const oneSecondBytes = (10 * 1024 * 1024) / 60;
  assert.ok(Math.abs(oneSecond.next.bytes - oneSecondBytes) < 1e-6);

  const longIdle = decideCharge(spent, UID_BUCKET, 0, T0 + 60 * 60 * 1000);
  assert.strictEqual(longIdle.next.events, UID_BUCKET.eventCapacity - 1); // clamped
  assert.strictEqual(longIdle.next.bytes, UID_BUCKET.byteCapacity);
});

test('an exhausted event budget throttles with a bounded retryAfterMs', () => {
  const exhausted = { events: 0, bytes: UID_BUCKET.byteCapacity, updatedAt: T0 };
  const decision = decideCharge(exhausted, UID_BUCKET, 0, T0);
  assert.strictEqual(decision.allowed, false);
  // 2 events/s refill -> half a second, and never beyond the bound.
  assert.strictEqual(decision.retryAfterMs, 500);
  assert.ok(decision.retryAfterMs <= MAX_RETRY_AFTER_MS);
});

test('the byte dimension throttles independently of the event dimension', () => {
  const bytesShort = { events: 30, bytes: 0, updatedAt: T0 };
  const decision = decideCharge(bytesShort, UID_BUCKET, 1024, T0);
  assert.strictEqual(decision.allowed, false);
  // 1024 bytes at 10 MiB/min -> ~6ms.
  assert.ok(decision.retryAfterMs >= 1 && decision.retryAfterMs <= 10);
});

test('a pair bucket is larger than a UID bucket and obeys its own rates', () => {
  assert.ok(PAIR_BUCKET.eventCapacity > UID_BUCKET.eventCapacity);
  assert.ok(PAIR_BUCKET.byteCapacity > UID_BUCKET.byteCapacity);
  const spent = { events: 0, bytes: 0, updatedAt: T0 };
  const decision = decideCharge(spent, PAIR_BUCKET, 0, T0 + 1000);
  assert.strictEqual(decision.next.events, PAIR_BUCKET.eventPerSecond - 1);
});

test('a rolled-back clock refills nothing and is never a windfall', () => {
  const state = { events: 5, bytes: 500, updatedAt: T0 };
  const decision = decideCharge(state, UID_BUCKET, 0, T0 - 60_000);
  assert.strictEqual(decision.next.events, 5 - 1); // no refill for negative elapsed
  assert.strictEqual(decision.next.bytes, 500);
});

test('a charge larger than the byte capacity always throttles with a bounded hint', () => {
  const decision = decideCharge(null, UID_BUCKET, UID_BUCKET.byteCapacity + 1, T0);
  assert.strictEqual(decision.allowed, false);
  assert.ok(decision.retryAfterMs <= MAX_RETRY_AFTER_MS);
});
