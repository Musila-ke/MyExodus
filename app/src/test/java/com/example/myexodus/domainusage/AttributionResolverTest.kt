package com.example.myexodus.domainusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-9 (§12.1): SNI authority, the §4.6 DNS-fallback agreement rule (D-11, INV-8) and TTL expiry.
 * The `(rule, revision)` binding legs live in `InspectingTcpTapTest` — they are the tap's
 * lifetime, not the resolver's.
 */
class AttributionResolverTest {

    private val counters = EngineCounters()
    private val clock = FakeMonotonicClock()
    private val observations = DnsObservationStore(clock)
    private val resolver = AttributionResolver(observations, counters)

    private val ip = DnsFixtures.ip(93, 184, 216, 34)

    private val index = RuleIndex.build(
        listOf(
            rule("r1", "limited.example"),
            rule("r2", "other.example"),
        ),
        counters,
    )

    private fun rule(id: String, pattern: String, revision: Long = 0L) =
        DomainRule(id, revision, pattern, 3_600_000L, enforcedToday = true)

    private fun hit(ip: ByteArray = this.ip): DomainRule? =
        (resolver.byDns(ip, index) as? AttributionResolver.Dns.Hit)?.rule

    // --- SNI (FR-3a) ---

    @Test
    fun aMatchingSniReturnsItsRule() {
        assertEquals("r1", resolver.bySni("www.limited.example", index)?.id)
        assertEquals("r1", resolver.bySni("limited.example", index)?.id)
    }

    @Test
    fun aValidButUnmatchedSniIsNullAndNeverFallsBackToDns() {
        observations.record(ip, "limited.example", 300)
        assertNull(resolver.bySni("unmatched.example", index))
        assertTrue("the DNS path would have attributed this address", hit() != null)
    }

    // --- DNS fallback (§4.6) ---

    @Test
    fun noObservedNamesIsNone() {
        assertSame(AttributionResolver.Dns.None, resolver.byDns(ip, index))
        assertEquals(0L, counters.get(Counter.dns_fallback_attributed))
    }

    @Test
    fun aSingleCandidateMatchingARuleIsAttributed() {
        observations.record(ip, "limited.example", 300)
        assertEquals("r1", hit()?.id)
        assertEquals(1L, counters.get(Counter.dns_fallback_attributed))
    }

    @Test
    fun twoNamesUnderTheSameRuleAreAttributed() {
        observations.record(ip, "limited.example", 300)
        observations.record(ip, "www.limited.example", 300)
        assertEquals("r1", hit()?.id)
        assertEquals(0L, counters.get(Counter.dns_fallback_ambiguous))
    }

    @Test
    fun twoNamesMatchingDifferentRulesAreAmbiguous() {
        observations.record(ip, "limited.example", 300)
        observations.record(ip, "other.example", 300)
        assertSame(AttributionResolver.Dns.Ambiguous, resolver.byDns(ip, index))
        assertEquals(1L, counters.get(Counter.dns_fallback_ambiguous))
    }

    @Test
    fun aRuleNameSharingTheAddressWithAnUnlimitedNameIsAmbiguous() {
        observations.record(ip, "limited.example", 300)
        observations.record(ip, "unmatched.example", 300)
        assertSame(AttributionResolver.Dns.Ambiguous, resolver.byDns(ip, index))

        // …and in the other arrival order: "no opinion" is a disagreement either way (D-11).
        val reversed = DnsObservationStore(clock)
        reversed.record(ip, "unmatched.example", 300)
        reversed.record(ip, "limited.example", 300)
        assertSame(
            AttributionResolver.Dns.Ambiguous,
            AttributionResolver(reversed, counters).byDns(ip, index),
        )
    }

    @Test
    fun namesMatchingNoRuleAtAllAreNoneNotAmbiguous() {
        observations.record(ip, "unmatched.example", 300)
        observations.record(ip, "also-unmatched.example", 300)
        assertSame(AttributionResolver.Dns.None, resolver.byDns(ip, index))
        assertEquals(0L, counters.get(Counter.dns_fallback_ambiguous))
    }

    @Test
    fun expiredCandidatesAreIgnored() {
        observations.record(ip, "limited.example", 10)
        clock.now = 10_000
        assertSame(AttributionResolver.Dns.None, resolver.byDns(ip, index))
    }

    @Test
    fun candidatesWrittenInIpv6MappedFormStillMatch() {
        val mapped = ByteArray(16).also { it[10] = -1; it[11] = -1; ip.copyInto(it, 12) }
        observations.record(mapped, "limited.example", 300)
        assertEquals("r1", hit()?.id)
    }
}
