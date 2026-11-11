package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * The recipient-local decrypted monitoring snapshot for one pair (plan §8.9, schema v19).
 * Wholesale state, never a delta: [snapshotEventSequence] is the monotonic guard, so a
 * replayed older snapshot cannot roll the card backwards. [snapshotInstallGeneration] is the
 * authenticated sender key id the snapshot arrived under — the same install generation a
 * re-pair changes. Display must treat a snapshot older than the staleness window as
 * `UNKNOWN`; this row records evidence, it never asserts current monitoring on its own.
 */
@Entity(tableName = "partner_monitoring_snapshot")
data class PartnerMonitoringSnapshot(
    @PrimaryKey @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "monitoring_state") val monitoringState: String,
    @ColumnInfo(name = "outbox_age_bucket") val outboxAgeBucket: String,
    @ColumnInfo(name = "snapshot_install_generation") val snapshotInstallGeneration: String,
    @ColumnInfo(name = "latest_relationship_seq") val latestRelationshipSeq: Long,
    @ColumnInfo(name = "snapshot_event_sequence") val snapshotEventSequence: Long,
    @ColumnInfo(name = "occurred_at_minute") val occurredAtMinute: Long,
    @ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
    /** Grants the sender held when this snapshot was taken (v3); null from a pre-v3 sender. */
    @ColumnInfo(name = "capabilities") val capabilities: String? = null,
)
