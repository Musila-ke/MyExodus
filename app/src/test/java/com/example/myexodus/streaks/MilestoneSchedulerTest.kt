package com.example.myexodus.streaks

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.UserProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * §8 Step 4 alarm reconciliation against the real `AlarmManager` shadow: trigger times are
 * projected from the accumulator — a backdated `recovery_start_at` cannot fire an alarm
 * immediately — achieved and relapsed milestones are cancelled, and the boot path reschedules
 * without the app being opened (§12) while a milestone whose projected time already passed is
 * never revived into an immediate fire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MilestoneSchedulerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var healthStore: DatabaseHealthStore
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var store: AppRulesStore
    private lateinit var scheduler: MilestoneScheduler
    private lateinit var repository: HabitRepository

    private var now = TimeReadings(tWallMillis = T0, rBootMillis = 1_000, bootCount = 7)

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
        scheduler = MilestoneScheduler(context, factory, readings = { now })
        repository = HabitRepository(
            storeFactory = factory,
            anomalyLog = StreakAnomalyLog(tmp.newFile("streak-anomalies.log")),
            readings = { now },
            scheduler = scheduler,
        )
    }

    @After
    fun tearDown() {
        store.close()
        recovery.resetAllData()
    }

    @Test
    fun reconcile_projectsTriggerTimesFromTheAccumulator_notTheBackdatedStart() = runBlocking {
        seedProfile()
        // 5 days backdated: projecting from recovery_start_at would fire Day 7 in 2 days and
        // could fire "immediately" for an older backdate. The accumulator starts at 0, so
        // every milestone is a full milestone-day span from the first anchor.
        onboard(recoveryStartAt = T0 - 5 * DAY)

        assertEquals(
            listOf(T0 + 7 * DAY, T0 + 30 * DAY, T0 + 90 * DAY, T0 + 180 * DAY, T0 + 365 * DAY),
            triggers(),
        )
        // Decision 10: inexact + doze-permitted, no exact-alarm permission.
        assertTrue(alarms().all { it.allowWhileIdle })
    }

    @Test
    fun reconcile_cancelsAchievedMilestone_afterEvaluation() = runBlocking {
        seedProfile()
        onboard()
        assertEquals(5, alarms().size)

        now = TimeReadings(tWallMillis = T0 + 7 * DAY, rBootMillis = 1_000 + 7 * DAY, bootCount = 7)
        repository.evaluate(habitIdOf())

        // Day 7 is achieved: its alarm is gone; the rungs ahead keep their projected times.
        assertEquals(
            listOf(T0 + 30 * DAY, T0 + 90 * DAY, T0 + 180 * DAY, T0 + 365 * DAY),
            triggers(),
        )
    }

    @Test
    fun reconcile_afterRelapse_cancelsEndedStreaksAlarms_andProjectsTheNewStreaks() = runBlocking {
        seedProfile()
        onboard()

        now = TimeReadings(tWallMillis = T0 + DAY, rBootMillis = 1_000 + DAY, bootCount = 7)
        repository.logRelapse(habitIdOf())

        // Per-(streak, day) identity: the new streak's alarms replace the old streak's without
        // colliding (T0+30d vs T0+31d are distinct entries, not one shared slot).
        assertEquals(
            listOf(T0 + 8 * DAY, T0 + 31 * DAY, T0 + 91 * DAY, T0 + 181 * DAY, T0 + 366 * DAY),
            triggers(),
        )
    }

    @Test
    fun reconcileAll_afterReboot_reschedulesWithoutTheAppBeingOpened() = runBlocking {
        seedProfile()
        onboard()

        clearAlarmsLikeAReboot()

        now = TimeReadings(tWallMillis = T0 + 2 * DAY, rBootMillis = 1_000, bootCount = 8)
        scheduler.reconcileAll()

        assertEquals(
            listOf(T0 + 7 * DAY, T0 + 30 * DAY, T0 + 90 * DAY, T0 + 180 * DAY, T0 + 365 * DAY),
            triggers(),
        )
    }

    @Test
    fun reconcileAll_afterLongOffPeriod_neverRevivesAPassedUnachievedMilestone() = runBlocking {
        seedProfile()
        onboard()
        clearAlarmsLikeAReboot()

        // Device off 20 days, boot receiver reconciles before any evaluation has run: Day 7's
        // projected time already passed. Rescheduling it would fire immediately; it is left
        // to the splash card, while the still-future milestones are rescheduled.
        now = TimeReadings(tWallMillis = T0 + 20 * DAY, rBootMillis = 1_000, bootCount = 8)
        scheduler.reconcileAll()

        assertEquals(
            listOf(T0 + 30 * DAY, T0 + 90 * DAY, T0 + 180 * DAY, T0 + 365 * DAY),
            triggers(),
        )
    }

    private fun alarmManager(): AlarmManager = context.getSystemService(AlarmManager::class.java)

    private fun alarms() = shadowOf(alarmManager()).scheduledAlarms

    private fun triggers(): List<Long> = alarms().map { it.triggerAtTime }.sorted()

    /** Alarms do not survive reboot — the OS clears them all; mirror that in the shadow. */
    private fun clearAlarmsLikeAReboot() {
        alarms().forEach { alarmManager().cancel(requireNotNull(it.operation)) }
        assertEquals(0, alarms().size)
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

    private suspend fun onboard(recoveryStartAt: Long = T0) {
        val result = repository.onboard(
            profileId = PROFILE_ID,
            targetIdentifier = "Instagram",
            habitDurationYears = 2,
            severity = WithdrawalSeverity.MODERATE,
            recoveryStartAt = recoveryStartAt,
        )
        assertTrue("onboard: $result", result is HabitRepository.OnboardingResult.Ready)
    }

    private fun habitIdOf(): String = checkNotNull(repository.state.value).habitId

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PROFILE_ID = "scheduler-profile"
        val DAY = StreakTimer.DAY_MS
        val T0 = StreakTimer.APP_RELEASE_EPOCH_MS + 5 * DAY
    }
}
