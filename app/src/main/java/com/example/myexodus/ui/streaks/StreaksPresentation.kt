package com.example.myexodus.ui.streaks

import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.streaks.StreakTimer
import com.example.myexodus.streaks.WithdrawalCurve
import com.example.myexodus.streaks.WithdrawalWeek

/**
 * Pure presentation math and copy for the Streaks/Home surfaces — the only place the UI
 * derives anything (plan §8 Step 5: memoized formatting values, no UI-side streak math).
 * Day counts use the engine's 24-hour unit (Decision 17), never calendar dates.
 */

/** Whole completed days of an accumulator value — the engine's own unit. */
fun habitDays(elapsedMs: Long): Int = (elapsedMs / StreakTimer.DAY_MS).toInt()

/**
 * The day count every user-facing surface renders: completed whole days, floored at 1 so a
 * freshly started (or backdated) streak never reads 0 — owner rulings 2026-10-03 (ADR #128)
 * and 2026-10-18 (ADR #176, which replaced the ordinal +1). The floor is display-only: the
 * engine still counts whole elapsed days and milestones still fire at their 24h thresholds
 * (§6.2 anti-minting).
 *
 * Every countdown is measured on this same scale ([daysUntil]), so the visible day count and
 * the distance to the next rung always add up to the milestone total: the day the "One week
 * free" card fires the hero reads 7, not 8.
 */
fun daysFree(elapsedMs: Long): Int = maxOf(1, habitDays(elapsedMs))

/** Next unachieved milestone of the active streak (rows are day-ordered), if any. */
fun nextMilestone(state: HabitState): MilestoneEvent? =
    state.milestones.firstOrNull { it.achievedAt == null }

/** Whole days until a milestone day, on the displayed-day scale; 0 once it is reached. */
fun daysUntil(state: HabitState, milestoneDay: Int): Int =
    (milestoneDay - daysFree(state.currentStreakElapsedMs)).coerceAtLeast(0)

/**
 * One stretch of the milestone ladder as the surfaces draw it: the grid counts [startDay]
 * from the top left and rings the rungs inside. Owner ruling 2026-11-07: after 90 days the
 * block starts a new count toward six months, then a year, so a long streak does not sit on
 * a full grid with nothing ahead. The day after the previous rung opens each new stretch,
 * and a relapse (day 1) always lands back in the first one.
 */
data class MilestoneWindow(val startDay: Int, val endDay: Int) {
    val length: Int get() = endDay - startDay + 1
    val rungs: List<Int> get() = MilestoneEvent.MILESTONE_DAYS.filter { it in startDay..endDay }
}

private val MILESTONE_WINDOWS = listOf(
    MilestoneWindow(1, 90),
    MilestoneWindow(91, 180),
    MilestoneWindow(181, 365),
)

/** The stretch containing [day]; past the last rung the final stretch stays on screen. */
fun milestoneWindow(day: Int): MilestoneWindow =
    MILESTONE_WINDOWS.firstOrNull { day <= it.endDay } ?: MILESTONE_WINDOWS.last()

/** Unacknowledged milestone cards for the splash surface (REQ-12), day-ordered. */
fun splashMilestones(state: HabitState): List<MilestoneEvent> =
    state.milestones
        .filter { it.achievedAt != null && it.acknowledgedAt == null }
        .sortedBy { it.milestoneDay }

/** Splash-card headline per plan milestone day (REQ-12); unknown days stay literal. */
private fun milestoneHeadline(milestoneDay: Int): String = when (milestoneDay) {
    7 -> "One week free"
    30 -> "One month free"
    90 -> "Three months free"
    180 -> "Six months free"
    365 -> "One year free"
    else -> "Day $milestoneDay reached"
}

/** Splash-card body — supportive, non-clinical (Decision 12 tone). */
private fun milestoneBody(milestoneDay: Int): String = when (milestoneDay) {
    7 -> "Seven days, counted one at a time. The first week is behind you. Keep going."
    30 -> "A full month of showing up. That's a month of choosing yourself. Keep going."
    90 -> "Ninety days. Three months of holding your line. Keep going."
    180 -> "Half a year of showing up. Six months of choosing yourself. Keep going."
    365 -> "A full year of holding your line. Every one of those days was a choice, and they all count. Keep going."
    else -> "A new milestone is on the board. Keep going. The streak is yours."
}

/** Splash-card headline for its days — one combined card when several milestones stack up
 *  (product-owner call 2026-09-14, resolving plan §16 Open Question line 276). */
fun milestoneCardHeadline(days: List<Int>): String =
    if (days.size == 1) {
        milestoneHeadline(days.first())
    } else {
        val named = days.map { "Day $it" }
        "${named.dropLast(1).joinToString(", ")} and ${named.last()} reached"
    }

/** Splash-card sub-label under the headline. */
fun milestoneCardLabel(days: List<Int>): String =
    if (days.size == 1) "Milestone · Day ${days.first()}"
    else "Milestones · ${days.size} reached"

/** Splash-card body for its days. */
fun milestoneCardBody(days: List<Int>): String =
    if (days.size == 1) milestoneBody(days.first())
    else "You crossed every one of these already. Each was earned, and they count. Keep going."

/**
 * Post-relapse line (design research: non-punitive copy, celebrate the streak that was —
 * "You lost your streak" style messaging is the churn driver, not the reset itself).
 */
fun relapseSupportNote(endedDays: Int, bestDays: Int): String =
    if (endedDays < 1) {
        "Every run starts somewhere. This one's count begins now."
    } else {
        buildString {
            append("You showed up ")
            append(if (endedDays == 1) "1 day" else "$endedDays days")
            append(" in a row. That progress is real and it stays with you.")
            if (bestDays > endedDays) append(" Your best run of $bestDays days is still on record.")
            append(" Day 1 of the next run starts now.")
        }
    }

/** Week number (1-based) containing the live streak, or null once past the 28-day timeline. */
fun currentTimelineWeek(state: HabitState): Int? {
    val weekIndex = habitDays(state.currentStreakElapsedMs) / WithdrawalCurve.DAYS_PER_WEEK
    val weekCount = WithdrawalCurve.TIMELINE_DAYS / WithdrawalCurve.DAYS_PER_WEEK
    return if (weekIndex < weekCount) weekIndex + 1 else null
}

/** Bar fill for a week, relative to the strongest week — bars, never clinical values. */
fun weekFill(week: WithdrawalWeek, weeks: List<WithdrawalWeek>): Float {
    val max = weeks.maxOfOrNull { it.intensity } ?: return 0f
    return if (max <= 0.0) 0f else (week.intensity / max).toFloat()
}

fun weekLabel(week: WithdrawalWeek): String =
    "Week ${week.weekNumber} · Days ${week.startDay}–${week.endDay}"

/** General expectation-setting copy per week (Decision 12: estimation, not clinical fact). */
fun weekExpectation(weekNumber: Int): String = when (weekNumber) {
    1 -> "Cravings often come on strongest early on. Expect the pull to show up often. That's the habit talking, not a failure."
    2 -> "Usually still a loud week, but the peaks tend to start coming further apart."
    3 -> "The urge waves often get shorter and easier to ride out around here."
    4 -> "For many people this is the steadiest week yet. The pull is quieter, and the new routine starts to feel like yours."
    else -> ""
}

/** Decision 12: wherever the timeline renders, it says what it is. */
const val TIMELINE_DISCLAIMER =
    "This timeline is a general estimate to help set expectations. It is not a clinical measurement."
