package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.example.myexodus.data.db.entity.CoachingStep
import kotlinx.coroutines.flow.Flow

@Dao
interface CoachingStepDao {
    @Insert suspend fun insert(step: CoachingStep)

    @Update suspend fun update(step: CoachingStep)

    @Delete suspend fun delete(step: CoachingStep)

    @Query("SELECT * FROM coaching_step WHERE profile_id = :profileId ORDER BY step_order ASC")
    fun observeByProfile(profileId: String): Flow<List<CoachingStep>>
}
