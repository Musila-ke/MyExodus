package com.example.myexodus.blocking

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stress slice A5 (backlog `project_stress_test_backlog`): the rule input domain at its
 * edges — blank, zero, short, over-max — crossed with the full mechanism product, per the
 * owner's scenario-matrix ruling. `BlockModeMatrixTest` already pins every *logical*
 * combination (A/W/Q × day scope × expiry × pause) with valid values; this file adds what
 * it does not have:
 *
 *  1. domain probes per input: malformed day strings, degenerate/over-max/negative windows,
 *     Long-range quota arithmetic, exact expiry boundaries, invalid timezones;
 *  2. a full cross-product sweep asserting the two public surfaces — `decideApp` and
 *     `blocksNow` (dashboard row) — agree on every combination (both delegate to one
 *     `evaluateRule`; a future edit that forks them fails here first) and that no
 *     combination throws.
 *
 * Semantics for out-of-domain rows are pinned as-is, not idealized: hand-edited/harness
 * rows exist in this project's history (ADR #141's double-active-profile residue), and the
 * engine must stay deterministic and total on them. Instants are fixed UTC (2026-09-16 Wed
 * .. 21 Mon) so day scope is explicit.
 */
class BlockDecisionEngineDomainStressTest {

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

    private fun decide(engine: BlockDecisionEngine, at: Long, usageMs: Long? = null) =
        engine.decideApp(PKG, at, usageMs)

    private fun blocks(engine: BlockDecisionEngine, at: Long, usageMs: Long? = null) =
        decide(engine, at, usageMs) is BlockDecision.Block

    // ---- day-string domain ----

    @Test
    fun dayStringDomain_blank_short_overLength_invalidChar() {
        // Blank and short strings fail the getOrNull read → match no day → the whole rule is inert.
        assertEquals(BlockDecision.Allow, decide(engine(alwaysOn = true, days = ""), sat(12)))
        assertEquals(BlockDecision.Allow, decide(engine(alwaysOn = true, days = "111"), fri(12)))
        assertEquals(BlockDecision.Allow, decide(engine(alwaysOn = true, days = "0"), mon(12)))
        // …but the covered prefix still works: "111" is Mon-Wed.
        assertTrue(blocks(engine(alwaysOn = true, days = "111"), mon(12)))
        assertTrue(blocks(engine(alwaysOn = true, days = "111"), wed(12)))
        // Over-max length: extra chars are never read → identical to EVERY_DAY.
        assertTrue(blocks(engine(alwaysOn = true, days = "11111111"), sun(12)))
        // An invalid char poisons exactly its weekday (Wednesday = index 2), not the string.
        val poisoned = engine(alwaysOn = true, days = "11x1111")
        assertEquals(BlockDecision.Allow, decide(poisoned, wed(12)))
        assertTrue(blocks(poisoned, thu(12)))
        assertTrue(blocks(poisoned, sun(12)))
        // The day scope also voids the window leg — windowDayActive reads the same bits.
        assertEquals(BlockDecision.Allow, decide(engine(window = 300L to 720L, days = ""), sat(6)))
    }

    // ---- window domain ----

    @Test
    fun windowDomain_degenerateEmptyFullDay_andOverMax() {
        // start == end → the half-open set is empty: the window leg never fires.
        assertEquals(BlockDecision.Allow, decide(engine(window = 600L to 600L), sat(10)))
        assertTrue("rest of the rule still evaluates", blocks(engine(alwaysOn = true, window = 600L to 600L), sat(10)))
        assertEquals(BlockDecision.Allow, decide(engine(window = 0L to 0L), sat(10)))
        // 0..1440 covers every minute-of-day: midnight through 23:59.
        val full = engine(window = 0L to 1440L)
        assertTrue(blocks(full, sat(0, 0)))
        assertTrue(blocks(full, sat(23, 59)))
        // Over-max end (1500 > any real minute): blocks from start through midnight.
        val overEnd = engine(window = 600L to 1500L)
        assertTrue(blocks(overEnd, sat(23, 59)))
        assertEquals(BlockDecision.Allow, decide(overEnd, sat(5, 59)))
        // Both ends over-max → no minute can satisfy → inert, like an unset window.
        assertEquals(BlockDecision.Allow, decide(engine(window = 1500L to 1600L), sat(12)))
        // Half-null windows (one end blank): the engine requires both ends.
        assertEquals(BlockDecision.Allow, decide(engine(window = 600L to null), sat(10)))
        assertEquals(BlockDecision.Allow, decide(engine(window = null to 600L), sat(10)))
        // Below-min start behaves as minute 0 (nowMin is never negative): [00:00, end).
        assertTrue(blocks(engine(window = -60L to 600L), sat(0, 0)))
        assertTrue(blocks(engine(window = -60L to 600L), sat(9, 59)))
        assertEquals(BlockDecision.Allow, decide(engine(window = -60L to 600L), sat(10, 0)))
    }

