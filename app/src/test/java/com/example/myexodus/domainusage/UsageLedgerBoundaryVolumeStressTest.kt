package com.example.myexodus.domainusage

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stress slice A7 + the A2 residual (backlog `project_stress_test_backlog`): the ledger's
 * day-key machinery at calendar boundaries and at volume. `UsageLedgerTest` pins the ordinary
 * midnight split, one backward jump and the anchored zone; nothing anywhere probes the
 * **month-end / leap-day / year-end** derivations (the A2 residual after the 2026-10-14
 * recon), the retention cutoff across those boundaries, or the tick loop carrying 2,000 live
 * counters.
 *
 * The day key is `LocalDate.toString()` compared as a **string**: ISO keys are
 * lexicographically ordered, so `"2026-01-31" < "2026-02-01"`, `"2024-02-29" < "2024-03-01"`
 * and `"2026-12-31" < "2027-01-01"` must all roll forward — and a backward jump must still
 * never reopen a day (INV-3). Instants are UTC through the shared fixtures.
 */
class UsageLedgerBoundaryVolumeStressTest {

    private val mono = FakeMonotonicClock()
    private val wall = FakeWallClock()
    private val counters = EngineCounters()
    private val flows = FlowRegistry(counters)
    private val zone = ZoneId.of("UTC")

    private var wallStart: Instant = Instant.parse("2026-09-20T12:00:00Z")

    private fun ledger(initialDayKey: String = "2026-09-20") =
        UsageLedger(mono, wall, counters, flows, EngineMode.ENFORCE, zone, initialDayKey)

    private fun advanceTo(elapsed: Long) {
        mono.now = elapsed
        wall.instant = wallStart.plusMillis(elapsed)
    }

    private fun rule(id: String = "r1", limit: Long = 3_600_000L) =
        DomainRule(id = id, revision = 0L, pattern = "example.com", dailyLimitMs = limit, enforcedToday = true)

    // ---- calendar boundaries on the day key ----

    @Test
    fun monthEndRollover_jan31ToFeb1_splitsExactlyAtMidnight() {
        wallStart = Instant.parse("2026-01-31T23:59:40Z") // UTC midnight is elapsed 20_000
        val ledger = ledger(initialDayKey = "2026-01-31")
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        var last: DayRollover? = null
        for (t in 0L..40_000L step 1_000L) {
            advanceTo(t)
            c.touch(t)
            last = ledger.tick() ?: last
        }

        val rollover = requireNotNull(last)
        assertEquals("2026-01-31", rollover.oldDayKey)
        assertEquals("2026-02-01", rollover.newDayKey)
        assertEquals("2026-02-01", ledger.dayKey)
        assertEquals("exactly the pre-midnight usage", 20_000L, rollover.finals.single().totalMs)
        assertEquals("the new month continues from the boundary", 20_000L, c.totalMs)
    }

    @Test
    fun leapDay_consecutiveRollovers_feb28_feb29_mar1_onOneLedger() {
        wallStart = Instant.parse("2024-02-28T23:59:40Z")
        val ledger = ledger(initialDayKey = "2024-02-28")
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        var first: DayRollover? = null
        for (t in 0L..40_000L step 1_000L) {
            advanceTo(t)
            c.touch(t)
            first = ledger.tick() ?: first
        }
        assertEquals("2024-02-28", requireNotNull(first).oldDayKey)
        assertEquals("2024-02-29", ledger.dayKey)
        assertEquals("2024-02-29", first.newDayKey)
        assertEquals(20_000L, c.totalMs)

        // Second midnight one leap-day later: Feb 29 -> Mar 1. Re-anchor the wall so the
        // boundary instant lands at elapsed 41_000 (mutual monotonic elapsed preserved).
        wallStart = Instant.parse("2024-02-29T23:59:19Z")
        advanceTo(42_000)
        c.touch(42_000)
        val second = requireNotNull(ledger.tick())
        assertEquals("2024-02-29", second.oldDayKey)
        assertEquals("2024-03-01", second.newDayKey)
        assertEquals("2024-03-01", ledger.dayKey)
        assertEquals("2024-02-29", second.finals.single().dayKey)
        assertEquals("carried 20 s + 1 s before the leap midnight", 21_000L, second.finals.single().totalMs)
        assertEquals("the post-midnight leg opens the new day", 1_000L, c.totalMs)
    }

