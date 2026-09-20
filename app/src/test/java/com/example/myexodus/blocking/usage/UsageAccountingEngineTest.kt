package com.example.myexodus.blocking.usage

import android.app.Application
import android.content.Context
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
import com.example.myexodus.data.db.entity.UserProfile
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private lateinit var engine: UsageAccountingEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        val factory = AppRulesStoreFactory(context, recovery, healthStore)
        db = factory.open()!!
        engine = UsageAccountingEngine(
            factory,
            Dispatchers.IO.limitedParallelism(1),
            com.example.myexodus.telemetry.CrashReporting(context),
        )
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
        assertEquals(NoAction, engine.processUsageObservation(PKG, 19 * MIN + 59 * SEC, 10 * SEC))
        assertEquals(ShowWarning(1, 10 * MIN), engine.processUsageObservation(PKG, 1 * SEC, 20 * SEC))
        // Undelivered → retried on the next observation (Decision 14).
        assertEquals(ShowWarning(1, 10 * MIN), engine.processUsageObservation(PKG, 0L, 30 * SEC))
        engine.markWarningDelivered(PKG, 1)
        engine.markWarningDelivered(PKG, 1)
        assertEquals(WarningState.DELIVERED, trackerRow()!!.warning1State)
        assertEquals(NoAction, engine.processUsageObservation(PKG, 4 * MIN + 59 * SEC, 40 * SEC))
        assertEquals(ShowWarning(2, 5 * MIN), engine.processUsageObservation(PKG, 1 * SEC, 50 * SEC))
        engine.markWarningDelivered(PKG, 2)
        assertEquals(NoAction, engine.processUsageObservation(PKG, 4 * MIN + 59 * SEC, 60 * SEC))
        assertEquals(Block, engine.processUsageObservation(PKG, 1 * SEC, 70 * SEC))
        assertEquals(true, trackerRow()!!.blockedToday)
        // Above the limit every observation keeps returning Block (Step 4 re-arm contract).
        assertEquals(Block, engine.processUsageObservation(PKG, 0L, 80 * SEC))
    }

    @Test
    fun rolloverRunsBeforeEvaluation_discardingStaleDay() = runTest {
        seedTracker(LocalDate.now().minusDays(1).toString(), msUsed = 29 * MIN, blocked = true)
        // Without rollover-first this observation would be 29+1 = 30 min → Block.
        assertEquals(NoAction, engine.processUsageObservation(PKG, 1 * MIN, 10 * SEC))
        val row = trackerRow()!!
        assertEquals(1 * MIN, row.msUsedToday)
        assertEquals(LocalDate.now().toString(), row.localDateAnchor)
        assertEquals(false, row.blockedToday)
        assertEquals(WarningState.NOT_DUE, row.warning1State)
        assertEquals(WarningState.NOT_DUE, row.warning2State)
    }

    @Test
    fun rollover_anyAnchorMismatchResets_coveredByTimezoneMoveCase() = runTest {
        // Decision 20: any stored-vs-current date mismatch rolls, regardless of direction.
        seedTracker(LocalDate.now().plusDays(1).toString(), msUsed = 5 * MIN)
        assertEquals(NoAction, engine.processUsageObservation(PKG, 2 * MIN, 10 * SEC))
        assertEquals(2 * MIN, trackerRow()!!.msUsedToday)
    }

    @Test
    fun callerSessionRestart_pattern_countsEachDeltaExactlyOnce() = runTest {
        var sessionStart = 0L
        var now = 0L
        repeat(3) {
            now += 10 * SEC
            assertEquals(NoAction, engine.processUsageObservation(PKG, now - sessionStart, now))
            sessionStart = now
        }
        // Incremental deltas sum to 30s; a double-counting caller would record 10+20+30 = 60s.
        assertEquals(30 * SEC, trackerRow()!!.msUsedToday)
    }

    // --- Mid-day limit change (Decision 15, §12 both directions) ---

    @Test
    fun midDayLimitChange_lowerBlocksImmediately_raiseUnblocks() = runTest {
        engine.processUsageObservation(PKG, 29 * MIN, 10 * SEC)
        setLimit(20 * MIN)
        engine.onDailyLimitChanged(PKG)
        assertEquals(true, trackerRow()!!.blockedToday)
        assertEquals(Block, engine.processUsageObservation(PKG, 0L, 20 * SEC))

        setLimit(35 * MIN)
        engine.onDailyLimitChanged(PKG)
        val row = trackerRow()!!
        assertEquals(false, row.blockedToday)
        assertEquals(WarningState.NOT_DUE, row.warning1State)
        assertEquals(WarningState.NOT_DUE, row.warning2State)
        // New thresholds are 25/30 min: 29 min of usage is past the first one again —
        // and the remaining-budget copy reflects the new limit (6 min of 35).
        assertEquals(ShowWarning(1, 6 * MIN), engine.processUsageObservation(PKG, 0L, 30 * SEC))
    }

    @Test
    fun limitRaised_recomputesThresholds_notJustClearsState() = runTest {
        engine.processUsageObservation(PKG, 29 * MIN, 10 * SEC)
        setLimit(60 * MIN)
        engine.onDailyLimitChanged(PKG)
        // New thresholds are 50/55 min — 29 min must not re-warn (proves the cache recomputed).
        assertEquals(NoAction, engine.processUsageObservation(PKG, 0L, 20 * SEC))
    }

    // --- §9 error-table rows reachable from the engine ---

    @Test
    fun nonPositiveLimit_blocksImmediately() = runTest {
        setLimit(0L)
        assertEquals(Block, engine.processUsageObservation(PKG, 1 * SEC, 10 * SEC))
        assertEquals(true, trackerRow()!!.blockedToday)
        setLimit(-5 * MIN)
        assertEquals(Block, engine.processUsageObservation(PKG, 0L, 20 * SEC))
    }

    @Test
    fun untrackedPackage_noActionAndNoRowCreated() = runTest {
        assertEquals(NoAction, engine.processUsageObservation("com.other.app", 5 * SEC, 10 * SEC))
        assertNull(db.dailyUsageTrackerDao().findByPackage(PROFILE_ID, "com.other.app"))
    }

    @Test
    fun disabledRule_isUntracked_noAccumulation() = runTest {
        val rule = db.restrictionRuleDao().getById(RULE_ID)!!
        db.restrictionRuleDao().update(rule.copy(isEnabled = false))
        assertEquals(NoAction, engine.processUsageObservation(PKG, 5 * SEC, 10 * SEC))
        assertNull(trackerRow())
    }

    /** Finding #5 (2026-09-16): an override sets blockExpiresAt to the past while the rule
     *  stays enabled — the row then reads "Expired", so nothing about the rule may still
     *  accrue, warn, or wall. */
    @Test
    fun expiredRule_isUntracked_noAccumulationNoWarning() = runTest {
        val rule = db.restrictionRuleDao().getById(RULE_ID)!!
        db.restrictionRuleDao().update(rule.copy(blockExpiresAt = System.currentTimeMillis() - 1))
        seedTracker(anchor = LocalDate.now().toString(), msUsed = 29 * MIN)

        // Past the level-1 threshold: a tracked rule would return ShowWarning here.
        assertEquals(NoAction, engine.processUsageObservation(PKG, 1 * SEC, 10 * SEC))
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
        assertEquals(NoAction, engine.processUsageObservation(PKG, 45 * MIN, 10 * SEC))
        assertNull(trackerRow())
    }

    @Test
    fun activeDay_stillAccruesAndBlocks() = runTest {
        setDayScope(daysOnlyToday())
        assertEquals(Block, engine.processUsageObservation(PKG, 45 * MIN, 10 * SEC))
        assertEquals(45 * MIN, trackerRow()!!.msUsedToday)
    }

    @Test
    fun inactiveDay_stillRollsAStaleRow_withoutAccruing() = runTest {
        setDayScope(daysExceptToday())
        seedTracker(LocalDate.now().minusDays(1).toString(), msUsed = 29 * MIN, blocked = true)
        assertEquals(NoAction, engine.processUsageObservation(PKG, 1 * MIN, 10 * SEC))
        val row = trackerRow()!!
        assertEquals(LocalDate.now().toString(), row.localDateAnchor)
        assertEquals(0L, row.msUsedToday)
        assertEquals(false, row.blockedToday)
    }

    // --- rolloverStaleDays (§8 Step 8 daily-maintenance sweep) ---

    @Test
    fun rolloverStaleDays_resetsStaleState_leavesCurrentDayRowUntouched() = runTest {
        db.dailyUsageTrackerDao().insert(
            DailyUsageTracker(
                trackerId = "stale", profileId = PROFILE_ID, packageName = PKG,
                localDateAnchor = LocalDate.now().minusDays(1).toString(),
                timezoneIdAtAnchor = "UTC", msUsedToday = 29 * MIN,
                warning1State = WarningState.DELIVERED, warning2State = WarningState.PENDING_DELIVERY,
                blockedToday = true,
            ),
        )
        db.dailyUsageTrackerDao().insert(
            DailyUsageTracker(
                trackerId = "today", profileId = PROFILE_ID, packageName = TODAY_PKG,
                localDateAnchor = LocalDate.now().toString(),
                timezoneIdAtAnchor = "UTC", msUsedToday = 5 * MIN,
                warning1State = WarningState.DELIVERED, warning2State = WarningState.NOT_DUE,
                blockedToday = true,
            ),
        )

        assertEquals(1, engine.rolloverStaleDays())

        val rolled = db.dailyUsageTrackerDao().findByPackage(PROFILE_ID, PKG)!!
        assertEquals(0L, rolled.msUsedToday)
        assertEquals(LocalDate.now().toString(), rolled.localDateAnchor)
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

    // --- Helpers ---

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
        val todayIndex = LocalDate.now().dayOfWeek.value - 1
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
    }
}
