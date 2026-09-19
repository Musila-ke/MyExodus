package com.example.myexodus.accountability.state

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update

@Dao
interface ChainEventDao {

    /** Returns the generated row id — the outbox row references it (AD-06b). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: ChainEvent): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(event: ChainEvent)

    @Query("SELECT * FROM events WHERE pair_id = :pairId AND sender_key_id = :senderKeyId AND sequence = :sequence")
    suspend fun getBySequence(pairId: String, senderKeyId: String, sequence: Long): ChainEvent?

    /** Transactional outbox: unsent local events, oldest first (§7.3). */
    @Query(
        "SELECT * FROM events WHERE pair_id = :pairId AND direction = 'SENT' " +
            "AND sync_state = 'PENDING' ORDER BY sequence ASC LIMIT :limit",
    )
    suspend fun pendingSentEvents(pairId: String, limit: Int): List<ChainEvent>

    @Query("SELECT count(*) FROM events WHERE pair_id = :pairId")
    suspend fun countByPair(pairId: String): Long

    /** Dashboard read model (Step 12): the partner's events received since a cutoff, newest
     *  first. Bounded — the dashboard summarizes, it is not an archive browser. */
    @Query(
        "SELECT * FROM events WHERE pair_id = :pairId AND direction = 'RECEIVED' " +
            "AND created_at >= :sinceEpochMillis ORDER BY created_at DESC LIMIT 500",
    )
    suspend fun receivedSince(pairId: String, sinceEpochMillis: Long): List<ChainEvent>

    @Query("SELECT * FROM events WHERE pair_id = :pairId ORDER BY sequence ASC")
    suspend fun chainFor(pairId: String): List<ChainEvent>

    /** §9 backfill: sequences still awaiting their missing predecessor, oldest-first. */
    @Query(
        "SELECT sequence FROM events WHERE pair_id = :pairId AND sender_key_id = :senderKeyId " +
            "AND sync_state = 'PENDING_GAP' ORDER BY sequence ASC",
    )
    suspend fun gapSequences(pairId: String, senderKeyId: String): List<Long>

    /** AD-08 terminal gap state (Step 11): the 30-day window lapsed with no recovery. */
    @Query(
        "UPDATE events SET sync_state = 'CHAIN_INCOMPLETE' " +
            "WHERE pair_id = :pairId AND sender_key_id = :senderKeyId AND sync_state = 'PENDING_GAP'",
    )
    suspend fun markGapsChainIncomplete(pairId: String, senderKeyId: String): Int

    /** Step 11 replay source: this sender's own stored events after [afterSequence], in chain order. */
    @Query(
        "SELECT * FROM events WHERE pair_id = :pairId AND sender_key_id = :senderKeyId " +
            "AND direction = 'SENT' AND sequence > :afterSequence ORDER BY sequence ASC LIMIT :limit",
    )
    suspend fun sentEventsAfter(pairId: String, senderKeyId: String, afterSequence: Long, limit: Int): List<ChainEvent>

    /** §10 bilateral deletion: pair data becomes unreadable garbage after ECDH shredding. */
    @Query("DELETE FROM events WHERE pair_id = :pairId")
    suspend fun deleteByPair(pairId: String)

    /** Step 17 (AD-22): the enrollment-less global wipe — no pair to scope a delete to. */
    @Query("DELETE FROM events")
    suspend fun deleteAll()
}
