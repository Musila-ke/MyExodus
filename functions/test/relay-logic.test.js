'use strict';
// Runnable checks for the mailbox relay's pure decision logic (plan §8 Step 5). No emulator
// needed: relay-logic.js has no admin-SDK dependency; isTokenInvalid is exercised through
// the registered fcm transport. Run with `npm test` in functions/.

const { test } = require('node:test');
const assert = require('node:assert');

const {
  MAX_PAYLOAD_BYTES,
  PAIR_WINDOW_MS,
  PAIR_WINDOW_MAX,
  SENDER_WINDOW_MS,
  SENDER_WINDOW_MAX,
  INSTALLATION_STALE_MS,
  MAX_INSTALLATIONS,
  MAX_ATTEMPTS,
  isValidBase64,
  validatePayload,
  windowState,
  incrementWindows,
  createRelayHandler,
  pairingUsableForRelay,
  selectInstallations,
  isInstallationStale,
} = require('../relay-logic');

const b64 = (bytes) => Buffer.alloc(bytes, 0x41).toString('base64');

// Defect #18(a): the unpair deletion request races its own pair's terminate — the trigger
// evaluates after the status flip and must still accept the message (members-bound), or the
// partner never learns to shred. Device-caught 2026-10-01 (request rejected 3.4 s after).
test('pairingUsableForRelay accepts an active pairing for members', () => {
  const pair = { status: 'active', members: ['alice', 'bob'] };
  assert.strictEqual(pairingUsableForRelay(pair, 'alice', 'bob'), true);
});

test('pairingUsableForRelay accepts a terminated pairing for members (the deletion-request race)', () => {
  const pair = { status: 'terminated', members: ['alice', 'bob'] };
  assert.strictEqual(pairingUsableForRelay(pair, 'alice', 'bob'), true);
});

test('pairingUsableForRelay rejects a non-member sender under either status', () => {
  for (const status of ['active', 'terminated']) {
    const pair = { status, members: ['alice', 'bob'] };
    assert.strictEqual(pairingUsableForRelay(pair, 'mallory', 'bob'), false);
    assert.strictEqual(pairingUsableForRelay(pair, 'alice', 'mallory'), false);
  }
});

test('pairingUsableForRelay rejects unknown statuses, missing members, and missing data', () => {
  assert.strictEqual(pairingUsableForRelay({ status: 'pending', members: ['a', 'b'] }, 'a', 'b'), false);
  assert.strictEqual(pairingUsableForRelay({ status: 'active' }, 'a', 'b'), false);
  assert.strictEqual(pairingUsableForRelay(null, 'a', 'b'), false);
  assert.strictEqual(pairingUsableForRelay({ status: 'active', members: ['a', 'b'] }, undefined, 'b'), false);
});

test('validatePayload accepts base64 within the AD-19 limit', () => {
  assert.strictEqual(validatePayload(b64(64)), null);
  assert.strictEqual(validatePayload(b64(MAX_PAYLOAD_BYTES)), null); // exactly at the limit
});

test('validatePayload rejects non-base64 and oversized payloads distinctly', () => {
  assert.strictEqual(validatePayload(''), 'INVALID_FORMAT');
  assert.strictEqual(validatePayload('not base64!!'), 'INVALID_FORMAT');
  assert.strictEqual(validatePayload('AAA'), 'INVALID_FORMAT'); // length % 4 != 0
  assert.strictEqual(validatePayload(null), 'INVALID_FORMAT');
  assert.strictEqual(validatePayload(1234), 'INVALID_FORMAT');
  assert.strictEqual(validatePayload(b64(MAX_PAYLOAD_BYTES + 1)), 'OVERSIZED');
  assert.strictEqual(validatePayload(b64(MAX_PAYLOAD_BYTES + 3)), 'OVERSIZED');
});

test('isValidBase64 is strict — unpadded or non-alphabet input fails', () => {
  assert.strictEqual(isValidBase64('AAAA'), true);
  assert.strictEqual(isValidBase64('AAA='), true);
  assert.strictEqual(isValidBase64('AA=='), true);
  assert.strictEqual(isValidBase64('AAA'), false);
  assert.strictEqual(isValidBase64('AA=A'), false);
  assert.strictEqual(isValidBase64('A_AA'), false); // url-safe alphabet not accepted
});

test('windowState starts fresh on missing or malformed state', () => {
  assert.deepStrictEqual(windowState(undefined, 1000, PAIR_WINDOW_MS), { start: 1000, count: 0 });
  assert.deepStrictEqual(windowState({ start: 'x', count: 1 }, 1000, PAIR_WINDOW_MS), { start: 1000, count: 0 });
});