    // ---- quota domain ----

    @Test
    fun quotaDomain_zeroNegative_andLongRangeArithmetic() {
        // Limit 0 or below = "no quota" (guard is dailyLimitMs > 0): usage magnitude is irrelevant.
        assertEquals(BlockDecision.Allow, decide(engine(limitMs = 0L), sat(12), Long.MAX_VALUE))
        assertEquals(BlockDecision.Allow, decide(engine(limitMs = -1L), sat(12), Long.MAX_VALUE))
        assertEquals(BlockDecision.Allow, decide(engine(limitMs = Long.MIN_VALUE), sat(12), Long.MAX_VALUE))
        // At the very top of the Long range the >= boundary still holds exactly.
        assertTrue(blocks(engine(limitMs = Long.MAX_VALUE), sat(12), Long.MAX_VALUE))
        assertEquals(BlockDecision.Allow, decide(engine(limitMs = Long.MAX_VALUE), sat(12), Long.MAX_VALUE - 1))
        // Exact boundary at ordinary values: >= blocks, one below allows.
        assertEquals(BlockDecision.Allow, decide(engine(limitMs = 1L), sat(12), 0L))
        assertTrue(blocks(engine(limitMs = 1L), sat(12), 1L))
        // Negative usage can never reach a positive limit.
        assertEquals(BlockDecision.Allow, decide(engine(limitMs = 600_000L), sat(12), -5L))
        // Null (not supplied — the UI row path) vs supplied-zero are distinct inputs, both under.
        assertEquals(BlockDecision.Allow, decide(engine(limitMs = 600_000L), sat(12), null))
        assertEquals(BlockDecision.Allow, decide(engine(limitMs = 600_000L), sat(12), 0L))
        // Quota is inert outside the day scope even at Long-range usage.
        assertEquals(BlockDecision.Allow, decide(engine(limitMs = 1L, days = SATURDAY), mon(12), Long.MAX_VALUE))
    }

    // ---- expiry domain ----

    @Test
    fun expiryDomain_exactBoundary_neverAndNegative() {
        val at = sat(12)
        // Strictly-after comparison: at the expiry instant the rule still blocks; +1 ms lifts it.
        assertTrue(blocks(engine(alwaysOn = true, expiresAt = at), at))
        assertEquals(BlockDecision.Allow, decide(engine(alwaysOn = true, expiresAt = at), at + 1))
        assertTrue(blocks(engine(alwaysOn = true, expiresAt = at + 1), at))
        // 0 and every negative read as "never expires" (the guard is blockExpiresAt > 0).
        assertTrue(blocks(engine(alwaysOn = true, expiresAt = 0L), at))
        assertTrue(blocks(engine(alwaysOn = true, expiresAt = -1L), at))
        assertTrue(blocks(engine(alwaysOn = true, expiresAt = Long.MIN_VALUE), at))
        // MAX_VALUE is unreachable by `now` → behaves as never-expires too.
        assertTrue(blocks(engine(alwaysOn = true, expiresAt = Long.MAX_VALUE), at))
        // The boundary lifts the window and quota legs identically (expiry is checked
        // first). 05:00-13:00 so the test instant is INSIDE the window — the only thing
        // separating the two assertions is the 1 ms crossing the expiry instant.
        assertTrue(blocks(engine(window = 300L to 780L, expiresAt = at), at))
        assertEquals(BlockDecision.Allow, decide(engine(window = 300L to 780L, expiresAt = at), at + 1))
        assertTrue(blocks(engine(limitMs = 1L, expiresAt = at), at, 5L))
        assertEquals(BlockDecision.Allow, decide(engine(limitMs = 1L, expiresAt = at), at + 1, 5L))
    }

    // ---- timezone domain ----

