'use strict';
// Step 9 payload inspection (§8.12): every plaintext byte the provider can see is pinned here.
// The wake carries the versioned generic type plus an opaque message id; DMS notices carry
// fixed OS copy and a hashed per-relationship collapse key; the liveness probe is data-only
// and shows nothing. Nothing here may ever name a relationship, uid, install, category,
// event, or timestamp.

const { test } = require('node:test');
const assert = require('node:assert');

const { createRelayHandler } = require('../relay-logic');
const { buildWakeMessage, WAKE_TTL_MS, WAKE_COLLAPSE_KEY, ANDROID_PACKAGE } = require('../fcm-transport');
const { _buildDmsMessage, _buildLivenessProbeMessage } = require('../dms-processor');
const { NOTICE_AVAILABILITY, NOTICE_RECOVERY } = require('../dms-logic');

const FORBIDDEN = [
  'relationshipid', 'relationship_id', 'pairid', 'group_id', 'installid', 'install_id',
  'last_check_in', 'last_checkin', 'last_received', 'category', 'event_id', 'senderuid',
  'recipientuid', 'monitored', 'watcher',
];

function assertNoIdentity(wire, secrets = []) {
  const json = JSON.stringify(wire).toLowerCase();
  for (const key of FORBIDDEN) assert.ok(!json.includes(key), `payload leaks ${key}: ${json}`);
  for (const secret of secrets) {
    assert.ok(!JSON.stringify(wire).includes(secret), `payload leaks ${secret}`);
  }
}

async function relayWakeData() {
  let wake;
  const handler = createRelayHandler({
    pairingActive: async () => true,
    checkRateLimit: async () => true,
    markMailbox: async () => {},
    listInstallations: async () => [{ id: 'install-1', token: 'tok-1' }],
    dispatch: async (recipientUid, messageId, installation, wakeData) => { wake = wakeData; },
    logger: { info() {}, warn() {}, error() {} },
  });
  await handler({
    params: { recipientUid: 'watcher-uid', messageId: 'message-1' },
    data: { data: () => ({ senderUid: 'monitored-uid', pairId: 'monitored-uid_watcher-uid', payload: 'AAAA' }) },
  });
  return wake;
}

test('relay wake data is the generic schema and nothing else', async () => {
  const wake = await relayWakeData();
  assert.deepStrictEqual(wake, { v: '1', type: 'SYNC_AVAILABLE', messageId: 'message-1' });
  assertNoIdentity(wake, ['watcher-uid', 'monitored-uid', 'monitored-uid_watcher-uid', 'install-1']);
});

test('relay wake is high priority (owner ruling 2026-11-09), TTL 30m, collapsed, package-restricted, no visible copy', async () => {
  const wake = await relayWakeData();
  const message = buildWakeMessage({ id: 'install-1', token: 'tok-1' }, wake);

  assert.strictEqual(message.notification, undefined, 'wakes never render OS copy');
  assert.strictEqual(message.token, 'tok-1');
  assert.deepStrictEqual(message.android, {
    priority: 'high',
    ttl: 30 * 60 * 1000,
    collapseKey: 'sync',
    restrictedPackageName: 'com.example.myexodus',
  });
  assert.strictEqual(WAKE_TTL_MS, 30 * 60 * 1000);
  assert.strictEqual(WAKE_COLLAPSE_KEY, 'sync');
  assert.strictEqual(ANDROID_PACKAGE, 'com.example.myexodus');
});

test('availability notice: fixed copy, high priority, hashed collapse key that never names the pair', () => {
  const row = { notice_type: NOTICE_AVAILABILITY, relationship_id: 'monitored-uid_watcher-uid' };
  const message = _buildDmsMessage(row, ['tok-1', 'tok-2']);

  assert.deepStrictEqual(message.data, { v: '1', type: 'SYNC_AVAILABLE' });
  assert.deepStrictEqual(message.notification, {
    title: 'Partner check-in',
    body: 'A paired device has not checked in recently. This is not proof the app was removed or blocking was turned off.',
  });
  assert.strictEqual(message.android.priority, 'high', 'user-visible time-sensitive notice');
  assert.strictEqual(message.android.ttl, 60 * 60 * 1000);
  assert.match(message.android.collapseKey, /^avail-[0-9a-f]{16}$/);
  assert.strictEqual(message.android.restrictedPackageName, 'com.example.myexodus');
  assert.strictEqual(message.android.notification.channelId, 'availability_alerts_v1');
  assertNoIdentity(message, ['monitored-uid_watcher-uid', 'monitored-uid', 'watcher-uid']);
});

test('recovery supersedes its own silence notice; different pairs never collapse together', () => {
  const pair = 'monitored-uid_watcher-uid';
  const silent = _buildDmsMessage({ notice_type: NOTICE_AVAILABILITY, relationship_id: pair }, ['t']);
  const recovered = _buildDmsMessage({ notice_type: NOTICE_RECOVERY, relationship_id: pair }, ['t']);
  const other = _buildDmsMessage({ notice_type: NOTICE_AVAILABILITY, relationship_id: 'a_b' }, ['t']);

  assert.strictEqual(silent.android.collapseKey, recovered.android.collapseKey);
  assert.notStrictEqual(silent.android.collapseKey, other.android.collapseKey);
  assert.deepStrictEqual(recovered.notification, {
    title: 'Partner check-in',
    body: 'A paired device has checked in again.',
  });
});

test('liveness probe: data-only (no visible copy), high priority, 6h TTL, collapses', () => {
  const message = _buildLivenessProbeMessage({ id: 'install-1', token: 'tok-1' });

  assert.deepStrictEqual(message.data, { v: '1', type: 'LIVENESS_PROBE' });
  assert.strictEqual(message.notification, undefined, 'silent: the user is never asked to check in');
  assert.strictEqual(message.android.priority, 'high', 'a Doze-deferred wake cannot do its job');
  assert.strictEqual(message.android.ttl, 6 * 60 * 60 * 1000);
  assert.strictEqual(message.android.collapseKey, 'liveness-probe');
  assert.strictEqual(message.android.restrictedPackageName, 'com.example.myexodus');
  assert.strictEqual(message.android.notification, undefined, 'no channel: nothing is displayed');
  assertNoIdentity(message, ['install-1']);
});
