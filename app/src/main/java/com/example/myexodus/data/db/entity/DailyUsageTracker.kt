package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One row per tracked package per local calendar day (enforcement plan v2 §8 Step 1).
 * Day identity is the (local date, IANA timezone) pair — Decision 20 — and the per-day
 * warning/block state travels with the row so the accounting transaction has a single
 * record to read and rewrite.
 */
@Entity(
    tableName = "daily_usage_tracker",
    foreignKeys = [
        ForeignKey(
            entity = UserProfile::class,
            parentColumns = ["user_id"],
            childColumns = ["profile_id"],
        ),
    ],
    indices = [
        Index(value = ["profile_id", "package_name", "local_date_anchor"], unique = true),
    ],
)
data class DailyUsageTracker(
    @PrimaryKey @ColumnInfo(name = "tracker_id") val trackerId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    /** ISO-8601 local date the accumulator belongs to, under [timezoneIdAtAnchor]. */
    @ColumnInfo(name = "local_date_anchor") val localDateAnchor: String,
    @ColumnInfo(name = "timezone_id_at_anchor") val timezoneIdAtAnchor: String,
    @ColumnInfo(name = "ms_used_today") val msUsedToday: Long,
    /** NOT_DUE / PENDING_DELIVERY / DELIVERED — state machine owned by the accounting engine. */
    @ColumnInfo(name = "warning1_state") val warning1State: String,
    @ColumnInfo(name = "warning2_state") val warning2State: String,
    @ColumnInfo(name = "blocked_today") val blockedToday: Boolean,
)

/** Decision 20: the day anchor is the (local date, IANA zone) pair — any mismatch with the
 *  device's current day resets the row, a timezone move included. Comparing the date alone
 *  kept a traveler's pre-move usage on the same date while the engine's advisory read already
 *  treated a zone mismatch as zero usage, so the two disagreed (deep review 2026-10-22). */
fun DailyUsageTracker.isStaleFor(today: String, zoneId: String): Boolean =
    localDateAnchor != today || timezoneIdAtAnchor != zoneId
