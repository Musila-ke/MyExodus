package com.example.myexodus.blocking.usage

import android.content.Context
import android.util.Log
import androidx.room3.withWriteTransaction
import com.example.myexodus.blocking.dayScopeCoversToday
import com.example.myexodus.blocking.systemBootCount
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.DailyUsageTracker
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.data.db.entity.isExpiredAt
import com.example.myexodus.telemetry.CrashReporting
import java.time.Instant
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
 * **The journal is the writer of record** (usage-ledger plan §8.3). A tick's credit is not
 * added to the tracker directly: the tick is journaled as an ACCOUNTING row inside the *same*
 * transaction, and the tracker advances by exactly what the journal accepted. A duplicate
 * delivery credits nothing (the original already advanced the projection), and a rejected or
 * colliding insert credits nothing either — which is what keeps `tracker.msUsedToday` equal to
 * Σ ledger for its own `localDateAnchor` day, the invariant [UsageLedgerRepository.rebuildProjection]
 * restores. Crediting a tick the journal refused would make the projection unreconstructable,
 * which is the whole failure this feature exists to end.
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
    private val ledger: UsageLedgerRepository,
    context: Context,
    /** Wall clock (§8.5's day attribution). Every `nowWallClockMs`/`today` below reads this one
     *  seam, so a test can pin the local day at any hour — reading the real clock made the
     *  day-split race real midnight and flaked the suite (found 2026-09-28). */
    private val wallClockNowMs: () -> Long,
) {
    /** Boot identity for the journal's rows (§8.6): an elapsed-time interval is only
     *  interpretable within the boot that produced it. */
    private val appContext = context.applicationContext
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
     * [nowElapsedRealtimeMs] is the caller's tick timestamp (NFR3). [source] identifies which
     * detector produced the tick (the journal's `source` column, §8.1) — diagnostic only;
     * authority means at most one of them is ever writing.
     */
    suspend fun processUsageObservation(
        packageName: String,
        incrementMs: Long,
        nowElapsedRealtimeMs: Long,
        source: String,
    ): EnforcementAction = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext EnforcementAction.NoAction
        // §14 telemetry is emitted after the transaction commits, never inside it; the
        // entry-transition flag survives out of the withWriteTransaction block.
        var enteredPendingLevel: Int? = null
        val action = db.withWriteTransaction {
            val profile = db.userProfileDao().getActive()
                ?: return@withWriteTransaction EnforcementAction.NoAction
            // One clock read governs the whole cycle: the day the tracker anchors to and the
            // day the journal's closing slice lands on are then the same by construction, so a
            // midnight crossing between two reads can never anchor a row to a day the credit
            // did not reach.
            val nowWallClockMs = wallClockNowMs()
            val rule = db.restrictionRuleDao()
                .getByProfileAndPackage(profile.userId, packageName)
                ?.takeIf { it.isEnabled && !it.isExpiredAt(nowWallClockMs) }
                ?: return@withWriteTransaction EnforcementAction.NoAction

            val trackerDao = db.dailyUsageTrackerDao()
            val existing = trackerDao.findByPackage(profile.userId, packageName)
            val today = localDateOf(nowWallClockMs)
            val bootCount = systemBootCount(appContext)
            var tracker = existing ?: newTracker(profile.userId, packageName, today)

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

            // (c) accumulate — through the journal, in this same transaction (§8.3). A zero
            // delta is not an observation and is never journaled; the tracker advance is
            // exactly the closing-day share the journal accepted, so a tick that crosses local
            // midnight credits this day only its own half (the opening half survives in the
            // journal as yesterday's row, which Decision 20's in-place rollover would otherwise
            // destroy before anyone could look at it).
            if (incrementMs > 0L) {
                val credited = when (
                    val outcome = ledger.append(
                        LedgerEventDraft(
                            kind = UsageLedgerKind.ACCOUNTING,
                            packageName = packageName,
                            creditedMs = incrementMs,
                            elapsedStartMs = nowElapsedRealtimeMs - incrementMs,
                            elapsedEndMs = nowElapsedRealtimeMs,
                            bootCount = bootCount,
                            source = source,
                            wallClockTsMs = nowWallClockMs,
                        ),
                    )
                ) {
                    is AppendOutcome.Written -> outcome.closingDayCreditedMs
                    // Already credited by the original insert (§8.6), or refused: crediting
                    // either would put the projection out of reach of a rebuild.
                    AppendOutcome.Duplicate, is AppendOutcome.Collision, is AppendOutcome.Rejected -> 0L
                }
                if (credited != incrementMs) {
                    Log.w(
                        TAG,
                        "observe $packageName partial credit ${credited}ms of ${incrementMs}ms" +
                            " (duplicate, refused, or crossing local midnight)",
                    )
                }
                tracker = tracker.copy(msUsedToday = tracker.msUsedToday + credited)
            }

            // (d) evaluate (§6 step 5) and (e) persist, shared with the reconciliation path.
            val verdict = deriveVerdict(tracker, rule.dailyLimitMs, packageName)
            enteredPendingLevel = verdict.enteredPendingLevel
            journalVerdictChange(verdict, packageName, source, bootCount, nowElapsedRealtimeMs, nowWallClockMs)
            if (existing == null) trackerDao.insert(verdict.tracker) else trackerDao.update(verdict.tracker)

            if (rolledOver || verdict.action != EnforcementAction.NoAction) {
                Log.i(
                    TAG,
                    "observe $packageName +${incrementMs}ms -> ${verdict.tracker.msUsedToday}ms/" +
                        "${rule.dailyLimitMs}ms ${verdict.action} (elapsed=$nowElapsedRealtimeMs rolledOver=$rolledOver)",
                )
            }
            verdict.action
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
     * OPD #28(a), user ruling 2026-09-27 ("Digital Wellbeing is the source of truth"): seed or
     * raise today's usage from the platform's own foreground number, called at the seams where
     * a rule starts counting (save, enable) — exactly where the engine's counter and the
     * platform's disagree most, because everything before that seam was never observed.
     *
     * Monotone by construction: `merged = max(existing, platformMs)` can only raise usage. It is
     * an absolute number, not an increment, so max keeps every millisecond the live paths
     * already credited (no double-count on a re-run) and can never un-block a row past its
     * limit. Null/zero platform = skip. Warnings are left untouched — the caller follows with
     * [onDailyLimitChanged] only when the limit itself changed.
     */
    suspend fun reconcileWithPlatform(packageName: String, platformMs: Long?) = withContext(dispatcher) {
        if (platformMs == null || platformMs <= 0L) return@withContext
        val db = storeFactory.open() ?: return@withContext
        db.withWriteTransaction {
            val profile = db.userProfileDao().getActive() ?: return@withWriteTransaction
            val nowWallClockMs = wallClockNowMs()
            val rule = db.restrictionRuleDao()
                .getByProfileAndPackage(profile.userId, packageName)
                ?.takeIf {
                    it.isEnabled && !it.isExpiredAt(nowWallClockMs) && it.dailyLimitMs > 0L
                }
                ?: return@withWriteTransaction
            val trackerDao = db.dailyUsageTrackerDao()
            val today = localDateOf(nowWallClockMs)
            val existing = trackerDao.findByPackage(profile.userId, packageName)
            val base = existing?.let {
                if (it.localDateAnchor != today) it.rolledOverTo(today) else it
            }
            // Inactive day scope accrues nothing (user decision 2026-09-20) — never seed into
            // it; a stale row still rolls, matching processUsageObservation's own handling.
            if (!dayScopeCoversToday(rule.activeDaysOfWeek, rule.timezone)) {
                if (base != null && base !== existing) trackerDao.update(base)
                return@withWriteTransaction
            }
            val before = base?.msUsedToday ?: 0L
            val merged = maxOf(before, platformMs)
            // The top-up has no observed interval behind it — it is the platform's own total
            // for time this app never saw. Journaled as a carryover aggregate (§8.10's shape:
            // zero-length interval, exempt from §8.2's credited≤span bound) so the projection
            // stays equal to Σ ledger and a rebuild cannot silently delete the seed.
            val carried = if (merged > before) {
                when (
                    val outcome = ledger.append(
                        LedgerEventDraft(
                            kind = UsageLedgerKind.MIGRATION_CARRYOVER,
                            packageName = packageName,
                            creditedMs = merged - before,
                            elapsedStartMs = 0L,
                            elapsedEndMs = 0L,
                            bootCount = systemBootCount(appContext),
                            source = UsageLedgerSource.MAINTENANCE,
                            wallClockTsMs = nowWallClockMs,
                        ),
                    )
                ) {
                    is AppendOutcome.Written -> outcome.closingDayCreditedMs
                    else -> 0L
                }
            } else {
                0L
            }
            val total = before + carried
            val tracker = (base ?: newTracker(profile.userId, packageName, today))
                .copy(msUsedToday = total, blockedToday = total >= rule.dailyLimitMs)
            if (existing == null) trackerDao.insert(tracker) else trackerDao.update(tracker)
            Log.i(
                TAG,
                "reconcile $packageName platform=${platformMs}ms carried=${carried}ms total=$total/" +
                    "${rule.dailyLimitMs}ms blocked=${tracker.blockedToday}",
            )
        }
    }

    /**
     * §8.9/§8.11: credit a platform-derived recovery window as RECONCILIATION — the one kind
     * UsageStatsManager-derived data is ever written as (R4). [foregroundMs] is the caller's own
     * measurement of the package's foreground time inside the elapsed window; the credit is
     * scaled to the share of that window no ACCOUNTING/RECONCILIATION row already covers, so
     * running the same recovery twice — even with different bounds — adds nothing the second
     * time (§8.9's overlap rule, checked in the same transaction as the write).
     *
     * Goes forward, never back (§8.11): the projection advances by the closing-day share the
     * journal accepted, and the verdict is derived exactly as a live observation's would be, so
     * a recovered window can still deliver a warning or a block that its outage swallowed.
     * The §8.9 overlap check compares only rows in the same boot (§8.5: elapsed values from
     * different boots are not comparable, so an equal number from an old frame is a coincidence,
     * not coverage). Returns the credited closing-day share (0 when nothing was credited).
     *
     * [answeredThroughWallMs] is the wall of the window's END (windowToElapsedMs), not the
     * write moment — and it is what the row stores. ADR #160's credit edge reads the newest
     * credit's wall as "everything up to here is answered for"; a row that answered for a past
     * window but stamped itself with the write moment would advance the edge past its own
     * answer — device-proven 2026-10-15: a service-start audit during a genuine pause stamped
     * write-now, pushing the edge 11 s past the user's stop, which disarmed the stop clip and
     * let the re-enable audit claim the paused era. The caller owns the wall↔elapsed mapping
     * (it derived the elapsed window from this same wall), so it passes the value back.
     */
    suspend fun creditReconciledWindow(
        packageName: String,
        windowFromElapsedMs: Long,
        windowToElapsedMs: Long,
        foregroundMs: Long,
        source: String,
        answeredThroughWallMs: Long,
    ): Long = withContext(dispatcher) {
        val span = windowToElapsedMs - windowFromElapsedMs
        if (span <= 0L || foregroundMs <= 0L) return@withContext 0L
        val db = storeFactory.open() ?: return@withContext 0L
        var enteredPendingLevel: Int? = null
        val credited = db.withWriteTransaction {
            val profile = db.userProfileDao().getActive() ?: return@withWriteTransaction 0L
            val nowWallClockMs = wallClockNowMs()
            val rule = db.restrictionRuleDao()
                .getByProfileAndPackage(profile.userId, packageName)
                ?.takeIf { it.isEnabled && !it.isExpiredAt(nowWallClockMs) }
                ?: return@withWriteTransaction 0L
            val today = localDateOf(nowWallClockMs)
            val bootCount = systemBootCount(appContext)
            val trackerDao = db.dailyUsageTrackerDao()
            val existing = trackerDao.findByPackage(profile.userId, packageName)
            var tracker = existing ?: newTracker(profile.userId, packageName, today)
            val rolledOver = tracker.localDateAnchor != today
            if (rolledOver) tracker = tracker.rolledOverTo(today)
            // Same day-scope rule as every credit path (user decision 2026-09-20).
            if (!dayScopeCoversToday(rule.activeDaysOfWeek, rule.timezone)) {
                if (rolledOver) trackerDao.update(tracker)
                return@withWriteTransaction 0L
            }
            // §8.9: credit only the uncovered remainder. MIGRATION_CARRYOVER and CORRECTION
            // rows own no interval and cannot cover a window; RULE_EVALUATION and the
            // diagnostics kinds are not accounting at all.
            val covered = db.usageLedgerDao()
                .intervalsOverlapping(packageName, OVERLAP_KINDS, windowFromElapsedMs, windowToElapsedMs, bootCount)
                .map { it.elapsedStartMs until it.elapsedEndMs }
            val uncovered = UsageGapRepair.uncoveredMs(covered, windowFromElapsedMs, windowToElapsedMs)
            if (uncovered <= 0L) {
                if (rolledOver) trackerDao.update(tracker)
                return@withWriteTransaction 0L
            }
            val credit = if (uncovered >= span) foregroundMs else foregroundMs * uncovered / span
            if (credit <= 0L) {
                if (rolledOver) trackerDao.update(tracker)
                return@withWriteTransaction 0L
            }
            val accepted = when (
                val outcome = ledger.append(
                    LedgerEventDraft(
                        kind = UsageLedgerKind.RECONCILIATION,
                        packageName = packageName,
                        creditedMs = credit,
                        elapsedStartMs = windowFromElapsedMs,
                        elapsedEndMs = windowToElapsedMs,
                        bootCount = bootCount,
                        source = source,
                        wallClockTsMs = answeredThroughWallMs,
                    ),
                )
            ) {
                is AppendOutcome.Written -> outcome.closingDayCreditedMs
                AppendOutcome.Duplicate, is AppendOutcome.Collision, is AppendOutcome.Rejected -> 0L
            }
            if (accepted != credit) {
                Log.w(TAG, "reconcile $packageName partial credit ${accepted}ms of ${credit}ms")
            }
            val verdict = deriveVerdict(
                tracker.copy(msUsedToday = tracker.msUsedToday + accepted),
                rule.dailyLimitMs,
                packageName,
            )
            enteredPendingLevel = verdict.enteredPendingLevel
            journalVerdictChange(
                verdict, packageName, source, bootCount, windowToElapsedMs, nowWallClockMs,
            )
            if (existing == null) trackerDao.insert(verdict.tracker) else trackerDao.update(verdict.tracker)
            Log.i(
                TAG,
                "reconcile $packageName foreground=${foregroundMs}ms uncovered=$uncovered/$span" +
                    " credit=${accepted}ms -> ${verdict.tracker.msUsedToday}ms/${rule.dailyLimitMs}ms",
            )
            accepted
        }
        enteredPendingLevel?.let {
            crashReporting.nonFatal("warning_pending_delivery", cause = "reconciliation_credit", "level" to "$it")
        }
        credited
    }

    /**
     * ADR #160's credit-edge anchor: per package, the wall of the newest interval-owning
     * credit (ACCOUNTING/RECONCILIATION — the same kinds §8.9's overlap check reads, because
     * only those own an interval whose end the anchor may claim). A package absent from the
     * map has no credit inside the range — its uncredited span starts at the range's floor.
     */
    suspend fun lastCreditWallPerPackage(
        packageNames: Collection<String>,
        sinceWallClockMs: Long,
    ): Map<String, Long> = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext emptyMap()
        db.usageLedgerDao()
            .creditEdges(packageNames, OVERLAP_KINDS, sinceWallClockMs)
            .associate { it.packageName to it.creditWallMs }
    }

    /** (d) of the accounting cycle, shared by every crediting path (live observation and
     *  reconciliation): derive the block from usage and advance the warning state machine (a
     *  non-positive limit reaches Block through the plain comparison — §9 "treat as already at
     *  limit" — so there is no division-by-zero path to guard). Pure over its arguments: the
     *  caller journals and persists. */
    private fun deriveVerdict(tracker: DailyUsageTracker, limitMs: Long, packageName: String): Verdict {
        val usage = tracker.msUsedToday
        val blocked = usage >= limitMs
        var updated = tracker.copy(blockedToday = blocked)
        val (threshold1, threshold2) = thresholdsFor(packageName, limitMs)
        var enteredPendingLevel: Int? = null
        val action: EnforcementAction = when {
            blocked -> EnforcementAction.Block
            // Retry semantics (Decision 14, §12): any non-DELIVERED state past its threshold
            // re-returns ShowWarning until delivery is confirmed. Level 2 shadows an undelivered
            // level 1 (most urgent wins); level 1 retries once 2 is delivered.
            usage >= threshold2 && updated.warning2State != WarningState.DELIVERED -> {
                if (updated.warning2State == WarningState.NOT_DUE) enteredPendingLevel = 2
                updated = updated.copy(warning2State = WarningState.PENDING_DELIVERY)
                EnforcementAction.ShowWarning(2, (limitMs - usage).coerceAtLeast(0))
            }
            usage >= threshold1 && updated.warning1State != WarningState.DELIVERED -> {
                if (updated.warning1State == WarningState.NOT_DUE) enteredPendingLevel = 1
                updated = updated.copy(warning1State = WarningState.PENDING_DELIVERY)
                EnforcementAction.ShowWarning(1, (limitMs - usage).coerceAtLeast(0))
            }
            else -> EnforcementAction.NoAction
        }
        return Verdict(updated, action, enteredPendingLevel, blocked && !tracker.blockedToday)
    }

    /** A verdict that changed is worth a journal row; a verdict merely re-stated is not (§8.1
     *  RULE_EVALUATION is diagnostic-only and never contributes to the projection, and a row per
     *  1.5 s tick would drown the journal it exists to make readable). */
    private suspend fun journalVerdictChange(
        verdict: Verdict,
        packageName: String,
        source: String,
        bootCount: Int,
        nowElapsedMs: Long,
        nowWallClockMs: Long,
    ) {
        if (verdict.enteredPendingLevel == null && !verdict.enteredBlock) return
        ledger.append(
            LedgerEventDraft(
                kind = UsageLedgerKind.RULE_EVALUATION,
                packageName = packageName,
                creditedMs = 0L,
                elapsedStartMs = nowElapsedMs,
                elapsedEndMs = nowElapsedMs,
                bootCount = bootCount,
                source = source,
                wallClockTsMs = nowWallClockMs,
            ),
        )
    }

    private class Verdict(
        val tracker: DailyUsageTracker,
        val action: EnforcementAction,
        val enteredPendingLevel: Int?,
        val enteredBlock: Boolean,
    )

    /**
     * Advisory read for Path B's adaptive ticker (§8 Step 5): the package's remaining budget
     * today, or null when it is untracked (no active profile, no enabled rule, non-positive
     * limit). Rollover-aware — a stale date/timezone anchor means zero usage today. Read-only,
     * and on the same dispatcher as the writes, so it can never observe a half-written cycle.
     */
    suspend fun remainingBudgetMs(packageName: String): Long? = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext null
        val profile = db.userProfileDao().getActive() ?: return@withContext null
        val nowWallClockMs = wallClockNowMs()
        val rule = db.restrictionRuleDao()
            .getByProfileAndPackage(profile.userId, packageName)
            ?.takeIf { it.isEnabled && !it.isExpiredAt(nowWallClockMs) }
            ?: return@withContext null
        val tracker = db.dailyUsageTrackerDao().findByPackage(profile.userId, packageName)
        val today = localDateOf(nowWallClockMs)
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
            val today = localDateOf(wallClockNowMs())
            val stale = trackerDao.getForProfile(profile.userId)
                .filter { it.localDateAnchor != today }
            stale.forEach { trackerDao.update(it.rolledOverTo(today)) }
            stale.size
        }
    }

    /** The device-local date a wall-clock reading belongs to — the single day-attribution
     *  authority ([wallClockNowMs] is its only clock). */
    private fun localDateOf(wallClockMs: Long): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(wallClockMs), ZoneId.systemDefault()).toString()

    /** The one construction site for a fresh day's row (Decision 20 shape). */
    private fun newTracker(profileId: String, packageName: String, today: String) = DailyUsageTracker(
        trackerId = UUID.randomUUID().toString(),
        profileId = profileId,
        packageName = packageName,
        localDateAnchor = today,
        timezoneIdAtAnchor = ZoneId.systemDefault().id,
        msUsedToday = 0L,
        warning1State = WarningState.NOT_DUE,
        warning2State = WarningState.NOT_DUE,
        blockedToday = false,
    )

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

        /** §8.9's overlap rule covers the two kinds that own an accounting interval — the only
         *  rows a reconciliation credit can duplicate. */
        private val OVERLAP_KINDS = listOf(UsageLedgerKind.ACCOUNTING, UsageLedgerKind.RECONCILIATION)

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
