package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.example.myexodus.data.db.entity.PairProfileSharing
import com.example.myexodus.data.db.entity.PairStreakSharing

/**
 * Per-pair sharing consent (schema v16, ADR #135). Reads are deliberately unbounded-in-Kotlin:
 * the whole matrix is a handful of rows (pairs × habits), and loading it whole lets the
 * journal gate, the reconcile filter and the settings surface each evaluate the same snapshot
 * without three different query shapes drifting apart.
 */
@Dao
interface PairSharingDao {

    @Query("SELECT * FROM pair_streak_sharing")
    suspend fun streakAll(): List<PairStreakSharing>

    @Query("SELECT * FROM pair_streak_sharing WHERE pair_id = :pairId")
    suspend fun streakForPair(pairId: String): List<PairStreakSharing>

    @Query(
        "SELECT * FROM pair_streak_sharing WHERE pair_id = :pairId AND habit_progress_id = :habitId",
    )
    suspend fun streakFor(pairId: String, habitId: String): PairStreakSharing?

    @Upsert
    suspend fun upsertStreak(row: PairStreakSharing)

    @Query("DELETE FROM pair_streak_sharing WHERE pair_id IN (:pairIds)")
    suspend fun deleteStreakFor(pairIds: List<String>)

    /** Habit deletion: stop sharing it with every pair (past activity events are not recalled). */
    @Query("DELETE FROM pair_streak_sharing WHERE habit_progress_id = :habitId")
    suspend fun deleteStreakForHabit(habitId: String)

    @Query("SELECT * FROM pair_profile_sharing")
    suspend fun profileAll(): List<PairProfileSharing>

    @Query("SELECT * FROM pair_profile_sharing WHERE pair_id = :pairId")
    suspend fun profileFor(pairId: String): PairProfileSharing?

    @Upsert
    suspend fun upsertProfile(row: PairProfileSharing)

    @Query("DELETE FROM pair_profile_sharing WHERE pair_id IN (:pairIds)")
    suspend fun deleteProfileFor(pairIds: List<String>)

    /** Every pair id that carries any consent row — the prune sweep's candidate set (a pair
     *  can have consent rows without ever having produced a projection or link). */
    @Query(
        "SELECT DISTINCT pair_id FROM pair_streak_sharing " +
            "UNION SELECT pair_id FROM pair_profile_sharing",
    )
    suspend fun consentPairIds(): List<String>
}
