'use strict';
// Phase 6 liveness suite (§8.7) — the real callable + evaluator against the Firestore
// emulator. Named without `.test.js` so `npm test` stays emulator-free; runs via
// `npm run test:liveness`.
//
// The contact's signed bytes are rebuilt independently here (sorted-key JCS by hand, not
// with the server's canonicalizer) so the wire format the Kotlin client must produce is
// pinned by a second implementation, exactly as the registration suite does.

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');

const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');
const { HttpsError } = require('firebase-functions/v2/https');

const {
  createSubmitDeviceContactHandler,
  createDeviceLivenessWorkerHandler,
} = require('../device-contact');
const { _createDmsWorkerHandler, _buildDmsMessage: buildMessage } = require('../dms-processor');

const PROJECT_ID = 'demo-myexodus-device-contact';
const OWNER = 'owner-uid';
const PARTNER = 'partner-uid';
const REL = 'rel-1';
const INSTALL = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';
const NOW = Date.UTC(2026, 10, 12, 12, 0, 0);
const MINUTE = 60 * 1000;

let app;
let db;
let clockMs;
let probes;
let pushes;

before(() => {
  app = initializeApp({ projectId: PROJECT_ID }, 'device-contact-emulator-suite');
  db = getFirestore(app);
});

after(async () => {
  await deleteApp(app);
});

beforeEach(async () => {
  clockMs = NOW;
  probes = [];
  pushes = [];
  for (const collection of [
    'partnerDeviceSigningRegistrations', 'partnerDeviceLiveness', 'pairings',
    'dms_notification_outbox', 'dms_install_state', 'dms_ingestion_outages',
    'users', 'deletionJobs',
  ]) {
    await db.recursiveDelete(db.collection(collection));
  }
  await db.doc('config/features').set({ device_liveness_alerts_v1_enabled: true });
  await db.doc('config/dms').delete();
  await db.doc('config/device_liveness_incident').delete();
});

function makeDevice() {
  const signing = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const spki = signing.publicKey.export({ type: 'spki', format: 'der' });
  return {
    signing,
    spkiBase64: spki.toString('base64'),
    keyId: crypto.createHash('sha256').update(spki).digest('base64url'),
  };
}

async function seedRegistration(device, { uid = OWNER, installationId = INSTALL } = {}) {
  await db.doc(`partnerDeviceSigningRegistrations/${uid}:${installationId}`).set({
    ownerUid: uid,
    installationId,
    signingKeyId: device.keyId,
    signingSpkiDerBase64: device.spkiBase64,
    recipientKeyId: 'r'.repeat(43),
    integrityTier: 'ACTIVE_UNATTESTED',
    registrationRevision: 1,
    status: 'ACTIVE',
    activatedAt: NOW,
  });
}

async function seedPairing({ consent = true, relationshipId = REL } = {}) {
  await db.doc(`pairings/${relationshipId}`).set({
    status: 'active',
    members: [OWNER, PARTNER],
    availabilityConsent: { [OWNER]: { enabled: consent } },
  });
}

const livenessPath = (uid = OWNER, installationId = INSTALL) =>
  `partnerDeviceLiveness/${uid}:${installationId}`;

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

/** Sorted-key JCS bytes, hand-built: deviceReportedAtMs, enforcement, heartbeatCounter,
 *  installationId, keyId, ownerUid, [probeId], v. */
function contactBytes(contact) {
  const e = contact.enforcement;
  const enforcementJson =
    `{"accessibilityEnabled":${e.accessibilityEnabled}` +
    `,"deviceAdminActive":${e.deviceAdminActive}` +
    `,"notificationPermissionGranted":${e.notificationPermissionGranted}` +
    `,"requiredCapabilities":${JSON.stringify(e.requiredCapabilities)}` +
    `,"vpnActive":${e.vpnActive}}`;
  return Buffer.from(
    `{"deviceReportedAtMs":${contact.deviceReportedAtMs}` +
    `,"enforcement":${enforcementJson}` +
    `,"heartbeatCounter":${contact.heartbeatCounter}` +
    `,"installationId":${JSON.stringify(contact.installationId)}` +
    `,"keyId":${JSON.stringify(contact.keyId)}` +
    `,"ownerUid":${JSON.stringify(contact.ownerUid)}` +
    (contact.probeId ? `,"probeId":${JSON.stringify(contact.probeId)}` : '') +
    ',"v":1}',
    'utf8',
  );
}

