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

    @Query("SELECT * FROM coaching_step WHERE profile_id = :profileId ORDER BY step_order ASC")
    suspend fun getForProfile(profileId: String): List<CoachingStep>

    /** v2 FR-6 caps are per kind: ≤5 steps, ≤5 If-Then, ≤5 Values. */
    @Query("SELECT count(*) FROM coaching_step WHERE profile_id = :profileId AND kind = :kind")
    suspend fun countForKind(profileId: String, kind: String): Long

    @Query(
        "SELECT COALESCE(MAX(step_order), 0) FROM coaching_step " +
            "WHERE profile_id = :profileId AND kind = :kind",
    )
    suspend fun maxOrderForKind(profileId: String, kind: String): Int

    @Query("UPDATE coaching_step SET step_order = :order WHERE step_id = :stepId")
    suspend fun setOrder(stepId: String, order: Int)
}
