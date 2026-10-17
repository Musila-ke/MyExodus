package com.example.myexodus.blocking

import com.example.myexodus.data.db.entity.RestrictionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Canonicalization + matching per Enhanced Mode spec §8 Step 8, pure JVM. */
class UrlPatternTest {

    // --- canonicalization ---

    @Test
    fun bareHost_noScheme() {
        val s = UrlPattern.parseStructure("betting.example.com")!!
        assertNull(s.scheme)
        assertNull(s.port)
        assertEquals("betting.example.com", s.hostUnicode)
        assertNull(s.pathPrefix)
    }

    @Test
    fun userinfoAndDefaultPort_stripped() {
        val s = UrlPattern.parseStructure("HTTP://User@Example.com:80/Path/Here?q=1#f")!!
        assertEquals("http", s.scheme)
        assertNull(s.port)
        assertEquals("example.com", s.hostUnicode)
        assertEquals("/Path/Here", s.pathPrefix)
    }

    /** The omnibox elides default ports (and the scheme — observed on-device 2026-08-27), so a
     *  scheme-less default port typed as a rule must canon away or the pattern can never match
     *  the address-bar text the scanner reads; non-default ports stay pinned. */
    @Test
    fun schemeLessDefaultPorts_areNormalized_nonDefaultStaysPinned() {
        val port80 = UrlPattern.parseStructure("example.com:80")!!
        assertNull(port80.port)
        val port443 = UrlPattern.parseStructure("example.com:443")!!
        assertNull(port443.port)
        val nonDefault = UrlPattern.parseStructure("example.com:8443")!!
        assertEquals(8443, nonDefault.port)

        val rule80 = UrlPattern.from(rule("example.com:80"))!!
        assertTrue(rule80.matches(candidate("example.com")))
        assertTrue(rule80.matches(candidate("http://example.com")))
        val rule8443 = UrlPattern.from(rule("example.com:8443"))!!
        assertTrue(rule8443.matches(candidate("example.com:8443")))
        assertFalse(rule8443.matches(candidate("example.com")))
    }

    @Test
    fun punycode_keepsBothForms() {
        val s = UrlPattern.parseStructure("https://exämple.com:8443")!!
        assertEquals("https", s.scheme)
        assertEquals(8443, s.port)
        assertEquals("exämple.com", s.hostUnicode)
        assertEquals("xn--exmple-cua.com", s.hostAscii)
    }

    @Test
    fun rawPunycodeInput_alsoRetainsUnicodeForm() {
        val s = UrlPattern.parseStructure("xn--exmple-cua.com")!!
        assertNull(s.scheme)
        assertEquals("exämple.com", s.hostUnicode)
        assertEquals("xn--exmple-cua.com", s.hostAscii)
    }

    @Test
    fun trailingSlashOnPath_trimmed() {
        val s = UrlPattern.parseStructure("https://example.com/forum/")!!
        assertEquals("/forum", s.pathPrefix)
    }

    @Test
    fun nonNavigableSchemes_neverCanonicalized() {
        assertNull(UrlPattern.parseStructure("blob:https://example.com/abc"))
        assertNull(UrlPattern.parseStructure("data:text/plain;base64,xxx"))
        assertNull(UrlPattern.parseStructure("javascript:alert(1)"))
    }

    @Test
    fun unparseableInput_isNull() {
        assertNull(UrlPattern.parseStructure(""))
        assertNull(UrlPattern.parseStructure("   "))
        assertNull(UrlPattern.parseStructure("about:blank"))
        assertNull(UrlPattern.parseStructure("http://"))
    }

    // --- matching ---

    @Test
    fun subdomainSuffixWithDotBoundary() {
        val pattern = UrlPattern.from(rule("example.com"))!!
        assertTrue(pattern.matches(candidate("example.com")))
        assertTrue(pattern.matches(candidate("sub.example.com")))
        assertTrue(pattern.matches(candidate("deep.sub.example.com")))
        assertFalse(pattern.matches(candidate("example.com.evil.com")))
        assertFalse(pattern.matches(candidate("example.org")))
        assertFalse(pattern.matches(candidate("notexample.com")))
    }

    @Test
    fun httpHttpsEquivalentUnlessSchemeSpecified() {
        val noScheme = UrlPattern.from(rule("example.com"))!!
        assertTrue(noScheme.matches(candidate("http://example.com")))
        assertTrue(noScheme.matches(candidate("https://example.com")))

        val https = UrlPattern.from(rule("https://example.com"))!!
        assertTrue(https.matches(candidate("https://example.com")))
        assertFalse(https.matches(candidate("http://example.com")))
    }

    @Test
    fun schemeLessCandidate_matchesSchemePinnedPattern() {
        // The address bar's normal display state omits the scheme (observed on-device
        // 2026-08-27: "example.com" for http://example.com); a scheme-less candidate states
        // no scheme, so it cannot violate the pattern's pin.
        val http = UrlPattern.from(rule("http://example.com"))!!
        assertTrue(http.matches(candidate("example.com")))
        val https = UrlPattern.from(rule("https://example.com"))!!
        assertTrue(https.matches(candidate("example.com")))
        // An explicit candidate scheme is still compared against the pin.
        assertFalse(http.matches(candidate("https://example.com")))
    }

    @Test
    fun explicitPort_isPartOfHostComponent() {
        val withPort = UrlPattern.from(rule("https://example.com:8443"))!!
        assertTrue(withPort.matches(candidate("https://example.com:8443")))
        assertFalse(withPort.matches(candidate("https://example.com:443")))
        assertFalse(withPort.matches(candidate("https://example.com")))
    }

    @Test
    fun pathPrefix_matchesOnlyThatPrefix() {
        val pattern = UrlPattern.from(rule("example.com/forum"))!!
        assertTrue(pattern.matches(candidate("example.com/forum")))
        assertTrue(pattern.matches(candidate("example.com/forum/topic/1")))
        assertFalse(pattern.matches(candidate("example.com")))
        assertFalse(pattern.matches(candidate("example.com/forums")))
    }

    @Test
    fun punycodeFormsMatchAcrossEachOther() {
        val unicodePattern = UrlPattern.from(rule("exämple.com"))!!
        assertTrue(unicodePattern.matches(candidate("xn--exmple-cua.com")))
        val asciiPattern = UrlPattern.from(rule("xn--exmple-cua.com"))!!
        assertTrue(asciiPattern.matches(candidate("exämple.com")))
    }

    private fun rule(blockedPackage: String) = RestrictionRule(
        ruleId = "r-1",
        profileId = "p-1",
        blockedPackage = blockedPackage,
        targetType = "WEBSITE",
        isAlwaysOn = true,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        blockExpiresAt = 0L,
        severityLevel = "HIGH",
    )

    private fun candidate(raw: String): CanonicalUrl =
        UrlPattern.canonicalizeCandidate(raw) ?: error("candidate unparseable: $raw")
}