    @Test
    fun timezoneDomain_invalidAndBlank_fallBackToGmt_deterministically() {
        // TimeZone.getTimeZone returns GMT for unrecognized ids — never the device zone
        // (ruleZoneId's device fallback is the accounting path, not this engine).
        val viaUtc = engine(alwaysOn = true, days = SATURDAY)
        val viaBlank = engine(alwaysOn = true, days = SATURDAY, timezone = "")
        val viaInvalid = engine(alwaysOn = true, days = SATURDAY, timezone = "Not/AZone")
        for (at in listOf(sat(0, 0), sat(12), sun(0, 0), mon(12))) {
            assertEquals("blank tz must behave as GMT=UTC", decide(viaUtc, at), decide(viaBlank, at))
            assertEquals("invalid tz must behave as GMT=UTC", decide(viaUtc, at), decide(viaInvalid, at))
        }
        // A valid +3 zone genuinely shifts the judgement: Fri 22:00 UTC is already Sat 01:00 EAT.
        assertTrue(blocks(engine(alwaysOn = true, days = SATURDAY, timezone = "Africa/Nairobi"), fri(22)))
        assertEquals(BlockDecision.Allow, decide(viaUtc, fri(22)))
    }

    // ---- full product: both surfaces agree on every combination ----

    @Test
    fun fullDomainSweep_decideAppAndBlocksNowAgree_andNoCombinationThrows() {
        val daysDomain = listOf("1111111", "0000000", "", "111", "11111111", "11x1111")
        val windowDomain = listOf<Pair<Long?, Long?>?>(
            null,
            300L to 720L,
            1380L to 420L,
            600L to 600L,
            0L to 1440L,
            600L to 1500L,
            1500L to 1600L,
            600L to null,
            -60L to 600L,
        )
        val limitDomain = listOf(0L, -1L, 1L, 600_000L, Long.MAX_VALUE)
        val usageDomain = listOf<Long?>(null, -5L, 0L, 599_999L, 600_000L, Long.MAX_VALUE)
        val expiryDomain = listOf(0L, SAT_NOON - 1, SAT_NOON, SAT_NOON + 1, Long.MAX_VALUE)
        val zoneDomain = listOf("UTC", "Africa/Nairobi", "Not/AZone")
        val instants = listOf(sat(0, 0), sat(6, 0), sat(10, 30), sun(3, 0), mon(12, 0))

        var combos = 0
        var blocked = 0
        for (alwaysOn in listOf(false, true))
            for (days in daysDomain)
                for (window in windowDomain)
                    for (limit in limitDomain)
                        for (usage in usageDomain)
                            for (expiry in expiryDomain)
                                for (zone in zoneDomain)
                                    for (at in instants) {
                                        val e = engine(alwaysOn, limit, window, days, expiry, zone)
                                        val app = decide(e, at, usage) is BlockDecision.Block
                                        val row = BlockDecisionEngine.blocksNow(
                                            isAlwaysOn = alwaysOn,
                                            dailyLimitMs = limit,
                                            timeRangeStart = window?.first,
                                            timeRangeEnd = window?.second,
                                            activeDaysOfWeek = days,
                                            timezone = zone,
                                            blockExpiresAt = expiry,
                                            nowEpochMillis = at,
                                            usageTodayMs = usage,
                                        )
                                        assertEquals(
                                            "app vs row disagree: alwaysOn=$alwaysOn days=$days window=$window " +
                                                "limit=$limit usage=$usage expiry=$expiry zone=$zone at=$at",
                                            app,
                                            row,
                                        )
                                        if (app) blocked++
                                        combos++
                                    }
        // The domain must actually exercise both outcomes — an everything-allows sweep is a dead test.
        assertTrue("sweep produced no Block outcome", blocked > 0)
        assertTrue("sweep produced no Allow outcome", blocked < combos)
        System.err.println("A5 sweep: $combos combinations, $blocked block")
    }

    private companion object {
        const val PKG = "com.instagram.android"
        val SATURDAY = "0000010"
        val SAT_NOON = utc(2026, 9, 19, 12, 0)

        /** Instant in UTC; 2026-09-16/17/18/19/20/21 are Wed/Thu/Fri/Sat/Sun/Mon. */
        fun utc(y: Int, month: Int, day: Int, h: Int, min: Int): Long =
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(y, month - 1, day, h, min, 0)
            }.timeInMillis
    }

    private fun wed(h: Int, min: Int = 0) = utc(2026, 9, 16, h, min)
    private fun thu(h: Int, min: Int = 0) = utc(2026, 9, 17, h, min)
    private fun fri(h: Int, min: Int = 0) = utc(2026, 9, 18, h, min)
    private fun sat(h: Int, min: Int = 0) = utc(2026, 9, 19, h, min)
    private fun sun(h: Int, min: Int = 0) = utc(2026, 9, 20, h, min)
    private fun mon(h: Int, min: Int = 0) = utc(2026, 9, 21, h, min)
}
