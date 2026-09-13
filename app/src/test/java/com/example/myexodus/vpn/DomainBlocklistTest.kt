package com.example.myexodus.vpn

import com.example.myexodus.blocking.UrlPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainBlocklistTest {

    private val blocklist = DomainBlocklist.of("blocked.example.com")

    @Test
    fun exactMatch_isBlocked() {
        assertTrue(blocklist.isBlocked("blocked.example.com"))
    }

    @Test
    fun subdomain_suffixPolicy_blocks() {
        // Plan §8.4 default: blocking example.com also blocks subdomains (dot boundary).
        assertTrue(blocklist.isBlocked("www.blocked.example.com"))
        assertTrue(blocklist.isBlocked("a.b.blocked.example.com"))
    }

    @Test
    fun suffixPolicy_noCrossDomainMatch() {
        assertFalse(blocklist.isBlocked("blocked.example.com.evil.com"))
        assertFalse(blocklist.isBlocked("xblocked.example.com"))
        assertFalse(blocklist.isBlocked("example.com"))
    }

    @Test
    fun caseInsensitive() {
        assertTrue(blocklist.isBlocked("BLOCKED.Example.COM"))
    }

    @Test
    fun trailingDot_normalizedOnBothSides() {
        assertTrue(blocklist.isBlocked("blocked.example.com."))
        assertTrue(DomainBlocklist.of("blocked.example.com.").isBlocked("blocked.example.com"))
    }

    @Test
    fun idna_punycodePatternMatchesPunycodeQname() {
        // Rule entered as "bücher.example" is stored punycode in UrlPattern.hostAscii;
        // a wire qname is always the ASCII form.
        val puny = "xn--bcher-kva.example"
        val list = DomainBlocklist.fromUrlPatterns(
            listOf(pattern("https://bücher.example")),
        )
        assertTrue(list.isBlocked(puny))
        assertTrue(list.isBlocked("www.$puny"))
    }

    @Test
    fun pathPinnedRule_skipped_noOverBlock() {
        // DNS-layer blocking cannot express paths; a path-pinned rule must not block the host.
        val list = DomainBlocklist.fromUrlPatterns(
            listOf(pattern("https://example.com/forum")),
        )
        assertEquals(0, list.size)
        assertFalse(list.isBlocked("example.com"))
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
        assertTrue(list.isBlocked("sub.example.com"))
    }

    @Test
    fun emptyList_blocksNothing() {
        assertFalse(DomainBlocklist.EMPTY.isBlocked("anything.example"))
    }

    private fun pattern(raw: String): UrlPattern {
        val rule = com.example.myexodus.data.db.entity.RestrictionRule(
            ruleId = "r-" + raw.hashCode(),
            profileId = "p",
            blockedPackage = raw,
            targetType = "WEBSITE",
            isAlwaysOn = true,
            dailyLimitMs = 0,
            timeRangeStart = null,
            timeRangeEnd = null,
            activeDaysOfWeek = "1111111",
            blockExpiresAt = Long.MAX_VALUE,
            severityLevel = "MEDIUM",
        )
        return UrlPattern.from(rule)!!
    }
}
