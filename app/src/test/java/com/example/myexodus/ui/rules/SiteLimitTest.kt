package com.example.myexodus.ui.rules

import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.domainusage.PublicSuffixList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S9's save-chain logic: the website daily-limit normalize/uniqueness chain (§8 S9.1, S2.8),
 * the stored-limit clamp (R-LIMIT-BOUNDS) and the Rules-screen status-banner gate (S9.4, user
 * ruling 2026-09-20). The composable around these functions is a thin string mapping — the
 * decisions live here so they can be asserted without a device.
 */
class SiteLimitTest {

    private val psl = PublicSuffixList.parse(listOf("com", "ke", "co.ke", "*.ck", "!www.ck"))

    @Test
    fun saveChain_acceptsAndNormalizesAPlainHost() {
        assertEquals(SiteLimitCheck.Ok("example.com"), siteLimitTarget("Example.COM", psl, emptySet()))
        assertEquals(SiteLimitCheck.Ok("example.com"), siteLimitTarget("  example.com.  ", psl, emptySet()))
        // Scheme and port are not part of a rule pattern; both strip cleanly.
        assertEquals(SiteLimitCheck.Ok("example.com"), siteLimitTarget("https://example.com:8443", psl, emptySet()))
    }

    @Test
    fun saveChain_pathInputOffersTheHostToUse() {
        // The "Use it" suggestion must already be a valid pattern — S2.1 validates it through
        // the same pipeline, so the editor never offers a value the next save would reject.
        assertEquals(
            SiteLimitCheck.HasPath("example.com"),
            siteLimitTarget("https://example.com/watch?v=1", psl, emptySet()),
        )
        assertEquals(
            SiteLimitCheck.HasPath("example.com"),
            siteLimitTarget("example.com/forum", psl, emptySet()),
        )
    }

    @Test
    fun saveChain_matchesThePlanErrorTable_caseForCase() {
        assertEquals(SiteLimitCheck.Invalid, siteLimitTarget("", psl, emptySet()))
        assertEquals(SiteLimitCheck.Invalid, siteLimitTarget("my site.com", psl, emptySet()))
        assertEquals(SiteLimitCheck.IpLiteral, siteLimitTarget("192.168.0.1", psl, emptySet()))
        assertEquals(SiteLimitCheck.IpLiteral, siteLimitTarget("2001:db8::1", psl, emptySet()))
        assertEquals(SiteLimitCheck.TooBroad, siteLimitTarget("com", psl, emptySet()))
        assertEquals(SiteLimitCheck.TooBroad, siteLimitTarget("example", psl, emptySet()))
        // PSL coverage: an exact rule ("co.ke") and a wildcard rule ("*.ck") are both too
        // broad, while the exception rule "!www.ck" makes that one host a legal target.
        assertEquals(SiteLimitCheck.TooBroad, siteLimitTarget("co.ke", psl, emptySet()))
        assertEquals(SiteLimitCheck.TooBroad, siteLimitTarget("foo.ck", psl, emptySet()))
        assertEquals(SiteLimitCheck.Ok("www.ck"), siteLimitTarget("www.ck", psl, emptySet()))
        assertEquals(SiteLimitCheck.Ok("example.co.ke"), siteLimitTarget("example.co.ke", psl, emptySet()))
        assertEquals(SiteLimitCheck.NeverBlock, siteLimitTarget("connectivitycheck.gstatic.com", psl, emptySet()))
        assertEquals(SiteLimitCheck.EncryptedDnsResolver, siteLimitTarget("dns.google", psl, emptySet()))
    }

    @Test
    fun saveChain_duplicateIsAnExactPatternMatch_subdomainsStayLegal() {
        val taken = setOf("example.com")
        assertEquals(SiteLimitCheck.DuplicatePattern, siteLimitTarget("example.com", psl, taken))
        // The uniqueness check compares normalized patterns, so a differently-typed spelling
        // of the limited site is still the same site.
        assertEquals(SiteLimitCheck.DuplicatePattern, siteLimitTarget("HTTPS://Example.com/", psl, taken))
        // S2.8: subdomain overlap is not a collision — longest-pattern-wins resolves it at
        // match time, so an independently limited subdomain is allowed.
        assertEquals(SiteLimitCheck.Ok("sub.example.com"), siteLimitTarget("sub.example.com", psl, taken))
    }

