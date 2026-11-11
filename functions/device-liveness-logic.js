'use strict';
// Liveness and enforcement-health decisions (partner event-delivery plan §7 Phase 6 / §8.7).
// Pure — no Firebase import — so node --test drives every branch and the emulator suite only
// has to prove the transactional half.
//
// This is the stateless/latest-wins signed heartbeat, deliberately NOT a second dead-man's
// switch: the signed contact is the authority, `dms_install_state` stays the legacy partner-
// notice state, and the accepted contact bridges into it (see device-contact.js). Counters are
// a separate namespace from the legacy unsigned heartbeat — replaying one never advances the
// other.
//
// Timestamps here are server receipt times passed in by the caller; nothing compares device
// clocks. The device-reported time is stored for diagnostics only.

const CONTACT_VERSION = 1;

// §8.7: after 90 minutes of silence the evaluator allocates an episode + one wake probe; the
// probe window is 20 minutes; the partner alert waits a conservative 240-minute grace (360
// once a sleep window is configured — none exists in this build, so callers pass the default).
const PROBE_AFTER_MS = 90 * 60 * 1000;
const PROBE_WINDOW_MS = 20 * 60 * 1000;
const ALERT_GRACE_MS = 240 * 60 * 1000;
const SLEEP_ALERT_GRACE_MS = 360 * 60 * 1000;
const PROBE_RATE_LIMIT_MS = 6 * 60 * 60 * 1000;

const STATUS_ACTIVE = 'ACTIVE';
const STATUS_CONTACT_STALE = 'CONTACT_STALE';
const STATUS_UNAVAILABLE = 'UNAVAILABLE';
const STATUS_MONITORING_UNAVAILABLE = 'MONITORING_UNAVAILABLE';

const ENFORCEMENT_HEALTHY = 'HEALTHY';
const ENFORCEMENT_DEGRADED = 'DEGRADED';

const CAPABILITY_ACCESSIBILITY = 'ACCESSIBILITY';
const CAPABILITY_VPN = 'VPN';
const CAPABILITY_DEVICE_ADMIN = 'DEVICE_ADMIN';
const CAPABILITIES = [CAPABILITY_ACCESSIBILITY, CAPABILITY_VPN, CAPABILITY_DEVICE_ADMIN];

const NOTICE_DEVICE_UNAVAILABLE = 'DEVICE_UNAVAILABLE';
const NOTICE_DEVICE_RECONNECTED = 'DEVICE_RECONNECTED';
const NOTICE_ENFORCEMENT_DEGRADED = 'ENFORCEMENT_DEGRADED';
const NOTICE_ENFORCEMENT_RESTORED = 'ENFORCEMENT_RESTORED';
// The one collection both writers share: the contact callable owns it, the evaluator and the
// dispatcher read it.
const LIVENESS_COLLECTION = 'partnerDeviceLiveness';

const LIVENESS_NOTICE_TYPES = [
  NOTICE_DEVICE_UNAVAILABLE,
  NOTICE_DEVICE_RECONNECTED,
  NOTICE_ENFORCEMENT_DEGRADED,
  NOTICE_ENFORCEMENT_RESTORED,
];

// The installation id is the client's service-installation UUID — the same value the
// registration callable accepts, so a contact can only name an installation that could have
// registered.
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const KEY_ID = /^[A-Za-z0-9_-]{43}$/;
const PROBE_ID = /^[A-Za-z0-9_-]{8,160}$/;
const COUNTER_MAX = Number.MAX_SAFE_INTEGER;

/** Registration uses `{uid}:{installationId}`; liveness mirrors it so an installation that
 *  re-registers under another account can never inherit the previous owner's episode. */
function livenessDocId(uid, installationId) {
  return `${uid}:${installationId}`;
}

function isValidInstallationId(value) {
  return typeof value === 'string' && UUID_V4.test(value);
}

function isValidKeyId(value) {
  return typeof value === 'string' && KEY_ID.test(value);
}

function isValidCounter(value) {
  return Number.isInteger(value) && value > 0 && value <= COUNTER_MAX;
}

function isValidProbeId(value) {
  return typeof value === 'string' && PROBE_ID.test(value);
}

function isValidUid(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 128 && !value.includes('/');
}

function bool(value) {
  return value === true || value === false;
}

