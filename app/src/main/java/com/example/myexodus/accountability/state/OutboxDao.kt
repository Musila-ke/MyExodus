package com.example.myexodus.accountability.state

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/** Step 9 drain row: the outbox bytes plus the event metadata the mailbox document needs
 *  (`senderId` = chain-scoping key id, `keyEpoch`). */
data class PendingOutboxRow(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "envelope_bytes") val envelopeBytes: ByteArray,
    @ColumnInfo(name = "sender_key_id") val senderKeyId: String,
    @ColumnInfo(name = "epoch") val epoch: Int,
    /** §8.5 backoff basis: how many attempts this row has already had. */
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
)

/** One outbox row plus the event metadata its mailbox document carries — the Step 13 unpair
 *  journal's pre-wipe snapshot (see [OutboxDao.snapshotOf]). */
data class OutboxRowSnapshot(
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "envelope_bytes") val envelopeBytes: ByteArray,
    @ColumnInfo(name = "sender_key_id") val senderKeyId: String,
    @ColumnInfo(name = "epoch") val epoch: Int,
)

/** Step 18 rejection check: a sent message whose mailbox document still owes one read-back. */
data class UncheckedSentRow(
    @ColumnInfo(name = "message_id") val messageId: String,
)

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: Outbox)

    /** Step 9 drain: unsent rows for the pair, in chain order. */
    @Query(
        "SELECT o.* FROM outbox o INNER JOIN events e ON e.id = o.event_id " +
            "WHERE e.pair_id = :pairId AND o.status = 'PENDING' ORDER BY e.sequence ASC LIMIT :limit",
    )
    suspend fun pendingFor(pairId: String, limit: Int): List<Outbox>

    /**
     * Step 9 drain projection: the stored bytes plus the event metadata the mailbox document
     * carries (`senderId` = chain-scoping key id, `keyEpoch`). Step 4 adds the due filter
     * (§8.5 backoff): only rows whose `next_attempt_at` has passed, and never a row past its
     * retention window even if the expiry stamp itself did not land.
     */
    @Query(
        "SELECT o.message_id, o.envelope_bytes, e.sender_key_id, e.epoch, o.attempt_count " +
            "FROM outbox o INNER JOIN events e ON e.id = o.event_id " +
            "WHERE e.pair_id = :pairId AND o.status = 'PENDING' " +
            "AND o.next_attempt_at <= :nowEpochMillis " +
            "AND (o.expires_at = 0 OR o.expires_at > :nowEpochMillis) " +
            "ORDER BY e.sequence ASC LIMIT :limit",
    )
    suspend fun pendingForDrain(
        pairId: String,
        nowEpochMillis: Long,
        limit: Int,
    ): List<PendingOutboxRow>

    @Query("UPDATE outbox SET status = 'SENT' WHERE message_id = :messageId")
    suspend fun markSent(messageId: String)

    /**
     * Step 10 (§8.4 `CANCELLED_BY_REVOCATION`): revocation ends delivery for everything the
     * pair has not already uploaded. Only PENDING rows move — a SENT row already lives on the
     * relay and is outside the sender's hands; an EXPIRED row is already terminal. Returns
     * the number of cancelled rows.
     */
    @Query(
        "UPDATE outbox SET status = 'CANCELLED_BY_REVOCATION' WHERE status = 'PENDING' " +
            "AND event_id IN (SELECT id FROM events WHERE pair_id = :pairId)",
    )
    suspend fun cancelPendingByPair(pairId: String): Int

    /** §8.2 pre-upload recheck: is this exact row still due for delivery? False when a
     *  concurrent revocation cancelled it (or a concurrent drainer already sent it). */
    @Query("SELECT COUNT(*) FROM outbox WHERE message_id = :messageId AND status = 'PENDING'")
    suspend fun isStillPending(messageId: String): Int

    /** The Step 13 unpair journal snapshots this row before the wipe deletes it: the journal
     *  must outlive the outbox (and the enrollment behind [OutboxRowSnapshot.senderKeyId]) and
     *  carry everything a post-shred re-delivery of that one message needs. */
    @Query(
        "SELECT o.status, o.envelope_bytes, e.sender_key_id, e.epoch " +
            "FROM outbox o INNER JOIN events e ON e.id = o.event_id WHERE o.message_id = :messageId",
    )
    suspend fun snapshotOf(messageId: String): OutboxRowSnapshot?

    /** §8.5: persists the retry metadata a later launch needs to resume with backoff. */
    @Query(
        "UPDATE outbox SET attempt_count = attempt_count + 1, last_attempt_at = :atEpochMillis, " +
            "next_attempt_at = :nextAttemptAtEpochMillis, last_error_code = :errorCode " +
            "WHERE message_id = :messageId",
    )
    suspend fun recordAttempt(
        messageId: String,
        atEpochMillis: Long,
        nextAttemptAtEpochMillis: Long,
        errorCode: String?,
    )

    /**
     * §8.7 retention/eviction. A PENDING row past its window becomes EXPIRED — the delivery
     * state is recorded, never a silent delete. The ciphertext bytes are cleared in both
     * PENDING and SENT rows once the window passes: the relay's own copy dies on the same
     * 30-day cap, so the local bytes can no longer deliver or repair anything. Rows with
     * `expires_at = 0` predate Step 4 and are left alone (migration 12→13 backfills them).
     */
    @Query(
        "UPDATE outbox SET " +
            "status = CASE WHEN status = 'PENDING' THEN 'EXPIRED' ELSE status END, " +
            "envelope_bytes = CASE WHEN length(envelope_bytes) > 0 THEN x'' ELSE envelope_bytes END " +
            "WHERE expires_at > 0 AND expires_at <= :nowEpochMillis",
    )
    suspend fun expireDue(nowEpochMillis: Long): Int

    /**
     * Step 18: sent rows whose mailbox document still owes a relay-marker read-back — never
     * checked, or last checked inside the last hour (ADR #53's due formula: the relay runs
     * asynchronously and a partner wake can trigger a sync pass seconds after a drain, so a
     * single no-marker look would be a false negative; re-checks make the read-back
     * self-healing). A checked row therefore keeps costing one read per pass while checks
     * continue, and drops out only after an hour without one.
     *
     * Ordered least-recently-checked first — SQLite sorts NULLs first under ASC, so a
     * never-checked row always outranks re-checks. Under `LIMIT` this guarantees fresh rows
     * get their first look even when an hour's worth of due rows exceeds the batch, and
     * re-stamping rotates the tail forward so no due row starves.
     */
    @Query(
        "SELECT o.message_id FROM outbox o INNER JOIN events e ON e.id = o.event_id " +
            "WHERE e.pair_id = :pairId AND o.status = 'SENT' AND o.relay_rejection IS NULL " +
            "AND (o.relay_checked_at IS NULL OR o.relay_checked_at > :settleBeforeEpochMillis) " +
            "ORDER BY o.relay_checked_at ASC LIMIT :limit",
    )
    suspend fun relayCheckDue(pairId: String, settleBeforeEpochMillis: Long, limit: Int): List<UncheckedSentRow>

    @Query("UPDATE outbox SET relay_checked_at = :atEpochMillis, relay_rejection = :rejection WHERE message_id = :messageId")
    suspend fun stampRelayCheck(messageId: String, rejection: String?, atEpochMillis: Long)

    /** Step 18 surfacing: rejections discovered within the window (by check time — the honest
     *  basis, since the relay marker carries no timestamp this client can order by). */
    @Query(
        "SELECT COUNT(*) FROM outbox o INNER JOIN events e ON e.id = o.event_id " +
            "WHERE e.pair_id = :pairId AND o.relay_rejection IS NOT NULL " +
            "AND o.relay_checked_at >= :sinceEpochMillis",
    )
    suspend fun rejectionCountSince(pairId: String, sinceEpochMillis: Long): Int

    /** §14 outbox-age metric: the oldest still-PENDING row's creation time (null = nothing
     *  owed). The age distribution is about time-in-PENDING, which is exactly now minus this. */
    @Query("SELECT MIN(created_at) FROM outbox WHERE status = 'PENDING'")
    suspend fun oldestPendingCreatedAt(): Long?

    /**
     * §10 bilateral deletion: outbound rows die with their chain events. Must run BEFORE the
     * events delete — the subquery reads the events table.
     */
    @Query("DELETE FROM outbox WHERE event_id IN (SELECT id FROM events WHERE pair_id = :pairId)")
    suspend fun deleteByPair(pairId: String)

    /** Step 17 (AD-22): the enrollment-less global wipe — an outbox row without a live
     *  enrollment can never legitimately drain (the drainer is enrollment-gated). */
    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}
