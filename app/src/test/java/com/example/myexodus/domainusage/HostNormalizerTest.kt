package com.example.myexodus.domainusage

import org.junit.Assert.assertEquals
import org.junit.Test

/** U-1: normalization and validation of rule input and stored rule values. */
class HostNormalizerTest {

    private val psl = PublicSuffixList.parse(listOf("com", "ke", "co.ke", "*.ck", "!www.ck"))

    private fun normalize(input: String) = HostNormalizer.normalize(input, psl)

    private fun legacy(input: String) = HostNormalizer.normalizeLegacy(input, psl)

    @Test
    fun stripsSchemeUserinfoPortAndUppercase() {
        assertEquals(
            HostNormalizer.Result.Ok("example.com"),
            normalize("https://user:pass@Example.COM:8443"),
        )
    }

    @Test
    fun pathIsSuggestedNotRejected() {
        assertEquals(HostNormalizer.Result.HasPath("example.com"), normalize("example.com/forum"))
    }

    @Test
    fun rootPathAndBareQueryBehaviour() {
        assertEquals(HostNormalizer.Result.Ok("example.com"), normalize("example.com/"))
        assertEquals(HostNormalizer.Result.HasPath("example.com"), normalize("example.com?q=1"))
        assertEquals(HostNormalizer.Result.HasPath("example.com"), normalize("example.com#frag"))
    }

    @Test
    fun trailingDotAndUppercaseNormalized() {
        assertEquals(HostNormalizer.Result.Ok("example.com"), normalize("EXAMPLE.COM."))
    }

    @Test
    fun idnConvertedToAscii() {
        assertEquals(HostNormalizer.Result.Ok("xn--bcher-kva.de"), normalize("Bücher.de"))
    }

    @Test
    fun ipLiteralsRejected() {
        assertEquals(HostNormalizer.Result.IpLiteral, normalize("192.168.1.1"))
        assertEquals(HostNormalizer.Result.IpLiteral, normalize("1.2.3.4:8080"))
        assertEquals(HostNormalizer.Result.IpLiteral, normalize("http://1.2.3.4/admin"))
        assertEquals(HostNormalizer.Result.IpLiteral, normalize("2001:db8::1"))
    }

    @Test
    fun singleLabelTooBroad() {
        assertEquals(HostNormalizer.Result.TooBroad, normalize("localhost"))
    }

    @Test
    fun publicSuffixRejectedButItsSubdomainAllowed() {
        assertEquals(HostNormalizer.Result.TooBroad, normalize("co.ke"))
        assertEquals(HostNormalizer.Result.Ok("example.co.ke"), normalize("example.co.ke"))
    }

    @Test
    fun neverBlockHostsAndSubdomainsRejected() {
        assertEquals(HostNormalizer.Result.NeverBlock, normalize("connectivitycheck.gstatic.com"))
        assertEquals(HostNormalizer.Result.NeverBlock, normalize("sub.connectivitycheck.android.com"))
        assertEquals(HostNormalizer.Result.NeverBlock, normalize("samhsa.gov"))
        assertEquals(HostNormalizer.Result.NeverBlock, normalize("pages.samhsa.gov"))
    }

    @Test
    fun resolverHostsRejected() {
        assertEquals(HostNormalizer.Result.EncryptedDnsResolver, normalize("dns.google"))
        assertEquals(HostNormalizer.Result.EncryptedDnsResolver, normalize("dns.quad9.net"))
        assertEquals(HostNormalizer.Result.EncryptedDnsResolver, normalize("mozilla.cloudflare-dns.com"))
    }

    @Test
    fun lengthLimitsEnforced() {
        assertEquals(HostNormalizer.Result.Invalid, normalize("a".repeat(64) + ".com"))
        val total = List(4) { "a".repeat(63) }.joinToString(".")
        assertEquals(HostNormalizer.Result.Invalid, normalize(total))
    }

    @Test
    fun invalidLabelsRejected() {
        assertEquals(HostNormalizer.Result.Invalid, normalize("foo_bar.com"))
        assertEquals(HostNormalizer.Result.Invalid, normalize("-example.com"))
        assertEquals(HostNormalizer.Result.Invalid, normalize("exa mple.com"))
        assertEquals(HostNormalizer.Result.Invalid, normalize(""))
        assertEquals(HostNormalizer.Result.Invalid, normalize("https://"))
    }

    @Test
    fun legacyDiscardsPathQueryAndFragment() {
        assertEquals(HostNormalizer.Result.Ok("example.com"), legacy("example.com/forum"))
        assertEquals(
            HostNormalizer.Result.Ok("example.com"),
            legacy("https://Example.com:8443/path?q=1#f"),
        )
    }

    @Test
    fun legacyAppliesTheSameChecks() {
        assertEquals(HostNormalizer.Result.IpLiteral, legacy("192.168.1.1/admin"))
        assertEquals(HostNormalizer.Result.TooBroad, legacy("localhost/forum"))
        assertEquals(HostNormalizer.Result.NeverBlock, legacy("samhsa.gov/help"))
    }
}
