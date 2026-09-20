package com.example.myexodus.blocking.usage

import android.util.Log
import androidx.room3.withWriteTransaction
import com.example.myexodus.blocking.dayScopeCoversToday
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.DailyUsageTracker
import com.example.myexodus.data.db.entity.isExpiredAt
import com.example.myexodus.telemetry.CrashReporting
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Outcome of one accounting evaluation (enforcement plan v2 §6 step 5). */
sealed interface EnforcementAction {
    data object NoAction : EnforcementAction

    /**
     * [level] 1 = first threshold (10 min of budget left for limits ≥ 10 min, else 50% used),
     * 2 = second (5 min left / 90% used). [remainingMs] = limit minus usage as computed at the
     * crossing, for the overlay's "about N min left" copy (§8 Step 10 content model); clamped
     * at 0. Returned again on every subsequent observation while the corresponding state is
     * PENDING_DELIVERY, until the caller confirms delivery via
     * [UsageAccountingEngine.markWarningDelivered] (Decision 14).
     */
    data class ShowWarning(val level: Int, val remainingMs: Long) : EnforcementAction

    /** Usage reached the limit — render/re-arm the block overlay (§6 step 5, Step 4 re-arm). */
    data object Block : EnforcementAction
}

/** Warning-delivery states persisted per threshold (Decision 14). */
object WarningState {
    const val NOT_DUE = "NOT_DUE"
    const val PENDING_DELIVERY = "PENDING_DELIVERY"
    const val DELIVERED = "DELIVERED"
}

/**
 * Enforcement Accounting Engine (enforcement plan v2 Step 3) — the only component that ever
 * mutates a tracked app's daily usage. [processUsageObservation] performs read + day-rollover +
 * accumulate + threshold-evaluate + persist as one Room write transaction (Decision 19) on the
 * single injected dispatcher every caller shares, closing the read-modify-write race between
 * the two detection paths. Core logic is pure arithmetic over Room rows, unit-testable against
 * a real database.
 *
 * Caller contract (§6 steps 3–4): each call carries an *incremental* delta and the caller
 * restarts its session anchor after every call, so a tick is never counted twice. Authority
 * gating (Decision 2) is caller-side: only the current authority holder calls this. Rule
 * lookup intentionally mirrors the enforcement snapshot: a disabled (paused) or expired rule
 * is untracked.
 */
