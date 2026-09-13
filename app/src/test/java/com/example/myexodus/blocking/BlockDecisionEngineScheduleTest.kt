package com.example.myexodus.blocking

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Explicit-timezone schedule evaluation (blocking-core plan §7.1: "explicit schedule model
 * (ZoneId, days-of-week, local start/end time, midnight-crossing and DST handling defined
 * and unit-tested)"). Each instant is computed in UTC for determinism, then evaluated in the
 * rule's zone.
 */
class BlockDecisionEngineScheduleTest {

    private val originalDefaultZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(originalDefaultZone)
    }

    private fun engine(tz: String, startMin: Long?, endMin: Long?): BlockDecisionEngine {
        val rule = Rule(
            ruleId = "r1",
            packageName = "com.example.app",
            isAlwaysOn = false,
            dailyLimitMs = 0L,
            timeRangeStart = startMin,
            timeRangeEnd = endMin,
            activeDaysOfWeek = "1111111",
            timezone = tz,
            blockExpiresAt = 0L,
            severityLevel = "HIGH",
        )
        val snapshot = RulesSnapshot(mapOf("com.example.app" to rule), emptyList(), 0L)
        return BlockDecisionEngine { snapshot }
    }

    /** Instant in UTC — a specific wall-clock moment, immune to DST ambiguity at build time. */
    private fun utc(y: Int, month: Int, day: Int, h: Int, min: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, month - 1, day, h, min, 0)
        }.timeInMillis

    @Test
    fun midnightCrossing_windowEvaluatedInRuleZone() {
        // 21:00-07:00 in America/New_York. 02:00Z June 16 = 22:00 EDT June 15 (inside);
        // 01:30Z = 21:30 EDT (inside); 12:00Z = 08:00 EDT (after the window, allowed).
        val engine = engine("America/New_York", 21 * 60L, 7 * 60L)
        assertEquals(BlockDecision.Block("HIGH", false), engine.decideApp("com.example.app", utc(2026, 6, 16, 2, 0)))
        assertEquals(
            BlockDecision.Block("HIGH", false),
            engine.decideApp("com.example.app", utc(2026, 6, 16, 1, 30)),
        )
        assertEquals(BlockDecision.Allow, engine.decideApp("com.example.app", utc(2026, 6, 16, 12, 0)))
    }

    @Test
    fun dstSpringForward_nonexistentHourBehavesAsRuleLocalTime() {
        // 2026-03-08 America/New_York spring-forward: 02:00 EST -> 03:00 EDT.
        // Rule 02:30-04:00 Sunday. 01:45 EST (06:45Z) is before the window; 03:15 EDT
        // (07:15Z) is inside; 06:00 EDT (10:00Z) is after.
        val engine = engine("America/New_York", 150L, 240L)
        assertEquals(BlockDecision.Allow, engine.decideApp("com.example.app", utc(2026, 3, 8, 6, 45)))
        assertEquals(BlockDecision.Block("HIGH", false), engine.decideApp("com.example.app", utc(2026, 3, 8, 7, 15)))
        assertEquals(BlockDecision.Allow, engine.decideApp("com.example.app", utc(2026, 3, 8, 10, 0)))
    }

    @Test
    fun dstFallBack_bothOccurrencesOfRepeatedHourAreBlocked() {
        // 2026-11-01 America/New_York fall-back: 02:00 EDT -> 01:00 EST.
        // Rule 01:30-02:30 Sunday. 01:45 EDT (05:45Z) and 01:45 EST (06:45Z) are both inside;
        // 02:15 EST (07:15Z) inside; 03:00 EST (08:00Z) after.
        val engine = engine("America/New_York", 90L, 150L)
        assertEquals(BlockDecision.Block("HIGH", false), engine.decideApp("com.example.app", utc(2026, 11, 1, 5, 45)))
        assertEquals(BlockDecision.Block("HIGH", false), engine.decideApp("com.example.app", utc(2026, 11, 1, 6, 45)))
        assertEquals(BlockDecision.Block("HIGH", false), engine.decideApp("com.example.app", utc(2026, 11, 1, 7, 15)))
        assertEquals(BlockDecision.Allow, engine.decideApp("com.example.app", utc(2026, 11, 1, 8, 0)))
    }

    /** Home dashboard state must agree with the enforcement path — same evaluation, both ways. */
    @Test
    fun blocksNow_matchesDecideAppForSameRule() {
        val rule = Rule(
            ruleId = "r1",
            packageName = "com.example.app",
            isAlwaysOn = false,
            dailyLimitMs = 0L,
            timeRangeStart = 21 * 60L,
            timeRangeEnd = 7 * 60L,
            activeDaysOfWeek = "1111111",
            timezone = "America/New_York",
            blockExpiresAt = 0L,
            severityLevel = "HIGH",
        )
        fun blocksNow(at: Long, expiresAt: Long = 0L) = BlockDecisionEngine.blocksNow(
            isAlwaysOn = rule.isAlwaysOn,
            dailyLimitMs = rule.dailyLimitMs,
            timeRangeStart = rule.timeRangeStart,
            timeRangeEnd = rule.timeRangeEnd,
            activeDaysOfWeek = rule.activeDaysOfWeek,
            timezone = rule.timezone,
            blockExpiresAt = expiresAt,
            nowEpochMillis = at,
        )
        assertEquals(true, blocksNow(utc(2026, 6, 16, 2, 0)))
        assertEquals(false, blocksNow(utc(2026, 6, 16, 12, 0)))
        // Rule 3 beats the window: an expired rule reports not-blocking.
        assertEquals(false, blocksNow(utc(2026, 6, 16, 2, 0), expiresAt = utc(2026, 6, 15, 0, 0)))
    }

    @Test
    fun ruleZoneIsUsedNotDeviceDefaultZone() {
        // Device default forced to UTC; rule lives in America/Los_Angeles (23:00-01:00).
        // 00:30 PDT June 15 = 07:30 UTC — inside the LA window, far outside any UTC window.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val engine = engine("America/Los_Angeles", 23 * 60L, 60L)
        assertEquals(BlockDecision.Block("HIGH", false), engine.decideApp("com.example.app", utc(2026, 6, 15, 7, 30)))
        assertEquals(BlockDecision.Allow, engine.decideApp("com.example.app", utc(2026, 6, 15, 10, 0)))
    }
}
