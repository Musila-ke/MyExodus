'use strict';
// Partner Pillar v2 Cloud Functions entry point (plan §8 Steps 3/5/6/18 — Step 6 and the
// Step 18 additions land in later commits; Step 3 (pairing registration) is defined here,
// Step 5 (the mailbox relay trigger) lives in ./relay.js and is re-exported below).
//
// Region invariant (AD-17a/b): the Firestore database and every function must be deployed to
// the same region. us-central1 is the agreed initial default; revisit per OQ-4 without ever
// separating the two.

const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue, Timestamp } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');

const { verifyProof } = require('./proof');
const { verifyAttestation } = require('./attestation');
const {
  IDEMPOTENCY_RETENTION_MS,
  isValidRequestId,
  isWithinRequestSizeLimit,
  requestHashOf,
  advancePairingWindow,
} = require('./pairing-guards');

initializeApp();
const db = getFirestore();

const REGION = 'us-central1';
const PAIRING_WINDOW_MS = 5 * 60 * 1000;

/**
 * AD-02: pairId = min(uidA, uidB) + "_" + max(uidA, uidB). Computed here — never trusted
 * from the client — so both devices address the same pairing document by construction.
 */
function pairIdOf(uidA, uidB) {
  const [min, max] = uidA < uidB ? [uidA, uidB] : [uidB, uidA];
  return `${min}_${max}`;
}

/**
 * §8 Step 3 / AD-02: the authoritative pairing record. Client writes are denied by rules;
 * this function is the only writer. Both devices submit a proof on enrollment completion;
 * the record flips pending → active only when both proofs are present within a 5-minute
 * window and their transcriptHash values match (the transcript binds the session id and
 * both agreement keys, so a matching transcriptHash means both devices saw the same SAS
 * transcript). Signature verification proves key possession; hardware attestation is
 * verified here as well (owner directive 2026-09-27, overrides AD#7) — see ./attestation.js
 * — in addition to the device-to-device enrollment check (§3).
 *
 * Idempotent, two layers (§9 corrected ordering): a requestId cached in `/idempotency`
 * replays its committed result before anything re-processes (retry-safe even after the
 * pairing terminates), and independently, re-submitting the same proof while active returns
 * the current state inside the transaction (heals a failed cache write).
 */
