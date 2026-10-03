package com.example.myexodus.ui.streaks

import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.streaks.StreakTimer
import com.example.myexodus.streaks.WithdrawalSeverity
import com.example.myexodus.streaks.WithdrawalWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation math and copy for the Streaks/Home surfaces (plan §8 Step 5). */
class StreaksPresentationTest {

    private val day = StreakTimer.DAY_MS

    private fun milestone(day: Int, achievedAt: Long? = null, acknowledgedAt: Long? = null) =
        MilestoneEvent(
            streakId = "streak-1",
            milestoneDay = day,
            achievedAt = achievedAt,
            acknowledgedAt = acknowledgedAt,
        )

    private fun state(
        elapsedMs: Long = 0,
        bestMs: Long = 0,
        milestones: List<MilestoneEvent> = listOf(
            milestone(7),
            milestone(30),
            milestone(90),
        ),
    ) = HabitState(
        habitId = "habit-1",
        streakId = "streak-1",
        targetLabel = "Instagram",
        recoveryStartAt = 0,
        currentStreakElapsedMs = elapsedMs,
        allTimeMaxElapsedMs = bestMs,
        currentDay = daysFree(elapsedMs),
        habitDurationYears = 2,
        severity = WithdrawalSeverity.MODERATE,
        withdrawalWeeks = emptyList(),
        milestones = milestones,
    )

    @Test
    fun habitDays_countsWhole24hDays_neverCalendarDates() {
        assertEquals(0, habitDays(0))
        assertEquals(0, habitDays(day - 1))
        assertEquals(1, habitDays(day))
        assertEquals(3, habitDays(3 * day + day / 2))
    }

    /** Owner ruling 2026-10-03 (ADR #128): the displayed count is ordinal — Day 1 at start
     *  (also when backdated), then one up per completed 24h day, never 0. */
    @Test
    fun daysFree_isTheOrdinalDisplayDay_neverZero() {
        assertEquals(1, daysFree(0))
        assertEquals(1, daysFree(day - 1))
        assertEquals(2, daysFree(day))
        assertEquals(4, daysFree(3 * day + day / 2))
    }

    /** The countdown stays on completed days so it agrees with the milestone's own 24h firing
     *  moment: "Day 7" completes `daysUntil` days from now = `7 - daysFree + 1`. */
    @Test
    fun daysUntil_matchesTheOrdinalCountdown() {
        assertEquals(7 - daysFree(0) + 1, daysUntil(state(elapsedMs = 0), 7))
        assertEquals(7 - daysFree(6 * day) + 1, daysUntil(state(elapsedMs = 6 * day), 7))
    }

    @Test
    fun nextMilestone_isFirstUnachieved_orNullWhenLadderComplete() {
        val pending = state(milestones = listOf(milestone(7, achievedAt = 1L), milestone(30)))
        assertEquals(30, nextMilestone(pending)?.milestoneDay)

        val complete = state(milestones = listOf(milestone(7, achievedAt = 1L), milestone(30, achievedAt = 2L)))
        assertNull(nextMilestone(complete))
    }

    @Test
    fun daysUntil_measuresFromLiveStreak_andFloorsAtZero() {
        assertEquals(7, daysUntil(state(), 7))
        assertEquals(27, daysUntil(state(elapsedMs = 3 * day), 30))
        assertEquals(0, daysUntil(state(elapsedMs = 10 * day), 7))
    }

    @Test
    fun splashMilestones_onlyUnacknowledgedAchieved_sortedByDay() {
        val s = state(
            milestones = listOf(
                milestone(90, achievedAt = 3L, acknowledgedAt = null),
                milestone(7, achievedAt = 1L, acknowledgedAt = 9L),
                milestone(30, achievedAt = 2L, acknowledgedAt = null),
            ),
        )
        assertEquals(listOf(30, 90), splashMilestones(s).map { it.milestoneDay })
        assertTrue(splashMilestones(state()).isEmpty())
    }

    @Test
    fun currentTimelineWeek_mapsDaysToWeek_andEndsAtTimeline() {
        assertEquals(1, currentTimelineWeek(state(elapsedMs = 0)))
        assertEquals(1, currentTimelineWeek(state(elapsedMs = 6 * day)))
        assertEquals(2, currentTimelineWeek(state(elapsedMs = 7 * day)))
        assertEquals(4, currentTimelineWeek(state(elapsedMs = 27 * day)))
        assertNull(currentTimelineWeek(state(elapsedMs = 28 * day)))
    }

    @Test
    fun weekFill_isRelativeToStrongestWeek_andSafeOnEmptyOrZero() {
        val weeks = listOf(
            WithdrawalWeek(weekNumber = 1, startDay = 0, endDay = 6, intensity = 0.8),
            WithdrawalWeek(weekNumber = 2, startDay = 7, endDay = 13, intensity = 0.4),
        )
        assertEquals(1.0f, weekFill(weeks[0], weeks), 0.001f)
        assertEquals(0.5f, weekFill(weeks[1], weeks), 0.001f)
        assertEquals(0f, weekFill(weeks[0], emptyList()), 0.001f)
        assertEquals(
            0f,
            weekFill(
                WithdrawalWeek(1, 0, 6, 0.0),
                listOf(WithdrawalWeek(1, 0, 6, 0.0)),
            ),
            0.001f,
        )
    }

    @Test
    fun milestoneCard_copyIsPerDayForOne_combinedWhenSeveralStackUp() {
        assertEquals("One week free", milestoneCardHeadline(listOf(7)))
        assertEquals("Milestone · Day 7", milestoneCardLabel(listOf(7)))
        assertTrue(milestoneCardBody(listOf(7)).contains("first week"))

        assertEquals("Day 7 and Day 30 reached", milestoneCardHeadline(listOf(7, 30)))
        assertEquals("Day 7, Day 30 and Day 90 reached", milestoneCardHeadline(listOf(7, 30, 90)))
        assertEquals("Milestones · 3 reached", milestoneCardLabel(listOf(7, 30, 90)))
        assertTrue(milestoneCardBody(listOf(7, 30)).contains("earned"))
    }

    @Test
    fun relapseSupportNote_isNonPunitive_andOnlyCitesBestWhenItSurvives() {
        assertTrue(relapseSupportNote(endedDays = 0, bestDays = 0).contains("Every run starts somewhere"))

        val midRun = relapseSupportNote(endedDays = 12, bestDays = 30)
        assertTrue(midRun.contains("12 days"))
        assertTrue(midRun.contains("best run of 30 days"))

        val onlyRun = relapseSupportNote(endedDays = 12, bestDays = 12)
        assertTrue(onlyRun.contains("12 days"))
        assertTrue(!onlyRun.contains("best run"))
    }
}
