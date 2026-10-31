package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.serialization.RuleTargetType

/**
 * Recipient-local rule-change feed (plan §8.4 RULE_CONTROL). Contract bodies never reach the
 * dashboard's chain decoder, so the received change is recorded here, keyed by
 * (pair, event) so mailbox redelivery is idempotent. Detail is retained 30 days (§8.7).
 */
@Entity(
    tableName = "partner_rule_change",
    primaryKeys = ["pair_id", "event_id"],
    indices = [Index(value = ["occurred_at_minute"])],
)
data class PartnerRuleChange(
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "rule_change") val ruleChange: String,
    /** Bounded v2 identity; null on rows received from a pre-v2 sender. */
    @ColumnInfo(name = "rule_label") val ruleLabel: String? = null,
    @ColumnInfo(name = "rule_target_type") val ruleTargetType: String? = null,
    @ColumnInfo(name = "rule_item_count") val ruleItemCount: Long? = null,
    @ColumnInfo(name = "rule_batch_label") val ruleBatchLabel: String? = null,
    @ColumnInfo(name = "occurred_at_minute") val occurredAtMinute: Long,
    @ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
) {
    /** The v2 identity as the wire delivered it; null on rows from a pre-v2 sender. */
    fun ruleIdentityOrNull(): RuleIdentity? {
        val type = ruleTargetType?.let(RuleTargetType::fromWire) ?: return null
        return RuleIdentity(
            label = ruleLabel ?: return null,
            targetType = type,
            itemCount = ruleItemCount,
            batchLabel = ruleBatchLabel,
        )
    }
}
