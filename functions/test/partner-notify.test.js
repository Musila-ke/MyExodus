'use strict';
// §8.7 `partner_notify_v1_enabled` pause semantics. Emulator-free: the pause check runs
// before any job read, so a paused dispatch must return without touching the job — which is
// exactly the "retain mailbox/jobs" contract. The unset case proves the gate is a kill
// switch, not an opt-in (unset/true = enabled).

const { test } = require('node:test');
const assert = require('node:assert');

const { dispatchJob } = require('../partner-notify');

function fakeDb(featuresData) {
  return {
    doc: () => ({
      get: async () => ({ exists: featuresData !== null, data: () => featuresData ?? {} }),
    }),
    // Reaching the claim proves the pause gate did not fire.
    runTransaction: async () => { throw new Error('CLAIM_REACHED'); },
  };
}

test('a paused notifier leaves the job untouched', async () => {
  const outcome = await dispatchJob({
    db: fakeDb({ partner_notify_v1_enabled: false }),
    jobId: 'event-id__installation-id',
  });
  assert.strictEqual(outcome, 'PAUSED');
});

test('an unset or true flag does not pause', async () => {
  for (const features of [{}, { partner_notify_v1_enabled: true }]) {
    await assert.rejects(
      () => dispatchJob({ db: fakeDb(features), jobId: 'event-id__installation-id' }),
      /CLAIM_REACHED/,
    );
  }
});
