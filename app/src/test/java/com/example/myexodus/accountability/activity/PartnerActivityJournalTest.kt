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
import com.example.myexodus.data.db.entity.PairProfileSharing
import com.example.myexodus.data.db.entity.PairStreakSharing
import com.example.myexodus.data.db.entity.PartnerActivityOutbox
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.UserProfile
import com.example.myexodus.streaks.StreakTimer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Journal-first emission contract (partner-activity plan §7 Step 5, Appendix A.3) on a real
 * Room store: the snapshot is the committed post-transition state (achieved milestones of the
 * *current* streak only), the v16 consent matrix gates every kind (per (pair, habit)
 * `pair_streak_sharing` for the habit kinds, per-pair `pair_profile_sharing` for coaching; the
 * Req 15 message needs the addressed pair to have a visible habit), wire v2 carries the
 * streak's name and the relapse/milestone day counts, and an emission inside a rolled-back
 * transaction leaves no journal row behind.
 *
 * Rows are read back through `pendingForPair` — the reconcile view, consent filter included:
 * a row is listed only for a pair whose switch is on and whose enable watermark predates it.
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
        val habitId = seedHabit(daysElapsed = 7, relapses = 2)
        seedStreakConsent()
        store.coachingStepDao().insert(coachingStep("c-1", PROFILE_ID))
        store.coachingStepDao().insert(coachingStep("c-2", PROFILE_ID))

        assertTrue(
            PartnerActivityJournal.emitForHabit(store, habitId, EventPayload.TYPE_STREAK_UPDATED, NOW),
        )

        val row = journalRows().single()
        assertEquals(EventPayload.TYPE_STREAK_UPDATED, row.kind)
        assertEquals("the consent key the reconcile filter joins on (v16)", HABIT_ID, row.habitProgressId)
        assertEquals("7 completed days = the displayed count (ADR #176)", 7, row.daysFree)
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
        val habitId = seedHabit()
        seedStreakConsent(shareRelapses = true)

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
        val habitId = seedHabit(daysElapsed = 35)
        seedStreakConsent(shareMilestones = true)

        PartnerActivityJournal.emitForHabit(store, habitId, EventPayload.TYPE_MILESTONE_REACHED, NOW)

        // 7 and 30 crossed at 35 days; 90 not.
        assertEquals("7,30", journalRows().single().milestonesReached)
    }

    // --- Consent matrix (v16/ADR #135) --------------------------------------------------------

    /** "Nothing until you choose": no consent row for any pair → nothing journals, so nothing
     *  can ever deliver. */
    @Test
    fun consentRows_absent_refusesEveryHabitKind() = runBlocking {
        seedProfile()
        val habitId = seedHabit()

        assertFalse(PartnerActivityJournal.emitForHabit(store, habitId, EventPayload.TYPE_STREAK_UPDATED, NOW))
        assertFalse(PartnerActivityJournal.emitForHabit(store, habitId, EventPayload.TYPE_MILESTONE_REACHED, NOW))
        assertFalse(PartnerActivityJournal.emitForHabit(store, habitId, EventPayload.TYPE_RELAPSE_RECORDED, NOW))
        assertTrue("a refused emission must write nothing", journalRows().isEmpty())
    }

    /** v16: the per-streak master gates every habit-anchored kind — category flags that are on
     *  cannot leak anything (name included) while the master is off. */
    @Test
    fun shareStreakMaster_off_silencesEveryHabitKindAndItsName() = runBlocking {
        seedProfile()
        val muted = seedHabit()
        seedStreakConsent(shareStreak = false, shareMilestones = true, shareRelapses = true)

        assertFalse(PartnerActivityJournal.emitForHabit(store, muted, EventPayload.TYPE_STREAK_UPDATED, NOW))
        assertFalse(PartnerActivityJournal.emitForHabit(store, muted, EventPayload.TYPE_MILESTONE_REACHED, NOW))
        assertFalse(PartnerActivityJournal.emitForHabit(store, muted, EventPayload.TYPE_RELAPSE_RECORDED, NOW))
        assertTrue("no row may carry the streak's name while the master is off", journalRows().isEmpty())
    }

    @Test
    fun consentFlags_arePerKindNotAllOrNothing() = runBlocking {
        seedProfile()
        val milestonesOnly = seedHabit()
        seedStreakConsent(shareStreak = true, shareMilestones = true, shareRelapses = false)

        assertTrue(PartnerActivityJournal.emitForHabit(store, milestonesOnly, EventPayload.TYPE_STREAK_UPDATED, NOW))
        assertFalse(PartnerActivityJournal.emitForHabit(store, milestonesOnly, EventPayload.TYPE_RELAPSE_RECORDED, NOW))
    }

    /** Owner directive 2026-11-09: the add / rename / delete acts ride the streak master
     *  alone (like the daily snapshot), and each row carries the streak's name. */
    @Test
    fun streakManagementKinds_rideTheStreakMaster_andCarryTheName() = runBlocking {
        seedProfile()
        val habitId = seedHabit()
        val kinds = listOf(
            EventPayload.TYPE_STREAK_SHARED,
            EventPayload.TYPE_STREAK_RENAMED,
            EventPayload.TYPE_STREAK_DELETED,
        )
        seedStreakConsent(shareStreak = false, shareMilestones = true, shareRelapses = true)

        kinds.forEach { kind ->
            assertFalse(
                "$kind must not journal while the master is off",
                PartnerActivityJournal.emitForHabit(store, habitId, kind, NOW),
            )
        }
        assertTrue(journalRows().isEmpty())

        seedStreakConsent(shareStreak = true, shareMilestones = false, shareRelapses = false)
        kinds.forEach { kind ->
            assertTrue(
                "$kind needs the master only, not the category flags",
                PartnerActivityJournal.emitForHabit(store, habitId, kind, NOW),
            )
        }

        assertEquals(kinds, journalRows().map { it.kind })
        assertTrue("each row names the streak", journalRows().all { it.habitLabel == "Instagram" })
    }

    /** v16: coaching needs the pair's recovery consent AND a visible habit to anchor its
     *  snapshot to — either one missing refuses the row (its aggregate must be truthful). */
    @Test
    fun coachingStep_needsRecoveryConsent_andAVisibleHabit() = runBlocking {
        seedProfile()
        store.coachingStepDao().insert(coachingStep("c-1", PROFILE_ID))

        assertFalse(
            "no consent at all — refused",
            PartnerActivityJournal.emitForProfile(store, PROFILE_ID, EventPayload.TYPE_COACHING_STEP_UPDATED, NOW),
        )
        seedProfileConsent(shareRecovery = true)
        assertFalse(
            "recovery consent but no visible habit — no truthful anchor",
            PartnerActivityJournal.emitForProfile(store, PROFILE_ID, EventPayload.TYPE_COACHING_STEP_UPDATED, NOW),
        )
        seedHabit()
        seedStreakConsent(shareStreak = true, shareMilestones = false, shareRelapses = false)
        assertTrue(
            PartnerActivityJournal.emitForProfile(store, PROFILE_ID, EventPayload.TYPE_COACHING_STEP_UPDATED, NOW),
        )
        assertEquals(
            listOf(EventPayload.TYPE_COACHING_STEP_UPDATED),
            journalRows().map { it.kind },
        )
    }

    // --- Wire v2 (ADR #134) -------------------------------------------------------------------

    /** The partner learns *which* streak a relapse concerns and how long it ran. */
    @Test
    fun relapseRow_carriesTheEndedDayCount_andTheStreakName() = runBlocking {
        seedProfile()
        val habitId = seedHabit()
        seedStreakConsent(shareRelapses = true)

        assertTrue(
            PartnerActivityJournal.emitForHabit(
                store, habitId, EventPayload.TYPE_RELAPSE_RECORDED, NOW, endedDays = 12,
            ),
        )

        val row = journalRows().single()
        assertEquals(12, row.endedDays)
        assertEquals("Instagram", row.habitLabel)
        assertNull(row.milestoneDay)
    }

    @Test
    fun milestoneRow_carriesTheDayReached_andTheStreakName() = runBlocking {
        seedProfile()
        val habitId = seedHabit(daysElapsed = 30)
        seedStreakConsent(shareMilestones = true)

        assertTrue(
            PartnerActivityJournal.emitForHabit(
                store, habitId, EventPayload.TYPE_MILESTONE_REACHED, NOW, milestoneDay = 30,
            ),
        )

        val row = journalRows().single()
        assertEquals(30, row.milestoneDay)
        assertEquals("Instagram", row.habitLabel)
        assertNull(row.endedDays)
    }

    /** Profile-anchored kinds carry no streak identity on the wire. */
    @Test
    fun motivationalMessage_ridesNameless() = runBlocking {
        seedProfile()
        seedHabit()
        seedStreakConsent()

        assertTrue(
            PartnerActivityJournal.emitForProfile(
                store, PROFILE_ID, EventPayload.TYPE_MOTIVATIONAL_MESSAGE, NOW, text = "hi",
            ),
        )

        val row = journalRows().single()
        assertNull(row.habitLabel)
        assertNull(row.endedDays)
        assertNull(row.milestoneDay)
    }

    @Test
    fun motivationalMessage_carriesItsText_underTheUniformSnapshot() = runBlocking {
        seedProfile()
        seedHabit(daysElapsed = 3)
        seedStreakConsent(shareStreak = true, shareMilestones = false, shareRelapses = false)

        assertTrue(
            PartnerActivityJournal.emitForProfile(
                store, PROFILE_ID, EventPayload.TYPE_MOTIVATIONAL_MESSAGE, NOW,
                text = "You've got this — day by day.",
            ),
        )

        val row = journalRows().single()
        assertEquals("You've got this — day by day.", row.text)
        assertEquals("the message rides the sender's own snapshot (3 completed days)", 3, row.daysFree)
    }

    /** ADR #285: a pure supervisor messages with nothing at all — no habit of their own, no
     *  consent rows. The row is journaled and pendable for the addressed pair. */
    @Test
    fun motivationalMessage_needsNoSharingAtAll() = runBlocking {
        seedProfile()

        assertTrue(
            PartnerActivityJournal.emitForProfile(
                store, PROFILE_ID, EventPayload.TYPE_MOTIVATIONAL_MESSAGE, NOW,
                text = "Checking in on you.", targetPairId = PAIR_ID,
            ),
        )

        val row = journalRows().single()
        assertEquals(EventPayload.TYPE_MOTIVATIONAL_MESSAGE, row.kind)
        assertEquals("Checking in on you.", row.text)
        assertEquals("no habit to anchor, and none is needed", null, row.habitProgressId)
    }

    /** The other half of the old gate: a habit exists, but this pair is not consented to it. */
    @Test
    fun motivationalMessage_withAHabitButNoConsent_isAccepted() = runBlocking {
        seedProfile()
        seedHabit(daysElapsed = 3)

        assertTrue(
            PartnerActivityJournal.emitForProfile(
                store, PROFILE_ID, EventPayload.TYPE_MOTIVATIONAL_MESSAGE, NOW,
                text = "Checking in on you.", targetPairId = PAIR_ID,
            ),
        )

        assertEquals(EventPayload.TYPE_MOTIVATIONAL_MESSAGE, journalRows().single().kind)
    }

    /**
     * §10.6 / Req 15 choke point (v16 comment: "a body the partner's phone would refuse to pin
     * must never be sent"): the input domain — blank, whitespace-only, exact bound, over bound,
     * and NFC folding before the length check — pinned with consent present so only the text
     * bound can be the refusal.
     */
    @Test
    fun motivationalMessage_textBound_isEnforcedAtTheJournalChokePoint() = runBlocking {
        seedProfile()
        seedHabit()
        seedStreakConsent()

        assertNull(PartnerActivityJournal.normalizeMessage(""))
        assertNull(PartnerActivityJournal.normalizeMessage("   \n\t "))
        val max = "x".repeat(EventPayload.MAX_ACTIVITY_TEXT_LENGTH)
        assertEquals(max, PartnerActivityJournal.normalizeMessage("  $max  "))
        assertNull(PartnerActivityJournal.normalizeMessage("x".repeat(EventPayload.MAX_ACTIVITY_TEXT_LENGTH + 1)))
        assertEquals("NFC folds before the bound: e + combining accent = one char", "é", PartnerActivityJournal.normalizeMessage("  é  "))

        assertFalse(
            "a refused body emits nothing",
            PartnerActivityJournal.emitForProfile(
                store, PROFILE_ID, EventPayload.TYPE_MOTIVATIONAL_MESSAGE, NOW, text = "x".repeat(EventPayload.MAX_ACTIVITY_TEXT_LENGTH + 1),
            ),
        )
        assertTrue(journalRows().isEmpty())
        assertTrue(
            PartnerActivityJournal.emitForProfile(
                store, PROFILE_ID, EventPayload.TYPE_MOTIVATIONAL_MESSAGE, NOW, text = max,
            ),
        )
        assertEquals(max, journalRows().single().text)
    }

    // --- Addressing (#96, schema v14) ---------------------------------------------------------

    @Test
    fun targetedMessage_pendsOnlyForItsPair_whileSnapshotsStillFanOut() = runBlocking {
        seedProfile()
        val habitId = seedHabit(daysElapsed = 4)
        // Both pairs consented to the streak: consent is orthogonal to addressing, and this
        // test isolates the addressing — pair-2 sees the fan-out row, never the message.
        seedStreakConsent(pairId = PAIR_ID)
        seedStreakConsent(pairId = "pair-2")

        assertTrue(
            PartnerActivityJournal.emitForProfile(
                store, PROFILE_ID, EventPayload.TYPE_MOTIVATIONAL_MESSAGE, NOW,
                text = "For you only", targetPairId = PAIR_ID,
            ),
        )
        assertTrue(PartnerActivityJournal.emitForHabit(store, habitId, EventPayload.TYPE_STREAK_UPDATED, NOW))

        val target = store.partnerActivityDao().pendingForPair(PAIR_ID, sinceMs = 0L, limit = 100)
        val message = target.single { it.kind == EventPayload.TYPE_MOTIVATIONAL_MESSAGE }
        assertEquals("the addressed row carries its recipient", PAIR_ID, message.targetPairId)
        assertNull(
            "the state snapshot stays untargeted — every pair receives it",
            target.single { it.kind == EventPayload.TYPE_STREAK_UPDATED }.targetPairId,
        )
        assertEquals(
            "another pair sees only the fan-out snapshot, never the message",
            listOf(EventPayload.TYPE_STREAK_UPDATED),
            store.partnerActivityDao().pendingForPair("pair-2", sinceMs = 0L, limit = 100).map { it.kind },
        )
    }

    // --- Enable watermark (v16) ---------------------------------------------------------------

    /** Enabling a switch never leaks the while-off backlog: rows written before the false→true
     *  transition carry an earlier `created_at_ms` than the watermark and are filtered per pair,
     *  while a pair that consented all along still receives them. */
    @Test
    fun enableWatermark_filtersTheWhileOffBacklog_perPair() = runBlocking {
        seedProfile()
        val habitId = seedHabit()
        seedStreakConsent(pairId = "pair-2", shareStreak = true)
        assertTrue(PartnerActivityJournal.emitForHabit(store, habitId, EventPayload.TYPE_STREAK_UPDATED, NOW))

        // pair-1 enables the streak master only now — the watermark sits after the first row.
        store.pairSharingDao().upsertStreak(
            PairStreakSharing(
                pairId = PAIR_ID, habitProgressId = HABIT_ID,
                shareStreak = true, shareRelapses = false, shareMilestones = false,
                streakEnabledSinceMs = NOW + 1_000,
                relapseEnabledSinceMs = 0, milestoneEnabledSinceMs = 0,
            ),
        )

        assertTrue(
            "the pre-enable row must not be handed to pair-1",
            store.partnerActivityDao().pendingForPair(PAIR_ID, sinceMs = 0L, limit = 100).isEmpty(),
        )
        assertEquals(
            "the pair that consented all along still receives it",
            1,
            store.partnerActivityDao().pendingForPair("pair-2", sinceMs = 0L, limit = 100).size,
        )

        assertTrue(PartnerActivityJournal.emitForHabit(store, habitId, EventPayload.TYPE_STREAK_UPDATED, NOW + 2_000))
        assertEquals(
            "a row written after the enable watermark delivers",
            1,
            store.partnerActivityDao().pendingForPair(PAIR_ID, sinceMs = 0L, limit = 100).size,
        )
    }

    // --- Un-share revoke (owner ruling 2026-11-09) --------------------------------------------

    /** The revoke is written after share_streak flipped off, so both gates must accept it on
     *  the consent row's existence alone — and it must name the streak that left the view. */
    @Test
    fun sharingStopped_survivesTheConsentFlip_andDeliversToThatPair() = runBlocking {
        seedProfile()
        val habitId = seedHabit()
        seedStreakConsent(shareStreak = true)
        // The repository's write order: consent off first, then the revoke row.
        seedStreakConsent(shareStreak = false)

        assertTrue(
            PartnerActivityJournal.emitForHabit(
                store, habitId, EventPayload.TYPE_STREAK_SHARING_STOPPED, NOW,
                targetPairId = PAIR_ID,
            ),
        )

        val row = store.partnerActivityDao().pendingForPair(PAIR_ID, sinceMs = 0L, limit = 100).single()
        assertEquals(EventPayload.TYPE_STREAK_SHARING_STOPPED, row.kind)
        assertEquals("the partner is told which streak left their view", "Instagram", row.habitLabel)
        assertEquals("it concerns one pair only", PAIR_ID, row.targetPairId)
    }

    /** No consent row ever existed for this habit: there was no sharing to revoke. */
    @Test
    fun sharingStopped_withoutAConsentRow_isRefused() = runBlocking {
        seedProfile()
        val habitId = seedHabit()

        assertFalse(
            PartnerActivityJournal.emitForHabit(
                store, habitId, EventPayload.TYPE_STREAK_SHARING_STOPPED, NOW,
                targetPairId = PAIR_ID,
            ),
        )
        assertTrue(store.partnerActivityDao().pendingForPair(PAIR_ID, 0L, 100).isEmpty())
    }

    // --- Atomicity (Step 5 validation) --------------------------------------------------------

    @Test
    fun emissionInsideARolledBackTransaction_leavesNoJournalRow() = runBlocking {
        seedProfile()
        val habitId = seedHabit()
        seedStreakConsent()

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
        store.partnerActivityDao().pendingForPair(PAIR_ID, sinceMs = 0L, limit = 100)

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

    /** The v16 consent row for ([pairId], the seeded habit); watermarks 0 so rows at [NOW]
     *  clear the enable bound and the test isolates the flag. */
    private suspend fun seedStreakConsent(
        pairId: String = PAIR_ID,
        shareStreak: Boolean = true,
        shareRelapses: Boolean = false,
        shareMilestones: Boolean = false,
    ) {
        store.pairSharingDao().upsertStreak(
            PairStreakSharing(
                pairId = pairId, habitProgressId = HABIT_ID,
                shareStreak = shareStreak, shareRelapses = shareRelapses,
                shareMilestones = shareMilestones,
                streakEnabledSinceMs = 0, relapseEnabledSinceMs = 0, milestoneEnabledSinceMs = 0,
            ),
        )
    }

    private suspend fun seedProfileConsent(
        pairId: String = PAIR_ID,
        shareCheckIns: Boolean = false,
        shareRecovery: Boolean = false,
    ) {
        store.pairSharingDao().upsertProfile(
            PairProfileSharing(
                pairId = pairId, shareCheckins = shareCheckIns, shareRecovery = shareRecovery,
                checkinsEnabledSinceMs = 0, recoveryEnabledSinceMs = 0,
            ),
        )
    }

    /** Habit + one active streak + its 7/30/90 rows; milestones up to [daysElapsed] stamped. */
    private suspend fun seedHabit(daysElapsed: Int = 0, relapses: Int = 0): String {
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
        const val PAIR_ID = "pair-1"
        const val NOW = 1_800_000_000_000L
    }
}
