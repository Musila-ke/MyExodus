package com.example.myexodus.accountability.state

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Plan §8 Step 4 / AD-06b outbox row. One row per locally-created event, inserted in the
 * same Room transaction as the chain append: `messageId` (UUID) is the Firestore mailbox
 * document id — generated once at creation, never regenerated — and [envelopeBytes] is the
 * exact serialized envelope, so sync retries transmit identical bytes.
 *
 * [destinationUid] is nullable by decision: partner-uid resolution is deliberately a
 * network-time concern (never persisted beyond the drain that needs it, §7.5), so the row is
 * born without it and the drain path fills it before the first mailbox write (Step 9).
 * Populated-and-drained from Step 9 onward; until then the v1 drain (ChainEvent.syncState)
 * remains the live transport.
 */
@Entity(tableName = "outbox")
data class Outbox(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "destination_uid") val destinationUid: String? = null,
    @ColumnInfo(name = "envelope_bytes") val envelopeBytes: ByteArray,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAtEpochMillis: Long? = null,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    /** Step 18 (AD-23): when this client last read the mailbox document back to look for the
     *  relay's rejection marker. Null = never checked — the row is due exactly one look. */
    @ColumnInfo(name = "relay_checked_at") val relayCheckedAtEpochMillis: Long? = null,
    /** Step 18: the marker the read-back found (`RATE_LIMITED`, `PAIRING_INVALID`, …), or null
     *  for none. Kept as the raw value — distinct from a network failure by construction,
     *  because network failures never get this far (they leave the row PENDING). */
    @ColumnInfo(name = "relay_rejection") val relayRejection: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT = "SENT"
        const val REJECTION_RATE_LIMITED = "RATE_LIMITED"
    }
}