function contact(device, overrides = {}) {
  const payload = {
    v: 1,
    ownerUid: OWNER,
    installationId: INSTALL,
    keyId: device.keyId,
    heartbeatCounter: 1,
    deviceReportedAtMs: clockMs,
    enforcement: enforcement(),
    ...overrides,
  };
  return {
    ...payload,
    contactSignature: crypto
      .sign('sha256', contactBytes(payload), device.signing.privateKey)
      .toString('base64url'),
  };
}

const handler = () => createSubmitDeviceContactHandler({ db, now: () => clockMs });
const submit = (data, uid = OWNER) => handler()({ auth: uid ? { uid } : undefined, data });

async function expectRejection(promise, code, message) {
  try {
    await promise;
    assert.fail(`expected HttpsError(${code})`);
  } catch (e) {
    assert.ok(e instanceof HttpsError, `expected HttpsError, got ${e && e.constructor && e.constructor.name}`);
    assert.strictEqual(e.code, code, `expected ${code}, got ${e.code}: ${e.message}`);
    if (message) assert.strictEqual(e.message, message);
    return e;
  }
}

async function seedLiveness(overrides = {}) {
  const installationId = overrides.installationId || INSTALL;
  await db.doc(livenessPath(OWNER, installationId)).set({
    ownerUid: OWNER,
    installationId,
    enrolledAtMs: NOW - 10 * MINUTE,
    lastAcceptedContactAtMs: NOW - 90 * MINUTE,
    lastHeartbeatCounter: 3,
    lastDeviceReportedAtMs: NOW - 90 * MINUTE,
    lastObservedEnforcement: { ...enforcement(), observedAtMs: NOW - 90 * MINUTE },
    enforcementState: 'HEALTHY',
    missingCapabilities: [],
    status: 'ACTIVE',
    outageEpisodeId: null,
    pendingProbeId: null,
    probeSentAtMs: null,
    probeDeadlineAtMs: null,
    alertedAtMs: null,
    restoredAtMs: null,
    suppressionReason: null,
    earliestAlertAtMs: NOW - 89 * MINUTE + 240 * MINUTE,
    updatedAtMs: NOW - 89 * MINUTE,
    ...overrides,
  });
}

async function seedPushInstallation(uid, installationId = INSTALL, extra = {}) {
  await db.doc(`users/${uid}/push_installations/${installationId}`).set({
    provider: 'fcm',
    token: `token-${uid}`,
    pushEligible: true,
    updatedAt: Timestamp.fromMillis(NOW),
    ...extra,
  });
}

const worker = () => createDeviceLivenessWorkerHandler({
  db,
  now: () => clockMs,
  sendProbe: async (probe) => { probes.push(probe); },
  sendPush: async ({ row, installs }) => {
    pushes.push({ row, installs });
    return { providerAccepted: installs.length, invalidInstallations: [] };
  },
  log: { info() {}, warn() {}, error() {} },
});

const outboxRow = async (generation, noticeType) =>
  (await db.doc(`dms_notification_outbox/${INSTALL}.${REL}.${generation}.${noticeType}`).get()).data();

test('the contact endpoint refuses bad auth, payloads, keys and signatures without writing', async () => {
  const device = makeDevice();
  await seedRegistration(device);
  await seedPairing();

  await expectRejection(submit(contact(device), null), 'unauthenticated');
  await expectRejection(submit(null), 'invalid-argument', 'CONTACT_INVALID');
  await expectRejection(
    submit(contact(device, { ownerUid: 'someone-else' }), OWNER),
    'permission-denied', 'OWNER_UID_MISMATCH',
  );
  await expectRejection(
    submit({ ...contact(device), contactSignature: 'short' }),
    'invalid-argument', 'SIGNATURE_INVALID',
  );
  await expectRejection(
    submit(contact(device, { installationId: '8a7b6c5d-4e3f-4a2b-8c1d-0e9f8a7b6c5d' })),
    'permission-denied', 'INSTALL_NOT_REGISTERED',
  );

  const other = makeDevice();
  const forged = contact(other, { keyId: device.keyId });
  await expectRejection(submit(forged), 'permission-denied', 'SIGNATURE_MISMATCH');

  await db.doc(`deletionJobs/${OWNER}`).set({ status: 'QUEUED' });
  await expectRejection(submit(contact(device)), 'permission-denied', 'ACCOUNT_DELETING');

  assert.strictEqual((await db.doc(livenessPath()).get()).exists, false);
  assert.strictEqual((await db.collection('dms_notification_outbox').get()).size, 0);
});

