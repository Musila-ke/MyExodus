package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

/**
 * One recovery streak (habit-streaks plan §6/§8 Step 2): created at onboarding and rolled
 * over on every relapse, so a habit accumulates one row per streak. The ended streak is
 * stamped with [relapsedAt] (plus what the OS reported at logging time, [relapseBootCount]/
 * [relapseElapsedRealtimeMs], for later ordering/anomaly analysis). The active streak is the
 * one with `relapsed_at IS NULL`.
 *
 * [habitId] references `habit_progress.progress_id` (the "habit" in the spec's vocabulary).
 * The RecoveryTimer anchor lives on this row: [tWallLastObserved]/[rBootLastObserved]/
 * [bootCountLastObserved] are the dual-clock anchor and [currentStreakElapsedMs] the
 * accumulator advanced during evaluation. [rowVersion] is bumped by every anchor write so a
 * compare-and-update can catch an evaluation that raced past the per-habit mutex
 * (defense in depth, §8 Step 2.7).
 */
@Entity(
    tableName = "recovery_streak",
    foreignKeys = [
        ForeignKey(
            entity = HabitProgress::class,
            parentColumns = ["progress_id"],
            childColumns = ["habit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RecoveryStreak(
    @PrimaryKey @ColumnInfo(name = "streak_id") val streakId: String,
    @ColumnInfo(name = "habit_id") val habitId: String,
    @ColumnInfo(name = "recovery_start_at") val recoveryStartAt: Long,
    @ColumnInfo(name = "relapsed_at") val relapsedAt: Long? = null,
    @ColumnInfo(name = "relapse_boot_count") val relapseBootCount: Long? = null,
    @ColumnInfo(name = "relapse_elapsed_realtime_ms") val relapseElapsedRealtimeMs: Long? = null,
    @ColumnInfo(name = "t_wall_last_observed") val tWallLastObserved: Long,
    @ColumnInfo(name = "r_boot_last_observed") val rBootLastObserved: Long,
    @ColumnInfo(name = "boot_count_last_observed") val bootCountLastObserved: Long,
    @ColumnInfo(name = "current_streak_elapsed_ms") val currentStreakElapsedMs: Long,
    @ColumnInfo(name = "row_version") val rowVersion: Long = 0,
)
