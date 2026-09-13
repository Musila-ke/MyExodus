package com.example.myexodus.accountability.state

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update

@Dao
interface ChainEventDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: ChainEvent)

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

    @Query("SELECT * FROM events WHERE pair_id = :pairId ORDER BY sequence ASC")
    suspend fun chainFor(pairId: String): List<ChainEvent>

    /** §9 gap terminal state: stale PENDING_GAP rows older than :olderThanEpochMillis become PERMANENTLY_INCOMPLETE. */
    @Query(
        "UPDATE events SET sync_state = 'PERMANENTLY_INCOMPLETE' " +
            "WHERE pair_id = :pairId AND sync_state = 'PENDING_GAP' AND created_at < :olderThanEpochMillis",
    )
    suspend fun expireGaps(pairId: String, olderThanEpochMillis: Long): Int

    /** §9 backfill: sequences still awaiting their missing predecessor, oldest-first. */
    @Query(
        "SELECT sequence FROM events WHERE pair_id = :pairId " +
            "AND sync_state = 'PENDING_GAP' ORDER BY sequence ASC",
    )
    suspend fun gapSequences(pairId: String): List<Long>

    /** §10 bilateral deletion: pair data becomes unreadable garbage after ECDH shredding. */
    @Query("DELETE FROM events WHERE pair_id = :pairId")
    suspend fun deleteByPair(pairId: String)
}
