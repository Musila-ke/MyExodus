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

    /**
     * FR-3(a): a valid SNI is authoritative; a miss here never falls back to DNS. A rule whose
     * `blockExpiresAt` has passed is a miss at this instant (audit A-02) — the snapshot may
     * still carry it until the next rebuild, but a lapsed rule must not bind or meter new flows.
     */
    fun bySni(host: String, index: RuleIndex, nowMillis: Long): DomainRule? =
        index.match(host)?.takeUnless { it.isExpiredAt(nowMillis) }

    /** §4.6: the DNS-answer fallback, keyed by the flow's destination address. */
    fun byDns(ip: ByteArray, index: RuleIndex, nowMillis: Long): Dns {
        val names = observations.candidates(ip)
        if (names.isEmpty()) return Dns.None
        val first = liveMatch(names[0], index, nowMillis)
        for (i in 1 until names.size) {
            if (liveMatch(names[i], index, nowMillis)?.id != first?.id) {
                counters.inc(Counter.dns_fallback_ambiguous)
                return Dns.Ambiguous
            }
        }
        if (first == null) return Dns.None
        counters.inc(Counter.dns_fallback_attributed)
        return Dns.Hit(first)
    }

    /** An expired match is not a candidate: that name is no longer a limited site. */
    private fun liveMatch(name: String, index: RuleIndex, nowMillis: Long): DomainRule? =
        index.match(name)?.takeUnless { it.isExpiredAt(nowMillis) }
}
