package com.example.myexodus.accountability.state

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface ProcessedEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ProcessedEvent)

    /** AD-10b gate: checked before any domain-state mutation or notification is created. */
    @Query("SELECT EXISTS(SELECT 1 FROM processed_events WHERE message_id = :messageId)")
    suspend fun contains(messageId: String): Boolean
}
