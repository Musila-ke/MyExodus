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

    @Test
    fun catchUpAll_postsWhenTheAlarmNeverFired() = runBlocking {
        seedProfile()
        onboard()
        grantNotifications()
        // The foreground evaluation stamps the crossing, but no alarm ever fired — the
        // device was off across the projected trigger (owner report 2026-09-27).
        now = TimeReadings(tWallMillis = T0 + 7 * DAY + 1_000, rBootMillis = 1_000 + 7 * DAY + 1_000, bootCount = 7)
        repository.refreshActive()
        assertNotNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.achievedAt)

        alerter.catchUpAll()

        assertEquals(1, shadowOf(notificationManager()).size())
        assertNotNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.notificationSentAt)
        assertEquals(1, postedLogLines())
    }

    @Test
    fun catchUpAll_postsNothingWhenNothingHasCrossed() = runBlocking {
        seedProfile()
        onboard()
        grantNotifications()
        now = TimeReadings(tWallMillis = T0 + 6 * DAY, rBootMillis = 1_000 + 6 * DAY, bootCount = 7)
        repository.refreshActive()

        alerter.catchUpAll()

        assertEquals(0, shadowOf(notificationManager()).size())
        assertNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.notificationSentAt)
        assertEquals(0, postedLogLines())
    }

    @Test
    fun handle_afterCatchUpDelivery_doesNotPostASecondNotification() = runBlocking {
        seedProfile()
        onboard()
        grantNotifications()
        now = TimeReadings(tWallMillis = T0 + 7 * DAY + 1_000, rBootMillis = 1_000 + 7 * DAY + 1_000, bootCount = 7)
        repository.refreshActive()
        alerter.catchUpAll()

        // The deferred alarm fires after the catch-up already claimed the milestone.
        alerter.handle(streakIdOf(), 7)

        assertEquals(1, shadowOf(notificationManager()).size())
        assertEquals(1, postedLogLines())
    }

    @Test
    fun catchUpAll_whenTheLogWriteFails_stillPostsAfterTheConsumedClaim() = runBlocking {
        // Finding #13 (2026-10-16): the §14 delivery-counter line sits between the claim and
        // the post — an I/O failure writing it must not turn a consumed claim into a lost
        // notification (the claim is the once-only token; nothing ever retries it).
        seedProfile()
        onboard()
        grantNotifications()
        now = TimeReadings(tWallMillis = T0 + 7 * DAY + 1_000, rBootMillis = 1_000 + 7 * DAY + 1_000, bootCount = 7)
        repository.refreshActive()
        anomalyFile.delete()
        anomalyFile.mkdirs()
        assertTrue("fixture must occupy the log path", anomalyFile.isDirectory)

        alerter.catchUpAll()

        assertEquals(1, shadowOf(notificationManager()).size())
        assertNotNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.notificationSentAt)
    }

    @Test
    fun catchUpAll_deniedPermission_keepsTheClaimForALaterGrant() = runBlocking {
        seedProfile()
        onboard()
        now = TimeReadings(tWallMillis = T0 + 7 * DAY + 1_000, rBootMillis = 1_000 + 7 * DAY + 1_000, bootCount = 7)
        repository.refreshActive()

        // Robolectric grants no runtime permissions by default; the claim stays unconsumed
        // so a later grant can still deliver (§9 — same guarantee as the alarm path).
        alerter.catchUpAll()
        assertEquals(0, shadowOf(notificationManager()).size())
        assertNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.notificationSentAt)

        grantNotifications()
        alerter.catchUpAll()

        assertEquals(1, shadowOf(notificationManager()).size())
        assertNotNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.notificationSentAt)
        assertEquals(1, postedLogLines())
    }

    @Test
    fun catchUpAll_stampsAndPostsACrossingFromTheDeviceOffWindow() = runBlocking {
        seedProfile()
        onboard()
        grantNotifications()
        // Reboot-detected transition: the crossing happened while the device was off, so
        // nothing evaluated or delivered at the old boot's wall time.
        now = TimeReadings(tWallMillis = T0 + 7 * DAY + 60_000, rBootMillis = 1_000 + 7 * DAY + 60_000, bootCount = 8)
        assertNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.achievedAt)

        alerter.catchUpAll()

        assertNotNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.achievedAt)
        assertEquals(1, shadowOf(notificationManager()).size())
        assertNotNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.notificationSentAt)
        assertEquals(1, postedLogLines())
    }

    @Test
    fun catchUpAll_evaluatesAndPostsForEveryActiveHabit() = runBlocking {
        seedProfile()
        onboard()
        val second = repository.onboard(
            profileId = PROFILE_ID,
            targetIdentifier = "TikTok",
            habitDurationYears = 2,
            severity = WithdrawalSeverity.MODERATE,
            recoveryStartAt = T0,
        )
        check(second is HabitRepository.OnboardingResult.Ready) { "second onboard: $second" }
        grantNotifications()
        now = TimeReadings(tWallMillis = T0 + 7 * DAY + 60_000, rBootMillis = 1_000 + 7 * DAY + 60_000, bootCount = 8)

        alerter.catchUpAll()

        assertEquals(2, shadowOf(notificationManager()).size())
        assertEquals(2, postedLogLines())
    }

    @Test
    fun catchUpAll_postsForANonDisplayedHabitWhoseAlarmNeverFired() = runBlocking {
        seedProfile()
        onboard()
        val displayedHabitId = habitIdOf()
        val second = repository.onboard(
            profileId = PROFILE_ID,
            targetIdentifier = "TikTok",
            habitDurationYears = 2,
            severity = WithdrawalSeverity.MODERATE,
            recoveryStartAt = T0,
        )
        check(second is HabitRepository.OnboardingResult.Ready) { "second onboard: $second" }
        // The user stays on the first habit; the second is never displayed. Same boot (no
        // reboot): the crossing passed in-window with no alarm to fire — finding #22.
        repository.selectHabit(displayedHabitId)
        grantNotifications()
        now = TimeReadings(tWallMillis = T0 + 7 * DAY + 1_000, rBootMillis = 1_000 + 7 * DAY + 1_000, bootCount = 7)

        alerter.catchUpAll()

        // The sweep delivers for every active habit, displayed or not — the earlier
        // displayed-habit-only foreground version left this crossing unannounced.
        assertEquals(2, shadowOf(notificationManager()).size())
        assertEquals(2, postedLogLines())
        assertNotNull(store.milestoneEventDao().get(streakIdOf(), 7)!!.notificationSentAt)
        val secondHabitId = store.recoveryStreakDao().activeHabitIds().first { it != displayedHabitId }
        val secondStreak = checkNotNull(store.recoveryStreakDao().getActiveStreak(secondHabitId))
        assertNotNull(store.milestoneEventDao().get(secondStreak.streakId, 7)!!.notificationSentAt)
    }

    @Test
    fun catchUpAll_leavesARelapsedStreaksMilestoneUnposted() = runBlocking {
        seedProfile()
        onboard()
        grantNotifications()
        val endedStreakId = streakIdOf()
        now = TimeReadings(tWallMillis = T0 + 7 * DAY + 1_000, rBootMillis = 1_000 + 7 * DAY + 1_000, bootCount = 7)
        repository.logRelapse(habitIdOf())

        alerter.catchUpAll()

        // The ended streak's Day 7 predates the relapse and is never announced
        // retroactively; the new streak has not crossed anything.
        assertEquals(0, shadowOf(notificationManager()).size())
        assertNull(store.milestoneEventDao().get(endedStreakId, 7)!!.notificationSentAt)
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
