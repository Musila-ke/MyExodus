'use strict';
// Pure input validation and replay classification for `appendUserEvent` (plan §8.8): exact
// outer field allowlist, per-field bounds, ASCII base64 ciphertext, fixed record kind. Kept
// out of the handler so `node --test` covers every rejection without an emulator.

const { isValidRequestId } = require('./pairing-guards');

const MAX_SENDER_ID_LENGTH = 128;
const MAX_KEY_EPOCH = 1_000_000;
// §8.8: the encoded ciphertext cap, checked on the base64 characters (what crosses the
// callable wire), never on a decoded map size.
const MAX_CIPHERTEXT_CHARS = 512 * 1024;
const RECORD_KIND = 'USER_ENCRYPTED';
const SCHEMA_VERSION = 1;

const REQUIRED_INPUT_KEYS = ['ciphertextB64', 'eventId', 'keyEpoch', 'pairId', 'senderId'];
// §13.3 dual-acceptance: optional device identification. Old APKs omit it and keep working
// during the compatibility window; a sender that supplies it can be checked against its
// registration before the legacy path accepts anything.
const OPTIONAL_INPUT_KEYS = ['installationId'];
const INPUT_KEYS = [...REQUIRED_INPUT_KEYS, ...OPTIONAL_INPUT_KEYS];

// pairId is min(uid)+"_"+max(uid) — Firestore doc-id-safe by construction. Auth uids are
// alphanumeric; the wider pattern covers both without allowing a '/'.
const PAIR_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const BASE64_PATTERN = /^[A-Za-z0-9+/]*={0,2}$/;
// Same v4 pinning as the signed ingest: one installation identifier format across both paths.
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function isBoundedBase64(value, maxChars) {
  return typeof value === 'string' &&
    value.length <= maxChars &&
    value.length % 4 === 0 &&
    BASE64_PATTERN.test(value);
}

function validateAppendInput(data) {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    return { ok: false, reason: 'INVALID_INPUT' };
  }
  const keys = Object.keys(data);
  if (!keys.every((key) => INPUT_KEYS.includes(key)) ||
      !REQUIRED_INPUT_KEYS.every((key) => keys.includes(key))) {
    return { ok: false, reason: 'FIELD_ALLOWLIST' };
  }
  if (data.installationId !== undefined &&
      (typeof data.installationId !== 'string' || !UUID_V4.test(data.installationId))) {
    return { ok: false, reason: 'INSTALLATION_ID_INVALID' };
  }
  if (typeof data.pairId !== 'string' || !PAIR_ID_PATTERN.test(data.pairId)) {
    return { ok: false, reason: 'PAIR_ID_INVALID' };
  }
  if (!isValidRequestId(data.eventId)) {
    return { ok: false, reason: 'EVENT_ID_INVALID' };
  }
  if (typeof data.senderId !== 'string' ||
      data.senderId.length === 0 || data.senderId.length > MAX_SENDER_ID_LENGTH) {
    return { ok: false, reason: 'SENDER_ID_INVALID' };
  }
  if (!Number.isInteger(data.keyEpoch) || data.keyEpoch < 0 || data.keyEpoch > MAX_KEY_EPOCH) {
    return { ok: false, reason: 'KEY_EPOCH_INVALID' };
  }
  if (!isBoundedBase64(data.ciphertextB64, MAX_CIPHERTEXT_CHARS)) {
    return { ok: false, reason: 'CIPHERTEXT_INVALID' };
  }
  return { ok: true };
}

/**
 * §8.8: an existing event ID is idempotent success only when every immutable field matches
 * the request exactly; any mismatch is a conflict — never an overwrite, never a silent win.
 */
function classifyExistingEvent(existing, input, senderUid) {
  const identical = existing.senderUid === senderUid &&
    existing.senderId === input.senderId &&
    existing.pairId === input.pairId &&
    existing.payload === input.ciphertextB64 &&
    existing.keyEpoch === input.keyEpoch;
  return identical ? 'replayed' : 'conflict';
}

module.exports = {
  MAX_SENDER_ID_LENGTH,
  MAX_KEY_EPOCH,
  MAX_CIPHERTEXT_CHARS,
  RECORD_KIND,
  SCHEMA_VERSION,
  INPUT_KEYS,
  validateAppendInput,
  classifyExistingEvent,
};
