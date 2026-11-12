'use strict';
// Partner event delivery v1 — server half of the cross-language protocol contract
// (partner event-delivery plan §7 Phase 1, §8.2–§8.4). Pure logic only: no
// firebase-functions/firebase-admin import, so `node --test test/partner-events.test.js`
// runs without an emulator. The Phase 2 callables build on these functions.
//
// Kotlin mirror: app/src/main/java/com/example/myexodus/accountability/delivery/
//   Jcs.kt, PartnerEventProtocol.kt
// Shared golden vectors: test/fixtures/partner-event-jcs-v1.json (both languages read it).

const crypto = require('node:crypto');

const VERSION = 1;
const ENCODING_EXISTING_E2EE = 'existing-e2ee';
const MAX_EVENT_BYTES = 16 * 1024;
const MAX_BATCH_EVENTS = 20;
const MAX_BATCH_BYTES = 256 * 1024;
const MAX_SAFE_INTEGER = 9007199254740991;

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const FIREBASE_UID = /^[A-Za-z0-9_-]{1,128}$/;
const BASE64URL = /^[A-Za-z0-9_-]+$/;
// base64url(SHA-256(SPKI DER)) is exactly 43 characters, unpadded.
const KEY_ID = /^[A-Za-z0-9_-]{43}$/;

const KIND = Object.freeze({
  BLOCK_ATTEMPT: 'BLOCK_ATTEMPT',
  CHECK_IN: 'CHECK_IN',
  RULE_CHANGE_ATTEMPT: 'RULE_CHANGE_ATTEMPT',
  // §13.3 staged transport for every other partner-visible event class: the E2EE body is the
  // legacy AccountabilityEvent / EventPayload wire, so no safe metadata is defined.
  ACCOUNTABILITY_UPDATE: 'ACCOUNTABILITY_UPDATE',
  MONITORING_UPDATE: 'MONITORING_UPDATE',
});

// §8.4 per-event callable response codes.
const RESULT = Object.freeze({
  ACCEPTED: 'ACCEPTED',
  ALREADY_ACCEPTED: 'ALREADY_ACCEPTED',
  RECIPIENT_KEY_STALE: 'RECIPIENT_KEY_STALE',
  RETRYABLE: 'RETRYABLE',
  REENROLL_REQUIRED: 'REENROLL_REQUIRED',
  PERMANENT_REJECTED: 'PERMANENT_REJECTED',
});

const ISSUE = Object.freeze({
  VERSION_UNSUPPORTED: 'VERSION_UNSUPPORTED',
  EVENT_ID_NOT_UUID_V4: 'EVENT_ID_NOT_UUID_V4',
  INSTALLATION_ID_NOT_UUID_V4: 'INSTALLATION_ID_NOT_UUID_V4',
  OWNER_UID_INVALID: 'OWNER_UID_INVALID',
  SEQUENCE_INVALID: 'SEQUENCE_INVALID',
  KIND_UNSUPPORTED: 'KIND_UNSUPPORTED',
  OCCURRED_AT_INVALID: 'OCCURRED_AT_INVALID',
  METADATA_INVALID: 'METADATA_INVALID',
  RECIPIENTS_INVALID: 'RECIPIENTS_INVALID',
  SUBMISSION_INVALID: 'SUBMISSION_INVALID',
  TOO_LARGE: 'TOO_LARGE',
});

// Closed safe-metadata allowlist — mirrors the Kotlin METADATA_VALUES. Free text, URLs,
// names, notes and identifiers are structurally impossible.
const METADATA_VALUES = Object.freeze({
  category: Object.freeze(['BLOCKED_APP_ATTEMPT', 'BLOCKED_DOMAIN_ATTEMPT']),
  ruleChange: Object.freeze(['CREATED', 'ASSIGNED', 'UNASSIGNED', 'UPDATED', 'DISABLED', 'DELETED', 'EXPIRED']),
});

