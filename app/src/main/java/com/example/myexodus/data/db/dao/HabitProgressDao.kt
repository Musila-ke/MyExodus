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

    @Query("SELECT * FROM habit_progress WHERE profile_id = :profileId")
    fun observeByProfile(profileId: String): Flow<List<HabitProgress>>
}