test('windowState keeps the window inside its span and resets at or after expiry', () => {
  const prev = { start: 1000, count: 4 };
  assert.deepStrictEqual(windowState(prev, 1000 + PAIR_WINDOW_MS - 1, PAIR_WINDOW_MS), prev);
  assert.deepStrictEqual(windowState(prev, 1000 + PAIR_WINDOW_MS, PAIR_WINDOW_MS), {
    start: 1000 + PAIR_WINDOW_MS,
    count: 0,
  });
});

test('windowState resets when start lies in the future (clock-skew guard)', () => {
  assert.deepStrictEqual(windowState({ start: 5000, count: 9 }, 1000, PAIR_WINDOW_MS), { start: 1000, count: 0 });
});

test('window constants match AD-23 and AD-18c', () => {
  assert.strictEqual(PAIR_WINDOW_MS, 60 * 1000);
  assert.strictEqual(PAIR_WINDOW_MAX, 10);
  assert.strictEqual(SENDER_WINDOW_MS, 24 * 60 * 60 * 1000);
  assert.strictEqual(SENDER_WINDOW_MAX, 200);
  assert.strictEqual(INSTALLATION_STALE_MS, 90 * 24 * 60 * 60 * 1000);
  assert.strictEqual(MAX_INSTALLATIONS, 5);
  assert.strictEqual(MAX_ATTEMPTS, 8);
});

function inst(id, updatedAt, extra = {}) {
  return { id, data: { token: `tok-${id}`, provider: 'fcm', updatedAt, ...extra } };
}

test('selectInstallations drops stale-flagged rows and rows past the 90-day refresh bound', () => {
  const now = 10 * INSTALLATION_STALE_MS; // large, so the 90-day arithmetic is unambiguous
  const docs = [
    inst('fresh', now - 1000),
    inst('flagged', now - 1000, { stale: true }),
    inst('expired', now - INSTALLATION_STALE_MS - 1),
    inst('edge', now - INSTALLATION_STALE_MS), // exactly at the bound: still selected
  ];
  assert.deepStrictEqual(selectInstallations(docs, now).map((d) => d.id), ['fresh', 'edge']);
});

test('selectInstallations sorts newest refresh first and caps at MAX_INSTALLATIONS', () => {
  const now = 10 * INSTALLATION_STALE_MS;
  const docs = Array.from({ length: 7 }, (_, i) => inst(`i${i}`, now - (i + 1) * 1000));
  docs.push({ id: 'noTimestamp', data: { token: 't', provider: 'fcm' } }); // refreshMillis 0 → oldest
  const picked = selectInstallations(docs, now);
  assert.strictEqual(picked.length, MAX_INSTALLATIONS);
  assert.deepStrictEqual(picked.map((d) => d.id), ['i0', 'i1', 'i2', 'i3', 'i4']);
});

test('selectInstallations accepts Firestore-Timestamp-shaped updatedAt', () => {
  const now = 10 * INSTALLATION_STALE_MS;
  const ts = { toMillis: () => now - 500 };
  assert.deepStrictEqual(selectInstallations([inst('a', ts)], now).map((d) => d.id), ['a']);
});

test('isInstallationStale only fires past the bound and never on a missing timestamp', () => {
  const now = 10 * INSTALLATION_STALE_MS;
  assert.strictEqual(isInstallationStale({ updatedAt: now - INSTALLATION_STALE_MS }, now), false);
  assert.strictEqual(isInstallationStale({ updatedAt: now - INSTALLATION_STALE_MS - 1 }, now), true);
  assert.strictEqual(isInstallationStale({}, now), false);
});

test('fcm transport classifies only the two gone-token codes as invalid', () => {
  require('../fcm-transport'); // registers 'fcm'
  const { isTokenInvalid } = require('../dispatch');
  assert.strictEqual(isTokenInvalid('fcm', { code: 'messaging/registration-token-not-registered' }), true);
  assert.strictEqual(isTokenInvalid('fcm', { code: 'messaging/invalid-registration-token' }), true);
  assert.strictEqual(isTokenInvalid('fcm', { code: 'messaging/internal-error' }), false);
  assert.strictEqual(isTokenInvalid('fcm', {}), false);
  assert.strictEqual(isTokenInvalid('hms', { code: 'messaging/registration-token-not-registered' }), false);
});

test('dispatchViaProvider throws UnknownProviderError for an unregistered provider', async () => {
  require('../fcm-transport');
  const { dispatchViaProvider, UnknownProviderError } = require('../dispatch');
  await assert.rejects(
    dispatchViaProvider({ provider: 'no-such-provider', token: 't' }, {}),
    UnknownProviderError,
  );
});

