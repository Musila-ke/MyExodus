package com.example.myexodus.ui.rules

import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.domainusage.EngineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(
            "owner 2026-10-04: blank minutes beside hours is 2 hrs exactly, no 00 needed",
            2 * 60L,
            parseLimitTotalMinutes("2", ""),
        )
        assertEquals("and a blank hours field beside minutes reads as 0", 30L, parseLimitTotalMinutes("", "30"))
        assertEquals("whitespace counts as blank", 30L, parseLimitTotalMinutes(" ", "30"))
        assertNull("both blank is no limit entered", parseLimitTotalMinutes("", ""))
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
    fun validate_limitLongerThanItsWindow_isAccepted_dayScopedQuota() {
        // Audit A-01: the window is an additional *block* period (BlockDecisionEngine Rule 1),
        // not the only time the site can be used. Usage outside it still accrues, and the
        // quota fires at `usageTodayMs >= dailyLimitMs` on any active day (Rule 2). A 2 h
        // limit beside a 1 h block window is therefore meaningful, not incoherent — the old
        // rejection rested on reading the window as an allowance.
        assertNull(
            "1h block window + 2h daily quota: quota counts all use that day",
            validateBlockMode(false, true, true, 18 * 60L, 19 * 60L, 120L),
        )
        assertNull(
            "overnight 22:00–07:00 (9h block) + 10h quota",
            validateBlockMode(false, true, true, 22 * 60L, 7 * 60L, 10 * 60L),
        )
        assertNull(
            "a limit with no window",
            validateBlockMode(false, false, true, 22 * 60L, 7 * 60L, 20 * 60L),
        )
        assertNull(
            "always-on overrides both mechanisms",
            validateBlockMode(true, true, true, 22 * 60L, 7 * 60L, 10 * 60L),
        )
        assertEquals(
            "a full day is still unreachable and rejected",
            ModeError.LimitTooLarge,
            validateBlockMode(false, true, true, 18 * 60L, 19 * 60L, MINUTES_PER_DAY.toLong()),
        )
    }

    @Test
    fun websiteLimitMechanism_needsEnforce_butAppLimitsNeverDo() {
        // Audit A-03: OFF and ACCOUNT never shape or block, so a website limit stored under
        // either is inert — the editor must not offer the switch. App quotas ride the
        // accessibility usage accounting and are live in every mode.
        assertFalse(websiteLimitMechanismAvailable(isApp = false, mode = EngineMode.OFF))
        assertFalse(websiteLimitMechanismAvailable(isApp = false, mode = EngineMode.ACCOUNT))
        assertTrue(websiteLimitMechanismAvailable(isApp = false, mode = EngineMode.ENFORCE))
        EngineMode.entries.forEach {
            assertTrue("app limits are mode-free", websiteLimitMechanismAvailable(isApp = true, mode = it))
        }
    }

    @Test
    fun resumedRule_clearsALapsedExpiry_butKeepsAFutureOne() {
        // #18 resume semantics: re-enabling a lapsed rule must not be a dead toggle — "on"
        // after the end date means on again with no end date.
        val now = 1_700_000_000_000L
        val lapsed = rule().copy(blockExpiresAt = now - 1_000L)
        assertEquals(0L, resumedRule(lapsed, now).blockExpiresAt)
        val future = rule().copy(blockExpiresAt = now + 3_600_000L)
        assertEquals(now + 3_600_000L, resumedRule(future, now).blockExpiresAt)
        assertEquals(
            "a never-expiring rule is returned unchanged",
            0L,
            resumedRule(rule(), now).blockExpiresAt,
        )
    }

    private fun rule() = RestrictionRule(
        ruleId = "r-resume",
        profileId = "p-1",
        blockedPackage = "com.example.app",
        targetType = "APP",
        isAlwaysOn = true,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        blockExpiresAt = 0L,
        severityLevel = "HIGH",
    )

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
