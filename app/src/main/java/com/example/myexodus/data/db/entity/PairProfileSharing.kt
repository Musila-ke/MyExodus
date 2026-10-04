package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Per-pair consent for the profile-wide categories (schema v16, ADR #135): blocked-app
 * check-ins (the attempt queue) and recovery practice (the session queue plus coaching rows).
 * Same semantics as [PairStreakSharing]: absence = nothing shared, and the `*_enabled_since_ms`
 * watermarks stop an enable from delivering the while-off backlog.
 */
@Entity(tableName = "pair_profile_sharing")
data class PairProfileSharing(
    @PrimaryKey @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "share_checkins") val shareCheckins: Boolean = false,
    @ColumnInfo(name = "share_recovery") val shareRecovery: Boolean = false,
    @ColumnInfo(name = "checkins_enabled_since_ms") val checkinsEnabledSinceMs: Long = 0,
    @ColumnInfo(name = "recovery_enabled_since_ms") val recoveryEnabledSinceMs: Long = 0,
)
