package com.example.myexodus.domainusage

import com.example.myexodus.blocking.RulesSnapshotPublisher
import com.example.myexodus.blocking.UrlPattern
import com.example.myexodus.blocking.dayScopeCoversToday
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * S2.6: adapts the existing website rules into [DomainRule]s. The source is the published
 * [RulesSnapshotPublisher] snapshot, not the raw table — "which website rules are live" then
 * has exactly one definition in the app (disabled and expired rules are already dropped there,
 * and no wall-clock read is needed here).
 *
 * Adaptations from the plan's wording, recorded in the implementation log: `id` is the
 * existing rule id as-is (a String in this codebase, so no `toString()`), and `pattern` is
 * built from [UrlPattern.hostAscii] — the ASCII/punycode canonical host the SNI and DNS paths
 * actually compare against.
 *
 * [DomainRule.revision] (D-32) is an in-memory counter per id that increments only when the
 * observed pattern changes. It resets with the process, so [seedRevisions] restores the
 * persisted high-water mark before the first snapshot is adapted.
 */
class DomainRuleProvider(
    private val publisher: RulesSnapshotPublisher,
    private val psl: PublicSuffixList,
    private val counters: EngineCounters,
) {

    private val lock = Any()
    private val lastPatternById = HashMap<String, String>()
    private val revisionById = HashMap<String, Long>()

    /** Re-emits whenever the rule table publishes a new snapshot. */
    val rules: Flow<List<DomainRule>> = publisher.state.map { adapt(publisher.snapshot.urlRules) }

    suspend fun snapshot(): List<DomainRule> = adapt(publisher.snapshot.urlRules)

    private fun adapt(urlRules: List<UrlPattern>): List<DomainRule> = synchronized(lock) {
        urlRules.mapNotNull { adaptOne(it) }
    }

    private fun adaptOne(rule: UrlPattern): DomainRule? {
        if (rule.dailyLimitMs <= 0L) return null
        val pattern = when (val result = HostNormalizer.normalizeLegacy(rule.hostAscii, psl)) {
            is HostNormalizer.Result.Ok -> result.host
            else -> {
                counters.inc(Counter.rules_invalid)
                return null
            }
        }
        // R-LIMIT-BOUNDS: a stored value outside the range is clamped and counted, never
        // silently accepted as-is.
        val limit = rule.dailyLimitMs
            .coerceIn(MeteringConfig.MIN_DAILY_LIMIT_MS, MeteringConfig.MAX_DAILY_LIMIT_MS)
        if (limit != rule.dailyLimitMs) counters.inc(Counter.rules_invalid)
        return DomainRule(
            id = rule.ruleId,
            revision = revisionFor(rule.ruleId, pattern),
            pattern = pattern,
            dailyLimitMs = limit,
            enforcedToday = dayScopeCoversToday(rule.activeDaysOfWeek, rule.timezone),
        )
    }

    /**
     * D-32 fix (§6.8): before the first snapshot is adapted, the Engine hands in the highest
     * revision each rule id was persisted under. A rule's first observation in this process
     * adopts that value instead of restarting at 0, so a restart cannot hand a live rule the
     * usage of the day it already burned through. Only ever raises; a seeded id whose pattern is
     * edited while the process is down still transfers that day's usage — undetectable without
     * persisting the pattern itself, which S-4 forbids (implementation log).
     */
    fun seedRevisions(maxRevisionByRuleId: Map<String, Long>) = synchronized(lock) {
        for ((id, seeded) in maxRevisionByRuleId) {
            val current = revisionById[id]
            if (current == null || current < seeded) revisionById[id] = seeded
        }
    }

    private fun revisionFor(id: String, pattern: String): Long {
        val last = lastPatternById[id]
        if (last == pattern) return revisionById.getValue(id)
        val next = if (last == null) revisionById[id] ?: 0L else revisionById.getValue(id) + 1L
        lastPatternById[id] = pattern
        revisionById[id] = next
        return next
    }
}
