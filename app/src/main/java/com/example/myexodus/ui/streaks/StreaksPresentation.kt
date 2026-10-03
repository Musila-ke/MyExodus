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
 * The ordinal day number every user-facing count renders: the day of the run the user is
 * *in*, so a freshly started (or backdated) streak shows Day 1 — never 0. Owner ruling
 * 2026-10-03 (ADR #128): "lets never tell the user 0 we show 1 then we proceed from there".
 *
 * The engine stays untouched: the accumulator still counts whole elapsed days and milestones
 * still fire at their 24h thresholds (§6.2 anti-minting). The +1 lives only here, at the
 * display boundary — after N completed days the user is on Day N+1. [daysUntil] deliberately
 * stays on completed days, which is the same thing as `milestoneDay - daysFree(elapsed) + 1`:
 * "Day 7" completes `7 - completed` days from now, the truthful countdown.
 */
fun daysFree(elapsedMs: Long): Int = habitDays(elapsedMs) + 1

/** Next unachieved milestone of the active streak (rows are day-ordered), if any. */
fun nextMilestone(state: HabitState): MilestoneEvent? =
    state.milestones.firstOrNull { it.achievedAt == null }

/** Whole days until a milestone day; 0 once it is reached. */
fun daysUntil(state: HabitState, milestoneDay: Int): Int =
    (milestoneDay - habitDays(state.currentStreakElapsedMs)).coerceAtLeast(0)

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
    else -> "Day $milestoneDay reached"
}

/** Splash-card body — supportive, non-clinical (Decision 12 tone). */
private fun milestoneBody(milestoneDay: Int): String = when (milestoneDay) {
    7 -> "Seven days, counted one at a time. The first week is behind you — keep going."
    30 -> "A full month of showing up. That's a month of choosing yourself — keep going."
    90 -> "Ninety days. Three months of holding your line — keep going."
    else -> "A new milestone is on the board — keep going. The streak is yours."
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
    else "You crossed every one of these already — each was earned, and they count. Keep going."

/**
 * Post-relapse line (design research: non-punitive copy, celebrate the streak that was —
 * "You lost your streak" style messaging is the churn driver, not the reset itself).
 */
fun relapseSupportNote(endedDays: Int, bestDays: Int): String =
    if (endedDays < 1) {
        "Every run starts somewhere — this one's count begins now."
    } else {
        buildString {
            append("You showed up ")
            append(if (endedDays == 1) "1 day" else "$endedDays days")
            append(" in a row — that progress is real and it stays with you.")
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
    1 -> "Cravings often come on strongest early on. Expect the pull to show up often — that's the habit talking, not a failure."
    2 -> "Usually still a loud week, but the peaks tend to start coming further apart."
    3 -> "The urge waves often get shorter and easier to ride out around here."
    4 -> "For many people the steadiest week yet — the pull is quieter, and the new routine starts to feel like yours."
    else -> ""
}

/** Decision 12: wherever the timeline renders, it says what it is. */
const val TIMELINE_DISCLAIMER =
    "This timeline is a general estimate to help set expectations — not a clinical measurement."
