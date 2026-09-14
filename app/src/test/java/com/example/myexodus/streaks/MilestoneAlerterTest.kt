package com.example.myexodus.streaks

import android.Manifest
import android.app.Application
import android.app.NotificationManager
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * §6/§8 Step 4 firing path on a real Room store + real notification shadow: an alarm firing is
 * never proof — the alerter re-evaluates from the live clocks, re-reads durable state, and the
 * atomic claim guarantees at most one post per (streak, day) under a race (§12). Two streaks
 * on one habit notify independently, relapsed streaks and unachieved milestones never post,
 * and denied POST_NOTIFICATIONS skips silently *without consuming the claim* so a later grant
 * can still deliver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MilestoneAlerterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var healthStore: DatabaseHealthStore
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var store: AppRulesStore
    private lateinit var anomalyFile: File
    private lateinit var repository: HabitRepository
    private lateinit var alerter: MilestoneAlerter

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
        anomalyFile = tmp.newFile("streak-anomalies.log")
        val anomalyLog = StreakAnomalyLog(anomalyFile)
        repository = HabitRepository(
            storeFactory = factory,
            anomalyLog = anomalyLog,
            readings = { now },
            scheduler = MilestoneScheduler(context, factory, readings = { now }),
        )
        alerter = MilestoneAlerter(
            context = context,
            storeFactory = factory,
            repository = repository,
            anomalyLog = anomalyLog,
            readings = { now },
        )
    }

    @After
    fun tearDown() {
        store.close()
        recovery.resetAllData()
    }

    @Test
    fun handle_postsExactlyOnce_whenTwoEvaluationsRaceTheSameAlarm() = runBlocking {
        seedProfile()
        onboard()
        grantNotifications()
        now = TimeReadings(tWallMillis = T0 + 7 * DAY + 1_000, rBootMillis = 1_000 + 7 * DAY + 1_000, bootCount = 7)
        val streakId = streakIdOf()

        val first = async(Dispatchers.IO) { alerter.handle(streakId, 7) }
        val second = async(Dispatchers.IO) { alerter.handle(streakId, 7) }
        first.await()
        second.await()

        assertEquals(1, notificationManager().let { shadowOf(it).size() })
        assertNotNull(store.milestoneEventDao().get(streakId, 7)!!.notificationSentAt)
        assertEquals(1, postedLogLines())
    }

    @Test
    fun handle_doesNothing_whenTheMilestoneIsNotYetAchieved() = runBlocking {
        seedProfile()
        onboard()
        // Granted so the 0 below means "not achieved", not "permission denied".
        grantNotifications()
        now = TimeReadings(tWallMillis = T0 + 6 * DAY, rBootMillis = 1_000 + 6 * DAY, bootCount = 7)

        alerter.handle(streakIdOf(), 7)

        assertEquals(0, shadowOf(notificationManager()).size())
        assertNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.notificationSentAt)
        assertEquals(0, postedLogLines())
    }

    @Test
    fun handle_doesNothing_forARelapsedStreak() = runBlocking {
        seedProfile()
        onboard()
        val endedStreakId = streakIdOf()
        now = TimeReadings(tWallMillis = T0 + 7 * DAY, rBootMillis = 1_000 + 7 * DAY, bootCount = 7)
        repository.logRelapse(habitIdOf())

        grantNotifications()
        alerter.handle(endedStreakId, 7)

        assertEquals(0, shadowOf(notificationManager()).size())
        assertNull(store.milestoneEventDao().get(endedStreakId, 7)!!.notificationSentAt)
    }

    @Test
    fun twoStreaks_notifyTheirOwnMilestonesIndependently() = runBlocking {
        seedProfile()
        onboard()
        val firstStreakId = streakIdOf()
        grantNotifications()

        now = TimeReadings(tWallMillis = T0 + 7 * DAY + 1_000, rBootMillis = 1_000 + 7 * DAY + 1_000, bootCount = 7)
        alerter.handle(firstStreakId, 7)
        assertEquals(1, shadowOf(notificationManager()).size())

        repository.logRelapse(habitIdOf())
        val secondStreakId = streakIdOf()
        now = TimeReadings(tWallMillis = now.tWallMillis + 7 * DAY, rBootMillis = now.rBootMillis + 7 * DAY, bootCount = 7)
        alerter.handle(secondStreakId, 7)

        // Distinct identities: the second streak's Day 7 is its own notification, not a
        // replacement of the first (§5 Decision 8 / §12 repeated-streak milestone test).
        assertEquals(2, shadowOf(notificationManager()).size())
        assertNotNull(store.milestoneEventDao().get(firstStreakId, 7)!!.notificationSentAt)
        assertNotNull(store.milestoneEventDao().get(secondStreakId, 7)!!.notificationSentAt)
    }

    @Test
    fun handle_deniedPermission_skipsSilently_andDoesNotConsumeTheClaim() = runBlocking {
        seedProfile()
        onboard()
        now = TimeReadings(tWallMillis = T0 + 7 * DAY + 1_000, rBootMillis = 1_000 + 7 * DAY + 1_000, bootCount = 7)
        val streakId = streakIdOf()

        // Robolectric grants no runtime permissions by default.
        alerter.handle(streakId, 7)
        assertEquals(0, shadowOf(notificationManager()).size())
        assertNull(store.milestoneEventDao().get(streakId, 7)!!.notificationSentAt)
        assertEquals(0, postedLogLines())

        grantNotifications()
        alerter.handle(streakId, 7)

        assertEquals(1, shadowOf(notificationManager()).size())
        assertNotNull(store.milestoneEventDao().get(streakId, 7)!!.notificationSentAt)
        assertEquals(1, postedLogLines())
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun grantNotifications() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** §14 delivery-counter lines actually written after a real post. */
    private fun postedLogLines(): Int =
        anomalyFile.readLines().count { it.contains("type=MILESTONE_NOTIFICATION_POSTED") }

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

    private suspend fun onboard() {
        val result = repository.onboard(
            profileId = PROFILE_ID,
            targetIdentifier = "Instagram",
            habitDurationYears = 2,
            severity = WithdrawalSeverity.MODERATE,
            recoveryStartAt = T0,
        )
        check(result is HabitRepository.OnboardingResult.Ready) { "onboard: $result" }
    }

    private fun habitIdOf(): String = checkNotNull(repository.state.value).habitId

    private fun streakIdOf(): String = checkNotNull(repository.state.value).streakId

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PROFILE_ID = "alerter-profile"
        val DAY = StreakTimer.DAY_MS
        val T0 = StreakTimer.APP_RELEASE_EPOCH_MS + 5 * DAY
    }
}
