package com.example.myexodus.blocking

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pipeline units (plan §8 Step 4) — pure JVM. */
class BlockingPipelineTest {

    // --- AccessibilityEventNormalizer ---

    @Test
    fun normalize_extractsEventFields() {
        val normalizer = AccessibilityEventNormalizer()
        val event = normalizer.normalize(
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.android.chrome",
            7,
        )
        assertEquals("com.android.chrome", event.packageName)
        assertEquals(7, event.windowId)
        assertEquals(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, event.eventType)
        assertNull(normalizer.normalize(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, null, 1).packageName)
    }

    // --- ForegroundStateMachine ---

    @Test
    fun generation_isMonotonicAndCurrent() {
        val fsm = ForegroundStateMachine()
        val g1 = fsm.advance("com.instagram.android", 5, detectionTimeNanos = 100L)
        val g2 = fsm.advance("com.tiktok.android", 6, detectionTimeNanos = 200L)
        assertTrue(g2 > g1)
        assertTrue(fsm.isCurrent(g2))
        assertTrue(!fsm.isCurrent(g1))
        assertEquals("com.tiktok.android", fsm.lastKnownPackage())
        assertEquals(6, fsm.lastKnownWindowId())
    }

    @Test
    fun sameWindowEvent_doesNotAdvanceGeneration() {
        // Chrome's page-load WSC arrives ~3ms after the content-changed events for the same
        // window; advancing on it would discard the pending debounced URL scan (caught
        // on-device 2026-08-27). Only a real transition supersedes.
        val fsm = ForegroundStateMachine()
        val g1 = fsm.advance("com.android.chrome", 1, detectionTimeNanos = 100L)
        val g2 = fsm.advance("com.android.chrome", 1, detectionTimeNanos = 110L)
        assertEquals(g1, g2)
        assertTrue(fsm.isCurrent(g1))
        assertTrue(fsm.isCurrent(g2))
        val g3 = fsm.advance("com.instagram.android", 2, detectionTimeNanos = 200L)
        assertTrue(g3 > g2)
        assertTrue(fsm.isCurrent(g3))
        assertTrue(!fsm.isCurrent(g1))
    }

    // --- BlockDecisionEngine ---

    private val snapshot = RulesSnapshot(
        appRules = mapOf(
            "com.alwayson.android" to Rule("r1", "com.alwayson.android", true, 0L, null, null, "1111111", 0L, "HIGH"),
            "com.window.android" to Rule("r2", "com.window.android", false, 0L, 21 * 60L, 7 * 60L, "1111111", 0L, "CRITICAL"),
            "com.windowdays.android" to Rule("r3", "com.windowdays.android", false, 0L, 9 * 60L, 17 * 60L, "1000000", 0L, "MODERATE"),
            "com.expired.android" to Rule("r4", "com.expired.android", true, 0L, null, null, "1111111", 1_000_000L, "HIGH"),
            "com.quota.android" to Rule("r5", "com.quota.android", false, 90_000L, null, null, "1111111", 0L, "HIGH"),
        ),
        urlRules = listOf(
            pattern("r6", "https://betting.example.com", severity = "CRITICAL"),
            pattern("r7", "https://news.example.com/forum", severity = "LOW"),
        ),
        version = 1L,
    )
    private val engine = BlockDecisionEngine { snapshot }

    private val wednesdayNoon = epochAt(weekday = Calendar.WEDNESDAY, hourOfDay = 12)
    private val wednesday2200 = epochAt(weekday = Calendar.WEDNESDAY, hourOfDay = 22)
    private val tuesday0200 = epochAt(weekday = Calendar.TUESDAY, hourOfDay = 2)
    private val wednesday1000 = epochAt(weekday = Calendar.WEDNESDAY, hourOfDay = 10)

    @Test
    fun unknownPackage_isAllowed() {
        assertEquals(BlockDecision.Allow, engine.decideApp("com.unknown.android", wednesdayNoon))
    }

    @Test
    fun alwaysOn_blocks() {
        assertEquals(BlockDecision.Block("HIGH", false), engine.decideApp("com.alwayson.android", wednesdayNoon))
    }

    @Test
    fun expiredRule_isAllowedEvenWhenAlwaysOn() {
        assertEquals(BlockDecision.Allow, engine.decideApp("com.expired.android", wednesdayNoon))
    }