/**
 * Validate a submitDeviceContact payload's shape and return only the allowlisted fields.
 * Null `error` means the contact is well-formed; the caller still owns auth, registration and
 * signature checks. Field names and nesting are the signed canonical shape — see
 * contactMessage().
 */
function validateContact(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    return { error: 'CONTACT_INVALID' };
  }
  if (data.v !== CONTACT_VERSION) return { error: 'CONTACT_VERSION_UNSUPPORTED' };
  if (!isValidUid(data.ownerUid)) return { error: 'OWNER_UID_INVALID' };
  if (!isValidInstallationId(data.installationId)) return { error: 'INSTALLATION_ID_INVALID' };
  if (!isValidKeyId(data.keyId)) return { error: 'KEY_ID_INVALID' };
  if (!isValidCounter(data.heartbeatCounter)) return { error: 'COUNTER_INVALID' };
  if (!Number.isInteger(data.deviceReportedAtMs) || data.deviceReportedAtMs < 0 ||
      data.deviceReportedAtMs > COUNTER_MAX) {
    return { error: 'DEVICE_TIME_INVALID' };
  }
  const enforcement = data.enforcement;
  if (!enforcement || typeof enforcement !== 'object' || Array.isArray(enforcement)) {
    return { error: 'ENFORCEMENT_INVALID' };
  }
  for (const field of ['accessibilityEnabled', 'vpnActive', 'deviceAdminActive',
    'notificationPermissionGranted']) {
    if (!bool(enforcement[field])) return { error: 'ENFORCEMENT_INVALID' };
  }
  const required = enforcement.requiredCapabilities;
  if (!Array.isArray(required) || required.length > CAPABILITIES.length ||
      new Set(required).size !== required.length ||
      required.some((capability) => !CAPABILITIES.includes(capability))) {
    return { error: 'ENFORCEMENT_INVALID' };
  }
  if (data.probeId !== undefined && data.probeId !== null && !isValidProbeId(data.probeId)) {
    return { error: 'PROBE_ID_INVALID' };
  }
  return {
    error: null,
    contact: {
      v: CONTACT_VERSION,
      ownerUid: data.ownerUid,
      installationId: data.installationId,
      keyId: data.keyId,
      heartbeatCounter: data.heartbeatCounter,
      deviceReportedAtMs: data.deviceReportedAtMs,
      enforcement: {
        accessibilityEnabled: enforcement.accessibilityEnabled,
        vpnActive: enforcement.vpnActive,
        deviceAdminActive: enforcement.deviceAdminActive,
        notificationPermissionGranted: enforcement.notificationPermissionGranted,
        requiredCapabilities: required.slice(),
      },
      // Optional field: absent and null mean the same thing, and neither appears in the
      // signed bytes, so a client that omits it and one that sends null sign identically.
      ...(typeof data.probeId === 'string' ? { probeId: data.probeId } : {}),
    },
  };
}

/** The exact object whose JCS bytes are signed — identical on both sides. */
function contactMessage(contact) {
  return {
    v: contact.v,
    ownerUid: contact.ownerUid,
    installationId: contact.installationId,
    keyId: contact.keyId,
    heartbeatCounter: contact.heartbeatCounter,
    deviceReportedAtMs: contact.deviceReportedAtMs,
    enforcement: {
      accessibilityEnabled: contact.enforcement.accessibilityEnabled,
      vpnActive: contact.enforcement.vpnActive,
      deviceAdminActive: contact.enforcement.deviceAdminActive,
      notificationPermissionGranted: contact.enforcement.notificationPermissionGranted,
      requiredCapabilities: contact.enforcement.requiredCapabilities.slice(),
    },
    ...(contact.probeId ? { probeId: contact.probeId } : {}),
  };
}

/** Required capabilities that the self-report says are missing. Optional protections never
 *  appear here because the mask is built from the effective monitoring policy. */
function missingCapabilities(enforcement) {
  const present = {
    [CAPABILITY_ACCESSIBILITY]: enforcement.accessibilityEnabled === true,
    [CAPABILITY_VPN]: enforcement.vpnActive === true,
    [CAPABILITY_DEVICE_ADMIN]: enforcement.deviceAdminActive === true,
  };
  return enforcement.requiredCapabilities.filter((capability) => !present[capability]);
}

function enforcementState(enforcement) {
  return missingCapabilities(enforcement).length === 0
    ? ENFORCEMENT_HEALTHY
    : ENFORCEMENT_DEGRADED;
}

