'use strict';
// Runnable checks for the AD-18b retention decision (plan §8 steps 5/6 sibling — the
// mailbox cleanup job). Pure module, no emulator: run with `npm test` in functions/.

const { test } = require('node:test');
const assert = require('node:assert');

const {
  DAY_MS,
  MIN_RETENTION_MS,
  MAX_RETENTION_MS,
  shouldDeleteMailbox,
  unackedInstallationIds,
} = require('../mailbox-retention');

test('retention bounds match AD-18b (7-day floor, 30-day cap)', () => {
  assert.strictEqual(MIN_RETENTION_MS, 7 * DAY_MS);
  assert.strictEqual(MAX_RETENTION_MS, 30 * DAY_MS);
});

test('before the floor: never deleted, even fully acked', () => {
  assert.strictEqual(shouldDeleteMailbox(6 * DAY_MS, true), false);
  assert.strictEqual(shouldDeleteMailbox(6 * DAY_MS, false), false);
});

test('past the floor: deleted only once every installation has acked', () => {
  assert.strictEqual(shouldDeleteMailbox(7 * DAY_MS, false), false);
  assert.strictEqual(shouldDeleteMailbox(7 * DAY_MS, true), true);
  assert.strictEqual(shouldDeleteMailbox(29 * DAY_MS, false), false);
  assert.strictEqual(shouldDeleteMailbox(29 * DAY_MS, true), true);
});

test('at the cap: deleted regardless of acks', () => {
  assert.strictEqual(shouldDeleteMailbox(30 * DAY_MS, false), true);
  assert.strictEqual(shouldDeleteMailbox(31 * DAY_MS, false), true);
});

test('unackedInstallationIds counts registered non-stale rows without an ack', () => {
  const installs = [
    { id: 'a' }, // acked below
    { id: 'b' }, // never acked
    { id: 'c', stale: true }, // stale rows never ack — not counted
  ];
  assert.deepStrictEqual(unackedInstallationIds(installs, new Set(['a'])), ['b']);
  assert.deepStrictEqual(unackedInstallationIds(installs, new Set(['a', 'b'])), []);
  assert.deepStrictEqual(unackedInstallationIds([], new Set()), []); // no-provider recipient
});