function createRegisterPairing({ attestationVerifier = verifyAttestation } = {}) {
  return onCall(
  {
    region: REGION,
    // Task #21 / AD-03: callable-level App Check enforcement. This is the money/abuse path
    // (writes the authoritative pairing record); the client SDK attaches the App Check token
    // automatically (PlayIntegrity in production, debug provider in instrumented tests).
    // Firestore-triggered background functions (relay.js) are NOT enforceable this way and
    // are not — AD-03's own reading.
    enforceAppCheck: true,
    // AD-20a: replaced with the load-derived value once OQ-1 (peak events/second) is
    // answered; until then this cap bounds cost and acts as the backpressure boundary.
    maxInstances: 10,
    // §13: explicit per-function ceiling (platform default is 60s).
    timeoutSeconds: 30,
  },
  async (request) => {
      const auth = request.auth;
      if (!auth) throw new HttpsError('unauthenticated', 'anonymous sign-in required');

      const proof = request.data;
      // §10.11/§13: bounded request payload — before the idempotency read, the rate-limit
      // transaction, or any crypto, so an oversized request costs neither.
      if (!isWithinRequestSizeLimit(proof)) {
        logger.warn('registerPairing rejected', { reason: 'REQUEST_TOO_LARGE' });
        throw new HttpsError('invalid-argument', 'REQUEST_TOO_LARGE');
      }
      // §9: requestId is both the idempotency key and a Firestore document id — its shape is
      // validated before anything touches it (a '/' would otherwise redirect the path).
      if (proof === null || typeof proof !== 'object' || !isValidRequestId(proof.requestId)) {
        throw new HttpsError('invalid-argument', 'REQUEST_ID_INVALID');
      }

      // §9 corrected ordering: the idempotency lookup precedes every other check. A replay of
      // an already-committed request returns its committed result — no re-processing, no
      // window/expiry logic — so a lost-response retry can never re-execute a state
      // transition (notably: it cannot resurrect a since-terminated pairing).
      const idempotencyRef = db.doc(`idempotency/${proof.requestId}`);
      const cached = await idempotencyRef.get();
      if (cached.exists) {
        if (cached.get('requestHash') !== requestHashOf(proof)) {
          logger.warn('registerPairing rejected', { reason: 'REQUEST_ID_REUSED' });
          throw new HttpsError('invalid-argument', 'REQUEST_ID_REUSED');
        }
        // §14 bullet 4: the idempotency cache-hit rail. Native metrics count invocations
        // (run.googleapis.com/request_count); this line is the replay numerator.
        logger.info('registerPairing idempotent replay');
        return cached.get('result');
      }

      // §10.5 pairing-path rate limit: only genuine new executions consume the per-UID
      // budget (replays returned above, so a retry storm is never locked out by its own
      // replays). Before the crypto checks so a flood costs reads, not verifications. The
      // doc id cannot collide with the relay's `/rate-limits/{pairId}` docs: a pairId is a
      // single '_' joining two Firebase uids, which never contain '_' themselves.
      const limitRef = db.doc(`rate-limits/pairing_${auth.uid}`);
      const withinLimit = await db.runTransaction(async (tx) => {
        const snap = await tx.get(limitRef);
        const decision = advancePairingWindow(snap.exists ? snap.data() : null, Date.now());
        if (!decision.allowed) return false;
        tx.set(limitRef, { window: decision.window }, { merge: false });
        return true;
      });
      if (!withinLimit) {
        logger.warn('registerPairing rejected', { reason: 'RATE_LIMITED' });
        throw new HttpsError('resource-exhausted', 'RATE_LIMITED');
      }

      const check = verifyProof(proof);
      if (!check.ok) {
        logger.warn('registerPairing rejected', { reason: check.reason });
        throw new HttpsError('invalid-argument', check.reason);
      }
      // Server-side hardware attestation (owner directive 2026-09-27, overrides AD#7): the
      // proof carries the device's attestation chain, verified here to the pinned Google
      // roots. verifyProof above proves possession of signingPublicKey; this proves that key
      // came out of a TEE/StrongBox.
      const attestation = attestationVerifier(proof);
      if (!attestation.ok) {
        logger.warn('registerPairing rejected', { reason: attestation.reason });
        throw new HttpsError('invalid-argument', attestation.reason);
      }
      if (proof.ownUid !== auth.uid) {
        throw new HttpsError('permission-denied', 'ownUid must be the authenticated user');
      }

      const pairId = pairIdOf(proof.ownUid, proof.partnerUid);
      const ref = db.doc(`pairings/${pairId}`);
      const now = Date.now();
      const entry = {
        publicKeyHash: proof.publicKeyHash,
        transcriptHash: proof.transcriptHash,
        protocolVersion: proof.protocolVersion,
        at: now,
      };

      const result = await db.runTransaction(async (tx) => {
        const snap = await tx.get(ref);
        const existing = snap.exists ? snap.data() : null;

        if (existing && existing.status === 'active') {
          const sameProof = existing.proofs && existing.proofs[auth.uid] &&
            existing.proofs[auth.uid].transcriptHash === proof.transcriptHash;
          if (!sameProof) throw new HttpsError('failed-precondition', 'PAIRING_ACTIVE');
          return { pairId, status: 'active', pairingEpoch: existing.pairingEpoch };
        }

        // A terminated record means a re-pair: pairingEpoch increments (AD-07 replay bound).
        const priorEpoch = existing && typeof existing.pairingEpoch === 'number' ? existing.pairingEpoch : 0;

        // A pending record only survives its 5-minute window; past it the attempt restarts.
        const pendingFresh = existing && existing.status === 'pending' &&
          now - (existing.pendingSince || 0) <= PAIRING_WINDOW_MS;
        const proofs = pendingFresh ? { ...(existing.proofs || {}) } : {};
        proofs[auth.uid] = entry;

        const other = proofs[proof.partnerUid];
        let status = 'pending';
        if (other) {
          if (now - other.at > PAIRING_WINDOW_MS) {
            delete proofs[proof.partnerUid]; // stale half of a previous attempt
          } else if (other.transcriptHash !== proof.transcriptHash) {
            // §14's cross-session mismatch counter is a log-based metric on this line,
            // label-extracted per submitting uid (surfaced for manual review, never an
            // auto-lock). The uid is the reporter whose proof disagrees with the stored
            // half — the pairId already implies both members, so this adds no new exposure.
            logger.warn('registerPairing rejected', { reason: 'TRANSCRIPT_MISMATCH', pairId, uid: auth.uid });
            throw new HttpsError('failed-precondition', 'TRANSCRIPT_MISMATCH');
          } else if (other.publicKeyHash === proof.publicKeyHash) {
            throw new HttpsError('failed-precondition', 'SAME_KEY');
          } else {
            status = 'active';
          }
        }

        const [uidA, uidB] = proof.ownUid < proof.partnerUid
          ? [proof.ownUid, proof.partnerUid]
          : [proof.partnerUid, proof.ownUid];

        // A retrying device refreshes the window (its proof's `at` is refreshed too), so
        // a submission counts as "arriving" on every retry — the 5-minute rule then
        // measures from each device's last attempt. Terminated records keep the old
        // pendingSince for reference; it is inert once status is active.
        const pendingSince = status === 'pending' ? now : (existing && existing.pendingSince) || now;

        const doc = {
          uidA,
          uidB,
          members: [uidA, uidB],
          status,
          pairingEpoch: priorEpoch + 1,
          proofs,
          pendingSince,
          // Every write replaces the document (`merge: false`), so the field must be
          // re-sent or it is destroyed by the write that activates the pairing — caught by
          // the emulator suite 2026-09-28. It belongs to the record, not the epoch: a
          // re-pair keeps the original creation time, `activatedAt` marks the current one.
          createdAt: (existing && existing.createdAt) || FieldValue.serverTimestamp(),
        };
        if (status === 'active') doc.activatedAt = FieldValue.serverTimestamp();

        tx.set(ref, doc, { merge: false });
        logger.info('registerPairing', { pairId, status });
        return { pairId, status, pairingEpoch: doc.pairingEpoch };
      });

      // §9 step 7: cache the committed result for replay. Best-effort by design — if this
      // write fails, a retry re-executes and the transaction's own proof-equality path still
      // returns the committed state (and a racing second write lands the same result), so a
      // failed cache write must not fail a registration that already committed.
      try {
        await idempotencyRef.set({
          functionName: 'registerPairing',
          requestHash: requestHashOf(proof),
          result,
          createdAt: Timestamp.now(),
          expiresAt: Timestamp.fromMillis(Date.now() + IDEMPOTENCY_RETENTION_MS),
        });
      } catch (e) {
        logger.warn('idempotency record write failed', { reason: e && e.message ? e.message : 'unknown' });
      }
      return result;
  },
  );
}

