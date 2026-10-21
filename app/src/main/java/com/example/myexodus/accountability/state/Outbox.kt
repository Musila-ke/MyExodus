package com.example.myexodus.accountability.state

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
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
@Entity(
    tableName = "outbox",
    // §8.9 pending-work index: the drain asks for due PENDING rows only.
    indices = [Index(value = ["status", "next_attempt_at"])],
)
data class Outbox(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "destination_uid") val destinationUid: String? = null,
    @ColumnInfo(name = "envelope_bytes") val envelopeBytes: ByteArray,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAtEpochMillis: Long? = null,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    /** §8.5/§8.9: earliest time this row is due for another attempt (0 = immediately). */
    @ColumnInfo(name = "next_attempt_at", defaultValue = "0") val nextAttemptAtEpochMillis: Long = 0,
    /** §8.7 retention: pending local envelopes expire 30 days after creation. */
    @ColumnInfo(name = "expires_at", defaultValue = "0") val expiresAtEpochMillis: Long = 0,
    /** §8.9 `last_error_code` — a closed code (`network`, a Firestore code name), never text
     *  from the event body or an exception message. */
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
    /** Step 18 (AD-23): when this client last read the mailbox document back to look for the
     *  relay's rejection marker. Null = never checked; the row is due from then until an hour
     *  passes without a check (ADR #53's self-healing due window — see [OutboxDao.relayCheckDue]). */
    @ColumnInfo(name = "relay_checked_at") val relayCheckedAtEpochMillis: Long? = null,
    /** Step 18: the marker the read-back found (`RATE_LIMITED`, `PAIRING_INVALID`, …), or null
     *  for none. Kept as the raw value — distinct from a network failure by construction,
     *  because network failures never get this far (they leave the row PENDING). */
    @ColumnInfo(name = "relay_rejection") val relayRejection: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT = "SENT"
        /** §8.4 `EXPIRED`: retention ended delivery. The row (and its state) is kept. */
        const val STATUS_EXPIRED = "EXPIRED"
        const val REJECTION_RATE_LIMITED = "RATE_LIMITED"

        /** §8.7 pending relay ciphertext retention: 30 days, aligned with the relay's cap. */
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
