package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey

/**
 * One row per (streak, milestone day) — never a shared boolean and never keyed by habit
 * alone, so every streak (including post-relapse ones) independently achieves, notifies,
 * and acknowledges its own 7/30/90-day milestones (habit-streaks plan §5 Decision 8, §8
 * Step 4). Rows are pre-created with all timestamps null when the streak starts.
 *
 * The DB is the sole source of truth for "has milestone N been reached for streak X":
 * achieved_at marks the day threshold crossed, notification_sent_at is the atomic claim
 * that suppresses duplicate posts, and acknowledged_at is the splash-card dismissal
 * (`achieved_at IS NOT NULL AND acknowledged_at IS NULL` = unacknowledged).
 */
@Entity(
    tableName = "milestone_events",
    primaryKeys = ["streak_id", "milestone_day"],
    foreignKeys = [
        ForeignKey(
            entity = RecoveryStreak::class,
            parentColumns = ["streak_id"],
            childColumns = ["streak_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MilestoneEvent(
    @ColumnInfo(name = "streak_id") val streakId: String,
    @ColumnInfo(name = "milestone_day") val milestoneDay: Int,
    @ColumnInfo(name = "achieved_at") val achievedAt: Long? = null,
    @ColumnInfo(name = "notification_sent_at") val notificationSentAt: Long? = null,
    @ColumnInfo(name = "acknowledged_at") val acknowledgedAt: Long? = null,
) {
    companion object {
        /** Milestone thresholds in days, single source of truth. The ladder originally stopped
         *  at 90 (plan §12); the owner extended it on 2026-11-07 to six months and a year. */
        val MILESTONE_DAYS = listOf(7, 30, 90, 180, 365)
    }
}
