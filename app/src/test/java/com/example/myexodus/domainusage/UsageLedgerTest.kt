package com.example.myexodus.domainusage

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-10 (revised, §12.1): the ledger's accounting, day scoping and rollover behavior. The
 * timezone-change leg asserts the property the ledger actually owns — its day key is a pure
 * function of the wall clock read in the *anchored* zone — because the "deferred to the next
 * start()" half of D-07 lives in the wiring: `start()` (S7) captures one zone and the tick loop
 * passes it for the engine's whole lifetime.
 */
class UsageLedgerTest {

    private val mono = FakeMonotonicClock()
    private val wall = FakeWallClock()
    private val counters = EngineCounters()
    private val flows = FlowRegistry(counters)
    private val zone = ZoneId.of("UTC")

    private var wallStart: Instant = Instant.parse("2026-09-20T12:00:00Z")

    private fun ledger(
        mode: EngineMode = EngineMode.ENFORCE,
        initialDayKey: String = "2026-09-20",
    ) = UsageLedger(mono, wall, counters, flows, mode, zone, initialDayKey)

    /** Advances both clocks together, so only deliberately moved fields produce diagnostics. */
    private fun advanceTo(elapsed: Long) {
        mono.now = elapsed
        wall.instant = wallStart.plusMillis(elapsed)
    }

    private fun rule(
        limit: Long = 3_600_000L,
        enforcedToday: Boolean = true,
        pattern: String = "example.com",
        revision: Long = 0L,
    ) = DomainRule("r1", revision, pattern, limit, enforcedToday)

    private class RecordingControl : FlowControl {
        var resets = 0
        override fun reset() {
            resets++
        }
    }

    @Test
    fun oneFlowOfTwentySecondsCountsTwentySecondsPlusTheIdleTailThenStops() {
        val ledger = ledger()
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        for (t in 0L..20_000L step 1_000L) {
            advanceTo(t)
            c.touch(t)
            ledger.tick()
        }
        assertEquals(20_000L, c.totalMs)

        for (t in 21_000L..36_000L step 1_000L) {
            advanceTo(t)
            ledger.tick()
        }
        assertEquals("20 s of activity + the 15 s tail", 35_000L, c.totalMs)

        for (t in 37_000L..90_000L step 1_000L) {
            advanceTo(t)
            ledger.tick()
        }
        assertEquals("idle never accrues past the tail", 35_000L, c.totalMs)
    }

    @Test
    fun eightOverlappingFlowsOnOneRuleCountOnce() {
        val ledger = ledger()
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        for (t in 0L..20_000L step 1_000L) {
            advanceTo(t)
            repeat(8) { c.touch(t) }
            ledger.tick()
        }
        for (t in 21_000L..36_000L step 1_000L) {
            advanceTo(t)
            ledger.tick()
        }
        assertEquals("eight streams to one site are still one site's usage", 35_000L, c.totalMs)
    }

    @Test
    fun aSingleMidIntervalPacketDoesNotCreditAFullTickPeriod() {
        val ledger = ledger()
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        advanceTo(5_000)
        c.touch(5_000)
        advanceTo(10_000)
        ledger.tick()

        assertEquals(5_000L, c.totalMs)
    }

    @Test
    fun aRuleOutsideItsDayScopeDoesNotAccrue() {
        val ledger = ledger()
        ledger.applyRules(listOf(rule(enforcedToday = false)))
        val c = ledger.counterFor("r1", 0)!!

        for (t in 0L..30_000L step 1_000L) {
            advanceTo(t)
            c.touch(t)
            ledger.tick()
        }
        assertEquals(0L, c.totalMs)
    }

    @Test
    fun applyRulesSeedsNewCountersFromPersistedTotals() {
        val ledger = ledger()
        ledger.applyRules(listOf(rule()), seededTotals = mapOf("r1:0" to 12_345L))
        assertEquals(12_345L, ledger.usageTodayMs("r1"))
    }

