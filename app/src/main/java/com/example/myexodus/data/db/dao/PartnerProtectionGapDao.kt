package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.example.myexodus.data.db.entity.PartnerProtectionGap

@Dao
interface PartnerProtectionGapDao {

    /** A redelivered snapshot collides on (pair, event, capability) and is ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: PartnerProtectionGap)

    @Query(
        "SELECT * FROM partner_protection_gap WHERE pair_id = :pairId AND expires_at_ms > :nowMs " +
            "ORDER BY occurred_at_ms DESC LIMIT :limit",
    )
    suspend fun recent(pairId: String, nowMs: Long, limit: Int): List<PartnerProtectionGap>

    @Query("DELETE FROM partner_protection_gap WHERE expires_at_ms <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int

    @Query("DELETE FROM partner_protection_gap WHERE pair_id IN (:pairIds)")
    suspend fun deleteFor(pairIds: List<String>): Int
}
