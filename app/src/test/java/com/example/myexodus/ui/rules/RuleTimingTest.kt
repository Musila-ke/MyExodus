package com.example.myexodus.ui.rules

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner report 2026-11-08: a block whose end date lands before its first chance to fire is
 * inert. [firstBlockOpportunityMillis] is the oracle for that check — these pin its calendar
 * arithmetic, the day scope, and the overnight leg.
 */
class RuleTimingTest {

    private fun instant(local: String): Long =
        LocalDateTime.parse(local).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun fields(
        alwaysOn: Boolean = false,
        start: Long? = null,
        end: Long? = null,
        days: String = "1111111",
        limitMs: Long = 0L,
    ) = RuleFields(
        isAlwaysOn = alwaysOn,
        timeRangeStart = start,
        timeRangeEnd = end,
        activeDaysOfWeek = days,
        dailyLimitMs = limitMs,
    )

    // 2026-11-03 is a Tuesday; 2026-11-09 the following Monday.
    private val tuesdayNoon = instant("2026-11-03T12:00")

    @Test
    fun windowOnly_opensAtItsStart_onTheNextActiveStartDay() {
        val mondayWindow = fields(start = 22 * 60, end = 23 * 60, days = "1000000")

        assertEquals(
            instant("2026-11-09T22:00"),
            firstBlockOpportunityMillis(mondayWindow, "UTC", tuesdayNoon),
        )
    }

    @Test
    fun overnightWindowAlreadyRunning_countsAsNow_viaItsStartDay() {
        // Monday 22:00-07:00 is still Monday's window on Tuesday at 03:00 (engine ruling).
        val mondayOvernight = fields(start = 22 * 60, end = 7 * 60, days = "1000000")
        val tuesdayThree = instant("2026-11-03T03:00")

        assertEquals(
            tuesdayThree,
            firstBlockOpportunityMillis(mondayOvernight, "UTC", tuesdayThree),
        )
    }

    @Test
    fun wholeDayMechanisms_openOnAnyActiveDay() {
        val alwaysOnToday = fields(alwaysOn = true, days = "0100000") // Tuesday
        assertEquals(tuesdayNoon, firstBlockOpportunityMillis(alwaysOnToday, "UTC", tuesdayNoon))

        val limitTomorrow = fields(limitMs = 60_000L, days = "0010000") // Wednesday
        assertEquals(
            instant("2026-11-04T00:00"),
            firstBlockOpportunityMillis(limitTomorrow, "UTC", tuesdayNoon),
        )
    }

    @Test
    fun noActiveDay_neverFires() {
        assertEquals(null, firstBlockOpportunityMillis(fields(alwaysOn = true, days = "0000000"), "UTC", tuesdayNoon))
    }

    @Test
    fun expiryBeforeTheFirstOpportunity_rejects() {
        val mondayWindow = fields(start = 22 * 60, end = 23 * 60, days = "1000000")

        assertTrue(expiresBeforeFirstBlock(mondayWindow, "UTC", instant("2026-11-09T21:00"), tuesdayNoon))
        assertFalse(expiresBeforeFirstBlock(mondayWindow, "UTC", instant("2026-11-09T23:00"), tuesdayNoon))
    }

    @Test
    fun expiryAfterAnActiveDayStarts_isAccepted() {
        // Ends tonight at 23:59; Tuesday is active and some of it remains.
        val tuesdayLimit = fields(limitMs = 60_000L, days = "0100000")
        assertFalse(
            expiresBeforeFirstBlock(tuesdayLimit, "UTC", instant("2026-11-03T23:59"), tuesdayNoon),
        )
    }

    @Test
    fun expiryNextDay_withTodayInactive_rejects() {
        // Tuesday is off; Wednesday starts after tonight's end date.
        val wednesdayLimit = fields(limitMs = 60_000L, days = "0010000")
        assertTrue(
            expiresBeforeFirstBlock(wednesdayLimit, "UTC", instant("2026-11-03T23:59"), tuesdayNoon),
        )
    }

    @Test
    fun neverEndingRules_areNeverRejected() {
        val impossible = fields(alwaysOn = true, days = "0000000")
        assertFalse(expiresBeforeFirstBlock(impossible, "UTC", 0L, tuesdayNoon))
    }

    @Test
    fun aRuleThatNeverFires_isRejectedOnceItHasAnEndDate() {
        val impossible = fields(alwaysOn = true, days = "0000000")
        assertTrue(expiresBeforeFirstBlock(impossible, "UTC", instant("2030-01-01T00:00"), tuesdayNoon))
    }
}