test('a first contact establishes the baseline silently and bridges the legacy state', async () => {
  const device = makeDevice();
  await seedRegistration(device);
  await seedPairing();

  const result = await submit(contact(device, {
    heartbeatCounter: 1,
    enforcement: enforcement({ vpnActive: false, requiredCapabilities: ['VPN'] }),
  }));
  assert.deepStrictEqual(result, {
    outcome: 'ACCEPTED',
    serverCounter: 1,
    enforcementState: 'DEGRADED',
    enforcementNotice: null, // enrollment never fabricates a tamper signal
    reconnectNotice: null,
    outboxCreated: 0,
  });

  const liveness = (await db.doc(livenessPath()).get()).data();
  assert.strictEqual(liveness.status, 'ACTIVE');
  assert.strictEqual(liveness.lastAcceptedContactAtMs, NOW);
  assert.strictEqual(liveness.lastHeartbeatCounter, 1);
  assert.strictEqual(liveness.enforcementState, 'DEGRADED');
  assert.deepStrictEqual(liveness.missingCapabilities, ['VPN']);
  assert.strictEqual(liveness.outageEpisodeId, null);
  assert.strictEqual(liveness.alertedAtMs, null);
  assert.strictEqual(liveness.earliestAlertAtMs, NOW + 240 * MINUTE);
  assert.strictEqual(liveness.enrolledAtMs, NOW);
  assert.strictEqual(liveness.lastObservedEnforcement.observedAtMs, NOW);

  const legacy = (await db.doc(`dms_install_state/${OWNER}_${INSTALL}`).get()).data();
  assert.strictEqual(legacy.last_received_at_ms, NOW);
  assert.strictEqual(legacy.next_deadline_at.toMillis(), NOW + 6 * 60 * MINUTE);
  assert.strictEqual(legacy.late_deadline_at.toMillis(), NOW + 45 * MINUTE);
  assert.strictEqual(legacy.notice_state, 'NONE');
  assert.strictEqual(legacy.liveness_generation, 1);
  assert.strictEqual(legacy.last_counter, undefined, 'the legacy counter is a separate namespace');
});

test('replays write nothing and a probe answer must name the pending probe', async () => {
  const device = makeDevice();
  await seedRegistration(device);
  await seedPairing();
  await submit(contact(device, { heartbeatCounter: 1 }));
  const acceptedAt = (await db.doc(livenessPath()).get()).data().lastAcceptedContactAtMs;

  clockMs += 5 * MINUTE;
  assert.deepStrictEqual(
    await submit(contact(device, { heartbeatCounter: 1 })),
    { outcome: 'STALE', serverCounter: 1 },
  );
  assert.strictEqual(
    (await db.doc(livenessPath()).get()).data().lastAcceptedContactAtMs, acceptedAt,
  );

  await db.doc(livenessPath()).set({ pendingProbeId: 'probe-current' }, { merge: true });
  assert.deepStrictEqual(
    await submit(contact(device, { heartbeatCounter: 2, probeId: 'probe-stale-1' })),
    { outcome: 'INVALID_PROBE' },
  );
  assert.strictEqual((await db.doc(livenessPath()).get()).data().lastHeartbeatCounter, 1);

  const answered = await submit(contact(device, { heartbeatCounter: 2, probeId: 'probe-current' }));
  assert.strictEqual(answered.outcome, 'ACCEPTED');
  const liveness = (await db.doc(livenessPath()).get()).data();
  assert.strictEqual(liveness.pendingProbeId, null);
  assert.strictEqual(liveness.lastHeartbeatCounter, 2);
});

