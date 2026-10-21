'use strict';
// DMS deadline processor + durable notification dispatch (partner event-delivery plan §7
// Step 8, §4 DMS states/outages, §8.11-§8.12).
//
// There is no durable delayed-job facility in this deployment beyond Cloud Scheduler, and the
// plan forbids introducing a new broker. So the queue is the existing authoritative database:
//
//   transition  — a bounded, indexed query over `dms_install_state.next_deadline_at` (single-
//                 field automatic index; LIMIT from config). Every claim re-reads the row in a
//                 transaction and compare-and-sets on (liveness_generation, last_received_at_ms):
//                 a heartbeat that landed after the query turns the claim into a no-op.
//   outbox      — written in the SAME transaction as the state transition, with a deterministic
//                 id over (install_id, relationship_id, outage_generation, notice_type), so the
//                 unique key is structural rather than a check-then-write race.
//   dispatch    — bounded batch; each row is re-claimed, rejoined to the authoritative pairing,
//                 consent, and DMS state immediately before the provider call. A stale or
//                 revoked row is suppressed, never sent. FCM acceptance is recorded as SENT but
//                 is not treated as a delivery receipt.
//   safety      — an open ingestion outage holds the whole pass (no transitions, no dispatch);
//                 a burst of first notices opens the fleet circuit breaker and holds dispatch
//                 until an operator releases it at a controlled rate.

const crypto = require('node:crypto');
const { getFirestore, Timestamp, FieldValue } = require('firebase-admin/firestore');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { logger } = require('firebase-functions');

require('./fcm-transport'); // registers the 'fcm' transport (token classification reuse)
const { isTokenInvalid } = require('./dispatch');
const { selectInstallations } = require('./relay-logic');
const { deferWhileIndexBuilds } = require('./index-build-guard');
const {
  normalizeConfig,
  noticeDocId,
  deadlineDecision,
  lateReminderDecision,
  LATE_AFTER_MS,
  consentingPartner,
  suppressionReason,
  circuitBreakerTripped,
  dispatchBackoffMs,
  shouldRetryDispatch,
  NOTICE_PENDING,
  NOTICE_DISPATCHING,
  NOTICE_SENT,
  NOTICE_SUPPRESSED,
  NOTICE_FAILED_TERMINAL,
  NOTICE_AVAILABILITY,
  NOTICE_RECOVERY,
  PARTNER_PUSH_AFTER_MS,
} = require('./dms-logic');

const REGION = 'us-central1';
const DMS_CONFIG_DOC = 'config/dms';
const OUTBOX = 'dms_notification_outbox';
const MAX_RELATIONSHIPS_PER_INSTALL = 50;
const MAX_OUTAGE_CANDIDATES = 5;
const MAX_LEASE_RECOVERY_PER_RUN = 50;

// §8.12: the complete plaintext on the wire is the fixed OS copy + the generic sync data key.
// No relationship id, uid, install id, event category, or precise time.
const ANDROID_PACKAGE = 'com.example.myexodus';
const CHANNEL_AVAILABILITY = 'availability_alerts_v1';
const CHANNEL_CHECKIN = 'checkin_reminders_v1';
const PUSH_TITLE = 'Partner check-in';
// §18 DoD: the notice itself must say silence is not proof — a partner may read it as an
// accusation otherwise. Still identity-free and whole-content (no fetch needed to interpret).
const PUSH_BODY_SILENT = 'A paired device has not checked in recently. This is not proof the app was removed or blocking was turned off.';
const PUSH_BODY_RECOVERED = 'A paired device has checked in again.';
const PUSH_TTL_MS = 60 * 60 * 1000;