    @Test
    fun yearEndRollover_dec31ToJan1() {
        wallStart = Instant.parse("2026-12-31T23:59:40Z")
        val ledger = ledger(initialDayKey = "2026-12-31")
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        var last: DayRollover? = null
        for (t in 0L..40_000L step 1_000L) {
            advanceTo(t)
            c.touch(t)
            last = ledger.tick() ?: last
        }

        val rollover = requireNotNull(last)
        assertEquals("2026-12-31", rollover.oldDayKey)
        assertEquals("2027-01-01", rollover.newDayKey)
        assertEquals("2027-01-01", ledger.dayKey)
        assertEquals(20_000L, rollover.finals.single().totalMs)
    }

    @Test
    fun backwardJumpAcrossMonthEnd_neverReopens_thenForwardStillRolls() {
        wallStart = Instant.parse("2026-03-01T12:00:00Z")
        val ledger = ledger(initialDayKey = "2026-03-01")
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        advanceTo(1_000)
        c.touch(1_000)
        ledger.tick()
        assertEquals("2026-03-01", ledger.dayKey)

        wallStart = Instant.parse("2026-02-28T23:00:00Z") // clock set back over the month end
        advanceTo(2_000)
        c.touch(2_000)
        assertNull(ledger.tick())
        assertEquals("INV-3: the effective key never moves backward", "2026-03-01", ledger.dayKey)

        // Forward to the NEXT midnight (not the one we already own): 23:59:58 + 3 s.
        wallStart = Instant.parse("2026-03-01T23:59:58Z")
        advanceTo(3_000)
        c.touch(3_000)
        val rollover = requireNotNull(ledger.tick())
        assertEquals("2026-03-01", rollover.oldDayKey)
        assertEquals("2026-03-02", rollover.newDayKey)
        assertEquals("old day keeps exactly the pre-midnight leg", 1_000L, rollover.finals.single().totalMs)
        assertEquals("new day carries the post-midnight leg", 1_000L, c.totalMs)
    }

    @Test
    fun retentionCutoffDomain_monthEnd_leapYearAndYearEnd() {
        // D-30: strictly-30-days-earlier ISO key; java.time does the calendar math, the string
        // is the contract (both the SQL `<` cutoff and the ledger's ordering rely on it).
        assertEquals("across a 28-day February", "2026-01-30", UsagePersistence.retentionCutoff("2026-03-01"))
        assertEquals("across a leap February", "2024-01-31", UsagePersistence.retentionCutoff("2024-03-01"))
        assertEquals("across 30-day April", "2026-04-01", UsagePersistence.retentionCutoff("2026-05-01"))
        assertEquals("across the year boundary", "2025-12-16", UsagePersistence.retentionCutoff("2026-01-15"))
        assertEquals("cutoff from a leap day itself", "2024-01-30", UsagePersistence.retentionCutoff("2024-02-29"))
        // Ordering invariant the delete and the rollover comparison both stand on.
        for (key in listOf("2024-02-29", "2024-03-01", "2026-01-01", "2026-03-01", "2026-12-31", "2027-01-01")) {
            val cutoff = UsagePersistence.retentionCutoff(key)
            assertTrue("cutoff $cutoff must sort strictly before $key", cutoff < key)
        }
    }

    // ---- volume ----

