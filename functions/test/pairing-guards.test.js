'use strict';
// Runnable checks for the pairing-path guards (plan §19.4 item 3, binding §19.3: the §9
// corrected idempotency ordering and §10.5 rate limits apply to `registerPairing`). Pure
// functions, no emulator. Run with `npm test` in functions/.

const { test } = require('node:test');
const assert = require('node:assert');

const {
  IDEMPOTENCY_RETENTION_MS,
  PAIRING_LIMIT_WINDOW_MS,
  PAIRING_LIMIT_MAX,
  MAX_REQUEST_BYTES,
  isValidRequestId,
  isWithinRequestSizeLimit,
  requestHashOf,
  canonicalJson,
  advancePairingWindow,
} = require('../pairing-guards');

const UUID = '3f2b8c1a-9d4e-4f6a-8b7c-1e2d3f4a5b6c';

test('isValidRequestId accepts UUID-shaped ids within the doc-id-safe domain', () => {
  assert.strictEqual(isValidRequestId(UUID), true);
  assert.strictEqual(isValidRequestId('abcDEF012_-'), true);
  assert.strictEqual(isValidRequestId('a'.repeat(64)), true); // max length
  assert.strictEqual(isValidRequestId('a'.repeat(8)), true); // min length
});

test('isValidRequestId rejects path-injection and malformed ids', () => {
  // The requestId becomes a Firestore document id; '/' would redirect the path.
  assert.strictEqual(isValidRequestId('abc/def'), false);
  assert.strictEqual(isValidRequestId('/abc-defg'), false);
  assert.strictEqual(isValidRequestId('a.b.cdef'), false); // reserved doc ids
  assert.strictEqual(isValidRequestId('..'), false);
  assert.strictEqual(isValidRequestId('a'.repeat(7)), false); // below min
  assert.strictEqual(isValidRequestId('a'.repeat(65)), false); // above max
  assert.strictEqual(isValidRequestId(''), false);
  assert.strictEqual(isValidRequestId(null), false);
  assert.strictEqual(isValidRequestId(12345678), false);
});

test('requestHashOf is deterministic and key-order independent', () => {
  const a = { requestId: UUID, ownUid: 'uid-a', partnerUid: 'uid-b', protocolVersion: 1 };
  const b = { protocolVersion: 1, partnerUid: 'uid-b', ownUid: 'uid-a', requestId: UUID };
  assert.strictEqual(requestHashOf(a), requestHashOf(a));
  assert.strictEqual(requestHashOf(a), requestHashOf(b));
});

test('requestHashOf excludes the requestId and is sensitive to payload changes', () => {
  const base = { requestId: UUID, ownUid: 'uid-a', partnerUid: 'uid-b' };
  const otherId = { ...base, requestId: 'ffffffff-0000-4000-8000-000000000000' };
  assert.strictEqual(requestHashOf(base), requestHashOf(otherId));
  assert.notStrictEqual(requestHashOf(base), requestHashOf({ ...base, partnerUid: 'uid-c' }));
});

test('requestHashOf keeps array order significant (attestationChain)', () => {
  const base = { requestId: UUID, attestationChain: ['cert-a', 'cert-b'] };
  const swapped = { requestId: UUID, attestationChain: ['cert-b', 'cert-a'] };
  assert.notStrictEqual(requestHashOf(base), requestHashOf(swapped));
});

test('canonicalJson sorts nested object keys but preserves array order', () => {
  assert.strictEqual(canonicalJson({ b: 1, a: { d: 2, c: 3 } }), '{"a":{"c":3,"d":2},"b":1}');
  assert.strictEqual(canonicalJson(['z', 'a']), '["z","a"]');
  assert.strictEqual(canonicalJson(null), 'null');
  assert.strictEqual(canonicalJson(undefined), 'null');
});

// The argument is the `/rate-limits/pairing_{uid}` document (`{ window }`) — the shape the
// caller hands over, not a bare window object. These tests used to pass the window itself,
// which is how a counter that was written but never read back shipped live (2026-09-28).
test('advancePairingWindow allows exactly the §10.5 budget, then rejects', () => {
  const now = 1_750_000_000_000;
  let state = null;
  for (let i = 0; i < PAIRING_LIMIT_MAX; i += 1) {
    const decision = advancePairingWindow(state, now);
    assert.strictEqual(decision.allowed, true, `attempt ${i + 1} must be allowed`);
    state = { window: decision.window };
  }
  assert.strictEqual(advancePairingWindow(state, now).allowed, false); // the 11th
});

test('the persisted count is the count that is read back', () => {
  const now = 1_750_000_000_000;
  const persisted = { window: { start: now, count: 3 } };
  assert.deepStrictEqual(advancePairingWindow(persisted, now).window, { start: now, count: 4 });
});

test('a rejected attempt neither counts nor extends the window it was rejected by', () => {
  const now = 1_750_000_000_000;
  const full = { window: { start: now, count: PAIRING_LIMIT_MAX } };
  const frozen = { window: { ...full.window } };
  assert.strictEqual(advancePairingWindow(full, now + PAIRING_LIMIT_WINDOW_MS - 1).allowed, false);
  assert.deepStrictEqual(full, frozen); // the rejected attempt mutated nothing
});

test('the window rolls over at its span and on a future start (rollback guard)', () => {
  const now = 1_750_000_000_000;
  const full = { window: { start: now, count: PAIRING_LIMIT_MAX } };
  const rolled = advancePairingWindow(full, now + PAIRING_LIMIT_WINDOW_MS);
  assert.strictEqual(rolled.allowed, true);
  assert.deepStrictEqual(rolled.window, { start: now + PAIRING_LIMIT_WINDOW_MS, count: 1 });

  const skewed = advancePairingWindow(full, now - 1000); // clock moved backwards
  assert.strictEqual(skewed.allowed, true);
  assert.deepStrictEqual(skewed.window, { start: now - 1000, count: 1 });
});

test('retention constant is the 24h §10.12 window', () => {
  assert.strictEqual(IDEMPOTENCY_RETENTION_MS, 24 * 60 * 60 * 1000);
});

test('a worst-case legitimate proof fits under the §13 request ceiling', () => {
  // Four leaf-first DER certs at the upper end of real Google chains (~2.5 KB each).
  const cert = Buffer.alloc(2500, 0x41).toString('base64');
  const worst = {
    requestId: UUID,
    ownUid: 'uid-a',
    partnerUid: 'uid-b',
    publicKeyHash: 'ab'.repeat(32),
    transcriptHash: 'cd'.repeat(32),
    protocolVersion: 1,
    signingPublicKey: Buffer.alloc(294, 0x41).toString('base64'),
    signature: Buffer.alloc(72, 0x41).toString('base64'),
    attestationChain: [cert, cert, cert, cert],
    attestationChallenge: Buffer.alloc(32, 0x41).toString('base64'),
  };
  assert.strictEqual(isWithinRequestSizeLimit(worst), true);
});

test('an oversized request is rejected by the §13 ceiling', () => {
  assert.strictEqual(isWithinRequestSizeLimit({ chain: 'A'.repeat(MAX_REQUEST_BYTES + 1) }), false);
  assert.strictEqual(isWithinRequestSizeLimit(null), true); // shape errors are the handler's job
});