// §10.10 Firestore layout. All writes are Admin SDK only. partnerMailboxes is the single
// recipient-readable surface (encrypted payloads the partner already holds the key for).
//
//   partnerDeviceSigningRegistrations/{ownerUid}:{installationId}
//     ownerUid, installationId, signingKeyId, signingSpkiDerBase64, recipientKeyId,
//     integrityTier (ACTIVE_HARDWARE|ACTIVE_UNATTESTED), registrationRevision,
//     status (ACTIVE|REENROLL_REQUIRED|REVOKED), activatedAt, revokedAt, lastRotatedAt,
//     rotationAudit[]  — §8.3.7; the server never stores private-key material
//   partnerDeviceChallenges/{challengeId}
//     ownerUid, installationId, intent, challengeHashHex, issuedAt, expiresAt, consumedAt
//     — one-time, five-minute lifetime (§8.3.2)
//   partnerRecipientKeys/{keyId}
//     ownerUid, installationId, keyId, algorithm ('ECDH-P256'), spkiDerBase64,
//     revision, status (ACTIVE|REVOKED), registeredAt, revokedAt, replacedByKeyId
//     — the server-side recipient encryption-key registry missing at Phase 0 (§0/§16.5)
//   partnerEvents/{ownerUid}/installations/{installationId}/events/{eventId}
//     eventId, ownerUid, installationId, sequence, kind, deviceOccurredAtMs,
//     safeMetadata, recipientKeyIds, digestSha256, signingKeyId, signature,
//     receivedAt, status — immutable after commit (§8.4)
//   partnerMailboxes/{recipientUid}/messages/{eventId}__{recipientInstallationId}
//     ownerUid (sender), eventId, recipientKeyId, recipientKeyRevision, payload,
//     encoding, pairId, createdAt — E2EE ciphertext only, never plaintext (§8.6). One
//     record per recipient installation, 1:1 with its notification job, because each
//     installation holds its own recipient key.
//   partnerNotificationOutbox/{eventId}__{recipientInstallationId}
//     eventId, recipientUid, recipientInstallationId, groupId, status,
//     leaseUntil, sentAt, createdAt — one job per accepted mailbox recipient (§8.6)
//   partnerFanoutTasks/{eventId}
//     eventId, ownerUid, installationId, kind, recipients[{recipientUid,
//     recipientInstallationId, recipientKeyId, recipientKeyRevision, payload, encoding,
//     pairId}], status (PENDING|DONE), createdAt — the durable authorized fanout work
//     record for a batch over MAX_INLINE_RECIPIENTS (R08), materialized by Phase 5
//   partnerDeviceLiveness/{installationId}
//     ownerUid, lastAcceptedContactAt, lastHeartbeatCounter, lastObservedEnforcement,
//     enrolledAt, status, outageEpisodeId, pendingProbeId, probeSentAt, probeDeadlineAt,
//     alertedAt, restoredAt, suppressionReason, earliestAlertAt (§8.7)
const COLLECTIONS = Object.freeze({
  deviceRegistrations: 'partnerDeviceSigningRegistrations',
  deviceChallenges: 'partnerDeviceChallenges',
  recipientKeys: 'partnerRecipientKeys',
  events: 'partnerEvents',
  mailboxes: 'partnerMailboxes',
  notificationOutbox: 'partnerNotificationOutbox',
  fanoutTasks: 'partnerFanoutTasks',
  deviceLiveness: 'partnerDeviceLiveness',
});

const RESULT_CODES = Object.freeze(Object.values(RESULT));
const ISSUE_CODES = Object.freeze(Object.values(ISSUE));

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

// RFC 8785 §3.2.2.2 string serialization (ECMAScript JSON.stringify rules). Rejects
// unpaired surrogates like the Kotlin encoder so both sides accept exactly the same bytes.
function quote(value) {
  let out = '"';
  for (let i = 0; i < value.length; i += 1) {
    const c = value[i];
    const code = value.charCodeAt(i);
    if (c === '"') out += '\\"';
    else if (c === '\\') out += '\\\\';
    else if (c === '\b') out += '\\b';
    else if (c === '\f') out += '\\f';
    else if (c === '\n') out += '\\n';
    else if (c === '\r') out += '\\r';
    else if (c === '\t') out += '\\t';
    else if (code < 0x20) out += `\\u${code.toString(16).padStart(4, '0')}`;
    else if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(i + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error('unpaired high surrogate');
      out += c + value[i + 1];
      i += 1;
    } else if (code >= 0xdc00 && code <= 0xdfff) throw new Error('unpaired low surrogate');
    else out += c;
  }
  return `${out}"`;
}

function appendValue(value, out) {
  if (value === null) out.push('null');
  else if (typeof value === 'boolean') out.push(value ? 'true' : 'false');
  else if (typeof value === 'string') out.push(quote(value));
  else if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) throw new Error('unsafe JCS integer');
    out.push(String(value));
  } else if (Array.isArray(value)) {
    out.push('[');
    value.forEach((item, index) => {
      if (index > 0) out.push(',');
      appendValue(item, out);
    });
    out.push(']');
  } else if (isPlainObject(value)) {
    out.push('{');
    // Default sort compares UTF-16 code units, which is RFC 8785's rule.
    Object.keys(value).sort().forEach((key, index) => {
      if (index > 0) out.push(',');
      out.push(quote(key), ':');
      appendValue(value[key], out);
    });
    out.push('}');
  } else {
    throw new Error(`unsupported JCS value: ${typeof value}`);
  }
}