    @Test
    fun volume_twoThousandRules_twoThousandTicks_exactTotals_underTheCeiling() {
        val n = 2_000
        val ticks = 2_000
        val ledger = ledger()
        ledger.applyRules((0 until n).map { rule(id = "r$it") })

        val startedAt = System.nanoTime()
        for (t in 0 until ticks) {
            val now = t * 1_000L
            advanceTo(now)
            for (i in 0 until n) ledger.counterFor("r$i", 0)!!.touch(now)
            ledger.tick()
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        System.err.println("A7 ledger volume: $n rules x $ticks ticks in ${elapsedMs}ms")

        // Touches run 0 s .. 1_999 s continuously: every counter owns exactly that many ms.
        assertEquals((ticks - 1) * 1_000L, ledger.usageTodayMs("r0"))
        assertEquals((ticks - 1) * 1_000L, ledger.usageTodayMs("r${n - 1}"))
        val sum = (0 until n).sumOf { ledger.usageTodayMs("r$it") }
        assertEquals(n * (ticks - 1) * 1_000L, sum)
        // Production cadence is one tick per second; 10 ms/tick is 100x headroom and only a
        // complexity regression (a per-tick scan the design removed) can blow it.
        assertTrue("${elapsedMs}ms for $ticks ticks — per-tick cost regression?", elapsedMs < ticks * 10L)
    }

    @Test
    fun rolloverAtVolume_twoThousandFinals_conserveTheSum() {
        wallStart = Instant.parse("2026-09-20T23:59:40Z")
        val n = 2_000
        val ledger = ledger(initialDayKey = "2026-09-20")
        ledger.applyRules((0 until n).map { rule(id = "r$it") })

        var last: DayRollover? = null
        for (t in 0L..40_000L step 1_000L) {
            advanceTo(t)
            for (i in 0 until n) ledger.counterFor("r$i", 0)!!.touch(t)
            last = ledger.tick() ?: last
        }

        val rollover = requireNotNull(last)
        assertEquals("one final per live counter", n, rollover.finals.size)
        assertEquals(
            "each counter split exactly 20 s to the outgoing day",
            n * 20_000L,
            rollover.finals.sumOf { it.totalMs },
        )
        assertEquals(
            "and the same 20 s continue on the new day — nothing lost, nothing double-counted",
            n * 20_000L,
            (0 until n).sumOf { ledger.counterFor("r$it", 0)!!.totalMs },
        )
        assertEquals("2026-09-21", ledger.dayKey)
    }

    @Test
    fun concurrentTouchesDuringTicks_loseNothing() {
        // The designed production race: relay threads touch counters while the tick thread
        // recomputes. Four threads round-robin the 2,000 counters for the whole window.
        val n = 2_000
        val spanMs = 200_000L
        val ledger = ledger()
        ledger.applyRules((0 until n).map { rule(id = "r$it") })

        val stop = AtomicBoolean(false)
        val ready = CountDownLatch(4)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val storms = (0 until 4).map { thread ->
                executor.submit {
                    ready.countDown()
                    var i = thread
                    while (!stop.get()) {
                        ledger.counterFor("r$i", 0)?.touch(mono.now)
                        i += 4
                        if (i >= n) i = thread
                    }
                }
            }
            ready.await()
            for (t in 0L..spanMs step 1_000L) {
                advanceTo(t)
                ledger.tick()
            }
            stop.set(true)
            storms.forEach { it.get() }
        } finally {
            stop.set(true)
            executor.shutdown()
        }
        ledger.tick()

        var zeros = 0
        var sum = 0L
        for (i in 0 until n) {
            val total = ledger.counterFor("r$i", 0)!!.totalMs
            if (total <= 0L) zeros++
            sum += total
        }
        assertEquals("every counter accrued under the concurrent touch storm", 0, zeros)
        assertTrue("accrual cannot exceed wall time", sum <= n * (spanMs + 1_000L))
        assertTrue("no mass loss: got $sum of at most ${n * spanMs}ms", sum > n * (spanMs - 60_000L))
    }
}
