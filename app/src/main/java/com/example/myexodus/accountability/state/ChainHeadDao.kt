package com.example.myexodus.accountability.state

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update

@Dao
interface ChainHeadDao {

    @Query("SELECT * FROM chain_heads WHERE pair_id = :pairId AND sender_key_id = :senderKeyId")
    suspend fun get(pairId: String, senderKeyId: String): ChainHead?

    /** Step 11: heads with a repair state other than NONE (at most one per pair in practice —
     *  a gap can only arise in the partner's sender namespace). */
    @Query("SELECT * FROM chain_heads WHERE pair_id = :pairId AND repair_state != 'NONE'")
    suspend fun repairHeads(pairId: String): List<ChainHead>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(head: ChainHead)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(head: ChainHead)

    /** §10 bilateral deletion: remove the chain head with its pair data. */
    @Query("DELETE FROM chain_heads WHERE pair_id = :pairId")
    suspend fun deleteByPair(pairId: String)

    /** Step 17 (AD-22): the enrollment-less global wipe — no pair to scope a delete to. */
    @Query("DELETE FROM chain_heads")
    suspend fun deleteAll()
}
