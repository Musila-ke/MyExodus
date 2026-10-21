'use strict';
// Device-availability heartbeat pure-logic suite (partner event-delivery plan §7 Step 7 /
// §8.10). Runs under plain `npm test` — no emulator. The transactional behaviors (install
// gate, namespace isolation, concurrent CAS) live in emulator-test/heartbeat.emulator.js.

const { test } = require('node:test');
const assert = require('node:assert');

const {
  DMS_PARTNER_PUSH_MS,
  MIN_ACCEPT_INTERVAL_MS,
  stateDocId,
  isValidInstallId,
  isValidCounter,
  decideHeartbeat,
} = require('../heartbeat-logic');

const NOW = Date.UTC(2026, 9, 21, 12, 0, 0);

test('constants match the plan thresholds', () => {
  assert.strictEqual(DMS_PARTNER_PUSH_MS, 6 * 60 * 60 * 1000);
  assert.strictEqual(MIN_ACCEPT_INTERVAL_MS, 30 * 1000);
});

test('stateDocId namespaces by uid and install id', () => {
  assert.strictEqual(stateDocId('uidA', 'fid-1'), 'uidA_fid-1');
  assert.notStrictEqual(
    stateDocId('uidA', 'fid-1'),
    stateDocId('uidB', 'fid-1'),
    'a rotated identity is a different document, never a collision',
  );
});

test('install ids: FID-shaped values accepted, junk rejected', () => {
  assert.ok(isValidInstallId('dGVzdC1maWQtMTIzNDU2Nzg5MA'));
  assert.ok(isValidInstallId('ab_cd-efgh'));
  assert.ok(!isValidInstallId('short'));
  assert.ok(!isValidInstallId(''));
  assert.ok(!isValidInstallId(null));
  assert.ok(!isValidInstallId('has space in it'));
  assert.ok(!isValidInstallId('slash/inside'));
  assert.ok(!isValidInstallId('x'.repeat(129)));
});

test('counters: positive safe integers only', () => {
  assert.ok(isValidCounter(1));
  assert.ok(isValidCounter(Number.MAX_SAFE_INTEGER));
  assert.ok(!isValidCounter(0));
  assert.ok(!isValidCounter(-1));
  assert.ok(!isValidCounter(1.5));
  assert.ok(!isValidCounter('2'));
  assert.ok(!isValidCounter(null));
});

test('first beat is accepted at generation 1 with a six-hour deadline', () => {
  assert.deepStrictEqual(decideHeartbeat(null, 1, NOW), {
    outcome: 'ACCEPTED',
    serverCounter: 1,
    generation: 1,
    lastReceivedAtMs: NOW,
    nextDeadlineAtMs: NOW + DMS_PARTNER_PUSH_MS,
  });
});

test('a newer counter advances the generation and rebases the deadline', () => {
  const previous = {
    last_counter: 4,
    last_received_at_ms: NOW - 10 * 60 * 1000,
    liveness_generation: 2,
  };
  assert.deepStrictEqual(decideHeartbeat(previous, 5, NOW), {
    outcome: 'ACCEPTED',
    serverCounter: 5,
    generation: 3,
    lastReceivedAtMs: NOW,
    nextDeadlineAtMs: NOW + DMS_PARTNER_PUSH_MS,
  });
});

test('a replayed or lost counter is STALE — never liveness, never an error', () => {
  const previous = {
    last_counter: 7,
    last_received_at_ms: NOW - 60_000,
    liveness_generation: 3,
  };
  for (const counter of [7, 6, 1]) {
    assert.deepStrictEqual(decideHeartbeat(previous, counter, NOW), {
      outcome: 'STALE',
      serverCounter: 7,
      generation: 3,
      lastReceivedAtMs: NOW - 60_000,
      nextDeadlineAtMs: null,
    });
  }
});

test('a beat faster than the server floor is RATE_LIMITED without touching liveness', () => {
  const previous = {
    last_counter: 2,
    last_received_at_ms: NOW - MIN_ACCEPT_INTERVAL_MS + 1,
    liveness_generation: 5,
  };
  assert.deepStrictEqual(decideHeartbeat(previous, 3, NOW), {
    outcome: 'RATE_LIMITED',
    serverCounter: 2,
    generation: 5,
    lastReceivedAtMs: NOW - MIN_ACCEPT_INTERVAL_MS + 1,
    nextDeadlineAtMs: null,
  });
});

test('exactly at the floor, a newer counter is accepted', () => {
  const previous = {
    last_counter: 2,
    last_received_at_ms: NOW - MIN_ACCEPT_INTERVAL_MS,
    liveness_generation: 5,
  };
  const decision = decideHeartbeat(previous, 3, NOW);
  assert.strictEqual(decision.outcome, 'ACCEPTED');
  assert.strictEqual(decision.generation, 6);
  assert.strictEqual(decision.lastReceivedAtMs, NOW);
  assert.strictEqual(decision.nextDeadlineAtMs, NOW + DMS_PARTNER_PUSH_MS);
});

test('STALE wins over the interval check: an equal counter is never rate-limited', () => {
  const previous = {
    last_counter: 9,
    last_received_at_ms: NOW - 5_000,
    liveness_generation: 4,
  };
  assert.strictEqual(decideHeartbeat(previous, 9, NOW).outcome, 'STALE');
});

test('missing or malformed prior fields degrade to safe defaults', () => {
  const decision = decideHeartbeat({}, 3, NOW);
  assert.strictEqual(decision.outcome, 'ACCEPTED');
  assert.strictEqual(decision.generation, 1);
  assert.strictEqual(decision.nextDeadlineAtMs, NOW + DMS_PARTNER_PUSH_MS);
});