// Local-suite rig (2026-09-28): the two-device pairing test points both phones at the local
// Functions emulator, where a Google-rooted hardware chain cannot exist (an emulator's key is
// SOFTWARE). The stub is opt-in (ATTESTATION_STUB=1) AND requires the emulator runtime, so a
// deployed instance never takes this branch; the verifier keeps its own unit suite + real
// device leg, and what runs against the emulator here is the handler, not the verifier.
const attestationStub = process.env.FUNCTIONS_EMULATOR && process.env.ATTESTATION_STUB === '1';
exports.registerPairing = createRegisterPairing(
  attestationStub ? { attestationVerifier: () => ({ ok: true, securityLevel: 2 }) } : {},
);

// House function-seam pattern (cf. createRelayHandler/createAttemptDispatch): the emulator
// suite builds its own instance with a stubbed attestation verifier — the emulator cannot
// produce Google-rooted device chains (ADR #96), and the verifier itself has its own unit
// suite plus the real-device leg, so what stays under test here is this handler's ordering,
// transactions and idempotency against a real Firestore.
exports._createRegisterPairing = createRegisterPairing;

// Exported for the function test suite once Step 20 wires the Functions emulator.
exports._pairIdOf = pairIdOf;

// §8 Step 5 / AD-10: mailbox-create trigger → validation, pairing re-check, rate limits,
// per-installation push dispatch. Re-exported so the deploy unit sees it at the root.
const { relayMailboxMessage } = require('./relay');
exports.relayMailboxMessage = relayMailboxMessage;

