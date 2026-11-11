'use strict';
// Liveness/enforcement pure-logic suite (partner event-delivery plan §7 Phase 6 / §8.7).
// Runs under plain `npm test`; the transactional half lives in
// emulator-test/device-contact.emulator.js.

const { test } = require('node:test');
const assert = require('node:assert');

const {
  CONTACT_VERSION,
  PROBE_AFTER_MS,
  PROBE_WINDOW_MS,
  ALERT_GRACE_MS,
  SLEEP_ALERT_GRACE_MS,
  PROBE_RATE_LIMIT_MS,
  STATUS_MONITORING_UNAVAILABLE,
  ENFORCEMENT_HEALTHY,
  ENFORCEMENT_DEGRADED,
  LIVENESS_NOTICE_TYPES,
  NOTICE_DEVICE_RECONNECTED,
  NOTICE_ENFORCEMENT_DEGRADED,
  NOTICE_ENFORCEMENT_RESTORED,
  livenessDocId,
  validateContact,
  contactMessage,
  missingCapabilities,
  enforcementState,
  episodeIdFor,
  probeIdFor,
  contactAcceptance,
  evaluationDecision,
  probePriority,
  fleetIncident,
  isLivenessNoticeType,
} = require('../device-liveness-logic');

const INSTALL = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';
const KEY = 'a'.repeat(43);
const NOW = Date.UTC(2026, 10, 12, 12, 0, 0);

function enforcement(overrides = {}) {
  return {
    accessibilityEnabled: true,
    vpnActive: true,
    deviceAdminActive: false,
    notificationPermissionGranted: true,
    requiredCapabilities: ['ACCESSIBILITY', 'VPN'],
    ...overrides,
  };
}

function contact(overrides = {}) {
  return {
    v: 1,
    ownerUid: 'owner-uid',
    installationId: INSTALL,
    keyId: KEY,
    heartbeatCounter: 5,
    deviceReportedAtMs: NOW - 1000,
    enforcement: enforcement(),
    ...overrides,
  };
}

function liveness(overrides = {}) {
  return {
    ownerUid: 'owner-uid',
    installationId: INSTALL,
    lastAcceptedContactAtMs: NOW,
    lastHeartbeatCounter: 4,
    enforcementState: ENFORCEMENT_HEALTHY,
    lastObservedEnforcement: enforcement(),
    earliestAlertAtMs: NOW + ALERT_GRACE_MS,
    ...overrides,
  };
}

test('constants match the plan thresholds', () => {
  assert.strictEqual(CONTACT_VERSION, 1);
  assert.strictEqual(PROBE_AFTER_MS, 90 * 60 * 1000);
  assert.strictEqual(PROBE_WINDOW_MS, 20 * 60 * 1000);
  assert.strictEqual(ALERT_GRACE_MS, 240 * 60 * 1000);
  assert.strictEqual(SLEEP_ALERT_GRACE_MS, 360 * 60 * 1000);
  assert.strictEqual(PROBE_RATE_LIMIT_MS, 6 * 60 * 60 * 1000);
  assert.deepStrictEqual(LIVENESS_NOTICE_TYPES, [
    'DEVICE_UNAVAILABLE',
    'DEVICE_RECONNECTED',
    'ENFORCEMENT_DEGRADED',
    'ENFORCEMENT_RESTORED',
  ]);
});

test('liveness documents are namespaced by owner and installation', () => {
  assert.strictEqual(livenessDocId('uidA', INSTALL), `uidA:${INSTALL}`);
  assert.notStrictEqual(livenessDocId('uidA', INSTALL), livenessDocId('uidB', INSTALL));
});

test('a canonical contact validates and keeps only the allowlisted fields', () => {
  const { error, contact: normalized } = validateContact({ ...contact(), extra: 'dropped' });
  assert.strictEqual(error, null);
  assert.deepStrictEqual(normalized, contact());
});

