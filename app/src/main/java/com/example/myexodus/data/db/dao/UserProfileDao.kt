package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.example.myexodus.data.db.entity.UserProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Insert suspend fun insert(profile: UserProfile)

    @Update suspend fun update(profile: UserProfile)

    @Delete suspend fun delete(profile: UserProfile)

    @Query("SELECT * FROM user_profile WHERE user_id = :userId")
    suspend fun getById(userId: String): UserProfile?

    @Query("SELECT * FROM user_profile WHERE is_active = 1 LIMIT 1")
    suspend fun getActive(): UserProfile?

    @Query("SELECT * FROM user_profile WHERE is_active = 1 LIMIT 1")
    fun observeActive(): Flow<UserProfile?>
}