test('one enforcement notice per transition direction, keyed by the accepting counter', async () => {
  const device = makeDevice();
  await seedRegistration(device);
  await seedPairing();
  await submit(contact(device, { heartbeatCounter: 1 }));

  clockMs += MINUTE;
  const degraded = await submit(contact(device, {
    heartbeatCounter: 2,
    enforcement: enforcement({ vpnActive: false }),
  }));
  assert.strictEqual(degraded.enforcementNotice, 'ENFORCEMENT_DEGRADED');
  assert.strictEqual(degraded.outboxCreated, 1);
  const row = await outboxRow(2, 'ENFORCEMENT_DEGRADED');
  assert.strictEqual(row.state_doc_id, `${OWNER}:${INSTALL}`);
  assert.strictEqual(row.recipient_uid, PARTNER);
  assert.strictEqual(row.monitored_uid, OWNER);
  assert.strictEqual(row.status, 'PENDING');

  clockMs += MINUTE;
  const stillDegraded = await submit(contact(device, {
    heartbeatCounter: 3,
    enforcement: enforcement({ vpnActive: false, accessibilityEnabled: false }),
  }));
  assert.strictEqual(stillDegraded.enforcementNotice, null, 'no second alert for the same state');

  clockMs += MINUTE;
  const restored = await submit(contact(device, { heartbeatCounter: 4 }));
  assert.strictEqual(restored.enforcementNotice, 'ENFORCEMENT_RESTORED');
  assert.ok(await outboxRow(4, 'ENFORCEMENT_RESTORED'));
  assert.strictEqual((await db.collection('dms_notification_outbox').get()).size, 2);

  // An unconsented relationship is never written to.
  await db.doc(`pairings/${REL}`).set({
    status: 'active', members: [OWNER, PARTNER], availabilityConsent: { [OWNER]: { enabled: false } },
  });
  clockMs += MINUTE;
  const unshared = await submit(contact(device, {
    heartbeatCounter: 5,
    enforcement: enforcement({ vpnActive: false }),
  }));
  assert.strictEqual(unshared.enforcementNotice, 'ENFORCEMENT_DEGRADED');
  assert.strictEqual(unshared.outboxCreated, 0, 'consent is re-read at contact time');
});

test('90 minutes of silence allocates one probe; the pending window blocks a second', async () => {
  await seedLiveness();
  await seedPushInstallation(OWNER);

  const first = await worker()();
  assert.strictEqual(first.probed, 1);
  assert.strictEqual(probes.length, 1);
  assert.strictEqual(probes[0].priority, 'high');
  assert.match(probes[0].probeId, /^probe-ep-/);

  const liveness = (await db.doc(livenessPath()).get()).data();
  assert.strictEqual(liveness.status, 'CONTACT_STALE');
  assert.strictEqual(liveness.pendingProbeId, probes[0].probeId);
  assert.strictEqual(liveness.probeSentAtMs, NOW);
  assert.strictEqual(liveness.probeDeadlineAtMs, NOW + 20 * MINUTE);

  clockMs += 5 * MINUTE;
  const second = await worker()();
  assert.strictEqual(probes.length, 1, 'the 20-minute window forbids a second wake');
  assert.strictEqual(second.quiet, 1);
});

test('a missing push installation claims the probe but never alerts before the grace', async () => {
  await seedLiveness();

  const result = await worker()();
  assert.strictEqual(result.probed, 0);
  assert.strictEqual(result.quiet, 1);
  const liveness = (await db.doc(livenessPath()).get()).data();
  assert.ok(liveness.pendingProbeId, 'the probe stays claimed; its window still elapses');

  clockMs += 25 * MINUTE; // window elapsed, grace not
  const grace = await worker()();
  assert.strictEqual(grace.quiet, 1);
  assert.strictEqual((await db.collection('dms_notification_outbox').get()).size, 0);
});

test('the alert waits for the grace, publishes once, and dispatches to the partner', async () => {
  await seedPairing();
  await seedLiveness({
    lastAcceptedContactAtMs: NOW - 300 * MINUTE,
    outageEpisodeId: 'ep-open',
    pendingProbeId: 'probe-open',
    probeDeadlineAtMs: NOW - MINUTE,
    probeSentAtMs: NOW - 20 * MINUTE,
    earliestAlertAtMs: NOW - MINUTE,
  });
  await seedPushInstallation(PARTNER, 'partner-install');

  const result = await worker()();
  assert.strictEqual(result.alerted, 1);
  assert.strictEqual(result.sent, 1);
  assert.strictEqual(pushes.length, 1);
  assert.strictEqual(pushes[0].row.notice_type, 'DEVICE_UNAVAILABLE');
  // Owner ruling 2026-11-12: the push is data-only; the app renders the copy post-parse.
  const wire = buildMessage(pushes[0].row);
  assert.strictEqual(wire.notification, undefined, 'no provider-rendered copy');
  assert.strictEqual(wire.data.type, 'PARTNER_AVAILABILITY');
  assert.strictEqual(wire.data.noticeType, 'DEVICE_UNAVAILABLE');

  const liveness = (await db.doc(livenessPath()).get()).data();
  assert.strictEqual(liveness.status, 'UNAVAILABLE');
  assert.strictEqual(liveness.alertedAtMs, NOW);

  const row = await outboxRow('ep-open', 'DEVICE_UNAVAILABLE');
  assert.strictEqual(row.status, 'SENT');
  assert.strictEqual(row.provider_accepted, 1);

  clockMs += 5 * MINUTE;
  const again = await worker()();
  assert.strictEqual(again.alerted, 0);
  assert.strictEqual(again.sent, 0);
  assert.strictEqual(pushes.length, 1, 'one alert per episode');
  assert.strictEqual((await db.collection('dms_notification_outbox').get()).size, 1);
});

