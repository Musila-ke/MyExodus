package com.example.myexodus.recovery

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the v2 M1.4 window semantics (V1 Req 19 / FR-7): [start,end), overnight wrap, day mask. */
class RiskWindowEvaluatorTest {

    // 2026-09-17 is a Thursday; 2026-09-21 the following Monday.
    private val thursday = LocalDate.of(2026, 9, 17)
    private val monday = LocalDate.of(2026, 9, 21)

    private val allDays = RiskWindowSpec(1, 20 * 60, 6 * 60, RiskWindowSpec.ALL_DAYS_MASK)
    private val daytime = RiskWindowSpec(2, 9 * 60, 17 * 60, RiskWindowSpec.ALL_DAYS_MASK)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): LocalDateTime =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))

    @Test
    fun overnightWindow_wrapsMidnight() {
        assertTrue(RiskWindowEvaluator.isActive(at(thursday, 23, 0), allDays))
        assertTrue(RiskWindowEvaluator.isActive(at(thursday, 0, 30), allDays))
        assertTrue(RiskWindowEvaluator.isActive(at(thursday, 20, 0), allDays))
        assertFalse(RiskWindowEvaluator.isActive(at(thursday, 12, 0), allDays))
        assertFalse("end minute is exclusive", RiskWindowEvaluator.isActive(at(thursday, 6, 0), allDays))
        assertFalse("start is inclusive but 19:59 is out", RiskWindowEvaluator.isActive(at(thursday, 19, 59), allDays))
    }

    @Test
    fun daytimeWindow_isHalfOpen() {
        assertTrue(RiskWindowEvaluator.isActive(at(thursday, 9, 0), daytime))
        assertTrue(RiskWindowEvaluator.isActive(at(thursday, 16, 59), daytime))
        assertFalse(RiskWindowEvaluator.isActive(at(thursday, 17, 0), daytime))
        assertFalse(RiskWindowEvaluator.isActive(at(thursday, 8, 59), daytime))
    }

    @Test
    fun afterMidnightPart_belongsToPreviousDaysMaskBit() {
        // Thursday 20:00 → Friday 06:00: covers Friday 01:00 (Thursday's bit), not Saturday's.
        val thursdayOnly = RiskWindowSpec(3, 20 * 60, 6 * 60, 1 shl (java.time.DayOfWeek.THURSDAY.value - 1))
        assertTrue(RiskWindowEvaluator.isActive(at(thursday, 21, 0), thursdayOnly))
        assertTrue(
            "Friday 01:00 is the wrapped tail of Thursday's window",
            RiskWindowEvaluator.isActive(at(thursday.plusDays(1), 1, 0), thursdayOnly),
        )
        assertFalse(
            "Friday 21:00 starts a Friday window — Friday's bit is unset",
            RiskWindowEvaluator.isActive(at(thursday.plusDays(1), 21, 0), thursdayOnly),
        )
        assertFalse(RiskWindowEvaluator.isActive(at(thursday, 12, 0), thursdayOnly))
    }

    @Test
    fun daytimeWindow_usesItsOwnDaysBit() {
        val mondayOnly = RiskWindowSpec(4, 9 * 60, 17 * 60, 1 shl (java.time.DayOfWeek.MONDAY.value - 1))
        assertTrue(RiskWindowEvaluator.isActive(at(monday, 10, 0), mondayOnly))
        assertFalse(RiskWindowEvaluator.isActive(at(thursday, 10, 0), mondayOnly))
    }

    @Test
    fun disabledWindow_neverActive() {
        assertFalse(RiskWindowEvaluator.isActive(at(thursday, 23, 0), allDays.copy(enabled = false)))
    }

    @Test
    fun activeWindow_returnsFirstMatchAndNullWhenNone() {
        // 18:00 is past the daytime window's 17:00 end and before the overnight window's 20:00.
        assertNull(RiskWindowEvaluator.activeWindow(at(thursday, 18, 0), listOf(allDays, daytime)))
        assertEquals(daytime, RiskWindowEvaluator.activeWindow(at(thursday, 10, 0), listOf(daytime)))
        assertEquals(
            "a disabled window is skipped, the enabled one still answers",
            daytime,
            RiskWindowEvaluator.activeWindow(at(thursday, 10, 0), listOf(daytime.copy(enabled = false), daytime)),
        )
        assertEquals(
            "overlap: the first configured window wins",
            allDays,
            RiskWindowEvaluator.activeWindow(at(thursday, 21, 0), listOf(allDays, daytime)),
        )
    }

    @Test
    fun minutesUntilEnd_countsAcrossMidnight() {
        assertEquals(6 * 60 + 30, RiskWindowEvaluator.minutesUntilEnd(at(thursday, 23, 30), allDays))
        assertEquals(360, RiskWindowEvaluator.minutesUntilEnd(at(thursday, 0, 0), allDays))
        assertEquals(0, RiskWindowEvaluator.minutesUntilEnd(at(thursday, 12, 0), allDays))
    }
}