    @Test
    fun aPatternEditStartsAFreshCounterAndTheOldOneSurvivesUntilItsFlowsClose() {
        val ledger = ledger()
        ledger.applyRules(listOf(rule()))
        val old = ledger.counterFor("r1", 0)!!
        val control = RecordingControl()
        flows.register(old.key, control)

        advanceTo(0)
        old.touch(0)
        advanceTo(1_000)
        ledger.tick()
        assertEquals(1_000L, ledger.usageTodayMs("r1"))

        ledger.applyRules(listOf(rule(pattern = "other.example", revision = 1L)))
        assertEquals("the new pattern starts from zero", 0L, ledger.usageTodayMs("r1"))
        assertEquals("no usage transfers to the new revision", 0L, ledger.counterFor("r1", 1L)!!.totalMs)

        advanceTo(2_000)
        ledger.tick()
        assertNotNull("the old revision still has an open flow", ledger.counterFor("r1", 0))
        assertEquals("and it keeps accruing", 2_000L, old.totalMs)

        flows.unregister(old.key, control)
        advanceTo(20_000) // past the idle tail, so the counter stops changing
        ledger.tick()
        ledger.acknowledge("r1", 0L, old.generation)
        advanceTo(21_000)
        ledger.tick()
        assertNull(ledger.counterFor("r1", 0))
    }

    @Test
    fun aRemovedRulesCounterWaitsForItsPendingWrite() {
        val ledger = ledger()
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!
        advanceTo(1_000)
        c.touch(1_000)
        ledger.tick()

        ledger.applyRules(emptyList())
        advanceTo(2_000)
        ledger.tick()
        assertNotNull("dirty: the accrued seconds are not written yet", ledger.counterFor("r1", 0))

        advanceTo(20_000) // past the idle tail, so nothing accrues again
        ledger.tick()
        ledger.acknowledge("r1", 0L, c.generation)
        advanceTo(21_000)
        ledger.tick()
        assertNull(ledger.counterFor("r1", 0))
    }

    @Test
    fun reachingTheLimitExhaustsTheRuleAndSweepsItsOpenFlows() {
        val ledger = ledger(mode = EngineMode.ENFORCE)
        ledger.applyRules(listOf(rule(limit = 30_000L)))
        val c = ledger.counterFor("r1", 0)!!
        val control = RecordingControl()
        flows.register(c.key, control)

        for (t in 0L..29_000L step 1_000L) {
            advanceTo(t)
            c.touch(t)
            ledger.tick()
        }
        assertFalse(c.exhausted)

        advanceTo(30_000)
        c.touch(30_000)
        ledger.tick()
        assertEquals(30_000L, c.totalMs)
        assertTrue(c.exhausted)
        assertEquals(1, control.resets)
        assertEquals(1L, counters.get(Counter.flow_resets_sweep))

        ledger.applyRules(listOf(rule(limit = 60_000L)))
        advanceTo(31_000)
        ledger.tick()
        assertFalse("raising the limit clears exhaustion on the next tick", c.exhausted)
        assertEquals("clearing exhaustion does not re-sweep", 1, control.resets)
    }

    @Test
    fun exhaustedIsTrackedButNeverSweptOutsideEnforce() {
        val ledger = ledger(mode = EngineMode.ACCOUNT)
        ledger.applyRules(listOf(rule(limit = 30_000L)))
        val c = ledger.counterFor("r1", 0)!!
        val control = RecordingControl()
        flows.register(c.key, control)

        for (t in 0L..30_000L step 1_000L) {
            advanceTo(t)
            c.touch(t)
            ledger.tick()
        }
        assertTrue(c.exhausted)
        assertEquals(0, control.resets)
        assertEquals(0L, counters.get(Counter.flow_resets_sweep))
    }

    @Test
    fun midnightSplitsTheStraddlingIntervalExactlyAtTheBoundary() {
        wallStart = Instant.parse("2026-09-20T23:59:40Z") // UTC midnight is elapsed 20_000
        val ledger = ledger(initialDayKey = "2026-09-20")
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        var lastRollover: DayRollover? = null
        for (t in 0L..40_000L step 1_000L) {
            advanceTo(t)
            c.touch(t)
            lastRollover = ledger.tick() ?: lastRollover
        }

        val rollover = requireNotNull(lastRollover)
        val finals = rollover.finals.single()
        assertEquals("2026-09-20", rollover.oldDayKey)
        assertEquals("2026-09-21", rollover.newDayKey)
        assertEquals("2026-09-21", ledger.dayKey)
        assertEquals("2026-09-20", finals.dayKey)
        assertEquals("exactly the pre-midnight usage", 20_000L, finals.totalMs)
        assertEquals("the new day continues from the boundary", 20_000L, c.totalMs)
    }