/** The stored, allowlisted snapshot — no free-form device data ever reaches the document. */
function storedEnforcement(enforcement, observedAtMs) {
  return {
    accessibilityEnabled: enforcement.accessibilityEnabled,
    vpnActive: enforcement.vpnActive,
    deviceAdminActive: enforcement.deviceAdminActive,
    notificationPermissionGranted: enforcement.notificationPermissionGranted,
    requiredCapabilities: enforcement.requiredCapabilities.slice(),
    observedAtMs,
  };
}

/** Deterministic ids make overlapping evaluator runs converge on one episode and one probe
 *  instead of allocating rivals. */
function episodeIdFor(installationId, lastAcceptedContactAtMs) {
  return `ep-${installationId}-${lastAcceptedContactAtMs}`;
}

function probeIdFor(episodeId) {
  return `probe-${episodeId}`;
}

/**
 * One signed contact's decision over the prior liveness document.
 *
 * @param previous `partnerDeviceLiveness` data or null.
 * @param contact validated contact (validateContact).
 * @returns STALE / INVALID_PROBE rejections (caller writes nothing), or an ACCEPTED decision
 *   with the state transition the handler must persist: enforcement notice, episode close,
 *   reconnect notice, and the policy-adjusted earliest alert time.
 */
function contactAcceptance(previous, contact, nowMs, { graceMs = ALERT_GRACE_MS } = {}) {
  const lastCounter = previous ? Number(previous.lastHeartbeatCounter) || 0 : 0;
  if (contact.heartbeatCounter <= lastCounter) {
    return { outcome: 'STALE', serverCounter: lastCounter };
  }
  const pendingProbeId = previous && previous.pendingProbeId ? previous.pendingProbeId : null;
  if (contact.probeId && contact.probeId !== pendingProbeId) {
    // A response to an older (or someone else's) probe proves nothing about this episode —
    // reject it without touching liveness so the episode keeps its original timeline.
    return { outcome: 'INVALID_PROBE' };
  }

  const nextEnforcementState = enforcementState(contact.enforcement);
  const previousEnforcementState = previous && previous.enforcementState
    ? previous.enforcementState
    : null;
  // A first-ever report establishes the baseline silently: "transition" needs a prior
  // observation, and inventing one on enrollment would fabricate a tamper signal.
  let enforcementNotice = null;
  if (previousEnforcementState && previousEnforcementState !== nextEnforcementState) {
    enforcementNotice = nextEnforcementState === ENFORCEMENT_DEGRADED
      ? NOTICE_ENFORCEMENT_DEGRADED
      : NOTICE_ENFORCEMENT_RESTORED;
  }

  const outageEpisodeId = previous && previous.outageEpisodeId ? previous.outageEpisodeId : null;
  const alertedAtMs = previous && previous.alertedAtMs ? previous.alertedAtMs : null;
  return {
    outcome: 'ACCEPTED',
    serverCounter: contact.heartbeatCounter,
    enforcementState: nextEnforcementState,
    missingCapabilities: missingCapabilities(contact.enforcement),
    enforcementNotice,
    outageEpisodeId,
    closesEpisode: outageEpisodeId !== null,
    // §8.7: reconnected only when the partner was actually told the device was unavailable;
    // a silently cancelled probe must not produce a "back online" notice.
    reconnectNotice: outageEpisodeId !== null && alertedAtMs !== null
      ? NOTICE_DEVICE_RECONNECTED
      : null,
    earliestAlertAtMs: nowMs + graceMs,
    answeredProbeId: contact.probeId || null,
  };
}

/**
 * The five-minute evaluator's decision for one monitored installation. Idempotent by
 * construction: every branch is derived from persisted server timestamps, so duplicate or
 * overlapping scheduler runs with the same `nowMs` choose the same action.
 *
 * @param state `partnerDeviceLiveness` data or null.
 * @param options incidentActive — validated fleet incident; graceMs — configured alert grace.
 * @returns action NONE / ALLOCATE_PROBE / SUPPRESS / ALERT with the reason and ids.
 */
