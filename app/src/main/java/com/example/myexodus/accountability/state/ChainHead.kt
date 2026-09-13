package com.example.myexodus.accountability.state

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * §8.3 chain_heads row. `forked` carries the §9 "flag senderKeyId as FORKED" state: once set,
 * the chain for (pair, sender) is permanently blocked from advancing.
 */
@Entity(
    tableName = "chain_heads",
    primaryKeys = ["pair_id", "sender_key_id"],
)
data class ChainHead(
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "sender_key_id") val senderKeyId: String,
    @ColumnInfo(name = "last_sequence") val lastSequence: Long,
    @ColumnInfo(name = "last_event_hash") val lastEventHash: ByteArray,
    @ColumnInfo(name = "forked") val forked: Boolean = false,
)
