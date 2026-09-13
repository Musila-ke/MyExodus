package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import com.example.myexodus.data.db.entity.RecoveryPolicy
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RecoveryPolicyDao {

    @Query("SELECT * FROM recovery_policy WHERE id = 0")
    abstract fun observe(): Flow<RecoveryPolicy?>

    @Query("SELECT * FROM recovery_policy WHERE id = 0")
    abstract suspend fun current(): RecoveryPolicy?

    @Query("UPDATE recovery_policy SET default_friction_seconds = :frictionSeconds, " +
        "override_cooldown_seconds = :cooldownSeconds WHERE id = 0")
    abstract suspend fun update(frictionSeconds: Long, cooldownSeconds: Long)
}
