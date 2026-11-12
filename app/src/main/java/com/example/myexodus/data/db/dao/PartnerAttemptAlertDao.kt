package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.example.myexodus.data.db.entity.PartnerAttemptAlert

@Dao
interface PartnerAttemptAlertDao {

    /** A mailbox redelivery collides on (pair, event) and is ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: PartnerAttemptAlert)

    @Query(
        "SELECT * FROM partner_attempt_alert WHERE pair_id = :pairId AND expires_at_ms > :nowMs " +
            "ORDER BY occurred_at_ms DESC LIMIT :limit",
    )
    suspend fun recent(pairId: String, nowMs: Long, limit: Int): List<PartnerAttemptAlert>

    @Query("DELETE FROM partner_attempt_alert WHERE expires_at_ms <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int

    @Query("DELETE FROM partner_attempt_alert WHERE pair_id IN (:pairIds)")
    suspend fun deleteFor(pairIds: List<String>): Int
}
