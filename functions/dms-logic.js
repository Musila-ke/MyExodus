'use strict';
// Device-availability state machine for the DMS deadline processor (partner event-delivery
// plan §7 Step 8, §4 "DMS states", §8.10-§8.13). Pure — no Firebase import — so `node --test`
// drives every branch directly and the emulator suite only has to prove the wiring.
//
// The displayed state is derived at read time from the effective last-received time; the
// deadline worker exists to schedule the ONE six-hour partner notice and to persist the
// transition/idempotency state. Nothing here treats heartbeat silence as proof of tampering,
// and nothing here reads a monitoring-state flag — availability is "the app talked to the
// server," never "monitoring is active" (§8.4).

const MINUTE = 60 * 1000;

// §4 display thresholds: CHECKING_IN < 45 min, LATE 45-90, NOT_CHECKING_IN >= 90.
const LATE_AFTER_MS = 45 * MINUTE;
const NOT_CHECKING_IN_AFTER_MS = 90 * MINUTE;

// The only external partner push threshold. Deliberately not shortened for latency (§4).
const PARTNER_PUSH_AFTER_MS = 6 * 60 * MINUTE;

// §8.13 coarse buckets — the partner never sees a precise heartbeat timestamp.
const BUCKET_WITHIN_1H = 'WITHIN_1H';
const BUCKET_1_TO_6H = 'HOURS_1_TO_6';
const BUCKET_OVER_6H = 'OVER_6H';
const BUCKET_NO_DATA = 'NO_DATA';

const CONNECTION_CHECKING_IN = 'CHECKING_IN';
const CONNECTION_LATE = 'LATE';
const CONNECTION_NOT_CHECKING_IN = 'NOT_CHECKING_IN';
const CONNECTION_NO_DATA = 'NO_DATA';

// Outbox notice types. The key `(install_id, relationship_id, outage_generation, notice_type)`
// makes each one exactly-once per outage; RECOVERY is a separate type so the pair can both be
// told about the silence and about the return without either suppressing the other (§4).
const NOTICE_AVAILABILITY = 'AVAILABILITY_6H';
const NOTICE_RECOVERY = 'RECOVERY';
const NOTICE_TYPES = [NOTICE_AVAILABILITY, NOTICE_RECOVERY];

const NOTICE_PENDING = 'PENDING';
const NOTICE_DISPATCHING = 'DISPATCHING';
const NOTICE_SENT = 'SENT';
const NOTICE_SUPPRESSED = 'SUPPRESSED';
const NOTICE_FAILED_TERMINAL = 'FAILED_TERMINAL';

// Dispatch retry bound: FCM acceptance is not a delivery receipt (§8.12), and a notice whose
// send keeps failing is stale long before attempt eight.
const MAX_DISPATCH_ATTEMPTS = 6;
const DISPATCH_LEASE_MS = 2 * MINUTE;
const DISPATCH_BACKOFF_BASE_MS = MINUTE;
const DISPATCH_BACKOFF_CAP_MS = 30 * MINUTE;

// Deployment config bounds (config/dms). Anything missing or malformed uses the default — a
// half-written operator document must never turn the worker into a full-table scan.
const DEFAULT_CONFIG = Object.freeze({
  due_batch: 100,
  dispatch_batch: 100,
  late_burst_threshold: 50,
});

function normalizeConfig(data) {
  const doc = data && typeof data === 'object' ? data : {};
  const bounded = (value, fallback, min, max) =>
    Number.isInteger(value) && value >= min && value <= max ? value : fallback;
  return {
    enabled: doc.enabled !== false,
    dispatchHold: doc.dispatch_hold === true,
    dueBatch: bounded(doc.due_batch, DEFAULT_CONFIG.due_batch, 1, 500),
    dispatchBatch: bounded(doc.dispatch_batch, DEFAULT_CONFIG.dispatch_batch, 1, 500),
    lateBurstThreshold: bounded(
      doc.late_burst_threshold,
      DEFAULT_CONFIG.late_burst_threshold,
      1,
      100000,
    ),
  };
}

/**
 * Deterministic outbox document id. Inputs are constrained by their writers — install ids use
 * `[A-Za-z0-9_-]`, pair ids are `uidA_uidB` (Firebase uids are alphanumeric), the generation
 * is a non-negative integer, and the type is one of two constants — so `.` cannot appear
 * inside a segment and the mapping is injective. Determinism is what makes the
 * `(install_id, relationship_id, outage_generation, notice_type)` uniqueness structural
 * rather than a check-then-write race.
 */
