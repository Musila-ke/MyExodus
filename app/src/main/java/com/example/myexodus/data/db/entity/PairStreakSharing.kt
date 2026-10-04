package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * Per-(pair, habit) streak consent (schema v16, ADR #135): the owner-mandated replacement for
 * the v15 global flags — each partner is chosen per streak (owner decision 2026-10-04).
 * Absence of a row = nothing shared for that (pair, habit) — "nothing until you choose" is
 * the default for every pair, new or existing.
 *
 * The `*_enabled_since_ms` watermarks are bumped on every false→true transition of their
 * switch: the reconcile filter delivers a journal row to this pair only when it was written
 * at or after the kind's watermark, so events from while the switch was off are never
 * delivered retroactively. 0 means "since the beginning of time" (the row's first write).
 *
 * Rows are keyed by pairId strings — enrollments live in the separate accountability store,
 * so no foreign key is possible on `pair_id`; orphaned rows are pruned by the sync sweep
 * against the live enrollment set. `habit_progress_id` does cascade with habit deletion.
 */
@Entity(
    tableName = "pair_streak_sharing",
    primaryKeys = ["pair_id", "habit_progress_id"],
    foreignKeys = [
        ForeignKey(
            entity = HabitProgress::class,
            parentColumns = ["progress_id"],
            childColumns = ["habit_progress_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["habit_progress_id"])],
)
data class PairStreakSharing(
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "habit_progress_id") val habitProgressId: String,
    @ColumnInfo(name = "share_streak") val shareStreak: Boolean = false,
    @ColumnInfo(name = "share_relapses") val shareRelapses: Boolean = false,
    @ColumnInfo(name = "share_milestones") val shareMilestones: Boolean = false,
    @ColumnInfo(name = "streak_enabled_since_ms") val streakEnabledSinceMs: Long = 0,
    @ColumnInfo(name = "relapse_enabled_since_ms") val relapseEnabledSinceMs: Long = 0,
    @ColumnInfo(name = "milestone_enabled_since_ms") val milestoneEnabledSinceMs: Long = 0,
)