// The monitored device's own LATE nudge (§4 "send at most one generic check-in reminder").
// Fixed generic copy, no partner/relationship text; NORMAL priority because the plan calls it
// a best-effort nudge, not a wake-up guarantee — the user-visible time-sensitive notice is
// the partner's six-hour alert, which stays HIGH. The TTL covers the LATE window itself
// (45–90 minutes): a reminder delivered after the device has been silent for longer is no
// longer the state it describes, and the partner path owns that threshold.
const CHECKIN_TITLE = 'Check in with MyExodus';
const CHECKIN_BODY = 'Your device has not checked in recently. Open the app to stay connected.';
const CHECKIN_TTL_MS = 45 * 60 * 1000;
const CHECKIN_COLLAPSE_KEY = 'checkin';

/** Collapse key for one relationship's availability/recovery notices. Per-device scoped, so
 *  relation identity is all that distinguishes two pairs; hashed because a raw relationship
 *  id must never appear in provider metadata (§8.12). Latest notice for the pair wins, which
 *  is exactly the desired behavior when a recovery supersedes a queued silence notice. */
function availabilityCollapseKey(relationshipId) {
  const digest = crypto.createHash('sha256').update(String(relationshipId)).digest('hex');
  return `avail-${digest.slice(0, 16)}`;
}

function errorCodeOf(error) {
  const code = error && typeof error.code === 'string' ? error.code : '';
  return code ? code.slice(0, 64) : 'UNKNOWN';
}

/**
 * The latest ENDED ingestion outage that could have consumed this install's deadline:
 * `end >= last_received` (the silence overlaps it) and `start <= last_received + windowMs`
 * (the deadline did not already fire before the outage began). Returns the outage end time or
 * null. The window matters: the LATE reminder only compensates an outage that began inside
 * its own 45-minute interval — an outage starting after the LATE deadline cannot have hidden
 * that transition and must not postpone it further.
 */
async function outageEndFor(tx, db, lastReceivedAtMs, windowMs = PARTNER_PUSH_AFTER_MS) {
  const snap = await tx.get(
    db.collection('dms_ingestion_outages')
      .where('ended_at_ms', '>=', lastReceivedAtMs)
      .orderBy('ended_at_ms', 'desc')
      .limit(MAX_OUTAGE_CANDIDATES),
  );
  for (const doc of snap.docs) {
    const data = doc.data();
    const start = Number(data.started_at_ms) || 0;
    const end = Number(data.ended_at_ms) || 0;
    if (end <= 0 || start <= 0) continue;
    if (start <= lastReceivedAtMs + windowMs && end >= lastReceivedAtMs) return end;
  }
  return null;
}

async function findOpenIngestionOutage(db) {
  const snap = await db.collection('dms_ingestion_outages')
    .where('ended_at_ms', '==', null)
    .limit(1)
    .get();
  return snap.empty ? null : snap.docs[0].id;
}

/**
 * One due row, in a transaction. `expected` is the snapshot the due query saw; the fresh
 * re-read supplies the actual state, and any movement on generation/receipt is a no-op.
 */
