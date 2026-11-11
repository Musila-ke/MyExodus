package com.example.myexodus.accountability.monitoring

import com.example.myexodus.accountability.serialization.MonitoringCapability
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.example.myexodus.data.db.entity.PartnerRuleChange

/**
 * Owner directive 2026-11-10: a lost grant only alerts when a rule the partner was assigned to
 * actually needs it. The received rule feed is this device's only rule-state source — rows
 * arrive one per lifecycle change, newest first — so the newest row per rule decides whether
 * it is still live. A pre-v2 row carries no target type and a rule older than the feed's
 * 30-day window is unknown here: both are skipped, because a guessed alert is worse than none.
 */
internal fun rulesNeeding(
    rows: List<PartnerRuleChange>,
    lost: Set<MonitoringCapability>,
): Map<MonitoringCapability, List<String>> {
    if (lost.isEmpty()) return emptyMap()
    val live = rows.groupBy { it.ruleId }
        .values
        .mapNotNull { changes ->
            changes.maxWithOrNull(compareBy({ it.occurredAtMinute }, { it.receivedAtMs }))
        }
        .filter { RuleChange.fromWire(it.ruleChange) !in RETIRED }
        .mapNotNull { row -> row.ruleIdentityOrNull()?.let { it.label to needs(it.targetType) } }
    return MonitoringCapability.entries
        .filter { it in lost }
        .associateWith { capability -> live.filter { capability in it.second }.map { it.first }.distinct() }
        .filterValues { it.isNotEmpty() }
}

/** No partner-facing obligation left: the rule is gone, off, expired, or no longer theirs. */
private val RETIRED = setOf(
    RuleChange.DELETED,
    RuleChange.DISABLED,
    RuleChange.EXPIRED,
    RuleChange.UNASSIGNED,
)

/** Which grants a rule of this shape relies on to do its job at all. */
private fun needs(type: RuleTargetType): Set<MonitoringCapability> = when (type) {
    RuleTargetType.APP -> setOf(
        MonitoringCapability.ACCESSIBILITY,
        MonitoringCapability.USAGE_ACCESS,
        MonitoringCapability.OVERLAY,
    )
    RuleTargetType.WEBSITE -> setOf(MonitoringCapability.WEBSITE_FILTER)
    RuleTargetType.BATCH -> MonitoringCapability.entries.toSet()
}
