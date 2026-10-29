package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.example.myexodus.data.db.entity.PartnerRuleChange

@Dao
interface PartnerRuleChangeDao {

    /** Redelivered events collide on (pair, event) and are ignored, never duplicated. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: PartnerRuleChange)

    @Query(
        "SELECT * FROM partner_rule_change WHERE pair_id = :pairId AND expires_at_ms > :nowMs " +
            "ORDER BY occurred_at_minute DESC LIMIT :limit",
    )
    suspend fun recent(pairId: String, nowMs: Long, limit: Int): List<PartnerRuleChange>

    @Query("DELETE FROM partner_rule_change WHERE expires_at_ms <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int

    @Query("DELETE FROM partner_rule_change WHERE pair_id IN (:pairIds)")
    suspend fun deleteFor(pairIds: List<String>): Int
}