async function processDueRow(db, dueDoc, nowMs) {
  const expected = dueDoc.data();
  return db.runTransaction(async (tx) => {
    const fresh = await tx.get(dueDoc.ref);
    if (!fresh.exists) return { status: 'stale', reason: 'REMOVED' };
    const state = fresh.data();
    const lastReceivedAtMs = Number(state.last_received_at_ms) || 0;
    const outageEndMs = await outageEndFor(tx, db, lastReceivedAtMs);
    const decision = deadlineDecision({
      state,
      expectedGeneration: expected.liveness_generation,
      expectedLastReceivedAtMs: expected.last_received_at_ms,
      nowMs,
      outageEndMs,
    });

    if (decision.action === 'STALE') return { status: 'stale', reason: decision.reason };
    if (decision.action === 'DEFER') {
      tx.update(dueDoc.ref, {
        next_deadline_at: Timestamp.fromMillis(decision.nextDeadlineAtMs),
        deadline_rebased_at_ms: nowMs,
        updated_at: Timestamp.fromMillis(nowMs),
      });
      return { status: 'deferred', nextDeadlineAtMs: decision.nextDeadlineAtMs };
    }

    // Reads before writes: every consenting relationship, then the deterministic outbox ids.
    const pairsSnap = await tx.get(
      db.collection('pairings')
        .where('members', 'array-contains', state.uid)
        .limit(MAX_RELATIONSHIPS_PER_INSTALL),
    );
    const relationships = pairsSnap.docs
      .map((doc) => ({ relationshipId: doc.id, partner: consentingPartner(doc.data(), state.uid) }))
      .filter((r) => r.partner != null)
      .map((r) => ({
        ...r,
        ref: db.doc(`${OUTBOX}/${noticeDocId(
          state.install_id,
          r.relationshipId,
          decision.outageGeneration,
          NOTICE_AVAILABILITY,
        )}`),
      }));
    const outboxSnaps = [];
    for (const relationship of relationships) {
      outboxSnaps.push(await tx.get(relationship.ref));
    }

    tx.update(dueDoc.ref, {
      outage_generation: decision.outageGeneration,
      notice_state: 'NOTIFIED',
      last_notified_generation: decision.notifiedGeneration,
      notified_at_ms: nowMs,
      next_deadline_at: FieldValue.delete(),
      updated_at: Timestamp.fromMillis(nowMs),
    });

    let created = 0;
    relationships.forEach((relationship, index) => {
      if (outboxSnaps[index].exists) return;
      tx.set(relationship.ref, {
        install_id: state.install_id,
        state_doc_id: dueDoc.id,
        monitored_uid: state.uid,
        relationship_id: relationship.relationshipId,
        recipient_uid: relationship.partner.recipientUid,
        outage_generation: decision.outageGeneration,
        notice_type: NOTICE_AVAILABILITY,
        expected_notified_generation: decision.notifiedGeneration,
        status: NOTICE_PENDING,
        attempts: 0,
        next_attempt_at_ms: nowMs,
        created_at_ms: nowMs,
      });
      created += 1;
    });
    return { status: 'notified', outboxCreated: created };
  });
}

/**
 * §4/§8.9 LATE reminder for one due state row. Two phases by necessity — the CAS claim is a
 * transaction, the provider call is not — and the mark is committed BEFORE the send: the
 * contract is at-most-once (a best-effort nudge), so a crash between the two loses one
 * reminder rather than risking a second. No outbox row: this notice has one recipient (the
 * install itself), no relationship/consent fan-out, and therefore none of the outbox's
 * dispatch-time revalidation needs. The installation row is re-read immediately before the
 * send, and a stale/cleared/opted-out row is skipped.
 */
async function processLateRow(db, dueDoc, nowMs, sendCheckin) {
  const expected = dueDoc.data();
  const claim = await db.runTransaction(async (tx) => {
    const fresh = await tx.get(dueDoc.ref);
    if (!fresh.exists) return { status: 'stale', reason: 'REMOVED' };
    const state = fresh.data();
    const outageEndMs = await outageEndFor(
      tx, db, Number(state.last_received_at_ms) || 0, LATE_AFTER_MS,
    );
    const decision = lateReminderDecision({
      state,
      expectedGeneration: expected.liveness_generation,
      expectedLastReceivedAtMs: expected.last_received_at_ms,
      nowMs,
      outageEndMs,
    });
    if (decision.action === 'STALE') return { status: 'stale', reason: decision.reason };
    if (decision.action === 'DEFER') {
      tx.update(dueDoc.ref, {
        late_deadline_at: Timestamp.fromMillis(decision.nextLateDeadlineAtMs),
        late_reminder_rebased_at_ms: nowMs,
        updated_at: Timestamp.fromMillis(nowMs),
      });
      return { status: 'deferred' };
    }
    tx.update(dueDoc.ref, {
      late_reminder_generation: decision.generation,
      late_reminder_at_ms: nowMs,
      late_deadline_at: FieldValue.delete(),
      updated_at: Timestamp.fromMillis(nowMs),
    });
    return { status: 'remind', uid: state.uid, installId: state.install_id };
  });
  if (claim.status !== 'remind') return claim;

  const snap = await db.doc(`users/${claim.uid}/push_installations/${claim.installId}`).get();
  const installation = snap.exists ? { id: snap.id, ...snap.data() } : null;
  const undeliverable = !installation ||
    installation.provider !== 'fcm' ||
    installation.stale === true ||
    installation.pushEligible === false ||
    !installation.token;
  if (undeliverable) return { status: 'reminded', skipped: true };

  try {
    await sendCheckin({ installation, nowMs });
    return { status: 'reminded', sent: true };
  } catch (error) {
    if (isTokenInvalid('fcm', error)) {
      await markInstallationStale(db, claim.uid, claim.installId);
      return { status: 'reminded', tokenCleared: true };
    }
    return { status: 'reminded', sendFailed: errorCodeOf(error) };
  }
}

