'use strict';
// appendUserEvent input validation + replay classification (plan §8.8) — pure, `npm test`.

const { test } = require('node:test');
const assert = require('node:assert');

const {
  MAX_CIPHERTEXT_CHARS,
  validateAppendInput,
  classifyExistingEvent,
} = require('../event-append-logic');

const valid = () => ({
  pairId: 'uidA_uidB',
  eventId: '3f2b8c1a-9d4e-4f6a-8b7c-1e2d3f4a5b6c',
  senderId: 'sender-key-id',
  keyEpoch: 2,
  ciphertextB64: 'AAECAw==',
});

test('a well-formed append is accepted', () => {
  assert.deepStrictEqual(validateAppendInput(valid()), { ok: true });
});

test('the outer field allowlist is exact — extras and misses are rejected', () => {
  assert.strictEqual(validateAppendInput({ ...valid(), extra: 1 }).reason, 'FIELD_ALLOWLIST');
  const missing = valid();
  delete missing.keyEpoch;
  assert.strictEqual(validateAppendInput(missing).reason, 'FIELD_ALLOWLIST');
  assert.strictEqual(validateAppendInput(null).reason, 'INVALID_INPUT');
  assert.strictEqual(validateAppendInput([]).reason, 'INVALID_INPUT');
});

test('pair ids, event ids, sender ids and epochs are bounded and doc-id-safe', () => {
  assert.strictEqual(validateAppendInput({ ...valid(), pairId: 'a/b' }).reason, 'PAIR_ID_INVALID');
  assert.strictEqual(validateAppendInput({ ...valid(), eventId: 'a/b' }).reason, 'EVENT_ID_INVALID');
  assert.strictEqual(validateAppendInput({ ...valid(), eventId: 'short' }).reason, 'EVENT_ID_INVALID');
  assert.strictEqual(validateAppendInput({ ...valid(), senderId: '' }).reason, 'SENDER_ID_INVALID');
  assert.strictEqual(validateAppendInput({ ...valid(), senderId: 's'.repeat(129) }).reason, 'SENDER_ID_INVALID');
  assert.strictEqual(validateAppendInput({ ...valid(), keyEpoch: -1 }).reason, 'KEY_EPOCH_INVALID');
  assert.strictEqual(validateAppendInput({ ...valid(), keyEpoch: 1.5 }).reason, 'KEY_EPOCH_INVALID');
});

test('ciphertext must be ASCII base64 under the 512 KiB encoded cap', () => {
  assert.strictEqual(validateAppendInput({ ...valid(), ciphertextB64: '<script>' }).reason, 'CIPHERTEXT_INVALID');
  assert.strictEqual(validateAppendInput({ ...valid(), ciphertextB64: 'AAA' }).reason, 'CIPHERTEXT_INVALID');
  assert.strictEqual(
    validateAppendInput({ ...valid(), ciphertextB64: 'A'.repeat(MAX_CIPHERTEXT_CHARS + 4) }).reason,
    'CIPHERTEXT_INVALID',
  );
  // Exactly at the cap passes (the cap is the encoded length the rules/wire carry).
  assert.strictEqual(validateAppendInput({ ...valid(), ciphertextB64: 'A'.repeat(MAX_CIPHERTEXT_CHARS) }).ok, true);
});

test('replay classification: identical immutable fields only', () => {
  const input = valid();
  const existing = {
    senderUid: 'uidA',
    senderId: input.senderId,
    pairId: input.pairId,
    payload: input.ciphertextB64,
    keyEpoch: input.keyEpoch,
  };
  assert.strictEqual(classifyExistingEvent(existing, input, 'uidA'), 'replayed');
  assert.strictEqual(classifyExistingEvent(existing, input, 'uidC'), 'conflict');
  assert.strictEqual(classifyExistingEvent({ ...existing, payload: 'AAAA' }, input, 'uidA'), 'conflict');
  assert.strictEqual(classifyExistingEvent({ ...existing, keyEpoch: 3 }, input, 'uidA'), 'conflict');
  assert.strictEqual(classifyExistingEvent({ ...existing, senderId: 'other' }, input, 'uidA'), 'conflict');
});
