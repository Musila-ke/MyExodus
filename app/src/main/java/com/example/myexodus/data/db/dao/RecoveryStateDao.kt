package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.example.myexodus.data.db.entity.RecoveryState

@Dao
interface RecoveryStateDao {
    @Query("SELECT * FROM recovery_state WHERE id = 0")
    suspend fun current(): RecoveryState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: RecoveryState)

    @Query("DELETE FROM recovery_state")
    suspend fun clear()
}
