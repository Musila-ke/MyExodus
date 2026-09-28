package com.example.myexodus.domainusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-14 (§12.1): the §4.7 interval formula against hand-computed `activeMs` values — a burst
 * mid-interval, a session across many ticks, a late tick (the stall-undercounting regression), a
 * doubled tick, and a session idling exactly at a tick boundary.
 */
class RuleCounterTest {

    private fun counter() = RuleCounter(
        ruleId = "r1",
        revision = 0L,
        dailyLimitMs = 3_600_000L,
        enforcedToday = true,
    )

    @Test
    fun aPacketMidIntervalCreditsOnlyTheTimeSinceItArrived() {
        // The original overcounting regression: a chunk at 5 s inside a 10 s tick period must
        // credit 5 s, not the whole period.
        val c = counter()
        c.touch(5_000)
        c.recompute(10_000)
        assertEquals(5_000L, c.totalMs)
    }

    @Test
    fun aLoneBurstCreditsItsActivityPlusTheIdleTailAndNothingProjectedForward() {
        val c = counter()
        c.touch(5_000)
        c.recompute(60_000)
        assertEquals("5 s of activity + 15 s tail, and no time before the chunk", 15_000L, c.totalMs)
    }

    @Test
    fun aSessionSpanningManyTicksAccruesExactlyOncePerMillisecond() {
        val c = counter()
        c.touch(0)
        var t = 0L
        while (t <= 19_000L) {
            c.recompute(t)
            t += 1_000L
            if (t <= 19_000L) c.touch(t)
        }
        assertEquals(19_000L, c.totalMs)

        c.recompute(34_000L) // exactly last activity + T_IDLE
        assertEquals(34_000L, c.totalMs)

        c.recompute(60_000L)
        assertEquals("past the tail, an idle counter stops accruing", 34_000L, c.totalMs)
    }

    @Test
    fun aTickInvokedLateBooksTheWholeGapInOneShot() {
        // The stall-undercounting regression: activity every 10 s for 50 s with no tick at all,
        // then one delivered 60 s in — the full span is booked, with no per-invocation cap.
        val c = counter()
        for (t in 0L..50_000L step 10_000L) c.touch(t)
        c.recompute(60_000L)
        assertEquals(60_000L, c.totalMs)
    }

    @Test
    fun aTickInvokedTwiceInQuickSuccessionBooksNothingTheSecondTime() {
        val c = counter()
        c.touch(0)
        c.recompute(1_000)
        c.recompute(1_000)
        assertEquals(1_000L, c.totalMs)
        c.recompute(1_001)
        assertEquals(1_001L, c.totalMs)
    }

    @Test
    fun aSessionIdlingExactlyAtTheBoundaryStillCountsTheBoundary() {
        val c = counter()
        c.touch(0)
        c.recompute(15_000) // now - last == T_IDLE: still active
        assertEquals(15_000L, c.totalMs)
        c.recompute(16_000)
        assertEquals(15_000L, c.totalMs)
    }

    @Test
    fun activityAfterAnIdleGapStartsANewSessionAndTheGapIsNeverCredited() {
        val c = counter()
        c.touch(0)
        c.recompute(15_000)
        c.touch(40_000) // 25 s gap > T_IDLE ⇒ a new streak begins here
        c.recompute(41_000)
        assertEquals(16_000L, c.totalMs)
    }

    @Test
    fun splitDayAtCreditsEachPortionToItsOwnDayExactlyAtTheBoundary() {
        val c = counter()
        c.touch(0)
        c.recompute(5_000)
        c.touch(9_000)

        val oldTotal = c.splitDayAt(boundary = 7_000, now = 10_000)

        assertEquals(7_000L, oldTotal)
        assertEquals(3_000L, c.totalMs)
        assertEquals(10_000L, c.lastAccountedElapsed)
        assertEquals(7_000L, c.sessionStart.get())
        c.recompute(11_000)
        assertEquals(4_000L, c.totalMs)
    }

    @Test
    fun splitDayAtWithANeverActiveCounterResetsWithoutCrediting() {
        val c = counter()
        assertEquals(0L, c.splitDayAt(boundary = 50_000, now = 60_000))
        assertEquals(0L, c.totalMs)
        assertEquals(50_000L, c.lastAccountedElapsed)
    }

    @Test
    fun resetForNewDayDropsTheTotalAndReturnsTheOutgoingOne() {
        val c = counter()
        c.touch(0)
        c.recompute(1_000)
        assertEquals(1_000L, c.resetForNewDay(50_000))
        assertEquals(0L, c.totalMs)
        assertEquals(50_000L, c.lastAccountedElapsed)
        assertEquals(50_000L, c.sessionStart.get())
    }

    @Test
    fun accrualAfterAResetNeverReopensPreBoundaryTime() {
        val c = counter()
        c.touch(0)
        c.recompute(1_000)
        c.resetForNewDay(50_000) // the rule was out of its day scope, say
        c.recompute(60_000)
        assertEquals(0L, c.totalMs)
        c.touch(70_000)
        c.recompute(71_000)
        assertEquals(1_000L, c.totalMs)
    }

    @Test
    fun generationAndTheDirtyFlagAdvanceOnlyWhenTimeIsBooked() {
        val c = counter()
        assertFalse(c.changedSincePersist)
        c.recompute(1_000) // never touched: nothing to book
        assertEquals(0L, c.generation)
        assertFalse(c.changedSincePersist)

        c.touch(2_000)
        c.recompute(3_000)
        assertEquals(1L, c.generation)
        assertTrue(c.changedSincePersist)

        c.recompute(3_000) // no further accrual
        assertEquals(1L, c.generation)
    }
}
