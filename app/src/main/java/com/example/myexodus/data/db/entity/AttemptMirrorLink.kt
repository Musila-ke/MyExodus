package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * One delivered mirror of an [AttemptLog] row to one pair (schema v12, ADR #111). Replaces the
 * single-valued `attempt_log.event_sequence` link: with N enrollments (§19.5) the old column
 * let the first pair's reconcile pass claim every pending row, so the rest never received a
 * copy. The composite key makes each (attempt, pair) delivery exactly-once — re-linking is a
 * no-op, and a row pending for one pair stays pending for every pair that has no link yet.
 */
@Entity(
    tableName = "attempt_mirror_link",
    primaryKeys = ["attempt_id", "pair_id"],
)
data class AttemptMirrorLink(
    @ColumnInfo(name = "attempt_id") val attemptId: Long,
    @ColumnInfo(name = "pair_id") val pairId: String,
    /** The chain sequence of the emitted event in THIS pair's chain. */
    @ColumnInfo(name = "sequence") val sequence: Long,
)
