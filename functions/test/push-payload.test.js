'use strict';
// Step 9 payload inspection (§8.12): every plaintext byte the provider can see is pinned here.
// The wake carries the versioned generic type plus an opaque message id; DMS notices are
// data-only with the pair id and notice type as local lookup keys (owner ruling 2026-11-12,
// D16 precedent) and a hashed per-relationship collapse key; the liveness probe is data-only
// and shows nothing. Wake payloads never name a relationship, uid, install, category, event,
// or timestamp; no payload reaches the OS as provider-rendered copy.

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

test('availability notice: data-only with local lookup keys, high priority, hashed collapse key', () => {
  const pair = 'monitored-uid_watcher-uid';
  const message = _buildDmsMessage(
    { notice_type: NOTICE_AVAILABILITY, relationship_id: pair }, ['tok-1', 'tok-2'],
  );

  // Owner ruling 2026-11-12 (D16 precedent): the app renders the copy post-parse and owns the
  // tap, so the OS displays nothing and the wire carries only the two local lookup keys.
  assert.strictEqual(message.notification, undefined, 'the OS never displays DMS copy');
  assert.strictEqual(message.android.notification, undefined, 'no OS channel to post to');
  assert.deepStrictEqual(message.data, {
    v: '1',
    type: 'PARTNER_AVAILABILITY',
    pairId: pair,
    noticeType: NOTICE_AVAILABILITY,
  });
  assert.deepStrictEqual(message.tokens, ['tok-1', 'tok-2']);
  assert.strictEqual(message.android.priority, 'high', 'a Doze-deferred wake cannot do its job');
  assert.strictEqual(message.android.ttl, 60 * 60 * 1000);
  assert.match(message.android.collapseKey, /^avail-[0-9a-f]{16}$/);
  assert.strictEqual(message.android.restrictedPackageName, 'com.example.myexodus');
  assert.ok(
    !JSON.stringify(message.android).includes(pair),
    'provider metadata never names the pair: only the hashed collapse key distinguishes it',
  );
});

test('recovery supersedes its own silence notice; different pairs never collapse together', () => {
  const pair = 'monitored-uid_watcher-uid';
  const silent = _buildDmsMessage({ notice_type: NOTICE_AVAILABILITY, relationship_id: pair }, ['t']);
  const recovered = _buildDmsMessage({ notice_type: NOTICE_RECOVERY, relationship_id: pair }, ['t']);
  const other = _buildDmsMessage({ notice_type: NOTICE_AVAILABILITY, relationship_id: 'a_b' }, ['t']);

  assert.strictEqual(silent.android.collapseKey, recovered.android.collapseKey);
  assert.notStrictEqual(silent.android.collapseKey, other.android.collapseKey);
  assert.strictEqual(recovered.data.noticeType, NOTICE_RECOVERY, 'the app picks the recovered copy');
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
