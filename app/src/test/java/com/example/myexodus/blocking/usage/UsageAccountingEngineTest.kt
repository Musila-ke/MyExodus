package com.example.myexodus.blocking.usage

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.blocking.usage.EnforcementAction.Block
import com.example.myexodus.blocking.usage.EnforcementAction.NoAction
import com.example.myexodus.blocking.usage.EnforcementAction.ShowWarning
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.DailyUsageTracker
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.data.db.entity.UserProfile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Enforcement Accounting Engine unit tests (plan §12). Runs against the real (Robolectric)
 * Room database through the production factory path — the engine's "in-memory database"
 * testing mode — since the single-transaction guarantee (Decision 19) is exactly what these
 * tests must not fake away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UsageAccountingEngineTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var db: AppRulesStore
    private lateinit var ledger: UsageLedgerRepository
    private lateinit var engine: UsageAccountingEngine

    /**
     * Pinned "now" — local noon of the day this test instance was created. The engine's day
     * attribution reads the injected clock (§8.5), so a 20–45 min span can never straddle real
     * midnight mid-suite (flake caught 2026-09-28 00:12). [today] is that same instant's local
     * date, so assertions and the engine's anchors cannot disagree whatever hour the suite runs.
     */
    private val wallNowMs: Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
        .plusHours(12).toInstant().toEpochMilli()
    private val today: LocalDate =
        LocalDate.ofInstant(Instant.ofEpochMilli(wallNowMs), ZoneId.systemDefault())

    /** The engine's injected clock — [wallNowMs] unless a test moves it across midnight. */
    private var engineNowMs: Long = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Pin the boot frame the engine reads, so the §8.9 same-boot overlap scoping is
        // deterministic instead of depending on the Robolectric default.
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, BOOT)
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        val factory = AppRulesStoreFactory(context, recovery, healthStore)
        db = factory.open()!!
        val dispatcher = Dispatchers.IO.limitedParallelism(1)
        val crashReporting = com.example.myexodus.telemetry.CrashReporting(context)
        ledger = UsageLedgerRepository(factory, dispatcher, crashReporting)
        engineNowMs = wallNowMs
        engine = UsageAccountingEngine(factory, dispatcher, crashReporting, ledger, context) { engineNowMs }
        kotlinx.coroutines.runBlocking {
            db.userProfileDao().insert(
                UserProfile(
                    userId = PROFILE_ID, name = "You", email = "", phoneNumber = null, dateOfBirth = 0L,
                    hasAccessibilityPermission = false, hasUsageStatsPermission = false,
                    hasVpnPermission = false, hasNotificationPermission = false,
                    hasDeviceAdminPermission = false, devicePublicKey = null, recoveryEscrowKey = null,
                    isActive = true,
                ),
            )
            db.restrictionRuleDao().insert(
                RestrictionRule(
                    ruleId = RULE_ID, profileId = PROFILE_ID, blockedPackage = PKG, targetType = "APP",
                    isAlwaysOn = false, dailyLimitMs = 30 * MIN, timeRangeStart = null, timeRangeEnd = null,
                    activeDaysOfWeek = "1111111", blockExpiresAt = 0L, severityLevel = "MEDIUM",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    // --- calculateWarningThresholdsMs (§12: both branches, exact-10-minute boundary, clamping) ---

    @Test
    fun thresholds_tenMinutesOrMore_warnAtTenAndFiveMinutesRemaining() {
        assertEquals(20 * MIN to 25 * MIN, UsageAccountingEngine.calculateWarningThresholdsMs(30 * MIN))
    }

    @Test
    fun thresholds_exactTenMinuteBoundary_usesRemainingBudgetBranch() {
        assertEquals(0L to 5 * MIN, UsageAccountingEngine.calculateWarningThresholdsMs(10 * MIN))
    }

    @Test
    fun thresholds_underTenMinutes_usePercentBranches() {
        assertEquals((9 * MIN) / 2 to (9 * MIN) * 9 / 10, UsageAccountingEngine.calculateWarningThresholdsMs(9 * MIN))
    }

    @Test
    fun thresholds_oneMinuteLimit_matchesSpecMatrix() {
        assertEquals(30_000L to 54_000L, UsageAccountingEngine.calculateWarningThresholdsMs(1 * MIN))
    }

    @Test
    fun thresholds_zeroAndNegative_clampToZero() {
        assertEquals(0L to 0L, UsageAccountingEngine.calculateWarningThresholdsMs(0L))
        assertEquals(0L to 0L, UsageAccountingEngine.calculateWarningThresholdsMs(-5 * MIN))
    }

    // --- processUsageObservation (§12: each threshold boundary, rollover ordering) ---

    @Test
    fun warningLifecycle_pendingRetriesThenDelivered_thenBlockAtLimit() = runTest {
        assertEquals(NoAction, engine.processUsageObservation(PKG, 19 * MIN + 59 * SEC, 10 * SEC, UsageLedgerSource.PATH_A))
        assertEquals(ShowWarning(1, 10 * MIN), engine.processUsageObservation(PKG, 1 * SEC, 20 * SEC, UsageLedgerSource.PATH_A))
        // Undelivered → retried on the next observation (Decision 14).
        assertEquals(ShowWarning(1, 10 * MIN), engine.processUsageObservation(PKG, 0L, 30 * SEC, UsageLedgerSource.PATH_A))
        engine.markWarningDelivered(PKG, 1)
        engine.markWarningDelivered(PKG, 1)
        assertEquals(WarningState.DELIVERED, trackerRow()!!.warning1State)
        assertEquals(NoAction, engine.processUsageObservation(PKG, 4 * MIN + 59 * SEC, 40 * SEC, UsageLedgerSource.PATH_A))
        assertEquals(ShowWarning(2, 5 * MIN), engine.processUsageObservation(PKG, 1 * SEC, 50 * SEC, UsageLedgerSource.PATH_A))
        engine.markWarningDelivered(PKG, 2)
        assertEquals(NoAction, engine.processUsageObservation(PKG, 4 * MIN + 59 * SEC, 60 * SEC, UsageLedgerSource.PATH_A))
        assertEquals(Block, engine.processUsageObservation(PKG, 1 * SEC, 70 * SEC, UsageLedgerSource.PATH_A))
        assertEquals(true, trackerRow()!!.blockedToday)
        // Above the limit every observation keeps returning Block (Step 4 re-arm contract).
        assertEquals(Block, engine.processUsageObservation(PKG, 0L, 80 * SEC, UsageLedgerSource.PATH_A))
    }

    @Test
    fun rolloverRunsBeforeEvaluation_discardingStaleDay() = runTest {
        seedTracker(today.minusDays(1).toString(), msUsed = 29 * MIN, blocked = true)
        // Without rollover-first this observation would be 29+1 = 30 min → Block.
        assertEquals(NoAction, engine.processUsageObservation(PKG, 1 * MIN, 10 * SEC, UsageLedgerSource.PATH_A))
        val row = trackerRow()!!
        assertEquals(1 * MIN, row.msUsedToday)
        assertEquals(today.toString(), row.localDateAnchor)
        assertEquals(false, row.blockedToday)
        assertEquals(WarningState.NOT_DUE, row.warning1State)
        assertEquals(WarningState.NOT_DUE, row.warning2State)
    }

    @Test
    fun rollover_anyAnchorMismatchResets_coveredByTimezoneMoveCase() = runTest {
        // Decision 20: any stored-vs-current date mismatch rolls, regardless of direction.
        seedTracker(today.plusDays(1).toString(), msUsed = 5 * MIN)
        assertEquals(NoAction, engine.processUsageObservation(PKG, 2 * MIN, 10 * SEC, UsageLedgerSource.PATH_A))
        assertEquals(2 * MIN, trackerRow()!!.msUsedToday)
    }

    @Test
    fun callerSessionRestart_pattern_countsEachDeltaExactlyOnce() = runTest {
        var sessionStart = 0L
        var now = 0L
        repeat(3) {
            now += 10 * SEC
            assertEquals(NoAction, engine.processUsageObservation(PKG, now - sessionStart, now, UsageLedgerSource.PATH_A))
            sessionStart = now
        }
        // Incremental deltas sum to 30s; a double-counting caller would record 10+20+30 = 60s.
        assertEquals(30 * SEC, trackerRow()!!.msUsedToday)
    }

    // --- Mid-day limit change (Decision 15, §12 both directions) ---

    @Test
    fun midDayLimitChange_lowerBlocksImmediately_raiseUnblocks() = runTest {
        engine.processUsageObservation(PKG, 29 * MIN, 10 * SEC, UsageLedgerSource.PATH_A)
        setLimit(20 * MIN)
        engine.onDailyLimitChanged(PKG)
        assertEquals(true, trackerRow()!!.blockedToday)
        assertEquals(Block, engine.processUsageObservation(PKG, 0L, 20 * SEC, UsageLedgerSource.PATH_A))

        setLimit(35 * MIN)
        engine.onDailyLimitChanged(PKG)
        val row = trackerRow()!!
        assertEquals(false, row.blockedToday)
        assertEquals(WarningState.NOT_DUE, row.warning1State)
        assertEquals(WarningState.NOT_DUE, row.warning2State)
        // New thresholds are 25/30 min: 29 min of usage is past the first one again —
        // and the remaining-budget copy reflects the new limit (6 min of 35).
        assertEquals(ShowWarning(1, 6 * MIN), engine.processUsageObservation(PKG, 0L, 30 * SEC, UsageLedgerSource.PATH_A))
    }

    @Test
    fun limitRaised_recomputesThresholds_notJustClearsState() = runTest {
        engine.processUsageObservation(PKG, 29 * MIN, 10 * SEC, UsageLedgerSource.PATH_A)
        setLimit(60 * MIN)
        engine.onDailyLimitChanged(PKG)
        // New thresholds are 50/55 min — 29 min must not re-warn (proves the cache recomputed).
        assertEquals(NoAction, engine.processUsageObservation(PKG, 0L, 20 * SEC, UsageLedgerSource.PATH_A))
    }

    // --- §9 error-table rows reachable from the engine ---

    @Test
    fun nonPositiveLimit_blocksImmediately() = runTest {
        setLimit(0L)
        assertEquals(Block, engine.processUsageObservation(PKG, 1 * SEC, 10 * SEC, UsageLedgerSource.PATH_A))
        assertEquals(true, trackerRow()!!.blockedToday)
        setLimit(-5 * MIN)
        assertEquals(Block, engine.processUsageObservation(PKG, 0L, 20 * SEC, UsageLedgerSource.PATH_A))
    }

    @Test
    fun untrackedPackage_noActionAndNoRowCreated() = runTest {
        assertEquals(NoAction, engine.processUsageObservation("com.other.app", 5 * SEC, 10 * SEC, UsageLedgerSource.PATH_A))
        assertNull(db.dailyUsageTrackerDao().findByPackage(PROFILE_ID, "com.other.app"))
    }

    @Test
    fun disabledRule_isUntracked_noAccumulation() = runTest {
        val rule = db.restrictionRuleDao().getById(RULE_ID)!!
        db.restrictionRuleDao().update(rule.copy(isEnabled = false))
        assertEquals(NoAction, engine.processUsageObservation(PKG, 5 * SEC, 10 * SEC, UsageLedgerSource.PATH_A))
        assertNull(trackerRow())
    }

    /** Finding #5 (2026-09-16): an override sets blockExpiresAt to the past while the rule
     *  stays enabled — the row then reads "Expired", so nothing about the rule may still
     *  accrue, warn, or wall. */
    @Test
    fun expiredRule_isUntracked_noAccumulationNoWarning() = runTest {
        val rule = db.restrictionRuleDao().getById(RULE_ID)!!
        db.restrictionRuleDao().update(rule.copy(blockExpiresAt = wallNowMs - 1))
        seedTracker(anchor = today.toString(), msUsed = 29 * MIN)

        // Past the level-1 threshold: a tracked rule would return ShowWarning here.
        assertEquals(NoAction, engine.processUsageObservation(PKG, 1 * SEC, 10 * SEC, UsageLedgerSource.PATH_A))
        assertEquals(29 * MIN, trackerRow()!!.msUsedToday)
        assertEquals(false, trackerRow()!!.blockedToday)
        assertNull(engine.remainingBudgetMs(PKG))
    }

    // --- rule day scope (user decision 2026-09-20: days bound the whole rule) ---

    @Test
    fun inactiveDay_noAccrualNoBlock_evenAboveTheLimit() = runTest {
        setDayScope(daysExceptToday())
        // 45 min against a 30 min limit: on an active day this is Block — on an inactive day
        // the rule does not operate, so nothing accrues, nothing blocks, and no row is even
        // opened for the day.
        assertEquals(NoAction, engine.processUsageObservation(PKG, 45 * MIN, 10 * SEC, UsageLedgerSource.PATH_A))
        assertNull(trackerRow())
    }

    @Test
    fun activeDay_stillAccruesAndBlocks() = runTest {
        setDayScope(daysOnlyToday())
        assertEquals(Block, engine.processUsageObservation(PKG, 45 * MIN, 10 * SEC, UsageLedgerSource.PATH_A))
        assertEquals(45 * MIN, trackerRow()!!.msUsedToday)
    }

    @Test
    fun inactiveDay_stillRollsAStaleRow_withoutAccruing() = runTest {
        setDayScope(daysExceptToday())
        seedTracker(today.minusDays(1).toString(), msUsed = 29 * MIN, blocked = true)
        assertEquals(NoAction, engine.processUsageObservation(PKG, 1 * MIN, 10 * SEC, UsageLedgerSource.PATH_A))
        val row = trackerRow()!!
        assertEquals(today.toString(), row.localDateAnchor)
        assertEquals(0L, row.msUsedToday)
        assertEquals(false, row.blockedToday)
    }

    // --- rolloverStaleDays (§8 Step 8 daily-maintenance sweep) ---

    @Test
    fun rolloverStaleDays_resetsStaleState_leavesCurrentDayRowUntouched() = runTest {
        db.dailyUsageTrackerDao().insert(
            DailyUsageTracker(
                trackerId = "stale", profileId = PROFILE_ID, packageName = PKG,
                localDateAnchor = today.minusDays(1).toString(),
                timezoneIdAtAnchor = "UTC", msUsedToday = 29 * MIN,
                warning1State = WarningState.DELIVERED, warning2State = WarningState.PENDING_DELIVERY,
                blockedToday = true,
            ),
        )
        db.dailyUsageTrackerDao().insert(
            DailyUsageTracker(
                trackerId = "today", profileId = PROFILE_ID, packageName = TODAY_PKG,
                localDateAnchor = today.toString(),
                timezoneIdAtAnchor = "UTC", msUsedToday = 5 * MIN,
                warning1State = WarningState.DELIVERED, warning2State = WarningState.NOT_DUE,
                blockedToday = true,
            ),
        )

        assertEquals(1, engine.rolloverStaleDays())

        val rolled = db.dailyUsageTrackerDao().findByPackage(PROFILE_ID, PKG)!!
        assertEquals(0L, rolled.msUsedToday)
        assertEquals(today.toString(), rolled.localDateAnchor)
        assertEquals(false, rolled.blockedToday)
        assertEquals(WarningState.NOT_DUE, rolled.warning1State)
        assertEquals(WarningState.NOT_DUE, rolled.warning2State)

        val current = db.dailyUsageTrackerDao().findByPackage(PROFILE_ID, TODAY_PKG)!!
        assertEquals(5 * MIN, current.msUsedToday)
        assertEquals(true, current.blockedToday)
        assertEquals(WarningState.DELIVERED, current.warning1State)

        // Second run rolls nothing (idempotent — the sweep may fire twice around the boundary).
        assertEquals(0, engine.rolloverStaleDays())
    }

    // --- reconcileWithPlatform (OPD #28(a): seed from the platform's own number) ---

    @Test
    fun reconcile_seedsFreshTracker_andBlocksWhenPlatformIsOverLimit() = runTest {
        // The device-caught shape: no tracker row (rule was paused / just created), platform
        // reports 45 min against a 30 min limit.
        engine.reconcileWithPlatform(PKG, 45 * MIN)
        val row = trackerRow()!!
        assertEquals(45 * MIN, row.msUsedToday)
        assertEquals(true, row.blockedToday)
        assertEquals(WarningState.NOT_DUE, row.warning1State)
        // The next observation is past the limit and keeps the block armed.
        assertEquals(Block, engine.processUsageObservation(PKG, 0L, 10 * SEC, UsageLedgerSource.PATH_A))
    }

    @Test
    fun reconcile_isMonotone_lowerPlatformNeverLowersUsageOrUnblocks() = runTest {
        engine.processUsageObservation(PKG, 20 * MIN, 10 * SEC, UsageLedgerSource.PATH_A)
        engine.reconcileWithPlatform(PKG, 45 * MIN)
        assertEquals(45 * MIN, trackerRow()!!.msUsedToday)
        // A second read (or a stale one) below the credited amount must not roll it back —
        // the platform number is absolute, the live paths' credit is already in it.
        engine.reconcileWithPlatform(PKG, 10 * MIN)
        assertEquals(45 * MIN, trackerRow()!!.msUsedToday)
        assertEquals(true, trackerRow()!!.blockedToday)
    }

    @Test
    fun reconcile_repeatedTopUps_sameBoot_eachDeltaLands() = runTest {
        // Owner-caught 2026-10-20: usage spent while a rule is paused is invisible to the live
        // paths, so every re-enable must carry the platform delta. Each top-up after the first
        // used to re-derive the first one's zero-interval id, collide, and silently credit 0 —
        // a repeated toggle permanently lost the paused time until the next boot.
        engine.reconcileWithPlatform(PKG, 45 * MIN)
        engine.reconcileWithPlatform(PKG, 62 * MIN)
        engine.reconcileWithPlatform(PKG, 71 * MIN)

        assertEquals(71 * MIN, trackerRow()!!.msUsedToday)
        val carryovers = db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.MIGRATION_CARRYOVER, 0L)
        assertEquals(listOf(9 * MIN, 17 * MIN, 45 * MIN), carryovers.map { it.creditedMs }.sorted())
        // Projection stays equal to the journal (§8.3): a rebuild rewrites nothing.
        assertEquals(0, ledger.rebuildProjection())
    }

    @Test
    fun reconcile_nullOrZeroPlatform_noOpNoRowCreated() = runTest {
        engine.reconcileWithPlatform(PKG, null)
        engine.reconcileWithPlatform(PKG, 0L)
        assertNull(trackerRow())
    }

    @Test
    fun reconcile_inactiveDayScope_neverSeeds() = runTest {
        setDayScope(daysExceptToday())
        engine.reconcileWithPlatform(PKG, 45 * MIN)
        assertNull(trackerRow())
    }

    @Test
    fun reconcile_staleRow_rollsThenSeeds() = runTest {
        seedTracker(today.minusDays(1).toString(), msUsed = 29 * MIN, blocked = true)
        engine.reconcileWithPlatform(PKG, 45 * MIN)
        val row = trackerRow()!!
        assertEquals(today.toString(), row.localDateAnchor)
        assertEquals(45 * MIN, row.msUsedToday)
        assertEquals(true, row.blockedToday)
    }

    @Test
    fun reconcile_disabledRuleOrNoLimit_isUntracked() = runTest {
        val rule = db.restrictionRuleDao().getById(RULE_ID)!!
        db.restrictionRuleDao().update(rule.copy(isEnabled = false))
        engine.reconcileWithPlatform(PKG, 45 * MIN)
        assertNull(trackerRow())

        db.restrictionRuleDao().update(rule.copy(dailyLimitMs = 0L))
        engine.reconcileWithPlatform(PKG, 45 * MIN)
        assertNull(trackerRow())
    }

    // --- creditReconciledWindow (§8.9/§8.11: platform-derived recovery as RECONCILIATION) ---

    @Test
    fun reconciledWindow_creditsAsReconciliation_andTheRebuildReproducesIt() = runTest {
        val credited = engine.creditReconciledWindow(PKG, 100_000L, 100_000L + 5 * MIN, 5 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs)

        assertEquals(5 * MIN, credited)
        assertEquals(5 * MIN, trackerRow()!!.msUsedToday)
        val row = db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.RECONCILIATION, 0L).single()
        assertEquals(UsageLedgerKind.RECONCILIATION, row.kind)
        assertEquals(5 * MIN, row.creditedMs)
        assertEquals(100_000L, row.elapsedStartMs)
        // §8.13: RECONCILIATION is in the resolving set, so the projection it advanced is
        // exactly what a rebuild re-derives.
        db.dailyUsageTrackerDao().update(trackerRow()!!.copy(msUsedToday = 0L))
        assertEquals(1, ledger.rebuildProjection())
        assertEquals(5 * MIN, trackerRow()!!.msUsedToday)
    }

    @Test
    fun reconciledWindow_overlappingAccountingRow_creditsOnlyTheUncoveredRemainder() = runTest {
        // A live observation already credited [300_000, 600_000) — the first half of the
        // recovered window.
        engine.processUsageObservation(PKG, 5 * MIN, 600_000L, UsageLedgerSource.PATH_A)

        val credited = engine.creditReconciledWindow(PKG, 300_000L, 900_000L, 10 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs)

        // Half the window was already accounted → half the foreground credit survives.
        assertEquals(5 * MIN, credited)
        assertEquals(10 * MIN, trackerRow()!!.msUsedToday)
    }

    @Test
    fun reconciledWindow_fullyCoveredWindow_creditsNothingAndWritesNoRow() = runTest {
        engine.processUsageObservation(PKG, 5 * MIN, 600_000L, UsageLedgerSource.PATH_A)

        val credited = engine.creditReconciledWindow(PKG, 300_000L, 600_000L, 5 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs)

        assertEquals(0L, credited)
        assertEquals(5 * MIN, trackerRow()!!.msUsedToday)
        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.RECONCILIATION, 0L).isEmpty())
    }

    @Test
    fun reconciledWindow_rerunInsideTheCreditedSpan_addsNothing() = runTest {
        engine.creditReconciledWindow(PKG, 1_000_000L, 1_000_000L + 5 * MIN, 4 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs)

        // The same recovery re-expressed with tighter bounds: covered, so nothing — the
        // overlap check (not the event id) is what absorbs it.
        val rerun = engine.creditReconciledWindow(PKG, 1_010_000L, 1_000_000L + 4 * MIN, 4 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs)

        assertEquals(0L, rerun)
        assertEquals(4 * MIN, trackerRow()!!.msUsedToday)
    }

    @Test
    fun reconciledWindow_canDeliverTheVerdictItsOutageSwallowed() = runTest {
        seedTracker(anchor = today.toString(), msUsed = 29 * MIN)

        val credited = engine.creditReconciledWindow(PKG, 0L, 2 * MIN, 2 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs)

        assertEquals(2 * MIN, credited)
        // 31 min against the 30 min limit: the recovered credit re-derives the block and
        // journals the verdict change exactly as a live observation would.
        assertEquals(true, trackerRow()!!.blockedToday)
        assertEquals(1, db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.RULE_EVALUATION, 0L).size)
    }

    @Test
    fun reconciledWindow_staleRow_rollsThenCredits() = runTest {
        seedTracker(anchor = today.minusDays(1).toString(), msUsed = 29 * MIN, blocked = true)

        assertEquals(4 * MIN, engine.creditReconciledWindow(PKG, 0L, 4 * MIN, 4 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs))

        val row = trackerRow()!!
        assertEquals(today.toString(), row.localDateAnchor)
        assertEquals(4 * MIN, row.msUsedToday)
        assertEquals(false, row.blockedToday)
    }

    @Test
    fun reconciledWindow_completingAfterMidnight_neverCreditsYesterdaysUsageIntoToday() = runTest {
        // §8.11/ADR #94: "a credit that completes today can never hand today a budget that
        // yesterday's usage should have consumed". The window was clipped to the pass day
        // (23:59:59) and the credit transaction lands just after midnight: the journal keeps
        // the row on the day the time occurred, the fresh tracker must stay at zero.
        val windowEnd = today.plusDays(1).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli() - 1_000L
        engineNowMs = windowEnd + 61_000L

        val credited = engine.creditReconciledWindow(
            PKG, 10 * MIN, 15 * MIN, 5 * MIN, UsageLedgerSource.MAINTENANCE, windowEnd,
        )

        assertEquals(0L, credited)
        val tracker = trackerRow()!!
        assertEquals(today.plusDays(1).toString(), tracker.localDateAnchor)
        assertEquals(0L, tracker.msUsedToday)
        val row = db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.RECONCILIATION, 0L).single()
        assertEquals(today.toString(), row.dayKey)
        assertEquals(5 * MIN, row.creditedMs)
    }

    @Test
    fun reconciledWindow_ignoresRowsFromAnotherBootsElapsedFrame() = runTest {
        // §8.5: elapsed values reset every boot, so an old frame's numbers landing in the same
        // range are a coincidence, not coverage — the credit must not be scaled down by them.
        ledger.appendStandalone(
            LedgerEventDraft(
                kind = UsageLedgerKind.ACCOUNTING,
                packageName = PKG,
                creditedMs = 5 * MIN,
                elapsedStartMs = 300_000L,
                elapsedEndMs = 600_000L,
                bootCount = BOOT - 1,
                source = UsageLedgerSource.PATH_A,
                wallClockTsMs = wallNowMs - 60_000L,
            ),
        )

        val credited = engine.creditReconciledWindow(PKG, 300_000L, 900_000L, 10 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs)

        assertEquals(10 * MIN, credited)
    }

    @Test
    fun reconciledWindow_untrackedOrDegenerateInputs_creditNothing() = runTest {
        // Zero/negative foreground or a non-positive span is not a recovery.
        assertEquals(0L, engine.creditReconciledWindow(PKG, 0L, 4 * MIN, 0L, UsageLedgerSource.MAINTENANCE, wallNowMs))
        assertEquals(0L, engine.creditReconciledWindow(PKG, 4 * MIN, 4 * MIN, 4 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs))
        // Untracked package, disabled rule, inactive day scope: no accrual, no row.
        assertEquals(0L, engine.creditReconciledWindow("com.other.app", 0L, 4 * MIN, 4 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs))
        val rule = db.restrictionRuleDao().getById(RULE_ID)!!
        db.restrictionRuleDao().update(rule.copy(isEnabled = false))
        assertEquals(0L, engine.creditReconciledWindow(PKG, 0L, 4 * MIN, 4 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs))
        db.restrictionRuleDao().update(rule.copy(isEnabled = true))
        setDayScope(daysExceptToday())
        assertEquals(0L, engine.creditReconciledWindow(PKG, 0L, 4 * MIN, 4 * MIN, UsageLedgerSource.MAINTENANCE, wallNowMs))
        assertNull(trackerRow())
        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.RECONCILIATION, 0L).isEmpty())
    }

    // --- lastCreditWallPerPackage (ADR #160's credit edge) ---------------------------------

    @Test
    fun lastCreditWallPerPackage_carryoverAnchorsTheEdgeToo() = runTest {
        creditRow(UsageLedgerKind.ACCOUNTING, wallNowMs - 10 * MIN)
        creditRow(UsageLedgerKind.RECONCILIATION, wallNowMs - 5 * MIN)
        // ADR #191: a carryover owns no interval, but its absolute platform total answers for
        // everything through its wall — it is the newest credit and must anchor the audit.
        creditRow(UsageLedgerKind.MIGRATION_CARRYOVER, wallNowMs - MIN)

        assertEquals(
            mapOf(PKG to (wallNowMs - MIN)),
            engine.lastCreditWallPerPackage(listOf(PKG), sinceWallClockMs = 0L),
        )
    }

    @Test
    fun lastCreditWallPerPackage_evidenceRowsDoNotAnchor() = runTest {
        // ADR #156's trap in one assertion: heartbeats prove liveness, never a credit — a
        // package whose only rows today are evidence must come back with no anchor.
        creditRow(UsageLedgerKind.HEARTBEAT, wallNowMs - MIN)
        creditRow(UsageLedgerKind.GAP_DETECTED, wallNowMs - MIN)
        creditRow(UsageLedgerKind.RULE_EVALUATION, wallNowMs - MIN)

        assertTrue(engine.lastCreditWallPerPackage(listOf(PKG), sinceWallClockMs = 0L).isEmpty())
    }

    @Test
    fun carryoverAdvancesTheCreditEdge_soTheRepairCannotRecreditThePausedWindow() = runTest {
        // Device catch 2026-10-20 (ADR #191): pause, use the app, re-enable — the carryover
        // credits the paused total, then the FGS audit anchored at the pre-pause ACCOUNTING
        // edge and re-credited 164,998 ms of the same paused windows (tracker ended 164,998 ms
        // ahead of Digital Wellbeing). With the carryover as the anchor, the repair window
        // begins after it and the paused marks fall outside.
        val pausedFrom = wallNowMs - 30 * MIN
        creditRow(UsageLedgerKind.ACCOUNTING, pausedFrom)
        val marks = listOf(
            UsageMark(pausedFrom + 5 * MIN, true),
            UsageMark(pausedFrom + 10 * MIN, false),
        )
        engine.reconcileWithPlatform(PKG, 45 * MIN)

        val edge = engine.lastCreditWallPerPackage(listOf(PKG), sinceWallClockMs = 0L)[PKG]!!
        assertEquals(wallNowMs, edge)
        val window = UsageGapRepair.uncreditedWindow(
            lastCreditWallMs = edge,
            localMidnightEpochMillis = 0L,
            bootOriginWallMs = 0L,
            resumeWallMs = wallNowMs + MIN,
            stopWallMs = pausedFrom,
        )!!
        assertEquals(0L, UsageGapRepair.foregroundMsWithin(marks, window.first, window.last + 1))
    }

    @Test
    fun lastCreditWallPerPackage_respectsTheWallCutoffAndThePackageScope() = runTest {
        creditRow(UsageLedgerKind.ACCOUNTING, wallNowMs - 30 * MIN)
        creditRow(UsageLedgerKind.ACCOUNTING, wallNowMs - MIN, packageName = TODAY_PKG)

        // The cutoff (midnight-at-the-call-site) hides the stale credit; only requested
        // packages appear in the map.
        assertEquals(
            mapOf(TODAY_PKG to (wallNowMs - MIN)),
            engine.lastCreditWallPerPackage(listOf(PKG, TODAY_PKG), sinceWallClockMs = wallNowMs - 20 * MIN),
        )
    }

    @Test
    fun reconciledWindow_edgeStampsTheAnsweredEnd_neverTheWriteMoment() = runTest {
        // Device leg 2 (2026-10-15): a service-start audit ran mid-pause and credited the
        // pre-stop tail. Stamped with the write moment (that row carried 23:45:00), its edge
        // landed 11 s past the user's stop (23:44:49), the stop clip could no longer anchor,
        // and the re-enable audit claimed the paused era — a 336.8 s window where the pause
        // promise says none. Stamped with the answered window end, the clip still bites.
        val stopWall = wallNowMs - 3 * MIN
        val credited = engine.creditReconciledWindow(
            PKG,
            100_000L,
            100_000L + 5 * MIN,
            5 * MIN,
            UsageLedgerSource.MAINTENANCE,
            answeredThroughWallMs = stopWall,
        )

        assertEquals(5 * MIN, credited)
        assertEquals(stopWall, engine.lastCreditWallPerPackage(listOf(PKG), 0L)[PKG])
        // The release audit's span, built exactly as the FGS builds it: edge == stop ⇒ the
        // clip empties the window ⇒ nothing to repair, the paused era stays unclaimable.
        assertNull(
            UsageGapRepair.uncreditedWindow(
                lastCreditWallMs = stopWall,
                localMidnightEpochMillis = 0L,
                bootOriginWallMs = 0L,
                resumeWallMs = wallNowMs,
                stopWallMs = stopWall,
            ),
        )
    }

    // --- Helpers ---

    /** One journal row with explicit kind and wall — the audit's raw material. Each kind gets
     *  its own elapsed interval because §8.6's event identity is (source, package, interval,
     *  boot) — kind is deliberately not part of it, so same-interval rows of different kinds
     *  would collide, which distinct real observations never do. The landed-ness assert is the
     *  scar: a silently collision-dropped fixture made an evidence-kinds test pass while only
     *  one of its three rows existed. */
    private suspend fun creditRow(
        kind: Int,
        wallMs: Long,
        packageName: String = PKG,
    ) {
        val outcome = ledger.appendStandalone(
            LedgerEventDraft(
                kind = kind,
                packageName = packageName,
                creditedMs = if (kind == UsageLedgerKind.ACCOUNTING || kind == UsageLedgerKind.RECONCILIATION) MIN else 0L,
                elapsedStartMs = 0L,
                // MIGRATION_CARRYOVER's real shape: a zero-length interval (it owns no span).
                elapsedEndMs = if (kind == UsageLedgerKind.MIGRATION_CARRYOVER) 0L else MIN * (kind + 1),
                bootCount = BOOT,
                source = UsageLedgerSource.PATH_A,
                wallClockTsMs = wallMs,
            ),
        )
        assertTrue("row rejected: $outcome", outcome is AppendOutcome.Written)
    }

    private suspend fun trackerRow(): DailyUsageTracker? =
        db.dailyUsageTrackerDao().findByPackage(PROFILE_ID, PKG)

    private suspend fun seedTracker(anchor: String, msUsed: Long, blocked: Boolean = false) {
        db.dailyUsageTrackerDao().insert(
            DailyUsageTracker(
                trackerId = "seed-1", profileId = PROFILE_ID, packageName = PKG,
                localDateAnchor = anchor, timezoneIdAtAnchor = "UTC", msUsedToday = msUsed,
                warning1State = WarningState.NOT_DUE, warning2State = WarningState.NOT_DUE,
                blockedToday = blocked,
            ),
        )
    }

    private suspend fun setLimit(limitMs: Long) {
        val rule = db.restrictionRuleDao().getById(RULE_ID)!!
        db.restrictionRuleDao().update(rule.copy(dailyLimitMs = limitMs))
    }

    /** Sets the rule's day scope to the given days, in the device zone — the zone these tests
     *  compute "today" in, so the day scope and the assertion cannot disagree near midnight. */
    private suspend fun setDayScope(days: String) {
        val rule = db.restrictionRuleDao().getById(RULE_ID)!!
        db.restrictionRuleDao().update(rule.copy(activeDaysOfWeek = days, timezone = ZoneId.systemDefault().id))
    }

    private fun daysExceptToday(): String = dayString(invert = true)

    private fun daysOnlyToday(): String = dayString(invert = false)

    private fun dayString(invert: Boolean): String {
        val todayIndex = today.dayOfWeek.value - 1
        return (0..6).joinToString("") { index -> if ((index == todayIndex) != invert) "1" else "0" }
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PROFILE_ID = "local"
        const val RULE_ID = "r-1"
        const val PKG = "com.instagram.android"
        const val TODAY_PKG = "com.today.app"
        const val SEC = 1_000L
        const val MIN = 60_000L
        const val BOOT = 7
    }
}
