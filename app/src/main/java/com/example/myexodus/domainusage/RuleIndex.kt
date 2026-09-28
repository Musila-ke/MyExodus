package com.example.myexodus.domainusage

/**
 * §4.5: the immutable matcher. Built once per rule snapshot; the Engine swaps the whole
 * instance atomically. Longest pattern wins; never-block hosts and encrypted-DNS resolvers
 * never match (S2.4/S2.5) — the escape hatch and the device's own connectivity must stay
 * unreachable by any rule, however the stored data got there.
 */
class RuleIndex private constructor(private val byPattern: Map<String, DomainRule>) {

    /** The rule matching [name], or null (unmatched, never-block, or resolver). */
    fun match(name: String): DomainRule? {
        val n = name.lowercase().trimEnd('.')
        if (n.isEmpty()) return null
        if (NeverBlockDomains.matches(n) || EncryptedDnsResolvers.matches(n)) return null
        var suffix = n
        while (true) {
            byPattern[suffix]?.let { return it }
            val firstDot = suffix.indexOf('.')
            // Stop at 2 labels: a rule pattern always has at least two.
            if (firstDot < 0 || suffix.indexOf('.', firstDot + 1) < 0) return null
            suffix = suffix.substring(firstDot + 1)
        }
    }

    companion object {
        /**
         * Build-time dedup (§4.5): two source rules on one pattern would silently lose one at
         * runtime, so the lexicographically smallest id keeps the pattern and the rest are
         * dropped and counted. RuleEditor rejects new collisions (S2.8); this is the backstop
         * for data that predates the check.
         */
        fun build(rules: List<DomainRule>, counters: EngineCounters): RuleIndex {
            val byPattern = HashMap<String, DomainRule>(rules.size)
            for (rule in rules) {
                val kept = byPattern[rule.pattern]
                when {
                    kept == null -> byPattern[rule.pattern] = rule
                    rule.id < kept.id -> {
                        byPattern[rule.pattern] = rule
                        counters.inc(Counter.rules_duplicate_pattern)
                    }
                    else -> counters.inc(Counter.rules_duplicate_pattern)
                }
            }
            return RuleIndex(byPattern)
        }
    }
}
