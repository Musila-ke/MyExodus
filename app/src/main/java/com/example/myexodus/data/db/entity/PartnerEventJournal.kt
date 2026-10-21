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
 * No per-pair values are stored: `latest_relationship_seq` and gap ranges are pair-specific
 * and are computed when that pair's envelope is built; gap markers are not journaled at all
 * because they are constructed from chain evidence, not detector input.
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
        require(schemaVersion == AccountabilityEventWire.SCHEMA_VERSION) {
            "journal schema version is not the live contract"
        }
        require(occurredAtMinute >= 1) { "occurred-at minute must be positive" }
        if (parsed.kind == AccountabilityEventKind.MONITORING_STATUS) {
            requireNotNull(monitoringState?.let(MonitoringState::fromWire)) {
                "monitoring journal rows need a contract monitoring state"
            }
            requireNotNull(outboxAgeBucket?.let(OutboxAgeBucket::fromWire)) {
                "monitoring journal rows need a contract outbox-age bucket"
            }
        } else {
            require(monitoringState == null && outboxAgeBucket == null) {
                "block events carry no monitoring fields"
            }
        }
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
    }
}
