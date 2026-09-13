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
 * Built from the policy snapshot's host-level website rules only: a path-pinned rule
 * (`example.com/forum`) is skipped here because DNS-layer blocking cannot express paths, and
 * including it would over-block the whole host. Path-pinned rules are the Accessibility
 * engine's job (Enhanced Mode). DNS-layer blocking also does not evaluate a rule's time
 * window or daily limit — the plan's §8.4 engine scope is domain matching + health + revoke;
 * scheduling semantics live in the app-block engine. Both ceilings are documented here, not
 * silently hidden.
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
