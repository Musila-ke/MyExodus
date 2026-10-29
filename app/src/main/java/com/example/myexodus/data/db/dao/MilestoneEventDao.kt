package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.example.myexodus.data.db.entity.MilestoneEvent

@Dao
interface MilestoneEventDao {
    /** Pre-create a new streak's 7/30/90 rows (plan §6, habit setup). */
    @Insert suspend fun insertAll(events: List<MilestoneEvent>)

    @Query("SELECT * FROM milestone_events WHERE streak_id = :streakId ORDER BY milestone_day")
    suspend fun getForStreak(streakId: String): List<MilestoneEvent>

    /** Whole table for the guest import (account plan §6.3: read without mutating). */
    @Query("SELECT * FROM milestone_events")
    suspend fun getAll(): List<MilestoneEvent>

    /** Merges the facts of a deduplicated streak into the row that already exists (§6.3.4). */
    @Update suspend fun update(event: MilestoneEvent)

    /** Every milestone row of every streak the habit ever had — habit deletion. */
    @Query(
        "DELETE FROM milestone_events WHERE streak_id IN " +
            "(SELECT streak_id FROM recovery_streak WHERE habit_id = :habitId)",
    )
    suspend fun deleteForHabit(habitId: String)

    @Query(
        "SELECT * FROM milestone_events WHERE streak_id = :streakId AND milestone_day = :milestoneDay",
    )
    suspend fun get(streakId: String, milestoneDay: Int): MilestoneEvent?

    /** First crossing only: a milestone already achieved is not re-stamped. */
    @Query(
        "UPDATE milestone_events SET achieved_at = :achievedAt WHERE streak_id = :streakId " +
            "AND milestone_day = :milestoneDay AND achieved_at IS NULL",
    )
    suspend fun markAchieved(streakId: String, milestoneDay: Int, achievedAt: Long): Int

    /**
     * Atomic notification claim (§6, §8 Step 4.3): returns 1 only for the evaluation that
     * won the race, so overlapping evaluations post at most one notification.
     */
    @Query(
        "UPDATE milestone_events SET notification_sent_at = :sentAt WHERE streak_id = :streakId " +
            "AND milestone_day = :milestoneDay AND notification_sent_at IS NULL",
    )
    suspend fun claimNotification(streakId: String, milestoneDay: Int, sentAt: Long): Int

    /**
     * Splash-card dismissal (§8 Step 4). Guarded on `achieved_at IS NOT NULL` so a dismissal
     * rendered for one streak can never pre-acknowledge the next streak's (unachieved) row
     * for the same day after a rollover.
     */
    @Query(
        "UPDATE milestone_events SET acknowledged_at = :acknowledgedAt WHERE streak_id = :streakId " +
            "AND milestone_day = :milestoneDay AND achieved_at IS NOT NULL AND acknowledged_at IS NULL",
    )
    suspend fun acknowledge(streakId: String, milestoneDay: Int, acknowledgedAt: Long): Int
}
