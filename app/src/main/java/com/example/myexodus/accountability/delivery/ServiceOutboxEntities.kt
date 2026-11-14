package com.example.myexodus.accountability.delivery

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * §8.1 per-account installation. `owner_uid` is unique by index: a second account gets a new
 * installation ID and never reuses the first account's sequence space.
 */
@Entity(
    tableName = "installation_state",
    indices = [Index(value = ["owner_uid"], unique = true)],
)
data class InstallationState(
    @PrimaryKey @ColumnInfo(name = "installation_id") val installationId: String,
    @ColumnInfo(name = "owner_uid") val ownerUid: String,
    @ColumnInfo(name = "current_sequence") val currentSequence: Long,
    @ColumnInfo(name = "heartbeat_counter", defaultValue = "0") val heartbeatCounter: Long = 0,
    @ColumnInfo(name = "active_key_id") val activeKeyId: String? = null,
    /** The recipient encryption key this installation registered for the active pairing. */
    @ColumnInfo(name = "recipient_key_id") val recipientKeyId: String? = null,
    @ColumnInfo(name = "registration_state") val registrationState: String? = null,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int = PartnerEventProtocol.VERSION,
) {
    companion object {
        const val REGISTRATION_UNREGISTERED = "UNREGISTERED"
        const val REGISTRATION_ACTIVE = "ACTIVE"
        const val REGISTRATION_REENROLL_REQUIRED = "REENROLL_REQUIRED"
    }
}

/**
 * §8.1 owner-bound outbox row. Holds the queue identity, the allowlisted safe metadata, the
 * per-recipient envelopes, and — until acknowledgement — the AES-sealed sensitive source that
 * a `RECIPIENT_KEY_STALE` rejection may re-encrypt from.
 */
@Entity(
    tableName = "pending_partner_events",
    indices = [
        Index(value = ["installation_id", "sequence"], unique = true),
        Index(value = ["owner_uid", "status", "next_attempt_at_ms"]),
    ],
)
data class PendingPartnerEvent(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "installation_id") val installationId: String,
    @ColumnInfo(name = "owner_uid") val ownerUid: String,
    @ColumnInfo(name = "sequence") val sequence: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "safe_metadata_json") val safeMetadataJson: String,
    @ColumnInfo(name = "recipient_envelopes_blob") val recipientEnvelopesBlob: ByteArray? = null,
    @ColumnInfo(name = "temporary_local_ciphertext") val temporaryLocalCiphertext: ByteArray? = null,
    @ColumnInfo(name = "device_occurred_at_ms") val deviceOccurredAtMs: Long,
    @ColumnInfo(name = "elapsed_realtime_at_capture_ms") val elapsedRealtimeAtCaptureMs: Long? = null,
    @ColumnInfo(name = "signed_envelope") val signedEnvelope: ByteArray? = null,
    @ColumnInfo(name = "signing_signature") val signingSignature: ByteArray? = null,
    @ColumnInfo(name = "signing_key_id") val signingKeyId: String? = null,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "recipient_key_revision") val recipientKeyRevision: Int? = null,
    @ColumnInfo(name = "attempted_wire_digest") val attemptedWireDigest: String? = null,
    @ColumnInfo(name = "wire_outcome", defaultValue = WIRE_NOT_SENT) val wireOutcome: String = WIRE_NOT_SENT,
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at_ms") val nextAttemptAtMs: Long? = null,
    @ColumnInfo(name = "lease_until_ms") val leaseUntilMs: Long? = null,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
    @ColumnInfo(name = "created_local_at_ms") val createdLocalAtMs: Long,
    @ColumnInfo(name = "acked_server_at_ms") val ackedServerAtMs: Long? = null,
    @ColumnInfo(name = "terminal_at_ms") val terminalAtMs: Long? = null,
    /** §13.4: the pairing whose recipient(s) the envelopes were sealed for, so a stale-key
     *  regeneration stays scoped to it. Null for kinds that fan out to every enrollment. */
    @ColumnInfo(name = "recipient_pair_id") val recipientPairId: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_LEASED = "LEASED"
        const val STATUS_WAITING_FOR_RECIPIENT_KEY = "WAITING_FOR_RECIPIENT_KEY"
        const val STATUS_ACKED = "ACKED"
        const val STATUS_OWNER_AUTH_REQUIRED = "OWNER_AUTH_REQUIRED"
        const val STATUS_REENROLL_REQUIRED = "REENROLL_REQUIRED"
        const val STATUS_PERMANENT_REJECTED = "PERMANENT_REJECTED"

        const val WIRE_NOT_SENT = "NOT_SENT"
        const val WIRE_ACCEPTED = "ACCEPTED"
        const val WIRE_ALREADY_ACCEPTED = "ALREADY_ACCEPTED"
        const val WIRE_RECIPIENT_KEY_STALE = "RECIPIENT_KEY_STALE"
        const val WIRE_RETRYABLE = "RETRYABLE"
        const val WIRE_PERMANENT_REJECTED = "PERMANENT_REJECTED"
        const val WIRE_REENROLL_REQUIRED = "REENROLL_REQUIRED"
        const val WIRE_UNKNOWN = "UNKNOWN"

        const val ERROR_PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
        /** Terminal stale: the server authorizes no key this row's pair can encrypt for. */
        const val ERROR_RECIPIENT_KEY_REVOKED = "RECIPIENT_KEY_REVOKED"
        /** Terminal: the row's pair was shredded; nothing queued for it may ever send. */
        const val ERROR_PAIR_REVOKED = "PAIR_REVOKED"

        /** §8.1 retention: ACKED rows live empty for seven days, then are deleted. */
        const val ACKED_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** Terminal rejections erase their content at once; the shell is kept as long. */
        const val REJECTED_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

/** §8.1 persistent capacity incident. One row (id = 1) so the condition cannot be missed. */
@Entity(tableName = "service_outbox_health")
data class ServiceOutboxHealth(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "incident_code") val incidentCode: String,
    @ColumnInfo(name = "raised_at_ms") val raisedAtMs: Long,
    @ColumnInfo(name = "pending_row_count") val pendingRowCount: Int,
    @ColumnInfo(name = "pending_byte_count") val pendingByteCount: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
        const val QUEUE_CAPACITY_EXCEEDED = "QUEUE_CAPACITY_EXCEEDED"
    }
}

/**
 * §8.5 bounded coalescing. A false→true transition is the only moment an append may be
 * scheduled, so a burst of captures cannot build an unbounded chain of work requests.
 * A worker clears it before its first read; anything recorded afterwards re-arms it.
 */
@Entity(tableName = "service_outbox_wake_state")
data class ServiceOutboxWakeState(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "wake_requested") val wakeRequested: Boolean,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