/** RFC 8785 canonical JSON for the constrained value set the protocol carries. */
function canonicalJson(value) {
  const out = [];
  appendValue(value, out);
  return out.join('');
}

function canonicalBytes(event) {
  return Buffer.from(canonicalJson(event), 'utf8');
}

function digest(event) {
  return crypto.createHash('sha256').update(canonicalBytes(event)).digest('hex');
}

function eventToWireValue(event) {
  // §8.2 exact envelope shape. The Kotlin toJcsValue() emits the same key set.
  return {
    v: event.v,
    eventId: event.eventId,
    installationId: event.installationId,
    ownerUid: event.ownerUid,
    sequence: event.sequence,
    kind: event.kind,
    deviceOccurredAtMs: event.deviceOccurredAtMs,
    metadata: event.metadata || {},
    recipientEnvelopes: (event.recipientEnvelopes || []).map((envelope) => ({
      recipientEncryptionKeyId: envelope.recipientEncryptionKeyId,
      version: envelope.version,
      encoding: envelope.encoding,
      payload: envelope.payload,
    })),
  };
}

function isString(value) {
  return typeof value === 'string';
}

const EVENT_KEYS = ['v', 'eventId', 'installationId', 'ownerUid', 'sequence', 'kind',
  'deviceOccurredAtMs', 'metadata', 'recipientEnvelopes'];
const ENVELOPE_KEYS = ['recipientEncryptionKeyId', 'version', 'encoding', 'payload'];

// Strict typed schema: the Kotlin encoder cannot emit unknown fields, so an unknown field
// here is hand-built or tampered input and is rejected rather than silently normalized.
function unknownField(obj, allowed) {
  return Object.keys(obj).some((key) => !allowed.includes(key));
}

function metadataIssue(event) {
  const metadata = event.metadata === undefined ? {} : event.metadata;
  if (!isPlainObject(metadata)) return ISSUE.METADATA_INVALID;
  const keys = Object.keys(metadata);
  if (keys.length > Object.keys(METADATA_VALUES).length) return ISSUE.METADATA_INVALID;
  for (const key of keys) {
    const allowed = METADATA_VALUES[key];
    if (!allowed) return ISSUE.METADATA_INVALID;
    if (!isString(metadata[key]) || !allowed.includes(metadata[key])) {
      return ISSUE.METADATA_INVALID;
    }
  }
  if (event.kind === KIND.BLOCK_ATTEMPT && !keys.includes('category')) return ISSUE.METADATA_INVALID;
  if (event.kind === KIND.RULE_CHANGE_ATTEMPT && !keys.includes('ruleChange')) return ISSUE.METADATA_INVALID;
  // No safe metadata is defined for these kinds; the content rides the E2EE payload.
  const payloadOnly = [KIND.CHECK_IN, KIND.ACCOUNTABILITY_UPDATE, KIND.MONITORING_UPDATE];
  if (payloadOnly.includes(event.kind) && keys.length > 0) return ISSUE.METADATA_INVALID;
  return null;
}

function recipientIssue(envelopes) {
  if (!Array.isArray(envelopes) || envelopes.length === 0) return ISSUE.RECIPIENTS_INVALID;
  const seen = new Set();
  for (const envelope of envelopes) {
    if (!isPlainObject(envelope)) return ISSUE.RECIPIENTS_INVALID;
    if (unknownField(envelope, ENVELOPE_KEYS)) return ISSUE.RECIPIENTS_INVALID;
    if (!isString(envelope.recipientEncryptionKeyId) || !KEY_ID.test(envelope.recipientEncryptionKeyId)) {
      return ISSUE.RECIPIENTS_INVALID;
    }
    if (seen.has(envelope.recipientEncryptionKeyId)) return ISSUE.RECIPIENTS_INVALID;
    seen.add(envelope.recipientEncryptionKeyId);
    if (!Number.isSafeInteger(envelope.version) || envelope.version < 1) return ISSUE.RECIPIENTS_INVALID;
    if (envelope.encoding !== ENCODING_EXISTING_E2EE) return ISSUE.RECIPIENTS_INVALID;
    if (!isString(envelope.payload) || !BASE64URL.test(envelope.payload)) return ISSUE.RECIPIENTS_INVALID;
  }
  return null;
}

