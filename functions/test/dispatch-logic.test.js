'use strict';
// Plan §12 "Cloud Function" cases for the dispatch record state machine (no emulator: the
// effects are injected). Runnable with `npm test` in functions/.

const { test } = require('node:test');
const assert = require('node:assert');

require('../dispatch');
const { UnknownProviderError } = require('../dispatch');
const { MAX_ATTEMPTS } = require('../relay-logic');
const { createAttemptDispatch, createSweepPass } = require('../dispatch-logic');

const KEY = (messageId, installationId) => `${messageId}/${installationId}`;

function attemptHarness() {
  const records = new Map();
  const patches = [];
  const calls = { dispatch: 0, stale: [], warns: [], lastWake: null };
  let dispatchError = null;
  let tokenInvalid = false;

  const attempt = createAttemptDispatch({
    readRecord: async (recipientUid, messageId, installationId) => records.get(KEY(messageId, installationId)) || null,
    writeRecord: async (recipientUid, messageId, installationId, patch) => {
      const key = KEY(messageId, installationId);
      patches.push([key, patch]);
      records.set(key, { ...(records.get(key) || {}), ...patch });
    },
    markInstallationStale: async (recipientUid, installationId) => { calls.stale.push(installationId); },
    dispatch: async (installation, wake) => {
      calls.dispatch += 1;
      calls.lastWake = wake;
      if (dispatchError) throw dispatchError;
    },
    isTokenInvalid: () => tokenInvalid,
    logger: { warn: (msg, fields) => calls.warns.push(fields), info() {} },
  });

  return {
    attempt,
    records,
    patches,
    calls,
    seed: (messageId, installationId, data) => records.set(KEY(messageId, installationId), data),
    failWith: (err) => { dispatchError = err; },
    failTokenInvalid: () => { tokenInvalid = true; dispatchError = new Error('gone'); },
  };
}

const inst = (id) => ({ id, provider: 'fcm', token: `tok-${id}` });
const WAKE = { v: '1', type: 'SYNC_AVAILABLE', messageId: 'm1' };

test('duplicate trigger for one messageId sends exactly one push and reaches DISPATCHED once', async () => {
  const h = attemptHarness();
  assert.strictEqual(await h.attempt('bob', 'm1', inst('i1'), WAKE), 'DISPATCHED');
  assert.strictEqual(await h.attempt('bob', 'm1', inst('i1'), WAKE), 'SKIPPED');

  assert.strictEqual(h.calls.dispatch, 1); // no duplicate push after DISPATCHED
  assert.strictEqual(h.records.get(KEY('m1', 'i1')).status, 'DISPATCHED');
  assert.strictEqual(h.records.get(KEY('m1', 'i1')).attempts, 1);
  assert.deepStrictEqual(h.patches.map(([, p]) => p.status), ['PENDING', 'DISPATCHED']);
});

test('one installation failing and another succeeding yields independent per-installation states', async () => {
  const h = attemptHarness();
  h.failWith(new Error('transient'));

  const failed = await h.attempt('bob', 'm1', inst('i1'), WAKE);
  h.failWith(null);
  const ok = await h.attempt('bob', 'm1', inst('i2'), WAKE);

  assert.strictEqual(failed, 'FAILED_RETRYABLE');
  assert.strictEqual(ok, 'DISPATCHED');
  assert.strictEqual(h.records.get(KEY('m1', 'i1')).status, 'FAILED_RETRYABLE');
  assert.strictEqual(h.records.get(KEY('m1', 'i2')).attempts, 1);
  assert.strictEqual(h.calls.dispatch, 2);
});

test('a failed attempt below the cap stays retryable; the 8th failed attempt is terminal', async () => {
  const h = attemptHarness();
  h.failWith(new Error('transient'));
  h.seed('m1', 'i1', { status: 'FAILED_RETRYABLE', attempts: MAX_ATTEMPTS - 2 });
  assert.strictEqual(await h.attempt('bob', 'm1', inst('i1'), WAKE), 'FAILED_RETRYABLE');
  assert.strictEqual(h.records.get(KEY('m1', 'i1')).attempts, MAX_ATTEMPTS - 1);

  h.seed('m1', 'i1', { status: 'FAILED_RETRYABLE', attempts: MAX_ATTEMPTS - 1 });
  assert.strictEqual(await h.attempt('bob', 'm1', inst('i1'), WAKE), 'FAILED_TERMINAL');
  assert.strictEqual(h.records.get(KEY('m1', 'i1')).attempts, MAX_ATTEMPTS);
  assert.strictEqual(h.calls.warns.at(-1).terminal, true);
});

