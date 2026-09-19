package com.example.myexodus.accountability.state

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * §8.3 chain_heads row — the plan §8 Step 4 `ChainState` (one row per sender, keyed by
 * pair + senderKeyHash; richer than the plan's senderKeyHash-only key because one device
 * carries a row per pair). `forked` carries the §9 "flag senderKeyId as FORKED" state: once
 * set, the chain for (pair, sender) is permanently blocked from advancing.
 *
 * `keyEpoch` is the AD-12/Step 15 session-key epoch; written by [SessionKeyManager]
 * (rotate at 500 events / 30 days) and stamped from received envelopes when the partner's
 * epoch advances. `keyEpochStartedAt` + `keyEpochStartSequence` anchor the two rotation
 * thresholds: `startedAt` is the first event's signed timestamp of the current epoch (same
 * value on both devices, so no clock reconciliation is needed beyond typical skew), and
 * `startSequence` makes `eventsSinceRotation = lastSequence - startSequence`.
 *
 * `repairState` (AD-08, Step 11) carries the staged repair path for a gap in this sender's
 * chain: NONE → REPAIR_PENDING → RECOVERY_EXHAUSTED → CHAIN_INCOMPLETE, with
 * TAMPER_SUSPECTED as the separate trust-blocking state (never conflated with the neutral
 * terminal). `repairFirstRequestedAt` anchors the 30-day window; `repairLastRequestedAt`
 * paces the repeated SyncRequests (one per 24h).
 */
@Entity(
    tableName = "chain_heads",
    primaryKeys = ["pair_id", "sender_key_id"],
)
data class ChainHead(
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "sender_key_id") val senderKeyId: String,
    @ColumnInfo(name = "last_sequence") val lastSequence: Long,
    @ColumnInfo(name = "last_event_hash") val lastEventHash: ByteArray,
    @ColumnInfo(name = "forked") val forked: Boolean = false,
    // defaultValue keeps Room's schema validation in step with MIGRATION_4_5's ADD COLUMN.
    @ColumnInfo(name = "key_epoch", defaultValue = "0") val keyEpoch: Int = 0,
    // Nullable: an epoch-0 row that predates MIGRATION_8_9 (or ran an older build) has no
    // anchor until its next event stamps one.
    @ColumnInfo(name = "key_epoch_started_at") val keyEpochStartedAtEpochMillis: Long? = null,
    // defaultValue keeps Room's schema validation in step with MIGRATION_8_9's ADD COLUMN.
    @ColumnInfo(name = "key_epoch_start_sequence", defaultValue = "0") val keyEpochStartSequence: Long = 0L,
    // defaultValue keeps Room's schema validation in step with MIGRATION_5_6's ADD COLUMN.
    @ColumnInfo(name = "repair_state", defaultValue = REPAIR_NONE) val repairState: String = REPAIR_NONE,
    @ColumnInfo(name = "repair_first_requested_at") val repairFirstRequestedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "repair_last_requested_at") val repairLastRequestedAtEpochMillis: Long? = null,
) {
    companion object {
        const val REPAIR_NONE = "NONE"
        const val REPAIR_PENDING = "REPAIR_PENDING"
        const val REPAIR_RECOVERY_EXHAUSTED = "RECOVERY_EXHAUSTED"
        const val REPAIR_CHAIN_INCOMPLETE = "CHAIN_INCOMPLETE"

        /** §9 row 458: a supplied replay failed verification; automatic trust is blocked. */
        const val REPAIR_TAMPER_SUSPECTED = "TAMPER_SUSPECTED"

        /** States in which a repair is in flight (a replay is expected). */
        val ACTIVE_REPAIR_STATES = setOf(REPAIR_PENDING, REPAIR_RECOVERY_EXHAUSTED)
    }
}
