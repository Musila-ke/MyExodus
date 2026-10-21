package com.example.myexodus.accountability.state

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * §8 Step 13 unpair journal access. Rows are keyed by pairId — one unpair per pair, and a
 * re-pair of the same two devices reuses the same pairId (AD-07: the epoch increments), so a
 * fresh enrollment invalidates a stale row rather than colliding with it
 * ([com.example.myexodus.accountability.BilateralDeletion.resumePending] drops those).
 */
@Dao
interface UnpairStateDao {

    @Query("SELECT * FROM unpair_state ORDER BY requested_at ASC")
    suspend fun all(): List<UnpairState>

    @Query("SELECT * FROM unpair_state WHERE pair_id = :pairId")
    suspend fun get(pairId: String): UnpairState?

    /** The initiator's row is written once and then advances flag-by-flag; everyone else is a
     *  full-row replace (the responder's row is written before its shred and updated by the
     *  ack path). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: UnpairState)

    @Query("UPDATE unpair_state SET delivered = 1 WHERE pair_id = :pairId")
    suspend fun markDelivered(pairId: String)

    @Query("UPDATE unpair_state SET ack_sent = 1 WHERE pair_id = :pairId")
    suspend fun markAckSent(pairId: String)

    @Query("UPDATE unpair_state SET local_shredded = 1 WHERE pair_id = :pairId")
    suspend fun markShredded(pairId: String)

    @Query("UPDATE unpair_state SET confirmed_at = :atEpochMillis WHERE pair_id = :pairId")
    suspend fun markConfirmed(pairId: String, atEpochMillis: Long)

    /** Step 10: first acknowledgement wins — a later pass must not rewrite the timestamp. */
    @Query("UPDATE unpair_state SET revoked_at = COALESCE(revoked_at, :atEpochMillis) WHERE pair_id = :pairId")
    suspend fun markRevoked(pairId: String, atEpochMillis: Long)

    /** User dismissed a terminal card (or a stale row was superseded by a re-pair). */
    @Query("DELETE FROM unpair_state WHERE pair_id = :pairId")
    suspend fun delete(pairId: String)
}
