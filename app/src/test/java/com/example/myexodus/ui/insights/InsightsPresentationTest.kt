package com.example.myexodus.ui.insights

import com.example.myexodus.insights.InsightDay
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chart-label math for the Insights page: bars share one scale per strip, durations never claim
 * a minute the ledger did not credit, and day labels are locale-driven (never hard-coded order).
 */
class InsightsPresentationTest {

    @Test
    fun compactDuration_floorsToMinutes() {
        assertEquals("0m", compactDuration(0))
        assertEquals("0m", compactDuration(59_999))
        assertEquals("1m", compactDuration(60_000))
        assertEquals("59m", compactDuration(3_599_999))
    }

    @Test
    fun compactDuration_usesTwoDigitMinutesPastTheHour() {
        assertEquals("1h00", compactDuration(3_600_000))
        assertEquals("2h05", compactDuration(7_500_000))
        assertEquals("12h30", compactDuration(45_000_000))
    }

    @Test
    fun compactDuration_treatsNegativeInputAsZero() {
        assertEquals("0m", compactDuration(-1))
    }

    @Test
    fun shareOf_isValueOverMax_andClamps() {
        assertEquals(0f, shareOf(value = 5, max = 0), 0.0001f)
        assertEquals(0.5f, shareOf(value = 5, max = 10), 0.0001f)
        assertEquals(1f, shareOf(value = 15, max = 10), 0.0001f)
        assertEquals(0f, shareOf(value = 0, max = 10), 0.0001f)
    }

    @Test
    fun stripScales_useTheTallestDay() {
        val days = listOf(
            day(LocalDate.of(2026, 10, 20), blocked = 2, overrides = 3, creditedMs = 60_000),
            day(LocalDate.of(2026, 10, 21), blocked = 4, overrides = 0, creditedMs = 120_000),
            day(LocalDate.of(2026, 10, 22), blocked = 0, overrides = 0, creditedMs = 0),
        )

        assertEquals(5, maxAttempts(days))
        assertEquals(120_000L, maxCreditedMs(days))
    }

    @Test
    fun stripScales_areZeroForAnEmptyWindow() {
        assertEquals(0, maxAttempts(emptyList()))
        assertEquals(0L, maxCreditedMs(emptyList()))
    }

    @Test
    fun shortDayLabel_followsTheGivenLocale() {
        val monday = LocalDate.of(2026, 10, 19)

        assertEquals("Mon", shortDayLabel(monday, Locale.US))
        assertEquals("lun.", shortDayLabel(monday, Locale.FRANCE))
    }

    private fun day(date: LocalDate, blocked: Int, overrides: Int, creditedMs: Long) =
        InsightDay(date = date, blocked = blocked, overrides = overrides, creditedMs = creditedMs)
}
