'use strict';
// Pure guards for the Release 0 pair/profile callables (plan §7 step 2 / §8.8): input
// allowlists, doc-id shapes, invitation TTL bounds and the per-UID invitation window. No
// Firestore, so `node --test` covers every rejection without an emulator.

const { windowState } = require('./rate-window');

// §8.8: invitation expiry is capped at 24 hours. The shipped typed-code token is 10 minutes
// (owner-approved); the cap is the security ceiling, not a floor — a client cannot mint a
// long-lived rendezvous document.
const MAX_INVITATION_TTL_MS = 24 * 60 * 60 * 1000;
// A CBOR EnrollmentPayload carries up to ~13.4 KB of base64 attestation chain; 32 KiB
// decoded leaves generous headroom without accepting arbitrary blobs.
const MAX_INVITATION_PAYLOAD_CHARS = 44 * 1024;
const MAX_SIGNATURE_CHARS = 8 * 1024;
const INVITATION_WINDOW_MS = 60 * 60 * 1000;
const INVITATION_LIMIT = 10;

const INVITATION_INPUT_KEYS = ['expiresAtEpochMillis', 'payloadB64', 'tokenId'];
const RESPONSE_INPUT_KEYS = ['partnerPayloadB64', 'partnerSignatureB64', 'tokenId'];

// The token document id is SHA-256(token) hex — the client mints the token, the server
// stores only its hash (the possession capability).
const TOKEN_ID_PATTERN = /^[0-9a-f]{64}$/;
const PAIR_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
const BASE64_PATTERN = /^[A-Za-z0-9+/]*={0,2}$/;

function isBoundedBase64(value, maxChars) {
  return typeof value === 'string' &&
    value.length <= maxChars &&
    value.length % 4 === 0 &&
    BASE64_PATTERN.test(value);
}

function hasExactKeys(data, expectedKeys) {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) return false;
  const keys = Object.keys(data).sort();
  return keys.length === expectedKeys.length && expectedKeys.every((key, i) => keys[i] === key);
}

function isValidPairId(value) {
  return typeof value === 'string' && PAIR_ID_PATTERN.test(value);
}

function validateInvitationInput(data, nowMs) {
  if (!hasExactKeys(data, INVITATION_INPUT_KEYS)) return { ok: false, reason: 'FIELD_ALLOWLIST' };
  if (typeof data.tokenId !== 'string' || !TOKEN_ID_PATTERN.test(data.tokenId)) {
    return { ok: false, reason: 'TOKEN_ID_INVALID' };
  }
  const expiresAt = data.expiresAtEpochMillis;
  if (!Number.isInteger(expiresAt) || expiresAt <= nowMs) {
    return { ok: false, reason: 'EXPIRY_INVALID' };
  }
  if (expiresAt > nowMs + MAX_INVITATION_TTL_MS) {
    return { ok: false, reason: 'EXPIRY_TOO_FAR' };
  }
  if (!isBoundedBase64(data.payloadB64, MAX_INVITATION_PAYLOAD_CHARS)) {
    return { ok: false, reason: 'PAYLOAD_INVALID' };
  }
  return { ok: true };
}

function validateResponseInput(data) {
  if (!hasExactKeys(data, RESPONSE_INPUT_KEYS)) return { ok: false, reason: 'FIELD_ALLOWLIST' };
  if (typeof data.tokenId !== 'string' || !TOKEN_ID_PATTERN.test(data.tokenId)) {
    return { ok: false, reason: 'TOKEN_ID_INVALID' };
  }
  if (!isBoundedBase64(data.partnerPayloadB64, MAX_INVITATION_PAYLOAD_CHARS)) {
    return { ok: false, reason: 'PAYLOAD_INVALID' };
  }
  if (!isBoundedBase64(data.partnerSignatureB64, MAX_SIGNATURE_CHARS)) {
    return { ok: false, reason: 'SIGNATURE_INVALID' };
  }
  return { ok: true };
}

/**
 * §8.8 "rate-limits by UID": same fixed-window shape as the pairing-path counter (the
 * emulator suite caught a write-only counter once — the caller must persist the returned
 * window in the same transaction that read it).
 */
function advanceInvitationWindow(data, nowMs) {
  const window = windowState(data && data.window, nowMs, INVITATION_WINDOW_MS);
  if (window.count >= INVITATION_LIMIT) {
    return { allowed: false, retryAfterMs: Math.max(1, INVITATION_WINDOW_MS - (nowMs - window.start)) };
  }
  window.count += 1;
  return { allowed: true, window };
}

module.exports = {
  MAX_INVITATION_TTL_MS,
  MAX_INVITATION_PAYLOAD_CHARS,
  MAX_SIGNATURE_CHARS,
  INVITATION_WINDOW_MS,
  INVITATION_LIMIT,
  isValidPairId,
  hasExactKeys,
  isBoundedBase64,
  validateInvitationInput,
  validateResponseInput,
  advanceInvitationWindow,
};
