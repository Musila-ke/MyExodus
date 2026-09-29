package com.example.myexodus.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.UserProfile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §12 milestone-schema requirements against a real [AppRulesStore]: two streaks on the same
 * habit each independently achieve, notify, and acknowledge their own Day-7 milestone, and
 * the notification claim lets exactly one racing evaluation win per (streak, milestone).
 */
@RunWith(AndroidJUnit4::class)
class MilestoneSchemaInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val legacyKeys = AndroidDatabaseKeyManager(context)
    private val activityKeys = ActivityLogKeyManager(context)
    private val rulesKeys = RulesStoreKeyManager(context)

    private lateinit var recovery: RecoveryManager
    private lateinit var health: DatabaseHealthStore

    @Before
    fun setUp() {
        assumeEmulatorOnly()
        health = DatabaseHealthStore()
        recovery = RecoveryManager(context, legacyKeys, activityKeys, rulesKeys, health)
        recovery.resetAllDataEmulatorOnly()
    }

    @After
    fun tearDown() {
        // @After runs even when setUp aborted on the emulator assumption; recovery is only
        // assigned after that assumption, so touching it here would throw on a handset.
        if (::recovery.isInitialized) recovery.resetAllDataEmulatorOnly()
    }

    @Test
    fun twoStreaks_independentlyAchieveNotifyAndAcknowledgeDay7() = runTest {
        val store = openStore()
        seedHabitAndStreaks(store)

        // Both streaks exist; the active-streak lookup finds only the un-relapsed one.
        assertEquals(2, store.recoveryStreakDao().getByHabit(HABIT_ID).size)
        assertEquals("s-2", store.recoveryStreakDao().getActiveStreak(HABIT_ID)!!.streakId)

        // Each streak's 7/30/90 rows were pre-created independently.
        assertEquals(
            MilestoneEvent.MILESTONE_DAYS,
            store.milestoneEventDao().getForStreak("s-1").map { it.milestoneDay },
        )
        assertEquals(
            MilestoneEvent.MILESTONE_DAYS,
            store.milestoneEventDao().getForStreak("s-2").map { it.milestoneDay },
        )

        // Day 7 is achieved on both streaks without interfering, and never re-stamped.
        assertEquals(1, store.milestoneEventDao().markAchieved("s-1", 7, 10_000L))
        assertEquals(1, store.milestoneEventDao().markAchieved("s-2", 7, 20_000L))
        assertEquals(0, store.milestoneEventDao().markAchieved("s-1", 7, 30_000L))

        // Each streak claims its own notification; a losing second claim is a no-op.
        assertEquals(1, store.milestoneEventDao().claimNotification("s-1", 7, 11_000L))
        assertEquals(1, store.milestoneEventDao().claimNotification("s-2", 7, 21_000L))
        assertEquals(0, store.milestoneEventDao().claimNotification("s-2", 7, 22_000L))

        // Acknowledging the old streak's day 7 leaves the active streak's untouched.
        assertEquals(1, store.milestoneEventDao().acknowledge("s-1", 7, 12_000L))
        assertEquals(0, store.milestoneEventDao().acknowledge("s-1", 7, 13_000L))
        suspend fun day7Of(streakId: String) =
            store.milestoneEventDao().getForStreak(streakId).first { it.milestoneDay == 7 }
        assertEquals(12_000L, day7Of("s-1").acknowledgedAt)
        assertNull("the active streak's day 7 stays unacknowledged", day7Of("s-2").acknowledgedAt)
        assertEquals(20_000L, day7Of("s-2").achievedAt)
        assertEquals(21_000L, day7Of("s-2").notificationSentAt)
        store.close()
    }

    @Test
    fun notificationClaim_letsExactlyOneEvaluationWin() = runTest {
        val store = openStore()
        seedHabitAndStreaks(store)
        store.milestoneEventDao().markAchieved("s-2", 7, 20_000L)

        // Two evaluations race for the same (streak, day): the atomic claim lets the
        // first stamp notification_sent_at and the second sees it already claimed.
        assertEquals(1, store.milestoneEventDao().claimNotification("s-2", 7, 21_000L))
        assertEquals(0, store.milestoneEventDao().claimNotification("s-2", 7, 21_500L))

        val day7 = store.milestoneEventDao().getForStreak("s-2").first { it.milestoneDay == 7 }
        assertEquals(21_000L, day7.notificationSentAt)
        store.close()
    }

    private fun openStore(): AppRulesStore {
        val store = AppRulesStoreFactory(context, recovery, health, encryption = null).open()
        checkNotNull(store)
        return store
    }

    private suspend fun seedHabitAndStreaks(store: AppRulesStore) {
        store.userProfileDao().insert(profile())
        store.habitProgressDao().insert(habit())
        // s-1 ended via relapse; s-2 is the active streak started after it.
        store.recoveryStreakDao().insert(
            streak("s-1", recoveryStartAt = 100L, relapsedAt = 150L),
        )
        store.recoveryStreakDao().insert(
            streak("s-2", recoveryStartAt = 200L, relapsedAt = null),
        )
        for (streakId in listOf("s-1", "s-2")) {
            store.milestoneEventDao().insertAll(
                MilestoneEvent.MILESTONE_DAYS.map { MilestoneEvent(streakId = streakId, milestoneDay = it) },
            )
        }
    }

    private fun profile() = UserProfile(
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
    )

    private fun habit() = HabitProgress(
        progressId = HABIT_ID,
        profileId = PROFILE_ID,
        targetIdentifier = "Instagram",
        currentCleanDaysStreak = 0,
        totalRelapses = 1,
        accessAttemptsCount = 0,
        habitDurationYears = 2,
        onboardingSeverityLevel = "MODERATE",
        lastRelapseTimestamp = 150L,
        shareAccessAttempts = false,
        shareRelapses = false,
        shareMilestones = true,
        assignedPartnershipId = null,
    )

    private fun streak(streakId: String, recoveryStartAt: Long, relapsedAt: Long?) = RecoveryStreak(
        streakId = streakId,
        habitId = HABIT_ID,
        recoveryStartAt = recoveryStartAt,
        relapsedAt = relapsedAt,
        relapseBootCount = if (relapsedAt != null) 7L else null,
        relapseElapsedRealtimeMs = if (relapsedAt != null) 1_000L else null,
        tWallLastObserved = recoveryStartAt,
        rBootLastObserved = recoveryStartAt,
        bootCountLastObserved = 7L,
        currentStreakElapsedMs = 0L,
        rowVersion = 0L,
    )

    private companion object {
        const val PROFILE_ID = "ms-profile"
        const val HABIT_ID = "h-1"
    }
}
