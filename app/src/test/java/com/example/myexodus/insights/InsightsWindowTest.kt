package com.example.myexodus.insights

import com.example.myexodus.data.db.dao.AttemptStamp
import com.example.myexodus.data.db.entity.AttemptLog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Insights window is seven local days ending today. Attempts bucket by the device's current
 * zone at read time; stamps outside the window are dropped, never folded into the nearest day.
 */
class InsightsWindowTest {

    private val zone = ZoneId.of("Africa/Nairobi")

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun stamp(date: LocalDate, hour: Int, outcome: String) =
        AttemptStamp(occurredAtEpochMillis = millis(date, hour), outcome = outcome)

    @Test
    fun days_isSevenAscendingDaysEndingToday() {
        val now = millis(LocalDate.of(2026, 10, 22), 12)

        val days = InsightsWindow.days(now, zone)

        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2026, 10, 16), days.first())
        assertEquals(LocalDate.of(2026, 10, 22), days.last())
        assertEquals(days.sorted(), days)
    }

    @Test
    fun days_isLocalToTheZone_evenWhenUtcIsStillYesterday() {
        // 22:30 UTC on Oct 21 is already 01:30 on Oct 22 in Nairobi.
        val now = LocalDateTime.of(2026, 10, 21, 22, 30)
            .atZone(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()

        assertEquals(LocalDate.of(2026, 10, 22), InsightsWindow.days(now, zone).last())
        assertEquals(LocalDate.of(2026, 10, 21), InsightsWindow.days(now, ZoneId.of("UTC")).last())
    }

    @Test
    fun startMs_isMidnightAtTheOldestDay() {
        val now = millis(LocalDate.of(2026, 10, 22), 12)

        assertEquals(millis(LocalDate.of(2026, 10, 16), 0), InsightsWindow.startMs(now, zone))
    }

    @Test
    fun bucketAttempts_splitsBlockedFromOverridesPerDay() {
        val now = millis(LocalDate.of(2026, 10, 22), 12)
        val days = InsightsWindow.days(now, zone)
        val stamps = listOf(
            stamp(LocalDate.of(2026, 10, 20), 9, AttemptLog.OUTCOME_BLOCKED),
            stamp(LocalDate.of(2026, 10, 20), 10, AttemptLog.OUTCOME_BLOCKED),
            stamp(LocalDate.of(2026, 10, 20), 11, AttemptLog.OUTCOME_OVERRIDE),
            stamp(LocalDate.of(2026, 10, 22), 8, AttemptLog.OUTCOME_BLOCKED),
        )

        val counts = InsightsWindow.bucketAttempts(stamps, days, zone)

        assertEquals(DayAttempts(blocked = 2, overrides = 1), counts[LocalDate.of(2026, 10, 20)])
        assertEquals(DayAttempts(blocked = 1, overrides = 0), counts[LocalDate.of(2026, 10, 22)])
        assertFalse("a day with no attempts has no entry", counts.containsKey(LocalDate.of(2026, 10, 21)))
    }

    @Test
    fun bucketAttempts_dropsStampsOutsideTheWindow() {
        val now = millis(LocalDate.of(2026, 10, 22), 12)
        val days = InsightsWindow.days(now, zone)
        val stamps = listOf(
            stamp(LocalDate.of(2026, 10, 15), 23, AttemptLog.OUTCOME_BLOCKED),
            stamp(LocalDate.of(2026, 10, 16), 0, AttemptLog.OUTCOME_OVERRIDE),
        )

        val counts = InsightsWindow.bucketAttempts(stamps, days, zone)

        assertFalse(counts.containsKey(LocalDate.of(2026, 10, 15)))
        assertEquals(DayAttempts(blocked = 0, overrides = 1), counts[LocalDate.of(2026, 10, 16)])
        assertEquals(1, counts.values.sumOf { it.blocked + it.overrides })
    }

    @Test
    fun bucketAttempts_ignoresUnknownOutcomesWithoutInventingAZeroDay() {
        val now = millis(LocalDate.of(2026, 10, 22), 12)
        val days = InsightsWindow.days(now, zone)
        val stamps = listOf(stamp(LocalDate.of(2026, 10, 21), 9, "SOMETHING_ELSE"))

        val counts = InsightsWindow.bucketAttempts(stamps, days, zone)

        assertTrue(counts.isEmpty())
    }
}
