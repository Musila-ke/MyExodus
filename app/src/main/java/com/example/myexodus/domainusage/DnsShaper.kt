package com.example.myexodus.domainusage

/**
 * S3.3/§6.1: the DNS decision point. Mode- and rule-scoped only — `OFF`/`ACCOUNT` never shape
 * an answer (FR-11, INV-15), and a name that matches no rule (including every never-block and
 * Recovery Resource host, which [RuleIndex.match] refuses) is never touched. `ENFORCE` answers
 * NXDOMAIN for an exhausted rule's name at any qtype, and NODATA for AAAA/SVCB/HTTPS while the
 * rule is enabled but not yet exhausted (the SNI-visibility measure, D-14).
 *
 * §6.2: [onResponse] walks the answer section as an alias chain — an A record only counts when
 * its owner equals the chain target at that point — and feeds [DnsObservationStore]. The
 * response bytes are never modified.
 */
class DnsShaper(
    private val counters: EngineCounters,
    private val observations: DnsObservationStore,
) {

    sealed interface Decision {
        data object Forward : Decision

        /** DNS reply bytes; the Baseline swaps the query packet's src/dst and writes them. */
        data class Respond(val bytes: ByteArray) : Decision
    }

    /**
     * @param query exact DNS payload of the query packet.
     * @param nowMillis wall clock at decision time — a rule whose `blockExpiresAt` has passed no
     *   longer shapes an answer, even if the last snapshot still carries it (audit A-02).
     * @param isExhausted whether the ledger currently flags [DomainRule] as over its daily limit.
     */
    fun decide(
        query: ByteArray,
        mode: EngineMode,
        ruleIndex: RuleIndex,
        nowMillis: Long,
        isExhausted: (DomainRule) -> Boolean,
    ): Decision {
        counters.inc(Counter.dns_queries)
        val parsed = parse(query)
        if (parsed !is DnsMessage.Parsed.Query) return Decision.Forward
        if (mode != EngineMode.ENFORCE) return Decision.Forward
        val rule = ruleIndex.match(parsed.question.name) ?: return Decision.Forward
        if (rule.isExpiredAt(nowMillis)) return Decision.Forward
        if (isExhausted(rule)) {
            counters.inc(Counter.dns_nxdomain_served)
            return Decision.Respond(
                DnsMessage.buildNegativeReply(parsed, DnsMessage.RCODE_NXDOMAIN, MeteringConfig.NXDOMAIN_NEG_TTL_S),
            )
        }
        return when (parsed.question.type) {
            DnsMessage.TYPE_AAAA -> {
                counters.inc(Counter.dns_nodata_aaaa)
                Decision.Respond(nodata(parsed))
            }
            DnsMessage.TYPE_SVCB -> {
                counters.inc(Counter.dns_nodata_svcb)
                Decision.Respond(nodata(parsed))
            }
            DnsMessage.TYPE_HTTPS -> {
                counters.inc(Counter.dns_nodata_https)
                Decision.Respond(nodata(parsed))
            }
            else -> Decision.Forward
        }
    }

    /** §6.2 — the alias-chain walk; anything unparseable or non-RCODE-0 is ignored. */
    fun onResponse(response: ByteArray) {
        val parsed = parse(response)
        if (parsed !is DnsMessage.Parsed.Response) return
        if (parsed.rcode != DnsMessage.RCODE_NOERROR) return
        val qname = parsed.questionName
        var target = qname
        for (record in parsed.answers) {
            if (record.owner != target) continue
            when {
                record.type == DnsMessage.TYPE_CNAME -> record.cnameTarget?.let { target = it }
                record.type == DnsMessage.TYPE_A && record.rdata.size == 4 ->
                    observations.record(record.rdata, qname, record.ttlSeconds)
                // §6.4 attributes every non-443 flow from this store, so a v6 answer has to be
                // recorded too: without it, no IPv6 flow is ever attributed and the whole
                // metering/enforcement path silently fails open on a v6-only network (device-
                // caught 2026-09-21, matrix I-13). The store has always keyed 16-byte addresses.
                record.type == DnsMessage.TYPE_AAAA && record.rdata.size == 16 ->
                    observations.record(record.rdata, qname, record.ttlSeconds)
            }
        }
    }

    private fun nodata(query: DnsMessage.Parsed.Query): ByteArray =
        DnsMessage.buildNegativeReply(query, DnsMessage.RCODE_NOERROR, MeteringConfig.NODATA_NEG_TTL_S)

    private fun parse(bytes: ByteArray): DnsMessage.Parsed =
        DnsMessage.parse(bytes).also { if (it is DnsMessage.Parsed.Failed) counters.inc(Counter.dns_parse_fail) }
}
