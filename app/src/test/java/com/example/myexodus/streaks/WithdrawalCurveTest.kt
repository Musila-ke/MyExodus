package com.example.myexodus.streaks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §12 Withdrawal-formula requirements: boundary values for every duration/severity
 * combination, plus monotonicity (the vocabulary's worst case, SEVERE, never produces a
 * lower intensity than the mildest case at the same day/duration).
 */
class WithdrawalCurveTest {

    private val m = WithdrawalSeverity.MILD
    private val mo = WithdrawalSeverity.MODERATE
    private val se = WithdrawalSeverity.SEVERE

    @Test
    fun boundaryValues_matchTheApprovedCurve() {
        // day 0 is the curve's peak: baseDecay = 0.3 + e^0 = 1.3 before modifiers.
        assertEquals(1.3, WithdrawalCurve.intensity(0, 0, m), EPS)
        assertEquals(1.82, WithdrawalCurve.intensity(0, 0, mo), EPS)
        assertEquals(1.365, WithdrawalCurve.intensity(0, 1, m), EPS) // 1.3 × 1.05
        assertEquals(1.95, WithdrawalCurve.intensity(0, 15, m), EPS) // years cap at 10 → 1.3 × 1.5

        // day 27 (last timeline day): baseDecay = 0.3 + e^(-3).
        assertEquals(0.349787068367864, WithdrawalCurve.intensity(27, 0, m), EPS)
        assertEquals(0.612127369643762, WithdrawalCurve.intensity(27, 5, mo), EPS)

        // Clamp boundary: extremes exceed 2.0 and come back clamped, never above.
        assertEquals(2.0, WithdrawalCurve.intensity(0, 10, se), EPS) // raw 3.51
        assertEquals(2.0, WithdrawalCurve.intensity(0, 0, se), EPS) // raw 2.34
        assertEquals(2.0, WithdrawalCurve.intensity(0, 5, mo), EPS) // raw 2.275
    }

    @Test
    fun everyDurationAndSeverityCombination_staysInBounds() {
        for (years in 0..16) {
            for (severity in WithdrawalSeverity.entries) {
                for (day in 0..WithdrawalCurve.TIMELINE_DAYS) {
                    val value = WithdrawalCurve.intensity(day, years, severity)
                    assertTrue(
                        "out of bounds: $value at day=$day years=$years severity=$severity",
                        value in 0.0..2.0,
                    )
                }
            }
        }
    }

    @Test
    fun intensity_neverRisesWithDay_andNeverFallsWithSeverityOrDuration() {
        for (years in listOf(0, 1, 5, 10, 15)) {
            for (severity in WithdrawalSeverity.entries) {
                for (day in 0 until WithdrawalCurve.TIMELINE_DAYS) {
                    assertTrue(
                        "not decaying at day=$day years=$years severity=$severity",
                        WithdrawalCurve.intensity(day, years, severity) >=
                            WithdrawalCurve.intensity(day + 1, years, severity),
                    )
                }
            }
        }
        // Non-strict "never lower" (§12): the [0, 2] clamp legitimately ties the strongest
        // combinations at the ceiling. Strict separation below the clamp is asserted via the
        // hand-computed boundary values above.
        for (years in listOf(0, 5, 10)) {
            for (day in 0 until WithdrawalCurve.TIMELINE_DAYS) {
                assertTrue(
                    WithdrawalCurve.intensity(day, years, m) <=
                        WithdrawalCurve.intensity(day, years, mo),
                )
                assertTrue(
                    WithdrawalCurve.intensity(day, years, mo) <=
                        WithdrawalCurve.intensity(day, years, se),
                )
                assertTrue(
                    WithdrawalCurve.intensity(day, 0, m) <=
                        WithdrawalCurve.intensity(day, years, m),
                )
            }
        }
    }

    @Test
    fun weeks_areFourBuckets_eachAtItsFirstDay_andDecaying() {
        val weeks = WithdrawalCurve.weeks(habitDurationYears = 2, severity = mo)

        assertEquals(4, weeks.size)
        assertEquals(listOf(1, 2, 3, 4), weeks.map { it.weekNumber })
        assertEquals(listOf(0, 7, 14, 21), weeks.map { it.startDay })
        assertEquals(listOf(6, 13, 20, 27), weeks.map { it.endDay })
        weeks.forEach { week ->
            assertEquals(
                WithdrawalCurve.intensity(week.startDay, 2, mo),
                week.intensity,
                EPS,
            )
        }
        assertTrue(weeks.zipWithNext().all { (a, b) -> a.intensity > b.intensity })
    }

    private companion object {
        const val EPS = 1e-9
    }
}
