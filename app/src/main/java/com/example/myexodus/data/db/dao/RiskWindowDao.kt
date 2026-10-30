package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.example.myexodus.data.db.entity.RiskWindow
import kotlinx.coroutines.flow.Flow

@Dao
interface RiskWindowDao {

    @Insert
    suspend fun insert(window: RiskWindow): Long

    @Update
    suspend fun update(window: RiskWindow)

    @Delete
    suspend fun delete(window: RiskWindow)

    @Query("SELECT * FROM risk_window ORDER BY start_minute ASC")
    fun observeAll(): Flow<List<RiskWindow>>

    @Query("SELECT * FROM risk_window ORDER BY start_minute ASC")
    suspend fun all(): List<RiskWindow>

    @Query("SELECT count(*) FROM risk_window")
    suspend fun count(): Long

    /** §6.6 item 7: the staging restore replaces the logical tables wholesale. */
    @Query("DELETE FROM risk_window")
    suspend fun deleteAll()
}
