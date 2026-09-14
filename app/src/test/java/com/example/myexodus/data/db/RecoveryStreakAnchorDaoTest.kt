package com.example.myexodus.data.db

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.UserProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §8 Step 2.6/2.7 persistence contract on a real Room store: the anchor compare-and-update
 * bumps `row_version` and rejects a stale write, the all-time record only ever rises, and
 * — the §12 explicit verification — `all_time_max_streak_elapsed_ms` is never read back as
 * `current_streak_elapsed_ms` (the two are separate columns updated by separate statements).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryStreakAnchorDaoTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var healthStore: DatabaseHealthStore
    private lateinit var factory: AppRulesStoreFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun compareAndUpdateAnchor_bumpsRowVersion_andStaleWriteIsRejected() {
        val store = checkNotNull(factory.open())
        runBlocking {
            seed(store)
            val dao = store.recoveryStreakDao()

            assertEquals(
                1,
                dao.compareAndUpdateAnchor(
                    streakId = STREAK_ID,
                    tWallLastObserved = 9_000L,
                    rBootLastObserved = 8_000L,
                    bootCountLastObserved = 8L,
                    currentStreakElapsedMs = 6_000L,
                    expectedRowVersion = 0L,
                ),
            )
            val advanced = dao.getById(STREAK_ID)!!
            assertEquals(9_000L, advanced.tWallLastObserved)
            assertEquals(8_000L, advanced.rBootLastObserved)
            assertEquals(8L, advanced.bootCountLastObserved)
            assertEquals(6_000L, advanced.currentStreakElapsedMs)
            assertEquals(1L, advanced.rowVersion)

            // A second evaluator still holding the pre-update version writes nothing.
            assertEquals(
                0,
                dao.compareAndUpdateAnchor(
                    streakId = STREAK_ID,
                    tWallLastObserved = 1L,
                    rBootLastObserved = 1L,
                    bootCountLastObserved = 1L,
                    currentStreakElapsedMs = 999_999L,
                    expectedRowVersion = 0L,
                ),
            )
            val unchanged = dao.getById(STREAK_ID)!!
            assertEquals(6_000L, unchanged.currentStreakElapsedMs)
            assertEquals(9_000L, unchanged.tWallLastObserved)
            assertEquals(1L, unchanged.rowVersion)
        }
        store.close()
    }

    @Test
    fun allTimeMax_isMonotonic_andNeverReadBackAsTheLiveAccumulator() {
        val store = checkNotNull(factory.open())
        runBlocking {
            seed(store)
            val habitDao = store.habitProgressDao()
            val streakDao = store.recoveryStreakDao()

            assertEquals(1, habitDao.raiseAllTimeMax(HABIT_ID, 90_000L))
            assertEquals(90_000L, habitDao.getById(HABIT_ID)!!.allTimeMaxStreakElapsedMs)

            // The live accumulator moves by its own statement and stays exactly what was
            // written — the 90s record is never substituted into it.
            assertEquals(
                1,
                streakDao.compareAndUpdateAnchor(STREAK_ID, 9_000L, 8_000L, 8L, 6_000L, 0L),
            )
            assertEquals(6_000L, streakDao.getById(STREAK_ID)!!.currentStreakElapsedMs)
            assertEquals(90_000L, habitDao.getById(HABIT_ID)!!.allTimeMaxStreakElapsedMs)

            // Monotonic: a lower value (e.g. after an anomaly) is a no-op, never a decrease.
            assertEquals(0, habitDao.raiseAllTimeMax(HABIT_ID, 10_000L))
            assertEquals(90_000L, habitDao.getById(HABIT_ID)!!.allTimeMaxStreakElapsedMs)
        }
        store.close()
    }

    private suspend fun seed(store: AppRulesStore) {
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
        store.habitProgressDao().insert(
            HabitProgress(
                progressId = HABIT_ID,
                profileId = PROFILE_ID,
                targetIdentifier = "Instagram",
                currentCleanDaysStreak = 0,
                totalRelapses = 0,
                accessAttemptsCount = 0,
                habitDurationYears = 2,
                onboardingSeverityLevel = "MODERATE",
                lastRelapseTimestamp = null,
                shareAccessAttempts = false,
                shareRelapses = false,
                shareMilestones = true,
                assignedPartnershipId = null,
            ),
        )
        store.recoveryStreakDao().insert(
            RecoveryStreak(
                streakId = STREAK_ID,
                habitId = HABIT_ID,
                recoveryStartAt = 1_000L,
                tWallLastObserved = 1_000L,
                rBootLastObserved = 2_000L,
                bootCountLastObserved = 7L,
                currentStreakElapsedMs = 5_000L,
            ),
        )
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PROFILE_ID = "anchor-profile"
        const val HABIT_ID = "anchor-habit"
        const val STREAK_ID = "anchor-streak"
    }
}
