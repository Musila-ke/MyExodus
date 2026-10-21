'use strict';
// DMS pure-logic suite (partner event-delivery plan §7 Step 8 / §4 / §8.11-§8.13). Runs under
// plain `npm test` — no emulator. Transactional behavior (CAS, outbox atomicity, concurrent
// workers, rebase, breaker) lives in emulator-test/dms.emulator.js.

const { test } = require('node:test');
const assert = require('node:assert');

const {
  LATE_AFTER_MS,
  NOT_CHECKING_IN_AFTER_MS,
  PARTNER_PUSH_AFTER_MS,
  CONNECTION_CHECKING_IN,
  CONNECTION_LATE,
  CONNECTION_NOT_CHECKING_IN,
  CONNECTION_NO_DATA,
  BUCKET_WITHIN_1H,
  BUCKET_1_TO_6H,
  BUCKET_OVER_6H,
  BUCKET_NO_DATA,
  NOTICE_AVAILABILITY,
  NOTICE_RECOVERY,
  MAX_DISPATCH_ATTEMPTS,
  normalizeConfig,
  noticeDocId,
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
} = require('../dms-logic');

const MINUTE = 60 * 1000;
const NOW = Date.UTC(2026, 9, 21, 12, 0, 0);

test('thresholds match the plan', () => {
  assert.strictEqual(LATE_AFTER_MS, 45 * MINUTE);
  assert.strictEqual(NOT_CHECKING_IN_AFTER_MS, 90 * MINUTE);
  assert.strictEqual(PARTNER_PUSH_AFTER_MS, 6 * 60 * MINUTE);
});

test('noticeDocId is deterministic and injective over its documented input domains', () => {
  assert.strictEqual(
    noticeDocId('fid_1', 'uidA_uidB', 3, NOTICE_AVAILABILITY),
    'fid_1.uidA_uidB.3.AVAILABILITY_6H',
  );
  assert.notStrictEqual(
    noticeDocId('fid_1', 'uidA_uidB', 3, NOTICE_AVAILABILITY),
    noticeDocId('fid_1', 'uidA_uidB', 4, NOTICE_AVAILABILITY),
  );
  assert.notStrictEqual(
    noticeDocId('fid_1', 'uidA_uidB', 3, NOTICE_AVAILABILITY),
    noticeDocId('fid_1', 'uidA_uidB', 3, NOTICE_RECOVERY),
  );
  // Valid-domain inputs cannot collide across segments: install ids are validated to
  // `[A-Za-z0-9_-]` and pair ids are `uidA_uidB`, so '.' never appears inside a segment.
  assert.notStrictEqual(
    noticeDocId('fid-1', 'uidA_uidB', 3, NOTICE_AVAILABILITY),
    noticeDocId('fid-1', 'uidA_uidC', 3, NOTICE_AVAILABILITY),
  );
  assert.notStrictEqual(
    noticeDocId('fid-1', 'uidA_uidB', 3, NOTICE_AVAILABILITY),
    noticeDocId('fid-2', 'uidA_uidB', 3, NOTICE_AVAILABILITY),
  );
});

test('deriveAvailability: no data is its own state, never LATE by assumption', () => {
  assert.deepStrictEqual(deriveAvailability(null, NOW), {
    connection: CONNECTION_NO_DATA,
    bucket: BUCKET_NO_DATA,
  });
  assert.deepStrictEqual(deriveAvailability(0, NOW), {
    connection: CONNECTION_NO_DATA,
    bucket: BUCKET_NO_DATA,
  });
});