test('a later contact closes the episode and notifies reconnected only after an alert', async () => {
  const device = makeDevice();
  await seedRegistration(device);
  await seedPairing();
  await seedPushInstallation(PARTNER, 'partner-install');
  await seedLiveness({
    lastAcceptedContactAtMs: NOW - 300 * MINUTE,
    status: 'UNAVAILABLE',
    outageEpisodeId: 'ep-open',
    alertedAtMs: NOW - 30 * MINUTE,
    lastHeartbeatCounter: 3,
  });

  const accepted = await submit(contact(device, { heartbeatCounter: 4 }));
  assert.strictEqual(accepted.outcome, 'ACCEPTED');
  assert.strictEqual(accepted.reconnectNotice, 'DEVICE_RECONNECTED');
  assert.strictEqual(accepted.outboxCreated, 1);

  const liveness = (await db.doc(livenessPath()).get()).data();
  assert.strictEqual(liveness.status, 'ACTIVE');
  assert.strictEqual(liveness.outageEpisodeId, null);
  assert.strictEqual(liveness.alertedAtMs, null);
  assert.strictEqual(liveness.restoredAtMs, NOW);

  await worker()();
  assert.strictEqual(pushes.length, 1);
  assert.strictEqual(pushes[0].row.notice_type, 'DEVICE_RECONNECTED');
  assert.strictEqual((await outboxRow('ep-open', 'DEVICE_RECONNECTED')).status, 'SENT');

  // A second silent episode must not notify a reconnect nobody was told about.
  await seedLiveness({
    lastAcceptedContactAtMs: NOW - 300 * MINUTE,
    status: 'UNAVAILABLE',
    outageEpisodeId: 'ep-silent',
    alertedAtMs: null,
  });
  clockMs += MINUTE;
  const silent = await submit(contact(device, { heartbeatCounter: 5 }));
  assert.strictEqual(silent.reconnectNotice, null);
});

test('dispatch revalidates liveness rows against their own state document', async () => {
  await seedPairing();
  await seedPushInstallation(PARTNER, 'partner-install');
  await seedLiveness({ status: 'ACTIVE', outageEpisodeId: null, alertedAtMs: null });
  await db.doc(`dms_notification_outbox/${INSTALL}.${REL}.ep-old.DEVICE_UNAVAILABLE`).set({
    install_id: INSTALL,
    state_doc_id: `${OWNER}:${INSTALL}`,
    monitored_uid: OWNER,
    relationship_id: REL,
    recipient_uid: PARTNER,
    outage_generation: 'ep-old',
    notice_type: 'DEVICE_UNAVAILABLE',
    status: 'PENDING',
    attempts: 0,
    next_attempt_at_ms: NOW - MINUTE,
    created_at_ms: NOW - 2 * MINUTE,
  });

  const result = await worker()();
  assert.strictEqual(result.suppressed, 1);
  assert.strictEqual(pushes.length, 0);
  const row = await outboxRow('ep-old', 'DEVICE_UNAVAILABLE');
  assert.strictEqual(row.status, 'SUPPRESSED');
  assert.strictEqual(row.suppressed_reason, 'RECOVERED_BEFORE_DISPATCH');
});

test('a fleet incident needs five stale installs plus a corroborating failure', async () => {
  for (let i = 0; i < 5; i += 1) {
    const installationId = `0000000${i}-0000-4000-8000-000000000000`;
    await seedLiveness({ installationId, lastAcceptedContactAtMs: NOW - 200 * MINUTE });
    await seedPushInstallation(OWNER, installationId);
  }
  await db.doc('config/dms').set({ dispatch_hold: true });

  const result = await worker()();
  assert.strictEqual(result.incidentActive, true);
  assert.strictEqual(result.incidentSuppressed, 5);
  assert.strictEqual(probes.length, 0, 'absence-only probes pause during a fleet incident');
  const incident = (await db.doc('config/device_liveness_incident').get()).data();
  assert.strictEqual(incident.active, true);
  assert.strictEqual(incident.corroboratingFailure, 'DISPATCH_HOLD');
  const state = (await db.doc(livenessPath('owner-uid', '00000000-0000-4000-8000-000000000000')).get()).data();
  assert.strictEqual(state.suppressionReason, 'FLEET_INCIDENT');

  // Correlation without a backend failure is not an incident.
  await db.doc('config/device_liveness_incident').delete();
  await db.doc('config/dms').delete();
  clockMs += 5 * MINUTE;
  const next = await worker()();
  assert.strictEqual(next.incidentActive, false);
  assert.strictEqual(probes.length, 5, 'each install is probed once the corroboration is gone');
});

