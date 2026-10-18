package com.example.myexodus.accountability.sync

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.ActivityStateAfter
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.dao.PartnerActivityDao
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.PairProfileSharing
import com.example.myexodus.data.db.entity.PairStreakSharing
import com.example.myexodus.data.db.entity.PartnerActivityOutbox
import com.example.myexodus.data.db.entity.decodeMilestoneDays
import com.example.myexodus.recovery.RecoveryMirror
import com.example.myexodus.streaks.StreakTimer
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * §14 partner-sync housekeeping (blocking-core plan §6 + §14): the periodic pass that turns
 * locally-recorded attempts into delivered partner alerts. Runs on the 15-minute WorkManager
 * cadence (plan §7 Decision 7 — partner sync is explicitly a housekeeping job, never inline
 * on an enforcement path).
 *
 * Per run: create chain events for attempts not yet linked (reconcile — heals the §6
 * attempt-first/cross-DB ordering, cold signing window, or a crash between event creation
 * and the link write), advance the Step 11 chain-repair protocol (escalations + due
 * SyncRequests, before the drain so a fresh request ships this pass — the responder side
 * runs inline in the pull's handler), drain the outbox into the partner's mailbox, pull
 * this device's own mailbox. Steps are independent — a drain failure never skips the pull.
 *
 * Signing is best-effort: the biometric auth window (NFR4, 900s) gates [AccountabilitySigner];
 * when it is cold the events stay unlinked and the next run with a warm window delivers them.
 * No biometric prompt is ever shown from background work.
 */
class PartnerSyncCoordinator(
    private val enrollmentDao: EnrollmentDao,
    private val storeFactory: AppRulesStoreFactory,
    private val creator: EventCreator,
    private val signer: AccountabilitySigner,
    private val resolveUids: suspend (pairId: String) -> Pair<String, String>?,
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
) {

    data class SyncReport(
        val enrolled: Boolean = false,
        val uidsResolved: Boolean = false,
        val eventsLinked: Int = 0,
        val drained: Boolean = false,
        val drainedCount: Int = 0,
        val pulledCount: Int = 0,
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
    )

    /** [wakeMessageId] carries the FCM wake's own message id (null on periodic/deleted-messages
     *  runs): the pull handles it first so an out-of-order arrival cannot be missed by the
     *  watermark query (plan §6 step 3's expedited path).
     *
     *  §19.5 multi-pair: one device can hold several enrollments (a partner supporting several
     *  users), and every one of them syncs — the single-enrollment `firstOrNull()` this loop
     *  replaced was OPD #15's "second pairing silently half-works" gap. Per-pair steps are
     *  independent; one pair's failure never skips another's. The report aggregates the pass
     *  (counters summed, [SyncReport.uidsResolved] true only when every pair resolved). */
    suspend fun syncOnce(wakeMessageId: String? = null): SyncReport {
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
        if (enrollments.isEmpty()) {
            // Unpaired: attempts stay local (§6 attempt log is the record) — but the unpair's
            // own tail (ack poll, confirmation) still ran above and must reach the UI.
            return SyncReport(unpairAdvanced = unpairAdvanced)
        }
        var report = SyncReport(enrolled = true, unpairAdvanced = unpairAdvanced)
        var resolved = 0
        for (enrollment in enrollments) {
            // Per-pair isolation covers THROWN failures too (e.g. an SQLite error under disk
            // pressure): the slice is skipped, every other pair still runs, the pass returns.
            val one = runCatching { syncPair(enrollment, wakeMessageId) }
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
            )
        }
        return report.copy(uidsResolved = resolved == enrollments.size)
    }

    /** One pair's slice of the pass; null when its uids could not be resolved (the pair is
     *  skipped, every other pair still runs). */
    private suspend fun syncPair(enrollment: EnrollmentRecord, wakeMessageId: String?): SyncReport? {
        val uids = resolveUids(enrollment.pairId) ?: return null
        val (myUid, partnerUid) = uids

        val rotated = runCatching { rotateKeys(enrollment.pairId, enrollment.mySenderKeyId) }
            .onFailure { Log.w(TAG, "session key rotation failed", it) }
            .getOrNull()
        if (rotated != null) Log.i(TAG, "session key rotated to epoch $rotated")

        val linked = reconcileEvents(enrollment.pairId, enrollment.createdAtEpochMillis)

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
        when (val outcome = drainOutbox(enrollment.pairId, myUid, partnerUid)) {
            is MailboxOutboxDrainer.DrainResult.Sent -> {
                drained = true
                drainedCount = outcome.eventsDelivered
                Log.i(TAG, "drain: ${outcome.eventsDelivered} event(s) delivered")
            }
            is MailboxOutboxDrainer.DrainResult.PermissionDenied -> Log.w(TAG, "drain: PERMISSION_DENIED — rows left pending, retried next run")
            is MailboxOutboxDrainer.DrainResult.Failed -> Log.w(TAG, "drain failed: ${outcome.reason} (retried next run)")
        }

        val pulled = runCatching { pullMailbox(enrollment.pairId, myUid, wakeMessageId) }
            .onFailure { Log.w(TAG, "mailbox pull failed", it) }
            .getOrNull()
        if (pulled != null && pulled.isNotEnrolled) Log.w(TAG, "mailbox pull: not enrolled (pair state gone?)")

        return SyncReport(
            enrolled = true,
            uidsResolved = true,
            eventsLinked = linked,
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
     *  consented to see. */
    private suspend fun reconcileEvents(pairId: String, sinceMs: Long): Int {
        val store = storeFactory.open() ?: return 0
        val sharingDao = store.pairSharingDao()
        val streakConsent = sharingDao.streakForPair(pairId)
        val consentByHabit = streakConsent.associateBy { it.habitProgressId }
        val profileConsent = sharingDao.profileFor(pairId)
        val attempts = store.attemptLogDao()
        var linked = 0
        if (profileConsent?.shareCheckins == true) {
            val since = maxOf(sinceMs, profileConsent.checkinsEnabledSinceMs)
            for (attempt in attempts.pendingEventCreation(pairId, since, RECONCILE_BATCH)) {
                when (val emit = emitActivity(pairId, "attempt ${attempt.id}", eventText(attempt))) {
                    is Emit.Created -> { attempts.linkEvent(attempt.id, pairId, emit.sequence); linked++ }
                    Emit.Skip -> Unit
                    Emit.Stop -> return linked
                }
            }
        }
        val sessions = store.recoverySessionDao()
        if (profileConsent?.shareRecovery == true) {
            val since = maxOf(sinceMs, profileConsent.recoveryEnabledSinceMs)
            for (session in sessions.pendingMirror(pairId, since, RECONCILE_BATCH)) {
                when (val emit = emitActivity(pairId, "session ${session.id}", RecoveryMirror.text(session.technique, session.state))) {
                    is Emit.Created -> { sessions.linkEvent(session.id, pairId, emit.sequence); linked++ }
                    Emit.Skip -> Unit
                    Emit.Stop -> return linked
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
                Emit.Skip -> Unit
                Emit.Stop -> return linked
            }
        }
        return linked
    }

    /** One row's aggregate as THIS pair may see it (v16, ADR #135).
     *
     *  Habit-anchored rows carry their event-time snapshot; profile-anchored rows (coaching,
     *  message) are re-anchored to a habit this pair can actually see — the row's own anchor
     *  when visible, else the first visible one — because the receiver applies the snapshot
     *  wholesale: an aggregate from a hidden habit would leak its day count through a row
     *  that itself is consented. Null = no truthful anchor exists; the caller records the row
     *  as undeliverable for this pair.
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
            anchor = row.habitProgressId?.let { id -> visibleHabits.firstOrNull { it.progressId == id } }
                ?: visibleHabits.firstOrNull()
                ?: return null
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
     *    ever having produced a projection or link.
     */
    private suspend fun sweepActivity(enrolledPairIds: Set<String>) {
        val store = storeFactory.open() ?: return
        val dao = store.partnerActivityDao()
        val sharingDao = store.pairSharingDao()
        val cutoff = System.currentTimeMillis() - ACTIVITY_RETENTION_MS
        repeat(SWEEP_BATCH_LIMIT) {
            if (dao.deleteJournalOlderThan(cutoff, RECONCILE_BATCH) == 0) return@repeat
        }
        val orphans = (dao.projectedPairIds() + dao.linkedPairIds() + sharingDao.consentPairIds())
            .distinct()
            .filterNot { it in enrolledPairIds }
        if (orphans.isNotEmpty()) {
            Log.i(TAG, "pruning activity state for ${orphans.size} unpaired pair(s)")
            dao.deleteLinksFor(orphans)
            dao.deleteProjectionsFor(orphans)
            sharingDao.deleteStreakFor(orphans)
            sharingDao.deleteProfileFor(orphans)
        }
    }

    /** Signs and creates one `TYPE_ACTIVITY` event (attempts/sessions — the shipped shape). */
    private suspend fun emitActivity(pairId: String, label: String, text: String): Emit =
        emitPayload(pairId, label, EventPayload(type = EventPayload.TYPE_ACTIVITY, text = text))

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
                    Log.w(TAG, "event duplicate for $label — left unlinked")
                    Emit.Skip
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
        data object Skip : Emit
        data object Stop : Emit
    }

    private fun eventText(attempt: AttemptLog): String = when (attempt.outcome) {
        AttemptLog.OUTCOME_OVERRIDE -> "override:${attempt.blockedTarget}"
        else -> "blocked:${attempt.blockedTarget}"
    }

    private companion object {
        const val TAG = "MyExodusPartnerSync"
        const val RECONCILE_BATCH = 100
        /** Journal retention (FR-15 delta, Appendix A.3): same 30-day bound as the mailbox TTL. */
        const val ACTIVITY_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val SWEEP_BATCH_LIMIT = 10
    }
}

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
) {
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
        AppCheckInstaller.install(appContext)
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
            ?: runCatching { auth.signInAnonymously().await() }
                .onFailure { Log.w(TAG, "anonymous sign-in failed", it) }
                .getOrNull()
                ?.user
        if (user == null) Log.w(TAG, "no uid — sign-in unavailable")
        user?.uid
    }

    suspend fun resolve(pairId: String): Pair<String, String>? {
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
        return uid to partner
    }

    private companion object {
        const val TAG = "MyExodusPairUidResolver"

        /** Static so every resolver instance shares one lock — see [ensureUid]. */
        val SIGN_IN_LOCK = Mutex()
    }
}
