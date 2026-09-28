package com.example.myexodus.domainusage

import java.util.concurrent.atomic.AtomicLong

/** The §14 counter set, literal names. In-memory only; no remote telemetry. */
enum class Counter {
    flows_tcp_total,
    tls443_flows,
    sni_ok,
    sni_none,
    sni_ech,
    sni_notls,
    sni_malformed,
    sni_toolarge,
    inspect_pool_exhausted,
    inspect_timeout,
    flows_attributed_total,
    flows_unattributed,
    dns_fallback_attributed,
    dns_fallback_ambiguous,
    dns_queries,
    dns_parse_fail,
    dns_nxdomain_served,
    dns_nodata_aaaa,
    dns_nodata_https,
    dns_nodata_svcb,
    udp_dropped,
    icmp_injected,
    icmp_build_fail,
    rst_injected,
    rst_build_fail,
    flow_resets_new,
    flow_resets_sweep,
    flow_registry_capped,
    clock_jump,
    db_flush_ok,
    db_flush_fail,
    hook_exceptions,
    fatal_vm_error,
    breaker_tripped,
    rules_invalid,
    rules_duplicate_pattern,
    tamper_private_dns,
    tamper_vpn_revoked,
    ;

    companion object {
        /**
         * `tls_clienthello_seen` (§14 health rule): the parser-health denominator, which
         * deliberately excludes [sni_ech] and [sni_notls] — those are protocol states where SNI
         * is inherently unavailable, not parser failures.
         */
        fun tlsClientHelloSeen(counters: EngineCounters): Long =
            counters.get(sni_ok) + counters.get(sni_none) + counters.get(sni_malformed) +
                counters.get(sni_toolarge)
    }
}

/**
 * Relay-thread-safe counter cells. The map is fully populated at construction and never
 * structurally modified, so unsynchronized reads are safe; only the [AtomicLong] cells mutate.
 */
class EngineCounters {

    private val cells: Map<Counter, AtomicLong> = Counter.entries.associateWith { AtomicLong() }

    fun inc(counter: Counter) {
        cells.getValue(counter).incrementAndGet()
    }

    fun get(counter: Counter): Long = cells.getValue(counter).get()

    fun snapshot(): Map<Counter, Long> = Counter.entries.associateWith { get(it) }
}
