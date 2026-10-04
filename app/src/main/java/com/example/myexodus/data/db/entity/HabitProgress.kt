package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

@Entity(
    tableName = "habit_progress",
    foreignKeys = [
        ForeignKey(
            entity = UserProfile::class,
            parentColumns = ["user_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HabitProgress(
    @PrimaryKey @ColumnInfo(name = "progress_id") val progressId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "target_identifier") val targetIdentifier: String,
    @ColumnInfo(name = "current_clean_days_streak") val currentCleanDaysStreak: Int,
    @ColumnInfo(name = "total_relapses") val totalRelapses: Int,
    @ColumnInfo(name = "access_attempts_count") val accessAttemptsCount: Int,
    @ColumnInfo(name = "habit_duration_years") val habitDurationYears: Int,
    @ColumnInfo(name = "onboarding_severity_level") val onboardingSeverityLevel: String,
    @ColumnInfo(name = "last_relapse_timestamp") val lastRelapseTimestamp: Long?,
    // Consent moved out of this row in v16 (ADR #135): sharing is now per (pair, habit) —
    // `pair_streak_sharing`; the v15 global flags (share_streak/relapses/milestones and the
    // dead share_access_attempts) were dropped. A habit with no consent row is shared with
    // nobody.
    // No FK: the Partnership table is out of scope for the persistence plan (six entities).
    @ColumnInfo(name = "assigned_partnership_id") val assignedPartnershipId: String?,
    // Habit-level record surviving streak rollovers (habit-streaks plan §8 Step 2); the live
    // accumulator is RecoveryStreak.current_streak_elapsed_ms and must never be read from here.
    // defaultValue keeps Room's schema validation in step with the v6 migration's ADD COLUMN.
    @ColumnInfo(name = "all_time_max_streak_elapsed_ms", defaultValue = "0")
    val allTimeMaxStreakElapsedMs: Long = 0,
    // The habit the UI displays (V1 multi-habit): exactly one selected row per profile once a
    // habit exists. A v7 addition; the migration backfills pre-v7 rows (≤1 habit per profile).
    @ColumnInfo(name = "is_selected", defaultValue = "0")
    val isSelected: Boolean = false,
)
