package com.example.myexodus.domainusage

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.AppRulesStore
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
 * §8 S7.1 against real Room + real SQLite (Robolectric): the `MAX` upsert semantics INV-5 rests
 * on, the retention delete's strict cutoff, and the D-32 revision query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuleUsageDaoTest {

    private lateinit var context: Context
    private lateinit var db: AppRulesStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        db = Room.databaseBuilder(context, AppRulesStore::class.java, DB_NAME).build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun snapshot(
        ruleId: String = "r1",
        revision: Long = 0L,
        dayKey: String = "2026-09-20",
        totalMs: Long = 5_000L,
    ) = UsageSnapshot(ruleId, revision, dayKey, totalMs, generation = 1L)

    @Test
    fun theFirstUpsertInsertsWhileThoseAfterItOnlyEverRaiseTheStoredTotal() = runBlocking {
        val dao = db.ruleUsageDao()

        dao.upsertMax(listOf(snapshot(totalMs = 5_000L)))
        assertEquals(5_000L, dao.loadDay("2026-09-20").single().activeMs)

        dao.upsertMax(listOf(snapshot(totalMs = 4_000L)))
        assertEquals(
            "INV-5: an absolute total below the stored one never lands",
            5_000L,
            dao.loadDay("2026-09-20").single().activeMs,
        )

        dao.upsertMax(listOf(snapshot(totalMs = 6_000L)))
        assertEquals(6_000L, dao.loadDay("2026-09-20").single().activeMs)
    }

    @Test
    fun replayingTheSameCycleIsIdempotent_soACrashRetryCannotDuplicateRows() = runBlocking {
        val dao = db.ruleUsageDao()
        val rows = listOf(snapshot(totalMs = 5_000L), snapshot(ruleId = "r2", totalMs = 1_000L))

        dao.upsertMax(rows)
        dao.upsertMax(rows)

        assertEquals(2, dao.loadDay("2026-09-20").size)
        assertEquals(mapOf("r1" to 5_000L, "r2" to 1_000L), byRule(dao.loadDay("2026-09-20")))
    }

    @Test
    fun rowsAreIndependentPerRuleRevisionAndDay() = runBlocking {
        val dao = db.ruleUsageDao()

        dao.upsertMax(
            listOf(
                snapshot(ruleId = "r1", revision = 0L, dayKey = "2026-09-19", totalMs = 900L),
                snapshot(ruleId = "r1", revision = 0L, dayKey = "2026-09-20", totalMs = 1_000L),
                snapshot(ruleId = "r1", revision = 2L, dayKey = "2026-09-20", totalMs = 2_000L),
            ),
        )
        dao.upsertMax(listOf(snapshot(ruleId = "r1", revision = 2L, dayKey = "2026-09-20", totalMs = 1_500L)))

        val today = byKey(dao.loadDay("2026-09-20"))
        assertEquals(1_000L, today["r1:0"])
        assertEquals("another revision of the same rule is its own row", 2_000L, today["r1:2"])
        assertEquals(900L, dao.loadDay("2026-09-19").single().activeMs)
    }

    @Test
    fun maxRevisionsReportsTheHighestEverPersistedPerRule() = runBlocking {
        val dao = db.ruleUsageDao()
        dao.upsertMax(
            listOf(
                snapshot(ruleId = "r1", revision = 0L, dayKey = "2026-09-19", totalMs = 1L),
                snapshot(ruleId = "r1", revision = 3L, dayKey = "2026-09-20", totalMs = 1L),
                snapshot(ruleId = "r2", revision = 7L, dayKey = "2026-09-18", totalMs = 1L),
            ),
        )

        assertEquals(
            mapOf("r1" to 3L, "r2" to 7L),
            dao.maxRevisions().associate { it.ruleId to it.revision },
        )
    }

    @Test
    fun deleteOlderThanKeepsTheCutoffDayItself() = runBlocking {
        val dao = db.ruleUsageDao()
        dao.upsertMax(
            listOf(
                snapshot(dayKey = "2026-08-20", totalMs = 1L),
                snapshot(dayKey = "2026-08-21", totalMs = 1L),
                snapshot(dayKey = "2026-09-20", totalMs = 1L),
            ),
        )

        dao.deleteOlderThan("2026-08-21")

        assertEquals(0, dao.loadDay("2026-08-20").size)
        assertEquals("§6.8's cutoff is strict: the boundary day is retained", 1, dao.loadDay("2026-08-21").size)
        assertEquals(1, dao.loadDay("2026-09-20").size)

        dao.deleteOlderThan("2026-08-21")
        assertEquals("retention runs are idempotent", 1, dao.loadDay("2026-08-21").size)
    }

    private fun byRule(rows: List<RuleUsageDaily>): Map<String, Long> =
        rows.associate { it.ruleId to it.activeMs }

    private fun byKey(rows: List<RuleUsageDaily>): Map<String, Long> =
        rows.associate { "${it.ruleId}:${it.revision}" to it.activeMs }

    private companion object {
        const val DB_NAME = "rule-usage-dao-test.db"
    }
}
