package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Block/attempt history (blocking-core plan §6): one row per enforced block or forced
 * override. The local source of truth for stats and partner-alert delivery (§14) — the
 * events table in the accountability store is the delivery channel, this table is the
 * record. [eventSequence] links the row to its chain event once created; null until then
 * (unpaired, or waiting on the signing auth window).
 */
@Entity(tableName = "attempt_log")
data class AttemptLog(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "occurred_at_epoch_millis") val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "blocked_target") val blockedTarget: String,
    @ColumnInfo(name = "outcome") val outcome: String,
    @ColumnInfo(name = "event_sequence") val eventSequence: Long? = null,
) {
    companion object {
        const val OUTCOME_BLOCKED = "BLOCKED"
        const val OUTCOME_OVERRIDE = "OVERRIDE"
    }
}