test('deriveAvailability: 45/90-minute and coarse-bucket boundaries', () => {
  const at = (ageMs) => deriveAvailability(NOW - ageMs, NOW);
  assert.deepStrictEqual(at(0), { connection: CONNECTION_CHECKING_IN, bucket: BUCKET_WITHIN_1H });
  assert.deepStrictEqual(at(44 * MINUTE + 59 * 1000), {
    connection: CONNECTION_CHECKING_IN,
    bucket: BUCKET_WITHIN_1H,
  });
  assert.deepStrictEqual(at(45 * MINUTE), { connection: CONNECTION_LATE, bucket: BUCKET_WITHIN_1H });
  assert.deepStrictEqual(at(59 * MINUTE + 59 * 1000), {
    connection: CONNECTION_LATE,
    bucket: BUCKET_WITHIN_1H,
  });
  assert.deepStrictEqual(at(60 * MINUTE), { connection: CONNECTION_LATE, bucket: BUCKET_1_TO_6H });
  assert.deepStrictEqual(at(89 * MINUTE + 59 * 1000), {
    connection: CONNECTION_LATE,
    bucket: BUCKET_1_TO_6H,
  });
  assert.deepStrictEqual(at(90 * MINUTE), {
    connection: CONNECTION_NOT_CHECKING_IN,
    bucket: BUCKET_1_TO_6H,
  });
  assert.deepStrictEqual(at(6 * 60 * MINUTE), {
    connection: CONNECTION_NOT_CHECKING_IN,
    bucket: BUCKET_OVER_6H,
  });
  // Client clock skew that puts a "future" receipt in the past must not read as an outage.
  assert.deepStrictEqual(deriveAvailability(NOW + MINUTE, NOW), {
    connection: CONNECTION_CHECKING_IN,
    bucket: BUCKET_WITHIN_1H,
  });
});

test('activePartner: only an ACTIVE two-member pairing names the other member', () => {
  const active = { status: 'active', members: ['mon', 'watcher'] };
  assert.strictEqual(activePartner(active, 'mon'), 'watcher');
  assert.strictEqual(activePartner(active, 'watcher'), 'mon');
  assert.strictEqual(activePartner(active, 'stranger'), null);
  assert.strictEqual(activePartner({ ...active, status: 'terminated' }, 'mon'), null);
  assert.strictEqual(activePartner({ ...active, status: 'pending' }, 'mon'), null);
  assert.strictEqual(activePartner({ status: 'active', members: ['mon'] }, 'mon'), null);
  assert.strictEqual(activePartner({ status: 'active', members: ['mon', 'a', 'b'] }, 'mon'), null);
  assert.strictEqual(activePartner(null, 'mon'), null);
});

test('availability consent fails closed and defaults to off', () => {
  const base = { status: 'active', members: ['mon', 'watcher'] };
  assert.strictEqual(consentEnabled(base, 'mon'), false, 'absent consent is not consent');
  assert.strictEqual(consentEnabled({ ...base, availabilityConsent: null }, 'mon'), false);
  assert.strictEqual(
    consentEnabled({ ...base, availabilityConsent: { mon: { enabled: 'true' } } }, 'mon'),
    false,
    'a string is not the boolean authority',
  );
  assert.strictEqual(
    consentEnabled({ ...base, availabilityConsent: { watcher: { enabled: true } } }, 'mon'),
    false,
    'the other member cannot consent on the monitored member\'s behalf',
  );
  const consented = { ...base, availabilityConsent: { mon: { enabled: true } } };
  assert.strictEqual(consentEnabled(consented, 'mon'), true);
  assert.deepStrictEqual(consentingPartner(consented, 'mon'), { recipientUid: 'watcher' });
  assert.strictEqual(consentingPartner(base, 'mon'), null);
});

test('deadlineDecision: stale generations and already-notified rows are no-ops', () => {
  const state = {
    last_received_at_ms: NOW - 7 * 60 * MINUTE,
    liveness_generation: 9,
    outage_generation: 2,
  };
  assert.strictEqual(deadlineDecision({ state: null, nowMs: NOW }).action, 'STALE');
  assert.strictEqual(
    deadlineDecision({ state: { last_received_at_ms: 0 }, nowMs: NOW }).action,
    'STALE',
  );
  assert.deepStrictEqual(
    deadlineDecision({ state, expectedGeneration: 8, nowMs: NOW }),
    { action: 'STALE', reason: 'GENERATION_MOVED' },
  );
  assert.deepStrictEqual(
    deadlineDecision({ state, expectedLastReceivedAtMs: NOW - 6 * 60 * MINUTE, nowMs: NOW }),
    { action: 'STALE', reason: 'RECEIPT_MOVED' },
  );
  assert.strictEqual(
    deadlineDecision({ state: { ...state, notice_state: 'NOTIFIED' }, nowMs: NOW }).action,
    'STALE',
  );
});

