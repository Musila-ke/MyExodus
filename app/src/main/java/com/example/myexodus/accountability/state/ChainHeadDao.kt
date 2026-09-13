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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(head: ChainHead)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(head: ChainHead)

    /** §10 bilateral deletion: remove the chain head with its pair data. */
    @Query("DELETE FROM chain_heads WHERE pair_id = :pairId")
    suspend fun deleteByPair(pairId: String)
}