// §8 Step 6 / AD-10c: every-2-minutes dispatch retry sweep (the single retry source).
const { retryDispatchSweep } = require('./sweep');
exports.retryDispatchSweep = retryDispatchSweep;

// AD-18b: daily mailbox retention job — the only deleter of mailbox documents.
// §9 corrected ordering / ADR #98: daily pairing idempotency-record retention (24h).
const { cleanupMailboxes, cleanupIdempotencyRecords, cleanupEventRateLimits } = require('./cleanup');
exports.cleanupMailboxes = cleanupMailboxes;
exports.cleanupIdempotencyRecords = cleanupIdempotencyRecords;
exports.cleanupEventRateLimits = cleanupEventRateLimits;

// V1 Req 35 / plan §8.4 Step 2: the SOS acceptance callable. The raw handler (injected db)
// is exported for the emulator suite, same seam pattern as `_createRegisterPairing`.
const { createSendNudge, createSendNudgeHandler } = require('./partner-sos');
exports.sendNudge = createSendNudge({ db });
exports._createSendNudgeHandler = createSendNudgeHandler;

// V1 Req 35 / plan §8.5-§8.10 Step 3: queue-created trigger → lease → FID multicast →
// lease-checked finalization. `_createPushNudge` is the emulator suite's seam (injected
// messaging client).
const { getMessaging } = require('firebase-admin/messaging');
const { createPushNudge, createOnNudgeQueueCreated } = require('./partner-sos-push');
const sosPushNudge = createPushNudge({ db, messaging: getMessaging() });
exports.onNudgeQueueCreated = createOnNudgeQueueCreated({ pushNudge: sosPushNudge });
exports._createPushNudge = createPushNudge;

// V1 Req 35 / plan §8.11/§6.6 Step 4: the every-5-minute repair sweep over the root queue.
// `_createResumePendingNudgesHandler` is the emulator suite's seam.
const {
  createResumePendingNudgesSchedule,
  createResumePendingNudgesHandler,
} = require('./partner-sos-scheduler');
exports.resumePendingNudges = createResumePendingNudgesSchedule({ db, pushNudge: sosPushNudge });
exports._createResumePendingNudgesHandler = createResumePendingNudgesHandler;

// Plan §7 Step 7 / §8.10: device-availability heartbeat. `_createSubmitHeartbeatHandler` is
// the emulator suite's seam (injected db + clock).
const { createSubmitHeartbeat, createSubmitHeartbeatHandler } = require('./heartbeat');
exports.submitHeartbeat = createSubmitHeartbeat({ db });
exports._createSubmitHeartbeatHandler = createSubmitHeartbeatHandler;

// Plan §7 Step 8 / §8.10-§8.11: DMS relationship contracts — explicit
// `DEVICE_AVAILABILITY_STATUS` consent, read-time status derivation, operator-recorded
// ingestion outages. `_create*Handler` are the emulator suite's seams.
const {
  setAvailabilityConsent,
  readPartnerStatus,
  recordIngestionOutage,
} = require('./dms');
exports.setAvailabilityConsent = setAvailabilityConsent;
exports.readPartnerStatus = readPartnerStatus;
exports.recordIngestionOutage = recordIngestionOutage;