    @Test
    fun collisionSet_otherLimitedSitesIncludingPaused_skipsUnlimitedAndApps() {
        val rules = listOf(
            website("self", "example.com", dailyLimitMs = 1_800_000L),
            website("other", "https://Example.com/topic", dailyLimitMs = 600_000L),
            // Paused on purpose: re-enabling it later would collide in RuleIndex, whose dedup
            // silently drops one side — rejecting the save now is the survivable failure.
            website("paused", "bet.com", dailyLimitMs = 600_000L, enabled = false),
            website("unlimited", "news.com", dailyLimitMs = 0L),
            appRule("app", dailyLimitMs = 600_000L),
        )
        assertEquals(
            setOf("example.com", "bet.com"),
            siteLimitPatternsOf(rules, psl, excludeRuleId = "self"),
        )
    }

    @Test
    fun collisionSet_skipsStoredValuesTheNormalizerRejects() {
        val rules = listOf(website("bad", "not a host", dailyLimitMs = 600_000L))
        assertEquals(emptySet<String>(), siteLimitPatternsOf(rules, psl, excludeRuleId = null))
    }

    @Test
    fun clamp_keepsZeroAtZero_andBoundsTheRest() {
        assertEquals(0L, clampStoredLimitMs(0L))
        assertEquals("A sign-damaged value must never switch a limit on", 0L, clampStoredLimitMs(-1L))
        assertEquals(60_000L, clampStoredLimitMs(30_000L))
        assertEquals(86_400_000L, clampStoredLimitMs(200_000_000L))
        assertEquals(3_600_000L, clampStoredLimitMs(3_600_000L))
    }

    @Test
    fun bannerGate_needsOneLiveLimitedSite() {
        val now = 1_700_000_000_000L
        assertTrue(hasLimitedSiteRule(listOf(website("a", "example.com", dailyLimitMs = 600_000L)), now))
        assertFalse(
            "Paused limited rule: nothing is metered, so the banner would be a false alarm",
            hasLimitedSiteRule(listOf(website("a", "example.com", dailyLimitMs = 600_000L, enabled = false)), now),
        )
        assertFalse(
            "Expired limited rule",
            hasLimitedSiteRule(listOf(website("a", "example.com", dailyLimitMs = 600_000L, expiresAt = now - 1)), now),
        )
        assertFalse(
            "Website rule without a limit",
            hasLimitedSiteRule(listOf(website("a", "example.com", dailyLimitMs = 0L)), now),
        )
        assertFalse(
            "An app limit belongs to the other engine — it is not a metered site",
            hasLimitedSiteRule(listOf(appRule("a", dailyLimitMs = 600_000L)), now),
        )
    }

    @Test
    fun tunnelGate_needsOneLiveWebsiteRule_blockOnlyCounts() {
        val now = 1_700_000_000_000L
        // #26: a block-only rule ("Always on", no limit) rides the same tunnel — it must
        // start it exactly like a limited one does.
        assertTrue(hasLiveWebsiteRule(listOf(website("a", "example.com")), now))
        assertTrue(hasLiveWebsiteRule(listOf(website("a", "example.com", dailyLimitMs = 600_000L)), now))
        assertFalse(
            "Paused website rule is not published",
            hasLiveWebsiteRule(listOf(website("a", "example.com", enabled = false)), now),
        )
        assertFalse(
            "Expired website rule is not published",
            hasLiveWebsiteRule(listOf(website("a", "example.com", expiresAt = now - 1)), now),
        )
        assertFalse(
            "App rules are the other engine's concern",
            hasLiveWebsiteRule(listOf(appRule("a", dailyLimitMs = 600_000L)), now),
        )
        assertFalse("No rules at all", hasLiveWebsiteRule(emptyList(), now))
    }

    private fun website(
        id: String,
        host: String,
        dailyLimitMs: Long = 0L,
        enabled: Boolean = true,
        expiresAt: Long = 0L,
    ) = RestrictionRule(
        ruleId = id,
        profileId = "p-1",
        blockedPackage = host,
        targetType = "WEBSITE",
        isAlwaysOn = false,
        dailyLimitMs = dailyLimitMs,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        blockExpiresAt = expiresAt,
        severityLevel = "HIGH",
        isEnabled = enabled,
    )

    private fun appRule(id: String, dailyLimitMs: Long) = RestrictionRule(
        ruleId = id,
        profileId = "p-1",
        blockedPackage = "com.instagram.android",
        targetType = "APP",
        isAlwaysOn = false,
        dailyLimitMs = dailyLimitMs,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        blockExpiresAt = 0L,
        severityLevel = "HIGH",
    )
}
