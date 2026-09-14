package com.example.myexodus.streaks

import kotlin.math.exp
import kotlin.math.min

/**
 * REQ-18 withdrawal-expectation timeline: a personalized intensity estimate for each day of
 * a young streak, bucketed into the four 7-day weeks the timeline UI displays.
 *
 * **This is an estimation tool, not a clinical measurement** (habit-streaks plan Decision
 * 12) — in-app copy shown wherever the timeline renders must say so. Intensities are
 * relative units used only to order weeks; they are not probabilities, scores, or advice.
 *
 *   intensity(day) = baseDecay(day) × (1 + durationModifier) × (1 + severityModifier),
 *   clamped to [0.0, 2.0];  baseDecay(day) = 0.3 + e^(-day/9)
 *
 * The curve decays monotonically with day and increases monotonically with habit duration
 * (capped at 10 years, max +0.5) and severity (max +0.8 for SEVERE), so at any given day a
 * SEVERE 10-year habit always reads at least as intense as a MILD 0-year one.
 *
 * The coefficients were not fixed by the plan (only the formula shape and the clamp were);
 * they were proposed in implementation and approved by the project owner on 2026-09-14.
 */
object WithdrawalCurve {

    /** Timeline length in days — four 7-day weeks (REQ-18). */
    const val TIMELINE_DAYS = 28
    const val DAYS_PER_WEEK = 7

    private const val MAX_INTENSITY = 2.0
    private const val CURVE_FLOOR = 0.3
    private const val DECAY_DIVISOR_DAYS = 9.0
    private const val DURATION_MODIFIER_MAX = 0.5
    private const val DURATION_YEARS_CAP = 10

    /**
     * Estimated withdrawal intensity on [day] (0-based, day 0 = streak start). [day] is
     * trusted to be ≥ 0 — it derives from the streak accumulator, which the engine never
     * lets go negative.
     */
    fun intensity(day: Int, habitDurationYears: Int, severity: WithdrawalSeverity): Double {
        val baseDecay = CURVE_FLOOR + exp(-day / DECAY_DIVISOR_DAYS)
        val durationModifier =
            DURATION_MODIFIER_MAX * min(habitDurationYears, DURATION_YEARS_CAP) / DURATION_YEARS_CAP
        return (baseDecay * (1 + durationModifier) * (1 + severity.modifier))
            .coerceIn(0.0, MAX_INTENSITY)
    }

    /**
     * The four weekly buckets, each represented by its first day — the week's peak, since
     * the curve is monotonically decreasing in day. Recalculated from Day 0 on every streak
     * rollover (REQ-18 edge case), which falls out of it being a pure function of the
     * persisted inputs.
     */
    fun weeks(habitDurationYears: Int, severity: WithdrawalSeverity): List<WithdrawalWeek> =
        (0 until TIMELINE_DAYS / DAYS_PER_WEEK).map { index ->
            val startDay = index * DAYS_PER_WEEK
            WithdrawalWeek(
                weekNumber = index + 1,
                startDay = startDay,
                endDay = startDay + DAYS_PER_WEEK - 1,
                intensity = intensity(startDay, habitDurationYears, severity),
            )
        }
}

/** Severity vocabulary of `habit_progress.onboarding_severity_level` (V1 doc: MILD/MODERATE/SEVERE). */
enum class WithdrawalSeverity(val modifier: Double) {
    MILD(0.0),
    MODERATE(0.4),
    SEVERE(0.8),
}

/** One 7-day timeline bucket; [intensity] is [WithdrawalCurve.intensity] at [startDay]. */
data class WithdrawalWeek(
    val weekNumber: Int,
    val startDay: Int,
    val endDay: Int,
    val intensity: Double,
)
