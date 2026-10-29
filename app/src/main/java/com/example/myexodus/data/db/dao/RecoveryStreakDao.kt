package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.example.myexodus.data.db.entity.RecoveryStreak

@Dao
interface RecoveryStreakDao {
    @Insert suspend fun insert(streak: RecoveryStreak)

    /** The streak the habit is currently accumulating (no relapse stamped yet). */
    @Query(
        "SELECT * FROM recovery_streak WHERE habit_id = :habitId AND relapsed_at IS NULL " +
            "ORDER BY recovery_start_at DESC LIMIT 1",
    )
    suspend fun getActiveStreak(habitId: String): RecoveryStreak?

    @Query("SELECT * FROM recovery_streak WHERE streak_id = :streakId")
    suspend fun getById(streakId: String): RecoveryStreak?

    /** Whole table for the guest snapshot (account plan §6.3: read without mutating). */
    @Query("SELECT * FROM recovery_streak")
    suspend fun getAll(): List<RecoveryStreak>

    /** Habits with a live (non-relapsed) streak — boot reconciliation's work list (§8 Step 4). */
    @Query("SELECT DISTINCT habit_id FROM recovery_streak WHERE relapsed_at IS NULL")
    suspend fun activeHabitIds(): List<String>

    /** All streaks of a habit, newest first (timeline + streak-rollover checks). */
    @Query(
        "SELECT * FROM recovery_streak WHERE habit_id = :habitId " +
            "ORDER BY recovery_start_at DESC",
    )
    suspend fun getByHabit(habitId: String): List<RecoveryStreak>

    /** Streak deletion (habit removed by the user). Milestones must be deleted first — their
     *  select keys on the streak rows. */
    @Query("DELETE FROM recovery_streak WHERE habit_id = :habitId")
    suspend fun deleteForHabit(habitId: String)

    /**
     * §6 relapse rollover: ends the streak in one statement, stamping what the OS reported
     * at logging time for later ordering/anomaly analysis. Returns 0 if it was already
     * ended (concurrent rollover past the mutex), so the caller can re-fetch instead of
     * double-ending it.
     */
    @Query(
        "UPDATE recovery_streak SET relapsed_at = :relapsedAt, " +
            "relapse_boot_count = :bootCount, " +
            "relapse_elapsed_realtime_ms = :elapsedRealtimeMs " +
            "WHERE streak_id = :streakId AND relapsed_at IS NULL",
    )
    suspend fun markRelapsed(
        streakId: String,
        relapsedAt: Long,
        bootCount: Long,
        elapsedRealtimeMs: Long,
    ): Int

    /**
     * §8 Step 2.7 compare-and-update: persists the evaluated anchor and bumps `row_version`
     * in one statement, returning 0 when another writer already advanced past
     * [expectedRowVersion] (mutex bypassed) — the caller then re-fetches instead of writing.
     */
    @Query(
        "UPDATE recovery_streak SET " +
            "t_wall_last_observed = :tWallLastObserved, " +
            "r_boot_last_observed = :rBootLastObserved, " +
            "boot_count_last_observed = :bootCountLastObserved, " +
            "current_streak_elapsed_ms = :currentStreakElapsedMs, " +
            "row_version = row_version + 1 " +
            "WHERE streak_id = :streakId AND row_version = :expectedRowVersion",
    )
    suspend fun compareAndUpdateAnchor(
        streakId: String,
        tWallLastObserved: Long,
        rBootLastObserved: Long,
        bootCountLastObserved: Long,
        currentStreakElapsedMs: Long,
        expectedRowVersion: Long,
    ): Int
}
