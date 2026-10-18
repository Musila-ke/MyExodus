package com.example.myexodus.vpn

import com.example.myexodus.blocking.UrlPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainBlocklistTest {

    /** The fixture rules below never expire (`blockExpiresAt = Long.MAX_VALUE`). */
    private val NOW_MS = Long.MAX_VALUE

    private val blocklist = DomainBlocklist.of("blocked.example.com")

    @Test
    fun exactMatch_isBlocked() {
        assertTrue(blocklist.isBlocked("blocked.example.com", NOW_MS))
    }

    @Test
    fun subdomain_suffixPolicy_blocks() {
        // Plan §8.4 default: blocking example.com also blocks subdomains (dot boundary).
        assertTrue(blocklist.isBlocked("www.blocked.example.com", NOW_MS))
        assertTrue(blocklist.isBlocked("a.b.blocked.example.com", NOW_MS))
    }

    @Test
    fun suffixPolicy_noCrossDomainMatch() {
        assertFalse(blocklist.isBlocked("blocked.example.com.evil.com", NOW_MS))
        assertFalse(blocklist.isBlocked("xblocked.example.com", NOW_MS))
        assertFalse(blocklist.isBlocked("example.com", NOW_MS))
    }

    @Test
    fun caseInsensitive() {
        assertTrue(blocklist.isBlocked("BLOCKED.Example.COM", NOW_MS))
    }

    @Test
    fun trailingDot_normalizedOnBothSides() {
        assertTrue(blocklist.isBlocked("blocked.example.com.", NOW_MS))
        assertTrue(DomainBlocklist.of("blocked.example.com.").isBlocked("blocked.example.com", NOW_MS))
    }

    @Test
    fun idna_punycodePatternMatchesPunycodeQname() {
        // Rule entered as "bücher.example" is stored punycode in UrlPattern.hostAscii;
        // a wire qname is always the ASCII form.
        val puny = "xn--bcher-kva.example"
        val list = DomainBlocklist.fromUrlPatterns(
            listOf(pattern("https://bücher.example")),
        )
        assertTrue(list.isBlocked(puny, NOW_MS))
        assertTrue(list.isBlocked("www.$puny", NOW_MS))
    }

    @Test
    fun pathPinnedRule_skipped_noOverBlock() {
        // DNS-layer blocking cannot express paths; a path-pinned rule must not block the host.
        val list = DomainBlocklist.fromUrlPatterns(
            listOf(pattern("https://example.com/forum")),
        )
        assertEquals(0, list.size)
        assertFalse(list.isBlocked("example.com", NOW_MS))
    }

    @Test
    fun ipLiteralRule_skipped() {
        val list = DomainBlocklist.fromUrlPatterns(
            listOf(pattern("http://[::1]:8080")),
        )
        assertEquals(0, list.size)
    }

    @Test
    fun hostLevelRule_included() {
        val list = DomainBlocklist.fromUrlPatterns(
            listOf(pattern("https://example.com"), pattern("https://example.org/path")),
        )
        assertEquals(1, list.size)
        assertTrue(list.isBlocked("sub.example.com", NOW_MS))
    }

    @Test
    fun emptyList_blocksNothing() {
        assertFalse(DomainBlocklist.EMPTY.isBlocked("anything.example", NOW_MS))
    }

    @Test
    fun limitOnlyRule_excluded_meteredNotBlocked() {
        // Plan §1/FR-7: a rule whose only mechanism is a daily limit is metered — the usage
        // shaper answers it (NXDOMAIN only once exhausted). Blocking it here would stop the
        // site before it could ever load, so no usage could accrue (device-caught 2026-09-21).
        val list = DomainBlocklist.fromUrlPatterns(
            listOf(pattern("https://example.com", isAlwaysOn = false, dailyLimitMs = 1_800_000)),
        )
        assertEquals(0, list.size)
        assertFalse(list.isBlocked("example.com", NOW_MS))
        assertFalse(list.isBlocked("www.example.com", NOW_MS))
    }

    @Test
    fun limitPlusWindowRule_excluded_shaperOwnsIt() {
        val list = DomainBlocklist.fromUrlPatterns(
            listOf(
                pattern(
                    "https://example.com",
                    isAlwaysOn = false,
                    dailyLimitMs = 1_800_000,
                    timeStart = 360, timeEnd = 1020,
                ),
            ),
        )
        assertEquals(0, list.size)
    }

    @Test
    fun alwaysOnPlusLimitRule_staysBlocked() {
        // Always-on is a state-independent mechanism: it blocks unconditionally, limit or not.
        val list = DomainBlocklist.fromUrlPatterns(
            listOf(pattern("https://example.com", isAlwaysOn = true, dailyLimitMs = 1_800_000)),
        )
        assertEquals(1, list.size)
        assertTrue(list.isBlocked("example.com", NOW_MS))
    }

    @Test
    fun expiredRule_stopsBlockingWithoutARebuild_auditA02() {
        // The snapshot still carries the rule; only the decision-time clock knows it lapsed.
        val expiry = 1_000_000L
        val list = DomainBlocklist.fromUrlPatterns(
            listOf(pattern("https://example.com", blockExpiresAt = expiry)),
        )
        assertEquals(1, list.size)
        assertTrue("at the expiry instant the rule is still live", list.isBlocked("example.com", expiry))
        assertTrue(list.isBlocked("www.example.com", expiry - 1))
        assertFalse("one millisecond later it must answer normally", list.isBlocked("example.com", expiry + 1))
        assertFalse(list.isBlocked("www.example.com", expiry + 1))
    }

    @Test
    fun zeroExpiry_neverExpires() {
        val list = DomainBlocklist.fromUrlPatterns(listOf(pattern("https://example.com", blockExpiresAt = 0L)))
        assertTrue(list.isBlocked("example.com", Long.MAX_VALUE))
    }

    @Test
    fun windowOnlyRule_stillBlocked_documentedCeiling() {
        // Pre-existing ceiling (class doc): the DNS layer has no clock, so a window-only rule
        // keeps its V1 all-day block here; the Accessibility engine enforces the window.
        val list = DomainBlocklist.fromUrlPatterns(
            listOf(pattern("https://example.com", isAlwaysOn = false, timeStart = 360, timeEnd = 1020)),
        )
        assertEquals(1, list.size)
        assertTrue(list.isBlocked("example.com", NOW_MS))
    }

    private fun pattern(
        raw: String,
        isAlwaysOn: Boolean = true,
        dailyLimitMs: Long = 0,
        timeStart: Long? = null,
        timeEnd: Long? = null,
        blockExpiresAt: Long = Long.MAX_VALUE,
    ): UrlPattern {
        val rule = com.example.myexodus.data.db.entity.RestrictionRule(
            ruleId = "r-" + raw.hashCode(),
            profileId = "p",
            blockedPackage = raw,
            targetType = "WEBSITE",
            isAlwaysOn = isAlwaysOn,
            dailyLimitMs = dailyLimitMs,
            timeRangeStart = timeStart,
            timeRangeEnd = timeEnd,
            activeDaysOfWeek = "1111111",
            blockExpiresAt = blockExpiresAt,
            severityLevel = "MEDIUM",
        )
        return UrlPattern.from(rule)!!
    }
}