test('malformed contacts are rejected by field', () => {
  const cases = [
    [null, 'CONTACT_INVALID'],
    [[], 'CONTACT_INVALID'],
    [{ ...contact(), v: 2 }, 'CONTACT_VERSION_UNSUPPORTED'],
    [{ ...contact(), ownerUid: '' }, 'OWNER_UID_INVALID'],
    [{ ...contact(), ownerUid: 'a/b' }, 'OWNER_UID_INVALID'],
    [{ ...contact(), installationId: 'not-a-uuid' }, 'INSTALLATION_ID_INVALID'],
    [{ ...contact(), keyId: 'short' }, 'KEY_ID_INVALID'],
    [{ ...contact(), heartbeatCounter: 0 }, 'COUNTER_INVALID'],
    [{ ...contact(), heartbeatCounter: 1.5 }, 'COUNTER_INVALID'],
    [{ ...contact(), deviceReportedAtMs: -1 }, 'DEVICE_TIME_INVALID'],
    [{ ...contact(), deviceReportedAtMs: 'now' }, 'DEVICE_TIME_INVALID'],
    [{ ...contact(), enforcement: null }, 'ENFORCEMENT_INVALID'],
    [{ ...contact(), enforcement: enforcement({ vpnActive: 'yes' }) }, 'ENFORCEMENT_INVALID'],
    [{ ...contact(), enforcement: enforcement({ requiredCapabilities: ['ROOT'] }) }, 'ENFORCEMENT_INVALID'],
    [{ ...contact(), enforcement: enforcement({ requiredCapabilities: ['VPN', 'VPN'] }) }, 'ENFORCEMENT_INVALID'],
    [{ ...contact(), probeId: 'short' }, 'PROBE_ID_INVALID'],
    [{ ...contact(), probeId: 'has space' }, 'PROBE_ID_INVALID'],
  ];
  for (const [payload, error] of cases) {
    assert.strictEqual(validateContact(payload).error, error, JSON.stringify(payload));
  }
});

test('omitting probeId and sending null sign identical bytes', () => {
  const without = validateContact(contact()).contact;
  const withNull = validateContact(contact({ probeId: null })).contact;
  assert.deepStrictEqual(contactMessage(without), contactMessage(withNull));
  assert.ok(!('probeId' in contactMessage(without)));
  const withProbe = validateContact(contact({ probeId: 'probe-abc-123' })).contact;
  assert.strictEqual(contactMessage(withProbe).probeId, 'probe-abc-123');
});

test('the signed message carries the plan field set and nested enforcement', () => {
  assert.deepStrictEqual(contactMessage(contact()), {
    v: 1,
    ownerUid: 'owner-uid',
    installationId: INSTALL,
    keyId: KEY,
    heartbeatCounter: 5,
    deviceReportedAtMs: NOW - 1000,
    enforcement: {
      accessibilityEnabled: true,
      vpnActive: true,
      deviceAdminActive: false,
      notificationPermissionGranted: true,
      requiredCapabilities: ['ACCESSIBILITY', 'VPN'],
    },
  });
});

test('required capabilities drive the health state; optional protections never do', () => {
  assert.deepStrictEqual(missingCapabilities(enforcement()), []);
  assert.strictEqual(enforcementState(enforcement()), ENFORCEMENT_HEALTHY);

  // Accessibility disabled but NOT required: still healthy — no optional-protection noise.
  const optionalOff = enforcement({ accessibilityEnabled: false, requiredCapabilities: ['VPN'] });
  assert.deepStrictEqual(missingCapabilities(optionalOff), []);
  assert.strictEqual(enforcementState(optionalOff), ENFORCEMENT_HEALTHY);

  assert.deepStrictEqual(
    missingCapabilities(enforcement({ vpnActive: false })),
    ['VPN'],
  );
  assert.strictEqual(
    enforcementState(enforcement({ vpnActive: false })),
    ENFORCEMENT_DEGRADED,
  );
  assert.strictEqual(
    enforcementState(enforcement({
      accessibilityEnabled: false,
      vpnActive: false,
      requiredCapabilities: ['ACCESSIBILITY', 'VPN'],
    })),
    ENFORCEMENT_DEGRADED,
  );
});

test('a stale counter is rejected without any state change', () => {
  for (const counter of [4, 3, 1]) {
    assert.deepStrictEqual(
      contactAcceptance(liveness(), contact({ heartbeatCounter: counter }), NOW + 1000),
      { outcome: 'STALE', serverCounter: 4 },
    );
  }
  assert.deepStrictEqual(contactAcceptance(null, contact({ heartbeatCounter: 0 }), NOW), {
    outcome: 'STALE',
    serverCounter: 0,
  });
});

test('a probe answer only counts against the currently pending probe id', () => {
  const state = liveness({ pendingProbeId: 'probe-current', outageEpisodeId: 'ep-1' });
  assert.deepStrictEqual(
    contactAcceptance(state, contact({ probeId: 'probe-older' }), NOW + 1000),
    { outcome: 'INVALID_PROBE' },
  );
  const accepted = contactAcceptance(state, contact({ probeId: 'probe-current' }), NOW + 1000);
  assert.strictEqual(accepted.outcome, 'ACCEPTED');
  assert.strictEqual(accepted.answeredProbeId, 'probe-current');
  // An ordinary contact (no probe id) is still a valid later contact that closes the episode.
  assert.strictEqual(contactAcceptance(state, contact(), NOW + 1000).outcome, 'ACCEPTED');
});