test('a gone token marks the installation stale and the record terminal', async () => {
  const h = attemptHarness();
  h.failTokenInvalid();

  assert.strictEqual(await h.attempt('bob', 'm1', inst('i1'), WAKE), 'FAILED_TERMINAL');
  assert.deepStrictEqual(h.calls.stale, ['i1']);
  assert.strictEqual(h.records.get(KEY('m1', 'i1')).status, 'FAILED_TERMINAL');
});

test('an unregistered provider is terminal without touching the installation row', async () => {
  const h = attemptHarness();
  h.failWith(new UnknownProviderError('hms'));

  assert.strictEqual(await h.attempt('bob', 'm1', inst('i1'), WAKE), 'FAILED_TERMINAL');
  assert.deepStrictEqual(h.calls.stale, []);
});

function sweepHarness(retryable, gate) {
  const marks = [];
  const attempts = [];
  const logs = [];
  const messages = [];
  const installations = {};
  const sweep = createSweepPass({
    ...(gate ? { gate } : {}),
    listRetryable: async () => retryable,
    getInstallation: async (recipientUid, installationId) => installations[installationId] || null,
    markGone: async (record) => { marks.push(record.installationId); },
    attempt: async (recipientUid, messageId, installation, wake) => { attempts.push({ messageId, installation, wake }); },
    logger: { info: (msg, fields) => { messages.push(msg); logs.push(fields); } },
  });
  return { sweep, marks, attempts, logs, messages, installations };
}

test('sweep retries only the failed installation and counts gone rows terminal', async () => {
  const h = sweepHarness([
    { ref: 'r1', recipientUid: 'bob', messageId: 'm1', installationId: 'gone' },
    { ref: 'r2', recipientUid: 'bob', messageId: 'm1', installationId: 'live' },
  ]);
  h.installations.live = { id: 'live', provider: 'fcm', token: 't' };

  const counts = await h.sweep();

  assert.deepStrictEqual(counts, { scanned: 2, redispatched: 1, terminal: 1 });
  assert.deepStrictEqual(h.marks, ['gone']);
  assert.strictEqual(h.attempts.length, 1);
  assert.strictEqual(h.attempts[0].installation.id, 'live');
  assert.deepStrictEqual(h.attempts[0].wake, { v: '1', type: 'SYNC_AVAILABLE', messageId: 'm1' }); // AD-11/§8.12
  assert.deepStrictEqual(h.logs, [{ scanned: 2, redispatched: 1, terminal: 1 }]);
});

test('sweep treats an already-stale installation row as a dead end too', async () => {
  const h = sweepHarness([{ ref: 'r1', recipientUid: 'bob', messageId: 'm1', installationId: 'stale' }]);
  h.installations.stale = { id: 'stale', provider: 'fcm', token: 't', stale: true };

  assert.deepStrictEqual(await h.sweep(), { scanned: 1, redispatched: 0, terminal: 1 });
  assert.deepStrictEqual(h.attempts, []);
});

test('an empty sweep does nothing and logs nothing', async () => {
  const h = sweepHarness([]);
  assert.deepStrictEqual(await h.sweep(), { scanned: 0, redispatched: 0, terminal: 0 });
  assert.deepStrictEqual(h.logs, []);
});

test('sweep: the partner-accountability switch stops redispatch without touching records', async () => {
  // ADR #207: a rollback must stop in-flight partner pushes too, not just new mailbox creates.
  // The pass returns before listing, so no retry, no gone-mark, no state mutation.
  const h = sweepHarness(
    [{ ref: 'r1', recipientUid: 'bob', messageId: 'm1', installationId: 'live' }],
    async () => false,
  );
  h.installations.live = { id: 'live', provider: 'fcm', token: 't' };

  assert.deepStrictEqual(await h.sweep(), { scanned: 0, redispatched: 0, terminal: 0, disabled: true });
  assert.deepStrictEqual(h.attempts, []);
  assert.deepStrictEqual(h.marks, []);
  assert.deepStrictEqual(h.messages, ['dispatch sweep disabled: partner accountability switched off']);
});

test('sweep: an enabled gate (or no gate dep) retries as before', async () => {
  const h = sweepHarness(
    [{ ref: 'r1', recipientUid: 'bob', messageId: 'm1', installationId: 'live' }],
    async () => true,
  );
  h.installations.live = { id: 'live', provider: 'fcm', token: 't' };

  assert.deepStrictEqual(await h.sweep(), { scanned: 1, redispatched: 1, terminal: 0 });
  assert.strictEqual(h.attempts.length, 1);
});
