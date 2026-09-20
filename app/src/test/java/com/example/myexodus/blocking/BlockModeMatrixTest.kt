package com.example.myexodus.blocking

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The finite block-mode matrix (user request 2026-09-20): every combination of the three
 * mechanisms — always-on (A), schedule window (W), daily quota (Q) — crossed with the
 * rule-level day scope, expiry, and pause, each state's decision pinned to the engine's
 * actual semantics rather than assumed.
 *
 * | state | meaning                                                        |
 * |-------|----------------------------------------------------------------|
 * | A     | blocked every hour of an active day                            |
 * | W     | blocked inside [start, end) of an active day                   |
 * | Q     | blocked once today's usage reaches the limit (active day only)  |
 * | A+W   | ≡ A — the window adds nothing (legacy rows; editor zeroes it)   |
 * | A+Q   | ≡ A — the quota is inert (the user's original trap row)         |
 * | A+W+Q | ≡ A — same                                                        |
 * | W+Q   | meaningful: blocked in the window, and after the quota is spent |
 * | none  | unreachable from the editor; inert if a legacy row carries it    |
 *
 * Pause and expiry are the two "off" switches: a paused rule is dropped by
 * [RulesSnapshot.build] before any decision (covered in RulesSnapshotTest), an expired rule is
 * lifted by [BlockDecisionEngine] itself — expiry is exercised here across all mechanisms at
 * once. Days scope the WHOLE rule (user decision 2026-09-20), so an inactive day allows every
 * mechanism — the accrual half of that is pinned in UsageAccountingEngineTest.
 *
 * Instants are UTC and fixed (Sat 2026-09-19, Sun 2026-09-20, Mon 2026-09-21) so the day
 * scope is explicit rather than whatever day the suite happens to run on.
 */
class BlockModeMatrixTest {

    private fun engine(
        alwaysOn: Boolean = false,
        limitMs: Long = 0L,
        window: Pair<Long, Long>? = null,
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

    private fun decide(engine: BlockDecisionEngine, at: Long, usageMs: Long? = null): BlockDecision =
        engine.decideApp(PKG, at, usageMs)

    // --- A: always-on ---

    @Test
    fun alwaysOn_blocksEveryHour_onEveryActiveDay() {
        val e = engine(alwaysOn = true)
        assertTrue(decide(e, sat(3)) is BlockDecision.Block)
        assertTrue(decide(e, sat(12)) is BlockDecision.Block)
        assertTrue(decide(e, mon(23, 59)) is BlockDecision.Block)
    }

    @Test
    fun alwaysOn_weekendScope_isTheWeekendLockdown() {
        val e = engine(alwaysOn = true, days = WEEKENDS)
        assertTrue("Saturday", decide(e, sat(6)) is BlockDecision.Block)
        assertTrue("Sunday", decide(e, sun(13)) is BlockDecision.Block)
        assertEquals("Monday is outside the day scope", BlockDecision.Allow, decide(e, mon(9)))
        assertEquals(BlockDecision.Allow, decide(e, mon(23, 59)))
    }

    // --- W: schedule window ---

    @Test
    fun windowOnly_blocksInsideTheWindow_onActiveDaysOnly() {
        val e = engine(window = 5 * 60L to 12 * 60L, days = WEEKENDS)
        assertTrue("start is inclusive", decide(e, sat(5)) is BlockDecision.Block)
        assertTrue("inside", decide(e, sat(6)) is BlockDecision.Block)
        assertEquals("end is exclusive", BlockDecision.Allow, decide(e, sat(12)))
        assertEquals(BlockDecision.Allow, decide(e, sat(13)))
        assertEquals("weekday is outside the day scope", BlockDecision.Allow, decide(e, mon(6)))
    }

    @Test
    fun windowOnly_overnightForm_postMidnightLegBelongsToTheDayItStarted() {
        // 22:00-07:00 (end < start) is the engine's overnight form. The after-midnight hours
        // belong to the day the window STARTED (user ruling 2026-09-20, matching the recovery
        // plan's RiskWindowEvaluator), so a weekend-scoped rule is still running Monday 03:00 —
        // Sunday night's window does not end until Monday 07:00.
        val e = engine(window = 22 * 60L to 7 * 60L, days = WEEKENDS)
        assertTrue(decide(e, sat(23)) is BlockDecision.Block)
        assertTrue("Saturday's window", decide(e, sun(3)) is BlockDecision.Block)
        assertTrue("Sunday's window", decide(e, mon(3)) is BlockDecision.Block)
        assertEquals("07:00 is the exclusive end", BlockDecision.Allow, decide(e, mon(7)))
        assertEquals("Monday night starts nothing — Monday is out of scope", BlockDecision.Allow, decide(e, mon(23)))
    }

    /** The discriminator between the two readings: current-day scope would ALLOW Saturday
     *  03:00 (Saturday is not Friday); start-day scope blocks it. And the scope still bounds
     *  the rule — Saturday's window needs Saturday in scope, so Sunday 03:00 allows. */
    @Test
    fun windowOnly_overnightForm_startDayOutOfScope_doesNotSpill() {
        val e = engine(window = 22 * 60L to 7 * 60L, days = FRIDAY)
        assertTrue(decide(e, fri(23)) is BlockDecision.Block)
        assertTrue("Friday's window runs into Saturday", decide(e, sat(3)) is BlockDecision.Block)
        assertEquals(BlockDecision.Allow, decide(e, sun(3)))
    }

    // --- Q: daily quota, no window (the state the old editor could not produce) ---

    @Test
    fun quotaOnly_blocksAtTheLimit_withNoWindowInvolved() {
        val e = engine(limitMs = 2 * HOUR)
        assertEquals("caller with no usage data (the UI row path)", BlockDecision.Allow, decide(e, sat(13)))
        assertEquals(BlockDecision.Allow, decide(e, sat(13), usageMs = 2 * HOUR - 1))
        assertTrue(decide(e, sat(13), usageMs = 2 * HOUR) is BlockDecision.Block)
        assertTrue(decide(e, sat(13), usageMs = 5 * HOUR) is BlockDecision.Block)
        assertTrue("any hour of an active day", decide(e, sat(3), usageMs = 2 * HOUR) is BlockDecision.Block)
    }

    @Test
    fun quotaOnly_dayScoped_neverBlocksOnAnInactiveDay() {
        val e = engine(limitMs = 2 * HOUR, days = WEEKENDS)
        assertTrue(decide(e, sat(13), usageMs = 5 * HOUR) is BlockDecision.Block)
        assertEquals(
            "Monday is not counted, so its usage cannot block",
            BlockDecision.Allow,
            decide(e, mon(6), usageMs = 5 * HOUR),
        )
    }

    // --- W+Q: the combination the user asked about ("05:00-12:00 AND 2h a day") ---

    @Test
    fun windowAndQuota_bothApplyIndependently() {
        val e = engine(limitMs = 2 * HOUR, window = 5 * 60L to 12 * 60L)
        assertTrue("inside the window, quota untouched", decide(e, sat(6), usageMs = 0L) is BlockDecision.Block)
        assertEquals("outside the window, under quota", BlockDecision.Allow, decide(e, sat(13), usageMs = 2 * HOUR - 1))
        assertTrue("outside the window, at quota", decide(e, sat(13), usageMs = 2 * HOUR) is BlockDecision.Block)
        assertTrue("before the window, at quota", decide(e, sat(4), usageMs = 2 * HOUR) is BlockDecision.Block)
    }

    @Test
    fun windowAndQuota_areBothScopedByTheSameDays() {
        val e = engine(limitMs = 2 * HOUR, window = 5 * 60L to 12 * 60L, days = WEEKENDS)
        assertTrue(decide(e, sat(6), usageMs = 5 * HOUR) is BlockDecision.Block)
        assertEquals("Monday: neither the window nor the quota applies", BlockDecision.Allow, decide(e, mon(6), usageMs = 5 * HOUR))
        assertEquals(BlockDecision.Allow, decide(e, mon(13), usageMs = 5 * HOUR))
    }

    // --- A+X: always-on subordinates the other mechanisms ---

    @Test
    fun alwaysOnSubordinates_windowAndQuota_everyLegacyCombinationBlocksLikeAlwaysOn() {
        val aPlusW = engine(alwaysOn = true, window = 5 * 60L to 12 * 60L)
        val aPlusQ = engine(alwaysOn = true, limitMs = 2 * HOUR)
        val allThree = engine(alwaysOn = true, limitMs = 2 * HOUR, window = 5 * 60L to 12 * 60L)
        for ((name, e) in listOf("A+W" to aPlusW, "A+Q" to aPlusQ, "A+W+Q" to allThree)) {
            assertTrue("$name blocks outside the window at zero usage", decide(e, sat(13), usageMs = 0L) is BlockDecision.Block)
            assertTrue("$name blocks inside the window", decide(e, sat(6), usageMs = 0L) is BlockDecision.Block)
            // The inert members must not restrict an active day further: still blocked.
            assertTrue("$name blocks at any hour", decide(e, sat(3), usageMs = 5 * HOUR) is BlockDecision.Block)
        }
    }

    @Test
    fun alwaysOnSubordinates_daysScopeStillBoundsTheLegacyCombinations() {
        val aPlusQ = engine(alwaysOn = true, limitMs = 2 * HOUR, days = WEEKENDS)
        assertEquals(BlockDecision.Allow, decide(aPlusQ, mon(13), usageMs = 5 * HOUR))
    }

    // --- none: unreachable from the editor, inert if a legacy row carries it ---

    @Test
    fun noMechanism_neverBlocks() {
        val e = engine()
        assertEquals(BlockDecision.Allow, decide(e, sat(6), usageMs = 5 * HOUR))
        assertEquals(BlockDecision.Allow, decide(e, mon(13), usageMs = 5 * HOUR))
    }

    // --- off switches: expiry (pause is the snapshot filter, see RulesSnapshotTest) ---

    @Test
    fun expiry_liftsEveryMechanismAtOnce() {
        val past = utc(2026, 9, 18, 0, 0)
        val expired = engine(alwaysOn = true, limitMs = 2 * HOUR, window = 5 * 60L to 12 * 60L, expiresAt = past)
        assertEquals(BlockDecision.Allow, decide(expired, sat(6), usageMs = 5 * HOUR))
        // The same rule before its expiry instant still blocks (§ engine Rule 3 ordering).
        val live = engine(alwaysOn = true, expiresAt = utc(2026, 9, 20, 0, 0))
        assertTrue(decide(live, sat(6)) is BlockDecision.Block)
    }

    @Test
    fun pausedRule_neverReachesTheEngine() {
        val snapshot = RulesSnapshot.build(
            listOf(
                com.example.myexodus.data.db.entity.RestrictionRule(
                    ruleId = "r1", profileId = "p", blockedPackage = PKG, targetType = "APP",
                    isAlwaysOn = true, dailyLimitMs = 0L, timeRangeStart = null, timeRangeEnd = null,
                    activeDaysOfWeek = EVERY_DAY, blockExpiresAt = 0L, severityLevel = "HIGH",
                    isEnabled = false,
                ),
            ),
            version = 1L,
            nowEpochMillis = utc(2026, 9, 19, 0, 0),
        )
        val engine = BlockDecisionEngine { snapshot }
        assertEquals(BlockDecision.Allow, engine.decideApp(PKG, sat(6)))
    }

    /** The other two mechanisms carry the same off switch: an expired window rule inside its
     *  own window allows, an expired quota rule over its limit allows. */
    @Test
    fun expiry_liftsWindowOnly_andQuotaOnly_individually() {
        val past = utc(2026, 9, 18, 0, 0)
        val expiredWindow = engine(window = 5 * 60L to 12 * 60L, expiresAt = past)
        assertEquals(BlockDecision.Allow, decide(expiredWindow, sat(6)))
        val expiredQuota = engine(limitMs = 2 * HOUR, expiresAt = past)
        assertEquals(BlockDecision.Allow, decide(expiredQuota, sat(6), usageMs = 5 * HOUR))
        // Non-expired twins still block — the inertness is the expiry, not a dead branch.
        assertTrue(decide(engine(window = 5 * 60L to 12 * 60L, expiresAt = utc(2026, 9, 20, 0, 0)), sat(6)) is BlockDecision.Block)
        assertTrue(decide(engine(limitMs = 2 * HOUR, expiresAt = utc(2026, 9, 20, 0, 0)), sat(6), usageMs = 5 * HOUR) is BlockDecision.Block)
    }

    /** …and pause (isEnabled=false, dropped by the snapshot build) likewise covers all three. */
    @Test
    fun pausedWindowAndQuotaRules_neverReachTheEngine() {
        fun pausedEngine(window: Pair<Long, Long>?, limitMs: Long): BlockDecisionEngine {
            val snapshot = RulesSnapshot.build(
                listOf(
                    com.example.myexodus.data.db.entity.RestrictionRule(
                        ruleId = "r1", profileId = "p", blockedPackage = PKG, targetType = "APP",
                        isAlwaysOn = false, dailyLimitMs = limitMs,
                        timeRangeStart = window?.first, timeRangeEnd = window?.second,
                        activeDaysOfWeek = EVERY_DAY, blockExpiresAt = 0L, severityLevel = "HIGH",
                        isEnabled = false,
                    ),
                ),
                version = 1L,
                nowEpochMillis = utc(2026, 9, 19, 0, 0),
            )
            return BlockDecisionEngine { snapshot }
        }
        assertEquals(BlockDecision.Allow, decide(pausedEngine(5 * 60L to 12 * 60L, 0L), sat(6)))
        assertEquals(BlockDecision.Allow, decide(pausedEngine(null, 2 * HOUR), sat(6), usageMs = 5 * HOUR))
    }

    // --- day axis boundaries ---

    /** V1 Req 3's own edge case: a Saturday-only rule runs to exactly midnight and not a
     *  minute into Sunday. The day check is part of the decision, so the boundary is where
     *  the rule's zone says midnight is. */
    @Test
    fun dayScope_dayBoundary_saturdayRuleLiftsAtSundayMidnight() {
        val e = engine(alwaysOn = true, days = SATURDAY)
        assertTrue("23:59 Saturday is still the scoped day", decide(e, sat(23, 59)) is BlockDecision.Block)
        assertEquals(BlockDecision.Allow, decide(e, sun(0, 0)))
        assertTrue("and Sunday is out until its own end", decide(e, sat(23, 58)) is BlockDecision.Block)
    }

    /** The day is judged in the RULE's zone, not the device's: the same instant is Saturday
     *  in UTC (blocked) and still Friday in UTC-11 (allowed). */
    @Test
    fun dayScope_judgedInTheRulesZone_notTheCallersZone() {
        val saturdayUtc10 = utc(2026, 9, 19, 10, 0) // Sat 10:00 UTC = Fri 23:00 in Pacific/Pago_Pago
        val inUtc = engine(alwaysOn = true, days = SATURDAY)
        assertTrue(decide(inUtc, saturdayUtc10) is BlockDecision.Block)
        val inPagoPago = engine(alwaysOn = true, days = SATURDAY, timezone = "Pacific/Pago_Pago")
        assertEquals(BlockDecision.Allow, decide(inPagoPago, saturdayUtc10))
    }

    // --- row display agrees with enforcement (same evaluation, no re-derivation) ---

    @Test
    fun blocksNow_matchesEnforcement_forDayScopedMechanisms() {
        fun row(at: Long, usageMs: Long?) = BlockDecisionEngine.blocksNow(
            isAlwaysOn = false, dailyLimitMs = 2 * HOUR, timeRangeStart = 5 * 60L, timeRangeEnd = 12 * 60L,
            activeDaysOfWeek = WEEKENDS, timezone = "UTC", blockExpiresAt = 0L,
            nowEpochMillis = at, usageTodayMs = usageMs,
        )
        assertEquals(true, row(sat(6), null))
        assertEquals(false, row(sat(13), 2 * HOUR - 1))
        assertEquals(true, row(sat(13), 2 * HOUR))
        assertEquals(false, row(mon(6), 5 * HOUR))
    }

    private companion object {
        const val PKG = "com.instagram.android"
        const val HOUR = 60 * 60_000L
        val WEEKENDS = "0000011"
        val SATURDAY = "0000010"
        val FRIDAY = "0000100"
    }

    /** Instant in UTC; 2026-09-18/19/20/21 are Friday/Saturday/Sunday/Monday. */
    private fun utc(y: Int, month: Int, day: Int, h: Int, min: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, month - 1, day, h, min, 0)
        }.timeInMillis

    private fun fri(h: Int, min: Int = 0) = utc(2026, 9, 18, h, min)
    private fun sat(h: Int, min: Int = 0) = utc(2026, 9, 19, h, min)
    private fun sun(h: Int, min: Int = 0) = utc(2026, 9, 20, h, min)
    private fun mon(h: Int, min: Int = 0) = utc(2026, 9, 21, h, min)
}
