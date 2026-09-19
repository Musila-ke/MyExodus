package com.example.myexodus.accountability.state

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Plan §8 Step 4 / AD-10b receive-side dedup: the relay is at-least-once, this table makes
 * client processing exactly-once. A `messageId` already present is a no-op before any domain
 * mutation or notification. Rows are written by the push-receive handlers (Step 10).
 */
@Entity(tableName = "processed_events")
data class ProcessedEvent(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "processed_at") val processedAtEpochMillis: Long,
)