async function listRecipientInstallations(db, recipientUid, nowMs) {
  const snap = await db.collection(`users/${recipientUid}/push_installations`).get();
  return selectInstallations(
    snap.docs.map((doc) => ({ id: doc.id, data: doc.data() })),
    nowMs,
  ).filter((install) => install.provider === 'fcm' && install.pushEligible !== false && install.token);
}

/**
 * Claim + revalidate one outbox row. Returns `{ send, row, attempts }`, `{ suppressed }`, or
 * `{ skip }`. The pairing/consent/state reads are the dispatch-time recheck the plan requires;
 * they happen inside the claim transaction so the row cannot change underneath the decision.
 */
async function claimNotice(db, rowDoc, nowMs) {
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(rowDoc.ref);
    if (!snap.exists) return { skip: true };
    const row = snap.data();
    if (row.status !== NOTICE_PENDING || Number(row.next_attempt_at_ms) > nowMs) {
      return { skip: true };
    }

    const [pairSnap, stateSnap] = await Promise.all([
      tx.get(db.doc(`pairings/${row.relationship_id}`)),
      tx.get(db.doc(`dms_install_state/${row.state_doc_id}`)),
    ]);
    const pairing = pairSnap.exists ? pairSnap.data() : null;
    const reason = suppressionReason({
      pairing,
      monitoredUid: row.monitored_uid,
      row,
      state: stateSnap.exists ? stateSnap.data() : null,
    });
    if (reason) {
      tx.update(rowDoc.ref, {
        status: NOTICE_SUPPRESSED,
        suppressed_reason: reason,
        updated_at_ms: nowMs,
      });
      return { suppressed: true, reason };
    }

    const attempts = (Number(row.attempts) || 0) + 1;
    tx.update(rowDoc.ref, {
      status: NOTICE_DISPATCHING,
      attempts,
      lease_until_ms: nowMs + 2 * 60 * 1000,
      dispatch_started_at_ms: nowMs,
    });
    return { send: true, row, attempts };
  });
}

/** Fenced finalize: only the claim that still owns the row may finish it. */
async function finalizeNotice(db, rowRef, attempts, patch) {
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(rowRef);
    if (!snap.exists) return false;
    const data = snap.data();
    if (data.status !== NOTICE_DISPATCHING || Number(data.attempts) !== attempts) return false;
    tx.update(rowRef, patch);
    return true;
  });
}

/** §7 Step 9: remove the provider-invalid token; keep the tombstone. The row says "this
 *  token cannot be sent to", never "the app is uninstalled" — the client re-registers a
 *  fresh token on its next sync pass (`PushInstallationRegistrar` full-replace write). */
async function markInstallationStale(db, recipientUid, installationId) {
  await db.doc(`users/${recipientUid}/push_installations/${installationId}`)
    .set({
      stale: true,
      staleAt: FieldValue.serverTimestamp(),
      token: FieldValue.delete(),
    }, { merge: true });
}

