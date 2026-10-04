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

    /** The profile every engine consults. Single-active is the invariant (SchemaConstraints);
     *  the ORDER BY makes the pick deterministic if a second active row ever appears, and
     *  [RestrictionRuleDao.getActiveRules] mirrors this exact query. */
    @Query("SELECT * FROM user_profile WHERE is_active = 1 ORDER BY user_id LIMIT 1")
    suspend fun getActive(): UserProfile?

    @Query("SELECT * FROM user_profile WHERE is_active = 1 ORDER BY user_id LIMIT 1")
    fun observeActive(): Flow<UserProfile?>
}