test('a first accepted contact establishes the enforcement baseline silently', () => {
  const decision = contactAcceptance(
    null,
    contact({ enforcement: enforcement({ vpnActive: false }) }),
    NOW,
  );
  assert.strictEqual(decision.outcome, 'ACCEPTED');
  assert.strictEqual(decision.serverCounter, 5);
  assert.strictEqual(decision.enforcementState, ENFORCEMENT_DEGRADED);
  assert.strictEqual(decision.enforcementNotice, null, 'no fabricated tamper signal on enrollment');
  assert.strictEqual(decision.closesEpisode, false);
  assert.strictEqual(decision.reconnectNotice, null);
  assert.strictEqual(decision.earliestAlertAtMs, NOW + ALERT_GRACE_MS);
  assert.strictEqual(decision.answeredProbeId, null);
});

test('the health transition emits exactly one notice per direction', () => {
  const degraded = liveness({ enforcementState: ENFORCEMENT_HEALTHY });
  assert.strictEqual(
    contactAcceptance(degraded, contact({ enforcement: enforcement({ vpnActive: false }) }), NOW)
      .enforcementNotice,
    NOTICE_ENFORCEMENT_DEGRADED,
  );
  const healthy = liveness({ enforcementState: ENFORCEMENT_DEGRADED });
  assert.strictEqual(
    contactAcceptance(healthy, contact(), NOW).enforcementNotice,
    NOTICE_ENFORCEMENT_RESTORED,
  );
  assert.strictEqual(contactAcceptance(liveness(), contact(), NOW).enforcementNotice, null);
  // Still degraded with a different missing capability: no second alert.
  assert.strictEqual(
    contactAcceptance(
      liveness({ enforcementState: ENFORCEMENT_DEGRADED }),
      contact({
        enforcement: enforcement({
          accessibilityEnabled: false,
          vpnActive: false,
          requiredCapabilities: ['ACCESSIBILITY', 'VPN'],
        }),
      }),
      NOW,
    ).enforcementNotice,
    null,
  );
});

test('closing an episode notifies reconnected only when an alert was published', () => {
  const silent = contactAcceptance(
    liveness({ outageEpisodeId: 'ep-1' }),
    contact(),
    NOW,
  );
  assert.strictEqual(silent.closesEpisode, true);
  assert.strictEqual(silent.reconnectNotice, null);

  const alerted = contactAcceptance(
    liveness({ outageEpisodeId: 'ep-1', alertedAtMs: NOW - 1000 }),
    contact(),
    NOW,
  );
  assert.strictEqual(alerted.closesEpisode, true);
  assert.strictEqual(alerted.reconnectNotice, NOTICE_DEVICE_RECONNECTED);
});

test('a custom grace (sleep window) is applied to the next alert deadline', () => {
  const decision = contactAcceptance(null, contact(), NOW, { graceMs: SLEEP_ALERT_GRACE_MS });
  assert.strictEqual(decision.earliestAlertAtMs, NOW + SLEEP_ALERT_GRACE_MS);
});

test('the evaluator stays quiet where there is nothing to judge', () => {
  assert.strictEqual(evaluationDecision({ state: null, nowMs: NOW }).reason, 'NO_LIVENESS_STATE');
  assert.strictEqual(
    evaluationDecision({
      state: liveness({ status: STATUS_MONITORING_UNAVAILABLE }),
      nowMs: NOW,
    }).reason,
    'MONITORING_PAUSED',
  );
  assert.strictEqual(
    evaluationDecision({ state: liveness({ lastAcceptedContactAtMs: 0 }), nowMs: NOW }).reason,
    'NEVER_CONTACTED',
  );
  assert.strictEqual(
    evaluationDecision({ state: liveness(), nowMs: NOW + PROBE_AFTER_MS - 1 }).reason,
    'CONTACT_FRESH',
  );
  assert.strictEqual(
    evaluationDecision({
      state: liveness({ outageEpisodeId: 'ep-1', alertedAtMs: NOW - 1000 }),
      nowMs: NOW + 24 * 60 * 60 * 1000,
    }).reason,
    'ALREADY_ALERTED',
  );
});

test('90 minutes of silence allocates one deterministic episode and probe', () => {
  const decision = evaluationDecision({ state: liveness(), nowMs: NOW + PROBE_AFTER_MS });
  const episodeId = episodeIdFor(INSTALL, NOW);
  assert.deepStrictEqual(decision, {
    action: 'ALLOCATE_PROBE',
    reason: 'CONTACT_STALE',
    outageEpisodeId: episodeId,
    probeId: probeIdFor(episodeId),
    probePriority: 'high',
  });
  // Deterministic ids: a duplicate scheduler run at the same instant lands on the same probe.
  assert.deepStrictEqual(evaluationDecision({ state: liveness(), nowMs: NOW + PROBE_AFTER_MS }), decision);
});

