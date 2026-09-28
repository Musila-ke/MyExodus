package com.example.myexodus.accountability.activity

import android.app.Application
import android.content.Context
import androidx.room3.withWriteTransaction
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
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.PartnerActivityOutbox
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.UserProfile
import com.example.myexodus.streaks.StreakTimer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Journal-first emission contract (partner-activity plan §7 Step 5, Appendix A.3) on a real
 * Room store: the snapshot is the committed post-transition state (achieved milestones of the
 * *current* streak only), the shipped consent flags gate the habits kinds, coaching/motivational
 * are user-initiated and ungated, and an emission inside a rolled-back transaction leaves no
 * journal row behind.
 *
 * Rows are read back through `pendingForPair` — before any pair links exist, every journal row
 * is pending for every pair, which is exactly the reconcile view (Step 6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerActivityJournalTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var store: AppRulesStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        store = checkNotNull(AppRulesStoreFactory(context, recovery, healthStore).open())
    }

    @After
    fun tearDown() {
        store.close()
        recovery.resetAllData()
    }

    // --- Snapshot ----------------------------------------------------------------------------

    @Test
    fun emitForHabit_snapshotIsTheCommittedStreakState() = runBlocking {
        seedProfile()
        val habitId = seedHabit(daysElapsed = 7, relapses = 2, shareMilestones = true)
        store.coachingStepDao().insert(coachingStep("c-1", PROFILE_ID))
        store.coachingStepDao().insert(coachingStep("c-2", PROFILE_ID))

        assertTrue(
            PartnerActivityJournal.emitForHabit(store, habitId, EventPayload.TYPE_STREAK_UPDATED, NOW),
        )

        val row = journalRows().single()
        assertEquals(EventPayload.TYPE_STREAK_UPDATED, row.kind)
        assertEquals(7, row.daysFree)
        assertEquals(2, row.relapseCount)
        assertEquals("7", row.milestonesReached)
        assertEquals(2, row.coachingProgress)
        assertEquals(EventPayload.ACTIVITY_PROTOCOL_VERSION, row.protocolVersion)
        assertEquals(EventPayload.ACTIVITY_SCHEMA_VERSION, row.schemaVersion)
        assertEquals(NOW, row.occurredAtMs)
        assertEquals(NOW, row.createdAtMs)
        assertEquals("", row.text)
    }

    @Test
    fun emitForHabit_occurredAndCreatedClocksAreIndependent() = runBlocking {
        seedProfile()
        val habitId = seedHabit(shareRelapses = true)

        PartnerActivityJournal.emitForHabit(
            store, habitId, EventPayload.TYPE_RELAPSE_RECORDED,
            occurredAtMs = NOW - 3 * StreakTimer.DAY_MS, createdAtMs = NOW,
        )

        val row = journalRows().single()
        assertEquals("a backdated occurrence keeps the retention clock at write time", NOW, row.createdAtMs)
        assertEquals(NOW - 3 * StreakTimer.DAY_MS, row.occurredAtMs)
    }

    @Test
    fun emitForHabit_unachievedMilestonesAreNotReported() = runBlocking {
        seedProfile()
        val habitId = seedHabit(daysElapsed = 35, shareMilestones = true)

        PartnerActivityJournal.emitForHabit(store, habitId, EventPayload.TYPE_MILESTONE_REACHED, NOW)

        // 7 and 30 crossed at 35 days; 90 not.
        assertEquals("7,30", journalRows().single().milestonesReached)
    }

    // --- Consent matrix (Appendix A.3) --------------------------------------------------------

    @Test
    fun consentFlags_gateTheHabitsKinds() = runBlocking {
        seedProfile()
        val closed = seedHabit(shareMilestones = false, shareRelapses = false)

        assertFalse(PartnerActivityJournal.emitForHabit(store, closed, EventPayload.TYPE_STREAK_UPDATED, NOW))
        assertFalse(PartnerActivityJournal.emitForHabit(store, closed, EventPayload.TYPE_MILESTONE_REACHED, NOW))
        assertFalse(PartnerActivityJournal.emitForHabit(store, closed, EventPayload.TYPE_RELAPSE_RECORDED, NOW))
        assertTrue("a refused emission must write nothing", journalRows().isEmpty())
    }

    @Test
    fun consentFlags_arePerKindNotAllOrNothing() = runBlocking {
        seedProfile()
        val milestonesOnly = seedHabit(shareMilestones = true, shareRelapses = false)

        assertTrue(PartnerActivityJournal.emitForHabit(store, milestonesOnly, EventPayload.TYPE_STREAK_UPDATED, NOW))
        assertFalse(PartnerActivityJournal.emitForHabit(store, milestonesOnly, EventPayload.TYPE_RELAPSE_RECORDED, NOW))
    }

    @Test
    fun emitForProfile_isUngated_andToleratesAProfileWithNoHabit() = runBlocking {
        seedProfile()
        store.coachingStepDao().insert(coachingStep("c-1", PROFILE_ID))

        assertTrue(
            PartnerActivityJournal.emitForProfile(
                store, PROFILE_ID, EventPayload.TYPE_COACHING_STEP_UPDATED, NOW,
            ),
        )

        val row = journalRows().single()
        assertEquals(0, row.daysFree)
        assertEquals(0, row.relapseCount)
        assertEquals("", row.milestonesReached)
        assertEquals(1, row.coachingProgress)
    }

    @Test
    fun motivationalMessage_carriesItsText_underTheUniformSnapshot() = runBlocking {
        seedProfile()
        seedHabit(daysElapsed = 3, shareMilestones = false, shareRelapses = false)

        assertTrue(
            PartnerActivityJournal.emitForProfile(
                store, PROFILE_ID, EventPayload.TYPE_MOTIVATIONAL_MESSAGE, NOW,
                text = "You've got this — day by day.",
            ),
        )

        val row = journalRows().single()
        assertEquals("You've got this — day by day.", row.text)
        assertEquals("the message rides the sender's own snapshot", 3, row.daysFree)
    }

    // --- Atomicity (Step 5 validation) --------------------------------------------------------

    @Test
    fun emissionInsideARolledBackTransaction_leavesNoJournalRow() = runBlocking {
        seedProfile()
        val habitId = seedHabit(shareMilestones = true)

        val thrown = runCatching {
            store.withWriteTransaction<Unit> {
                PartnerActivityJournal.emitForHabit(store, habitId, EventPayload.TYPE_STREAK_UPDATED, NOW)
                throw IllegalStateException("domain write failed")
            }
        }

        assertTrue(thrown.isFailure)
        assertTrue("a failed domain transaction must not leave a phantom event", journalRows().isEmpty())
    }

    // --- Harness ------------------------------------------------------------------------------

    private suspend fun journalRows(): List<PartnerActivityOutbox> =
        store.partnerActivityDao().pendingForPair("pair-1", 100)

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

    /** Habit + one active streak + its 7/30/90 rows; milestones up to [daysElapsed] stamped. */
    private suspend fun seedHabit(
        daysElapsed: Int = 0,
        relapses: Int = 0,
        shareMilestones: Boolean = true,
        shareRelapses: Boolean = true,
    ): String {
        store.habitProgressDao().insert(
            HabitProgress(
                progressId = HABIT_ID,
                profileId = PROFILE_ID,
                targetIdentifier = "Instagram",
                currentCleanDaysStreak = 0,
                totalRelapses = relapses,
                accessAttemptsCount = 0,
                habitDurationYears = 2,
                onboardingSeverityLevel = "MODERATE",
                lastRelapseTimestamp = null,
                shareAccessAttempts = false,
                shareRelapses = shareRelapses,
                shareMilestones = shareMilestones,
                assignedPartnershipId = null,
                isSelected = true,
            ),
        )
        store.recoveryStreakDao().insert(
            RecoveryStreak(
                streakId = STREAK_ID,
                habitId = HABIT_ID,
                recoveryStartAt = NOW - daysElapsed * StreakTimer.DAY_MS,
                tWallLastObserved = NOW,
                rBootLastObserved = 0,
                bootCountLastObserved = 0,
                currentStreakElapsedMs = daysElapsed * StreakTimer.DAY_MS,
            ),
        )
        store.milestoneEventDao().insertAll(MilestoneEvent.MILESTONE_DAYS.map { MilestoneEvent(STREAK_ID, it) })
        MilestoneEvent.MILESTONE_DAYS.filter { it <= daysElapsed }.forEach {
            store.milestoneEventDao().markAchieved(STREAK_ID, it, NOW)
        }
        return HABIT_ID
    }

    private fun coachingStep(stepId: String, profileId: String) = CoachingStep(
        stepId = stepId,
        profileId = profileId,
        stepOrder = 1,
        actionText = "Call my brother",
        associatedTriggerWindow = "",
        kind = CoachingStep.KIND_STEP,
    )

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PROFILE_ID = "profile-1"
        const val HABIT_ID = "habit-1"
        const val STREAK_ID = "streak-1"
        const val NOW = 1_800_000_000_000L
    }
}
