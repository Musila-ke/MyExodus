package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.example.myexodus.data.db.entity.PartnerEventJournal

/**
 * Step 4 journal access (plan §8.9). [insert] is called inside the detector's own domain
 * write transaction — a rolled-back transition must leave no event behind, exactly like the
 * activity journal ([PartnerActivityDao]).
 *
 * [dueForDelivery] is the reconcile work list: rows created at or after the relationship's
 * enrollment ([sinceMs]; a re-enrollment therefore never sees pre-pairing rows) and not yet
 * expired. Whether a particular pair already received a row is decided by
 * `events.event_id` + the unique `(pair_id, sender_key_id, event_id)` index — there is no
 * separate link table to drift.
 */
@Dao
interface PartnerEventJournalDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: PartnerEventJournal)

    @Query("SELECT * FROM partner_event_journal WHERE event_id = :eventId")
    suspend fun byId(eventId: String): PartnerEventJournal?

    @Query(
        "SELECT * FROM partner_event_journal WHERE created_at_ms >= :sinceMs " +
            "AND expires_at_ms > :nowMs ORDER BY created_at_ms ASC LIMIT :limit",
    )
    suspend fun dueForDelivery(sinceMs: Long, nowMs: Long, limit: Int): List<PartnerEventJournal>

    /** §8.4 rule-scoped route: rule-lifecycle rows for one assigned pair, consent-independent. */
    @Query(
        "SELECT * FROM partner_event_journal WHERE route_pair_id = :pairId " +
            "AND created_at_ms >= :sinceMs AND expires_at_ms > :nowMs " +
            "ORDER BY created_at_ms ASC LIMIT :limit",
    )
    suspend fun dueRuleChangesForPair(
        pairId: String,
        sinceMs: Long,
        nowMs: Long,
        limit: Int,
    ): List<PartnerEventJournal>

    /** §8.7 retention sweep: the body detail goes after its 30-day window. */
    @Query("DELETE FROM partner_event_journal WHERE expires_at_ms <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int
}
