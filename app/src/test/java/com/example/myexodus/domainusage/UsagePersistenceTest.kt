package com.example.myexodus.domainusage

import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §6.6–§6.8 / §9: the startup load, the generation-guarded flush, and the db-failure policy. */
class UsagePersistenceTest {

    private val counters = EngineCounters()

    /** The persistence-facing half of the degraded flag: the last value it was told, if any. */
    private class DegradeLog {
        @Volatile var last: Boolean? = null

        fun accept(degraded: Boolean) {
            last = degraded
        }
    }

    private fun persistence(dao: () -> RuleUsageDao?, degraded: DegradeLog) =
        UsagePersistence(dao, counters, degraded::accept)

    @Test
    fun theStartupLoadReturnsTodaysTotalsKeyedByRuleAndRevision_plusTheRevisionSeeds() = runTest {
        val dao = FakeRuleUsageDao()
        dao.seed("r1", 0L, "2026-09-20", 5_000L)
        dao.seed("r1", 2L, "2026-09-20", 0L)
        dao.seed("r2", 1L, "2026-09-20", 7_000L)
        dao.seed("r1", 4L, "2026-09-19", 9_000L) // another day: seed only, never a total
        val degraded = DegradeLog()

        val load = persistence({ dao }, degraded).loadStartupState("2026-09-20")

        assertEquals(mapOf("r1:0" to 5_000L, "r1:2" to 0L, "r2:1" to 7_000L), load.totalsByKey)
        assertEquals(mapOf("r1" to 4L, "r2" to 1L), load.maxRevisionByRuleId)
        assertFalse("a successful load clears the degraded flag", degraded.last!!)
    }

    @Test
    fun theStartupLoadAlsoRunsTheRetentionDeleteForTheLoadedDay() = runTest {
        val dao = FakeRuleUsageDao()
        dao.seed("r1", 0L, "2026-08-01", 5_000L)
        dao.seed("r1", 0L, "2026-09-20", 5_000L)

        persistence({ dao }, DegradeLog()).loadStartupState("2026-09-20")

        assertEquals(listOf(UsagePersistence.retentionCutoff("2026-09-20")), dao.deletedCutoffs)
        assertNull("rows at or before the cutoff are gone", dao.stored("r1", 0L, "2026-08-01"))
        assertEquals(5_000L, dao.stored("r1", 0L, "2026-09-20"))
        assertEquals("2026-08-21", UsagePersistence.retentionCutoff("2026-09-20"))
    }

    @Test
    fun aFailedStartupLoadDegradesImmediatelyAndRetriesUntilItSucceeds() = runTest {
        val dao = FakeRuleUsageDao()
        dao.failLoad = true
        dao.seed("r1", 0L, "2026-09-20", 1_000L)
        val degraded = DegradeLog()

        val job = async { persistence({ dao }, degraded).loadStartupState("2026-09-20") }
        testScheduler.runCurrent()

        assertEquals("the failure surfaces as Degraded(DB_ERROR)", true, degraded.last)
        assertEquals(1L, counters.get(Counter.db_flush_fail))
        assertFalse("the caller is still waiting on the retry", job.isCompleted)

        dao.failLoad = false
        testScheduler.advanceTimeBy(UsagePersistence.RETRY_MS)
        testScheduler.runCurrent()

        assertEquals(mapOf("r1:0" to 1_000L), job.await().totalsByKey)
        assertFalse("the successful retry clears the degraded flag", degraded.last!!)
    }

    @Test
    fun anUnavailableStoreIsRetried_neverCrashingTheCaller() = runTest {
        val dao = FakeRuleUsageDao()
        dao.seed("r1", 0L, "2026-09-20", 1_000L)
        var opens = 0

        // The rules store can fail into a recovery state: the provider hands back null and the
        // retry loop must re-attempt the open, not just the query.
        val load = persistence({ if (++opens == 1) null else dao }, DegradeLog())
            .loadStartupState("2026-09-20")

        assertEquals(2, opens)
        assertEquals(mapOf("r1:0" to 1_000L), load.totalsByKey)
        assertEquals(1L, counters.get(Counter.db_flush_fail))
    }