test('incrementWindows allows exactly PAIR_WINDOW_MAX per minute, then rejects without counting', () => {
  const now = 1_000_000;
  let data = {};
  for (let i = 1; i <= PAIR_WINDOW_MAX; i++) {
    const d = incrementWindows(data, 'alice', now);
    assert.strictEqual(d.allowed, true, `message ${i} must be allowed`);
    data = { pair: d.pair, senders: d.senders };
  }
  const rejected = incrementWindows(data, 'alice', now);
  assert.strictEqual(rejected.allowed, false);
  assert.strictEqual(rejected.pair, undefined); // nothing to persist: a rejected message never
  // extends the window it was rejected by, or a flood would keep the window permanently full.
  assert.strictEqual(data.pair.count, PAIR_WINDOW_MAX);

  // The next minute's window starts empty again.
  const next = incrementWindows(data, 'alice', now + PAIR_WINDOW_MS);
  assert.strictEqual(next.allowed, true);
  assert.strictEqual(next.pair.count, 1);
});

test('incrementWindows caps each sender at SENDER_WINDOW_MAX per day, independently', () => {
  const now = 2_000_000;
  // The pair window would fill first (10/min), so drive the sender window directly: the
  // sender's own count is already at the limit inside a fresh pair window.
  const data = { senders: { alice: { start: now, count: SENDER_WINDOW_MAX } } };
  const rejected = incrementWindows(data, 'alice', now);
  assert.strictEqual(rejected.allowed, false);

  // A different sender in the same pair is unaffected by alice's exhausted window.
  const other = incrementWindows(data, 'bob', now);
  assert.strictEqual(other.allowed, true);
  assert.strictEqual(other.senders.alice.count, SENDER_WINDOW_MAX);
  assert.strictEqual(other.senders.bob.count, 1);

  // The sender window expires on its own span.
  const tomorrow = incrementWindows(data, 'alice', now + SENDER_WINDOW_MS);
  assert.strictEqual(tomorrow.allowed, true);
  assert.strictEqual(tomorrow.senders.alice.count, 1);
});

// Plan §12 / §17 line 638: "Rate limit rejection occurs before any installation lookup or
// dispatch attempt (verified via call-count assertions on the dispatch mock)."
function handlerHarness(overrides = {}) {
  const calls = { pairingActive: 0, checkRateLimit: 0, markMailbox: [], listInstallations: 0, dispatch: 0 };
  const handler = createRelayHandler({
    pairingActive: async () => { calls.pairingActive += 1; return true; },
    checkRateLimit: async () => { calls.checkRateLimit += 1; return true; },
    markMailbox: async (recipientUid, messageId, pairId, status) => { calls.markMailbox.push([messageId, pairId, status]); },
    listInstallations: async () => { calls.listInstallations += 1; return [{ id: 'i1' }]; },
    dispatch: async () => { calls.dispatch += 1; },
    logger: { info() {}, warn() {} },
    ...overrides,
  });
  return { calls, handler };
}

const relayEvent = (payload) => ({
  params: { recipientUid: 'bob', messageId: 'm1' },
  data: { data: () => ({ senderUid: 'alice', pairId: 'pair-1', payload }) },
});

test('rate-limit rejection happens before any installation lookup or dispatch', async () => {
  const { calls, handler } = handlerHarness({ checkRateLimit: async () => false });
  await handler(relayEvent(b64(64)));
  assert.deepStrictEqual(calls.markMailbox, [['m1', 'pair-1', 'RATE_LIMITED']]);
  assert.strictEqual(calls.listInstallations, 0);
  assert.strictEqual(calls.dispatch, 0);
});

test('oversized payload is rejected before the pairing check, lookup, or dispatch', async () => {
  const { calls, handler } = handlerHarness();
  await handler(relayEvent(b64(MAX_PAYLOAD_BYTES + 1)));
  assert.deepStrictEqual(calls.markMailbox, [['m1', 'pair-1', 'OVERSIZED']]);
  assert.strictEqual(calls.pairingActive, 0);
  assert.strictEqual(calls.listInstallations, 0);
  assert.strictEqual(calls.dispatch, 0);
});

test('an inactive pairing is rejected before the rate limit, lookup, or dispatch', async () => {
  const { calls, handler } = handlerHarness({ pairingActive: async () => false });
  await handler(relayEvent(b64(64)));
  assert.deepStrictEqual(calls.markMailbox, [['m1', 'pair-1', 'PAIRING_INVALID']]);
  assert.strictEqual(calls.checkRateLimit, 0);
  assert.strictEqual(calls.listInstallations, 0);
  assert.strictEqual(calls.dispatch, 0);
});

test('an accepted message dispatches to every selected installation', async () => {
  const { calls, handler } = handlerHarness({
    listInstallations: async () => [{ id: 'i1' }, { id: 'i2' }],
  });
  await handler(relayEvent(b64(64)));
  assert.deepStrictEqual(calls.markMailbox, []);
  assert.strictEqual(calls.dispatch, 2);
});

test('an accepted message with no installations records nothing and dispatches nothing', async () => {
  const { calls, handler } = handlerHarness({ listInstallations: async () => [] });
  await handler(relayEvent(b64(64)));
  assert.deepStrictEqual(calls.markMailbox, []);
  assert.strictEqual(calls.dispatch, 0);
});