function noticeDocId(installId, relationshipId, outageGeneration, noticeType) {
  return `${installId}.${relationshipId}.${outageGeneration}.${noticeType}`;
}

function isValidNoticeType(value) {
  return NOTICE_TYPES.includes(value);
}

/** §8.13 read-time derivation. Never labels the device; returns the two coarse facts only. */
function deriveAvailability(lastActivityMs, nowMs) {
  const last = Number(lastActivityMs) || 0;
  if (last <= 0) return { connection: CONNECTION_NO_DATA, bucket: BUCKET_NO_DATA };
  const age = Math.max(0, nowMs - last);
  const connection = age < LATE_AFTER_MS
    ? CONNECTION_CHECKING_IN
    : age < NOT_CHECKING_IN_AFTER_MS ? CONNECTION_LATE : CONNECTION_NOT_CHECKING_IN;
  const bucket = age < 60 * MINUTE
    ? BUCKET_WITHIN_1H
    : age < PARTNER_PUSH_AFTER_MS ? BUCKET_1_TO_6H : BUCKET_OVER_6H;
  return { connection, bucket };
}

/** The other member of an ACTIVE two-member pairing, or null. Membership is the security
 *  boundary; a terminated pairing has no active partner (§8.10 "revoke relationship"). */
function activePartner(pairing, monitoredUid) {
  if (!pairing || pairing.status !== 'active' || !Array.isArray(pairing.members)) return null;
  if (pairing.members.length !== 2 || !pairing.members.includes(monitoredUid)) return null;
  return pairing.members.find((uid) => uid !== monitoredUid) || null;
}

/**
 * `DEVICE_AVAILABILITY_STATUS` consent is a separate, explicit relationship scope owned by
 * the MONITORED member. Absent, malformed, or false is "not consented" — fail closed. The
 * partner can always see events it was authorized for; availability is not implied by them.
 */
function consentEnabled(pairing, monitoredUid) {
  const consent = pairing && pairing.availabilityConsent;
  const entry = consent && typeof consent === 'object' ? consent[monitoredUid] : null;
  return !!(entry && entry.enabled === true);
}

/** The relationship as the notifier needs it, or null when it may not be notified. */
function consentingPartner(pairing, monitoredUid) {
  const recipientUid = activePartner(pairing, monitoredUid);
  if (!recipientUid) return null;
  return consentEnabled(pairing, monitoredUid) ? { recipientUid } : null;
}

/**
 * One due-row decision. The caller re-reads the row inside the transaction and passes the
 * original snapshot values as `expectedGeneration`/`expectedLastReceivedAtMs`: a heartbeat
 * that landed between the due query and the transaction turns the claim into a no-op.
 *
 * @param state current `dms_install_state` data.
 * @param expectedGeneration liveness generation the due query observed (null = no check).
 * @param expectedLastReceivedAtMs server receipt time the due query observed (null = no check).
 * @param outageEndMs the end of the latest ENDED heartbeat-ingestion outage (null = none);
 *        the deadline is rebased to `max(last_received_at, outage_end) + 6h` (§4).
 */
function deadlineDecision({ state, expectedGeneration = null, expectedLastReceivedAtMs = null, nowMs, outageEndMs = null }) {
  if (!state) return { action: 'STALE', reason: 'NO_STATE' };
  const lastReceivedAtMs = Number(state.last_received_at_ms) || 0;
  if (lastReceivedAtMs <= 0) return { action: 'STALE', reason: 'NO_RECEIPT' };
  const generation = Number(state.liveness_generation) || 0;
  if (expectedGeneration !== null && generation !== Number(expectedGeneration)) {
    return { action: 'STALE', reason: 'GENERATION_MOVED' };
  }
  if (expectedLastReceivedAtMs !== null && lastReceivedAtMs !== Number(expectedLastReceivedAtMs)) {
    return { action: 'STALE', reason: 'RECEIPT_MOVED' };
  }
  if (state.notice_state === 'NOTIFIED') {
    return { action: 'STALE', reason: 'ALREADY_NOTIFIED' };
  }
  const base = Math.max(lastReceivedAtMs, Number(outageEndMs) || 0);
  const dueAtMs = base + PARTNER_PUSH_AFTER_MS;
  if (dueAtMs > nowMs) {
    return { action: 'DEFER', nextDeadlineAtMs: dueAtMs };
  }
  return {
    action: 'NOTIFY',
    outageGeneration: (Number(state.outage_generation) || 0) + 1,
    notifiedGeneration: generation,
  };
}

