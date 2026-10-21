package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Per-pair consent for the profile-wide categories (schema v16, ADR #135): blocked-app
 * check-ins (the attempt queue), recovery practice (the session queue plus coaching rows),
 * and — since v19 (Step 7) — device availability (the monitoring snapshot). Same semantics as
 * [PairStreakSharing]: absence = nothing shared, and the `*_enabled_since_ms` watermarks stop
 * an enable from delivering the while-off backlog.
 */
@Entity(tableName = "pair_profile_sharing")
data class PairProfileSharing(
    @PrimaryKey @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "share_checkins") val shareCheckins: Boolean = false,
    @ColumnInfo(name = "share_recovery") val shareRecovery: Boolean = false,
    @ColumnInfo(name = "checkins_enabled_since_ms") val checkinsEnabledSinceMs: Long = 0,
    @ColumnInfo(name = "recovery_enabled_since_ms") val recoveryEnabledSinceMs: Long = 0,
    // Defaults keep Room's schema validation in step with the v19 ADD COLUMN migration.
    @ColumnInfo(name = "share_availability", defaultValue = "0")
    val shareAvailability: Boolean = false,
    @ColumnInfo(name = "availability_enabled_since_ms", defaultValue = "0")
    val availabilityEnabledSinceMs: Long = 0,
    /** Step 10 (schema v20): the availability consent changed and the server has not yet
     *  acknowledged it — the revocation/transition is retried every pass and the settings
     *  screen says so. Local behavior (snapshot sending) always follows [shareAvailability],
     *  never this flag; it is purely the server-convergence report. */
    @ColumnInfo(name = "availability_consent_pending", defaultValue = "0")
    val availabilityConsentPending: Boolean = false,
)