test('an open ingestion outage pauses evaluation but keeps explicit enforcement dispatch', async () => {
  await seedPairing();
  await seedLiveness({ lastAcceptedContactAtMs: NOW - 200 * MINUTE });
  await seedPushInstallation(PARTNER, 'partner-install');
  await db.collection('dms_ingestion_outages').add({ started_at_ms: NOW - 60 * MINUTE, ended_at_ms: null });
  await db.doc(`dms_notification_outbox/${INSTALL}.${REL}.9.ENFORCEMENT_DEGRADED`).set({
    install_id: INSTALL,
    state_doc_id: `${OWNER}:${INSTALL}`,
    monitored_uid: OWNER,
    relationship_id: REL,
    recipient_uid: PARTNER,
    outage_generation: 9,
    notice_type: 'ENFORCEMENT_DEGRADED',
    status: 'PENDING',
    attempts: 0,
    next_attempt_at_ms: NOW - MINUTE,
    created_at_ms: NOW - 2 * MINUTE,
  });
  await db.doc(livenessPath()).set({ enforcementState: 'DEGRADED' }, { merge: true });

  const result = await worker()();
  assert.strictEqual(result.held, 'INGESTION_OUTAGE');
  assert.strictEqual(probes.length, 0);
  assert.strictEqual(pushes.length, 1, 'explicit enforcement is not absence-based');
  assert.strictEqual(pushes[0].row.notice_type, 'ENFORCEMENT_DEGRADED');
});

test('the rollout flag stops both workers: liveness owns dispatch or the legacy worker does', async () => {
  await db.doc('config/features').set({ device_liveness_alerts_v1_enabled: false });
  assert.deepStrictEqual(await worker()(), { disabled: 'LIVENESS_V1_OFF' });

  await db.doc('config/features').set({ device_liveness_alerts_v1_enabled: true });
  const legacy = await _createDmsWorkerHandler({ db, now: () => clockMs, log: { info() {}, warn() {}, error() {} } })();
  assert.deepStrictEqual(legacy, { disabled: 'LIVENESS_V1_ENABLED' });

  await db.doc('config/features').set({ device_liveness_alerts_v1_enabled: true, partnerAccountabilityEnabled: false });
  assert.deepStrictEqual(await worker()(), { disabled: 'PARTNER_ACCOUNTABILITY' });
});

test('a contact accepted while liveness is paused stores MONITORING_UNAVAILABLE, then resumes', async () => {
  const device = makeDevice();
  await seedRegistration(device);
  await seedPairing();

  await db.doc('config/features').set({ device_liveness_alerts_v1_enabled: false });
  const paused = await submit(contact(device));
  assert.strictEqual(paused.outcome, 'ACCEPTED');
  const state = (await db.doc(livenessPath()).get()).data();
  assert.strictEqual(state.status, 'MONITORING_UNAVAILABLE');
  assert.strictEqual(state.lastAcceptedContactAtMs, NOW);

  await db.doc('config/features').set({ device_liveness_alerts_v1_enabled: true });
  clockMs += MINUTE;
  await submit(contact(device, { heartbeatCounter: 2, deviceReportedAtMs: clockMs }));
  assert.strictEqual((await db.doc(livenessPath()).get()).data().status, 'ACTIVE');
});

test('MONITORING_UNAVAILABLE pauses evaluation without fabricating alerts', async () => {
  await seedLiveness({
    status: 'MONITORING_UNAVAILABLE',
    lastAcceptedContactAtMs: NOW - 500 * MINUTE,
  });
  const result = await worker()();
  assert.strictEqual(result.stale, 1);
  assert.strictEqual(result.quiet, 1);
  assert.strictEqual(probes.length, 0);
  assert.strictEqual((await db.collection('dms_notification_outbox').get()).size, 0);
});
