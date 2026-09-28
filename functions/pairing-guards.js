'use strict';
// Plan §19.4 item 3 against the shipped surface (§19.3: the five session callables are
// superseded; their security requirements bind to `registerPairing` + the rules-mediated
// token doc instead). Three guards, all pure so `node --test` covers them without an
// emulator:
//
// 1. §9 corrected idempotency: the callable accepts a client-generated `requestId`; the
//    request hash (function name + normalized payload) is compared against a cached record
//    BEFORE anything else re-processes. A replay of a committed request returns its
//    committed result even if the pairing has since terminated or been re-paired — which is
//    what makes a lost-response retry safe and keeps a stale in-flight proof from silently
//    resurrecting a dead pairing.
// 2. §10.5 pairing-path rate limits: a per-UID fixed-window counter (shared fixed-window
//    math, ./rate-window). The plan's per-role numbers (5 create + 10 join per UID/hour)
//    merge to one 10/hour cap because the shipped surface cannot tell initiator submissions
//    from partner submissions — both sides send one identical-shape proof.
// 3. §10.11/§13 bounded request payload: the evidence payloads carry up to four PEM-sized
//    DER certificates, so the ceiling is checked against the parsed request before any
//    crypto or Firestore work.

const crypto = require('node:crypto');
const { windowState } = require('./rate-window');

/** §10.12: idempotency records are retained 24h, then the scheduled cleanup deletes them. */
const IDEMPOTENCY_RETENTION_MS = 24 * 60 * 60 * 1000;

// Firestore doc-id-safe by construction: the requestId becomes the record's document id,
// so '/' (path injection) and the reserved '.'/'..' ids must be impossible. UUID v4 from
// the shipped client fits with room to spare.
const REQUEST_ID_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;

// §10.5 merged per-UID pairing budget: 10 registerPairing executions per UID per hour.
const PAIRING_LIMIT_WINDOW_MS = 60 * 60 * 1000;
const PAIRING_LIMIT_MAX = 10;

// §10.11/§13 bounded request payload. Measured against the PARSED request (the v2 callable
// transport's own request ceiling is not configurable per function); 16 KB leaves headroom
// over the worst legitimate proof — up to four leaf-first DER certs, ~2.5 KB each in the
// wild for Google chains, ≈ 13.4 KB as base64 — plus every string field. §12.3 records the
// device-measured chain size.
const MAX_REQUEST_BYTES = 16 * 1024;

function isWithinRequestSizeLimit(request) {
  return Buffer.byteLength(JSON.stringify(request), 'utf8') <= MAX_REQUEST_BYTES;
}

function isValidRequestId(requestId) {
  return typeof requestId === 'string' && REQUEST_ID_PATTERN.test(requestId);
}

/**
 * §9 step 2: requestHash = hash(function name + normalized payload). The requestId itself
 * is the key, not payload — excluded. Canonical JSON (sorted keys, array order kept) makes
 * the hash independent of field order in the wire map and deterministic across replays.
 */
function requestHashOf(proof) {
  const payload = { ...proof };
  delete payload.requestId;
  return crypto.createHash('sha256')
    .update(`registerPairing\n${canonicalJson(payload)}`, 'utf8')
    .digest('hex');
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${canonicalJson(value[k])}`).join(',')}}`;
  }
  return JSON.stringify(value) ?? 'null';
}

/**
 * §10.5 gate for one genuine execution (replays never reach it — idempotency lookup runs
 * first). Advances the UID's window and returns the state to persist, or { allowed: false }
 * so a rejected attempt is never counted and never extends the window it was rejected by.
 * The caller writes the result inside the same Firestore transaction that read the state —
 * that is what makes the counter race-free.
 *
 * `data` is the whole `/rate-limits/pairing_{uid}` document (`{ window }`), mirroring
 * `incrementWindows(data, …)` in relay-logic: the unwrapping lives here so a caller cannot
 * pass a bare window object and silently restart the count on every execution — the exact
 * defect the emulator suite caught 2026-09-28 (the counter was written but never read back,
 * so the limit never engaged in production).
 */
function advancePairingWindow(data, now) {
  const window = windowState(data && data.window, now, PAIRING_LIMIT_WINDOW_MS);
  if (window.count >= PAIRING_LIMIT_MAX) return { allowed: false };
  window.count += 1;
  return { allowed: true, window };
}

module.exports = {
  IDEMPOTENCY_RETENTION_MS,
  PAIRING_LIMIT_WINDOW_MS,
  PAIRING_LIMIT_MAX,
  MAX_REQUEST_BYTES,
  isWithinRequestSizeLimit,
  isValidRequestId,
  requestHashOf,
  canonicalJson,
  advancePairingWindow,
};