// Plan §7 Step 8 / §8.11-§8.12: the every-5-minute bounded deadline processor + durable
// notification dispatch. `_createDmsWorkerHandler` is the emulator suite's seam.
const { dmsDeadlineWorker } = require('./dms-processor');
exports.dmsDeadlineWorker = dmsDeadlineWorker;

// Account plan §7 Release 0 step 2 / §8.8: `appendUserEvent` is the only user-event write
// path once the rules stop allowing direct mailbox creates. `_createAppendUserEventHandler`
// is the emulator suite's seam (injected db + clock).
const { createAppendUserEvent, createAppendUserEventHandler } = require('./event-append');
exports.appendUserEvent = createAppendUserEvent({ db });
exports._createAppendUserEventHandler = createAppendUserEventHandler;

// Account plan §7 Release 0 step 2 / §8.8-§8.10: the pair/profile callables — invitation
// register/respond, terminate (+ deterministic system event), profile initialization and
// the client compatibility read. `_create*Handler` are the emulator suite's seams.
const {
  createInitializeUserProfile,
  createCreatePairInvitation,
  createRespondToPairInvitation,
  createTerminatePair,
  createGetClientCompatibility,
  createInitializeUserProfileHandler,
  createCreatePairInvitationHandler,
  createRespondToPairInvitationHandler,
  createTerminatePairHandler,
  createGetClientCompatibilityHandler,
} = require('./pair-lifecycle');
exports.initializeUserProfile = createInitializeUserProfile({ db });
exports.createPairInvitation = createCreatePairInvitation({ db });
exports.respondToPairInvitation = createRespondToPairInvitation({ db });
exports.terminatePair = createTerminatePair({ db });
exports.getClientCompatibility = createGetClientCompatibility({ db });
exports._createInitializeUserProfileHandler = createInitializeUserProfileHandler;
exports._createCreatePairInvitationHandler = createCreatePairInvitationHandler;
exports._createRespondToPairInvitationHandler = createRespondToPairInvitationHandler;
exports._createTerminatePairHandler = createTerminatePairHandler;
exports._createGetClientCompatibilityHandler = createGetClientCompatibilityHandler;

// Account plan §7 Release 1 step 9 / §8.4 / §4.2: the guest-transition callables.
const {
  createPrepareGuestTransition,
  createFinalizeGuestTransition,
  createCancelGuestTransition,
  createPrepareGuestTransitionHandler,
  createFinalizeGuestTransitionHandler,
  createCancelGuestTransitionHandler,
} = require('./guest-transition');
exports.prepareGuestTransition = createPrepareGuestTransition({ db });
exports.finalizeGuestTransition = createFinalizeGuestTransition({ db });
exports.cancelGuestTransition = createCancelGuestTransition({ db });
exports._createPrepareGuestTransitionHandler = createPrepareGuestTransitionHandler;
exports._createFinalizeGuestTransitionHandler = createFinalizeGuestTransitionHandler;
exports._createCancelGuestTransitionHandler = createCancelGuestTransitionHandler;

// Account plan §7 Release 2 step 12 / §6.4-§6.5 / §8.5: the wrapped-ABK key-bundle callables.
const {
  createInitializeBackupKeyBundle,
  createUpdateBackupKeyBundle,
  createInitializeBackupKeyBundleHandler,
  createUpdateBackupKeyBundleHandler,
} = require('./backup-key');
exports.initializeBackupKeyBundle = createInitializeBackupKeyBundle({ db });
exports.updateBackupKeyBundle = createUpdateBackupKeyBundle({ db });
exports._createInitializeBackupKeyBundleHandler = createInitializeBackupKeyBundleHandler;
exports._createUpdateBackupKeyBundleHandler = createUpdateBackupKeyBundleHandler;
