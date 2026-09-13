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
    @ColumnInfo(name = "share_access_attempts") val shareAccessAttempts: Boolean,
    @ColumnInfo(name = "share_relapses") val shareRelapses: Boolean,
    @ColumnInfo(name = "share_milestones") val shareMilestones: Boolean,
    // No FK: the Partnership table is out of scope for the persistence plan (six entities).
    @ColumnInfo(name = "assigned_partnership_id") val assignedPartnershipId: String?,
)
