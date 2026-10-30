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
import com.example.myexodus.data.db.entity.DailyUsageTracker
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UsageLedger
import com.example.myexodus.domainusage.RuleUsageDaily
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR #239: the one-time import of a retained scope. Staging must never run without an offer,
 * must bind the record to the signed-in account (the same quarantine and dry run as the guest
 * transition then take over), and must fail closed when the copy cannot be read or written.
 * ADR #240: everything the same-device copy carries — blocks and usage on top of the logical
 * snapshot — lands in the account scope, additively and idempotently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScopeRecoveryImporterTest {

    private val now = 1_700_000_000_000L
    private val hash = "a".repeat(32)

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var rules: RulesRepository

    private val emptySnapshot = GuestSnapshot(
        habits = emptyList(),
        streaks = emptyList(),
        milestones = emptyList(),
        attempts = emptyList(),
        sessions = emptyList(),
        coaching = emptyList(),
        riskWindows = emptyList(),
        policy = null,
    )

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
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    private inner class FakeReader : ScopeSnapshotReader {
        var result: GuestSnapshot? = emptySnapshot
        var localData: RetainedLocalData? =
            RetainedLocalData(emptyList(), emptyList(), emptyList(), emptyList())
        val requested = mutableListOf<String>()
        override suspend fun snapshotOf(scopeHash: String): GuestSnapshot? {
            requested += scopeHash
            return result
        }

        override suspend fun localDataOf(scopeHash: String): RetainedLocalData? = localData
    }

    private inner class Harness(
        identity: AuthIdentity?,
        var offer: String?,
        bound: String? = offer,
    ) {
        val reader = FakeReader()
        val quarantine = FakeQuarantine()
        val importer = ScopeRecoveryImporter(
            AuthCoordinator(FakeAuthOps(identity)),
            reader,
            quarantine,
            recoveryOffer = { offer },
            storeFactory = factory,
            rules = rules,
            boundOffer = { bound },
            clock = { now },
        )
    }

    @Test
    fun withoutAnOffer_nothingIsReadOrStaged() = runTest {
        val h = Harness(accountMemberUser, offer = null)

        assertEquals(RecoveryStage.NotOffered, h.importer.stageForImport())
        assertTrue(h.reader.requested.isEmpty())
        assertTrue(h.quarantine.records.isEmpty())
        assertFalse(h.importer.pendingStaged())
    }

    @Test
    fun anUnreadableRetainedScope_reportsUnreadable_andStagesNothing() = runTest {
        val h = Harness(accountMemberUser, offer = hash)
        h.reader.result = null

        assertEquals(RecoveryStage.Failed(RecoveryFailure.UNREADABLE), h.importer.stageForImport())
        assertEquals(listOf(hash), h.reader.requested)
        assertTrue(h.quarantine.records.isEmpty())
    }

    @Test
    fun staging_bindsARecoveryRecordToTheSignedInAccount() = runTest {
        val h = Harness(accountMemberUser, offer = hash)

        assertEquals(RecoveryStage.Staged, h.importer.stageForImport())

        val record = h.quarantine.records.single()
        assertEquals("scope-$hash", record.guestUid)
        assertEquals("recovery-$hash", record.transitionId)
        assertEquals(TransitionIntent.IMPORT, record.intent)
        assertEquals(accountMemberUser.uid, record.targetUid)
        assertEquals(now, record.stagedAtMs)
        assertNull(record.importedAtMs)
        assertTrue(h.importer.pendingStaged())
        assertTrue(ScopeRecoveryImporter.isRecoveryRecord(record))
    }

    @Test
    fun stagingIsIdempotent_andAnImportedRecordIsNoLongerPending() = runTest {
        val h = Harness(accountMemberUser, offer = hash)

        h.importer.stageForImport()
        h.importer.stageForImport()
        assertEquals(1, h.quarantine.records.size)

        val done = h.quarantine.records.single().copy(importedAtMs = now)
        h.quarantine.records[0] = done
        assertFalse(h.importer.pendingStaged())
    }

    @Test
    fun aFailedStageWrite_reportsStoreUnavailable() = runTest {
        val h = Harness(accountMemberUser, offer = hash)
        h.quarantine.saveResult = false

        assertEquals(
            RecoveryStage.Failed(RecoveryFailure.STORE_UNAVAILABLE),
            h.importer.stageForImport(),
        )
        assertFalse(h.importer.pendingStaged())
    }

    @Test
    fun stageIfOffered_stagesABoundOfferExactlyOnce() = runTest {
        val h = Harness(accountMemberUser, offer = hash)

        assertTrue(h.importer.stageIfOffered())
        assertEquals(listOf(hash), h.reader.requested)
        assertEquals(1, h.quarantine.records.size)

        assertFalse("a pending copy is not staged again", h.importer.stageIfOffered())
        assertEquals(listOf(hash), h.reader.requested)
        assertEquals(1, h.quarantine.records.size)
    }

    @Test
    fun stageIfOffered_neverTouchesAnUnboundOffer() = runTest {
        // ADR #240: a guest sign-out's copy has no owning account, so it keeps the explicit card.
        val h = Harness(accountMemberUser, offer = hash, bound = null)

        assertFalse(h.importer.stageIfOffered())
        assertTrue(h.reader.requested.isEmpty())
        assertTrue(h.quarantine.records.isEmpty())
    }

    @Test
    fun stageIfOffered_doesNothingWithoutAnOffer() = runTest {
        val h = Harness(accountMemberUser, offer = null, bound = null)

        assertFalse(h.importer.stageIfOffered())
        assertTrue(h.reader.requested.isEmpty())
    }

    @Test
    fun stagingWithoutAPersistentAccount_failsClosed() = runTest {
        val h = Harness(accountGuestUser, offer = hash)

        assertEquals(
            RecoveryStage.Failed(RecoveryFailure.STORE_UNAVAILABLE),
            h.importer.stageForImport(),
        )
        assertTrue(h.quarantine.records.isEmpty())
    }

    @Test
    fun onlyTheScopePrefixMarksARecoveryRecord() {
        val guest = GuestQuarantineRecord(
            transitionId = "t".repeat(22),
            guestUid = "guest-1",
            importOperationId = "op-1",
            intent = TransitionIntent.IMPORT,
            stagedAtMs = now,
            targetUid = accountMemberUser.uid,
            snapshot = emptySnapshot,
        )
        assertFalse(ScopeRecoveryImporter.isRecoveryRecord(guest))
        assertTrue(ScopeRecoveryImporter.isRecoveryRecord(guest.copy(guestUid = "scope-$hash")))
    }

    @Test
    fun restoreLocalData_carriesTheRetainedBlocks_intoTheAccountProfile() = runTest {
        val h = Harness(accountMemberUser, offer = hash)
        h.reader.localData = RetainedLocalData(
            rules = listOf(
                rule("r1", "com.blocked.app"),
                rule("r2", "example.com", targetType = "WEBSITE", assignedPairId = "pair-1"),
            ),
            trackers = emptyList(),
            ledger = emptyList(),
            ruleUsage = emptyList(),
        )

        val restored = h.importer.restoreLocalData(hash)

        assertEquals(LocalRestore(2, 0, 0, 0), restored)
        val db = factory.open()!!
        val active = db.restrictionRuleDao().getActiveRules()
        assertEquals(2, active.size)
        assertEquals("local", active.single { it.ruleId == "r1" }.profileId)
        val web = active.single { it.ruleId == "r2" }
        assertEquals("example.com", web.blockedPackage)
        assertNull("a retained pair binding cannot outlive its transition", web.assignedPairId)
    }

    @Test
    fun restoreLocalData_dedupesByPackage_andReplaysAsACompleteNoOp() = runTest {
        val h = Harness(accountMemberUser, offer = hash)
        h.reader.localData = RetainedLocalData(
            rules = listOf(rule("r1", "com.blocked.app"), rule("r2", "com.other.app")),
            trackers = emptyList(),
            ledger = emptyList(),
            ruleUsage = emptyList(),
        )
        val db = factory.open()!!
        val profile = rules.ensureActiveProfile().userId
        db.restrictionRuleDao().insert(rule("r0", "com.blocked.app").copy(profileId = profile))

        assertEquals(LocalRestore(1, 1, 0, 0), h.importer.restoreLocalData(hash))
        assertEquals(LocalRestore(0, 2, 0, 0), h.importer.restoreLocalData(hash))
        assertEquals(2, db.restrictionRuleDao().getActiveRules().size)
    }

    @Test
    fun restoreLocalData_carriesUsage_andNeverLowersAStoredTotal() = runTest {
        val h = Harness(accountMemberUser, offer = hash)
        val day = "2026-10-30"
        h.reader.localData = RetainedLocalData(
            rules = emptyList(),
            trackers = listOf(
                DailyUsageTracker(
                    trackerId = "t1",
                    profileId = "guest-profile",
                    packageName = "com.blocked.app",
                    localDateAnchor = day,
                    timezoneIdAtAnchor = "Africa/Nairobi",
                    msUsedToday = 1_234,
                    warning1State = "DELIVERED",
                    warning2State = "NOT_DUE",
                    blockedToday = false,
                ),
            ),
            ledger = listOf(
                ledgerRow("e1", "com.blocked.app", day, 400),
                ledgerRow("e2", "com.blocked.app", day, 700),
            ),
            ruleUsage = listOf(
                RuleUsageDaily("r0", 0, day, activeMs = 300),
                RuleUsageDaily("r0", 1, day, activeMs = 200),
            ),
        )
        val db = factory.open()!!
        val profile = rules.ensureActiveProfile().userId
        // The target already counted e1 and 900 ms for r0/rev0 — recovery must not restate it.
        db.usageLedgerDao().insert(ledgerRow("e1", "com.blocked.app", day, 500).copy(id = 0))
        db.ruleUsageDao().restoreMax(listOf(RuleUsageDaily("r0", 0, day, activeMs = 900)))

        val restored = h.importer.restoreLocalData(hash)

        assertEquals("one tracker + one new ledger event", 2, restored?.usageRows)
        assertEquals(500, db.usageLedgerDao().findByEventId("e1")!!.creditedMs)
        assertEquals(700, db.usageLedgerDao().findByEventId("e2")!!.creditedMs)
        val tracker = db.dailyUsageTrackerDao().getForProfile(profile).single()
        assertEquals("com.blocked.app", tracker.packageName)
        assertEquals(1_234, tracker.msUsedToday)
        assertEquals(900, db.ruleUsageDao().loadDay(day).single { it.revision == 0L }.activeMs)
        assertEquals(200, db.ruleUsageDao().loadDay(day).single { it.revision == 1L }.activeMs)

        assertEquals("a replay adds nothing", 0, h.importer.restoreLocalData(hash)?.usageRows)
        assertEquals(2, db.usageLedgerDao().all().size)
    }

    @Test
    fun restoreLocalData_failsClosed_withoutAPersistentAccount() = runTest {
        val h = Harness(accountGuestUser, offer = hash)
        h.reader.localData = RetainedLocalData(
            listOf(rule("r1", "com.blocked.app")),
            emptyList(),
            emptyList(),
            emptyList(),
        )

        assertNull(h.importer.restoreLocalData(hash))
        assertTrue(factory.open()!!.restrictionRuleDao().getActiveRules().isEmpty())
    }

    @Test
    fun restoreLocalData_reportsNothingButTheNull_whenTheRetainedScopeCannotBeRead() = runTest {
        // The caller keeps the offer on null, so the next start retries against the same copy.
        val h = Harness(accountMemberUser, offer = hash)
        h.reader.localData = null

        assertNull(h.importer.restoreLocalData(hash))
        assertTrue(factory.open()!!.restrictionRuleDao().getActiveRules().isEmpty())
    }

    private fun rule(
        id: String,
        pkg: String,
        targetType: String = "APP",
        assignedPairId: String? = null,
    ) = RestrictionRule(
        ruleId = id,
        profileId = "guest-profile",
        blockedPackage = pkg,
        targetType = targetType,
        isAlwaysOn = true,
        dailyLimitMs = 0,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "127",
        blockExpiresAt = 0,
        severityLevel = "MEDIUM",
        assignedPairId = assignedPairId,
    )

    private fun ledgerRow(eventId: String, pkg: String, day: String, creditedMs: Long) =
        UsageLedger(
            id = 0,
            eventId = eventId,
            kind = 1,
            wallClockTsMs = now,
            elapsedStartMs = 0,
            elapsedEndMs = creditedMs,
            bootCount = 0,
            packageName = pkg,
            dayKey = day,
            creditedMs = creditedMs,
            source = "PATH_A",
            sourceVersion = "test",
        )

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}
