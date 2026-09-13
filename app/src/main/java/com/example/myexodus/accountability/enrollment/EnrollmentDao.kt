package com.example.myexodus.accountability.enrollment

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface EnrollmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: EnrollmentRecord)

    @Query("SELECT * FROM enrollments WHERE pair_id = :pairId")
    suspend fun getByPairId(pairId: String): EnrollmentRecord?

    @Query("SELECT * FROM enrollments ORDER BY created_at DESC")
    suspend fun all(): List<EnrollmentRecord>

    @Delete
    suspend fun delete(record: EnrollmentRecord)

    @Query("UPDATE enrollments SET mailbox_watermark_ts = :ts WHERE pair_id = :pairId")
    suspend fun updateMailboxWatermark(pairId: String, ts: Long)
}