/**
 * §8.12: the complete plaintext on the wire. Fixed OS copy + the generic sync key only — no
 * relationship id, uid, install id, event category, or precise time, because those would let
 * anyone holding a notification read the partner's state without the app's E2EE channel.
 * Exported so the emulator suite can assert the payload byte-for-byte.
 */
function buildDmsMessage(row, tokens) {
  return {
    tokens,
    notification: {
      title: PUSH_TITLE,
      body: row.notice_type === NOTICE_RECOVERY ? PUSH_BODY_RECOVERED : PUSH_BODY_SILENT,
    },
    data: { v: '1', type: 'SYNC_AVAILABLE' },
    android: {
      priority: 'high',
      ttl: PUSH_TTL_MS,
      collapseKey: availabilityCollapseKey(row.relationship_id),
      restrictedPackageName: ANDROID_PACKAGE,
      notification: { channelId: CHANNEL_AVAILABILITY },
    },
  };
}

/**
 * §4/§8.9: the LATE reminder to the monitored device. Fixed generic copy, no relationship
 * identity, normal priority, and its own channel so a user can silence self-reminders
 * without silencing partner accountability events (or vice versa). Collapses by device: only
 * the newest reminder for this install is worth showing.
 */
function buildCheckinMessage(installation) {
  return {
    token: installation.token,
    notification: { title: CHECKIN_TITLE, body: CHECKIN_BODY },
    data: { v: '1', type: 'CHECKIN_REMINDER' },
    android: {
      priority: 'normal',
      ttl: CHECKIN_TTL_MS,
      collapseKey: CHECKIN_COLLAPSE_KEY,
      restrictedPackageName: ANDROID_PACKAGE,
      notification: { channelId: CHANNEL_CHECKIN },
    },
  };
}

/**
 * Default provider call. A user-visible OS notification with fixed generic copy plus the
 * generic fetch trigger; high priority because it results in a visible notification and is the
 * only DMS surface (§8.9: never high priority for silent work).
 */
async function sendDmsPush({ row, installs, nowMs }) {
  const tokens = installs.map((install) => install.token);
  if (tokens.length === 0) return { providerAccepted: 0, invalidInstallations: [] };
  const { getMessaging } = require('firebase-admin/messaging');
  const result = await getMessaging().sendEachForMulticast(buildDmsMessage(row, tokens));
  const invalidInstallations = [];
  result.responses.forEach((response, index) => {
    if (!response.success && isTokenInvalid('fcm', response.error)) {
      invalidInstallations.push(installs[index].id);
    }
  });
  // `successCount` is provider ACCEPTANCE, not a delivery or display receipt (§8.12). The
  // field name is part of the contract: nothing downstream may present it as "delivered".
  return { providerAccepted: result.successCount, invalidInstallations };
}

/** Default provider call for the LATE self-reminder (one installation, one attempt). */
async function sendCheckinPush({ installation }) {
  const { getMessaging } = require('firebase-admin/messaging');
  await getMessaging().send(buildCheckinMessage(installation));
}

/** Rows stuck in DISPATCHING past their lease (a crashed invocation) return to PENDING. */
async function requeueExpiredClaims(db, nowMs) {
  const snap = await db.collection(OUTBOX)
    .where('status', '==', NOTICE_DISPATCHING)
    .limit(MAX_LEASE_RECOVERY_PER_RUN)
    .get();
  let requeued = 0;
  for (const doc of snap.docs) {
    const data = doc.data();
    if (Number(data.lease_until_ms) > nowMs) continue;
    const ok = await db.runTransaction(async (tx) => {
      const fresh = await tx.get(doc.ref);
      if (!fresh.exists) return false;
      const current = fresh.data();
      if (current.status !== NOTICE_DISPATCHING || Number(current.lease_until_ms) > nowMs) return false;
      tx.update(doc.ref, {
        status: NOTICE_PENDING,
        next_attempt_at_ms: nowMs,
        last_error_code: 'LEASE_EXPIRED',
      });
      return true;
    });
    if (ok) requeued += 1;
  }
  return requeued;
}

