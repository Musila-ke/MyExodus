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
            "WHERE profile_id = :profileId AND package_name = :packageName AND date_epoch = :dateEpoch",
    )
    suspend fun findByKey(profileId: String, packageName: String, dateEpoch: Int): DailyUsageTracker?
}
