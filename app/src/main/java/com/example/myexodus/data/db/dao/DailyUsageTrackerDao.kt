package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import com.example.myexodus.data.db.entity.DailyUsageTracker

@Dao
interface DailyUsageTrackerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tracker: DailyUsageTracker)

    @Update suspend fun update(tracker: DailyUsageTracker)

    @Delete suspend fun delete(tracker: DailyUsageTracker)

    @Query(
        "SELECT * FROM daily_usage_tracker " +
            "WHERE profile_id = :profileId AND package_name = :packageName " +
            "AND local_date_anchor = :localDateAnchor",
    )
    suspend fun findByKey(profileId: String, packageName: String, localDateAnchor: String): DailyUsageTracker?

    /** Latest row for the package — the accounting engine's read; a stale anchor is rolled
     *  over in place rather than filtered out (Decision 20). */
    @Query(
        "SELECT * FROM daily_usage_tracker " +
            "WHERE profile_id = :profileId AND package_name = :packageName " +
            "ORDER BY local_date_anchor DESC LIMIT 1",
    )
    suspend fun findByPackage(profileId: String, packageName: String): DailyUsageTracker?

    /** All tracker rows for the profile — the daily-maintenance sweep's read (§8 Step 8). */
    @Query("SELECT * FROM daily_usage_tracker WHERE profile_id = :profileId")
    suspend fun getForProfile(profileId: String): List<DailyUsageTracker>

    /** ADR #240: the retained scope's counters, read from its recovery copy under its profile. */
    @Query("SELECT * FROM daily_usage_tracker")
    suspend fun all(): List<DailyUsageTracker>
}