async function dispatchOutbox({ db, nowMs, config, sendPush }) {
  const due = await db.collection(OUTBOX)
    .where('status', '==', NOTICE_PENDING)
    .where('next_attempt_at_ms', '<=', nowMs)
    .orderBy('next_attempt_at_ms')
    .limit(config.dispatchBatch)
    .get();

  let sent = 0;
  let suppressed = 0;
  let failed = 0;
  for (const doc of due.docs) {
    const claim = await claimNotice(db, doc, nowMs);
    if (claim.suppressed) {
      suppressed += 1;
      continue;
    }
    if (!claim.send) continue;

    const row = claim.row;
    const installs = await listRecipientInstallations(db, row.recipient_uid, nowMs);
    if (installs.length === 0) {
      await finalizeNotice(db, doc.ref, claim.attempts, {
        status: NOTICE_SUPPRESSED,
        suppressed_reason: 'NO_INSTALLATION',
        updated_at_ms: nowMs,
      });
      suppressed += 1;
      continue;
    }

    try {
      const outcome = await sendPush({ row, installs, nowMs });
      const finished = await finalizeNotice(db, doc.ref, claim.attempts, {
        status: NOTICE_SENT,
        dispatched_at_ms: nowMs,
        provider_accepted: outcome.providerAccepted,
        updated_at_ms: nowMs,
      });
      if (finished) sent += 1;
      await Promise.all(
        outcome.invalidInstallations.map((id) => markInstallationStale(db, row.recipient_uid, id)),
      );
    } catch (error) {
      const retry = shouldRetryDispatch(claim.attempts);
      await finalizeNotice(db, doc.ref, claim.attempts, retry
        ? {
          status: NOTICE_PENDING,
          next_attempt_at_ms: nowMs + dispatchBackoffMs(claim.attempts),
          last_error_code: errorCodeOf(error),
        }
        : {
          status: NOTICE_FAILED_TERMINAL,
          last_error_code: errorCodeOf(error),
          updated_at_ms: nowMs,
        });
      failed += 1;
    }
  }
  return { sent, suppressed, failed };
}

/**
 * Handler seam. `sendPush` is injected by the emulator suite; production uses the FCM call.
 * One invocation = one bounded transition batch + one bounded dispatch batch. Never a scan.
 */
