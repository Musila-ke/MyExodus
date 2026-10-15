package com.example.myexodus.blocking

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task #14 (owner mandate 2026-10-15): "you never logically did a stress test and exhaustive
 * test and edge case exhaustion on these" — schedule × daily limit × weekday/weekend scope ×
 * inside/outside window × multi-rule combos × input-domain edges. The hand-picked cells live
 * in [BlockModeMatrixTest] and [BlockDecisionEngineScheduleTest]; this suite adds the
 * *systematic* half:
 *
 * - [sweep_engineMatchesAnIndependentOracleAcrossTheWholeMatrix] runs the full Cartesian
 *   product (112,896 cells, two zones) and compares the engine against an oracle written
 *   independently in java.time — the engine runs on Calendar/TimeZone, the oracle on
 *   Instant/ZonedDateTime, so a shared implementation bug cannot cancel out (a tautology
 *   check would prove nothing).
 * - Input-domain edges: malformed day strings, half/zero-length/full-day windows, negative
 *   and extreme usage, the expiry==now boundary.
 * - Multi-rule: overlapping URL patterns resolve first-match-wins by list order, and app
 *   rules are independent of URL rules.
 * - The owner's reported shape as a named regression: schedule window + daily limit, used
 *   *outside* the window, blocks at the quota — given the usage input. The 2026-10-15 live
 *   defect was upstream of this engine (the accounting gate voided that input); the pinned
 *   `usage = null → Allow` row is exactly why the pipeline is load-bearing for this shape.
 */
class BlockDecisionEngineCombinationSweepTest {

    private val limit = 2 * HOUR

    // --- the exhaustive sweep ---

