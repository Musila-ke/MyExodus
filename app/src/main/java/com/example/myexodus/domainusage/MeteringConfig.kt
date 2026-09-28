package com.example.myexodus.domainusage

/**
 * Compile-time tuning constants, plan §2.3 (domain usage metering). Values change only by
 * explicit plan revision; several are beta-tuned from `ACCOUNT`-stage counters (§16 item 9).
 * `DNS_TTL_FLOOR_S` deliberately does not exist: a positive TTL floor was the defect that made
 * short-TTL CDN reassignments stale (§16 item 10) — only the memory-safety cap remains.
 */
object MeteringConfig {

    // Tick and activity (D-03/D-04).
    const val TICK_MS = 1_000L
    const val T_IDLE_MS = 15_000L
    const val MIN_ACTIVITY_BYTES = 512

    // Persistence (D-06, §4.9/NFR-8).
    const val PERSIST_INTERVAL_MS = 10_000L
    const val SHUTDOWN_FLUSH_TIMEOUT_MS = 3_000L

    // Monitoring (§14): debug/beta counter log cadence.
    const val COUNTER_LOG_INTERVAL_MS = 60_000L

    // Clock diagnostics (§6.5 step 3, INV-16) — never affect accrual.
    const val CLOCK_JUMP_THRESHOLD_MS = 5_000L
    const val MAX_TICK_GAP_DIAG_MS = 60_000L

    // TLS inspection (D-13, §16 item R-INSPECT-TIMEOUT).
    const val MAX_INSPECTION_HOLD_MS = 8_000L
    const val INSPECT_MAX_BYTES = 16_384
    const val INSPECT_POOL_SIZE = 64

    // DNS observation store (§6.2, §16 item R-TTL-FLOOR).
    const val DNS_OBS_MAX_IPS = 4_096
    const val DNS_OBS_MAX_NAMES_PER_IP = 8
    const val DNS_TTL_CAP_S = 1_800
    const val NXDOMAIN_NEG_TTL_S = 5
    const val NODATA_NEG_TTL_S = 60

    // Retention (§6.7/§6.8).
    const val RETENTION_DAYS = 30

    // Circuit breaker (§8 S1.5).
    const val BREAKER_MAX_EXCEPTIONS = 20
    const val BREAKER_WINDOW_MS = 60_000L

    // Rule limits (§16 item R-LIMIT-BOUNDS).
    const val MIN_DAILY_LIMIT_MS = 60_000L
    const val MAX_DAILY_LIMIT_MS = 86_400_000L

    // Flow registry cap (§16 item R-FLOWREG).
    const val MAX_TRACKED_FLOWS_PER_RULE = 2_048

    // RFC 792 port-unreachable (§6.10).
    const val ICMP_PORT_UNREACHABLE_TYPE = 3
    const val ICMP_PORT_UNREACHABLE_CODE = 3
}
