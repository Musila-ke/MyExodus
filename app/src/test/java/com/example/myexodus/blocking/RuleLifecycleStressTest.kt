package com.example.myexodus.blocking

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.AccountabilityEventWire
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.PartnerEventJournal
import com.example.myexodus.data.db.entity.PartnerRuleChange
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stress slice for the per-rule assignment milestone (§2.1 req 1-3, §8.4 RULE_CONTROL): the
 * happy-path tests pin one assignment or one redelivery; this file drives the production write
 * path at volume and at the boundary where it actually breaks —
 *
 *  - 120 rules through the full create→assign→edit→reassign→disable→re-enable→unassign→
 *    reassign→delete chain, proving every write journals exactly the right closed verb to
 *    exactly the right pair, cross-pair isolation holds, and unassigned rules journal nothing;
 *  - pre-seeded replay plus retention sweeps on both new tables, at thousands of rows;
 *  - a pre-1970 device clock (floorDiv minutes, negative integers) still writes and reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuleLifecycleStressTest {

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
    fun assignmentChurn_atVolume_journalsOneCorrectChainPerPair() = runBlocking {
        seedProfile()
        var clock = BASE_MS
        val repository = RulesRepository(factory, RulesSnapshotPublisher(context, factory)) { clock }
        val journalDao = factory.open()!!.partnerEventJournalDao()

        repeat(RULE_COUNT) { i ->
            val ruleId = UUID.randomUUID().toString()
            val base = appRule("com.example.app$i", ruleId)

            repository.insertRule(base)                 // unassigned: nothing to journal
            clock += STEP_MS
            repository.updateRule(base.copy(assignedPairId = PAIR_A))
            clock += STEP_MS
            repository.updateRule(base.copy(assignedPairId = PAIR_A, dailyLimitMs = 60_000L))
            clock += STEP_MS
            repository.updateRule(base.copy(assignedPairId = PAIR_B))
            clock += STEP_MS
            repository.updateRule(base.copy(assignedPairId = PAIR_B, isEnabled = false))
            clock += STEP_MS
            repository.updateRule(base.copy(assignedPairId = PAIR_B))
            clock += STEP_MS
            repository.updateRule(base)                 // unassign
            clock += STEP_MS
            repository.updateRule(base.copy(assignedPairId = PAIR_A))
            clock += STEP_MS
            repository.deleteRule(base.copy(assignedPairId = PAIR_A))
            clock += STEP_MS
        }

        val startedAt = System.nanoTime()
        val aRows = journalDao.dueRuleChangesForPair(PAIR_A, 0L, clock, LIMIT)
        val bRows = journalDao.dueRuleChangesForPair(PAIR_B, 0L, clock, LIMIT)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        System.err.println("rule churn: ${RULE_COUNT * 9} writes, A=${aRows.size} B=${bRows.size} rows read in ${elapsedMs}ms")

        assertEquals("A: ASSIGNED, UPDATED, UNASSIGNED, ASSIGNED, DELETED per rule", RULE_COUNT * 5, aRows.size)
        assertEquals("B: ASSIGNED, DISABLED, UPDATED, UNASSIGNED per rule", RULE_COUNT * 4, bRows.size)
        assertTrue("cross-pair leakage into A", aRows.all { it.routePairId == PAIR_A })
        assertTrue("cross-pair leakage into B", bRows.all { it.routePairId == PAIR_B })

        // Every verb is from the closed enum, and the journal's payload matches it.
        val byRuleA = aRows.groupBy { it.ruleId }
        val byRuleB = bRows.groupBy { it.ruleId }
        assertEquals(RULE_COUNT, byRuleA.size)
        assertEquals(RULE_COUNT, byRuleB.size)
        for (rows in byRuleA.values) {
            assertEquals(listOf("ASSIGNED", "UPDATED", "UNASSIGNED", "ASSIGNED", "DELETED"), rows.map { it.ruleChange })
            assertEquals(rows.sortedBy { it.createdAtMs }, rows)
        }
        for (rows in byRuleB.values) {
            assertEquals(listOf("ASSIGNED", "DISABLED", "UPDATED", "UNASSIGNED"), rows.map { it.ruleChange })
        }
        assertTrue("every row carries the rule's UUID", aRows.all { runCatching { UUID.fromString(it.ruleId) }.isSuccess })
    }

    @Test
    fun legacyNonUuidRuleId_assignmentStillStores_butJournalsNothing() = runBlocking {
        seedProfile()
        var clock = BASE_MS
        val repository = RulesRepository(factory, RulesSnapshotPublisher(context, factory)) { clock }
        val store = factory.open()!!

        val legacy = appRule("com.example.legacy", ruleId = "legacy-rule-1")
        repository.insertRule(legacy.copy(assignedPairId = PAIR_A))

        val stored = store.restrictionRuleDao().getById("legacy-rule-1")
        assertEquals("the assignment itself is still honoured", PAIR_A, stored?.assignedPairId)
        assertEquals(
            "an unparseable id has no wire identity",
            0,
            store.partnerEventJournalDao().dueRuleChangesForPair(PAIR_A, 0L, clock, LIMIT).size,
        )
    }

    @Test
    fun receivedRuleChanges_atVolume_dedupeOnReplay_scopePerPairAndSweepOnExpiry() = runBlocking {
        val db = Room.databaseBuilder(context, AppRulesStore::class.java, DB_NAME).build()
        try {
            val dao = db.partnerRuleChangeDao()
            val pairs = listOf("pair-0", "pair-1", "pair-2")
            val rows = pairs.flatMap { pair ->
                (0 until PER_PAIR).map { i ->
                    PartnerRuleChange(
                        pairId = pair,
                        eventId = UUID.randomUUID().toString(),
                        ruleId = "rule-$i",
                        ruleChange = "UPDATED",
                        occurredAtMinute = i.toLong(),
                        receivedAtMs = NOW_MS,
                        expiresAtMs = NOW_MS + PartnerEventJournal.RETENTION_MILLIS,
                    )
                }
            }

            val insertStartedAt = System.nanoTime()
            rows.forEach { dao.insert(it) }
            rows.forEach { dao.insert(it) } // full mailbox redelivery
            System.err.println(
                "rule-change feed: ${rows.size} rows inserted + replayed in " +
                    "${(System.nanoTime() - insertStartedAt) / 1_000_000}ms",
            )

            pairs.forEach { pair ->
                val scoped = dao.recent(pair, NOW_MS, LIMIT)
                assertEquals("$pair rows, replay must not duplicate", PER_PAIR, scoped.size)
                assertTrue("$pair leakage", scoped.all { it.pairId == pair })
                assertEquals("newest first", scoped.sortedByDescending { it.occurredAtMinute }, scoped)
                val top = dao.recent(pair, NOW_MS, 50)
                assertEquals(50, top.size)
                assertEquals(PER_PAIR - 1L, top.first().occurredAtMinute)
                assertEquals(PER_PAIR - 50L, top.last().occurredAtMinute)
            }

            // Expired rows: never surfaced by the feed, and the sweep removes exactly them.
            val expired = (0 until PER_PAIR).map { i ->
                PartnerRuleChange(
                    pairId = "pair-expired",
                    eventId = UUID.randomUUID().toString(),
                    ruleId = "rule-$i",
                    ruleChange = "DELETED",
                    occurredAtMinute = i.toLong(),
                    receivedAtMs = NOW_MS - PartnerEventJournal.RETENTION_MILLIS,
                    expiresAtMs = NOW_MS,
                )
            }
            expired.forEach { dao.insert(it) }
            assertEquals("expired rows never reach the feed", 0, dao.recent("pair-expired", NOW_MS, LIMIT).size)
            assertEquals("sweep removes exactly the expired rows", PER_PAIR, dao.deleteExpired(NOW_MS))
            assertEquals("live rows survive the sweep", PER_PAIR, dao.recent("pair-0", NOW_MS, LIMIT).size)

            assertEquals("pair-scoped wipe", PER_PAIR * 2, dao.deleteFor(listOf("pair-0", "pair-1")))
            assertEquals(PER_PAIR, dao.recent("pair-2", NOW_MS, LIMIT).size)
        } finally {
            db.close()
            context.deleteDatabase(DB_NAME)
        }
    }

    @Test
    fun journalRetention_atVolume_deliversOnlyTheLiveRelationshipWindow() = runBlocking {
        val db = Room.databaseBuilder(context, AppRulesStore::class.java, DB_NAME).build()
        try {
            val dao = db.partnerEventJournalDao()
            val ruleId = UUID.randomUUID()
            val liveSince = NOW_MS - 7 * 24 * 60 * 60 * 1000L

            // 2,000 rows in three age bands: 500 past the 30-day retention (never deliverable),
            // 500 inside retention but outside the 7-day relationship window, 1,000 deliverable.
            val rows = (0 until 500).map { i ->
                journalRow(ruleId, PAIR_A, NOW_MS - 31L * DAY_MS + i * 1_000L, RuleChange.UPDATED)
            } + (0 until 500).map { i ->
                journalRow(ruleId, PAIR_A, NOW_MS - 20L * DAY_MS + i * 1_000L, RuleChange.UPDATED)
            } + (0 until 1_000).map { i ->
                journalRow(ruleId, PAIR_A, NOW_MS - 3L * DAY_MS + i * 60_000L, RuleChange.UPDATED)
            }
            rows.forEach { dao.insert(it) }

            val all = dao.dueRuleChangesForPair(PAIR_A, 0L, NOW_MS, LIMIT)
            assertEquals("expired rows excluded by the query", JOURNAL_ROWS - 500, all.size)
            assertEquals("ascending delivery order", all.sortedBy { it.createdAtMs }, all)

            val windowed = dao.dueRuleChangesForPair(PAIR_A, liveSince, NOW_MS, LIMIT)
            assertTrue("since window respected", windowed.all { it.createdAtMs >= liveSince })
            assertEquals("only the relationship-window rows deliver", 1_000, windowed.size)

            assertEquals("the sweep reclaims exactly the expired band", 500, dao.deleteExpired(NOW_MS))
            assertEquals(JOURNAL_ROWS - 500, dao.dueRuleChangesForPair(PAIR_A, 0L, NOW_MS, LIMIT).size)
        } finally {
            db.close()
            context.deleteDatabase(DB_NAME)
        }
    }

    /**
     * Stress-campaign finding (2026-10-29): the contract row rejects minutes < 1, so a device
     * clock set before 1970 used to throw out of every assigned-rule save. The write must land
     * and the journal must simply stay silent — same degradation as a legacy non-UUID rule id.
     */
    @Test
    fun pre1970Clock_ruleWriteStillLands_journalSilentlySkip() = runBlocking {
        seedProfile()
        var clock = -61_000L
        val repository = RulesRepository(factory, RulesSnapshotPublisher(context, factory)) { clock }
        val store = factory.open()!!
        val ruleId = UUID.randomUUID().toString()

        repository.insertRule(appRule("com.example.app0", ruleId).copy(assignedPairId = PAIR_A))

        assertEquals(-2L, AccountabilityEventWire.minuteOf(clock))
        assertEquals(PAIR_A, store.restrictionRuleDao().getById(ruleId)?.assignedPairId)
        assertEquals(
            "no representable occurrence minute means no journal row",
            0,
            store.partnerEventJournalDao().dueRuleChangesForPair(PAIR_A, Long.MIN_VALUE, clock, LIMIT).size,
        )

        // The moment the clock becomes representable again, journaling resumes normally.
        clock = BASE_MS
        repository.updateRule(appRule("com.example.app0", ruleId).copy(assignedPairId = PAIR_A, dailyLimitMs = 1))
        val resumed = store.partnerEventJournalDao().dueRuleChangesForPair(PAIR_A, 0L, clock, LIMIT)
        assertEquals(1, resumed.size)
        assertEquals(RuleChange.UPDATED.wire, resumed.single().ruleChange)
    }

    private fun journalRow(
        ruleId: UUID,
        pairId: String,
        createdAtMs: Long,
        change: RuleChange,
    ) = PartnerEventJournal.forRuleChange(
        eventId = UUID.randomUUID().toString(),
        ruleId = ruleId,
        change = change,
        routePairId = pairId,
        occurredAtMinute = AccountabilityEventWire.minuteOf(createdAtMs),
        createdAtMs = createdAtMs,
    )

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

    private fun appRule(packageName: String, ruleId: String) = RestrictionRule(
        ruleId = ruleId,
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
    )

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val DB_NAME = "rule-lifecycle-stress-test.db"
        const val PROFILE_ID = "p-1"
        const val PAIR_A = "pair-A"
        const val PAIR_B = "pair-B"
        const val RULE_COUNT = 120
        const val PER_PAIR = 1_500
        const val JOURNAL_ROWS = 2_000
        const val LIMIT = 100_000
        const val STEP_MS = 1_000L
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val BASE_MS = 1_760_000_000_000L
        const val NOW_MS = BASE_MS + 1_000_000L
    }
}
