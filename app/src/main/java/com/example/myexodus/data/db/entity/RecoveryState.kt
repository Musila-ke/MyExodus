package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Durable recovery/friction session (blocking-core plan §8.3), one row per rule (owner
 * acceptance test 2026-11-02: a second app's session must not replace the first). Created on
 * "Recovery starts" — both deadlines written in one Room transaction, per §6.
 *
 * `bootSessionId` is the elapsed-realtime value sampled when the session was created; it
 * changes across reboots (elapsed-realtime resets at boot), so a read compares a fresh
 * sample against it to decide which clock is authoritative (plan §8.3).
 */
@Entity(tableName = "recovery_state")
data class RecoveryState(
    @PrimaryKey @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "deadline_wall_clock_utc_millis") val deadlineWallClockUtcMillis: Long,
    @ColumnInfo(name = "deadline_elapsed_realtime_this_boot") val deadlineElapsedRealtimeThisBoot: Long,
    @ColumnInfo(name = "boot_session_id") val bootSessionId: Long,
    @ColumnInfo(name = "last_override_start_wall_utc_millis") val lastOverrideStartWallUtcMillis: Long? = null,
)