/** Null = valid. Order matches the Kotlin validator; every rule is mandatory. */
function validateEvent(rawEvent) {
  if (!isPlainObject(rawEvent)) return ISSUE.METADATA_INVALID;
  if (unknownField(rawEvent, EVENT_KEYS)) return ISSUE.SUBMISSION_INVALID;
  if (rawEvent.metadata !== undefined && unknownField(
    isPlainObject(rawEvent.metadata) ? rawEvent.metadata : {}, ['category', 'ruleChange'],
  )) return ISSUE.METADATA_INVALID;
  const event = eventToWireValue(rawEvent);
  if (event.v !== VERSION) return ISSUE.VERSION_UNSUPPORTED;
  if (!isString(event.eventId) || !UUID_V4.test(event.eventId)) return ISSUE.EVENT_ID_NOT_UUID_V4;
  if (!isString(event.installationId) || !UUID_V4.test(event.installationId)) {
    return ISSUE.INSTALLATION_ID_NOT_UUID_V4;
  }
  if (!isString(event.ownerUid) || !FIREBASE_UID.test(event.ownerUid)) return ISSUE.OWNER_UID_INVALID;
  if (!Number.isSafeInteger(event.sequence) || event.sequence < 1) return ISSUE.SEQUENCE_INVALID;
  if (!isString(event.kind) || !Object.values(KIND).includes(event.kind)) return ISSUE.KIND_UNSUPPORTED;
  if (!Number.isSafeInteger(event.deviceOccurredAtMs) || event.deviceOccurredAtMs < 1) {
    return ISSUE.OCCURRED_AT_INVALID;
  }
  const meta = metadataIssue(event);
  if (meta) return meta;
  const recipients = recipientIssue(event.recipientEnvelopes);
  if (recipients) return recipients;
  if (canonicalBytes(event).length > MAX_EVENT_BYTES) return ISSUE.TOO_LARGE;
  return null;
}

function validateSubmission(wire) {
  if (!isPlainObject(wire)) return ISSUE.SUBMISSION_INVALID;
  if (unknownField(wire, ['keyId', 'event', 'signature'])) return ISSUE.SUBMISSION_INVALID;
  if (!isString(wire.keyId) || !KEY_ID.test(wire.keyId)) return ISSUE.SUBMISSION_INVALID;
  if (!isString(wire.signature) || !BASE64URL.test(wire.signature)) return ISSUE.SUBMISSION_INVALID;
  return validateEvent(wire.event);
}

/** §8.2 batch limits: 20 events and a 256 KiB encoded body. */
function validateBatch(events) {
  if (!Array.isArray(events) || events.length === 0 || events.length > MAX_BATCH_EVENTS) {
    return ISSUE.TOO_LARGE;
  }
  let total = 0;
  for (const wire of events) {
    const issue = validateSubmission(wire);
    if (issue) return issue;
    total += canonicalBytes(eventToWireValue(wire.event)).length;
    if (total > MAX_BATCH_BYTES) return ISSUE.TOO_LARGE;
  }
  return null;
}

/**
 * Verifies a base64url DER ECDSA (SHA-256) signature over the exact JCS bytes. Never
 * throws; an unparseable key or signature is a failed verification, not a crash.
 */
function verifyEventSignature(event, signatureBase64Url, publicKey) {
  try {
    let key = publicKey;
    if (typeof publicKey === 'string') {
      key = publicKey.includes('BEGIN')
        ? crypto.createPublicKey(publicKey)
        : crypto.createPublicKey({
          key: Buffer.from(publicKey, 'base64url'), format: 'der', type: 'spki',
        });
    }
    const signature = Buffer.from(signatureBase64Url, 'base64url');
    return crypto.verify('sha256', canonicalBytes(eventToWireValue(event)), key, signature);
  } catch {
    return false;
  }
}

/** Only these two outcomes may destroy the locally wrapped source content (§8.2). */
function isAcknowledged(code) {
  return code === RESULT.ACCEPTED || code === RESULT.ALREADY_ACCEPTED;
}

/**
 * I11/§8.1: recipient ciphertext and the signature may be regenerated only after a
 * definitive pre-effect rejection. Never for RETRYABLE or an unknown outcome.
 */
function allowsCiphertextRegeneration(code) {
  return code === RESULT.RECIPIENT_KEY_STALE;
}

module.exports = {
  VERSION,
  ENCODING_EXISTING_E2EE,
  MAX_EVENT_BYTES,
  MAX_BATCH_EVENTS,
  MAX_BATCH_BYTES,
  KIND,
  RESULT,
  RESULT_CODES,
  ISSUE,
  ISSUE_CODES,
  METADATA_VALUES,
  COLLECTIONS,
  canonicalJson,
  canonicalBytes,
  eventToWireValue,
  digest,
  validateEvent,
  validateSubmission,
  validateBatch,
  verifyEventSignature,
  isAcknowledged,
  allowsCiphertextRegeneration,
};