test('deadlineDecision: notify at exactly six hours, defer before it', () => {
  const fresh = {
    last_received_at_ms: NOW - 6 * 60 * MINUTE + 1,
    liveness_generation: 4,
    outage_generation: 0,
  };
  const deferred = deadlineDecision({ state: fresh, nowMs: NOW });
  assert.strictEqual(deferred.action, 'DEFER');
  assert.strictEqual(deferred.nextDeadlineAtMs, NOW + 1);

  const due = { ...fresh, last_received_at_ms: NOW - 6 * 60 * MINUTE, liveness_generation: 7 };
  assert.deepStrictEqual(deadlineDecision({ state: due, nowMs: NOW }), {
    action: 'NOTIFY',
    outageGeneration: 1,
    notifiedGeneration: 7,
  });
});

test('deadlineDecision: an ingestion outage rebases the deadline, a scheduler outage does not', () => {
  const state = {
    last_received_at_ms: NOW - 10 * 60 * MINUTE,
    liveness_generation: 3,
    outage_generation: 1,
  };
  // Ingestion outage ended 5 minutes ago: the silence began before it, so the notice is not
  // due until outage_end + 6h. Without the rebase the row would notify now.
  const rebased = deadlineDecision({
    state,
    nowMs: NOW,
    outageEndMs: NOW - 5 * MINUTE,
  });
  assert.strictEqual(rebased.action, 'DEFER');
  assert.strictEqual(rebased.nextDeadlineAtMs, NOW - 5 * MINUTE + PARTNER_PUSH_AFTER_MS);

  // An outage that ended before the last receipt changed nothing.
  const unaffected = deadlineDecision({
    state,
    nowMs: NOW,
    outageEndMs: NOW - 20 * 60 * MINUTE,
  });
  assert.strictEqual(unaffected.action, 'NOTIFY');
  assert.strictEqual(unaffected.outageGeneration, 2);
});

const pairing = (extra = {}) => ({
  status: 'active',
  members: ['mon', 'watcher'],
  availabilityConsent: { mon: { enabled: true } },
  ...extra,
});

const availabilityRow = {
  recipient_uid: 'watcher',
  notice_type: NOTICE_AVAILABILITY,
  expected_notified_generation: 7,
};
const availabilityState = {
  notice_state: 'NOTIFIED',
  last_notified_generation: 7,
  liveness_generation: 7,
  outage_generation: 1,
};

test('suppressionReason: the relationship and consent are re-read at dispatch time', () => {
  assert.strictEqual(
    suppressionReason({
      pairing: pairing({ status: 'terminated' }),
      monitoredUid: 'mon',
      row: availabilityRow,
      state: availabilityState,
    }),
    'RELATIONSHIP_CLOSED',
  );
  assert.strictEqual(
    suppressionReason({
      pairing: pairing({ members: ['mon', 'someone-else'] }),
      monitoredUid: 'mon',
      row: availabilityRow,
      state: availabilityState,
    }),
    'RELATIONSHIP_CLOSED',
  );
  assert.strictEqual(
    suppressionReason({
      pairing: pairing({ availabilityConsent: { mon: { enabled: false } } }),
      monitoredUid: 'mon',
      row: availabilityRow,
      state: availabilityState,
    }),
    'CONSENT_REVOKED',
  );
});

test('suppressionReason: a stale availability row is never sent', () => {
  assert.strictEqual(
    suppressionReason({ pairing: pairing(), monitoredUid: 'mon', row: availabilityRow, state: null }),
    'STALE',
  );
  assert.strictEqual(
    suppressionReason({
      pairing: pairing(),
      monitoredUid: 'mon',
      row: availabilityRow,
      state: { ...availabilityState, notice_state: 'NONE' },
    }),
    'RECOVERED_BEFORE_DISPATCH',
  );
  assert.strictEqual(
    suppressionReason({
      pairing: pairing(),
      monitoredUid: 'mon',
      row: availabilityRow,
      state: { ...availabilityState, last_notified_generation: 6 },
    }),
    'STALE',
  );
  assert.strictEqual(
    suppressionReason({
      pairing: pairing(),
      monitoredUid: 'mon',
      row: availabilityRow,
      state: availabilityState,
    }),
    null,
  );
});

