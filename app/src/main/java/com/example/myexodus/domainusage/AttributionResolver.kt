package com.example.myexodus.domainusage

/**
 * §4.6: combines SNI and DNS evidence into at most one rule per flow. An attribution binds to the
 * rule's *current* revision (D-32) — the revision that was current when the flow opened — and never
 * changes afterwards (FR-14).
 *
 * The ambiguity rule is the whole point of the DNS path: a shared-IP address with several observed
 * names may only be attributed when every candidate resolves to the same rule. A candidate that
 * matches no rule is not "no opinion" — it is a disagreement, because the same address is then
 * serving both a limited site and an unlimited one (D-11, INV-8).
 */
class AttributionResolver(
    private val observations: DnsObservationStore,
    private val counters: EngineCounters,
) {

    sealed interface Dns {
        class Hit(val rule: DomainRule) : Dns

        /** No unexpired candidates, or every candidate matches no rule. */
        data object None : Dns

        /** Candidates disagree — including "one matches a rule, another matches none". */
        data object Ambiguous : Dns
    }

    /** FR-3(a): a valid SNI is authoritative; a miss here never falls back to DNS. */
    fun bySni(host: String, index: RuleIndex): DomainRule? = index.match(host)

    /** §4.6: the DNS-answer fallback, keyed by the flow's destination address. */
    fun byDns(ip: ByteArray, index: RuleIndex): Dns {
        val names = observations.candidates(ip)
        if (names.isEmpty()) return Dns.None
        val first = index.match(names[0])
        for (i in 1 until names.size) {
            if (index.match(names[i])?.id != first?.id) {
                counters.inc(Counter.dns_fallback_ambiguous)
                return Dns.Ambiguous
            }
        }
        if (first == null) return Dns.None
        counters.inc(Counter.dns_fallback_attributed)
        return Dns.Hit(first)
    }
}
