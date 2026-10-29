'use strict';
// Pair/profile callable guards (plan §8.8/§8.10) — pure, `npm test`.

const { test } = require('node:test');
const assert = require('node:assert');

const {
  MAX_INVITATION_TTL_MS,
  INVITATION_LIMIT,
  INVITATION_WINDOW_MS,
  isValidPairId,
  validateInvitationInput,
  validateResponseInput,
  advanceInvitationWindow,
} = require('../pair-lifecycle-logic');

const NOW = 1_760_000_000_000;
const TOKEN_ID = 'a'.repeat(64);

const invitation = (overrides = {}) => ({
  tokenId: TOKEN_ID,
  expiresAtEpochMillis: NOW + 10 * 60 * 1000,
  payloadB64: 'AQID',
  ...overrides,
});

test('a well-formed invitation registration is accepted', () => {
  assert.deepStrictEqual(validateInvitationInput(invitation(), NOW), { ok: true });
});

test('identical offline token responses are accepted', () => {
  assert.deepStrictEqual(
    validateResponseInput({ tokenId: TOKEN_ID, partnerPayloadB64: 'AQID', partnerSignatureB64: 'BAUG' }),
    { ok: true },
  );
});

test('invitation fields are an exact allowlist and the token id is a sha256 hex', () => {
  assert.strictEqual(validateInvitationInput({ ...invitation(), extra: 1 }, NOW).reason, 'FIELD_ALLOWLIST');
  assert.strictEqual(validateInvitationInput(invitation({ tokenId: 'not-a-hash' }), NOW).reason, 'TOKEN_ID_INVALID');
  assert.strictEqual(validateInvitationInput(invitation({ tokenId: 'A'.repeat(64) }), NOW).reason, 'TOKEN_ID_INVALID');
  assert.strictEqual(
    validateResponseInput({ tokenId: TOKEN_ID, partnerPayloadB64: 'AQID' }).reason,
    'FIELD_ALLOWLIST',
  );
});

test('invitation expiry must be in the future and inside the 24-hour ceiling', () => {
  assert.strictEqual(validateInvitationInput(invitation({ expiresAtEpochMillis: NOW }), NOW).reason, 'EXPIRY_INVALID');
  assert.strictEqual(validateInvitationInput(invitation({ expiresAtEpochMillis: NOW + 1.5 }), NOW).reason, 'EXPIRY_INVALID');
  assert.strictEqual(
    validateInvitationInput(invitation({ expiresAtEpochMillis: NOW + MAX_INVITATION_TTL_MS + 1 }), NOW).reason,
    'EXPIRY_TOO_FAR',
  );
  assert.strictEqual(
    validateInvitationInput(invitation({ expiresAtEpochMillis: NOW + MAX_INVITATION_TTL_MS }), NOW).ok,
    true,
  );
});

test('payloads and signatures are bounded base64', () => {
  assert.strictEqual(validateInvitationInput(invitation({ payloadB64: '<payload>' }), NOW).reason, 'PAYLOAD_INVALID');
  assert.strictEqual(
    validateResponseInput({ tokenId: TOKEN_ID, partnerPayloadB64: 'AQID', partnerSignatureB64: 'not base64!' }).reason,
    'SIGNATURE_INVALID',
  );
});

test('the per-UID invitation window admits ten an hour and never charges a rejection', () => {
  const fresh = advanceInvitationWindow(null, NOW);
  assert.strictEqual(fresh.allowed, true);
  assert.deepStrictEqual(fresh.window, { start: NOW, count: 1 });

  const full = advanceInvitationWindow({ window: { start: NOW, count: INVITATION_LIMIT } }, NOW);
  assert.strictEqual(full.allowed, false);
  assert.ok(full.retryAfterMs > 0 && full.retryAfterMs <= INVITATION_WINDOW_MS);

  const nextWindow = advanceInvitationWindow(
    { window: { start: NOW - INVITATION_WINDOW_MS, count: INVITATION_LIMIT } },
    NOW,
  );
  assert.strictEqual(nextWindow.allowed, true);
  assert.deepStrictEqual(nextWindow.window, { start: NOW, count: 1 });
});

test('pair ids are doc-id-safe', () => {
  assert.strictEqual(isValidPairId('alice-uid_bob-uid'), true);
  assert.strictEqual(isValidPairId('a/b'), false);
  assert.strictEqual(isValidPairId(''), false);
  assert.strictEqual(isValidPairId(null), false);
});
