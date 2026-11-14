package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.example.myexodus.data.db.entity.RecoveryPolicy
import kotlinx.coroutines.flow.Flow

@Dao
interface RecoveryPolicyDao {

    @Query("SELECT * FROM recovery_policy WHERE id = 0")
    fun observe(): Flow<RecoveryPolicy?>

    @Query("SELECT * FROM recovery_policy WHERE id = 0")
    suspend fun current(): RecoveryPolicy?

    @Query("UPDATE recovery_policy SET default_friction_seconds = :frictionSeconds, " +
        "override_cooldown_seconds = :cooldownSeconds WHERE id = 0")
    suspend fun update(frictionSeconds: Long, cooldownSeconds: Long)

    /** Opt-in portable-preference import (account plan §6.3.5); the row is seeded on create. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(policy: RecoveryPolicy)
}
