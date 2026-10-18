package com.example.myexodus.streaks

import androidx.room3.withWriteTransaction
import com.example.myexodus.accountability.activity.PartnerActivityJournal
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.PairProfileSharing
import com.example.myexodus.data.db.entity.PairStreakSharing
import com.example.myexodus.data.db.entity.RecoveryStreak
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single writer for habit/streak state (habit-streaks plan §7.5, §8 Step 3): onboarding,
 * relapse rollover, on-read evaluation persistence, milestone achievement stamping, and the
 * [state] flow the UI consumes read-only. All multi-row writes run in one
 * `withWriteTransaction` so no partial state is ever visible (§6.5); evaluation is serialized
 * through a mutex (§6.1 — evaluations are rare and cheap, so one mutex covers every habit)
 * and every anchor write additionally compare-and-updates on `row_version` as the §8 Step 2.7
 * defense in depth.
 *
 * Multi-habit (V1 follow-up): [state] is always the *displayed* habit — the profile's
 * `is_selected` row ([selectHabit] changes it, [onboard] selects the new habit, and
 * [refreshActive] falls back to the first habit when nothing is flagged). Evaluation of a
 * non-displayed habit (an alarm firing for it) persists and reconciles but never swaps what
 * the UI shows.
 *
 * The store is opened per operation via [AppRulesStoreFactory] (house background-component
 * pattern): when the DB is in a defined recovery state the write is refused with a defined
 * result rather than crashing, and alarm receivers therefore cannot take the app down.
 *
 * Every mutation that changes which alarms should exist (onboarding, relapse, evaluation,
 * selection) ends with [MilestoneScheduler.reconcile] — the §8 Step 4 single reconciliation
 * point, which recomputes the alarm set from durable state and never assumes a prior call
 * succeeded. This also makes the app-foreground evaluation the backstop for a missed
 * `BOOT_COMPLETED`.
 *
 * Deviations/decisions recorded here:
 * - A backdated `recoveryStartAt` sets only the streak's start label; the accumulator still
 *   starts at 0 (§6.2), so backdating cannot mint streak days or milestones.
 * - The first anchor is taken from [StreakTimer.firstAnchor]; an implausible device clock
 *   rejects the whole onboarding write (§6.5 rolls it back) instead of persisting a bad anchor.
 * - Milestone stamping also runs on the held (backward-clock) evaluation path: the accumulator
 *   is unchanged there, but a milestone crossed by durable state (e.g. a fresh install
 *   upgrading mid-streak) must still surface its splash card.
 * - Partner-visible activity (partner-activity plan Appendix A.3) is journalled inside these
 *   same transactions via [PartnerActivityJournal]: STREAK_UPDATED on a day crossing only,
 *   MILESTONE_REACHED per newly stamped day (carrying the day reached), RELAPSE_RECORDED
 *   after the rollover (carrying the ended streak's ordinal day-count). Consent flags and
 *   the absence of fan-out here are the emitter's contract, not this class's.
 */

/** One row of the habit switcher. */
data class HabitSummary(val habitId: String, val label: String, val isSelected: Boolean)

/** One pair's three consent switches for one habit (v16/ADR #135). */
data class StreakSharingFlags(
    val shareStreak: Boolean,
    val shareRelapses: Boolean,
    val shareMilestones: Boolean,
) {
    companion object {
        /** Absence of a consent row: nothing shared ("nothing until you choose"). */
        val None = StreakSharingFlags(shareStreak = false, shareRelapses = false, shareMilestones = false)
    }
}

/** One habit with every partner's flags; a pair missing from [perPair] shares nothing. */
data class HabitSharingItem(
    val progressId: String,
    val label: String,
    val perPair: Map<String, StreakSharingFlags>,
)

/** One pair's profile-wide categories (blocked-app check-ins, recovery practice). */
data class ProfileSharingFlags(
    val shareCheckIns: Boolean,
    val shareRecovery: Boolean,
) {
    companion object {
        val None = ProfileSharingFlags(shareCheckIns = false, shareRecovery = false)
    }
}

/** The whole consent matrix as the sharing surface renders it (v16/ADR #135). */
data class SharingMatrix(
    val habits: List<HabitSharingItem>,
    val profile: Map<String, ProfileSharingFlags>,
)

class HabitRepository(
    private val storeFactory: AppRulesStoreFactory,
    private val anomalyLog: StreakAnomalyLog,
    private val readings: () -> TimeReadings,
    private val scheduler: MilestoneScheduler,
) {

    private val evaluationMutex = Mutex()

    private val _state = MutableStateFlow<HabitState?>(null)

    /** Last persisted snapshot; null until a habit is onboarded or evaluated. */
    val state: StateFlow<HabitState?> = _state.asStateFlow()

    private val _resolved = MutableStateFlow(false)

    /**
     * True once [state] carries a definite answer — a habit's state, or "none is set up".
     * Distinct from `state == null`, which also means "the store has not been read yet"
     * (pre-unlock process start, first tick). The widget's writer needs the distinction so a
     * not-yet-read null can never overwrite a known streak ("No streak yet" flicker).
     */
    val resolved: StateFlow<Boolean> = _resolved.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)

    /**
     * True when the last read could not open the store (a defined recovery state, per
     * [AppRulesStoreFactory]). Without it a failed read is indistinguishable from "still
     * loading" — [resolved] never flips and the UI sits on its skeleton forever. Cleared by
     * any definite read: every [publishState] proves the store opened.
     */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** The only way [state] changes: every definite read marks the repository resolved. */
    private fun publishState(state: HabitState?) {
        _state.value = state
        _resolved.value = true
        _loadFailed.value = false
    }

    sealed interface OnboardingResult {
        data class Ready(val state: HabitState) : OnboardingResult

        /** §8 Step 3 input constraints — refused before anything was written. */
        data class Rejected(val reason: Reason) : OnboardingResult {
            enum class Reason {
                /** `recovery_start_at` later than the current wall clock at entry. */
                START_IN_FUTURE,
                INVALID_DURATION,

                /** Device clock outside the app's plausible lifetime (§8 Step 2 first-anchor
                 *  bounds) — deferred; the user fixes the clock and submits again. */
                IMPLAUSIBLE_CLOCK,

                /** DB in a defined recovery state (corruption/key unavailable): nothing was
                 *  written; the existing recovery UI surface explains the state. */
                STORE_UNAVAILABLE,
            }
        }
    }

    sealed interface RelapseResult {
        data class RolledOver(val state: HabitState) : RelapseResult

        /** §8 Step 2.5: a relapse earlier than the active streak's start would create a
         *  negative-duration streak — rejected as invalid input, nothing written. */
        data object RejectedBeforeStreakStart : RelapseResult

        /** §9 defense in depth against duplicate taps: a second write inside
         *  [IDEMPOTENCY_WINDOW_MS] is ignored (the UI debounce is the first line). */
        data object DuplicateIgnored : RelapseResult

        data object NoActiveStreak : RelapseResult

        /** DB in a defined recovery state: the relapse was not written. */
        data object StoreUnavailable : RelapseResult
    }

    /**
     * §6 onboarding: validates the `recovery_start_at` constraints, then writes the habit
     * row, the first streak with its anchor (`current_streak_elapsed_ms = 0`), and the
     * pre-created 7/30/90 milestone rows in one transaction. The "not earlier than any
     * previously logged relapse" constraint is structural here — a brand-new habit has no
     * relapses, and [logRelapse] keeps every relapse ≥ its streak's start.
     */
    suspend fun onboard(
        profileId: String,
        targetIdentifier: String,
        habitDurationYears: Int,
        severity: WithdrawalSeverity,
        recoveryStartAt: Long,
    ): OnboardingResult {
        if (habitDurationYears < 0) {
            return OnboardingResult.Rejected(OnboardingResult.Rejected.Reason.INVALID_DURATION)
        }
        val readings = readings()
        if (recoveryStartAt > readings.tWallMillis) {
            return OnboardingResult.Rejected(OnboardingResult.Rejected.Reason.START_IN_FUTURE)
        }
        val db = storeFactory.open()
            ?: return OnboardingResult.Rejected(OnboardingResult.Rejected.Reason.STORE_UNAVAILABLE)
        val anchor = when (val first = StreakTimer.firstAnchor(readings)) {
            is FirstAnchor.Implausible -> {
                anomalyLog.append(first.anomaly)
                return OnboardingResult.Rejected(OnboardingResult.Rejected.Reason.IMPLAUSIBLE_CLOCK)
            }

            is FirstAnchor.Anchored -> first
        }

        val habitId = UUID.randomUUID().toString()
        val streakId = UUID.randomUUID().toString()
        // Under the evaluation mutex: the selection flag is written here and read by
        // [refreshActive], and flag writes must be serialized with flag reads so a
        // concurrently-running refresh can never emit a stale displayed habit last.
        val state = requireNotNull(
            evaluationMutex.withLock {
                db.withWriteTransaction<HabitState?> {
                    // The habit the user just created is what they should see (multi-habit);
                    // the clear+insert pair keeps "one selected row per profile" exact.
                    db.habitProgressDao().clearSelection(profileId)
                    db.habitProgressDao().insert(
                        HabitProgress(
                            progressId = habitId,
                            profileId = profileId,
                            targetIdentifier = targetIdentifier,
                            currentCleanDaysStreak = 0,
                            totalRelapses = 0,
                            accessAttemptsCount = 0,
                            habitDurationYears = habitDurationYears,
                            onboardingSeverityLevel = severity.name,
                            lastRelapseTimestamp = null,
                            // v16/ADR #135: a new habit is shared with nobody — consent is
                            // per (pair, habit) and lives in pair_streak_sharing; the sharing
                            // surface is where partners are chosen ("nothing until you choose").
                            assignedPartnershipId = null,
                            isSelected = true,
                        ),
                    )
                    db.recoveryStreakDao().insert(
                        RecoveryStreak(
                            streakId = streakId,
                            habitId = habitId,
                            recoveryStartAt = recoveryStartAt,
                            tWallLastObserved = anchor.tWallMillis,
                            rBootLastObserved = anchor.rBootMillis,
                            bootCountLastObserved = anchor.bootCount,
                            currentStreakElapsedMs = 0,
                        ),
                    )
                    db.milestoneEventDao().insertAll(milestoneRows(streakId))
                    loadState(db, habitId)
                }
            },
        )
        publishState(state)
        scheduler.reconcile(habitId)
        return OnboardingResult.Ready(state)
    }

    /**
     * §6 relapse logging: rolls the active streak over in one transaction — stamp the old
     * streak ended, raise the all-time record, bump the habit counters, start the new streak
     * (accumulator back to 0) and pre-create its milestone rows. [backdatedRelapseAt] is the
     * user-entered time when the relapse is logged retrospectively; it defaults to now.
     */
    suspend fun logRelapse(habitId: String, backdatedRelapseAt: Long? = null): RelapseResult {
        val db = storeFactory.open() ?: return RelapseResult.StoreUnavailable
        val result = evaluationMutex.withLock {
            // §6.2 rolls the persisted accumulator into the record; evaluate first so it
            // reflects the freshest observed time, not the last foreground.
            evaluateLocked(db, habitId, forceDisplay = true)
            db.withWriteTransaction { relapseOnce(db, habitId, backdatedRelapseAt) }
        }
        if (result is RelapseResult.RolledOver) publishState(result.state)
        scheduler.reconcile(habitId)
        return result
    }

    /**
     * §6 on-read evaluation entry for the UI (plan §8 Step 5): resolve the displayed habit
     * (selected, else first in display order — deterministic, unlike the old unflagged
     * `LIMIT 1`), then evaluate it. Triggered from the ViewModel's `init` and the foreground
     * lifecycle callback — never by composition (Decision 9). Emits and returns null when no
     * habit has been set up yet. The selection flag is read under the evaluation mutex so a
     * concurrent [selectHabit]/[onboard] (notification deep link racing a cold-start refresh)
     * cannot have its write overtaken by this read.
     */
    suspend fun refreshActive(): HabitState? {
        val db = storeFactory.open() ?: run {
            _loadFailed.value = true
            return _state.value
        }
        val profile = db.userProfileDao().getActive() ?: run {
            publishState(null)
            return null
        }
        val state = evaluationMutex.withLock {
            val habit = db.habitProgressDao().getSelected(profile.userId)
                ?: db.habitProgressDao().getFirstForProfile(profile.userId)
            if (habit == null) {
                publishState(null)
                null
            } else {
                evaluateLocked(db, habit.progressId, forceDisplay = true)
            }
        }
        state?.let { scheduler.reconcile(it.habitId) }
        return state
    }

    /** All habits of the active profile, display-ordered — the switcher's read model. */
    suspend fun habits(): List<HabitSummary> {
        val db = storeFactory.open() ?: return emptyList()
        val profile = db.userProfileDao().getActive() ?: return emptyList()
        return db.habitProgressDao().getForProfile(profile.userId)
            .map { HabitSummary(it.progressId, it.targetIdentifier, it.isSelected) }
    }

    /** The sharing surface's read model (v16/ADR #135): every habit with every pair's flags
     *  plus the pair-level profile categories. Null = the store refused to open — the pane
     *  says so rather than claiming "no streaks"; an empty habit list is a real answer. */
    suspend fun sharingMatrix(): SharingMatrix? {
        val db = storeFactory.open() ?: return null
        val profile = db.userProfileDao().getActive() ?: return SharingMatrix(emptyList(), emptyMap())
        val streakRows = db.pairSharingDao().streakAll()
        val habits = db.habitProgressDao().getForProfile(profile.userId).map { habit ->
            HabitSharingItem(
                progressId = habit.progressId,
                label = habit.targetIdentifier,
                perPair = streakRows
                    .filter { it.habitProgressId == habit.progressId }
                    .associate {
                        it.pairId to StreakSharingFlags(
                            shareStreak = it.shareStreak,
                            shareRelapses = it.shareRelapses,
                            shareMilestones = it.shareMilestones,
                        )
                    },
            )
        }
        val profileFlags = db.pairSharingDao().profileAll()
            .associate { it.pairId to ProfileSharingFlags(it.shareCheckins, it.shareRecovery) }
        return SharingMatrix(habits = habits, profile = profileFlags)
    }

    /** Owner ask 2026-10-04 ("you have added a partner sure but you haven't shared anything
     *  with them yet"): true when nothing is being shared with [pairId] — no streak master on
     *  and no profile category on. Null = the store refused to open (unknown, never a false
     *  "nothing shared"). */
    suspend fun sharesNothingWith(pairId: String): Boolean? =
        sharingMatrix()?.let { matrix ->
            matrix.habits.none { it.perPair[pairId]?.shareStreak == true } &&
                matrix.profile[pairId]?.let { it.shareCheckIns || it.shareRecovery } != true
        }

    /**
     * Full-state write of one (pair, habit) streak consent; false = store unavailable or the
     * habit is gone (the pane reverts its optimistic flip). Each switch's false→true
     * transition bumps its enable watermark — the reconcile filter then delivers only rows
     * written at/after it, so enabling never leaks the while-off backlog. A streak-master
     * rise additionally journals one targeted snapshot now (the pair just started seeing this
     * habit; waiting for the next real transition would leave their dashboard on "waiting").
     */
    suspend fun setPairStreakSharing(
        pairId: String,
        habitProgressId: String,
        shareStreak: Boolean,
        shareRelapses: Boolean,
        shareMilestones: Boolean,
    ): Boolean {
        val db = storeFactory.open() ?: return false
        val habit = db.habitProgressDao().getById(habitProgressId) ?: return false
        val now = readings().tWallMillis
        return runCatching {
            db.withWriteTransaction<Unit> {
                val before = db.pairSharingDao().streakFor(pairId, habitProgressId)
                db.pairSharingDao().upsertStreak(
                    PairStreakSharing(
                        pairId = pairId,
                        habitProgressId = habitProgressId,
                        shareStreak = shareStreak,
                        shareRelapses = shareRelapses,
                        shareMilestones = shareMilestones,
                        streakEnabledSinceMs = watermark(before?.streakEnabledSinceMs, before?.shareStreak, shareStreak, now),
                        relapseEnabledSinceMs = watermark(before?.relapseEnabledSinceMs, before?.shareRelapses, shareRelapses, now),
                        milestoneEnabledSinceMs = watermark(before?.milestoneEnabledSinceMs, before?.shareMilestones, shareMilestones, now),
                    ),
                )
                if (shareStreak && before?.shareStreak != true) {
                    PartnerActivityJournal.emitForHabit(
                        db, habit.progressId, EventPayload.TYPE_STREAK_UPDATED, now,
                        targetPairId = pairId,
                    )
                }
            }
        }.isSuccess
    }

    /** Full-state write of one pair's profile-level categories; watermark semantics match
     *  [setPairStreakSharing]. No snapshot: attempt and session rows are announcements, not
     *  state — there is no current-state catch-up to send. */
    suspend fun setPairProfileSharing(
        pairId: String,
        shareCheckIns: Boolean,
        shareRecovery: Boolean,
    ): Boolean {
        val db = storeFactory.open() ?: return false
        val now = readings().tWallMillis
        return runCatching {
            db.withWriteTransaction<Unit> {
                val before = db.pairSharingDao().profileFor(pairId)
                db.pairSharingDao().upsertProfile(
                    PairProfileSharing(
                        pairId = pairId,
                        shareCheckins = shareCheckIns,
                        shareRecovery = shareRecovery,
                        checkinsEnabledSinceMs = watermark(before?.checkinsEnabledSinceMs, before?.shareCheckins, shareCheckIns, now),
                        recoveryEnabledSinceMs = watermark(before?.recoveryEnabledSinceMs, before?.shareRecovery, shareRecovery, now),
                    ),
                )
            }
        }.isSuccess
    }

    /** The enable watermark: pinned at the false→true transition, kept otherwise (the value is
     *  only ever read while the switch is on). */
    private fun watermark(existing: Long?, was: Boolean?, now: Boolean, at: Long): Long =
        if (now && was != true) at else (existing ?: 0L)

    /**
     * Multi-habit switch: make [habitId] the displayed habit (one transaction), then evaluate
     * it so the UI shows a fresh accumulator rather than the last-persisted one. Selection
     * survives process death because it is a row flag, not memory. The flag write runs under
     * the evaluation mutex — see [refreshActive].
     */
    suspend fun selectHabit(habitId: String): HabitState? {
        val db = storeFactory.open() ?: return _state.value
        val state = evaluationMutex.withLock {
            val habit = db.habitProgressDao().getById(habitId) ?: return@withLock null
            db.withWriteTransaction<Unit> {
                db.habitProgressDao().clearSelection(habit.profileId)
                db.habitProgressDao().markSelected(habitId)
            }
            evaluateLocked(db, habitId, forceDisplay = true)
        } ?: return _state.value
        scheduler.reconcile(habitId)
        return state
    }

    /**
     * §6 on-read evaluation under the mutex: advance [habitId]'s accumulator from live clocks
     * and persist anchor + record in one transaction. Triggered by app foreground/ViewModel/
     * alarm receivers (Step 4/5), never by recomposition. Ends with the §8 Step 4
     * reconciliation — the app-foreground alarm backstop. The displayed state is only
     * refreshed when [habitId] IS the displayed habit (multi-habit: a background habit's
     * milestone alarm must not swap what the UI shows).
     */
    suspend fun evaluate(habitId: String): HabitState? {
        val db = storeFactory.open() ?: return _state.value
        val state = evaluationMutex.withLock { evaluateLocked(db, habitId, forceDisplay = false) }
        scheduler.reconcile(habitId)
        return state
    }

    /**
     * §8 Step 4 splash-card dismissal. Keyed by (habit, day) and resolved to the active
     * streak inside the transaction — a dismissal that races a rollover acks the new streak's
     * same-day row, which the DAO guard (`achieved_at IS NOT NULL`) leaves untouched.
     */
    suspend fun acknowledgeMilestone(habitId: String, milestoneDay: Int) {
        val db = storeFactory.open() ?: return
        db.withWriteTransaction<Unit> {
            val streak = db.recoveryStreakDao().getActiveStreak(habitId) ?: return@withWriteTransaction
            db.milestoneEventDao().acknowledge(streak.streakId, milestoneDay, readings().tWallMillis)
        }
        loadState(db, habitId)?.let { publishState(it) }
    }

    /**
     * Caller must already hold [evaluationMutex]. [forceDisplay] is set by the selection-side
     * callers (refresh/select/onboard/relapse); the alarm-side path leaves it false so only
     * the displayed habit's state is emitted.
     */
    private suspend fun evaluateLocked(
        db: AppRulesStore,
        habitId: String,
        forceDisplay: Boolean,
    ): HabitState? {
        repeat(MAX_EVALUATION_ATTEMPTS) {
            val outcome = db.withWriteTransaction { evaluateOnce(db, habitId) }
            if (outcome is Outcome.Settled) {
                if (
                    forceDisplay ||
                    (outcome.state != null && outcome.state.habitId == _state.value?.habitId)
                ) {
                    publishState(outcome.state)
                }
                return outcome.state
            }
        }
        // Retries exhausted (a writer bypassed the mutex twice): keep the last emitted state
        // and let the next trigger evaluate again.
        return _state.value
    }

    /** Caller must already hold [evaluationMutex] and be inside a write transaction. */
    private suspend fun evaluateOnce(db: AppRulesStore, habitId: String): Outcome {
        val habit = db.habitProgressDao().getById(habitId) ?: return Outcome.Settled(null)
        val streak = db.recoveryStreakDao().getActiveStreak(habitId) ?: return Outcome.Settled(null)
        val readings = readings()

        return when (val evaluation = StreakTimer.evaluate(streak.toAnchor(), readings)) {
            // The active-streak read above cannot return an already-relapsed row inside the
            // write transaction; kept as the engine's defensive guard — re-fetch instead.
            StreakEvaluation.Superseded -> Outcome.Retry

            is StreakEvaluation.RejectedBackwardClock -> {
                // Nothing persisted (§9): hold the last valid accumulator, retry next evaluation.
                anomalyLog.append(evaluation.anomaly)
                stampAchievedMilestones(db, habitId, streak, streak.currentStreakElapsedMs, readings.tWallMillis)
                Outcome.Settled(habitStateOf(db, habit, streak))
            }

            is StreakEvaluation.Advance -> {
                val updated = db.recoveryStreakDao().compareAndUpdateAnchor(
                    streakId = streak.streakId,
                    tWallLastObserved = evaluation.tWallLastObserved,
                    rBootLastObserved = evaluation.rBootLastObserved,
                    bootCountLastObserved = evaluation.bootCountLastObserved,
                    currentStreakElapsedMs = evaluation.currentStreakElapsedMs,
                    expectedRowVersion = streak.rowVersion,
                )
                if (updated == 0) {
                    // §8 Step 2.7: another writer advanced past us — re-fetch and retry.
                    anomalyLog.append(
                        StreakAnomaly(
                            type = StreakAnomalyType.VERSION_CONFLICT,
                            tWallMillis = readings.tWallMillis,
                            rBootMillis = readings.rBootMillis,
                            bootCount = readings.bootCount,
                            deltaMs = null,
                            resolution = AnomalyResolution.RETRIED_AFTER_REFETCH,
                        ),
                    )
                    return Outcome.Retry
                }
                db.habitProgressDao().raiseAllTimeMax(habitId, evaluation.currentStreakElapsedMs)
                evaluation.anomaly?.let(anomalyLog::append)
                stampAchievedMilestones(db, habitId, streak, evaluation.currentStreakElapsedMs, readings.tWallMillis)
                if (dayOf(evaluation.currentStreakElapsedMs) > dayOf(streak.currentStreakElapsedMs)) {
                    // Appendix A.3: day-crossing only — bounds the partner feed to one
                    // STREAK_UPDATED per clean day instead of one per evaluation.
                    PartnerActivityJournal.emitForHabit(
                        db, habitId, EventPayload.TYPE_STREAK_UPDATED, readings.tWallMillis,
                    )
                }
                Outcome.Settled(
                    habitStateOf(
                        db,
                        habit,
                        streak.copy(currentStreakElapsedMs = evaluation.currentStreakElapsedMs),
                    ),
                )
            }
        }
    }

    /**
     * §6 step 8 first crossing: stamp `achieved_at` for every milestone whose day threshold
     * the streak's accumulator has reached. The DAO update is guarded `achieved_at IS NULL`,
     * so a milestone already celebrated is never re-stamped.
     */
    private suspend fun stampAchievedMilestones(
        db: AppRulesStore,
        habitId: String,
        streak: RecoveryStreak,
        currentStreakElapsedMs: Long,
        tWallMillis: Long,
    ) {
        db.milestoneEventDao().getForStreak(streak.streakId)
            .filter {
                it.achievedAt == null && currentStreakElapsedMs >= it.milestoneDay * StreakTimer.DAY_MS
            }
            .forEach {
                val stamped = db.milestoneEventDao().markAchieved(streak.streakId, it.milestoneDay, tWallMillis)
                // Appendix A.3: one MILESTONE_REACHED per *newly* stamped day — a day already
                // achieved by a racing writer must not be re-announced to the partner.
                if (stamped > 0) {
                    PartnerActivityJournal.emitForHabit(
                        db, habitId, EventPayload.TYPE_MILESTONE_REACHED, tWallMillis,
                        milestoneDay = it.milestoneDay,
                    )
                }
            }
    }

    /** Caller must already hold [evaluationMutex] and be inside a write transaction. */
    private suspend fun relapseOnce(
        db: AppRulesStore,
        habitId: String,
        backdatedRelapseAt: Long?,
    ): RelapseResult {
        val habit = db.habitProgressDao().getById(habitId) ?: return RelapseResult.NoActiveStreak
        val streak = db.recoveryStreakDao().getActiveStreak(habitId) ?: return RelapseResult.NoActiveStreak
        val readings = readings()
        val relapsedAt = backdatedRelapseAt ?: readings.tWallMillis

        if (!StreakTimer.isRelapseTimestampValid(streak.recoveryStartAt, relapsedAt)) {
            anomalyLog.append(
                StreakAnomaly(
                    type = StreakAnomalyType.RELAPSE_ROLLBACK_REJECTED,
                    tWallMillis = relapsedAt,
                    rBootMillis = readings.rBootMillis,
                    bootCount = readings.bootCount,
                    deltaMs = relapsedAt - streak.recoveryStartAt,
                    resolution = AnomalyResolution.REJECTED_INVALID_INPUT,
                ),
            )
            return RelapseResult.RejectedBeforeStreakStart
        }
        val lastRelapse = habit.lastRelapseTimestamp
        if (lastRelapse != null && abs(readings.tWallMillis - lastRelapse) < IDEMPOTENCY_WINDOW_MS) {
            return RelapseResult.DuplicateIgnored
        }

        // Running record from the ending streak's accumulator before it is superseded
        // (§8 Step 2.6) — the freshest value available even if the preceding evaluation
        // was rejected by an anomaly.
        db.habitProgressDao().raiseAllTimeMax(habitId, streak.currentStreakElapsedMs)
        db.recoveryStreakDao().markRelapsed(
            streakId = streak.streakId,
            relapsedAt = relapsedAt,
            bootCount = readings.bootCount,
            elapsedRealtimeMs = readings.rBootMillis,
        )
        db.habitProgressDao().recordRelapse(habitId, relapsedAt)

        // §6.3: the new streak starts now — a backdated relapse stamp ends the old streak
        // but never mints days for the new one.
        val newStreakId = UUID.randomUUID().toString()
        db.recoveryStreakDao().insert(
            RecoveryStreak(
                streakId = newStreakId,
                habitId = habitId,
                recoveryStartAt = readings.tWallMillis,
                tWallLastObserved = readings.tWallMillis,
                rBootLastObserved = readings.rBootMillis,
                bootCountLastObserved = readings.bootCount,
                currentStreakElapsedMs = 0,
            ),
        )
        db.milestoneEventDao().insertAll(milestoneRows(newStreakId))

        // Appendix A.3: post-rollover snapshot (fresh streak, empty milestone list, bumped
        // count) plus wire v2's [endedDays] — the ordinal day-count of the streak that just
        // ended, the same number the sender's own relapse card showed (ADR #128). The
        // occurrence is the relapse time — backdated when logged retrospectively — while the
        // journal's retention clock stays real write time.
        PartnerActivityJournal.emitForHabit(
            db, habitId, EventPayload.TYPE_RELAPSE_RECORDED,
            occurredAtMs = relapsedAt, createdAtMs = readings.tWallMillis,
            endedDays = dayOf(streak.currentStreakElapsedMs) + 1,
        )

        return RelapseResult.RolledOver(requireNotNull(loadState(db, habitId)))
    }

    private suspend fun loadState(db: AppRulesStore, habitId: String): HabitState? {
        val habit = db.habitProgressDao().getById(habitId) ?: return null
        val streak = db.recoveryStreakDao().getActiveStreak(habitId) ?: return null
        return habitStateOf(db, habit, streak)
    }

    private suspend fun habitStateOf(
        db: AppRulesStore,
        habit: HabitProgress,
        streak: RecoveryStreak,
    ): HabitState {
        // Reads back what onboard() wrote (severity.name); any other value is a corrupted
        // store, not a case to paper over.
        val severity = WithdrawalSeverity.valueOf(habit.onboardingSeverityLevel)
        return HabitState(
            habitId = habit.progressId,
            streakId = streak.streakId,
            targetLabel = habit.targetIdentifier,
            recoveryStartAt = streak.recoveryStartAt,
            currentStreakElapsedMs = streak.currentStreakElapsedMs,
            allTimeMaxElapsedMs = maxOf(
                habit.allTimeMaxStreakElapsedMs,
                streak.currentStreakElapsedMs,
            ),
            // Completed whole days, floored at 1 (owner rulings ADR #128, display rule revised
            // by ADR #176): never 0, and the same scale every countdown uses.
            currentDay = maxOf(1, dayOf(streak.currentStreakElapsedMs)),
            habitDurationYears = habit.habitDurationYears,
            severity = severity,
            withdrawalWeeks = WithdrawalCurve.weeks(habit.habitDurationYears, severity),
            milestones = db.milestoneEventDao().getForStreak(streak.streakId),
        )
    }

    /** Completed clean days of an accumulator — the engine's day-crossing unit (partner
     *  snapshots, milestone thresholds). [HabitState.currentDay] adds only the never-0 floor. */
    private fun dayOf(elapsedMs: Long): Int = (elapsedMs / StreakTimer.DAY_MS).toInt()

    private fun RecoveryStreak.toAnchor() = StreakAnchor(
        tWallLastObserved = tWallLastObserved,
        rBootLastObserved = rBootLastObserved,
        bootCountLastObserved = bootCountLastObserved,
        currentStreakElapsedMs = currentStreakElapsedMs,
        rowVersion = rowVersion,
        relapsedAt = relapsedAt,
    )

    private fun milestoneRows(streakId: String): List<MilestoneEvent> =
        MilestoneEvent.MILESTONE_DAYS.map { MilestoneEvent(streakId = streakId, milestoneDay = it) }

    private sealed interface Outcome {
        data class Settled(val state: HabitState?) : Outcome
        data object Retry : Outcome
    }

    companion object {
        /** §9 DB-level idempotency window for relapse writes (UI debounce is the first line). */
        const val IDEMPOTENCY_WINDOW_MS = 10_000L

        private const val MAX_EVALUATION_ATTEMPTS = 2
    }
}