test('suppressionReason: recovery is sent once, and only for its own outage generation', () => {
  const recoveryRow = {
    recipient_uid: 'watcher',
    notice_type: NOTICE_RECOVERY,
    outage_generation: 1,
  };
  const recovered = { notice_state: 'NONE', outage_generation: 1 };
  assert.strictEqual(
    suppressionReason({ pairing: pairing(), monitoredUid: 'mon', row: recoveryRow, state: recovered }),
    null,
  );
  assert.strictEqual(
    suppressionReason({
      pairing: pairing(),
      monitoredUid: 'mon',
      row: recoveryRow,
      state: { ...recovered, outage_generation: 2 },
    }),
    'STALE',
  );
  assert.strictEqual(
    suppressionReason({
      pairing: pairing(),
      monitoredUid: 'mon',
      row: recoveryRow,
      state: { notice_state: 'NOTIFIED', outage_generation: 1 },
    }),
    'STALE',
    'a new outage suppresses the previous outage\'s recovery notice',
  );
  assert.strictEqual(
    suppressionReason({
      pairing: pairing(),
      monitoredUid: 'mon',
      row: { ...recoveryRow, notice_type: 'SOMETHING_ELSE' },
      state: recovered,
    }),
    'UNKNOWN_NOTICE_TYPE',
  );
});

test('circuit breaker trips at the configured burst threshold', () => {
  assert.strictEqual(circuitBreakerTripped(49, 50), false);
  assert.strictEqual(circuitBreakerTripped(50, 50), true);
  assert.strictEqual(circuitBreakerTripped(51, 50), true);
  assert.strictEqual(circuitBreakerTripped(1, 0), false, '0 disables the breaker, not everything');
});

test('dispatch backoff doubles to the cap and stops at the attempt bound', () => {
  assert.strictEqual(dispatchBackoffMs(1), MINUTE);
  assert.strictEqual(dispatchBackoffMs(2), 2 * MINUTE);
  assert.strictEqual(dispatchBackoffMs(5), 16 * MINUTE);
  assert.strictEqual(dispatchBackoffMs(6), 30 * MINUTE);
  assert.strictEqual(dispatchBackoffMs(50), 30 * MINUTE);
  assert.strictEqual(shouldRetryDispatch(MAX_DISPATCH_ATTEMPTS - 1), true);
  assert.strictEqual(shouldRetryDispatch(MAX_DISPATCH_ATTEMPTS), false);
});

test('normalizeConfig: malformed operator values fall back to bounded defaults', () => {
  assert.deepStrictEqual(normalizeConfig(null), {
    enabled: true,
    dispatchHold: false,
    dueBatch: 100,
    dispatchBatch: 100,
    lateBurstThreshold: 50,
  });
  assert.deepStrictEqual(
    normalizeConfig({
      enabled: false,
      dispatch_hold: true,
      due_batch: 0,
      dispatch_batch: 999,
      late_burst_threshold: 'lots',
    }),
    {
      enabled: false,
      dispatchHold: true,
      dueBatch: 100,
      dispatchBatch: 100,
      lateBurstThreshold: 50,
    },
  );
  assert.deepStrictEqual(
    normalizeConfig({ due_batch: 1, dispatch_batch: 5, late_burst_threshold: 2 }),
    { enabled: true, dispatchHold: false, dueBatch: 1, dispatchBatch: 5, lateBurstThreshold: 2 },
  );
});

test('operator gate fails closed and outage intervals are validated', () => {
  assert.strictEqual(isOperator(null, 'u1'), false);
  assert.strictEqual(isOperator({ operator_uids: [] }, 'u1'), false);
  assert.strictEqual(isOperator({ operator_uids: ['u2'] }, 'u1'), false);
  assert.strictEqual(isOperator({ operator_uids: ['u1'] }, 'u1'), true);

  assert.strictEqual(isValidOutageInterval(NOW, null), true, 'an open outage is valid');
  assert.strictEqual(isValidOutageInterval(NOW, NOW + MINUTE), true);
  assert.strictEqual(isValidOutageInterval(NOW, NOW), true);
  assert.strictEqual(isValidOutageInterval(NOW, NOW - 1), false, 'end cannot precede start');
  assert.strictEqual(isValidOutageInterval(0, null), false);
  assert.strictEqual(isValidOutageInterval(1.5, null), false);
  assert.strictEqual(isValidOutageInterval(NOW, NOW + 0.5), false);
});
