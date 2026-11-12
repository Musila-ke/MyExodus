package com.example.myexodus.accountability.monitoring

import com.example.myexodus.accountability.serialization.MonitoringCapability
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.example.myexodus.data.db.entity.PartnerRuleChange
import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner directive 2026-11-10: a lost grant only alerts for rules that actually need it. */
class RuleCapabilityGapTest {

    @Test
    fun liveAppRule_needsTheThreeAppGrants() {
        val rows = listOf(row(RuleChange.ASSIGNED, RuleTargetType.APP, "Instagram"))

        assertEquals(
            mapOf(
                MonitoringCapability.ACCESSIBILITY to listOf("Instagram"),
                MonitoringCapability.USAGE_ACCESS to listOf("Instagram"),
                MonitoringCapability.OVERLAY to listOf("Instagram"),
            ),
            rulesNeeding(rows, deletedAppGrants),
        )
        assertEquals(
            "a lost website filter is not this rule's problem",
            emptyMap<MonitoringCapability, List<String>>(),
            rulesNeeding(rows, setOf(MonitoringCapability.WEBSITE_FILTER)),
        )
    }

    @Test
    fun newestRowDecides_whetherARuleIsStillLive() {
        val rows = listOf(
            row(RuleChange.ASSIGNED, RuleTargetType.APP, "Instagram", at = 10),
            row(RuleChange.DELETED, RuleTargetType.APP, "Instagram", at = 20),
        )

        assertEquals(emptyMap<MonitoringCapability, List<String>>(), rulesNeeding(rows, deletedAppGrants))
    }

    @Test
    fun preIdentityRows_areSkipped_ratherThanGuessed() {
        val rows = listOf(
            PartnerRuleChange(
                pairId = "pair-1",
                eventId = "event-1",
                ruleId = "rule-1",
                ruleChange = RuleChange.ASSIGNED.wire,
                occurredAtMinute = 10,
                receivedAtMs = 10,
                expiresAtMs = 10,
            ),
        )

        assertEquals(emptyMap<MonitoringCapability, List<String>>(), rulesNeeding(rows, deletedAppGrants))
    }

    private fun row(
        change: RuleChange,
        type: RuleTargetType,
        label: String,
        at: Long = 10,
    ) = PartnerRuleChange(
        pairId = "pair-1",
        eventId = "event-$at",
        ruleId = "rule-1",
        ruleChange = change.wire,
        ruleLabel = label,
        ruleTargetType = type.wire,
        occurredAtMinute = at,
        receivedAtMs = at,
        expiresAtMs = 10,
    )

    private companion object {
        val deletedAppGrants = setOf(
            MonitoringCapability.ACCESSIBILITY,
            MonitoringCapability.USAGE_ACCESS,
            MonitoringCapability.OVERLAY,
        )
    }
}
