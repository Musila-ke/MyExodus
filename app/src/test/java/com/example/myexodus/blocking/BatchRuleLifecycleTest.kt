package com.example.myexodus.blocking

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner mandate 2026-10-31: a named batch ("Social media") is created, paused, unshared,
 * deleted and expired as ONE object. The partner hears one batch-level sentence with its size,
 * never N member rows — [RulesRepository.batchJournalRows] is the contract under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BatchRuleLifecycleTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory

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
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun createAssignedBatch_storesEveryMember_andJournalsOneCreatedBatchRow() = runBlocking {
        seedProfile()
        var clock = BASE_MS
        val repository = RulesRepository(factory, RulesSnapshotPublisher(context, factory), now = { clock })
        val store = factory.open()!!
        val batchId = UUID.randomUUID().toString()
        val batchName = "Social media"

        val batch = listOf(
            appRule("com.example.instagram", batchId, batchName, PAIR_A),
            appRule("com.example.tiktok", batchId, batchName, PAIR_A),
            appRule("com.example.snapchat", batchId, batchName, PAIR_A),
            websiteRule("facebook.com", batchId, batchName, PAIR_A),
            websiteRule("x.com", batchId, batchName, PAIR_A),
        )
        repository.insertBatch(batch)

        val stored = store.restrictionRuleDao().getByBatch(batchId)
        assertEquals("every member shares the batch id and name", batch.size, stored.size)
        assertTrue(stored.all { it.batchName == batchName && it.assignedPairId == PAIR_A })

        val rows = store.partnerEventJournalDao().dueRuleChangesForPair(PAIR_A, 0L, clock, LIMIT)
        assertEquals("one batch row, not five member rows", 1, rows.size)
        val row = rows.single()
        assertEquals(RuleChange.CREATED.wire, row.ruleChange)
        assertEquals("the wire rule id is the batch id", batchId, row.ruleId)
        val identity = requireNotNull(row.ruleIdentityOrNull())
        assertEquals(batchName, identity.label)
        assertEquals(RuleTargetType.BATCH, identity.targetType)
        assertEquals(batch.size.toLong(), identity.itemCount)
        assertNull("a batch row is not a member row", identity.batchLabel)
    }

    @Test
    fun unassignedBatch_journalsNothing_andMixedPairsGetTheirOwnCounts() = runBlocking {
        seedProfile()
        var clock = BASE_MS
        val repository = RulesRepository(factory, RulesSnapshotPublisher(context, factory), now = { clock })
        val journalDao = factory.open()!!.partnerEventJournalDao()

        val soloBatch = UUID.randomUUID().toString()
        repository.insertBatch(
            listOf(
                appRule("com.example.solo1", soloBatch, "Solo", assignedPairId = null),
                appRule("com.example.solo2", soloBatch, "Solo", assignedPairId = null),
            ),
        )
        assertTrue(
            "an unassigned batch has no partner to tell",
            journalDao.dueRuleChangesForPair(PAIR_A, 0L, clock, LIMIT).isEmpty(),
        )

        clock += STEP_MS
        val mixedBatch = UUID.randomUUID().toString()
        repository.insertBatch(
            listOf(
                appRule("com.example.a1", mixedBatch, "Mixed", PAIR_A),
                appRule("com.example.a2", mixedBatch, "Mixed", PAIR_A),
                appRule("com.example.a3", mixedBatch, "Mixed", PAIR_A),
                appRule("com.example.b1", mixedBatch, "Mixed", PAIR_B),
                appRule("com.example.b2", mixedBatch, "Mixed", PAIR_B),
                appRule("com.example.none", mixedBatch, "Mixed", assignedPairId = null),
            ),
        )

        val aRows = journalDao.dueRuleChangesForPair(PAIR_A, 0L, clock, LIMIT)
        val bRows = journalDao.dueRuleChangesForPair(PAIR_B, 0L, clock, LIMIT)
        assertEquals(1, aRows.size)
        assertEquals(1, bRows.size)
        assertEquals("A is told its own member count", 3L, aRows.single().ruleIdentityOrNull()?.itemCount)
        assertEquals("B is told its own member count", 2L, bRows.single().ruleIdentityOrNull()?.itemCount)
        assertTrue("the unassigned member is never routed", aRows.none { it.routePairId == PAIR_B })
    }

    @Test
    fun batchPauseResumeDelete_areSingleBatchRows_inOrder() = runBlocking {
        seedProfile()
        var clock = BASE_MS
        val repository = RulesRepository(factory, RulesSnapshotPublisher(context, factory), now = { clock })
        val store = factory.open()!!
        val batchId = UUID.randomUUID().toString()

        repository.insertBatch(
            listOf(
                appRule("com.example.one", batchId, "Social media", PAIR_A),
                appRule("com.example.two", batchId, "Social media", PAIR_A),
            ),
        )
        clock += STEP_MS
        repository.setBatchEnabled(batchId, false)
        assertTrue(
            "pause switches every member off",
            store.restrictionRuleDao().getByBatch(batchId).all { !it.isEnabled },
        )
        clock += STEP_MS
        repository.setBatchEnabled(batchId, true)
        assertTrue(
            "resume switches every member on",
            store.restrictionRuleDao().getByBatch(batchId).all { it.isEnabled },
        )
        clock += STEP_MS
        repository.deleteBatch(batchId)
        assertTrue("delete removes every member", store.restrictionRuleDao().getByBatch(batchId).isEmpty())

        val rows = store.partnerEventJournalDao().dueRuleChangesForPair(PAIR_A, 0L, clock, LIMIT)
        assertEquals(4, rows.size)
        assertEquals(
            listOf(
                RuleChange.CREATED.wire,
                RuleChange.DISABLED.wire,
                RuleChange.UPDATED.wire,
                RuleChange.DELETED.wire,
            ),
            rows.sortedBy { it.createdAtMs }.map { it.ruleChange },
        )
        assertTrue("each verb is ONE batch row", rows.all { it.ruleId == batchId && it.ruleIdentityOrNull()?.targetType == RuleTargetType.BATCH })
        assertEquals("the deleted row still states the batch size", 2L, rows.last().ruleIdentityOrNull()?.itemCount)
    }

    @Test
    fun resumeDropsLapsedExpiry_soTheSweepCannotFlipTheBatchStraightOff() = runBlocking {
        seedProfile()
        var clock = BASE_MS
        val repository = RulesRepository(factory, RulesSnapshotPublisher(context, factory), now = { clock })
        val store = factory.open()!!
        val batchId = UUID.randomUUID().toString()

        val expiringAt = clock + 60_000L
        repository.insertBatch(
            listOf(
                appRule("com.example.one", batchId, "Social media", PAIR_A).copy(blockExpiresAt = expiringAt),
                appRule("com.example.two", batchId, "Social media", PAIR_A).copy(blockExpiresAt = expiringAt),
            ),
        )
        clock = expiringAt + 1
        repository.setBatchEnabled(batchId, false)
        clock += STEP_MS
        repository.setBatchEnabled(batchId, true)

        val resumed = store.restrictionRuleDao().getByBatch(batchId)
        assertTrue(resumed.all { it.isEnabled })
        assertTrue("resume means 'on again, no end date' (#18)", resumed.all { it.blockExpiresAt == 0L })

        clock += STEP_MS
        repository.sweepExpired(clock)
        assertTrue("nothing left to sweep", store.restrictionRuleDao().getByBatch(batchId).all { it.isEnabled })
    }

    @Test
    fun removePartner_journalsOneUnassignedBatchRow_routedToTheOldPair() = runBlocking {
        seedProfile()
        var clock = BASE_MS
        val repository = RulesRepository(factory, RulesSnapshotPublisher(context, factory), now = { clock })
        val store = factory.open()!!
        val batchId = UUID.randomUUID().toString()

        repository.insertBatch(
            listOf(
                appRule("com.example.one", batchId, "Social media", PAIR_A),
                websiteRule("tiktok.com", batchId, "Social media", PAIR_A),
            ),
        )
        clock += STEP_MS
        repository.removeBatchPartner(batchId)

        assertTrue(
            "every member loses the partner",
            store.restrictionRuleDao().getByBatch(batchId).all { it.assignedPairId == null },
        )
        val rows = store.partnerEventJournalDao().dueRuleChangesForPair(PAIR_A, 0L, clock, LIMIT)
        assertEquals(2, rows.size)
        assertEquals(RuleChange.UNASSIGNED.wire, rows.sortedBy { it.createdAtMs }.last().ruleChange)
        assertEquals("still told as the batch", RuleTargetType.BATCH, rows.last().ruleIdentityOrNull()?.targetType)

        clock += STEP_MS
        repository.removeBatchPartner(batchId)
        assertEquals(
            "removing an absent partner journals nothing",
            2,
            store.partnerEventJournalDao().dueRuleChangesForPair(PAIR_A, 0L, clock, LIMIT).size,
        )
    }

    @Test
    fun sweepExpired_tellsAWholeLapsedBatchOnce_butApartialLapseNamesItsMembers() = runBlocking {
        seedProfile()
        var clock = BASE_MS
        val repository = RulesRepository(factory, RulesSnapshotPublisher(context, factory), now = { clock })
        val store = factory.open()!!
        val journalDao = store.partnerEventJournalDao()

        val expiredAt = clock - 1
        val wholeBatch = UUID.randomUUID().toString()
        repository.insertBatch(
            listOf(
                appRule("com.example.one", wholeBatch, "Gaming", PAIR_A).copy(blockExpiresAt = expiredAt),
                appRule("com.example.two", wholeBatch, "Gaming", PAIR_A).copy(blockExpiresAt = expiredAt),
                appRule("com.example.three", wholeBatch, "Gaming", PAIR_A).copy(blockExpiresAt = expiredAt),
            ),
        )
        clock += STEP_MS
        repository.sweepExpired(clock)

        assertTrue(store.restrictionRuleDao().getByBatch(wholeBatch).all { !it.isEnabled })
        val wholeRows = journalDao.dueRuleChangesForPair(PAIR_A, 0L, clock, LIMIT)
            .filter { it.ruleChange == RuleChange.EXPIRED.wire }
        assertEquals("the lapsed batch is told once", 1, wholeRows.size)
        val wholeIdentity = requireNotNull(wholeRows.single().ruleIdentityOrNull())
        assertEquals(RuleTargetType.BATCH, wholeIdentity.targetType)
        assertEquals(3L, wholeIdentity.itemCount)
        assertEquals("Gaming", wholeIdentity.label)

        clock += STEP_MS
        val partialBatch = UUID.randomUUID().toString()
        repository.insertBatch(
            listOf(
                appRule("com.example.p1", partialBatch, "Study", PAIR_B).copy(blockExpiresAt = expiredAt),
                appRule("com.example.p2", partialBatch, "Study", PAIR_B),
            ),
        )
        clock += STEP_MS
        repository.sweepExpired(clock)
        val partialRows = journalDao.dueRuleChangesForPair(PAIR_B, 0L, clock, LIMIT)
            .filter { it.ruleChange == RuleChange.EXPIRED.wire }
        assertEquals("a partial lapse reports the member that ended", 1, partialRows.size)
        val memberIdentity = requireNotNull(partialRows.single().ruleIdentityOrNull())
        assertEquals(RuleTargetType.APP, memberIdentity.targetType)
        assertEquals("Study", memberIdentity.batchLabel)
        assertNotEquals(partialBatch, partialRows.single().ruleId)
        val surviving = store.restrictionRuleDao().getByBatch(partialBatch).filter { it.isEnabled }
        assertEquals("the live member keeps blocking", 1, surviving.size)
    }

    private suspend fun seedProfile() {
        factory.open()!!.userProfileDao().insert(
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

    private fun appRule(
        packageName: String,
        batchId: String,
        batchName: String,
        assignedPairId: String?,
    ) = RestrictionRule(
        ruleId = UUID.randomUUID().toString(),
        profileId = PROFILE_ID,
        blockedPackage = packageName,
        targetType = "APP",
        isAlwaysOn = true,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        blockExpiresAt = 0L,
        severityLevel = "HIGH",
        assignedPairId = assignedPairId,
        batchId = batchId,
        batchName = batchName,
    )

    private fun websiteRule(
        host: String,
        batchId: String,
        batchName: String,
        assignedPairId: String?,
    ) = appRule(host, batchId, batchName, assignedPairId).copy(targetType = "WEBSITE")

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PROFILE_ID = "p-1"
        const val PAIR_A = "pair-A"
        const val PAIR_B = "pair-B"
        const val LIMIT = 100_000
        const val STEP_MS = 1_000L
        const val BASE_MS = 1_760_000_000_000L
    }
}