    @Test
    fun aFailedFlushKeepsTheRowDirty_andDegradesOnlyOnTheThirdConsecutiveFailure() = runTest {
        val dao = FakeRuleUsageDao()
        dao.failUpsert = true
        val ledger = ledgerWith()
        val counter = ledger.counterFor("r1", 0L)!!
        counter.touch(0L)
        counter.recompute(4_000L)
        val rows = listOf(UsageSnapshot("r1", 0L, "2026-09-20", 4_000L, counter.generation))
        val degraded = DegradeLog()
        val persistence = persistence({ dao }, degraded)

        persistence.flush(ledger, rows)
        persistence.flush(ledger, rows)
        assertEquals(2L, counters.get(Counter.db_flush_fail))
        assertNull("two failures are not yet a degraded store", degraded.last)
        assertTrue("the rule stays dirty for the next cycle", counter.changedSincePersist)

        persistence.flush(ledger, rows)
        assertEquals(true, degraded.last)
    }

    @Test
    fun aSuccessBetweenFailuresResetsTheRun() = runTest {
        val dao = FakeRuleUsageDao()
        dao.failUpsert = true
        val ledger = ledgerWith()
        val counter = ledger.counterFor("r1", 0L)!!
        counter.touch(0L)
        counter.recompute(4_000L)
        val rows = listOf(UsageSnapshot("r1", 0L, "2026-09-20", 4_000L, counter.generation))
        val degraded = DegradeLog()
        val persistence = persistence({ dao }, degraded)

        persistence.flush(ledger, rows)
        persistence.flush(ledger, rows)
        dao.failUpsert = false
        persistence.flush(ledger, rows)
        dao.failUpsert = true
        persistence.flush(ledger, rows)
        persistence.flush(ledger, rows)

        assertFalse("the run restarted at the success", degraded.last!!)
        assertFalse("a committed row is no longer dirty", counter.changedSincePersist)
    }

    @Test
    fun aSuccessfulFlushWritesTheMax_neverLastWriteWins() = runTest {
        val dao = FakeRuleUsageDao()
        dao.seed("r1", 0L, "2026-09-20", 9_000L)
        val ledger = ledgerWith()
        val counter = ledger.counterFor("r1", 0L)!!
        val degraded = DegradeLog()

        persistence({ dao }, degraded)
            .flush(ledger, listOf(UsageSnapshot("r1", 0L, "2026-09-20", 4_000L, counter.generation)))

        assertEquals("INV-5: a lower value never overwrites a higher one", 9_000L, dao.stored("r1", 0L, "2026-09-20"))
        assertFalse("the committed generation clears the dirty flag", counter.changedSincePersist)
        assertEquals(1L, counters.get(Counter.db_flush_ok))
        assertFalse(degraded.last!!)
    }

    @Test
    fun anAccrualDuringTheCommitLeavesTheRowDirty() = runTest {
        val dao = FakeRuleUsageDao()
        val ledger = ledgerWith()
        val counter = ledger.counterFor("r1", 0L)!!
        val committedGeneration = counter.generation

        // The tick thread books 3 s while the db thread is mid-commit (§4.9's generation guard).
        dao.upsertMax(listOf(UsageSnapshot("r1", 0L, "2026-09-20", 1_000L, committedGeneration)))
        counter.touch(0L)
        counter.recompute(3_000L)

        persistence({ dao }, DegradeLog())
            .flush(ledger, listOf(UsageSnapshot("r1", 0L, "2026-09-20", 1_000L, committedGeneration)))

        assertEquals("3 s booked mid-commit", 3_000L, counter.totalMs)
        assertTrue("a newer generation must survive the ack", counter.changedSincePersist)
    }

    @Test
    fun aFailedRetentionDeleteIsCountedButNeverThrows() = runTest {
        val dao = FakeRuleUsageDao()
        dao.failDelete = true

        persistence({ dao }, DegradeLog()).deleteOlderThan("2026-08-21")

        assertEquals(1L, counters.get(Counter.db_flush_fail))
    }

    private fun ledgerWith(): UsageLedger {
        val ledger = UsageLedger(
            FakeMonotonicClock(),
            FakeWallClock(),
            counters,
            FlowRegistry(counters),
            EngineMode.ENFORCE,
            ZoneId.of("UTC"),
            "2026-09-20",
        )
        ledger.applyRules(listOf(DomainRule("r1", 0L, "example.com", 3_600_000L, true)))
        return ledger
    }
}