    @Test
    fun sweep_engineMatchesAnIndependentOracleAcrossTheWholeMatrix() {
        val zones = listOf("UTC", "America/New_York")
        val instants = listOf(
            utc(2026, 9, 18, 23, 30), // Friday
            utc(2026, 9, 19, 3, 0), utc(2026, 9, 19, 5, 0), utc(2026, 9, 19, 6, 0),
            utc(2026, 9, 19, 12, 0), utc(2026, 9, 19, 13, 0), utc(2026, 9, 19, 23, 30),
            utc(2026, 9, 20, 3, 0), utc(2026, 9, 21, 13, 0), // Friday, Saturday (×6), Sunday, Monday
        )
        // start/end minutes-since-midnight; null = the mechanism is absent from the rule.
        val windows = listOf<Pair<Long?, Long?>>(
            null to null, // no window
            300L to 720L, // 05:00-12:00
            1320L to 420L, // 22:00-07:00 overnight
            600L to 600L, // zero-length
            0L to 1440L, // full day
            600L to null, // half window (start only)
            null to 600L, // half window (end only)
        )
        val daysAxis = listOf(
            "1111111", "0000011", "0000010", "0000100", "0000000", "111111", "", "11111111",
        )
        val usages = listOf(null, 0L, -1L, limit - 1, limit, limit + 1, Long.MAX_VALUE)
        val expiries = listOf("never", "past", "exact", "future")

        var cells = 0
        for (zone in zones) {
            for (at in instants) {
                for ((start, end) in windows) {
                    for (days in daysAxis) {
                        for (alwaysOn in listOf(false, true)) {
                            for (limitMs in listOf(0L, limit)) {
                                for (usage in usages) {
                                    for (expiry in expiries) {
                                        val expiresAt = when (expiry) {
                                            "never" -> 0L
                                            "past" -> at - 1
                                            "exact" -> at
                                            else -> at + 1
                                        }
                                        val expected = oracle(
                                            alwaysOn, limitMs, start, end, days, expiresAt, at, usage, zone,
                                        )
                                        val rule = Rule(
                                            ruleId = "sweep",
                                            packageName = PKG,
                                            isAlwaysOn = alwaysOn,
                                            dailyLimitMs = limitMs,
                                            timeRangeStart = start,
                                            timeRangeEnd = end,
                                            activeDaysOfWeek = days,
                                            timezone = zone,
                                            blockExpiresAt = expiresAt,
                                            severityLevel = "HIGH",
                                        )
                                        val engine = BlockDecisionEngine {
                                            RulesSnapshot(mapOf(PKG to rule), emptyList(), 1L)
                                        }
                                        val actual = engine.decideApp(PKG, at, usage) is BlockDecision.Block
                                        assertEquals(
                                            "zone=$zone at=${Instant.ofEpochMilli(at)} win=($start,$end) days='$days' " +
                                                "always=$alwaysOn limit=$limitMs usage=$usage expiry=$expiry",
                                            expected,
                                            actual,
                                        )
                                        cells++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // The full product, pinned as a literal so a later edit cannot silently shrink the matrix.
        assertEquals(2 * 9 * 7 * 8 * 2 * 2 * 7 * 4, cells)
    }

    /** The spec, re-derived independently in java.time (the engine runs on Calendar/TimeZone —
     *  different machinery on purpose, so a shared bug cannot make both agree). */
    private fun oracle(
        alwaysOn: Boolean,
        limitMs: Long,
        startMin: Long?,
        endMin: Long?,
        days: String,
        expiresAt: Long,
        nowMs: Long,
        usage: Long?,
        zone: String,
    ): Boolean {
        if (expiresAt > 0 && nowMs > expiresAt) return false
        val dt = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(zone))
        val dow = dt.dayOfWeek
        fun active(day: DayOfWeek) = days.getOrNull(day.value - 1) == '1'
        if (startMin != null && endMin != null) {
            val nowMin = dt.hour * 60L + dt.minute
            val inWindow =
                if (startMin <= endMin) nowMin >= startMin && nowMin < endMin
                else nowMin >= startMin || nowMin < endMin
            if (inWindow) {
                // Post-midnight leg of an overnight window belongs to the day it started.
                val windowDay = if (endMin < startMin && nowMin < endMin) dow.minus(1) else dow
                if (active(windowDay)) return true
            }
        }
        if (usage != null && limitMs > 0 && active(dow) && usage >= limitMs) return true
        return alwaysOn && active(dow)
    }

    // --- input-domain edges: malformed day strings ---

    @Test
    fun days_stringsWithoutAOneForTheDay_matchNoDay_everyMechanismLetsGo() {
        // Days scale the WHOLE rule; a day the string does not cover lets every mechanism go.
        // These are editor-never-writes-it shapes only a hand-edited or legacy row carries.
        for (bad in listOf("", "0000000", "abc", "00x0x0x")) {
            val e = engine(alwaysOn = true, limitMs = limit, window = 300L to 720L, days = bad)
            assertEquals("days='$bad' inside window", BlockDecision.Allow, e.decideApp(PKG, sat(6), limit + 1))
            assertEquals("days='$bad' at quota", BlockDecision.Allow, e.decideApp(PKG, sat(13), limit))
        }
        // A short string covers exactly the days its characters reach: "111111" is Mon-Sat,
        // so Sunday (index 6 -> absent) matches nothing — while Saturday still blocks.
        val short = engine(alwaysOn = true, limitMs = limit, window = 300L to 720L, days = "111111")
        assertEquals(BlockDecision.Allow, short.decideApp(PKG, sun(6), limit + 1))
        assertTrue(short.decideApp(PKG, sat(6)) is BlockDecision.Block)
    }

    @Test
    fun days_longerThanSeven_extraCharactersAreIgnored() {
        // Index 0-6 are the whole convention; an 8-char legacy string still means Mon..Sun.
        val e = engine(alwaysOn = true, days = "11111111")
        assertTrue(e.decideApp(PKG, sun(13)) is BlockDecision.Block)
    }

    // --- input-domain edges: window forms the sweep compresses ---

    @Test
    fun window_zeroLength_neverBlocks() {
        val e = engine(window = 600L to 600L)
        assertEquals(BlockDecision.Allow, e.decideApp(PKG, sat(9, 59)))
        assertEquals(BlockDecision.Allow, e.decideApp(PKG, sat(10, 0)))
        assertEquals(BlockDecision.Allow, e.decideApp(PKG, sat(10, 1)))
    }

    @Test
    fun window_halfSpecified_isInert_neverBlocks() {
        // The editor writes both ends or neither; an engine-level half window must not
        // half-apply (no crash, no partial window).
        assertEquals(BlockDecision.Allow, engine(window = 600L to null).decideApp(PKG, sat(10, 30)))
        assertEquals(BlockDecision.Allow, engine(window = null to 600L).decideApp(PKG, sat(10, 30)))
    }

    @Test
    fun window_fullDay_blocksMidnightToMidnight() {
        val e = engine(window = 0L to 1440L)
        assertTrue(e.decideApp(PKG, sat(0, 0)) is BlockDecision.Block)
        assertTrue(e.decideApp(PKG, sat(23, 59)) is BlockDecision.Block)
    }

    // --- input-domain edges: quota input values and the expiry instant ---

    @Test
    fun quota_usageInputDomain_onlyAtOrAboveAPositiveLimitBlocks() {
        val e = engine(limitMs = limit)
        assertEquals("blank (no accounting input)", BlockDecision.Allow, e.decideApp(PKG, sat(13)))
        assertEquals("zero", BlockDecision.Allow, e.decideApp(PKG, sat(13), 0L))
        assertEquals("negative cannot block", BlockDecision.Allow, e.decideApp(PKG, sat(13), -1L))
        assertEquals(BlockDecision.Allow, e.decideApp(PKG, sat(13), limit - 1))
        assertTrue(e.decideApp(PKG, sat(13), limit) is BlockDecision.Block)
        assertTrue(e.decideApp(PKG, sat(13), limit + 1) is BlockDecision.Block)
        assertTrue("over-max still blocks", e.decideApp(PKG, sat(13), Long.MAX_VALUE) is BlockDecision.Block)
        // A zero limit is "no quota", not "blocked at zero" — even over any usage.
        assertFalse(engine(limitMs = 0L).decideApp(PKG, sat(13), Long.MAX_VALUE) is BlockDecision.Block)
    }

    @Test
    fun expiry_isStrictlyAfter_theInstant_soTheExactInstantStillBlocks() {
        val at = sat(13)
        assertTrue(engine(alwaysOn = true, expiresAt = at).decideApp(PKG, at) is BlockDecision.Block)
        assertTrue(engine(alwaysOn = true, expiresAt = at + 1).decideApp(PKG, at) is BlockDecision.Block)
        assertEquals(
            BlockDecision.Allow,
            engine(alwaysOn = true, expiresAt = at - 1).decideApp(PKG, at),
        )
    }

    // --- multi-rule: URL first-match-wins, app/url independence ---

    @Test
    fun overlappingUrlRules_firstMatchInListOrderWins_appRulesAreIndependent() {
        fun urlRule(id: String, alwaysOn: Boolean) = com.example.myexodus.data.db.entity.RestrictionRule(
            ruleId = id, profileId = "p", blockedPackage = "example.com", targetType = "WEBSITE",
            isAlwaysOn = alwaysOn, dailyLimitMs = 0L, timeRangeStart = null, timeRangeEnd = null,
            activeDaysOfWeek = EVERY_DAY, blockExpiresAt = 0L, severityLevel = "HIGH",
        )
        val candidate = UrlPattern.canonicalizeCandidate("sub.example.com")!!
        // r-loose first: it matches but never blocks at this instant; r-strict (always-on)
        // would block, but the list order adjudicates before any block is considered.
        val snapshot = RulesSnapshot.build(
            listOf(urlRule("r-loose", alwaysOn = false), urlRule("r-strict", alwaysOn = true)),
            1L,
            sat(13),
        )
        val engine = BlockDecisionEngine { snapshot }
        assertEquals("r-loose", engine.matchingUrlRule(candidate)!!.ruleId)
        assertEquals(BlockDecision.Allow, engine.decideUrl(candidate, sat(13)))

        val reversed = BlockDecisionEngine {
            RulesSnapshot(snapshot.appRules, snapshot.urlRules.reversed(), 1L)
        }
        assertTrue(reversed.decideUrl(candidate, sat(13)) is BlockDecision.Block)

        // No app rule for the browser package: blocking the site never blocks the app.
        assertEquals(BlockDecision.Allow, engine.decideApp("com.android.chrome", sat(13)))
    }

    // --- DST × overnight × day scope (the axis combinations ScheduleTest does not cross) ---

    @Test
    fun overnightWindow_acrossSpringForward_secondLegStillBelongsToItsStartDay() {
        // 2026-03-08 America/New_York spring-forward: 02:00 EST -> 03:00 EDT; 02:xx does not
        // exist locally. Saturday-scoped 22:00-07:00 must cover Sunday 03:30 EDT (07:30Z) —
        // it is Saturday's window — and lift at 07:00 local (11:00Z).
        val e = engine(window = 1320L to 420L, days = SATURDAY, timezone = "America/New_York")
        assertTrue("Saturday 23:00 EST", e.decideApp(PKG, utc(2026, 3, 8, 4, 0)) is BlockDecision.Block)
        assertTrue("Sunday 03:30 EDT, post-jump", e.decideApp(PKG, utc(2026, 3, 8, 7, 30)) is BlockDecision.Block)
        assertEquals(BlockDecision.Allow, e.decideApp(PKG, utc(2026, 3, 8, 11, 0)))
        // Monday 03:00 EDT would belong to Sunday — out of scope for a Saturday-only rule.
        assertEquals(BlockDecision.Allow, e.decideApp(PKG, utc(2026, 3, 9, 7, 0)))
    }

    // --- the owner's reported shape, named (task #14) ---

    @Test
    fun ownerReportedShape_schedulePlusLimit_usedOutsideTheWindow_blocksAtTheQuota() {
        // Instagram: window 09:00-17:00 + 2 h daily limit; used after the window. The quota
        // is day-scoped, never window-scoped — at/over 2 h it must block any hour of an
        // active day. usage=null outside the window allows BY DESIGN (the geometry callers
        // pass no usage); this is the row the 2026-10-15 live defect starved of true usage.
        val e = engine(limitMs = limit, window = 540L to 1020L)
        assertTrue("inside window, no usage yet", e.decideApp(PKG, sat(10)) is BlockDecision.Block)
        assertEquals("outside window, under quota", BlockDecision.Allow, e.decideApp(PKG, sat(20, 15), limit - 1))
        assertTrue("outside window, at quota", e.decideApp(PKG, sat(20, 15), limit) is BlockDecision.Block)
        assertTrue("before window, over quota", e.decideApp(PKG, sat(4, 0), limit + 1) is BlockDecision.Block)
        assertEquals("outside window, no accounting input", BlockDecision.Allow, e.decideApp(PKG, sat(20, 15)))

        // Weekday/weekend scope on the same shape: weekend-scoped rules block Saturday night,
        // never Monday; Monday's usage belongs to Monday.
        val weekend = engine(limitMs = limit, window = 540L to 1020L, days = WEEKENDS)
        assertTrue(weekend.decideApp(PKG, sat(20, 15), limit) is BlockDecision.Block)
        assertEquals(BlockDecision.Allow, weekend.decideApp(PKG, mon(20, 15), limit))
    }

    private companion object {
        const val PKG = "com.instagram.android"
        const val HOUR = 60 * 60_000L
        val WEEKENDS = "0000011"
        val SATURDAY = "0000010"
    }

    private fun engine(
        alwaysOn: Boolean = false,
        limitMs: Long = 0L,
        window: Pair<Long?, Long?>? = null,
        days: String = EVERY_DAY,
        expiresAt: Long = 0L,
        timezone: String = "UTC",
    ): BlockDecisionEngine {
        val rule = Rule(
            ruleId = "r1",
            packageName = PKG,
            isAlwaysOn = alwaysOn,
            dailyLimitMs = limitMs,
            timeRangeStart = window?.first,
            timeRangeEnd = window?.second,
            activeDaysOfWeek = days,
            timezone = timezone,
            blockExpiresAt = expiresAt,
            severityLevel = "HIGH",
        )
        return BlockDecisionEngine { RulesSnapshot(mapOf(PKG to rule), emptyList(), 1L) }
    }

    /** Instant in UTC; 2026-09-18/19/20/21 are Friday/Saturday/Sunday/Monday. */
    private fun utc(y: Int, month: Int, day: Int, h: Int, min: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, month - 1, day, h, min, 0)
        }.timeInMillis

    private fun sat(h: Int, min: Int = 0) = utc(2026, 9, 19, h, min)
    private fun sun(h: Int, min: Int = 0) = utc(2026, 9, 20, h, min)
    private fun mon(h: Int, min: Int = 0) = utc(2026, 9, 21, h, min)
}