function createDmsWorkerHandler({
  db,
  now = () => Date.now(),
  sendPush = sendDmsPush,
  sendCheckin = sendCheckinPush,
  log = logger,
}) {
  return async function dmsWorkerHandler() {
    const nowMs = now();
    const configSnap = await db.doc(DMS_CONFIG_DOC).get();
    const config = normalizeConfig(configSnap.exists ? configSnap.data() : null);
    if (!config.enabled) {
      log.info('dms worker disabled');
      return { disabled: true };
    }
    // Step 12 operator gate (2026-10-22, ADR #207): the same switch the mailbox create rule
    // reads. An explicit false stops DMS transitions AND dispatch for this feature while
    // leaving state, outbox and pairing documents untouched. Absent doc/field = enabled.
    const featuresSnap = await db.doc('config/features').get();
    const features = featuresSnap.exists ? featuresSnap.data() : {};
    if (features.partnerAccountabilityEnabled === false) {
      log.info('dms worker disabled: partner accountability switched off');
      return { disabled: 'PARTNER_ACCOUNTABILITY' };
    }

    const openOutageId = await findOpenIngestionOutage(db);
    if (openOutageId) {
      log.warn('dms worker held: ingestion outage open', { outageId: openOutageId });
      return { held: 'INGESTION_OUTAGE' };
    }

    const due = await db.collection('dms_install_state')
      .where('next_deadline_at', '<=', Timestamp.fromMillis(nowMs))
      .orderBy('next_deadline_at')
      .limit(config.dueBatch)
      .get();

    let notified = 0;
    let deferred = 0;
    let stale = 0;
    let outboxCreated = 0;
    for (const doc of due.docs) {
      const outcome = await processDueRow(db, doc, nowMs);
      if (outcome.status === 'notified') {
        notified += 1;
        outboxCreated += outcome.outboxCreated;
      } else if (outcome.status === 'deferred') {
        deferred += 1;
      } else {
        stale += 1;
      }
    }

    // §4 LATE reminder: a separate bounded due query over the earlier threshold. This is not
    // partner fan-out — it goes to the monitored device — so it is not gated by the fleet
    // breaker below; a correlated fleet-wide LATE spike is still visible in `lateReminded`.
    const lateDue = await db.collection('dms_install_state')
      .where('late_deadline_at', '<=', Timestamp.fromMillis(nowMs))
      .orderBy('late_deadline_at')
      .limit(config.dueBatch)
      .get();
    let lateReminded = 0;
    let lateDeferred = 0;
    let lateStale = 0;
    for (const doc of lateDue.docs) {
      const outcome = await processLateRow(db, doc, nowMs, sendCheckin);
      if (outcome.status === 'reminded') lateReminded += 1;
      else if (outcome.status === 'deferred') lateDeferred += 1;
      else lateStale += 1;
    }

    let breakerOpened = false;
    if (circuitBreakerTripped(notified, config.lateBurstThreshold)) {
      breakerOpened = true;
      await db.doc(DMS_CONFIG_DOC).set({
        dispatch_hold: true,
        incident_reason: 'LATE_BURST',
        incident_count: notified,
        incident_opened_at_ms: nowMs,
      }, { merge: true });
      log.error('dms circuit breaker opened: fleet-wide late burst', {
        notified,
        threshold: config.lateBurstThreshold,
      });
    }

    const requeued = await requeueExpiredClaims(db, nowMs);
    let dispatched = { sent: 0, suppressed: 0, failed: 0 };
    const dispatchHeld = breakerOpened || config.dispatchHold;
    if (dispatchHeld) {
      log.warn('dms dispatch held', {
        reason: breakerOpened ? 'LATE_BURST' : 'OPERATOR_HOLD',
        pending: outboxCreated,
      });
    } else {
      dispatched = await dispatchOutbox({ db, nowMs, config, sendPush });
    }

    log.info('dms worker pass', {
      due: due.size,
      notified,
      deferred,
      stale,
      outboxCreated,
      lateDue: lateDue.size,
      lateReminded,
      lateDeferred,
      lateStale,
      requeued,
      dispatchHeld,
      ...dispatched,
    });
    return {
      due: due.size,
      notified,
      deferred,
      stale,
      outboxCreated,
      lateDue: lateDue.size,
      lateReminded,
      lateDeferred,
      lateStale,
      requeued,
      dispatchHeld,
      breakerOpened,
      ...dispatched,
    };
  };
}

exports.dmsDeadlineWorker = onSchedule(
  {
    region: REGION,
    schedule: '*/5 * * * *',
    timeZone: 'UTC',
    // Singleton: overlapping runs would waste transactions; CAS still makes them safe, which
    // the emulator suite proves independently of this setting.
    maxInstances: 1,
    timeoutSeconds: 300,
  },
  // Lazy `getFirestore()`: the module is required by test processes before any app exists,
  // and the default app is initialized after require in the emulator suites.
  deferWhileIndexBuilds((event) => createDmsWorkerHandler({ db: getFirestore() })(event)),
);

exports._createDmsWorkerHandler = createDmsWorkerHandler;
exports._processDueRow = processDueRow;
exports._processLateRow = processLateRow;
exports._claimNotice = claimNotice;
exports._dispatchOutbox = dispatchOutbox;
exports._requeueExpiredClaims = requeueExpiredClaims;
exports._outageEndFor = outageEndFor;
exports._buildDmsMessage = buildDmsMessage;
exports._buildCheckinMessage = buildCheckinMessage;
exports._sendCheckinPush = sendCheckinPush;
