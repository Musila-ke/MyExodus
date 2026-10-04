package com.example.myexodus.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.RestrictionRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a rule row claims, per block-mode combination (user request 2026-09-20 the rows must
 * never claim a mechanism the engine will not enforce): days are rule-level and print once,
 * only when they narrow the rule; a limit beside "always on" is inert and never shown;
 * a usage rule with no live usage feed still makes no claim at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScheduleSummaryTest {

    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    private fun rule(
        alwaysOn: Boolean = false,
        limitMs: Long = 0L,
        window: Pair<Long, Long>? = null,
        days: String = "1111111",
        expiresAt: Long = 0L,
    ) = RestrictionRule(
        ruleId = "r1", profileId = "p", blockedPackage = "com.example.app", targetType = "APP",
        isAlwaysOn = alwaysOn, dailyLimitMs = limitMs,
        timeRangeStart = window?.first, timeRangeEnd = window?.second,
        activeDaysOfWeek = days, timezone = "UTC", blockExpiresAt = expiresAt, severityLevel = "HIGH",
    )

    @Test
    fun alwaysOn_isJustAlwaysOn_unlessTheDaysNarrowIt() {
        assertEquals(res.getString(R.string.schedule_always_on), scheduleSummary(rule(alwaysOn = true), res))
        assertEquals(
            "Always on · weekends",
            scheduleSummary(rule(alwaysOn = true, days = "0000011"), res),
        )
    }

    @Test
    fun limitOnly_claimsTheLimit_andTheDayScopeWhenNarrowed() {
        assertEquals("2h daily limit", scheduleSummary(rule(limitMs = 2 * HOUR), res))
        assertEquals("2h daily limit · weekends", scheduleSummary(rule(limitMs = 2 * HOUR, days = "0000011"), res))
    }

    @Test
    fun legacyAlwaysOnWithALimit_neverClaimsTheInertLimit() {
        assertEquals(
            "the limit cannot fire under an always-on rule",
            res.getString(R.string.schedule_always_on),
            scheduleSummary(rule(alwaysOn = true, limitMs = 2 * HOUR), res),
        )
    }

    @Test
    fun windowAndLimit_shareOneDayScope_printedOnce() {
        val summary = scheduleSummary(rule(limitMs = 2 * HOUR, window = 5 * 60L to 12 * 60L, days = "0000011"), res)
        assertEquals(
            "${formatTime(5 * 60L)}–${formatTime(12 * 60L)} · 2h daily limit · weekends",
            summary,
        )
    }

    @Test
    fun everyDayWindow_printsNoDayPart() {
        assertEquals(
            "${formatTime(22 * 60L)}–${formatTime(7 * 60L)} (overnight)",
            scheduleSummary(rule(window = 22 * 60L to 7 * 60L), res),
        )
    }

    @Test
    fun malformedDayString_isOmitted_ratherThanClaimingADay() {
        val summary = scheduleSummary(rule(alwaysOn = true, days = "11"), res)
        assertEquals(res.getString(R.string.schedule_always_on), summary)
    }

    @Test
    fun headerDate_namesTheWeekday_andNeverTheYear() {
        val millis = LocalDate.of(2026, 9, 22).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val text = formatHeaderDate(millis)
        val tuesday = DayOfWeek.TUESDAY.getDisplayName(TextStyle.FULL, Locale.getDefault())
        assertTrue(text, text.contains(tuesday))
        assertTrue(text, text.contains("22"))
        assertFalse("the Home eyebrow carries no year: $text", text.contains("2026"))
    }

    @Test
    fun expiry_isAppendedForEveryMechanism() {
        val summary = scheduleSummary(rule(alwaysOn = true, expiresAt = utc(2026, 10, 1, 12, 0)), res)
        assertTrue(summary, summary.contains("until"))
    }

    @Test
    fun freeSinceDate_dropsTheYearOnlyWithinTheSameYear() {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.of(2026, 10, 3).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val thisYear = LocalDate.of(2026, 10, 1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val lastYear = LocalDate.of(2025, 12, 31).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val oct = Month.OCTOBER.getDisplayName(TextStyle.SHORT, Locale.getDefault())

        val recent = formatDayMonth(thisYear, now)
        assertTrue(recent, recent.contains(oct))
        assertFalse("a this-year date keeps no year: $recent", recent.contains("2026"))

        val older = formatDayMonth(lastYear, now)
        assertTrue("another year stays unambiguous: $older", older.contains("2025"))
    }

    @Test
    fun rowState_dayScopedAlwaysOn_saysNotActiveNow_onAnInactiveDay() {
        val weekendLockdown = rule(alwaysOn = true, days = "0000011")
        val saturday = utc(2026, 9, 19, 12, 0)
        val monday = utc(2026, 9, 21, 12, 0)
        assertEquals(RuleRowState(res.getString(R.string.state_blocked_now), true), ruleRowState(weekendLockdown, saturday, res))
        assertEquals(RuleRowState(res.getString(R.string.state_not_active_now), false), ruleRowState(weekendLockdown, monday, res))
    }

    @Test
    fun rowState_usageRule_makesNoClaimWithoutTheUsageFeed() {
        // The engine's quota branch needs today's usage; the row has none, so it says nothing
        // rather than guessing "not active".
        val state = ruleRowState(rule(limitMs = 2 * HOUR), utc(2026, 9, 19, 12, 0), res)
        assertNull(state.label)
        assertFalse(state.active)
    }

    @Test
    fun rowState_quotaRuleOnAnInactiveDay_makesNoClaim() {
        // Day scope is decided by the engine, so an inactive day is an Allow — and an Allow
        // with a limit is still "no claim" (the limit's state is unknown to the row).
        val state = ruleRowState(rule(limitMs = 2 * HOUR, days = "0000011"), utc(2026, 9, 21, 12, 0), res)
        assertNull(state.label)
    }

    @Test
    fun rowState_lapsedDurationOutranksPause_becauseTheUserNeverPausedIt() {
        // #18: the sweep flips a lapsed rule's toggle off — the pill must own that ("Expired")
        // rather than blame a pause the user never made.
        assertEquals(
            RuleRowState(res.getString(R.string.state_expired), false),
            ruleRowState(
                rule(alwaysOn = true, expiresAt = utc(2026, 9, 1, 12, 0)).copy(isEnabled = false),
                utc(2026, 9, 19, 12, 0),
                res,
            ),
        )
        assertEquals(
            "a plain pause still says Paused",
            RuleRowState(res.getString(R.string.state_paused), false),
            ruleRowState(rule(alwaysOn = true).copy(isEnabled = false), utc(2026, 9, 19, 12, 0), res),
        )
    }

    private fun utc(y: Int, month: Int, day: Int, h: Int, min: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, month - 1, day, h, min, 0)
        }.timeInMillis

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
