package com.example.myexodus.domainusage

import java.util.Random
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
        pattern = "example.com",
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
    fun aTickStalledAcrossAnIdleGapStillBooksTheEarlierStreak() {
        // No recompute runs between the two streaks: the first streak's 15 s tail would be lost
        // if touch's sessionStart reset were the only record of it.
        val c = counter()
        c.touch(0)
        c.touch(20_000)
        c.recompute(21_000)
        assertEquals(16_000L, c.totalMs)
    }

    @Test
    fun severalIdleGapsBeforeOneTickBookEveryStreak() {
        val c = counter()
        c.touch(0)
        c.touch(20_000)
        c.touch(40_000)
        c.recompute(41_000)
        assertEquals(31_000L, c.totalMs)
    }

    @Test
    fun aStalledStreakClosedAcrossMidnightSplitsIntoTheRightDay() {
        val c = counter()
        c.touch(0)
        c.touch(20_000) // closes [0, 15 000); streak [20 000, …) is live
        val oldTotal = c.splitDayAt(boundary = 10_000, now = 21_000)
        assertEquals("only the pre-midnight part of the tail is yesterday's", 10_000L, oldTotal)
        assertEquals("post-midnight tail + live streak stay today", 6_000L, c.totalMs)
    }

    @Test
    fun randomizedTimelinesMatchTheIndependentUnionOfActivityTails() {
        for (seed in 0 until 500) {
            val rng = Random(seed.toLong())
            val c = counter()
            val touches = ArrayList<Long>()
            var now = 0L
            var boundary: Long? = null

            repeat(80) {
                // A mix of sub-tick motion, idle tails, and gaps that outlive T_IDLE — with
                // recomputes allowed to stall across any number of them.
                now += when (rng.nextInt(10)) {
                    0, 1, 2 -> 1_000L
                    3, 4 -> rng.nextInt(5_000).toLong()
                    5, 6 -> (5_000 + rng.nextInt(10_001)).toLong()
                    else -> (15_001 + rng.nextInt(75_000)).toLong()
                }
                val action = rng.nextInt(4)
                if (action != 0) {
                    c.touch(now)
                    touches += now
                }
                if (action != 1) {
                    // Half the seeds also cross a midnight boundary mid-run. Real rollover runs
                    // at the first tick after midnight, so the boundary lies between what is
                    // already booked and now — never retroactively inside booked time.
                    if (boundary == null && seed % 2 == 0 && rng.nextInt(20) == 0) {
                        val booked = if (c.lastAccountedElapsed < 0L) 0L else c.lastAccountedElapsed
                        val b = booked + rng.nextInt((now - booked + 1).toInt()).toLong()
                        assertEquals("seed $seed old day at $b", unionMs(touches, 0L, b), c.splitDayAt(b, now))
                        boundary = b
                    } else {
                        c.recompute(now)
                    }
                }
            }

            val end = now + rng.nextInt(30_000)
            c.recompute(end)
            val dayBoundary = boundary
            if (dayBoundary == null) {
                assertEquals("seed $seed", unionMs(touches, 0L, end), c.totalMs)
            } else {
                assertEquals("seed $seed new day", unionMs(touches, dayBoundary, end), c.totalMs)
            }
        }
    }

    /**
     * Independently written oracle: total active time is the measure of the union of every
     * chunk's `[t, t + T_IDLE]` tail, clipped to the window. Deliberately does not mirror the
     * implementation's sequential booking — it merges intervals directly.
     */
    private fun unionMs(touches: List<Long>, from: Long, to: Long): Long {
        var total = 0L
        var i = 0
        while (i < touches.size) {
            var start = maxOf(touches[i], from)
            var end = minOf(touches[i] + MeteringConfig.T_IDLE_MS, to)
            while (i + 1 < touches.size && touches[i + 1] <= end) {
                i++
                end = minOf(touches[i] + MeteringConfig.T_IDLE_MS, to)
            }
            if (end > start) total += end - start
            i++
        }
        return total
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

    // --- audit A-02: expiry freezes accrual at the exact instant ---

    @Test
    fun markExpiryBooksUpToTheExpiryInstantAndNothingAfter() {
        val c = counter()
        c.touch(0)
        c.recompute(5_000)
        assertEquals(5_000L, c.totalMs)

        c.touch(6_000)
        c.markExpiry(10_000)
        c.recompute(20_000)
        // The [0, 6 000) streak's 15 s tail covers the gap; clipping its end at 10 000 books
        // exactly the expiry instant and nothing after.
        assertEquals(10_000L, c.totalMs)

        c.touch(11_000) // ignored: past the accounting end
        c.recompute(30_000)
        assertEquals(10_000L, c.totalMs)
    }

    @Test
    fun markExpiryClampsAStreakClosedWhileTheTickWasStalled() {
        val c = counter()
        c.touch(0)
        c.touch(20_000) // queues the closed [0, 15 000) streak, starts a new one at 20 000
        c.markExpiry(10_000)
        c.recompute(21_000)
        assertEquals("the queued streak is clipped to the expiry instant", 10_000L, c.totalMs)
    }

    @Test
    fun retargetExpiryReArmsAccrualForAnExtendedRule() {
        val c = counter()
        c.touch(0)
        c.markExpiry(5_000)
        c.recompute(6_000)
        assertEquals(5_000L, c.totalMs)
        c.touch(7_000)
        c.recompute(8_000)
        assertEquals("clamped: no accrual while lapsed", 5_000L, c.totalMs)

        c.retargetExpiry(99_000_000L)
        c.touch(8_000)
        c.recompute(9_000)
        assertEquals("the extension re-opens accounting", 9_000L, c.totalMs)
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
