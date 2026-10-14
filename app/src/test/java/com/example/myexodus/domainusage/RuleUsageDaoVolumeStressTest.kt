package com.example.myexodus.domainusage

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.AppRulesStore
import java.time.LocalDate
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
 * Stress slice A7, storage half (backlog `project_stress_test_backlog`): `RuleUsageDaoTest`
 * pins the DAO's semantics on a handful of rows; this file drives the same real Room + SQLite
 * schema at the volume and calendar edges production actually hits — 250 rules × 40 days =
 * 10,000 rows spanning the **2024 leap boundary** (2024-02-20 .. 2024-03-30, leap day inside),
 * the retention cutoff *derived by the production code* (`UsagePersistence.retentionCutoff`)
 * deleting at strict-string volume, the MAX upsert surviving a full 10k replay/re-lower, and
 * the D-32 `MAX(revision)` GROUP BY across the whole table. Wall-time is printed with a
 * generous ceiling: only a complexity regression (per-row transaction, per-row
 * read-then-write) can blow it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RuleUsageDaoVolumeStressTest {

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

    private fun snapshot(ruleId: String, revision: Long, dayKey: String, totalMs: Long) =
        UsageSnapshot(ruleId, revision, dayKey, totalMs, generation = 1L)

    /** Rule index i always totals [expectedActiveMs]; a crash-retry replays the same basis. */
    private fun rowsFor(totalFor: (Int) -> Long = ::expectedActiveMs): List<UsageSnapshot> =
        DAYS.flatMap { day ->
            RULES.mapIndexed { i, rule -> snapshot(rule, i.toLong(), day, totalFor(i)) }
        }

    private suspend fun countAll(dao: RuleUsageDao): Int = DAYS.sumOf { dao.loadDay(it).size }

    private suspend fun assertAllDays(dao: RuleUsageDao, expectedTotal: (Int) -> Long) {
        val expected = RULES.indices.associate { "r$it" to expectedTotal(it) }
        for (day in DAYS) {
            assertEquals("$day totals", expected, dao.loadDay(day).associate { it.ruleId to it.activeMs })
        }
    }

    @Test
    fun bulkUpsert_tenThousandRows_overTheLeapBoundary_timedAndSpotChecked() = runBlocking {
        val dao = db.ruleUsageDao()
        val rows = rowsFor()
        assertEquals(RULE_COUNT * DAY_COUNT, rows.size)

        val startedAt = System.nanoTime()
        dao.upsertMax(rows)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        System.err.println("A7 dao volume: ${rows.size} rows upserted in ${elapsedMs}ms")
        // ~1.7ms/row here, matching PF-7's device figure (1.64ms/row) — the per-row suspend-DAO
        // path dominates, same on Robolectric. The ceiling only trips on a complexity regression
        // (a second statement per row, a per-row transaction), not on machine variance.
        assertTrue("${elapsedMs}ms for ${rows.size} rows — per-row cost regression?", elapsedMs < 60_000)

        assertEquals(RULE_COUNT * DAY_COUNT, countAll(dao))
        val leap = dao.loadDay("2024-02-29")
        assertEquals("the leap day keeps one row per rule", RULE_COUNT, leap.size)
        assertEquals(
            "exact totals on the leap day",
            RULES.indices.associate { "r$it" to expectedActiveMs(it) },
            leap.associate { it.ruleId to it.activeMs },
        )
        assertEquals("the day after the leap boundary is present too", RULE_COUNT, dao.loadDay("2024-03-01").size)
    }

    @Test
    fun replayAndRelowerAtVolume_neverLowersTotalsNorDuplicatesRows() = runBlocking {
        val dao = db.ruleUsageDao()
        dao.upsertMax(rowsFor())

        // §6.6/INV-5 at volume: a crash-retry replay carrying one-ms-lower totals must change nothing.
        dao.upsertMax(rowsFor { expectedActiveMs(it) - 1L })
        assertEquals("replay cannot duplicate rows", RULE_COUNT * DAY_COUNT, countAll(dao))
        assertAllDays(dao, ::expectedActiveMs)

        // And a genuinely-higher replay lands everywhere.
        dao.upsertMax(rowsFor { expectedActiveMs(it) + 500L })
        assertEquals(RULE_COUNT * DAY_COUNT, countAll(dao))
        for (day in listOf("2024-02-20", "2024-02-29", "2024-03-01", "2024-03-30")) {
            val got = dao.loadDay(day).associate { it.ruleId to it.activeMs }
            assertEquals("$day totals after the raise", RULES.indices.associate { "r$it" to expectedActiveMs(it) + 500L }, got)
        }
    }

    @Test
    fun deleteOlderThan_productionDerivedCutoff_atVolume_strictLeapBoundary() = runBlocking {
        val dao = db.ruleUsageDao()
        dao.upsertMax(rowsFor())

        val cutoff = UsagePersistence.retentionCutoff(CUTOFF_FROM)
        assertEquals("2024-03-01", cutoff)
        dao.deleteOlderThan(cutoff)

        // Feb 20..29 gone (leap day included), Mar 1..30 kept — the cutoff day itself survives.
        for (day in DAYS) {
            val kept = dao.loadDay(day).size
            if (day < cutoff) assertEquals("$day must be purged", 0, kept)
            else assertEquals("$day must be retained", RULE_COUNT, kept)
        }
        assertEquals("exactly 30 retained days", 30 * RULE_COUNT, countAll(dao))

        dao.deleteOlderThan(cutoff)
        assertEquals("retention is idempotent at volume", 30 * RULE_COUNT, countAll(dao))
    }

    @Test
    fun maxRevisionsGroupBy_atVolume_perRuleIsolationAndNumericMax() = runBlocking {
        val dao = db.ruleUsageDao()
        val bigLow = 999_999_999_999L
        val bigHigh = 1_000_000_000_000L
        dao.upsertMax(
            rowsFor() + listOf(
                snapshot("r249", bigLow, "2024-03-29", 1L),
                snapshot("r249", bigHigh, "2024-03-30", 1L),
            ),
        )

        val startedAt = System.nanoTime()
        val revisions = dao.maxRevisions()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        System.err.println("A7 dao aggregate: maxRevisions over ${RULE_COUNT * DAY_COUNT + 2} rows in ${elapsedMs}ms")
        assertTrue("${elapsedMs}ms for the GROUP BY — complexity regression?", elapsedMs < 30_000)

        val byRule = revisions.associate { it.ruleId to it.revision }
        assertEquals("one entry per rule, no day grain", RULE_COUNT, revisions.size)
        assertEquals(0L, byRule["r0"])
        assertEquals(248L, byRule["r248"])
        assertEquals(
            "numeric MAX, not lexicographic — 1e12 must beat 999999999999",
            bigHigh,
            byRule["r249"],
        )
    }

    private companion object {
        const val DB_NAME = "rule-usage-volume-test.db"
        const val RULE_COUNT = 250
        const val DAY_COUNT = 40
        const val CUTOFF_FROM = "2024-03-31" // −30 days = 2024-03-01

        val RULES = (0 until RULE_COUNT).map { "r$it" }
        val DAYS = (0 until DAY_COUNT).map { LocalDate.parse("2024-02-20").plusDays(it.toLong()).toString() }

        fun expectedActiveMs(ruleIndex: Int): Long = 1_000L + ruleIndex
    }
}