test('probe priority follows the last observed notification permission', () => {
  assert.strictEqual(probePriority(enforcement()), 'high');
  assert.strictEqual(
    probePriority(enforcement({ notificationPermissionGranted: false })),
    'normal',
  );
  assert.strictEqual(probePriority(null), 'normal');
  assert.strictEqual(
    evaluationDecision({
      state: liveness({ lastObservedEnforcement: enforcement({ notificationPermissionGranted: false }) }),
      nowMs: NOW + PROBE_AFTER_MS,
    }).probePriority,
    'normal',
  );
});

test('the probe rate cap prevents more than one wake probe per six hours', () => {
  const staleAt = NOW + PROBE_AFTER_MS;
  const state = liveness({ lastProbeSentAtMs: staleAt - PROBE_RATE_LIMIT_MS + 1 });
  assert.deepStrictEqual(
    evaluationDecision({ state, nowMs: staleAt }),
    { action: 'NONE', reason: 'PROBE_RATE_LIMITED' },
  );
  assert.strictEqual(
    evaluationDecision({
      state: liveness({ lastProbeSentAtMs: staleAt - PROBE_RATE_LIMIT_MS }),
      nowMs: staleAt,
    }).action,
    'ALLOCATE_PROBE',
  );
});

test('a pending probe blocks re-allocation until its 20-minute window closes', () => {
  const staleAt = NOW + PROBE_AFTER_MS;
  const state = liveness({
    outageEpisodeId: 'ep-1',
    pendingProbeId: 'probe-1',
    probeDeadlineAtMs: staleAt + PROBE_WINDOW_MS,
  });
  assert.deepStrictEqual(
    evaluationDecision({ state, nowMs: staleAt + PROBE_WINDOW_MS - 1 }),
    { action: 'NONE', reason: 'PROBE_PENDING', probeId: 'probe-1' },
  );
});

test('an unanswered probe waits for the conservative grace before alerting', () => {
  const state = liveness({
    outageEpisodeId: 'ep-1',
    pendingProbeId: 'probe-1',
    probeDeadlineAtMs: NOW + PROBE_AFTER_MS + PROBE_WINDOW_MS,
  });
  assert.deepStrictEqual(
    evaluationDecision({ state, nowMs: NOW + PROBE_AFTER_MS + PROBE_WINDOW_MS }),
    { action: 'NONE', reason: 'GRACE_PENDING', probeId: 'probe-1' },
  );
  assert.deepStrictEqual(
    evaluationDecision({ state, nowMs: NOW + ALERT_GRACE_MS }),
    { action: 'ALERT', reason: 'POLICY_STILL_FAILING', outageEpisodeId: 'ep-1' },
  );
});

test('a validated fleet incident suppresses absence-only alerts but nothing else', () => {
  const state = liveness({
    outageEpisodeId: 'ep-1',
    pendingProbeId: 'probe-1',
    probeDeadlineAtMs: NOW + PROBE_AFTER_MS + PROBE_WINDOW_MS,
  });
  assert.deepStrictEqual(
    evaluationDecision({ state, nowMs: NOW + ALERT_GRACE_MS, incidentActive: true }),
    { action: 'SUPPRESS', reason: 'FLEET_INCIDENT' },
  );
  assert.deepStrictEqual(
    evaluationDecision({ state: liveness(), nowMs: NOW + PROBE_AFTER_MS, incidentActive: true }),
    { action: 'SUPPRESS', reason: 'FLEET_INCIDENT' },
  );
});

test('a fleet incident needs both correlation and a corroborating backend failure', () => {
  assert.strictEqual(fleetIncident({ corroboratingFailure: true, staleInstallations: 5 }), true);
  assert.strictEqual(fleetIncident({ corroboratingFailure: false, staleInstallations: 99 }), false);
  assert.strictEqual(fleetIncident({ corroboratingFailure: true, staleInstallations: 4 }), false);
  assert.strictEqual(
    fleetIncident({ corroboratingFailure: true, staleInstallations: 10, threshold: 10 }),
    true,
  );
});

test('liveness notice types are closed-set', () => {
  for (const type of LIVENESS_NOTICE_TYPES) assert.strictEqual(isLivenessNoticeType(type), true);
  assert.strictEqual(isLivenessNoticeType('AVAILABILITY_6H'), false);
  assert.strictEqual(isLivenessNoticeType('UNKNOWN'), false);
});
