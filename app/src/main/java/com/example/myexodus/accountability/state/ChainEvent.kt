package com.example.myexodus.accountability.state

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** §8.3 events row. The envelope is the encoded [com.example.myexodus.accountability.serialization.SignedEnvelope]. */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["pair_id", "sender_key_id", "sequence"], unique = true),
    ],
)
data class ChainEvent(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "sender_key_id") val senderKeyId: String,
    @ColumnInfo(name = "epoch") val epoch: Int,
    @ColumnInfo(name = "sequence") val sequence: Long,
    @ColumnInfo(name = "previous_event_hash") val previousEventHash: ByteArray?,
    @ColumnInfo(name = "event_hash") val eventHash: ByteArray,
    @ColumnInfo(name = "envelope") val envelope: ByteArray,
    @ColumnInfo(name = "direction") val direction: String,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "sync_state") val syncState: String,
) {
    companion object {
        const val DIRECTION_SENT = "SENT"
        const val DIRECTION_RECEIVED = "RECEIVED"
        const val SYNC_STATE_PENDING = "PENDING"
        const val SYNC_STATE_SENT = "SENT"
        const val SYNC_STATE_PENDING_GAP = "PENDING_GAP"
        const val SYNC_STATE_PERMANENTLY_INCOMPLETE = "PERMANENTLY_INCOMPLETE"
    }
}