/**
 * Dispatch-time revalidation, in the order the outbox row is checked: the authoritative
 * relationship, the monitored member's current consent, then the DMS state the row was
 * created from. Returns a suppression reason or null when the send may proceed. A stale row
 * is never sent — an old queue message is not authority (§4 consent, §8.10).
 */
function suppressionReason({ pairing, monitoredUid, row, state }) {
  const recipientUid = activePartner(pairing, monitoredUid);
  if (!recipientUid || recipientUid !== row.recipient_uid) return 'RELATIONSHIP_CLOSED';
  if (!consentEnabled(pairing, monitoredUid)) return 'CONSENT_REVOKED';
  if (!state) return 'STALE';
  if (row.notice_type === NOTICE_AVAILABILITY) {
    if (state.notice_state !== 'NOTIFIED') return 'RECOVERED_BEFORE_DISPATCH';
    if (Number(state.last_notified_generation) !== Number(row.expected_notified_generation)) {
      return 'STALE';
    }
    return null;
  }
  if (row.notice_type === NOTICE_RECOVERY) {
    if (Number(state.outage_generation) !== Number(row.outage_generation)) return 'STALE';
    if (state.notice_state === 'NOTIFIED') return 'STALE';
    return null;
  }
  return 'UNKNOWN_NOTICE_TYPE';
}

/** Fleet circuit breaker (§4 correlated-client-outage): a burst of first notices in one sweep
 *  is an incident signal, not a reason to page every partner. Zero disables nothing here —
 *  normalizeConfig already floors the threshold at 1. */
function circuitBreakerTripped(notifiedCount, threshold) {
  const limit = Number(threshold) || 0;
  return limit > 0 && notifiedCount >= limit;
}

function dispatchBackoffMs(attempts) {
  const n = Math.max(1, Number(attempts) || 1);
  return Math.min(DISPATCH_BACKOFF_CAP_MS, DISPATCH_BACKOFF_BASE_MS * 2 ** (n - 1));
}

function shouldRetryDispatch(attempts) {
  return (Number(attempts) || 0) < MAX_DISPATCH_ATTEMPTS;
}

/** Operator gate for the ingestion-outage record. Fail closed: no list, no authority. */
function isOperator(config, uid) {
  if (!config || !Array.isArray(config.operator_uids)) return false;
  return typeof uid === 'string' && config.operator_uids.includes(uid);
}

/** `started_at_ms` is required; `ended_at_ms` may be omitted while the outage is ongoing.
 *  Intervals are server-recorded operator facts — never client-supplied. */
function isValidOutageInterval(startedAtMs, endedAtMs) {
  if (!Number.isInteger(startedAtMs) || startedAtMs <= 0) return false;
  if (endedAtMs === null || endedAtMs === undefined) return true;
  return Number.isInteger(endedAtMs) && endedAtMs >= startedAtMs;
}

module.exports = {
  LATE_AFTER_MS,
  NOT_CHECKING_IN_AFTER_MS,
  PARTNER_PUSH_AFTER_MS,
  BUCKET_WITHIN_1H,
  BUCKET_1_TO_6H,
  BUCKET_OVER_6H,
  BUCKET_NO_DATA,
  CONNECTION_CHECKING_IN,
  CONNECTION_LATE,
  CONNECTION_NOT_CHECKING_IN,
  CONNECTION_NO_DATA,
  NOTICE_AVAILABILITY,
  NOTICE_RECOVERY,
  NOTICE_TYPES,
  NOTICE_PENDING,
  NOTICE_DISPATCHING,
  NOTICE_SENT,
  NOTICE_SUPPRESSED,
  NOTICE_FAILED_TERMINAL,
  MAX_DISPATCH_ATTEMPTS,
  DISPATCH_LEASE_MS,
  DEFAULT_CONFIG,
  normalizeConfig,
  noticeDocId,
  isValidNoticeType,
  deriveAvailability,
  activePartner,
  consentEnabled,
  consentingPartner,
  deadlineDecision,
  suppressionReason,
  circuitBreakerTripped,
  dispatchBackoffMs,
  shouldRetryDispatch,
  isOperator,
  isValidOutageInterval,
};
