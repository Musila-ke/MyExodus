'use strict';
// Scheduled-function guard against Firestore composite-index build windows (owner alert report
// 2026-10-21). Pure Node — no emulator.

const { test } = require('node:test');
const assert = require('node:assert');

const { isIndexBuildInProgress, deferWhileIndexBuilds } = require('../index-build-guard');

const INDEX_ERROR = Object.assign(
  new Error(
    '9 FAILED_PRECONDITION: The query requires an index. That index is currently building ' +
    'and cannot be used yet. See the index URL for details.',
  ),
  { code: 9 },
);

test('classifier: the real Firestore index-build error (gRPC code 9) is recognized', () => {
  assert.strictEqual(isIndexBuildInProgress(INDEX_ERROR), true);
});

test('classifier: string status and building-only wording are both recognized', () => {
  assert.strictEqual(
    isIndexBuildInProgress({ status: 'FAILED_PRECONDITION', message: 'An index is currently building' }),
    true,
  );
});

test('classifier: other preconditions and ordinary errors are not deferred', () => {
  assert.strictEqual(isIndexBuildInProgress({ code: 9, message: 'The document is not empty' }), false);
  assert.strictEqual(
    isIndexBuildInProgress({ code: 5, message: 'The query requires an index' }),
    false,
  );
  assert.strictEqual(isIndexBuildInProgress(new Error('boom')), false);
  assert.strictEqual(isIndexBuildInProgress(null), false);
  assert.strictEqual(isIndexBuildInProgress(undefined), false);
});

test('wrapper: a normal pass returns its result untouched', async () => {
  const run = async () => ({ due: 3, dispatched: 1 });
  assert.deepStrictEqual(await deferWhileIndexBuilds(run)(), { due: 3, dispatched: 1 });
});

test('wrapper: an index-build failure defers the pass and logs one warning', async () => {
  const logged = [];
  const run = async () => { throw INDEX_ERROR; };
  const guarded = deferWhileIndexBuilds(run, { log: { warn: (...args) => logged.push(args) } });

  assert.deepStrictEqual(await guarded(), { deferred: 'INDEX_BUILDING' });
  assert.strictEqual(logged.length, 1);
  assert.match(String(logged[0][0]), /index still building/i);
});

test('wrapper: any other failure still fails the invocation', async () => {
  const guarded = deferWhileIndexBuilds(async () => { throw new Error('boom'); });
  await assert.rejects(guarded, /boom/);
});
