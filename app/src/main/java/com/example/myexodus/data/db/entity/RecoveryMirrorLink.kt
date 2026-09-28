package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * One delivered mirror of a [RecoverySession] row to one pair (schema v12, ADR #111). The
 * recovery-side twin of [AttemptMirrorLink]: per-(session, pair) exactly-once delivery so
 * every enrollment receives its own copy of the mirrored event.
 */
@Entity(
    tableName = "recovery_mirror_link",
    primaryKeys = ["session_id", "pair_id"],
)
data class RecoveryMirrorLink(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "pair_id") val pairId: String,
    /** The chain sequence of the emitted event in THIS pair's chain. */
    @ColumnInfo(name = "sequence") val sequence: Long,
)
