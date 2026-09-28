package com.example.myexodus.vpn

import com.example.myexodus.blocking.UrlPattern
import java.util.Locale

/**
 * DNS-layer domain matcher (plan §8.4). Matching semantics, all explicit and unit-tested:
 *  - case-insensitive (qnames are lowercased by [DnsMessages.parseQuery]; patterns too);
 *  - trailing-dot normalized on both sides;
 *  - IDNA/punycode-aware: patterns are matched in their ASCII wire form ([UrlPattern.hostAscii]),
 *    which is what a DNS qname on the wire is — a rule entered as "bücher.example" matches the
 *    punycode qname "xn--bcher-kva.example";
 *  - suffix policy: blocking `example.com` also blocks `www.example.com` and any deeper subdomain
 *    (the plan's documented default) — dot-boundary only, so `example.com.evil.com` never matches.
 *
 * Built from the policy snapshot's host-level website rules only, and only those whose block
 * does not depend on usage state:
 *  - a path-pinned rule (`example.com/forum`) is skipped because DNS-layer blocking cannot
 *    express paths, and including it would over-block the whole host; path-pinned rules are
 *    the Accessibility engine's job (Enhanced Mode);
 *  - a rule whose block is governed by its daily limit alone (`dailyLimitMs > 0`, not
 *    always-on) is skipped because it is *metered*, not blocked: `DomainUsageEngine` owns its
 *    DNS answer (NODATA for AAAA/HTTPS/SVCB while under the limit, NXDOMAIN once exhausted,
 *    plan §1/FR-7). Answering NXDOMAIN here ended every connection before a byte could be
 *    counted, so a plain daily-limit rule never loaded at all — device-caught 2026-09-21,
 *    found by the §12.2 matrix run.
 * Remaining ceiling, documented not silently hidden: a rule whose only mechanism is a time
 * window still blocks all day here — the DNS layer has no clock; the Accessibility engine
 * enforces the window accurately.
 */
class DomainBlocklist private constructor(
    private val domains: List<String>,
) {
    fun isBlocked(qname: String): Boolean {
        val q = normalize(qname)
        return domains.any { q == it || q.endsWith(".$it") }
    }

    val size: Int get() = domains.size

    companion object {
        val EMPTY = DomainBlocklist(emptyList())

        fun fromUrlPatterns(patterns: List<UrlPattern>): DomainBlocklist {
            val names = patterns.asSequence()
                .filter { it.pathPrefix == null }
                .filter { it.isAlwaysOn || it.dailyLimitMs <= 0L }
                .mapNotNull { it.hostAscii.takeIf { host -> host.isNotEmpty() && !host.contains(':') } }
                .map { normalize(it) }
                .toList()
            return DomainBlocklist(names)
        }

        /** A blocklist from raw names — used by unit tests and the spike harness. */
        fun of(vararg names: String): DomainBlocklist = DomainBlocklist(names.map { normalize(it) })

        fun normalize(name: String): String {
            var n = name.trim().lowercase(Locale.ROOT)
            if (n.endsWith('.')) n = n.dropLast(1)
            return n
        }
    }
}
