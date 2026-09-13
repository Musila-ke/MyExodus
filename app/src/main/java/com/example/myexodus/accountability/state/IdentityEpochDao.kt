package com.example.myexodus.accountability.state

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface IdentityEpochDao {

    @Query("SELECT epoch FROM identity_epoch WHERE id = ${IdentityEpoch.SINGLETON_ROW}")
    suspend fun current(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: IdentityEpoch)
}