    @Test
    fun zeroExpiry_blocksEvenInTheDistantFuture() {
        // 0 = never expires: a block must still hold far beyond any plausible wall-clock
        // (was a tautological duplicate of alwaysOn_blocks — same input, same assertion).
        val farFuture = 1_800_000_000_000L // 2027
        assertEquals(BlockDecision.Block("HIGH", false), engine.decideApp("com.alwayson.android", farFuture))
        // Control: a rule with a real expiry is lifted at the same timestamp.
        assertEquals(BlockDecision.Allow, engine.decideApp("com.expired.android", farFuture))
    }

    @Test
    fun timeWindow_blocksInside_andAllowsOutside() {
        assertEquals(BlockDecision.Block("CRITICAL", false), engine.decideApp("com.window.android", wednesday2200))
        assertEquals(BlockDecision.Allow, engine.decideApp("com.window.android", wednesdayNoon))
    }

    @Test
    fun crossingMidnightWindow_blocksAfterMidnight() {
        assertEquals(BlockDecision.Block("CRITICAL", false), engine.decideApp("com.window.android", tuesday0200))
    }

    @Test
    fun dayOfWeek_restrictsWindow() {
        // Mon-only window: Wednesday 10:00 is out of the day set.
        assertEquals(BlockDecision.Allow, engine.decideApp("com.windowdays.android", wednesday1000))
        val monday1000 = epochAt(weekday = Calendar.MONDAY, hourOfDay = 10)
        assertEquals(BlockDecision.Block("MODERATE", false), engine.decideApp("com.windowdays.android", monday1000))
    }

    @Test
    fun usageQuota_blocksOnlyWhenInputProvided() {
        assertEquals(BlockDecision.Allow, engine.decideApp("com.quota.android", wednesdayNoon))
        assertEquals(BlockDecision.Block("HIGH", false), engine.decideApp("com.quota.android", wednesdayNoon, usageTodayMs = 90_000L))
        assertEquals(BlockDecision.Allow, engine.decideApp("com.quota.android", wednesdayNoon, usageTodayMs = 89_999L))
    }

    @Test
    fun urlMatch_blocksWithWebsiteLabel() {
        val candidate = UrlPattern.canonicalizeCandidate("https://betting.example.com/sports")!!
        assertEquals(BlockDecision.Block("CRITICAL", true), engine.decideUrl(candidate, wednesdayNoon))
    }

    @Test
    fun urlSubdomain_matchBlocks() {
        val candidate = UrlPattern.canonicalizeCandidate("https://sub.betting.example.com")!!
        assertEquals(BlockDecision.Block("CRITICAL", true), engine.decideUrl(candidate, wednesdayNoon))
    }

    @Test
    fun urlNoMatch_isAllowed() {
        val candidate = UrlPattern.canonicalizeCandidate("https://example.com")!!
        assertEquals(BlockDecision.Allow, engine.decideUrl(candidate, wednesdayNoon))
    }

    @Test
    fun urlPathPrefix_requiresSegmentBoundary() {
        assertEquals(BlockDecision.Block("LOW", true), engine.decideUrl(
            UrlPattern.canonicalizeCandidate("https://news.example.com/forum/topic/1")!!, wednesdayNoon))
        assertEquals(BlockDecision.Allow, engine.decideUrl(
            UrlPattern.canonicalizeCandidate("https://news.example.com/forums")!!, wednesdayNoon))
    }

    private fun pattern(ruleId: String, raw: String, severity: String): UrlPattern =
        UrlPattern.from(
            com.example.myexodus.data.db.entity.RestrictionRule(
                ruleId = ruleId,
                profileId = "p-1",
                blockedPackage = raw,
                targetType = "WEBSITE",
                isAlwaysOn = true,
                dailyLimitMs = 0L,
                timeRangeStart = null,
                timeRangeEnd = null,
                activeDaysOfWeek = "1111111",
                blockExpiresAt = 0L,
                severityLevel = severity,
            ),
        )!!

    // Fixture rules default to the explicit "UTC" zone (plan §7.1), so fixture instants
    // must be computed in UTC too — never the machine's default zone.
    private fun epochAt(weekday: Int, hourOfDay: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(Calendar.DAY_OF_WEEK, weekday)
        set(Calendar.HOUR_OF_DAY, hourOfDay)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
