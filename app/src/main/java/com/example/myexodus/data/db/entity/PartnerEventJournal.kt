package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.example.myexodus.accountability.serialization.AccountabilityEventCategory
import com.example.myexodus.accountability.serialization.AccountabilityEventKind
import com.example.myexodus.accountability.serialization.AccountabilityEventWire
import com.example.myexodus.accountability.serialization.MonitoringState
import com.example.myexodus.accountability.serialization.OutboxAgeBucket
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.serialization.RuleTargetType
import java.util.UUID

/**
 * Step 4 durable sender-side journal, schema v18 (plan §7 Step 4 / §8.9
 * `partner_event_outbox`'s minimized body). Written inside the domain transaction at
 * detection, before any envelope exists — envelope creation is user-auth-gated, so the
 * minimized body is the durable record and the envelope is produced later at reconcile
 * (Appendix S1.3). Lives in SQLCipher with the other detector journals (§8.9: minimized
 * body "stored only inside SQLCipher").
 *
 * The delivery half of the logical outbox is not duplicated here: per-recipient
 * `relationship_id` / `sender_install_id` / `relationship_seq` / envelope / delivery state
 * live on the existing `events` + `outbox` rows in [com.example.myexodus.accountability.state.AccountabilityDatabase],
 * and `events.event_id` is this row's key — the chain row itself is the link, so a crash
 * between append and "link" cannot exist. One journal row fans out to one chain event per
 * consented relationship (§8.2).
 *
 * Block and monitoring rows store no per-pair values: `latest_relationship_seq` and gap
 * ranges are pair-specific and are computed when that pair's envelope is built; gap markers
 * are not journaled at all because they are constructed from chain evidence, not detector
 * input. Rule-lifecycle rows are the exception — plan §8.4 routes them to the rule's assigned
 * partner only, so they carry that pair id.
 */
