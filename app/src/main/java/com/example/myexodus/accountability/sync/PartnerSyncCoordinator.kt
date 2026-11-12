package com.example.myexodus.accountability.sync

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import com.example.myexodus.account.SessionFence
import com.example.myexodus.account.SessionFingerprint
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.delivery.PartnerRuleChangeTransport
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.AccountabilityEventCategory
import com.example.myexodus.accountability.serialization.AccountabilityEventKind
import com.example.myexodus.accountability.serialization.AccountabilityEventWire
import com.example.myexodus.accountability.serialization.ActivityStateAfter
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.MonitoringState
import com.example.myexodus.accountability.serialization.OutboxAgeBucket
import com.example.myexodus.accountability.serialization.PlanBody
import com.example.myexodus.accountability.serialization.PlanTaskBody
import com.example.myexodus.accountability.serialization.PlanWire
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.prunePartnerActivity
import com.example.myexodus.data.db.dao.PartnerActivityDao
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.PairProfileSharing
import com.example.myexodus.data.db.entity.PairStreakSharing
import com.example.myexodus.data.db.entity.PartnerActivityOutbox
import com.example.myexodus.data.db.entity.PartnerEventJournal
import com.example.myexodus.data.db.entity.PartnerPlan
import com.example.myexodus.data.db.entity.PartnerPlanTask
import com.example.myexodus.data.db.entity.decodeMilestoneDays
import com.example.myexodus.recovery.RecoveryMirror
import com.example.myexodus.streaks.StreakTimer
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * §14 partner-sync housekeeping (blocking-core plan §6 + §14): the periodic pass that turns
 * locally-recorded attempts into delivered partner alerts. Runs on the 15-minute WorkManager
 * cadence (plan §7 Decision 7 — partner sync is explicitly a housekeeping job, never inline
 * on an enforcement path).
 *
 * Per run: create chain events for attempts not yet linked (reconcile — heals the §6
 * attempt-first/cross-DB ordering, cold signing window, or — for activity and monitoring
 * rows, whose wire ids anchor them — a crash between event creation and the link write),
 * advance the Step 11 chain-repair protocol (escalations + due SyncRequests, before the
 * drain so a fresh request ships this pass — the responder side runs inline in the pull's
 * handler), drain the outbox into the partner's mailbox, pull this device's own mailbox.
 * Steps are independent — a drain failure never skips the pull. Passes are serialized
 * ([syncMutex]); only one reconcile ever runs at a time.
 *
 * Signing is gated by the biometric auth window (NFR4, 900s): when it is cold the events stay
 * unlinked, and the pass reports [SyncReport.signingBlocked]. No prompt is ever raised from
 * background work — the foreground owns the heal (ADR #216): it raises the signing prompt and
 * re-runs the pass. Without that heal a cold window never became warm on its own, so queued
 * partner-visible rows sat undelivered indefinitely (rig-proven 2026-10-23).
 */
class PartnerSyncCoordinator(
    private val enrollmentDao: EnrollmentDao,
    private val storeFactory: AppRulesStoreFactory,
    private val creator: EventCreator,
    /** Step 7: the per-pair chain link for journaled monitoring snapshots — a journal row has
     *  already been delivered to this pair once the chain holds its event id. */
    private val chain: ChainStateStore,
    private val signer: AccountabilitySigner,
    private val resolveUids: suspend (pairId: String) -> PairUidResolver.Resolved?,
    private val drainOutbox: suspend (pairId: String, authorUid: String, partnerUid: String) -> MailboxOutboxDrainer.DrainResult,
    private val pullMailbox: suspend (pairId: String, myUid: String, messageId: String?) -> FirestoreMailboxReceiver.PullResult,
    private val repairPass: suspend (pairId: String) -> Int,
    /** Step 13: the 72h approval sweep — run before the pull so an expired request the pull
     *  is about to answer can never be decided past its deadline by the responder UI. */
    private val expireApprovals: suspend (pairId: String) -> Int = { 0 },
    /** Step 13 unpair: continue in-flight unpairs (re-delivery, owed ack, ack poll, timeout).
     *  Runs BEFORE the enrollment early-return — a shredded device has no enrollment, and its
     *  unfinished unpair is exactly what still needs this pass. True = a journal row advanced
     *  (feeds [SyncReport.unpairAdvanced] so the UI repaints without a manual reload). */
    private val resumeUnpairs: suspend () -> Boolean = { false },
    /** Step 15 (AD-12): the rotation sweep. [EventCreator] also checks before every envelope,
     *  so this covers the idle pair — a 30-day-old epoch with no new traffic still rolls. */
    private val rotateKeys: suspend (pairId: String, mySenderKeyId: String) -> Int? = { _, _ -> null },
    /** Step 18 (AD-23): read back the relay's rejection marker for sent rows. Runs BEFORE the
     *  drain so this pass's own writes are never checked against a relay that has not run. */
    private val checkRejections: suspend (pairId: String, partnerUid: String) -> Int = { _, _ -> 0 },
    /** OPD #30: drop reachability flags for pairings this device no longer holds. */
    private val pruneReachability: (activePairIds: Set<String>) -> Unit = {},
    /** OPD #30: a recorded identity rotation marks every held pairing as gone. */
    private val consumeIdentityRotation: (activePairIds: Set<String>) -> Unit = {},
    /** Step 7 WorkManager fallback (§4.7): the pass is the 15-minute cadence, so the heartbeat
     *  rides every paired run. It runs only while paired — no relationship, nothing to report —
     *  and the client enforces its own 15-minute window unless [force] is set (a server
     *  liveness probe proves the server has not accepted a beat recently, so the local window
     *  must not suppress the reply). */
    private val checkIn: suspend (Boolean) -> Unit = {},
    /** §8.6 Phase 5 recipient half: pull this installation's partner mailbox and return the
     *  events opened this pass. FCM-independent by construction — the periodic pass, the wake's
     *  sync work and the foreground refresh all reach the same code, so a device that never got
     *  a push still converges here. Account-scoped (the service installation id), so it runs
     *  once per pass next to [checkIn], not once per pair. */
    private val pullPartnerEvents: suspend () -> Int = { 0 },
    /** Step 8 (§8.10): converge this pair's explicit `DEVICE_AVAILABILITY_STATUS` choice onto
     *  the authoritative pairing document. True = the server holds it (or already did). */
    private val syncAvailabilityConsent: suspend (pairId: String, enabled: Boolean) -> Boolean =
        { _, _ -> true },
    /**
     * Account plan §7 step 10/§12.4: the identity this pass runs under. A pass started while the
     * session was valid stops before its next remote step if the fence moved (transition,
     * sign-out or a purge block) — WorkManager cancellation alone cannot stop a running pass.
     * The JVM default keeps the coordinator's own contract testable without a session.
     */
    private val fence: SessionFence = SessionFence.Unfenced,
    /** Human name for a target as this device shows it. Only v1 journal rows queued before
     *  the identity upgrade need it — v2 rows carry their label, and a rule that no longer
     *  exists keeps the contract's neutral fallback. */
    private val resolveRuleLabel: (targetType: String, target: String) -> String = { _, target -> target },
    /** OPD #43: the partner's account was deleted — erase this device's copy of the pair
     *  (journal a terminal responder row + shred). True = the pair is gone. */
    private val purgeAccountDeletedPair: suspend (pairId: String) -> Boolean = { false },
    /** §13.3 Phase 7 staged transport for journaled rule-lifecycle rows. The default keeps
     *  every production and test caller on the shipped signed-chain path until the producer
     *  is wired and its flag is on. */
    private val ruleChangeTransport: suspend (PartnerEventJournal) -> PartnerRuleChangeTransport =
        { PartnerRuleChangeTransport.LEGACY },
) {

    data class SyncReport(
        val enrolled: Boolean = false,
        val uidsResolved: Boolean = false,
        val eventsLinked: Int = 0,
        val drained: Boolean = false,
        val drainedCount: Int = 0,
        val pulledCount: Int = 0,
        /** §8.6 Phase 5 recipient half: partner events opened from this installation's mailbox
         *  this pass (delivered only — duplicates and terminal receipts are not counted). */
        val recipientEventsPulled: Int = 0,
        val repairRequestsCreated: Int = 0,
        val approvalsExpired: Int = 0,
        /** Non-null when this pass rotated the pair's session key (Step 15). */
        val rotatedKeyEpoch: Int? = null,
        /** Sent messages this pass read back as rejected by the relay (Step 18; any marker —
         *  `RATE_LIMITED` is the one the §9 error table names). */
        val rejectionsFound: Int = 0,
        /** Local partner-visible state changed outside the append path: an in-flight unpair
         *  advanced (delivery, ack, confirmation, timeout) or a verified deletion request
         *  shredded this device's pair. One-shot screens — the Partner dashboard, the unpair
         *  card — re-read on it; without it a shred on device B left B's page showing the
         *  erased data until the next manual navigation (owner-observed 2026-10-01). */
        val unpairAdvanced: Boolean = false,
        /** This pass stopped reconcile on a cold §8.1 signing window with partner-visible
         *  rows still queued (ADR #216). Only a user-authenticated prompt can warm the key,
         *  and background work may never raise one — so the foreground owns the heal: the
         *  pass reports the block and callers with an Activity prompt + re-run. Without this
         *  flag the report read `eventsLinked=0` and every caller treated the queue as
         *  drained, which is exactly how partner Activity stayed empty while rows piled up
         *  (rig-proven 2026-10-23: pendingForPair=1, drained=true, nothing sent). */
        val signingBlocked: Boolean = false,
    )

    /** Reconcile's own tally: rows linked this pass, and whether it had to stop early because
     *  the signing key refused a cold window (the stop leaves the remaining rows pending). */
    private data class ReconcileOutcome(val linked: Int, val coldWindow: Boolean)

    /** Pass-level mutual exclusion: the periodic worker, an FCM wake, a foreground sync and an
     *  activity-emitted signal can all call [syncOnce] at once, and reconcile's pending read is
     *  a snapshot — two passes that both saw the same unlinked rows would each sign a copy.
     *  Device-proven duplicate partner alerts (2026-10-22 rig): one milestone row became three
     *  chain events, one message row two. Serializing makes every later pass read post-link. */
    private val syncMutex = Mutex()

    /** [wakeMessageId] carries the FCM wake's own message id (null on periodic/deleted-messages
     *  runs): the pull handles it first so an out-of-order arrival cannot be missed by the
     *  watermark query (plan §6 step 3's expedited path).
     *
     *  §19.5 multi-pair: one device can hold several enrollments (a partner supporting several
     *  users), and every one of them syncs — the single-enrollment `firstOrNull()` this loop
     *  replaced was OPD #15's "second pairing silently half-works" gap. Per-pair steps are
     *  independent; one pair's failure never skips another's. The report aggregates the pass
     *  (counters summed, [SyncReport.uidsResolved] true only when every pair resolved).
     *
     *  Serialized by [syncMutex]: overlapping triggers queue behind the running pass instead of
     *  reconciling from the same pre-link snapshot. */
    suspend fun syncOnce(
        wakeMessageId: String? = null,
        forceHeartbeat: Boolean = false,
    ): SyncReport = syncMutex.withLock {
        syncOnceLocked(wakeMessageId, forceHeartbeat)
    }

    private suspend fun syncOnceLocked(wakeMessageId: String?, forceHeartbeat: Boolean): SyncReport {
        // Account plan §8.7: signed out, mid transition or blocked by the one-time purge means
        // no cloud work at all — local stores are untouched, so the next valid pass heals.
        val fingerprint = fence.current() ?: return SyncReport()
        val unpairAdvanced = runCatching { resumeUnpairs() }
            .onFailure { Log.w(TAG, "unpair resume failed", it) }
            .getOrDefault(false)
        val enrollments = enrollmentDao.all()
        val enrolledPairIds = enrollments.map { it.pairId }.toSet()
        // Before the early return: after the last unpair there is no enrollment left, which is
        // exactly when the orphaned rows for the gone pairs must still be pruned.
        runCatching { sweepActivity(enrolledPairIds) }
            .onFailure { Log.w(TAG, "activity sweep failed", it) }
        runCatching { pruneReachability(enrolledPairIds) }
            .onFailure { Log.w(TAG, "reachability prune failed", it) }
        // A rotated identity is a member of no pairing, so every enrollment is certainly gone —
        // marked here, not at the sign-in site, because push registration can rotate before the
        // pairs are known. Runs with no enrollments too: a marker left behind would brand a
        // pairing made after the rotation.
        runCatching { consumeIdentityRotation(enrolledPairIds) }
            .onFailure { Log.w(TAG, "identity rotation marking failed", it) }
        if (enrollments.isEmpty()) {
            // Unpaired: attempts stay local (§6 attempt log is the record) — but the unpair's
            // own tail (ack poll, confirmation) still ran above and must reach the UI.
            return SyncReport(unpairAdvanced = unpairAdvanced)
        }
        if (!fence.isCurrent(fingerprint)) {
            Log.i(TAG, "session changed at pass start — no remote work sent")
            return SyncReport(enrolled = true, unpairAdvanced = unpairAdvanced)
        }
        runCatching { checkIn(forceHeartbeat) }
            .onFailure { Log.w(TAG, "heartbeat check-in failed", it) }
        // §8.6: the account-scoped mailbox pull runs every pass whether or not any push ever
        // arrived — the periodic cadence IS the delivery mechanism for a device with denied
        // notifications, no token, or a killed app.
        val recipientEventsPulled = runCatching { pullPartnerEvents() }
            .onFailure { Log.w(TAG, "partner mailbox pull failed", it) }
            .getOrDefault(0)
        var report = SyncReport(
            enrolled = true,
            unpairAdvanced = unpairAdvanced,
            recipientEventsPulled = recipientEventsPulled,
        )
        var resolved = 0
        for (enrollment in enrollments) {
            if (!fence.isCurrent(fingerprint)) {
                Log.i(TAG, "session changed mid-pass — remaining pairs skipped")
                break
            }
            // Per-pair isolation covers THROWN failures too (e.g. an SQLite error under disk
            // pressure): the slice is skipped, every other pair still runs, the pass returns.
            val one = runCatching { syncPair(enrollment, wakeMessageId, fingerprint) }
                .onFailure { Log.w(TAG, "pair ${enrollment.pairId} slice failed", it) }
                .getOrNull() ?: continue
            resolved++
            report = report.copy(
                eventsLinked = report.eventsLinked + one.eventsLinked,
                drained = report.drained || one.drained,
                drainedCount = report.drainedCount + one.drainedCount,
                pulledCount = report.pulledCount + one.pulledCount,
                repairRequestsCreated = report.repairRequestsCreated + one.repairRequestsCreated,
                approvalsExpired = report.approvalsExpired + one.approvalsExpired,
                rotatedKeyEpoch = one.rotatedKeyEpoch ?: report.rotatedKeyEpoch,
                rejectionsFound = report.rejectionsFound + one.rejectionsFound,
                unpairAdvanced = report.unpairAdvanced || one.unpairAdvanced,
                signingBlocked = report.signingBlocked || one.signingBlocked,
            )
        }
        return report.copy(uidsResolved = resolved == enrollments.size)
    }

    /** One pair's slice of the pass; null when its uids could not be resolved (the pair is
     *  skipped, every other pair still runs). */
    private suspend fun syncPair(
        enrollment: EnrollmentRecord,
        wakeMessageId: String?,
        fingerprint: SessionFingerprint,
    ): SyncReport? {
        val resolved = resolveUids(enrollment.pairId) ?: return null
        val myUid = resolved.myUid
        val partnerUid = resolved.partnerUid

        // OPD #43: the partner deleting their account is the server's own termination event
        // (`terminationReason`), read from the document this pass already fetched. The mailbox
        // cannot carry it — the E2EE keys died with the account — so this is the deletion
        // request's delivery. Erase this device's copy and stop: every remote step below is
        // exactly what a terminated pair refuses.
        if (resolved.partnerAccountDeleted) {
            val purged = runCatching { purgeAccountDeletedPair(enrollment.pairId) }
                .onFailure { Log.w(TAG, "account-deleted purge failed", it) }
                .getOrDefault(false)
            // The pre-loop orphan sweep still saw this pair as enrolled. Its projections carry
            // the partner's content (the Home pinned card reads them), so they must fall with
            // the shred, not on the next pass (owner report 2026-11-08: the message stayed).
            if (purged) {
                runCatching { pruneActivityFor(listOf(enrollment.pairId)) }
                    .onFailure { Log.w(TAG, "purged-pair activity prune failed pairId=${enrollment.pairId}", it) }
            }
            return SyncReport(enrolled = true, uidsResolved = true, unpairAdvanced = purged)
        }

        // Step 8 (§8.10): the local availability switch is the user's intent; the server's pairing
        // document is what DMS dispatch actually checks at send time, so it converges on every
        // pass. No local row = this device never expressed a choice, and must not overwrite a
        // value another install of the same identity may hold. A failure is quiet: the previous
        // server value stands and the next pass retries — offline revocation stays pending.
        runCatching {
            val store = storeFactory.open() ?: return@runCatching
            val consent = store.pairSharingDao().profileFor(enrollment.pairId) ?: return@runCatching
            if (syncAvailabilityConsent(enrollment.pairId, consent.shareAvailability)) {
                // Step 10: the server holds this value — clear the pending marker, but only if
                // the user has not toggled again since (the CAS is on the acknowledged value).
                store.pairSharingDao().clearAvailabilityPendingIfMatches(
                    enrollment.pairId,
                    consent.shareAvailability,
                )
            } else {
                Log.w(TAG, "availability consent sync not acknowledged; retrying next pass")
            }
        }.onFailure { Log.w(TAG, "availability consent sync failed", it) }

        val rotated = runCatching { rotateKeys(enrollment.pairId, enrollment.mySenderKeyId) }
            .onFailure { Log.w(TAG, "session key rotation failed", it) }
            .getOrNull()
        if (rotated != null) Log.i(TAG, "session key rotated to epoch $rotated")

        val reconcile = reconcileEvents(
            enrollment.pairId,
            enrollment.createdAtEpochMillis,
            enrollment.mySenderKeyId,
        )

        // Step 11 before the drain: a request created now is delivered by this same pass.
        val repairs = runCatching { repairPass(enrollment.pairId) }
            .onFailure { Log.w(TAG, "repair pass failed", it) }
            .getOrDefault(0)

        val expiredApprovals = runCatching { expireApprovals(enrollment.pairId) }
            .onFailure { Log.w(TAG, "approval expiry sweep failed", it) }
            .getOrDefault(0)

        // Before the drain: rows sent by earlier passes are settled by the relay by now, and
        // this pass's own writes are not yet SENT, so they are never read back prematurely.
        val rejected = runCatching { checkRejections(enrollment.pairId, partnerUid) }
            .onFailure { Log.w(TAG, "rejection check failed", it) }
            .getOrDefault(0)

        var drained = false
        var drainedCount = 0
        // Plan §8.7: the drain is the first remote write of the pair slice; a transition that
        // started while local reconcile ran must stop here.
        if (!fence.isCurrent(fingerprint)) return null
        when (val outcome = drainOutbox(enrollment.pairId, myUid, partnerUid)) {
            is MailboxOutboxDrainer.DrainResult.Sent -> {
                drained = true
                drainedCount = outcome.eventsDelivered
                Log.i(TAG, "drain: ${outcome.eventsDelivered} event(s) delivered")
            }
            is MailboxOutboxDrainer.DrainResult.PermissionDenied -> Log.w(TAG, "drain: PERMISSION_DENIED — rows left pending, retried next run")
            MailboxOutboxDrainer.DrainResult.SessionChanged -> Log.i(TAG, "drain: session changed — remaining rows left pending")
            is MailboxOutboxDrainer.DrainResult.Failed -> Log.w(TAG, "drain failed: ${outcome.reason} (retried next run)")
        }

        if (!fence.isCurrent(fingerprint)) return null
        val pulled = runCatching { pullMailbox(enrollment.pairId, myUid, wakeMessageId) }
            .onFailure { Log.w(TAG, "mailbox pull failed", it) }
            .getOrNull()
        if (pulled != null && pulled.isNotEnrolled) Log.w(TAG, "mailbox pull: not enrolled (pair state gone?)")

        return SyncReport(
            enrolled = true,
            uidsResolved = true,
            eventsLinked = reconcile.linked,
            drained = drained,
            drainedCount = drainedCount,
            pulledCount = pulled?.appended ?: 0,
            repairRequestsCreated = repairs,
            approvalsExpired = expiredApprovals,
            rotatedKeyEpoch = rotated,
            rejectionsFound = rejected,
            // A deletion request pulled this pass shredded the pair (the responder half runs
            // in the pull path): the UI must repaint onto the termination card even though
            // pulledCount stays 0 on the deletion wake path (owner-observed 2026-10-01).
            unpairAdvanced = pulled?.deletionRequested == true,
            signingBlocked = reconcile.coldWindow,
        )
    }

    /** Unlinked rows get a chain event now — but only while the auth window is warm; a cold
     *  window stops the pass (every remaining row would fail the same way). Three queues share
     *  the pass: block/override attempts (existing), recovery sessions (W5), and partner-visible
     *  activity journal rows (partner-activity plan §7 Step 6), each linked in its own table.
     *  §19.5 multi-pair: pending is per pair (ADR #111) — own activity fans out to every
     *  enrollment, one delivery per pair, and one pair's delivery never hides the row from the
     *  rest.
     *
     *  [sinceMs] is this pair's enrollment time (owner ruling 2026-09-29): a newcomer must not
     *  inherit history that predates them.
     *
     *  v16 consent (ADR #135, owner mandate 2026-10-04): every queue is gated against THIS
     *  pair's matrix rows. Attempts need `pair_profile_sharing.share_checkins`, sessions
     *  `share_recovery` — and each queue's effective since is additionally floored at its
     *  switch's enable watermark, so an enable never delivers the while-off backlog. Activity
     *  rows are filtered in SQL by [PartnerActivityDao.pendingForPair] against the same
     *  matrix; the payload of each delivered row is then re-anchored and scrubbed per this
     *  pair (see [payloadFor]) — the pair's projection must only ever reflect what this pair
     *  consented to see.
     *
     *  Step 7 adds the fifth queue: journaled `MONITORING_STATUS` rows (plan §8.9), consumed
     *  only while this pair shares availability and floored at that switch's enable watermark.
     *  The chain itself is the link ([ChainStateStore.hasEvent]), so there is no link write —
     *  a crash between append and here is healed as a Duplicate on the next pass. */
    private suspend fun reconcileEvents(pairId: String, sinceMs: Long, senderKeyId: String): ReconcileOutcome {
        val store = storeFactory.open() ?: return ReconcileOutcome(0, coldWindow = false)
        val sharingDao = store.pairSharingDao()
        val streakConsent = sharingDao.streakForPair(pairId)
        val consentByHabit = streakConsent.associateBy { it.habitProgressId }
        val profileConsent = sharingDao.profileFor(pairId)
        val attempts = store.attemptLogDao()
        var linked = 0
        if (profileConsent?.shareCheckins == true) {
            val since = maxOf(sinceMs, profileConsent.checkinsEnabledSinceMs)
            for (attempt in attempts.pendingEventCreation(pairId, since, RECONCILE_BATCH)) {
                when (
                    val emit = emitActivity(
                        pairId,
                        "attempt ${attempt.id}",
                        eventText(attempt, store),
                        appLabel = attemptAppLabel(attempt),
                    )
                ) {
                    is Emit.Created -> { attempts.linkEvent(attempt.id, pairId, emit.sequence); linked++ }
                    Emit.Duplicate, Emit.Skip -> Unit
                    Emit.Stop -> return ReconcileOutcome(linked, coldWindow = true)
                }
            }
        }
        val sessions = store.recoverySessionDao()
        if (profileConsent?.shareRecovery == true) {
            val since = maxOf(sinceMs, profileConsent.recoveryEnabledSinceMs)
            for (session in sessions.pendingMirror(pairId, since, RECONCILE_BATCH)) {
                when (val emit = emitActivity(pairId, "session ${session.id}", RecoveryMirror.text(session.technique, session.state))) {
                    is Emit.Created -> { sessions.linkEvent(session.id, pairId, emit.sequence); linked++ }
                    Emit.Duplicate, Emit.Skip -> Unit
                    Emit.Stop -> return ReconcileOutcome(linked, coldWindow = true)
                }
            }
        }
        val activity = store.partnerActivityDao()
        // The pair's visible habits, in the app's display order (getForProfile's
        // target_identifier COLLATE NOCASE) — the re-anchor pool for profile rows.
        val visibleHabits = streakConsent
            .filter { it.shareStreak }
            .mapNotNull { store.habitProgressDao().getById(it.habitProgressId) }
            .sortedBy { it.targetIdentifier.lowercase() }
        for (row in activity.pendingForPair(pairId, sinceMs, RECONCILE_BATCH)) {
            // Crash-window heal: the row's event is already on the chain but its link write
            // never landed. Re-signing would append a SECOND copy under a new sequence, and
            // the receiver has no per-activity dedupe — it would show the alert twice.
            val chainedSequence = chain.sequenceForEvent(pairId, senderKeyId, row.eventId)
            if (chainedSequence != null) {
                activity.linkEvent(row.eventId, pairId, chainedSequence)
                linked++
                continue
            }
            val payload = payloadFor(store, row, consentByHabit[row.habitProgressId], profileConsent, visibleHabits)
            if (payload == null) {
                // Structurally undeliverable for this pair (its anchor vanished between the
                // SQL consent check and here — a mid-pass toggle). Recorded, never emitted:
                // an unlinked phantom row would otherwise stall its batch window until the
                // retention sweep. The next pass's SQL drops it before this point.
                activity.linkEvent(row.eventId, pairId, 0L)
                continue
            }
            when (val emit = emitPayload(pairId, "activity ${row.eventId}", payload)) {
                is Emit.Created -> { activity.linkEvent(row.eventId, pairId, emit.sequence); linked++ }
                Emit.Duplicate, Emit.Skip -> Unit
                Emit.Stop -> return ReconcileOutcome(linked, coldWindow = true)
            }
        }
        // Plans (ADR #193): the plan rows ARE the outbox. A pending flag marks "this pair owes
        // a snapshot"; the payload is built here from the current revision, so an edit made
        // while a previous revision was waiting coalesces into the newest one. Duplicate means
        // the identical event is already chained (a crash-after-append retry) — delivered, so
        // the flag clears too.
        val plans = store.partnerPlanDao()
        for (plan in plans.pendingPublishForPair(pairId, RECONCILE_BATCH)) {
            val payload = publishedPlanPayload(plan, plans.tasks(plan.planId))
            when (val emit = emitPayload(pairId, "plan ${plan.planId}", payload)) {
                is Emit.Created, Emit.Duplicate -> {
                    plans.clearPublishPending(plan.planId, plan.revision, plan.updatedAtMs)
                    linked++
                }
                Emit.Skip -> Unit
                Emit.Stop -> return ReconcileOutcome(linked, coldWindow = true)
            }
        }
        for (plan in plans.pendingProgressForPair(pairId, RECONCILE_BATCH)) {
            val payload = planProgressPayload(plan, plans.tasks(plan.planId))
            when (val emit = emitPayload(pairId, "plan progress ${plan.planId}", payload)) {
                is Emit.Created, Emit.Duplicate -> {
                    plans.clearProgressPending(plan.planId, plan.revision)
                    linked++
                }
                Emit.Skip -> Unit
                Emit.Stop -> return ReconcileOutcome(linked, coldWindow = true)
            }
        }
        // §8.4 rule-control queue: the rule's own assignment IS the category consent, so this
        // queue is independent of the activity consent matrix. The route pair is the SQL
        // predicate — a rule change never fans out to a pair that was not assigned.
        val journal = store.partnerEventJournalDao()
        val nowMs = System.currentTimeMillis()
        for (row in journal.dueRuleChangesForPair(pairId, sinceMs, nowMs, RECONCILE_BATCH)) {
            // Crash-window heal across both transports: an event already on the chain was
            // delivered by the legacy path before a prior pass died before deleting the row.
            if (chain.hasEvent(pairId, senderKeyId, row.eventId)) {
                journal.deleteById(row.eventId)
                continue
            }
            when (ruleChangeTransport(row)) {
                PartnerRuleChangeTransport.LEGACY -> when (val emit = emitRuleChange(pairId, senderKeyId, row)) {
                    // Once the event is on the chain, the chain and its outbox row own delivery;
                    // the journal row only feeds creation. Deleting it lets the LIMIT window
                    // advance past a backlog that exceeds one batch.
                    is Emit.Created -> { linked++; journal.deleteById(row.eventId) }
                    Emit.Duplicate -> journal.deleteById(row.eventId)
                    Emit.Skip -> Unit
                    Emit.Stop -> return ReconcileOutcome(linked, coldWindow = true)
                }
                // The signed outbox row now holds the sealed source, envelopes and stable
                // event id transactionally — it is the migration checkpoint (§13.4).
                PartnerRuleChangeTransport.SIGNED -> { linked++; journal.deleteById(row.eventId) }
                PartnerRuleChangeTransport.ACKNOWLEDGED -> journal.deleteById(row.eventId)
                PartnerRuleChangeTransport.DEFERRED -> Unit
            }
        }
        if (profileConsent?.shareAvailability == true) {
            val since = maxOf(sinceMs, profileConsent.availabilityEnabledSinceMs)
            for (row in journal.dueForDelivery(since, nowMs, RECONCILE_BATCH)) {
                val category = AccountabilityEventCategory.fromWire(row.category) ?: continue
                if (category.kind != AccountabilityEventKind.MONITORING_STATUS) continue
                when (val emit = emitMonitoring(pairId, senderKeyId, row, category)) {
                    // The chain row itself is the per-pair link (a snapshot carries no local
                    // row to write back): Duplicate means "this pair already holds the event",
                    // so it owes nothing further.
                    is Emit.Created -> linked++
                    Emit.Duplicate, Emit.Skip -> Unit
                    Emit.Stop -> return ReconcileOutcome(linked, coldWindow = true)
                }
            }
        }
        return ReconcileOutcome(linked, coldWindow = false)
    }

    /** The author's whole plan at its current revision; task ids stay stable across edits so
     *  the recipient's checks survive. */
    private fun publishedPlanPayload(
        plan: PartnerPlan,
        tasks: List<PartnerPlanTask>,
    ): EventPayload = EventPayload(
        type = PlanWire.TYPE_PLAN_PUBLISHED,
        text = "",
        protocolVersion = PlanWire.PLAN_PROTOCOL_VERSION,
        schemaVersion = PlanWire.PLAN_SCHEMA_VERSION,
        plan = PlanBody(
            planId = plan.planId,
            title = plan.title,
            revision = plan.revision,
            updatedAtMs = plan.updatedAtMs,
            tasks = tasks.map { PlanTaskBody(it.taskId, it.taskOrder, it.text) },
        ),
    )

    /** The recipient's checked set at the revision it was seen. The task list is not repeated:
     *  progress is only applied to a copy the author still holds at this exact revision. */
    private fun planProgressPayload(
        plan: PartnerPlan,
        tasks: List<PartnerPlanTask>,
    ): EventPayload = EventPayload(
        type = PlanWire.TYPE_PLAN_PROGRESS,
        text = "",
        protocolVersion = PlanWire.PLAN_PROTOCOL_VERSION,
        schemaVersion = PlanWire.PLAN_SCHEMA_VERSION,
        plan = PlanBody(
            planId = plan.planId,
            title = plan.title,
            revision = plan.revision,
            updatedAtMs = System.currentTimeMillis(),
            doneTaskIds = tasks.filter { it.done }.map { it.taskId },
        ),
    )

    /** One row's aggregate as THIS pair may see it (v16, ADR #135).
     *
     *  Habit-anchored rows carry their event-time snapshot; profile-anchored rows (coaching,
     *  message) are re-anchored to a habit this pair can actually see — the row's own anchor
     *  when visible, else the first visible one — because the receiver applies the snapshot
     *  wholesale: an aggregate from a hidden habit would leak its day count through a row
     *  that itself is consented. Null = no truthful anchor exists; the caller records the row
     *  as undeliverable for this pair — except a motivational message, which ships
     *  snapshot-less (ADR #285): its text is the payload, not the state.
     *
     *  Sub-content is scrubbed by the pair's own sub-switches: relapse count 0 and milestone
     *  list empty when the corresponding switch is off, coaching count 0 without recovery
     *  consent — the pair's projection must only ever reflect what it consented to see, on
     *  every kind (the v15 wire leaked these fields on streak rows; this closes that). */
    private suspend fun payloadFor(
        store: AppRulesStore,
        row: PartnerActivityOutbox,
        consent: PairStreakSharing?,
        profileConsent: PairProfileSharing?,
        visibleHabits: List<HabitProgress>,
    ): EventPayload? {
        // Owner ruling 2026-11-10: the check-ins revoke is a consent announcement, never
        // state — shipping it snapshot-less keeps a check-ins-only pair's day counts, which
        // they never consented to share, off the wire entirely.
        if (row.kind == EventPayload.TYPE_CHECKINS_SHARING_STOPPED) {
            return EventPayload(
                type = row.kind,
                text = row.text,
                protocolVersion = row.protocolVersion,
                schemaVersion = row.schemaVersion,
            )
        }
        val isHabitRow = row.kind in EventPayload.HABIT_ANCHORED_ACTIVITY_TYPES
        val anchor: HabitProgress?
        val base: Aggregate
        if (isHabitRow) {
            anchor = null
            base = Aggregate(
                daysFree = row.daysFree,
                relapseCount = row.relapseCount,
                milestones = decodeMilestoneDays(row.milestonesReached),
                coachingProgress = row.coachingProgress,
                habitLabel = row.habitLabel,
            )
        } else {
            val visible = row.habitProgressId?.let { id -> visibleHabits.firstOrNull { it.progressId == id } }
                ?: visibleHabits.firstOrNull()
            if (visible == null) {
                // ADR #285: nothing shared means no snapshot to apply, but a message is
                // still a message — the send time rides its own key so it can be pinned.
                return if (row.kind == EventPayload.TYPE_MOTIVATIONAL_MESSAGE) {
                    EventPayload(
                        type = row.kind,
                        text = row.text,
                        protocolVersion = row.protocolVersion,
                        schemaVersion = row.schemaVersion,
                        messageAtMs = row.occurredAtMs,
                    )
                } else {
                    null
                }
            }
            anchor = visible
            val streak = store.recoveryStreakDao().getActiveStreak(anchor.progressId)
            base = Aggregate(
                // Same displayed day count as the sender's own surfaces (ADR #128/#176).
                daysFree = streak?.let {
                    maxOf(1, (it.currentStreakElapsedMs / StreakTimer.DAY_MS).toInt())
                } ?: 0,
                relapseCount = anchor.totalRelapses,
                milestones = achievedDays(store, streak?.streakId),
                coachingProgress = row.coachingProgress,
                // Profile rows stay nameless (wire v2 contract).
                habitLabel = null,
            )
        }
        return EventPayload(
            type = row.kind,
            text = row.text,
            protocolVersion = row.protocolVersion,
            schemaVersion = row.schemaVersion,
            stateAfter = ActivityStateAfter(
                daysFree = base.daysFree,
                relapseCount = if (consent?.shareRelapses == true) base.relapseCount else 0,
                milestonesReached = if (consent?.shareMilestones == true) base.milestones else emptyList(),
                coachingProgress = if (profileConsent?.shareRecovery == true) base.coachingProgress else 0,
                eventOccurredAtMs = row.occurredAtMs,
                habitLabel = base.habitLabel,
                // Wire v3 (owner directive 2026-11-09): the stable per-streak key behind the
                // receiver's selector. Habit-anchored kinds only; profile rows stay keyless.
                habitId = if (isHabitRow) row.habitProgressId else null,
                endedDays = row.endedDays,
                milestoneDay = row.milestoneDay,
            ),
        )
    }

    private data class Aggregate(
        val daysFree: Int,
        val relapseCount: Int,
        val milestones: List<Int>,
        val coachingProgress: Int,
        val habitLabel: String?,
    )

    private suspend fun achievedDays(store: AppRulesStore, streakId: String?): List<Int> {
        if (streakId == null) return emptyList()
        return store.milestoneEventDao().getForStreak(streakId)
            .filter { it.achievedAt != null }
            .map { it.milestoneDay }
            .sorted()
    }

    /**
     * Partner-activity retention and unpair cleanup (plan §8.17 delta, Appendix A.3):
     *  - journal rows past the mailbox-TTL window are dropped in bounded batches — a state
     *    snapshot that old is superseded by any newer event, and an undelivered one has long
     *    since missed its moment;
     *  - projections/links/consent rows for pair ids no longer enrolled are deleted,
     *    self-healing after an unpair that never touched this store (no BilateralDeletion
     *    coupling). Consent rows are candidates too (v16): a pair can hold them without
     *    ever having produced a projection or link. Plans are candidates as well (ADR #193
     *    §9): a plan can be the only row a retired pair leaves behind.
     */
    private suspend fun sweepActivity(enrolledPairIds: Set<String>) {
        val store = storeFactory.open() ?: return
        val dao = store.partnerActivityDao()
        val sharingDao = store.pairSharingDao()
        val nowMs = System.currentTimeMillis()
        val cutoff = nowMs - ACTIVITY_RETENTION_MS
        repeat(SWEEP_BATCH_LIMIT) {
            if (dao.deleteJournalOlderThan(cutoff, RECONCILE_BATCH) == 0) return@repeat
        }
        // Step 7 journal + received snapshots expire on their own §8.7 clocks: a snapshot that
        // old has been superseded by any newer event (monitoring rows carry no history that
        // matters), and a received snapshot for a pair this device no longer holds is noise.
        repeat(SWEEP_BATCH_LIMIT) {
            if (store.partnerEventJournalDao().deleteExpired(nowMs) < RECONCILE_BATCH) return@repeat
        }
        val orphans = (
            dao.projectedPairIds() + dao.linkedPairIds() + sharingDao.consentPairIds() +
                store.partnerPlanDao().pairedPairIds()
            )
            .distinct()
            .filterNot { it in enrolledPairIds }
        if (orphans.isNotEmpty()) {
            Log.i(TAG, "pruning activity state for ${orphans.size} unpaired pair(s)")
            pruneActivityFor(orphans)
        }
    }

    /** Orphan sweep and account-deletion purge entry point (the latter must not wait for the
     *  next pass's sweep); the deletes live in [prunePartnerActivity]. */
    private suspend fun pruneActivityFor(pairIds: List<String>) {
        storeFactory.open()?.prunePartnerActivity(pairIds)
    }

    /**
     * Step 7 sender half: one journaled monitoring row → one E2EE `MONITORING_STATUS` envelope
     * for this pair. The allocated sequence is both the event's own `relationship_seq` and its
     * `latest_relationship_seq` (the chain head at build time), and it exists only inside the
     * ciphertext. A journal row that no longer parses against the contract is skipped — the
     * entity's constructor already rejects those, so this is belt-and-braces for a store
     * written by an older schema.
     */
    private suspend fun emitMonitoring(
        pairId: String,
        senderKeyId: String,
        row: PartnerEventJournal,
        category: AccountabilityEventCategory,
    ): Emit {
        if (chain.hasEvent(pairId, senderKeyId, row.eventId)) return Emit.Duplicate
        val state = row.monitoringState?.let(MonitoringState::fromWire) ?: return Emit.Skip
        val bucket = row.outboxAgeBucket?.let(OutboxAgeBucket::fromWire) ?: return Emit.Skip
        val capabilities = row.capabilitiesOrNull()
        val signature = try {
            signer.beginSign()
        } catch (e: Exception) {
            if (signer.isUserNotAuthenticated(e)) return Emit.Stop
            Log.w(TAG, "signing init failed", e)
            return Emit.Skip
        }
        return try {
            when (
                val result = creator.createBody(
                    pairId = pairId,
                    bodyFor = { sequence ->
                        AccountabilityEventWire.encode(
                            AccountabilityEventWire.monitoring(
                                eventId = row.eventId,
                                sequence = sequence,
                                category = category,
                                occurredAtMillis = row.occurredAtMinute * 60_000L,
                                state = state,
                                outboxAge = bucket,
                                latestSequence = sequence,
                                capabilities = capabilities,
                                schemaVersion = row.schemaVersion,
                            ),
                        )
                    },
                    signature = signature,
                    eventId = row.eventId,
                )
            ) {
                is EventCreator.CreateResult.Created -> Emit.Created(result.sequence)
                is EventCreator.CreateResult.Duplicate -> Emit.Duplicate
                is EventCreator.CreateResult.Failed -> {
                    Log.w(TAG, "monitoring event creation failed for ${row.eventId}: ${result.reason}")
                    Emit.Skip
                }
            }
        } catch (e: UserNotAuthenticatedException) {
            Emit.Stop
        }
    }

    /**
     * §8.4 rule-control sender half: one journaled rule-lifecycle row → one E2EE
     * `RULE_CHANGED` envelope for the rule's assigned pair. The row is already routed by SQL;
     * an unparseable row is skipped (the entity constructor rejects those, so this is
     * belt-and-braces for a store written by an older schema).
     */
    private suspend fun emitRuleChange(
        pairId: String,
        senderKeyId: String,
        row: PartnerEventJournal,
    ): Emit {
        if (chain.hasEvent(pairId, senderKeyId, row.eventId)) return Emit.Duplicate
        val ruleId = row.ruleId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Emit.Skip
        val change = row.ruleChange?.let(RuleChange::fromWire) ?: return Emit.Skip
        val identity = row.ruleIdentityOrNull() ?: legacyRuleIdentity(row)
        val signature = try {
            signer.beginSign()
        } catch (e: Exception) {
            if (signer.isUserNotAuthenticated(e)) return Emit.Stop
            Log.w(TAG, "signing init failed", e)
            return Emit.Skip
        }
        return try {
            when (
                val result = creator.createBody(
                    pairId = pairId,
                    bodyFor = { sequence ->
                        AccountabilityEventWire.encode(
                            AccountabilityEventWire.ruleControl(
                                eventId = row.eventId,
                                sequence = sequence,
                                ruleId = ruleId,
                                change = change,
                                occurredAtMillis = row.occurredAtMinute * 60_000L,
                                identity = identity,
                            ),
                        )
                    },
                    signature = signature,
                    eventId = row.eventId,
                )
            ) {
                is EventCreator.CreateResult.Created -> Emit.Created(result.sequence)
                is EventCreator.CreateResult.Duplicate -> Emit.Duplicate
                is EventCreator.CreateResult.Failed -> {
                    Log.w(TAG, "rule event creation failed for ${row.eventId}: ${result.reason}")
                    Emit.Skip
                }
            }
        } catch (e: UserNotAuthenticatedException) {
            Emit.Stop
        }
    }

    /** Signs and creates one `TYPE_ACTIVITY` event (attempts/sessions — the shipped shape). */
    private suspend fun emitActivity(
        pairId: String,
        label: String,
        text: String,
        appLabel: String? = null,
    ): Emit = emitPayload(
        pairId,
        label,
        EventPayload(type = EventPayload.TYPE_ACTIVITY, text = text, appLabel = appLabel),
    )

    /** The name the owner sees for the blocked app, bounded like every wire label. Null when
     *  it would add nothing (unresolvable, or the package id itself). */
    private fun attemptAppLabel(attempt: AttemptLog): String? =
        AccountabilityEventWire.sanitizeRuleLabel(resolveRuleLabel("APP", attempt.blockedTarget))
            .takeIf { it.isNotBlank() && it != attempt.blockedTarget }

    /** Signs and creates one event carrying [payload]. [Stop] is the cold-auth-window signal
     *  the caller ends the pass on; the row stays unlinked for the next run either way. */
    private suspend fun emitPayload(pairId: String, label: String, payload: EventPayload): Emit {
        val signature = try {
            signer.beginSign()
        } catch (e: Exception) {
            if (signer.isUserNotAuthenticated(e)) return Emit.Stop
            Log.w(TAG, "signing init failed", e)
            return Emit.Skip
        }
        return try {
            when (val result = creator.create(pairId, payload, signature)) {
                is EventCreator.CreateResult.Created -> Emit.Created(result.sequence)
                is EventCreator.CreateResult.Duplicate -> {
                    Log.w(TAG, "event duplicate for $label")
                    Emit.Duplicate
                }
                is EventCreator.CreateResult.Failed -> {
                    Log.w(TAG, "event creation failed for $label: ${result.reason}")
                    Emit.Skip
                }
            }
        } catch (e: UserNotAuthenticatedException) {
            Emit.Stop
        }
    }

    private sealed interface Emit {
        data class Created(val sequence: Long) : Emit

        /** Identical event already chained (retry after a crash between append and link). */
        data object Duplicate : Emit
        data object Skip : Emit
        data object Stop : Emit
    }

    /** v1 journal rows queued before the identity upgrade: the rule row still names the
     *  target, so resolve it now rather than send the broken generic copy the upgrade fixed. */
    private suspend fun legacyRuleIdentity(row: PartnerEventJournal): RuleIdentity {
        val rule = runCatching {
            storeFactory.open()?.restrictionRuleDao()?.getById(row.ruleId.orEmpty())
        }.getOrNull() ?: return RuleIdentity(label = "Restriction rule", targetType = RuleTargetType.APP)
        return RuleIdentity.of(
            targetType = rule.targetType,
            target = rule.blockedPackage,
            label = resolveRuleLabel(rule.targetType, rule.blockedPackage),
            batchName = rule.batchName,
        )
    }

    /** Attempt text: what was blocked/overridden, plus the batch when the rule belongs to one
     *  (owner mandate 2026-10-31 — the partner must know the attempt targets batch content). */
    private suspend fun eventText(attempt: AttemptLog, store: AppRulesStore): String {
        val verb = if (attempt.outcome == AttemptLog.OUTCOME_OVERRIDE) "override:" else "blocked:"
        val rule = runCatching { store.restrictionRuleDao().getById(attempt.ruleId) }.getOrNull()
        val batch = rule?.batchName?.let(AccountabilityEventWire::sanitizeRuleLabel)?.takeIf { it.isNotBlank() }
        return if (batch == null) "$verb${attempt.blockedTarget}" else "$verb${attempt.blockedTarget}|$batch"
    }

    private companion object {
        const val TAG = "MyExodusPartnerSync"
        const val RECONCILE_BATCH = 100
        /** Journal retention (FR-15 delta, Appendix A.3): same 30-day bound as the mailbox TTL. */
        const val ACTIVITY_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val SWEEP_BATCH_LIMIT = 10
    }
}

/** OPD #43: the server terminated this pair because the other member's account was deleted
 *  (the account-deletion worker's `terminationReason`). A normal unpair leaves it unset. */
internal fun isAccountDeletionTermination(status: String?, terminationReason: String?): Boolean =
    status == "terminated" && terminationReason in ACCOUNT_DELETION_REASONS

private val ACCOUNT_DELETION_REASONS = setOf("ACCOUNT_DELETED", "GUEST_ACCOUNT_DELETION")

/**
 * Resolves (myUid, partnerUid) for a pair: anonymous sign-in if needed, then the v2
 * `/pairings/{pairId}` document's `members` **array** (Step 1 rules shape — an array, unlike
 * the retired v1 `pairs` map). The rules gate the read to members; the doc is the only local
 * source of the partner's uid — it is deliberately never persisted on-device.
 *
 * Every pair read in the app starts here, so this is also the one place the OPD #30
 * identity-loss signal is observed: a server denial (and only a server denial, and only while
 * the app can still attest — see [AppCheckInstaller.canAttest]) is recorded in
 * [PairingReachabilityStore], a server answer clears it, and the dashboard renders the re-pair
 * card from it.
 */
class PairUidResolver(
    private val appContext: Context,
    private val firestore: FirebaseFirestore,
    private val reachability: PairingReachabilityStore,
    /** Release 0: creates the ACTIVE `users/{uid}` profile every protected callable requires.
     *  Best effort — a failure retries on the next `ensureUid()`. */
    private val ensureProfile: suspend () -> Unit = {},
    /**
     * Account plan §4.1/§8.7: a deliberate sign-out must not be undone by the lazy anonymous
     * mint, and no identity may be minted while a switch or the one-time purge owns the session.
     */
    private val fence: SessionFence = SessionFence.Unfenced,
) {
    /** A resolved pairing: its two uids, plus whether the server ended it because the other
     *  member deleted their account (OPD #43). */
    data class Resolved(
        val myUid: String,
        val partnerUid: String,
        val partnerAccountDeleted: Boolean,
    )

    /** Uid whose profile this process has already ensured; rotation/reset re-runs it. */
    private var profileReadyFor: String? = null

    /** The local device's uid — anonymous sign-in if needed. [resolve]'s first half, on its
     *  own for the pairing ceremony, which needs a uid before any pair id exists.
     *
     *  Single-flight ([SIGN_IN_LOCK]): on cold start several callers race here (push
     *  registration, sync, the dashboard, the ceremony) while `currentUser` is still null,
     *  and each concurrent signInAnonymously() mints a DISTINCT anonymous account —
     *  device-verified 2026-10-01, three accounts per phone within one second. The Firebase
     *  SDK's uid/token state then desyncs (the app names uid A while the wire carries uid B's
     *  token), and every identity-pinning write is denied server-side (pairing_tokens create,
     *  push registration) while reads pass — the "missing or insufficient permissions" pairing
     *  failure of that day. Serializing sign-in leaves exactly one account per install. */
    suspend fun ensureUid(): String? = SIGN_IN_LOCK.withLock {
        if (!fence.mayMintGuest()) {
            Log.w(TAG, "no uid: signed out or the session is transitioning")
            return@withLock null
        }
        AppCheckInstaller.install(appContext)
        val auth = FirebaseAuth.getInstance()
        var user = auth.currentUser ?: signIn(auth)
        // A dead refresh token leaves `currentUser` set while every authenticated call fails
        // forever — the SDK never signs itself out of that state, so nothing recovers and the
        // OPD #30 card never earns its verdict (device-proven 2026-10-23: both emulator auth
        // accounts vanished; an hour of 401s, no rotation). The probe is a cached-token read on
        // the happy path; only a definitive auth rejection rotates the identity, never a
        // network failure (ADR #125). The fresh uid is a member of no existing pairing, so the
        // rotation is recorded for [PartnerSyncCoordinator] to raise the re-pair card from —
        // certain loss, no attestation check needed.
        if (user != null && identityRejected(user)) {
            Log.w(TAG, "identity abandoned by the server — minting a new anonymous uid")
            auth.signOut()
            user = signIn(auth)
            if (user != null) reachability.markIdentityRotated(System.currentTimeMillis())
        }
        if (user == null) Log.w(TAG, "no uid — sign-in unavailable")
        user?.uid?.also { uid ->
            if (uid != profileReadyFor) {
                runCatching { ensureProfile() }
                    .onSuccess { profileReadyFor = uid }
                    .onFailure { Log.w(TAG, "profile initialization failed — will retry", it) }
            }
        }
    }

    private suspend fun signIn(auth: FirebaseAuth): FirebaseUser? = runCatching {
        auth.signInAnonymously().await()
    }
        .onFailure { Log.w(TAG, "anonymous sign-in failed", it) }
        .getOrNull()
        ?.user

    private suspend fun identityRejected(user: FirebaseUser): Boolean =
        isDeadIdentity(runCatching { user.getIdToken(false).await() }.exceptionOrNull())

    suspend fun resolve(pairId: String): Resolved? {
        val uid = ensureUid() ?: return null
        val read = runCatching {
            FirestoreSchema.pairingDocument(firestore, pairId).get().await()
        }
            .onFailure { Log.w(TAG, "pair $pairId: pairing document read failed", it) }
        val snapshot = read.getOrNull()
        // OPD #30: a definitive denial means this uid is no longer a member — the rotated-identity
        // (or evicted) pairing. Network failures must not qualify, and neither may an attestation
        // failure (same PERMISSION_DENIED, but about the build — see shouldRecordDenial).
        val cause = read.exceptionOrNull()
        if (shouldRecordDenial(cause, AppCheckInstaller.canAttest())) {
            reachability.markDenied(pairId, System.currentTimeMillis())
        }
        // The server answered, so whatever was denied before is denied no longer; a cached copy
        // is not a server answer (see shouldClearDenial).
        if (shouldClearDenial(snapshot != null, snapshot?.metadata?.isFromCache == true)) {
            reachability.markReachable(pairId)
        }
        val members = snapshot?.get("members")
        if (members == null) {
            Log.w(TAG, "pair $pairId: pairing document missing or has no members field")
            return null
        }
        val partner = (members as? List<*>)?.filterIsInstance<String>()?.firstOrNull { it != uid }
        if (partner == null) {
            Log.w(TAG, "pair $pairId: no other member (members=${(members as? List<*>)?.size ?: "not a list"})")
            return null
        }
        // A fully resolved pairing read is the server-answered "up to date" proof the dashboard
        // needs when no event has ever been received. A cache-served copy proves nothing.
        if (snapshot.metadata.isFromCache.not()) {
            reachability.markContact(pairId, System.currentTimeMillis())
        }
        return Resolved(
            myUid = uid,
            partnerUid = partner,
            partnerAccountDeleted = isAccountDeletionTermination(
                snapshot.getString("status"),
                snapshot.getString("terminationReason"),
            ),
        )
    }

    private companion object {
        const val TAG = "MyExodusPairUidResolver"

        /** Static so every resolver instance shares one lock — see [ensureUid]. */
        val SIGN_IN_LOCK = Mutex()
    }
}