    @Test
    fun aMidnightFallingBetweenTicksIsStillSplitExactly() {
        wallStart = Instant.parse("2026-09-20T23:59:40Z")
        val ledger = ledger(initialDayKey = "2026-09-20")
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        for (t in 0L..19_000L step 1_000L) {
            advanceTo(t)
            c.touch(t)
            ledger.tick()
        }
        advanceTo(21_000)
        c.touch(21_000)
        val rollover = requireNotNull(ledger.tick())

        // The boundary is at elapsed 20_000: the delayed tick books 19 s..20 s to the old day
        // and 20 s..21 s to the new one.
        assertEquals(20_000L, rollover.finals.single().totalMs)
        assertEquals(1_000L, c.totalMs)
    }

    @Test
    fun aBackwardWallClockJumpCannotReopenTheDay() {
        wallStart = Instant.parse("2026-09-20T23:59:40Z")
        val ledger = ledger(initialDayKey = "2026-09-20")
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        advanceTo(1_000)
        c.touch(1_000)
        ledger.tick()
        assertEquals("2026-09-20", ledger.dayKey)

        wallStart = Instant.parse("2026-09-19T08:00:00Z") // the wall clock jumps back a day
        advanceTo(2_000)
        c.touch(2_000)
        assertNull(ledger.tick())
        assertEquals("the effective day key never moves backward", "2026-09-20", ledger.dayKey)
        assertEquals("accrual continues on the same day", 1_000L, c.totalMs)
    }

    @Test
    fun aForwardWallClockJumpAdvancesTheDayKey() {
        val ledger = ledger(initialDayKey = "2026-09-20")
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        advanceTo(1_000)
        c.touch(1_000)
        ledger.tick()

        wallStart = Instant.parse("2026-09-21T10:00:00Z") // a day forward
        advanceTo(2_000)
        c.touch(2_000)
        val rollover = requireNotNull(ledger.tick())
        assertEquals("2026-09-20", rollover.oldDayKey)
        assertEquals("2026-09-21", ledger.dayKey)
    }

    @Test
    fun aWallClockJumpIsCountedAndNeverTouchesAccrual() {
        val ledger = ledger()
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        advanceTo(1_000)
        c.touch(1_000)
        ledger.tick()

        wallStart = wallStart.plusSeconds(60) // wall +60 s while elapsed +1 s
        advanceTo(2_000)
        c.touch(2_000)
        ledger.tick()

        assertEquals(1L, counters.get(Counter.clock_jump))
        assertEquals("durations are monotonic-only (INV-2)", 1_000L, c.totalMs)
    }

    @Test
    fun theDayKeyFollowsTheAnchoredZoneNotTheSystemDefault() {
        wall.instant = Instant.parse("2026-09-20T23:30:00Z")
        mono.now = 0

        val utc = UsageLedger(mono, wall, counters, flows, EngineMode.ENFORCE, ZoneId.of("UTC"), "")
        utc.tick()
        assertEquals("2026-09-20", utc.dayKey)

        val kiritimati = UsageLedger(
            mono, wall, counters, FlowRegistry(counters), EngineMode.ENFORCE,
            ZoneId.of("Pacific/Kiritimati"), "",
        )
        kiritimati.tick()
        assertEquals("2026-09-21", kiritimati.dayKey)
    }

    @Test
    fun aFirstTickAdoptsTheDayKeyWithoutARollover() {
        val ledger = ledger(initialDayKey = "")
        assertNull(ledger.tick())
        assertEquals("2026-09-20", ledger.dayKey)
    }

    @Test
    fun acknowledgeKeepsADirtyFlagWhenTheGenerationMovedOn() {
        val ledger = ledger()
        ledger.applyRules(listOf(rule()))
        val c = ledger.counterFor("r1", 0)!!

        advanceTo(0)
        c.touch(0)
        advanceTo(1_000)
        ledger.tick()
        val snapshot = ledger.dirtySnapshot().single()
        assertEquals(1_000L, snapshot.totalMs)
        assertEquals(c.generation, snapshot.generation)

        advanceTo(3_000) // an accrual lands between the snapshot and its commit
        c.touch(3_000)
        ledger.tick()
        ledger.acknowledge("r1", 0L, snapshot.generation)
        assertTrue("the newer value must stay dirty", c.changedSincePersist)

        val retry = ledger.dirtySnapshot().single()
        ledger.acknowledge("r1", 0L, retry.generation)
        assertFalse(c.changedSincePersist)
        assertTrue(ledger.dirtySnapshot().isEmpty())
    }
}
