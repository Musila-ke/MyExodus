package com.example.myexodus.ui.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The block-mode form's pure logic (user request 2026-09-20): minutes parsing, the at-least-
 * one-mechanism gate, and what each combination stores. The three mechanisms are independent
 * switches — the old form defaulted always-on to true and made a plain daily limit
 * unreachable, which is the defect this pins shut.
 */
class RuleModeTest {

    @Test
    fun parseLimitTotalMinutes_readsHoursAndMinutes_asOneTotal() {
        assertEquals(8 * 60L, parseLimitTotalMinutes("8", "0"))
        assertEquals(90L, parseLimitTotalMinutes("1", "30"))
        assertEquals(30L, parseLimitTotalMinutes("0", "30"))
        assertEquals(30L, parseLimitTotalMinutes(" 0 ", "30"))
        assertNull("zero total is not a limit", parseLimitTotalMinutes("0", "0"))
        assertNull("blank hours", parseLimitTotalMinutes("", "30"))
        assertNull("blank minutes", parseLimitTotalMinutes("2", ""))
        assertNull("non-numeric", parseLimitTotalMinutes("two", "30"))
    }

    @Test
    fun validate_noMechanismSelected_isRejected() {
        assertEquals(ModeError.NoneSelected, validateBlockMode(false, false, false, 22 * 60L, 7 * 60L, null))
        assertEquals(
            "a limit that reads zero does not count as selected",
            ModeError.LimitInvalid,
            validateBlockMode(false, false, true, 22 * 60L, 7 * 60L, null),
        )
    }

    @Test
    fun validate_eachMechanismAlone_isAccepted() {
        assertNull("always-on alone", validateBlockMode(true, false, false, 22 * 60L, 7 * 60L, null))
        assertNull("window alone", validateBlockMode(false, true, false, 22 * 60L, 7 * 60L, null))
        assertNull("limit alone — the state the old form could not produce", validateBlockMode(false, false, true, 22 * 60L, 7 * 60L, 120L))
    }

    @Test
    fun validate_windowAndLimitTogether_isAccepted() {
        assertNull(validateBlockMode(false, true, true, 5 * 60L, 12 * 60L, 120L))
    }

    @Test
    fun validate_equalWindowTimes_isRejected_butOnlyWhenTheWindowIsLive() {
        assertEquals(ModeError.SameTime, validateBlockMode(false, true, false, 9 * 60L, 9 * 60L, null))
        assertNull(
            "always-on overrides the window, so a stale equal pair must not block saving",
            validateBlockMode(true, true, false, 9 * 60L, 9 * 60L, null),
        )
    }

    @Test
    fun validate_zeroLimit_isRejected_butNotWhenAlwaysOnOverridesIt() {
        assertEquals(ModeError.LimitInvalid, validateBlockMode(false, false, true, 22 * 60L, 7 * 60L, null))
        assertNull(validateBlockMode(true, false, true, 22 * 60L, 7 * 60L, null))
    }

    @Test
    fun validate_limitAtOrOverAFullDay_isRejected() {
        // User-reported 2026-09-20: "30 hrs a day" saved and could never fire — a usage day
        // is 24 h long, so any limit at or above one day is a rule that can never block.
        assertEquals(ModeError.LimitTooLarge, validateBlockMode(false, false, true, 22 * 60L, 7 * 60L, 30 * 60L))
        assertEquals(
            "exactly 24 h is unreachable too",
            ModeError.LimitTooLarge,
            validateBlockMode(false, false, true, 22 * 60L, 7 * 60L, MINUTES_PER_DAY.toLong()),
        )
        assertEquals(
            "the bound is on the total, not the minutes field (0 h 1440 m)",
            ModeError.LimitTooLarge,
            validateBlockMode(false, false, true, 22 * 60L, 7 * 60L, parseLimitTotalMinutes("0", "1440")),
        )
        assertNull(
            "23 h 59 m is the largest limit that can still fire",
            validateBlockMode(false, false, true, 22 * 60L, 7 * 60L, MINUTES_PER_DAY - 1L),
        )
    }

    @Test
    fun validate_tooLargeLimit_besideAlwaysOn_isInert_likeTheOtherLimitChecks() {
        assertNull(validateBlockMode(true, false, true, 22 * 60L, 7 * 60L, 30 * 60L))
    }

    @Test
    fun compose_limitAlone_storesNoWindow_andKeepsTheDays() {
        val fields = composeRuleFields(
            alwaysOn = false, windowEnabled = false, limitEnabled = true,
            startMinutes = 22 * 60L, endMinutes = 7 * 60L, days = "0000011", limitTotalMinutes = 120L,
        )
        assertEquals(false, fields.isAlwaysOn)
        assertNull(fields.timeRangeStart)
        assertNull(fields.timeRangeEnd)
        assertEquals(120L * 60_000, fields.dailyLimitMs)
        assertEquals("0000011", fields.activeDaysOfWeek)
    }

    @Test
    fun compose_alwaysOn_keepsItsDayScope_andZeroesTheOverriddenMechanisms() {
        // Weekend lockdown: "block all day" + weekends. The window and limit the form still
        // holds are not stored — the saved rule says exactly what the engine will do.
        val fields = composeRuleFields(
            alwaysOn = true, windowEnabled = true, limitEnabled = true,
            startMinutes = 5 * 60L, endMinutes = 12 * 60L, days = "0000011", limitTotalMinutes = 120L,
        )
        assertEquals(true, fields.isAlwaysOn)
        assertNull(fields.timeRangeStart)
        assertNull(fields.timeRangeEnd)
        assertEquals(0L, fields.dailyLimitMs)
        assertEquals("0000011", fields.activeDaysOfWeek)
    }

    @Test
    fun compose_windowAndLimit_storesBoth_underOneDayScope() {
        val fields = composeRuleFields(
            alwaysOn = false, windowEnabled = true, limitEnabled = true,
            startMinutes = 5 * 60L, endMinutes = 12 * 60L, days = "1111100", limitTotalMinutes = 120L,
        )
        assertEquals(5 * 60L, fields.timeRangeStart)
        assertEquals(12 * 60L, fields.timeRangeEnd)
        assertEquals(120L * 60_000, fields.dailyLimitMs)
        assertEquals("1111100", fields.activeDaysOfWeek)
    }
}
