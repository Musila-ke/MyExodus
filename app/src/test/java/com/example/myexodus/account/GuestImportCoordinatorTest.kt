package com.example.myexodus.account

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.RulesSnapshotPublisher
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RiskWindow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account plan §8.4/§6.3/§9: the post-switch import against a real (plaintext, Robolectric)
 * target database. The dry run precedes the write, the write is one transaction that rolls
 * back whole on a conflict, the journal records the outcome, and a replay after a crash adds
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GuestImportCoordinatorTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var rules: RulesRepository
    private lateinit var quarantine: FakeQuarantine
    private lateinit var ops: FakeAuthOps
    private lateinit var auth: AuthCoordinator
    private lateinit var coordinator: GuestImportCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context,
            FakeKeyManager(),
            ActivityLogKeyManager(context),
            RulesStoreKeyManager(context),
            healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        rules = RulesRepository(factory, RulesSnapshotPublisher(context, factory))
        quarantine = FakeQuarantine()
        ops = FakeAuthOps(accountMemberUser)
        auth = AuthCoordinator(ops)
        coordinator = newCoordinator()
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    private fun newCoordinator() = GuestImportCoordinator(
        auth,
        GuestTransitionCoordinator(auth, IdleJournal(), IdleApi(), FakeStager(), quarantine),
        factory,
        quarantine,
        rules,
        GuestImportPlanner(),
        clock = { now },
    )

    private fun record(
        snapshot: GuestSnapshot,
        targetUid: String? = "member-1",
        importedAtMs: Long? = null,
        summary: ImportSummary? = null,
        transitionId: String = "t".repeat(22),
    ) = GuestQuarantineRecord(
        transitionId = transitionId,
        guestUid = "guest-1",
        importOperationId = "op-1",
        intent = TransitionIntent.IMPORT,
        stagedAtMs = now - 3_600_000L,
        targetUid = targetUid,
        importedAtMs = importedAtMs,
        summary = summary,
        snapshot = snapshot,
    )

    private fun snapshot(
        habits: List<HabitProgress> = listOf(habit("gh1")),
        streaks: List<RecoveryStreak> = emptyList(),
        milestones: List<MilestoneEvent> = emptyList(),
        attempts: List<AttemptLog> = emptyList(),
        sessions: List<RecoverySession> = emptyList(),
        coaching: List<CoachingStep> = emptyList(),
        riskWindows: List<RiskWindow> = emptyList(),
        policy: RecoveryPolicy? = null,
    ) = GuestSnapshot(habits, streaks, milestones, attempts, sessions, coaching, riskWindows, policy)

    private fun habit(id: String, name: String = "Porn") = HabitProgress(
        progressId = id,
        profileId = "guest-profile",
        targetIdentifier = name,
        currentCleanDaysStreak = 0,
        totalRelapses = 0,
        accessAttemptsCount = 2,
        habitDurationYears = 5,
        onboardingSeverityLevel = "MODERATE",
        lastRelapseTimestamp = null,
        assignedPartnershipId = null,
        isSelected = true,
    )

    @Test
    fun confirm_importsTheSnapshotIntoTheTargetDatabase_andJournalsTheOutcome() = runTest {
        val guest = snapshot(
            streaks = listOf(
                RecoveryStreak(
                    streakId = "gs1",
                    habitId = "gh1",
                    recoveryStartAt = 1_000_000L,
                    relapsedAt = 1_000_000L + 10 * day,
                    tWallLastObserved = 1_000_000L + 10 * day,
                    rBootLastObserved = 0,
                    bootCountLastObserved = 0,
                    currentStreakElapsedMs = 10 * day,
                ),
            ),
            milestones = listOf(MilestoneEvent("gs1", 7, achievedAt = 2 * day)),
            attempts = listOf(AttemptLog(occurredAtEpochMillis = 55L, ruleId = "r1", blockedTarget = "com.x", outcome = "BLOCKED")),
            sessions = listOf(
                RecoverySession(
                    id = "j1",
                    technique = "box_breathing",
                    state = RecoverySession.STATE_COMPLETED,
                    startedAtEpochMs = 56L,
                    zoneId = "Africa/Nairobi",
                    entry = RecoverySession.ENTRY_TAB,
                ),
            ),
            coaching = listOf(CoachingStep("c1", "guest-profile", 0, "Call Sam", "")),
            riskWindows = listOf(RiskWindow(startMinute = 1200, endMinute = 1380, daysMask = 127)),
        )
        quarantine.records += record(guest)

        coordinator.refresh()
        val confirming = coordinator.state.value as GuestImportState.Confirming
        assertEquals(ImportSummary(7, 0, 0, 0), confirming.summary)

        coordinator.confirm()

        assertEquals(GuestImportState.Imported(ImportSummary(7, 0, 0, 0), record(guest).purgeAtMs), coordinator.state.value)
        val db = factory.open()!!
        val importedHabit = db.habitProgressDao().getForProfile("local").single()
        assertEquals("Porn", importedHabit.targetIdentifier)
        assertEquals(1, importedHabit.totalRelapses)
        assertEquals(10 * day, importedHabit.allTimeMaxStreakElapsedMs)
        assertEquals(1, db.recoveryStreakDao().getAll().size)
        assertEquals(1, db.milestoneEventDao().getAll().size)
        assertEquals(1, db.attemptLogDao().getAll().size)
        assertEquals(1, db.recoverySessionDao().getAll().size)
        assertEquals(1, db.coachingStepDao().getForProfile("local").size)
        assertEquals(1, db.riskWindowDao().all().size)

        val journaled = quarantine.records.single()
        assertEquals(now, journaled.importedAtMs)
        assertEquals(ImportSummary(7, 0, 0, 0), journaled.summary)
    }

    @Test
    fun aCrashBeforeTheJournalWrite_replaysToANoOp_andThenRecordsTheOutcome() = runTest {
        val guest = snapshot(
            attempts = listOf(AttemptLog(occurredAtEpochMillis = 55L, ruleId = "r1", blockedTarget = "com.x", outcome = "BLOCKED")),
            riskWindows = listOf(RiskWindow(startMinute = 1200, endMinute = 1380, daysMask = 127)),
        )
        val original = record(guest)
        quarantine.records += original
        quarantine.saveResult = false

        coordinator.refresh()
        coordinator.confirm()

        val failed = coordinator.state.value as GuestImportState.Failed
        assertEquals(ImportFailure.IMPORT_FAILED, failed.failure)
        assertEquals(ImportSummary(3, 0, 0, 0), failed.summary)
        assertEquals("the rows committed even though the journal write failed", 1, factory.open()!!.attemptLogDao().getAll().size)
        assertNull("the record still says unimported", quarantine.records.single().importedAtMs)

        // Process restart: a fresh coordinator replays the same operation.
        quarantine.saveResult = true
        val restarted = newCoordinator()
        restarted.refresh()
        assertTrue(restarted.state.value is GuestImportState.Confirming)
        restarted.confirm()

        assertEquals(
            GuestImportState.Imported(ImportSummary(0, 0, 3, 0), original.purgeAtMs),
            restarted.state.value,
        )
        assertEquals("replay never duplicates", 1, factory.open()!!.attemptLogDao().getAll().size)
        assertEquals(now, quarantine.records.single().importedAtMs)
    }

    @Test
    fun confirm_rollsBackTheWholeTransactionWhenTheTargetChanged() = runTest {
        val db = factory.open()!!
        rules.ensureActiveProfile()
        repeat(RiskWindow.MAX_WINDOWS) {
            db.riskWindowDao().insert(
                RiskWindow(startMinute = 60 * it, endMinute = 60 * it + 30, daysMask = 127),
            )
        }
        quarantine.records += record(
            snapshot(
                riskWindows = listOf(RiskWindow(startMinute = 1200, endMinute = 1380, daysMask = 127)),
            ),
        )

        coordinator.refresh()
        assertTrue((coordinator.state.value as GuestImportState.Confirming).conflicts.isNotEmpty())

        coordinator.confirm()

        val rechecked = coordinator.state.value as GuestImportState.Confirming
        assertEquals(
            ImportConflictTable.RISK_WINDOWS,
            rechecked.conflicts.single().table,
        )
        assertTrue("nothing from the aborted plan is written", db.habitProgressDao().getForProfile("local").isEmpty())
        assertEquals(RiskWindow.MAX_WINDOWS, db.riskWindowDao().all().size)
        assertNull(quarantine.records.single().importedAtMs)
    }

    @Test
    fun portablePreferences_areOptIn_andAppliedWhenChosen() = runTest {
        val db = factory.open()!!
        db.recoveryPolicyDao().put(RecoveryPolicy(defaultFrictionSeconds = 10, overrideCooldownSeconds = 20))
        val guestPolicy = RecoveryPolicy(defaultFrictionSeconds = 30, overrideCooldownSeconds = 60)
        quarantine.records += record(snapshot(policy = guestPolicy))

        coordinator.refresh()
        val off = coordinator.state.value as GuestImportState.Confirming
        assertTrue(off.portablePreferencesAvailable)
        assertEquals(false, off.portablePreferences)

        coordinator.preview(includePortablePreferences = true)
        assertEquals(true, (coordinator.state.value as GuestImportState.Confirming).portablePreferences)

        coordinator.confirm()

        assertEquals(guestPolicy, db.recoveryPolicyDao().current())
        assertTrue(coordinator.state.value is GuestImportState.Imported)
    }

    @Test
    fun deleteQuarantine_removesOnlyThisTargetsCopies() = runTest {
        quarantine.records += record(snapshot())
        quarantine.records += record(
            snapshot(),
            targetUid = "another-member",
            transitionId = "u".repeat(22),
        )

        coordinator.deleteQuarantine()

        assertEquals(GuestImportState.Idle, coordinator.state.value)
        assertEquals(listOf("another-member"), quarantine.records.map { it.targetUid })
        assertEquals(1, quarantine.deleted.size)
    }

    @Test
    fun refresh_reportsAnUnreadableCopy_insteadOfSilentlyLookingEmpty() = runTest {
        quarantine.unreadable = 1

        coordinator.refresh()

        assertEquals(GuestImportState.Failed(ImportFailure.UNREADABLE, null), coordinator.state.value)
    }

    @Test
    fun anAlreadyImportedRecord_showsTheCompletedCardOnColdStart() = runTest {
        val summary = ImportSummary(5, 1, 2, 0)
        quarantine.records += record(snapshot(), importedAtMs = now - 1_000L, summary = summary)

        coordinator.refresh()

        assertEquals(
            GuestImportState.Imported(summary, record(snapshot()).purgeAtMs),
            coordinator.state.value,
        )
    }

    @Test
    fun confirm_isIgnoredWhenTheTargetSessionChangedMidFlight() = runTest {
        quarantine.records += record(snapshot(
            riskWindows = listOf(RiskWindow(startMinute = 1200, endMinute = 1380, daysMask = 127)),
        ))
        coordinator.refresh()
        assertTrue(coordinator.state.value is GuestImportState.Confirming)

        // The target signed out before the user confirmed: durable identity is no longer the
        // record's target, so nothing may be written.
        ops.become(null)

        coordinator.confirm()

        assertTrue(factory.open()!!.riskWindowDao().all().isEmpty())
        assertNull(quarantine.records.single().importedAtMs)
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    /** The import coordinator reads only [GuestTransitionCoordinator.state]; these stay idle. */
    private class IdleJournal : GuestTransitionJournal {
        override fun load(): JournalLoad = JournalLoad.Absent
        override fun save(record: GuestTransitionRecord) = Unit
        override fun clear() = Unit
    }

    private class IdleApi : GuestTransitionApi {
        override suspend fun prepare(
            transitionId: String,
            capability: String,
            intent: TransitionIntent,
        ): PrepareOutcome = PrepareOutcome(SERVER_PREPARED)

        override suspend fun finalize(
            transitionId: String,
            capability: String,
            intent: TransitionIntent,
        ): FinalizeOutcome = FinalizeOutcome(emptyList(), null)

        override suspend fun cancel(transitionId: String, capability: String) = Unit
    }
}
