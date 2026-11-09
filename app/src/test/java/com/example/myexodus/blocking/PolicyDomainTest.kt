package com.example.myexodus.blocking

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.PairProfileSharing
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room-backed PolicyVersion semantics (blocking-core plan §6: rule write + version bump in
 * one transaction; version is Room's authority, not a publisher counter) and the seeded
 * RecoveryPolicy singleton (plan §4 Policy Domain).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PolicyDomainTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var publisher: RulesSnapshotPublisher
    private lateinit var repository: RulesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        publisher = RulesSnapshotPublisher(context, factory)
        repository = RulesRepository(factory, publisher)
        runBlocking { factory.open()!!.userProfileDao().insert(profile()) }
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun repositoryWrite_bumpsRoomVersionAndSnapshotInLockstep() = runTest {
        repository.insertRule(appRule("com.instagram.android", ruleId = "r-1"))
        assertEquals(1L, publisher.snapshot.version)
        assertEquals(1L, factory.open()!!.policyVersionDao().current())

        repository.deleteRule(appRule("com.instagram.android", ruleId = "r-1"))
        assertEquals(2L, publisher.snapshot.version)
        assertEquals(2L, factory.open()!!.policyVersionDao().current())
    }

    @Test
    fun failedWrite_doesNotBumpVersion() = runTest {
        repository.insertRule(appRule("com.instagram.android", ruleId = "r-1"))
        assertEquals(1L, factory.open()!!.policyVersionDao().current())

        // Duplicate PK aborts the insert (OnConflictStrategy.ABORT) inside the write
        // transaction; the version bump must roll back with it.
        val e = runCatching { repository.insertRule(appRule("com.instagram.android", ruleId = "r-1")) }
            .exceptionOrNull()
        assertTrue("Expected constraint failure, got $e", e is SQLiteConstraintException)
        assertEquals("Version must not advance on a failed write", 1L, factory.open()!!.policyVersionDao().current())
        assertEquals(1L, publisher.snapshot.version)
    }

    @Test
    fun sweepExpired_flipsOnlyLapsedEnabledRules() = runTest {
        val now = 1_700_000_000_000L
        repository.insertRule(appRule("com.a", "r-lapsed").copy(blockExpiresAt = now - 1_000L))
        repository.insertRule(appRule("com.b", "r-future").copy(blockExpiresAt = now + 3_600_000L))
        repository.insertRule(appRule("com.c", "r-never"))
        repository.insertRule(
            appRule("com.d", "r-paused-lapsed").copy(blockExpiresAt = now - 1_000L, isEnabled = false),
        )

        repository.sweepExpired(now)

        val rules = repository.getActiveRules().associateBy { it.ruleId }
        assertEquals("#18: a lapsed enabled rule flips itself off", false, rules.getValue("r-lapsed").isEnabled)
        assertEquals("a future expiry is untouched", true, rules.getValue("r-future").isEnabled)
        assertEquals("never-expiring stays on", true, rules.getValue("r-never").isEnabled)
        assertEquals(
            "an already-disabled lapsed rule stays off — the sweep claims nothing",
            false,
            rules.getValue("r-paused-lapsed").isEnabled,
        )
    }

    /** ADR #294: a partner-visible row requests one silent delivery pass at the act — warm
     *  delivers, cold defers; nothing in this path may raise a device-key sheet. */
    @Test
    fun partnerRoutedWrites_requestAPass_onlyWhenARowWasJournaled() = runTest {
        val clock = 1_700_000_000_000L
        var journaled = 0
        val repo = RulesRepository(
            factory, publisher,
            onJournaled = { journaled++ },
            now = { clock },
        )

        repo.insertRule(
            appRule("com.a", "0b7c1a9e-3f1e-4c1e-9c1e-0123456789ab")
                .copy(assignedPairId = "pair-1", blockExpiresAt = clock - 1_000L),
        )
        assertEquals("an assigned save journals and requests the pass", 1, journaled)

        repo.sweepExpired(clock)
        assertEquals("the sweep journals its lapsed rule and requests the same pass", 2, journaled)

        repo.insertRule(appRule("com.b", "0b7c1a9e-3f1e-4c1e-9c1e-0123456789ac"))
        assertEquals("an unassigned rule tells no partner — no row, no pass", 2, journaled)
    }

    /** Owner ruling 2026-11-10: assigning a rule or batch switches that pair's blocked-app
     *  check-ins on, so the partner watching the rules hears violations without a second
     *  setup step. An unassigned write touches no consent row, and a pair that had the
     *  switch off gets it back on with a fresh watermark (no while-off backlog leaks). */
    @Test
    fun assignment_turnsCheckInsOn_forThatPairOnly() = runTest {
        val clock = 1_700_000_000_000L
        val repo = RulesRepository(factory, publisher, now = { clock })
        val dao = factory.open()!!.pairSharingDao()
        dao.upsertProfile(
            PairProfileSharing(pairId = "pair-1", shareCheckins = false, checkinsEnabledSinceMs = 5L),
        )

        repo.insertRule(appRule("com.a", "r-1").copy(assignedPairId = "pair-1"))

        val share = dao.profileFor("pair-1")!!
        assertTrue("the assignment turns check-ins on", share.shareCheckins)
        assertEquals(
            "a re-enable pins a fresh watermark",
            clock,
            share.checkinsEnabledSinceMs,
        )

        repo.insertBatch(
            listOf(
                appRule("com.b", "r-2").copy(assignedPairId = "pair-2", batchId = "b-1"),
                appRule("com.c", "r-3").copy(assignedPairId = "pair-2", batchId = "b-1"),
            ),
        )
        assertTrue(
            "a batch turns on every assigned pair, once",
            dao.profileFor("pair-2")!!.shareCheckins,
        )

        repo.insertRule(appRule("com.d", "r-4"))
        assertEquals("an unassigned rule writes no consent row", 2, dao.profileAll().size)
    }

    @Test
    fun deleteRules_removesTheBatch_andReloadsTheSnapshotOnce() = runTest {
        repository.insertRule(appRule("com.a", "r-1"))
        repository.insertRule(appRule("com.b", "r-2"))
        repository.insertRule(appRule("com.c", "r-3"))

        repository.deleteRules(repository.getActiveRules().filter { it.ruleId != "r-3" })

        assertEquals(listOf("r-3"), repository.getActiveRules().map { it.ruleId })
        assertEquals("both deletes bumped the Room version", 5L, factory.open()!!.policyVersionDao().current())
        assertEquals("and one reload republished that version", 5L, publisher.snapshot.version)
    }

    @Test
    fun recoveryPolicy_seededWithDefaults() = runTest {
        val policy = factory.open()!!.recoveryPolicyDao().current()
        assertNotNull(policy)
        assertEquals(60L, policy!!.defaultFrictionSeconds)
        assertEquals(600L, policy.overrideCooldownSeconds)
    }

    @Test
    fun recoveryPolicy_updatePersists() = runTest {
        factory.open()!!.recoveryPolicyDao().update(120L, 900L)
        val policy = factory.open()!!.recoveryPolicyDao().current()
        assertEquals(120L, policy!!.defaultFrictionSeconds)
        assertEquals(900L, policy.overrideCooldownSeconds)
    }

    @Test
    fun activeProfileQueries_agreeUnder_aStraySecondActiveRow() = runTest {
        val store = factory.open()!!
        // The 2026-10-04 rig residue shape: a second is_active=1 profile (the spike harness
        // used to insert one). Engines must consult exactly the observeActive pick — a union
        // over is_active rows would enforce rules the rules UI can never show.
        store.userProfileDao().insert(profile().copy(userId = "spike-harness-profile", name = "Spike Harness"))
        repository.insertRule(appRule("com.instagram.android", ruleId = "r-1"))
        repository.insertRule(
            appRule("com.instagram.android", ruleId = "r-2").copy(profileId = "spike-harness-profile"),
        )

        assertEquals("the deterministic pick is the lowest user_id", "p-1", store.userProfileDao().getActive()?.userId)
        assertEquals(listOf("r-1"), store.restrictionRuleDao().getActiveRules().map { it.ruleId })
        assertEquals(
            setOf("com.instagram.android"),
            publisher.snapshot.appRules.keys,
        )
        assertEquals("r-1", publisher.snapshot.appRules.getValue("com.instagram.android").ruleId)
    }

    private fun profile() = UserProfile(
        userId = "p-1",
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

    private fun appRule(packageName: String, ruleId: String) = RestrictionRule(
        ruleId = ruleId,
        profileId = "p-1",
        blockedPackage = packageName,
        targetType = "APP",
        isAlwaysOn = true,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        blockExpiresAt = 0L,
        severityLevel = "HIGH",
    )

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}
