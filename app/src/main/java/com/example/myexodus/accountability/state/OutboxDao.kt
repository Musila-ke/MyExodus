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

    /** Step 9 drain projection: the stored bytes plus the event metadata the mailbox document
     *  carries (`senderId` = chain-scoping key id, `keyEpoch`). */
    @Query(
        "SELECT o.message_id, o.envelope_bytes, e.sender_key_id, e.epoch " +
            "FROM outbox o INNER JOIN events e ON e.id = o.event_id " +
            "WHERE e.pair_id = :pairId AND o.status = 'PENDING' ORDER BY e.sequence ASC LIMIT :limit",
    )
    suspend fun pendingForDrain(pairId: String, limit: Int): List<PendingOutboxRow>

    @Query("UPDATE outbox SET status = 'SENT' WHERE message_id = :messageId")
    suspend fun markSent(messageId: String)

    /** The Step 13 unpair journal snapshots this row before the wipe deletes it: the journal
     *  must outlive the outbox (and the enrollment behind [OutboxRowSnapshot.senderKeyId]) and
     *  carry everything a post-shred re-delivery of that one message needs. */
    @Query(
        "SELECT o.status, o.envelope_bytes, e.sender_key_id, e.epoch " +
            "FROM outbox o INNER JOIN events e ON e.id = o.event_id WHERE o.message_id = :messageId",
    )
    suspend fun snapshotOf(messageId: String): OutboxRowSnapshot?

    @Query("UPDATE outbox SET attempt_count = attempt_count + 1, last_attempt_at = :atEpochMillis WHERE message_id = :messageId")
    suspend fun recordAttempt(messageId: String, atEpochMillis: Long)

    /**
     * Step 18: sent rows whose mailbox document still owes a relay-marker read-back — never
     * checked, or last checked inside the settle window. The window, not a single look, is
     * the design: the relay runs asynchronously and a partner wake can trigger a sync pass
     * seconds after a drain, so "no marker yet" is inconclusive until the relay's own
     * latency has certainly elapsed.
     */
    @Query(
        "SELECT o.message_id FROM outbox o INNER JOIN events e ON e.id = o.event_id " +
            "WHERE e.pair_id = :pairId AND o.status = 'SENT' AND o.relay_rejection IS NULL " +
            "AND (o.relay_checked_at IS NULL OR o.relay_checked_at > :settleBeforeEpochMillis) " +
            "LIMIT :limit",
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