function evaluationDecision({ state, nowMs, incidentActive = false, graceMs = ALERT_GRACE_MS }) {
  if (!state) return { action: 'NONE', reason: 'NO_LIVENESS_STATE' };
  if (state.status === STATUS_MONITORING_UNAVAILABLE) {
    return { action: 'NONE', reason: 'MONITORING_PAUSED' };
  }
  const lastContactAtMs = Number(state.lastAcceptedContactAtMs) || 0;
  if (!lastContactAtMs) return { action: 'NONE', reason: 'NEVER_CONTACTED' };
  // One alert per episode: once told, the partner is not retold on every evaluator pass.
  if (state.outageEpisodeId && state.alertedAtMs) {
    return { action: 'NONE', reason: 'ALREADY_ALERTED' };
  }
  if (nowMs - lastContactAtMs < PROBE_AFTER_MS) {
    return { action: 'NONE', reason: 'CONTACT_FRESH' };
  }

  const earliestAlertAtMs = Number(state.earliestAlertAtMs) ||
    lastContactAtMs + graceMs;
  const pendingProbeId = state.pendingProbeId || null;

  if (pendingProbeId) {
    if (Number(state.probeDeadlineAtMs) > nowMs) {
      return { action: 'NONE', reason: 'PROBE_PENDING', probeId: pendingProbeId };
    }
    if (incidentActive) {
      return { action: 'SUPPRESS', reason: 'FLEET_INCIDENT' };
    }
    if (nowMs < earliestAlertAtMs) {
      // Probe window has elapsed but the conservative grace has not: keep CONTACT_STALE.
      return { action: 'NONE', reason: 'GRACE_PENDING', probeId: pendingProbeId };
    }
    return { action: 'ALERT', reason: 'POLICY_STILL_FAILING', outageEpisodeId: state.outageEpisodeId };
  }

  if (incidentActive) {
    return { action: 'SUPPRESS', reason: 'FLEET_INCIDENT' };
  }
  const lastProbeSentAtMs = Number(state.lastProbeSentAtMs) || 0;
  if (lastProbeSentAtMs && nowMs - lastProbeSentAtMs < PROBE_RATE_LIMIT_MS) {
    // Never probe more than once per six hours per installation; wait out the cap.
    return { action: 'NONE', reason: 'PROBE_RATE_LIMITED' };
  }
  const episodeId = state.outageEpisodeId ||
    episodeIdFor(state.installationId, lastContactAtMs);
  return {
    action: 'ALLOCATE_PROBE',
    reason: 'CONTACT_STALE',
    outageEpisodeId: episodeId,
    probeId: probeIdFor(episodeId),
    // §8.7: high priority only where the device told us it can show notifications; the
    // probe callback still must run either way.
    probePriority: probePriority(state.lastObservedEnforcement),
  };
}

function probePriority(enforcement) {
  return enforcement && enforcement.notificationPermissionGranted === true ? 'high' : 'normal';
}

/** Fleet incident: absence-only alerts pause only when contact loss is fleet-wide AND a
 *  backend/Firebase failure corroborates it. `staleInstallations` counts unrelated
 *  installations over the probe threshold; the threshold is operational config. */
function fleetIncident({ corroboratingFailure, staleInstallations, threshold = 5 }) {
  return corroboratingFailure === true &&
    Number(staleInstallations) >= Number(threshold);
}

function isLivenessNoticeType(value) {
  return LIVENESS_NOTICE_TYPES.includes(value);
}

module.exports = {
  CONTACT_VERSION,
  PROBE_AFTER_MS,
  PROBE_WINDOW_MS,
  ALERT_GRACE_MS,
  SLEEP_ALERT_GRACE_MS,
  PROBE_RATE_LIMIT_MS,
  STATUS_ACTIVE,
  STATUS_CONTACT_STALE,
  STATUS_UNAVAILABLE,
  STATUS_MONITORING_UNAVAILABLE,
  ENFORCEMENT_HEALTHY,
  ENFORCEMENT_DEGRADED,
  CAPABILITIES,
  LIVENESS_COLLECTION,
  LIVENESS_NOTICE_TYPES,
  NOTICE_DEVICE_UNAVAILABLE,
  NOTICE_DEVICE_RECONNECTED,
  NOTICE_ENFORCEMENT_DEGRADED,
  NOTICE_ENFORCEMENT_RESTORED,
  livenessDocId,
  isValidInstallationId,
  isValidKeyId,
  isValidCounter,
  isValidProbeId,
  validateContact,
  contactMessage,
  missingCapabilities,
  enforcementState,
  storedEnforcement,
  episodeIdFor,
  probeIdFor,
  contactAcceptance,
  evaluationDecision,
  probePriority,
  fleetIncident,
  isLivenessNoticeType,
};
