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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInstallation(state: InstallationState)

    @Query(
        "UPDATE installation_state SET current_sequence = current_sequence + 1 " +
            "WHERE installation_id = :installationId",
    )
    suspend fun bumpSequence(installationId: String)

    @Query("SELECT current_sequence FROM installation_state WHERE installation_id = :installationId")
    suspend fun currentSequence(installationId: String): Long?

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

    @Query("SELECT COUNT(*) FROM pending_partner_events WHERE status != 'ACKED'")
    suspend fun unacknowledgedRowCount(): Int

    @Query(
        "SELECT COALESCE(SUM(" +
            "COALESCE(LENGTH(temporary_local_ciphertext), 0) + " +
            "COALESCE(LENGTH(recipient_envelopes_blob), 0) + " +
            "COALESCE(LENGTH(signed_envelope), 0)), 0) " +
            "FROM pending_partner_events WHERE status != 'ACKED'",
    )
    suspend fun unacknowledgedByteCount(): Long

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
            "signing_key_id = NULL, attempted_wire_digest = NULL, wire_outcome = 'NOT_SENT', " +
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
            "recipient_envelopes_blob = NULL, signed_envelope = NULL WHERE event_id = :eventId",
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHealth(row: ServiceOutboxHealth)

    @Query("SELECT * FROM service_outbox_health WHERE id = 1")
    suspend fun health(): ServiceOutboxHealth?

    @Query("DELETE FROM service_outbox_health")
    suspend fun clearHealth()
}
