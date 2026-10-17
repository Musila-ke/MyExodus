package com.example.myexodus.streaks

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.PairStreakSharing
import com.example.myexodus.data.db.entity.UserProfile
import com.example.myexodus.streaks.HabitRepository.OnboardingResult.Rejected.Reason
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §6/§8 Step 3 repository contract on a real Room store: onboarding writes habit + first
 * streak + milestone rows atomically (and rolls back entirely on a rejected clock), relapse
 * rolls the streak over and carries the running record forward, the idempotency window and
 * the relapse-rollback rejection behave, and — the §12 explicit verification, repo level —
 * the all-time record is never read back as the live accumulator of the new streak.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HabitRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var healthStore: DatabaseHealthStore
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var store: AppRulesStore
    private lateinit var anomalyFile: File

    private var now = TimeReadings(tWallMillis = T0, rBootMillis = 1_000, bootCount = 7)
    private lateinit var repository: HabitRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        store = checkNotNull(factory.open())
        anomalyFile = tmp.newFile("streak-anomalies.log")

        repository = HabitRepository(
            storeFactory = factory,
            anomalyLog = StreakAnomalyLog(anomalyFile),
            readings = { now },
            scheduler = MilestoneScheduler(context, factory, readings = { now }),
        )
    }

    @After
    fun tearDown() {
        store.close()
        recovery.resetAllData()
    }

    private suspend fun seedProfile() {
        store.userProfileDao().insert(
            UserProfile(
                userId = PROFILE_ID,
                name = "Test",
                email = "t@example.com",
                phoneNumber = null,
                dateOfBirth = 0L,
                hasAccessibilityPermission = false,
                hasUsageStatsPermission = false,
                hasVpnPermission = false,
                hasNotificationPermission = false,
                hasDeviceAdminPermission = false,
                devicePublicKey = null,
                recoveryEscrowKey = null,
                isActive = true,
            ),
        )
    }

    private suspend fun onboard(
        recoveryStartAt: Long = T0,
        years: Int = 2,
        severity: WithdrawalSeverity = WithdrawalSeverity.MODERATE,
        label: String = "Instagram",
    ): HabitRepository.OnboardingResult = repository.onboard(
        profileId = PROFILE_ID,
        targetIdentifier = label,
        habitDurationYears = years,
        severity = severity,
        recoveryStartAt = recoveryStartAt,
    )

    /** v16/ADR #135: nothing journals without an explicit per-(pair, habit) consent row;
     *  watermarks 0 so the emission at any test clock clears the enable bound. */
    private suspend fun seedStreakConsent(
        habitId: String,
        shareStreak: Boolean = true,
        shareRelapses: Boolean = false,
        shareMilestones: Boolean = false,
    ) {
        store.pairSharingDao().upsertStreak(
            PairStreakSharing(
                pairId = "pair-1", habitProgressId = habitId,
                shareStreak = shareStreak, shareRelapses = shareRelapses,
                shareMilestones = shareMilestones,
                streakEnabledSinceMs = 0, relapseEnabledSinceMs = 0, milestoneEnabledSinceMs = 0,
            ),
        )
    }

    @Test
    fun onboard_writesHabitAnchorAndMilestones_andEmitsState() = runBlocking {
        seedProfile()
        val result = onboard(recoveryStartAt = T0 - StreakTimer.DAY_MS) as HabitRepository.OnboardingResult.Ready
        val state = result.state

        assertEquals(0L, state.currentStreakElapsedMs)
        assertEquals(0L, state.allTimeMaxElapsedMs)
        // Display ordinal, never 0 (ADR #128): a backdated start still shows Day 1 — backdating
        // sets the label, the accumulator still anchors at creation (§6.2).
        assertEquals(1, state.currentDay)
        assertEquals(T0 - StreakTimer.DAY_MS, state.recoveryStartAt)
        assertEquals(WithdrawalSeverity.MODERATE, state.severity)
        assertEquals(WithdrawalCurve.weeks(2, WithdrawalSeverity.MODERATE), state.withdrawalWeeks)
        assertEquals(state, repository.state.value)

        val habit = store.habitProgressDao().getById(state.habitId)!!
        assertEquals("MODERATE", habit.onboardingSeverityLevel)
        assertEquals(2, habit.habitDurationYears)
        assertEquals(0, habit.totalRelapses)
        assertNull(habit.lastRelapseTimestamp)

        val streak = store.recoveryStreakDao().getActiveStreak(state.habitId)!!
        assertEquals(state.streakId, streak.streakId)
        assertEquals(T0, streak.tWallLastObserved)
        assertEquals(1_000L, streak.rBootLastObserved)
        assertEquals(7L, streak.bootCountLastObserved)
        assertEquals(0L, streak.currentStreakElapsedMs)

        val milestones = store.milestoneEventDao().getForStreak(state.streakId)
        assertEquals(listOf(7, 30, 90), milestones.map { it.milestoneDay })
        assertTrue(milestones.all { it.achievedAt == null && it.notificationSentAt == null && it.acknowledgedAt == null })
    }

    @Test
    fun onboard_rejectsInvalidInputs_andWritesNothing() = runBlocking {
        seedProfile()

        val future = onboard(recoveryStartAt = T0 + 1)
        assertEquals(
            HabitRepository.OnboardingResult.Rejected(Reason.START_IN_FUTURE),
            future,
        )
        assertEquals(
            HabitRepository.OnboardingResult.Rejected(Reason.INVALID_DURATION),
            onboard(years = -1),
        )

        val implausibleNow = TimeReadings(
            tWallMillis = StreakTimer.APP_RELEASE_EPOCH_MS - 2 * StreakTimer.DAY_MS,
            rBootMillis = 1_000,
            bootCount = 7,
        )
        now = implausibleNow
        assertEquals(
            HabitRepository.OnboardingResult.Rejected(Reason.IMPLAUSIBLE_CLOCK),
            onboard(recoveryStartAt = implausibleNow.tWallMillis),
        )

        assertTrue(store.habitProgressDao().observeByProfile(PROFILE_ID).first().isEmpty())
        assertNull(repository.state.value)
        // Both first-anchor rejections are §14 anomaly events, not silent no-ops.
        assertTrue(anomalyFile.readText().contains("type=FIRST_ANCHOR_IMPLAUSIBLE"))
    }

    @Test
    fun refreshActive_resolvesTheActiveProfilesHabit_andEvaluatesIt() = runBlocking {
        seedProfile()
        onboard()

        now = TimeReadings(tWallMillis = T0 + 120_000, rBootMillis = 121_000, bootCount = 7)
        val refreshed = repository.refreshActive()!!

        assertEquals(120_000L, refreshed.currentStreakElapsedMs)
        assertEquals(refreshed, repository.state.value)
        assertEquals(120_000L, store.recoveryStreakDao().getActiveStreak(refreshed.habitId)!!.currentStreakElapsedMs)
    }

    @Test
    fun refreshActive_withoutAProfileOrHabit_returnsNullAndWritesNothing() = runBlocking {
        assertNull(repository.refreshActive())

        // Profile but no habit: still null, and nothing was created.
        seedProfile()
        assertNull(repository.refreshActive())
        assertNull(repository.state.value)
        assertTrue(store.habitProgressDao().observeByProfile(PROFILE_ID).first().isEmpty())
    }

    /**
     * The Home milestone section's error state depends on this being observable: a read that
     * cannot open the store must be distinguishable from one that has not finished yet
     * ([resolved] stays false for both), and a later definite read must clear it.
     */
    @Test
    fun refreshActive_storeUnavailable_reportsLoadFailed_whichTheNextResolvedReadClears() = runBlocking {
        seedProfile()
        onboard()
        store.close()
        // A directory where the database file belongs: the open fails with a cant-open
        // SQLiteException (not corruption — that path rebuilds and recovers), which is exactly
        // the factory's defined "null open" state.
        val dbFile = context.getDatabasePath(AppRulesStoreFactory.DB_NAME)
        deleteStoreFiles(dbFile)
        assertTrue(dbFile.mkdirs())
        val blockedFactory = AppRulesStoreFactory(context, recovery, healthStore)
        val blocked = HabitRepository(
            storeFactory = blockedFactory,
            anomalyLog = StreakAnomalyLog(anomalyFile),
            readings = { now },
            scheduler = MilestoneScheduler(context, blockedFactory, readings = { now }),
        )

        assertNull(blocked.refreshActive())
        assertTrue("a failed open is the error state, not a pending one", blocked.loadFailed.value)
        assertTrue(!blocked.resolved.value)

        // Heal: the next open builds a fresh store (no profile), which is a definite answer —
        // the error clears and the repository resolves to "nothing set up".
        deleteStoreFiles(dbFile)
        assertNull(blocked.refreshActive())
        assertTrue(blocked.resolved.value)
        assertTrue(!blocked.loadFailed.value)
    }

    private fun deleteStoreFiles(dbFile: File) {
        listOf("", "-journal", "-wal", "-shm").forEach { File(dbFile.path + it).deleteRecursively() }
    }

    @Test
    fun secondOnboard_selectsTheNewHabit_andLeavesTheFirstUntouched() = runBlocking {
        seedProfile()
        val first = (onboard() as HabitRepository.OnboardingResult.Ready).state
        val second = (onboard(label = "Betting apps") as HabitRepository.OnboardingResult.Ready).state

        // The user sees the habit they just created.
        assertEquals("Betting apps", second.targetLabel)
        assertEquals(second, repository.state.value)

        val summaries = repository.habits()
        assertEquals(listOf("Betting apps", "Instagram"), summaries.map { it.label })
        assertTrue(summaries.single { it.habitId == second.habitId }.isSelected)
        assertTrue(!summaries.single { it.habitId == first.habitId }.isSelected)

        // The first habit keeps its own streak and milestone rows — nothing was rewritten.
        assertNotNull(store.recoveryStreakDao().getActiveStreak(first.habitId))
        assertEquals(3, store.milestoneEventDao().getForStreak(first.streakId).size)
    }

    @Test
    fun selectHabit_switchesTheDisplayedState_andTheChoiceSurvivesAFreshRepository() = runBlocking {
        seedProfile()
        val first = (onboard() as HabitRepository.OnboardingResult.Ready).state
        onboard(label = "Betting apps")

        val switched = repository.selectHabit(first.habitId)!!

        assertEquals("Instagram", switched.targetLabel)
        assertEquals(switched, repository.state.value)

        // Persisted as a row flag, not memory: a fresh instance (app relaunch) resolves the
        // same habit without any re-selection.
        val relaunched = HabitRepository(
            storeFactory = factory,
            anomalyLog = StreakAnomalyLog(anomalyFile),
            readings = { now },
            scheduler = MilestoneScheduler(context, factory, readings = { now }),
        )
        assertEquals("Instagram", relaunched.refreshActive()!!.targetLabel)
    }

    @Test
    fun evaluate_ofANonDisplayedHabit_advancesItWithoutSwappingTheDisplayedState() = runBlocking {
        seedProfile()
        val first = (onboard() as HabitRepository.OnboardingResult.Ready).state
        val second = (onboard(label = "Betting apps") as HabitRepository.OnboardingResult.Ready).state

        now = TimeReadings(
            tWallMillis = T0 + 7 * StreakTimer.DAY_MS,
            rBootMillis = 1_000 + 7 * StreakTimer.DAY_MS,
            bootCount = 7,
        )
        val evaluated = repository.evaluate(first.habitId)!!

        // The background habit's evaluation persists and stamps its milestone (alarm path)…
        assertEquals(first.habitId, evaluated.habitId)
        assertEquals(7 * StreakTimer.DAY_MS, evaluated.currentStreakElapsedMs)
        assertEquals(
            7 * StreakTimer.DAY_MS,
            store.recoveryStreakDao().getActiveStreak(first.habitId)!!.currentStreakElapsedMs,
        )
        assertNotNull(store.milestoneEventDao().get(first.streakId, 7)!!.achievedAt)

        // …but the UI keeps showing the selected habit — an alarm firing is not a user switch.
        assertEquals(second.habitId, repository.state.value!!.habitId)
    }

    @Test
    fun evaluate_advancesByMonotonicDelta_raisesRecord_andPersistsAnchor() = runBlocking {
        seedProfile()
        val habitId = (onboard() as HabitRepository.OnboardingResult.Ready).state.habitId

        now = TimeReadings(tWallMillis = T0 + 999_000, rBootMillis = 61_000, bootCount = 7)
        val advanced = repository.evaluate(habitId)!!

        assertEquals(60_000L, advanced.currentStreakElapsedMs)
        assertEquals(60_000L, advanced.allTimeMaxElapsedMs)
        // Still inside the first 24h: the ordinal display stays Day 1.
        assertEquals(1, advanced.currentDay)
        assertEquals(advanced, repository.state.value)

        val streak = store.recoveryStreakDao().getActiveStreak(habitId)!!
        assertEquals(60_000L, streak.currentStreakElapsedMs)
        assertEquals(1L, streak.rowVersion)
        assertEquals(now.tWallMillis, streak.tWallLastObserved)
        assertEquals(60_000L, store.habitProgressDao().getById(habitId)!!.allTimeMaxStreakElapsedMs)

        // Re-evaluating with no clock movement changes nothing.
        assertEquals(60_000L, repository.evaluate(habitId)!!.currentStreakElapsedMs)
    }

    @Test
    fun evaluate_backwardClockAfterReboot_holdsValue_andLogsAnomaly() = runBlocking {
        seedProfile()
        val habitId = (onboard() as HabitRepository.OnboardingResult.Ready).state.habitId

        now = TimeReadings(tWallMillis = T0 - 3_600_000, rBootMillis = 500, bootCount = 8)
        val held = repository.evaluate(habitId)!!

        assertEquals(0L, held.currentStreakElapsedMs)
        assertEquals(0L, store.recoveryStreakDao().getActiveStreak(habitId)!!.rowVersion)
        assertTrue(anomalyFile.readText().contains("type=BACKWARD_CLOCK_AFTER_REBOOT"))
    }

    @Test
    fun evaluate_whenTheAnomalyLogCannotBeWritten_stillSettlesAndHolds() = runBlocking {
        // Finding #13 (2026-10-16): the backward-clock path appends its anomaly from inside
        // the evaluation write transaction — an I/O failure there must drop the evidence
        // line, not roll back the hold (or throw into the caller's scope).
        seedProfile()
        val habitId = (onboard() as HabitRepository.OnboardingResult.Ready).state.habitId
        anomalyFile.delete()
        anomalyFile.mkdirs()
        assertTrue("fixture must occupy the log path", anomalyFile.isDirectory)

        now = TimeReadings(tWallMillis = T0 - 3_600_000, rBootMillis = 500, bootCount = 8)
        val held = repository.evaluate(habitId)!!

        assertEquals(0L, held.currentStreakElapsedMs)
        assertEquals(0L, store.recoveryStreakDao().getActiveStreak(habitId)!!.rowVersion)
    }

    @Test
    fun logRelapse_rollsOver_carriesRecordForward_andPreCreatesNewMilestones() = runBlocking {
        seedProfile()
        val firstStreakId = (onboard() as HabitRepository.OnboardingResult.Ready).state.streakId

        now = TimeReadings(tWallMillis = T0 + 5 * StreakTimer.DAY_MS, rBootMillis = 1_000 + 5 * StreakTimer.DAY_MS, bootCount = 7)
        // 5 completed days = the 6th ordinal day (ADR #128).
        assertEquals(6, repository.evaluate(habitIdOf())!!.currentDay)

        val result = repository.logRelapse(habitIdOf()) as HabitRepository.RelapseResult.RolledOver
        val state = result.state

        // REQ-18 edge: the new streak restarts at Day 1 — never 0 (ADR #128).
        assertEquals(0L, state.currentStreakElapsedMs)
        assertEquals(1, state.currentDay)
        assertEquals(5 * StreakTimer.DAY_MS, state.allTimeMaxElapsedMs)
        assertEquals(WithdrawalCurve.weeks(2, WithdrawalSeverity.MODERATE), state.withdrawalWeeks)
        assertTrue(state.streakId != firstStreakId)
        assertEquals(state, repository.state.value)

        val ended = store.recoveryStreakDao().getById(firstStreakId)!!
        assertEquals(now.tWallMillis, ended.relapsedAt)
        assertEquals(7L, ended.relapseBootCount)
        assertEquals(now.rBootMillis, ended.relapseElapsedRealtimeMs)

        val habit = store.habitProgressDao().getById(state.habitId)!!
        assertEquals(1, habit.totalRelapses)
        assertEquals(now.tWallMillis, habit.lastRelapseTimestamp)
        assertEquals(5 * StreakTimer.DAY_MS, habit.allTimeMaxStreakElapsedMs)

        val newStreak = store.recoveryStreakDao().getActiveStreak(state.habitId)!!
        assertEquals(state.streakId, newStreak.streakId)
        assertEquals(now.tWallMillis, newStreak.recoveryStartAt)
        assertEquals(0L, newStreak.currentStreakElapsedMs)

        // Both streaks keep their own 7/30/90 rows (§5 Decision 8).
        assertEquals(3, store.milestoneEventDao().getForStreak(firstStreakId).size)
        val fresh = store.milestoneEventDao().getForStreak(state.streakId)
        assertEquals(listOf(7, 30, 90), fresh.map { it.milestoneDay })
        assertTrue(fresh.all { it.achievedAt == null && it.notificationSentAt == null })
    }

    @Test
    fun logRelapse_earlierThanStreakStart_isRejected_andNothingRolls() = runBlocking {
        seedProfile()
        val streakId = (onboard() as HabitRepository.OnboardingResult.Ready).state.streakId

        val result = repository.logRelapse(habitIdOf(), backdatedRelapseAt = T0 - 1)

        assertEquals(HabitRepository.RelapseResult.RejectedBeforeStreakStart, result)
        val active = store.recoveryStreakDao().getActiveStreak(habitIdOf())!!
        assertEquals(streakId, active.streakId)
        assertNull(active.relapsedAt)
        assertEquals(0, store.habitProgressDao().getById(habitIdOf())!!.totalRelapses)
        assertTrue(anomalyFile.readText().contains("type=RELAPSE_ROLLBACK_REJECTED"))
    }

    @Test
    fun logRelapse_insideIdempotencyWindow_isIgnored() = runBlocking {
        seedProfile()
        onboard()

        assertTrue(repository.logRelapse(habitIdOf()) is HabitRepository.RelapseResult.RolledOver)
        assertEquals(HabitRepository.RelapseResult.DuplicateIgnored, repository.logRelapse(habitIdOf()))

        // Exactly two streaks exist: the original and the one rollover — no third from the tap.
        assertEquals(2, store.recoveryStreakDao().getByHabit(habitIdOf()).size)
        assertEquals(1, store.habitProgressDao().getById(habitIdOf())!!.totalRelapses)
    }

    @Test
    fun newStreak_recordIsNeverReadBackAsTheLiveAccumulator() = runBlocking {
        seedProfile()
        onboard(years = 0, severity = WithdrawalSeverity.MILD)

        now = TimeReadings(tWallMillis = T0 + 90_000, rBootMillis = 91_000, bootCount = 7)
        assertEquals(90_000L, repository.evaluate(habitIdOf())!!.allTimeMaxElapsedMs)
        val rolled = repository.logRelapse(habitIdOf()) as HabitRepository.RelapseResult.RolledOver
        assertEquals(90_000L, rolled.state.allTimeMaxElapsedMs)
        assertEquals(0L, rolled.state.currentStreakElapsedMs)

        now = TimeReadings(tWallMillis = T0 + 95_000, rBootMillis = 96_000, bootCount = 7)
        val live = repository.evaluate(habitIdOf())!!

        // The §12 explicit verification: the 90s record stays a record; the live accumulator
        // reflects only the new streak's 5s.
        assertEquals(5_000L, live.currentStreakElapsedMs)
        assertEquals(90_000L, live.allTimeMaxElapsedMs)
        val streak = store.recoveryStreakDao().getActiveStreak(habitIdOf())!!
        assertEquals(5_000L, streak.currentStreakElapsedMs)
        assertEquals(90_000L, store.habitProgressDao().getById(habitIdOf())!!.allTimeMaxStreakElapsedMs)
    }

    @Test
    fun evaluate_stampsFirstCrossingOnly_andSurfacesMilestonesInState() = runBlocking {
        seedProfile()
        val streakId = (onboard() as HabitRepository.OnboardingResult.Ready).state.streakId

        now = TimeReadings(tWallMillis = T0 + 7 * StreakTimer.DAY_MS, rBootMillis = 1_000 + 7 * StreakTimer.DAY_MS, bootCount = 7)
        val crossed = repository.evaluate(habitIdOf())!!
        val achieved = crossed.milestones.single { it.milestoneDay == 7 }
        assertEquals(now.tWallMillis, achieved.achievedAt)
        assertNull(achieved.acknowledgedAt)
        assertTrue(crossed.milestones.filter { it.milestoneDay != 7 }.all { it.achievedAt == null })

        // §6 step 8 first crossing: a later evaluation never re-stamps it — the achievement
        // time stays the crossing, not the latest evaluation.
        now = TimeReadings(tWallMillis = T0 + 8 * StreakTimer.DAY_MS, rBootMillis = 1_000 + 8 * StreakTimer.DAY_MS, bootCount = 7)
        val later = repository.evaluate(habitIdOf())!!
        assertEquals(achieved.achievedAt, later.milestones.single { it.milestoneDay == 7 }.achievedAt)
        assertEquals(achieved.achievedAt, store.milestoneEventDao().get(streakId, 7)!!.achievedAt)
    }

    @Test
    fun acknowledgeMilestone_clearsTheCard_andNeverPreAcknowledgesUnachievedRows() = runBlocking {
        seedProfile()
        onboard()

        // Dismissing before the day is reached affects nothing (the DAO guard): the tap could
        // only come from a stale render, and the future splash card must not be suppressed.
        repository.acknowledgeMilestone(habitIdOf(), 7)
        assertNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.acknowledgedAt)

        now = TimeReadings(tWallMillis = T0 + 7 * StreakTimer.DAY_MS, rBootMillis = 1_000 + 7 * StreakTimer.DAY_MS, bootCount = 7)
        repository.evaluate(habitIdOf())
        assertEquals(1, repository.state.value!!.milestones.count { it.achievedAt != null && it.acknowledgedAt == null })

        repository.acknowledgeMilestone(habitIdOf(), 7)

        assertEquals(now.tWallMillis, store.milestoneEventDao().get(streakIdOf(), 7)!!.acknowledgedAt)
        assertTrue(repository.state.value!!.milestones.none { it.achievedAt != null && it.acknowledgedAt == null })
    }

    @Test
    fun twoStreaks_achieveAndAcknowledgeTheirOwnMilestonesIndependently() = runBlocking {
        seedProfile()
        onboard()
        val firstStreakId = streakIdOf()

        now = TimeReadings(tWallMillis = T0 + 7 * StreakTimer.DAY_MS, rBootMillis = 1_000 + 7 * StreakTimer.DAY_MS, bootCount = 7)
        repository.evaluate(habitIdOf())
        repository.acknowledgeMilestone(habitIdOf(), 7)
        val firstAckedAt = store.milestoneEventDao().get(firstStreakId, 7)!!.acknowledgedAt
        assertTrue(firstAckedAt != null)

        now = TimeReadings(tWallMillis = now.tWallMillis + 3_600_000, rBootMillis = now.rBootMillis + 3_600_000, bootCount = 7)
        repository.logRelapse(habitIdOf())
        val secondStreakId = streakIdOf()
        assertTrue(secondStreakId != firstStreakId)

        now = TimeReadings(tWallMillis = now.tWallMillis + 7 * StreakTimer.DAY_MS, rBootMillis = now.rBootMillis + 7 * StreakTimer.DAY_MS, bootCount = 7)
        val secondState = repository.evaluate(habitIdOf())!!

        // Streak 1's acknowledgement never leaks: the second streak's Day 7 shows its own card.
        assertEquals(1, secondState.milestones.count { it.achievedAt != null && it.acknowledgedAt == null })

        repository.acknowledgeMilestone(habitIdOf(), 7)
        assertNotNull(store.milestoneEventDao().get(secondStreakId, 7)!!.acknowledgedAt)
        assertEquals(firstAckedAt, store.milestoneEventDao().get(firstStreakId, 7)!!.acknowledgedAt)
    }

    // --- Partner-visible activity hooks (partner-activity plan Appendix A.3) -----------------

    @Test
    fun evaluate_dayCrossing_journalsOneStreakUpdated_forThatDayOnly() = runBlocking {
        seedProfile()
        onboard()
        seedStreakConsent(habitIdOf())

        now = TimeReadings(tWallMillis = T0 + StreakTimer.DAY_MS, rBootMillis = RB0 + StreakTimer.DAY_MS, bootCount = 7)
        // One completed day = ordinal Day 2 — the count the user's own surfaces show (ADR #128).
        assertEquals(2, repository.evaluate(habitIdOf())!!.currentDay)

        val rows = journalRows()
        assertEquals(listOf(EventPayload.TYPE_STREAK_UPDATED), rows.map { it.kind })
        assertEquals(2, rows.single().daysFree)

        // Re-evaluating inside the same day adds nothing — the gate is the day boundary.
        now = TimeReadings(tWallMillis = T0 + StreakTimer.DAY_MS + 60_000, rBootMillis = RB0 + StreakTimer.DAY_MS + 60_000, bootCount = 7)
        repository.evaluate(habitIdOf())
        assertEquals(1, journalRows().size)
    }

    @Test
    fun evaluate_milestoneCrossing_journalsMilestoneReached_oncePerCrossedDay() = runBlocking {
        seedProfile()
        onboard()
        seedStreakConsent(habitIdOf(), shareMilestones = true)

        now = TimeReadings(tWallMillis = T0 + 7 * StreakTimer.DAY_MS, rBootMillis = RB0 + 7 * StreakTimer.DAY_MS, bootCount = 7)
        repository.evaluate(habitIdOf())

        val kinds = journalRows().map { it.kind }
        assertEquals(2, kinds.size)
        assertTrue(kinds.containsAll(listOf(EventPayload.TYPE_MILESTONE_REACHED, EventPayload.TYPE_STREAK_UPDATED)))
        assertEquals("7", journalRows().single { it.kind == EventPayload.TYPE_MILESTONE_REACHED }.milestonesReached)

        // The stamp is first-crossing only, so the next evaluation announces nothing again.
        now = TimeReadings(tWallMillis = T0 + 8 * StreakTimer.DAY_MS, rBootMillis = RB0 + 8 * StreakTimer.DAY_MS, bootCount = 7)
        repository.evaluate(habitIdOf())
        assertEquals(1, journalRows().count { it.kind == EventPayload.TYPE_MILESTONE_REACHED })
    }

    @Test
    fun logRelapse_journalsRelapseRecorded_withAPostRolloverSnapshot_whenConsentShared() = runBlocking {
        seedProfile()
        onboard()
        val habitId = habitIdOf()
        seedStreakConsent(habitId, shareRelapses = true)

        now = TimeReadings(tWallMillis = T0 + 5 * StreakTimer.DAY_MS, rBootMillis = RB0 + 5 * StreakTimer.DAY_MS, bootCount = 7)
        repository.evaluate(habitId)
        repository.logRelapse(habitId)

        val row = journalRows().single { it.kind == EventPayload.TYPE_RELAPSE_RECORDED }
        assertEquals("the new streak reports Day 1, never 0 (ADR #128)", 1, row.daysFree)
        assertEquals(1, row.relapseCount)
        assertEquals("a fresh streak has achieved nothing", "", row.milestonesReached)
    }

    @Test
    fun logRelapse_withoutRelapseConsent_journalsNothing() = runBlocking {
        seedProfile()
        onboard() // v16: no consent row at all — nothing may journal

        now = TimeReadings(tWallMillis = T0 + 5 * StreakTimer.DAY_MS, rBootMillis = RB0 + 5 * StreakTimer.DAY_MS, bootCount = 7)
        repository.evaluate(habitIdOf())
        assertTrue(repository.logRelapse(habitIdOf()) is HabitRepository.RelapseResult.RolledOver)

        assertTrue(journalRows().none { it.kind == EventPayload.TYPE_RELAPSE_RECORDED })
    }

    // --- Consent matrix write API (v16/ADR #135) ----------------------------------------------

    /** The owner-visible promise of the master toggle: the pair that just enabled sees the
     *  streak's current state immediately (one targeted snapshot that clears the watermark),
     *  and only that pair sees it. */
    @Test
    fun setPairStreakSharing_masterRise_journalsOneTargetedSnapshot_thatClearsTheWatermark() = runBlocking {
        seedProfile()
        onboard()
        val habitId = habitIdOf()
        val riseAt = now.tWallMillis

        assertTrue(
            repository.setPairStreakSharing("pair-1", habitId, shareStreak = true, shareRelapses = false, shareMilestones = false),
        )

        val row = journalRows().single()
        assertEquals(EventPayload.TYPE_STREAK_UPDATED, row.kind)
        assertEquals("the pair that just enabled is the only recipient", "pair-1", row.targetPairId)
        assertEquals("the new streak reports Day 1, never 0 (ADR #128)", 1, row.daysFree)
        assertEquals(riseAt, row.occurredAtMs)
        assertEquals(
            "the watermark is pinned at the rise so the snapshot itself delivers",
            riseAt, store.pairSharingDao().streakFor("pair-1", habitId)!!.streakEnabledSinceMs,
        )
        assertTrue(
            "another pair sees nothing — no consent row, and the snapshot is targeted",
            store.partnerActivityDao().pendingForPair("pair-2", sinceMs = 0L, limit = 100).isEmpty(),
        )

        assertTrue(
            repository.setPairStreakSharing("pair-1", habitId, shareStreak = true, shareRelapses = false, shareMilestones = false),
        )
        assertEquals("re-enabling an already-on master adds no second snapshot", 1, journalRows().size)
    }

    @Test
    fun setPairProfileSharing_roundTripsThroughTheMatrix_andPinsEachWatermarkAtItsRise() = runBlocking {
        seedProfile()
        onboard()
        val habitId = habitIdOf()
        val firstAt = now.tWallMillis

        assertTrue(repository.setPairProfileSharing("pair-1", shareCheckIns = true, shareRecovery = false))
        val afterFirst = store.pairSharingDao().profileFor("pair-1")!!
        assertEquals(firstAt, afterFirst.checkinsEnabledSinceMs)
        assertEquals("a switch that was never on has no watermark", 0L, afterFirst.recoveryEnabledSinceMs)

        now = TimeReadings(tWallMillis = firstAt + 3_600_000, rBootMillis = RB0 + 3_600_000, bootCount = 7)
        assertTrue(repository.setPairProfileSharing("pair-1", shareCheckIns = true, shareRecovery = true))
        val afterSecond = store.pairSharingDao().profileFor("pair-1")!!
        assertEquals("a category that stayed on keeps its original watermark", firstAt, afterSecond.checkinsEnabledSinceMs)
        assertEquals("the rise pins the new watermark", firstAt + 3_600_000, afterSecond.recoveryEnabledSinceMs)

        val matrix = checkNotNull(repository.sharingMatrix())
        assertEquals(ProfileSharingFlags(shareCheckIns = true, shareRecovery = true), matrix.profile["pair-1"])
        val item = matrix.habits.single()
        assertEquals(habitId, item.progressId)
        assertEquals("Instagram", item.label)
        assertNull("a habit with no consent row shows as not shared for every pair", item.perPair["pair-1"])
    }

    /** Owner ask 2026-10-04: the hub's "nothing shared yet" card gate — true until ANY
     *  category rises for THAT pair, and per-pair, not global. */
    @Test
    fun sharesNothingWith_isTrueUntilAnyCategoryRises_perPair() = runBlocking {
        seedProfile()
        onboard()
        val habitId = habitIdOf()

        assertEquals("a fresh pair shares nothing", true, repository.sharesNothingWith("pair-1"))
        assertEquals("no consent row for pair-2 either", true, repository.sharesNothingWith("pair-2"))

        assertTrue(repository.setPairProfileSharing("pair-1", shareCheckIns = true, shareRecovery = false))
        assertEquals("one profile category on counts as sharing", false, repository.sharesNothingWith("pair-1"))
        assertEquals("and only for that pair", true, repository.sharesNothingWith("pair-2"))

        assertTrue(
            repository.setPairStreakSharing("pair-2", habitId, shareStreak = true, shareRelapses = false, shareMilestones = false),
        )
        assertEquals("a streak master on counts as sharing", false, repository.sharesNothingWith("pair-2"))
    }

    private suspend fun journalRows() =
        store.partnerActivityDao().pendingForPair("pair-1", sinceMs = 0L, limit = 100)

    private suspend fun habitIdOf(): String =
        checkNotNull(repository.state.value).habitId

    private fun streakIdOf(): String = checkNotNull(repository.state.value).streakId

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PROFILE_ID = "repo-profile"
        const val RB0 = 1_000L
        val T0 = StreakTimer.APP_RELEASE_EPOCH_MS + 5 * StreakTimer.DAY_MS
    }
}
