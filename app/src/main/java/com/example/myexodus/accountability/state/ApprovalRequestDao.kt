package com.example.myexodus.accountability.state

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * §8 Step 12 exactly-once transitions: every state change is a single-statement compare-and-set
 * guarded on `status = 'REQUESTED'` — a replayed or racing response can never move a row twice
 * (§9 row 459: rejected by the transactional guard, logged, not applied).
 */
@Dao
interface ApprovalRequestDao {

    /** First writer wins; a re-delivered request (mailbox re-drain, Step 11 replay) is a no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(request: ApprovalRequest): Long

    @Query("SELECT * FROM approval_requests WHERE request_event_id = :requestEventId")
    suspend fun byId(requestEventId: String): ApprovalRequest?

    /** Unpair shred completeness (ADR #48 Consequences → Step 14): the ended pair's request rows
     *  die with the rest of its data — a stale REQUESTED row must never outlive the partnership. */
    @Query("DELETE FROM approval_requests WHERE pair_id = :pairId")
    suspend fun deleteByPair(pairId: String)

    @Query(
        "SELECT * FROM approval_requests " +
            "WHERE pair_id = :pairId AND role = :role AND status = 'REQUESTED' ORDER BY created_at DESC",
    )
    suspend fun pendingFor(pairId: String, role: String): List<ApprovalRequest>

    /** Dashboard display: decided/expired history, newest first. */
    @Query("SELECT * FROM approval_requests WHERE pair_id = :pairId ORDER BY created_at DESC LIMIT :limit")
    suspend fun recentFor(pairId: String, limit: Int): List<ApprovalRequest>

    /**
     * Responder decision (AD-16): the expiry filter rides the CAS — the server-timestamp-anchored
     * `expires_at` is authoritative at the instant of the single write. 0 rows changed = already
     * decided, already expired, or unknown: the caller logs and drops (§9 row 459).
     */
    @Query(
        "UPDATE approval_requests SET status = :status, decided_at = :decidedAt " +
            "WHERE request_event_id = :requestEventId AND role = 'RESPONDER' " +
            "AND status = 'REQUESTED' AND expires_at > :nowEpochMillis",
    )
    suspend fun casDecide(requestEventId: String, status: String, decidedAt: Long, nowEpochMillis: Long): Int

    /** Requester applies the wire response. No expiry filter: the responder's server-anchored
     *  decision is the authority; the requester's clock only ever produced display state (AD-16). */
    @Query(
        "UPDATE approval_requests SET status = :status, decided_at = :decidedAt " +
            "WHERE request_event_id = :requestEventId AND role = 'REQUESTER' AND status = 'REQUESTED'",
    )
    suspend fun casApply(requestEventId: String, status: String, decidedAt: Long): Int

    /** Response-create rollback, guarded on [fromStatus] so a racing sweep or duplicate response
     *  can never be clobbered back to REQUESTED. */
    @Query(
        "UPDATE approval_requests SET status = 'REQUESTED', decided_at = NULL " +
            "WHERE request_event_id = :requestEventId AND status = :fromStatus",
    )
    suspend fun casRevert(requestEventId: String, fromStatus: String): Int

    /** 72h sweep: REQUESTED rows past their deadline go EXPIRED. Returns rows changed. */
    @Query(
        "UPDATE approval_requests SET status = 'EXPIRED', decided_at = :nowEpochMillis " +
            "WHERE pair_id = :pairId AND status = 'REQUESTED' AND expires_at <= :nowEpochMillis",
    )
    suspend fun casExpireDue(pairId: String, nowEpochMillis: Long): Int

    /** APPROVED → CONSUMED, the single-use boundary (the Strict Mode gate's one-shot read). */
    @Query(
        "UPDATE approval_requests SET status = 'CONSUMED' " +
            "WHERE request_event_id = :requestEventId AND status = 'APPROVED'",
    )
    suspend fun casConsume(requestEventId: String): Int

    /** Step 17 (AD-22): the enrollment-less global wipe — no pair to scope a delete to. */
    @Query("DELETE FROM approval_requests")
    suspend fun deleteAll()
}