class UsageAccountingEngine(
    private val storeFactory: AppRulesStoreFactory,
    private val dispatcher: CoroutineDispatcher,
    private val crashReporting: CrashReporting,
) {
    /** Threshold pair per package with the limit it was computed for — self-invalidating when
     *  the limit changes (Step 3 "cached, recomputed on limit change", Decision 15). Touched
     *  only inside [dispatcher], which every caller shares. */
    private val thresholdCache = HashMap<String, CachedThresholds>()

    /**
     * The single transactional accounting cycle. Reads the active profile's rule and today's
     * tracker row, rolls the day over when the stored anchor no longer matches the current
     * local date (Decision 20 — any mismatch, including a timezone move, rolls), adds
     * [incrementMs], derives [DailyUsageTracker.blockedToday] from usage vs. limit, advances
     * the warning state machine, persists, and returns what the caller must render.
     *
     * [nowElapsedRealtimeMs] is the caller's tick timestamp (NFR3); used for logging only.
     */
    suspend fun processUsageObservation(
        packageName: String,
        incrementMs: Long,
        nowElapsedRealtimeMs: Long,
    ): EnforcementAction = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext EnforcementAction.NoAction
        // §14 telemetry is emitted after the transaction commits, never inside it; the
        // entry-transition flag survives out of the withWriteTransaction block.
        var enteredPendingLevel: Int? = null
        val action = db.withWriteTransaction {
            val profile = db.userProfileDao().getActive()
                ?: return@withWriteTransaction EnforcementAction.NoAction
            val rule = db.restrictionRuleDao()
                .getByProfileAndPackage(profile.userId, packageName)
                ?.takeIf { it.isEnabled && !it.isExpiredAt(System.currentTimeMillis()) }
                ?: return@withWriteTransaction EnforcementAction.NoAction

            val trackerDao = db.dailyUsageTrackerDao()
            val existing = trackerDao.findByPackage(profile.userId, packageName)
            val today = LocalDate.now().toString()
            var tracker = existing ?: DailyUsageTracker(
                trackerId = UUID.randomUUID().toString(),
                profileId = profile.userId,
                packageName = packageName,
                localDateAnchor = today,
                timezoneIdAtAnchor = ZoneId.systemDefault().id,
                msUsedToday = 0L,
                warning1State = WarningState.NOT_DUE,
                warning2State = WarningState.NOT_DUE,
                blockedToday = false,
            )

            // (b) rollover first, before any evaluation (§6 step 6) — a stale row must never
            // contribute yesterday's usage to today's decision.
            val rolledOver = tracker.localDateAnchor != today
            if (rolledOver) tracker = tracker.rolledOverTo(today)

            // (b2) day scope (user decision 2026-09-20): on a day the rule does not cover the
            // rule is inactive — nothing accrues, nothing warns, nothing blocks, so an inactive
            // day can never eat into an active day's budget. Judged in the rule's zone (the
            // window's anchor); the tracker's own anchor stays device-local (Decision 20).
            if (!dayScopeCoversToday(rule.activeDaysOfWeek, rule.timezone)) {
                if (rolledOver) trackerDao.update(tracker)
                return@withWriteTransaction EnforcementAction.NoAction
            }

            // (c) accumulate.
            tracker = tracker.copy(msUsedToday = tracker.msUsedToday + incrementMs)

            // (d) evaluate (§6 step 5). A non-positive limit reaches Block through the plain
            // comparison (§9 "treat as already at limit") — the threshold formula below divides
            // only by constants, so there is no division-by-zero path to guard.
            val limit = rule.dailyLimitMs
            val usage = tracker.msUsedToday
            val blocked = usage >= limit
            tracker = tracker.copy(blockedToday = blocked)
            val (threshold1, threshold2) = thresholdsFor(packageName, limit)
            val action: EnforcementAction = when {
                blocked -> EnforcementAction.Block
                // Retry semantics (Decision 14, §12): any non-DELIVERED state past its
                // threshold re-returns ShowWarning until delivery is confirmed. Level 2 shadows
                // an undelivered level 1 (most urgent wins); level 1 retries once 2 is delivered.
                usage >= threshold2 && tracker.warning2State != WarningState.DELIVERED -> {
                    if (tracker.warning2State == WarningState.NOT_DUE) enteredPendingLevel = 2
                    tracker = tracker.copy(warning2State = WarningState.PENDING_DELIVERY)
                    EnforcementAction.ShowWarning(2, (limit - usage).coerceAtLeast(0))
                }
                usage >= threshold1 && tracker.warning1State != WarningState.DELIVERED -> {
                    if (tracker.warning1State == WarningState.NOT_DUE) enteredPendingLevel = 1
                    tracker = tracker.copy(warning1State = WarningState.PENDING_DELIVERY)
                    EnforcementAction.ShowWarning(1, (limit - usage).coerceAtLeast(0))
                }
                else -> EnforcementAction.NoAction
            }

            // (e) persist.
            if (existing == null) trackerDao.insert(tracker) else trackerDao.update(tracker)

            if (rolledOver || action != EnforcementAction.NoAction) {
                Log.i(
                    TAG,
                    "observe $packageName +${incrementMs}ms -> ${usage}ms/${limit}ms $action" +
                        " (elapsed=$nowElapsedRealtimeMs rolledOver=$rolledOver)",
                )
            }
            action
        }
        // §14: entering PENDING_DELIVERY. Package name never leaves the device (Decision 12);
        // the "still pending after N retries" variant of this event needs a persisted retry
        // counter the schema does not have — recorded deviation, not emitted here.
        enteredPendingLevel?.let { level ->
            crashReporting.nonFatal("warning_pending_delivery", cause = "threshold_crossed", "level" to "$level")
        }
        action
    }

    /**
     * Delivery confirmation (§6 step 7, Decision 14): only PENDING_DELIVERY → DELIVERED. A late
     * confirmation after a rollover or warning reset is a no-op, never a fabricated delivery.
     */
    suspend fun markWarningDelivered(packageName: String, level: Int) = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext
        val delivered = db.withWriteTransaction {
            val profile = db.userProfileDao().getActive() ?: return@withWriteTransaction false
            val trackerDao = db.dailyUsageTrackerDao()
            val tracker = trackerDao.findByPackage(profile.userId, packageName)
                ?: return@withWriteTransaction false
            val updated = when (level) {
                1 -> tracker.takeIf { it.warning1State == WarningState.PENDING_DELIVERY }
                    ?.copy(warning1State = WarningState.DELIVERED)
                2 -> tracker.takeIf { it.warning2State == WarningState.PENDING_DELIVERY }
                    ?.copy(warning2State = WarningState.DELIVERED)
                else -> null
            }
            if (updated != null) trackerDao.update(updated)
            updated != null
        }
        // §14: the resolution half of the PENDING_DELIVERY pair (delivered only; a no-op
        // confirm after rollover/reset is not a delivery and reports nothing).
        if (delivered) crashReporting.nonFatal("warning_delivery_resolved", cause = "delivered", "level" to "$level")
    }

    /**
     * Mid-day limit change (Decision 15): call AFTER the new dailyLimitMs is committed to the
     * rule. Resets both warning states to NOT_DUE, re-derives blockedToday against the new
     * limit (so a lowered limit blocks immediately and a raised one un-blocks immediately),
     * and leaves accumulated usage untouched. Thresholds need no explicit invalidation —
     * [thresholdsFor] re-derives whenever the cached pair's limit no longer matches. No tracker
     * row yet means today has no usage; nothing to reset.
     */
    suspend fun onDailyLimitChanged(packageName: String) = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext
        db.withWriteTransaction {
            val profile = db.userProfileDao().getActive() ?: return@withWriteTransaction
            val rule = db.restrictionRuleDao()
                .getByProfileAndPackage(profile.userId, packageName)
                ?: return@withWriteTransaction
            val trackerDao = db.dailyUsageTrackerDao()
            val tracker = trackerDao.findByPackage(profile.userId, packageName)
                ?: return@withWriteTransaction
            trackerDao.update(
                tracker.copy(
                    warning1State = WarningState.NOT_DUE,
                    warning2State = WarningState.NOT_DUE,
                    blockedToday = tracker.msUsedToday >= rule.dailyLimitMs,
                ),
            )
        }
    }

    /**
     * Advisory read for Path B's adaptive ticker (§8 Step 5): the package's remaining budget
     * today, or null when it is untracked (no active profile, no enabled rule, non-positive
     * limit). Rollover-aware — a stale date/timezone anchor means zero usage today. Read-only,
     * and on the same dispatcher as the writes, so it can never observe a half-written cycle.
     */
    suspend fun remainingBudgetMs(packageName: String): Long? = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext null
        val profile = db.userProfileDao().getActive() ?: return@withContext null
        val rule = db.restrictionRuleDao()
            .getByProfileAndPackage(profile.userId, packageName)
            ?.takeIf { it.isEnabled && !it.isExpiredAt(System.currentTimeMillis()) }
            ?: return@withContext null
        val tracker = db.dailyUsageTrackerDao().findByPackage(profile.userId, packageName)
        val today = LocalDate.now().toString()
        val tz = ZoneId.systemDefault().id
        val used = tracker
            ?.takeIf { it.localDateAnchor == today && it.timezoneIdAtAnchor == tz }
            ?.msUsedToday
            ?: 0L
        (rule.dailyLimitMs - used).coerceAtLeast(0)
    }

    /**
     * Daily-maintenance sweep (§8 Step 8): rolls every tracker row whose stored anchor no
     * longer matches the current local date, so a day with no tracked-app launch still clears
     * stale `blockedToday`/warning state. Shares the exact Decision 20 transformation with
     * [processUsageObservation] via [rolledOverTo] — the two paths cannot drift. Returns the
     * number of rows rolled (0 when the profile/database is unavailable).
     */
    suspend fun rolloverStaleDays(): Int = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext 0
        db.withWriteTransaction {
            val profile = db.userProfileDao().getActive() ?: return@withWriteTransaction 0
            val trackerDao = db.dailyUsageTrackerDao()
            val today = LocalDate.now().toString()
            val stale = trackerDao.getForProfile(profile.userId)
                .filter { it.localDateAnchor != today }
            stale.forEach { trackerDao.update(it.rolledOverTo(today)) }
            stale.size
        }
    }

    /** Decision 20's rollover transformation — a stale anchor (date or timezone move) resets
     *  the row to a fresh day: zero usage, no warnings, unblocked. */
    private fun DailyUsageTracker.rolledOverTo(today: String) = copy(
        localDateAnchor = today,
        timezoneIdAtAnchor = ZoneId.systemDefault().id,
        msUsedToday = 0L,
        warning1State = WarningState.NOT_DUE,
        warning2State = WarningState.NOT_DUE,
        blockedToday = false,
    )

    private fun thresholdsFor(packageName: String, limitMs: Long): Pair<Long, Long> {
        val cached = thresholdCache[packageName]
        if (cached != null && cached.limitMs == limitMs) return cached.thresholds
        return calculateWarningThresholdsMs(limitMs).also {
            thresholdCache[packageName] = CachedThresholds(limitMs, it)
        }
    }

    private data class CachedThresholds(val limitMs: Long, val thresholds: Pair<Long, Long>)

    companion object {
        private const val TAG = "MyExodusUsage"

        /**
         * FR3 thresholds (Step 3): limits ≥ 10 min warn at 10 and 5 minutes of remaining
         * budget; smaller limits at 50% and 90% used. Non-positive limits clamp to (0, 0) —
         * the engine returns Block for them before thresholds matter (§9).
         */
        fun calculateWarningThresholdsMs(dailyLimitMs: Long): Pair<Long, Long> {
            if (dailyLimitMs <= 0) return 0L to 0L
            val tenMin = 10 * 60_000L
            val fiveMin = 5 * 60_000L
            return if (dailyLimitMs >= tenMin) {
                (dailyLimitMs - tenMin) to (dailyLimitMs - fiveMin)
            } else {
                (dailyLimitMs / 2) to ((dailyLimitMs * 9) / 10)
            }
        }
    }
}
