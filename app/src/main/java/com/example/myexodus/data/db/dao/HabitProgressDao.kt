package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.example.myexodus.data.db.entity.HabitProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitProgressDao {
    @Insert suspend fun insert(progress: HabitProgress)

    @Update suspend fun update(progress: HabitProgress)

    @Delete suspend fun delete(progress: HabitProgress)

    @Query("SELECT * FROM habit_progress WHERE progress_id = :progressId")
    suspend fun getById(progressId: String): HabitProgress?

    /**
     * §8 Step 2.6 running record: raises `all_time_max_streak_elapsed_ms` only — the `WHERE`
     * guard makes it monotonic in SQL, so a later anomaly that shrinks the live accumulator
     * can never lower the record (and the record is never read back as the live streak).
     */
    @Query(
        "UPDATE habit_progress SET all_time_max_streak_elapsed_ms = :value " +
            "WHERE progress_id = :progressId AND all_time_max_streak_elapsed_ms < :value",
    )
    suspend fun raiseAllTimeMax(progressId: String, value: Long): Int

    /** §6 relapse rollover: bumps the counter and stamps the logged relapse time (which
     *  doubles as the idempotency window's reference point, §9). */
    @Query(
        "UPDATE habit_progress SET total_relapses = total_relapses + 1, " +
            "last_relapse_timestamp = :relapsedAt WHERE progress_id = :progressId",
    )
    suspend fun recordRelapse(progressId: String, relapsedAt: Long): Int

    @Query("SELECT * FROM habit_progress WHERE profile_id = :profileId")
    fun observeByProfile(profileId: String): Flow<List<HabitProgress>>

    /** Whole table for the guest snapshot (account plan §6.3: read without mutating). */
    @Query("SELECT * FROM habit_progress")
    suspend fun getAll(): List<HabitProgress>

    /** §6.6 item 7: the staging restore replaces the logical tables wholesale. */
    @Query("DELETE FROM habit_progress")
    suspend fun deleteAll()

    /** All habits of a profile, deterministic display order (multi-habit switcher). */
    @Query(
        "SELECT * FROM habit_progress WHERE profile_id = :profileId " +
            "ORDER BY target_identifier COLLATE NOCASE",
    )
    suspend fun getForProfile(profileId: String): List<HabitProgress>

    /** The displayed habit. LIMIT 1 guards a legacy state where several rows carry the flag. */
    @Query(
        "SELECT * FROM habit_progress WHERE profile_id = :profileId AND is_selected = 1 " +
            "ORDER BY target_identifier COLLATE NOCASE LIMIT 1",
    )
    suspend fun getSelected(profileId: String): HabitProgress?

    /** Selected-habit fallback when nothing carries the flag (e.g. rows written pre-v7):
     *  the first in display order, so resolution is never nondeterministic. */
    @Query(
        "SELECT * FROM habit_progress WHERE profile_id = :profileId " +
            "ORDER BY target_identifier COLLATE NOCASE LIMIT 1",
    )
    suspend fun getFirstForProfile(profileId: String): HabitProgress?

    @Query("UPDATE habit_progress SET is_selected = 0 WHERE profile_id = :profileId")
    suspend fun clearSelection(profileId: String)

    @Query("UPDATE habit_progress SET is_selected = 1 WHERE progress_id = :progressId")
    suspend fun markSelected(progressId: String)

    /** Rename only the label; the streak, milestones and sharing rows stay untouched. */
    @Query("UPDATE habit_progress SET target_identifier = :label WHERE progress_id = :progressId")
    suspend fun rename(progressId: String, label: String): Int
}