@Entity(
    tableName = "partner_event_journal",
    indices = [
        Index(value = ["created_at_ms"]),
        Index(value = ["expires_at_ms"]),
    ],
)
data class PartnerEventJournal(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    /** [AccountabilityEventCategory.wire] — the closed Step 3 allowlist. */
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "occurred_at_minute") val occurredAtMinute: Long,
    /** Only for `MONITORING_STATUS` rows; never inferred, never the persisted desired state. */
    @ColumnInfo(name = "monitoring_state") val monitoringState: String? = null,
    @ColumnInfo(name = "outbox_age_bucket") val outboxAgeBucket: String? = null,
    /** RULE_CONTROL rows only: canonical UUID text, never a package name. */
    @ColumnInfo(name = "rule_id") val ruleId: String? = null,
    @ColumnInfo(name = "rule_change") val ruleChange: String? = null,
    /** Bounded v2 identity (schema v23): what the partner is told changed. v1 rows carry
     *  nulls and the emitter resolves the name from the rule row when it still exists. */
    @ColumnInfo(name = "rule_label") val ruleLabel: String? = null,
    @ColumnInfo(name = "rule_target_type") val ruleTargetType: String? = null,
    @ColumnInfo(name = "rule_item_count") val ruleItemCount: Long? = null,
    @ColumnInfo(name = "rule_batch_label") val ruleBatchLabel: String? = null,
    /** RULE_CONTROL rows only: the assigned pair the envelope is routed to. */
    @ColumnInfo(name = "route_pair_id") val routePairId: String? = null,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    /** §8.7: partner-local event detail is retained for 30 days, then swept. */
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
) {
    init {
        require(AccountabilityEventWire.isEventId(eventId)) { "journal event id is not a UUID" }
        val parsed = requireNotNull(AccountabilityEventCategory.fromWire(category)) {
            "journal category is outside the event contract"
        }
        require(parsed.kind != AccountabilityEventKind.GAP_MARKER) {
            "gap markers are built from chain evidence, not journaled"
        }
        // v1 rows are accepted on read: they were queued before the v2 identity upgrade and
        // must still deliver (the emitter resolves their label from the rule row).
        require(schemaVersion in 1..AccountabilityEventWire.SCHEMA_VERSION) {
            "journal schema version is not a live contract"
        }
        require(occurredAtMinute >= 1) { "occurred-at minute must be positive" }
        if (parsed.kind == AccountabilityEventKind.MONITORING_STATUS) {
            requireNotNull(monitoringState?.let(MonitoringState::fromWire)) {
                "monitoring journal rows need a contract monitoring state"
            }
            requireNotNull(outboxAgeBucket?.let(OutboxAgeBucket::fromWire)) {
                "monitoring journal rows need a contract outbox-age bucket"
            }
            require(ruleId == null && ruleChange == null && routePairId == null) {
                "monitoring journal rows carry no rule fields"
            }
        } else if (parsed.kind == AccountabilityEventKind.RULE_CONTROL) {
            val rid = requireNotNull(ruleId) { "rule journal rows need a rule id" }
            require(AccountabilityEventWire.isEventId(rid)) { "rule journal rule id is not a UUID" }
            requireNotNull(ruleChange?.let(RuleChange::fromWire)) {
                "rule journal rows need a contract rule change"
            }
            requireNotNull(routePairId?.takeIf { it.isNotBlank() }) {
                "rule journal rows need a route pair"
            }
            require(monitoringState == null && outboxAgeBucket == null) {
                "rule journal rows carry no monitoring fields"
            }
            if (schemaVersion == AccountabilityEventWire.SCHEMA_VERSION) {
                requireNotNull(ruleTargetType?.let(RuleTargetType::fromWire)) {
                    "v2 rule journal rows need a target type"
                }
                RuleIdentity(
                    label = requireNotNull(ruleLabel) { "v2 rule journal rows need a label" },
                    targetType = requireNotNull(RuleTargetType.fromWire(ruleTargetType)),
                    itemCount = ruleItemCount,
                    batchLabel = ruleBatchLabel,
                )
            } else {
                require(ruleLabel == null && ruleTargetType == null &&
                    ruleItemCount == null && ruleBatchLabel == null) {
                    "v1 rule journal rows carry no identity"
                }
            }
        } else {
            require(monitoringState == null && outboxAgeBucket == null) {
                "block events carry no monitoring fields"
            }
            require(ruleId == null && ruleChange == null && routePairId == null) {
                "block events carry no rule fields"
            }
        }
    }

    /** Wire identity for a v2 row; null for a v1 row queued before the upgrade. */
    fun ruleIdentityOrNull(): RuleIdentity? {
        if (schemaVersion < AccountabilityEventWire.SCHEMA_VERSION) return null
        val type = ruleTargetType?.let(RuleTargetType::fromWire) ?: return null
        return RuleIdentity(
            label = ruleLabel ?: return null,
            targetType = type,
            itemCount = ruleItemCount,
            batchLabel = ruleBatchLabel,
        )
    }

    companion object {
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** The row for one detector event; the caller supplies the transaction's clock. */
        fun forEvent(
            eventId: String,
            category: AccountabilityEventCategory,
            occurredAtMinute: Long,
            createdAtMs: Long,
            monitoringState: MonitoringState? = null,
            outboxAgeBucket: OutboxAgeBucket? = null,
        ): PartnerEventJournal = PartnerEventJournal(
            eventId = eventId,
            category = category.wire,
            occurredAtMinute = occurredAtMinute,
            monitoringState = monitoringState?.wire,
            outboxAgeBucket = outboxAgeBucket?.wire,
            schemaVersion = AccountabilityEventWire.SCHEMA_VERSION,
            createdAtMs = createdAtMs,
            expiresAtMs = createdAtMs + RETENTION_MILLIS,
        )

        /** One rule-lifecycle row, routed to the rule's assigned pair only (plan §8.4). */
        fun forRuleChange(
            eventId: String,
            ruleId: UUID,
            change: RuleChange,
            routePairId: String,
            occurredAtMinute: Long,
            createdAtMs: Long,
            identity: RuleIdentity,
        ): PartnerEventJournal = PartnerEventJournal(
            eventId = eventId,
            category = AccountabilityEventCategory.RULE_CHANGED.wire,
            occurredAtMinute = occurredAtMinute,
            ruleId = ruleId.toString(),
            ruleChange = change.wire,
            ruleLabel = identity.label,
            ruleTargetType = identity.targetType.wire,
            ruleItemCount = identity.itemCount,
            ruleBatchLabel = identity.batchLabel,
            routePairId = routePairId,
            schemaVersion = AccountabilityEventWire.SCHEMA_VERSION,
            createdAtMs = createdAtMs,
            expiresAtMs = createdAtMs + RETENTION_MILLIS,
        )
    }
}
