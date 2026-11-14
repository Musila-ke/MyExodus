package com.example.myexodus.accountability.delivery

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/** §8.1 storage for the owner-bound service outbox. Every multi-statement transition goes
 *  through [ServiceEventOutbox]'s `withWriteTransaction` block, never a bare DAO call. */
@Dao
interface ServiceEventOutboxDao {

    @Query("SELECT * FROM installation_state WHERE owner_uid = :ownerUid")
    suspend fun installationForOwner(ownerUid: String): InstallationState?

    @Query("SELECT * FROM installation_state WHERE installation_id = :installationId")
    suspend fun installation(installationId: String): InstallationState?

    @Query(
        "UPDATE installation_state SET active_key_id = :keyId, " +
            "recipient_key_id = :recipientKeyId, registration_state = :state " +
            "WHERE installation_id = :installationId",
    )
    suspend fun updateRegistration(
        installationId: String,
        keyId: String,
        recipientKeyId: String,
        state: String,
    ): Int

    @Query(
        "UPDATE installation_state SET registration_state = :state " +
            "WHERE installation_id = :installationId",
    )
    suspend fun updateRegistrationState(installationId: String, state: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInstallation(state: InstallationState)

    @Query(
        "UPDATE installation_state SET current_sequence = current_sequence + 1 " +
            "WHERE installation_id = :installationId",
    )
    suspend fun bumpSequence(installationId: String)

    @Query("SELECT current_sequence FROM installation_state WHERE installation_id = :installationId")
    suspend fun currentSequence(installationId: String): Long?

    /** §6.3 signed-contact counter — a namespace separate from the event sequence. */
    @Query("SELECT heartbeat_counter FROM installation_state WHERE installation_id = :installationId")
    suspend fun heartbeatCounter(installationId: String): Long?

    @Query(
        "UPDATE installation_state SET heartbeat_counter = :counter " +
            "WHERE installation_id = :installationId",
    )
    suspend fun setHeartbeatCounter(installationId: String, counter: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(row: PendingPartnerEvent)

    @Query("SELECT * FROM pending_partner_events WHERE event_id = :eventId")
    suspend fun event(eventId: String): PendingPartnerEvent?

    /** PENDING only: §6.1 forbids submitting incomplete ciphertext, and a
     *  WAITING_FOR_RECIPIENT_KEY row has none. It becomes READY by regeneration/resume. */
    @Query(
        "SELECT * FROM pending_partner_events " +
            "WHERE owner_uid = :ownerUid AND status = 'PENDING' " +
            "AND (next_attempt_at_ms IS NULL OR next_attempt_at_ms <= :nowMs) " +
            "ORDER BY sequence ASC, event_id ASC LIMIT :limit",
    )
    suspend fun dueRows(ownerUid: String, nowMs: Long, limit: Int): List<PendingPartnerEvent>

    /** Terminal rejections never become deliverable again, so they cannot consume the budget. */
    @Query(
        "SELECT COUNT(*) FROM pending_partner_events " +
            "WHERE status NOT IN ('ACKED', 'PERMANENT_REJECTED')",
    )
    suspend fun unacknowledgedRowCount(): Int

    @Query(
        "SELECT COALESCE(SUM(" +
            "COALESCE(LENGTH(temporary_local_ciphertext), 0) + " +
            "COALESCE(LENGTH(recipient_envelopes_blob), 0) + " +
            "COALESCE(LENGTH(signed_envelope), 0)), 0) " +
            "FROM pending_partner_events WHERE status NOT IN ('ACKED', 'PERMANENT_REJECTED')",
    )
    suspend fun unacknowledgedByteCount(): Long

    /** Process start: a lease cannot outlive the process that took it (§4.3). */
    @Query(
        "UPDATE pending_partner_events SET status = 'PENDING', lease_until_ms = NULL " +
            "WHERE status = 'LEASED'",
    )
    suspend fun resetAllLeases(): Int

    @Query(
        "UPDATE pending_partner_events SET status = 'PENDING', lease_until_ms = NULL " +
            "WHERE status = 'LEASED' AND lease_until_ms IS NOT NULL AND lease_until_ms <= :nowMs",
    )
    suspend fun resetExpiredLeases(nowMs: Long): Int

    /** Guarded claim: only an unleased PENDING row can be taken, once. */
    @Query(
        "UPDATE pending_partner_events SET status = 'LEASED', lease_until_ms = :leaseUntilMs " +
            "WHERE event_id = :eventId AND status = 'PENDING'",
    )
    suspend fun leaseRow(eventId: String, leaseUntilMs: Long): Int

    @Query(
        "UPDATE pending_partner_events SET status = 'PENDING', lease_until_ms = NULL " +
            "WHERE event_id = :eventId AND status = 'LEASED'",
    )
    suspend fun releaseLease(eventId: String): Int

    @Query(
        "SELECT * FROM pending_partner_events " +
            "WHERE owner_uid = :ownerUid AND status = 'WAITING_FOR_RECIPIENT_KEY' " +
            "AND (next_attempt_at_ms IS NULL OR next_attempt_at_ms <= :nowMs) " +
            "ORDER BY sequence ASC, event_id ASC LIMIT :limit",
    )
    suspend fun waitingRows(ownerUid: String, nowMs: Long, limit: Int): List<PendingPartnerEvent>

    /** §14 oldest `WAITING_FOR_RECIPIENT_KEY` metric (null = nothing waiting). */
    @Query(
        "SELECT MIN(created_local_at_ms) FROM pending_partner_events " +
            "WHERE status = 'WAITING_FOR_RECIPIENT_KEY'",
    )
    suspend fun oldestWaitingCreatedAt(): Long?

    @Query(
        "UPDATE pending_partner_events SET next_attempt_at_ms = :nextAttemptAtMs " +
            "WHERE event_id = :eventId AND status = 'WAITING_FOR_RECIPIENT_KEY'",
    )
    suspend fun deferWaitingRow(eventId: String, nextAttemptAtMs: Long): Int

    @Query(
        "UPDATE pending_partner_events SET signed_envelope = :signedEnvelope, " +
            "signing_signature = :signature, signing_key_id = :keyId " +
            "WHERE event_id = :eventId AND status = 'LEASED'",
    )
    suspend fun recordSignature(
        eventId: String,
        signedEnvelope: ByteArray,
        signature: ByteArray,
        keyId: String,
    ): Int

    /** Keeps the attempted bytes/digest for a diagnostic; the row stays resendable unchanged. */
    @Query(
        "UPDATE pending_partner_events SET status = 'PENDING', wire_outcome = :wireOutcome, " +
            "attempts = attempts + 1, next_attempt_at_ms = :nextAttemptAtMs, " +
            "lease_until_ms = NULL, last_error_code = :errorCode, " +
            "attempted_wire_digest = :wireDigest " +
            "WHERE event_id = :eventId AND status = 'LEASED'",
    )
    suspend fun retryRow(
        eventId: String,
        wireOutcome: String,
        nextAttemptAtMs: Long,
        errorCode: String?,
        wireDigest: String?,
    ): Int

    /** Terminal transition: the sensitive source and signature are erased in the same write
     *  that marks the row undeliverable, so no rejected content outlives the outcome. */
    @Query(
        "UPDATE pending_partner_events SET status = 'PERMANENT_REJECTED', " +
            "wire_outcome = 'PERMANENT_REJECTED', lease_until_ms = NULL, " +
            "last_error_code = :errorCode, terminal_at_ms = :terminalAtMs, " +
            "temporary_local_ciphertext = NULL, recipient_envelopes_blob = NULL, " +
            "signed_envelope = NULL, signing_signature = NULL " +
            "WHERE event_id = :eventId AND status = 'LEASED'",
    )
    suspend fun permanentRejectRow(eventId: String, errorCode: String?, terminalAtMs: Long): Int

    /**
     * §8.1 terminal stale: the server's authorized set named none of the row's local keys, so
     * every resend would earn the same rejection. Guarded on the stale outcome the rejection
     * just recorded — a concurrent ACK or regeneration still wins.
     */
    @Query(
        "UPDATE pending_partner_events SET status = 'PERMANENT_REJECTED', " +
            "wire_outcome = 'PERMANENT_REJECTED', lease_until_ms = NULL, " +
            "last_error_code = :errorCode, terminal_at_ms = :terminalAtMs, " +
            "temporary_local_ciphertext = NULL, recipient_envelopes_blob = NULL, " +
            "signed_envelope = NULL, signing_signature = NULL " +
            "WHERE event_id = :eventId AND status IN ('PENDING', 'LEASED') " +
            "AND wire_outcome = 'RECIPIENT_KEY_STALE'",
    )
    suspend fun rejectRevokedRecipientKeyRow(
        eventId: String,
        errorCode: String,
        terminalAtMs: Long,
    ): Int

    /** Step 10 pair shred: anything queued for the revoked pair is undeliverable from the
     *  moment its keys are destroyed, and must never be resendable afterwards. */
    @Query(
        "UPDATE pending_partner_events SET status = 'PERMANENT_REJECTED', " +
            "wire_outcome = 'PERMANENT_REJECTED', lease_until_ms = NULL, " +
            "last_error_code = :errorCode, terminal_at_ms = :terminalAtMs, " +
            "temporary_local_ciphertext = NULL, recipient_envelopes_blob = NULL, " +
            "signed_envelope = NULL, signing_signature = NULL " +
            "WHERE recipient_pair_id = :pairId " +
            "AND status NOT IN ('ACKED', 'PERMANENT_REJECTED')",
    )
    suspend fun rejectPairRows(pairId: String, errorCode: String, terminalAtMs: Long): Int

    /** §8.3.8: key loss suspends the whole installation; rows are preserved, never dropped. */
    @Query(
        "UPDATE pending_partner_events SET status = 'REENROLL_REQUIRED', lease_until_ms = NULL " +
            "WHERE installation_id = :installationId AND status IN ('PENDING', 'LEASED')",
    )
    suspend fun suspendForReenroll(installationId: String): Int

    /**
     * §8.3.8 resume: identity, sequence and occurrence time survive; only the signature is
     * rewritten. An UNKNOWN outcome keeps its marker so regeneration stays blocked until the
     * server definitively answers — the digest covers the event payload, so a re-signed
     * resend still dedupes.
     */
    @Query(
        "UPDATE pending_partner_events SET signed_envelope = NULL, signing_signature = NULL, " +
            "signing_key_id = NULL, lease_until_ms = NULL, next_attempt_at_ms = NULL, " +
            "last_error_code = NULL, " +
            "wire_outcome = CASE WHEN wire_outcome = 'UNKNOWN' THEN 'UNKNOWN' ELSE 'NOT_SENT' END, " +
            "attempted_wire_digest = CASE WHEN wire_outcome = 'UNKNOWN' " +
            "THEN attempted_wire_digest ELSE NULL END, " +
            "status = CASE WHEN recipient_envelopes_blob IS NULL " +
            "THEN 'WAITING_FOR_RECIPIENT_KEY' ELSE 'PENDING' END " +
            "WHERE installation_id = :installationId AND status = 'REENROLL_REQUIRED'",
    )
    suspend fun resumeAfterReenroll(installationId: String): Int

    /** A row with no defer is due now — 0, not NULL, or a crash between commit and work
     *  scheduling would leave it invisible to startup reconciliation. */
    @Query(
        "SELECT MIN(COALESCE(next_attempt_at_ms, 0)) FROM pending_partner_events " +
            "WHERE owner_uid = :ownerUid " +
            "AND status IN ('PENDING', 'WAITING_FOR_RECIPIENT_KEY')",
    )
    suspend fun earliestNextAttempt(ownerUid: String): Long?

    /**
     * §8.1 stale-recipient-key regeneration. The WHERE clause is the safety rule: only a row
     * that was never submitted or was definitively rejected with no effect may have its
     * ciphertext and signature replaced. A concurrent ACK wins — zero rows updated.
     * `envelopes == null` records the still-waiting state.
     */
    @Query(
        "UPDATE pending_partner_events SET recipient_envelopes_blob = :envelopes, " +
            "status = CASE WHEN :envelopes IS NULL " +
            "THEN 'WAITING_FOR_RECIPIENT_KEY' ELSE 'PENDING' END, " +
            "recipient_key_revision = :revision, signed_envelope = NULL, " +
            "signing_signature = NULL, signing_key_id = NULL, attempted_wire_digest = NULL, " +
            "wire_outcome = 'NOT_SENT', " +
            "next_attempt_at_ms = NULL, last_error_code = NULL " +
            "WHERE event_id = :eventId AND status != 'ACKED' " +
            "AND wire_outcome IN ('NOT_SENT', 'RECIPIENT_KEY_STALE')",
    )
    suspend fun replaceRecipientEnvelopes(eventId: String, envelopes: ByteArray?, revision: Int?): Int

    /** §6.1 step 8 / §8.1: acknowledgement erases every locally retained ciphertext of the
     *  sensitive source; the identity row survives for diagnostics and the retention window. */
    @Query(
        "UPDATE pending_partner_events SET status = 'ACKED', wire_outcome = :outcome, " +
            "acked_server_at_ms = :ackedAtMs, temporary_local_ciphertext = NULL, " +
            "recipient_envelopes_blob = NULL, signed_envelope = NULL, signing_signature = NULL " +
            "WHERE event_id = :eventId",
    )
    suspend fun markAcked(eventId: String, outcome: String, ackedAtMs: Long): Int

    @Query(
        "UPDATE pending_partner_events SET status = 'OWNER_AUTH_REQUIRED' " +
            "WHERE owner_uid != :activeOwnerUid " +
            "AND status IN ('PENDING', 'WAITING_FOR_RECIPIENT_KEY')",
    )
    suspend fun suspendOtherOwners(activeOwnerUid: String): Int

    @Query(
        "UPDATE pending_partner_events SET status = CASE " +
            "WHEN recipient_envelopes_blob IS NULL THEN 'WAITING_FOR_RECIPIENT_KEY' " +
            "ELSE 'PENDING' END " +
            "WHERE owner_uid = :ownerUid AND status = 'OWNER_AUTH_REQUIRED'",
    )
    suspend fun resumeOwner(ownerUid: String): Int

    /** Bounded (§8.1): the subselect caps one cleanup transaction's work. */
    @Query(
        "DELETE FROM pending_partner_events WHERE event_id IN (" +
            "SELECT event_id FROM pending_partner_events WHERE status = 'ACKED' " +
            "AND acked_server_at_ms IS NOT NULL AND acked_server_at_ms < :cutoffMs LIMIT :limit)",
    )
    suspend fun deleteAckedBefore(cutoffMs: Long, limit: Int): Int

    /** Bounded (§8.1) terminal-shell cleanup; the content was erased at the rejection. */
    @Query(
        "DELETE FROM pending_partner_events WHERE event_id IN (" +
            "SELECT event_id FROM pending_partner_events WHERE status = 'PERMANENT_REJECTED' " +
            "AND terminal_at_ms IS NOT NULL AND terminal_at_ms < :cutoffMs LIMIT :limit)",
    )
    suspend fun deleteRejectedBefore(cutoffMs: Long, limit: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHealth(row: ServiceOutboxHealth)

    @Query("SELECT * FROM service_outbox_health WHERE id = 1")
    suspend fun health(): ServiceOutboxHealth?

    @Query("DELETE FROM service_outbox_health")
    suspend fun clearHealth()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureWakeRow(row: ServiceOutboxWakeState): Long

    /** Returns 1 only for the false→true transition; that is the single append permit. */
    @Query(
        "UPDATE service_outbox_wake_state SET wake_requested = 1 " +
            "WHERE id = 1 AND wake_requested = 0",
    )
    suspend fun armWake(): Int

    @Query("UPDATE service_outbox_wake_state SET wake_requested = 0 WHERE id = 1")
    suspend fun clearWake(): Int
}
