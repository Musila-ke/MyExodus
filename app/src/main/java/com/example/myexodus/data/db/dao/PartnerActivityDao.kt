package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.example.myexodus.data.db.entity.PartnerActivityOutbox
import com.example.myexodus.data.db.entity.PartnerActivityProjection
import kotlinx.coroutines.flow.Flow

@Dao
interface PartnerActivityDao {

    // --- Journal + per-pair fan-out links (Steps 5/6, ADR #111 shape) ----------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJournal(row: PartnerActivityOutbox)

    /**
     * Journal rows with no link for THIS pair yet, oldest first. Per-pair: a row pending for one
     * pair stays pending for every pair that has no link yet (§19.5 / ADR #111).
     */
    @Query(
        "SELECT * FROM partner_activity_outbox WHERE NOT EXISTS (" +
            "SELECT 1 FROM partner_activity_link l " +
            "WHERE l.event_id = partner_activity_outbox.event_id AND l.pair_id = :pairId) " +
            "ORDER BY created_at_ms ASC LIMIT :limit",
    )
    suspend fun pendingForPair(pairId: String, limit: Int): List<PartnerActivityOutbox>

    /** Exactly-once per (event, pair): re-linking the same pair is a no-op (ADR #111). */
    @Query(
        "INSERT OR IGNORE INTO partner_activity_link (event_id, pair_id, sequence) " +
            "VALUES (:eventId, :pairId, :sequence)",
    )
    suspend fun linkEvent(eventId: String, pairId: String, sequence: Long)

    @Query("SELECT sequence FROM partner_activity_link WHERE event_id = :eventId AND pair_id = :pairId")
    suspend fun linkSequence(eventId: String, pairId: String): Long?

    @Query("SELECT DISTINCT pair_id FROM partner_activity_link")
    suspend fun linkedPairIds(): List<String>

    /**
     * Retention (FR-15 delta): the journal is a transport buffer on the same 30-day bound as the
     * mailbox TTL — a state snapshot older than that is superseded by any newer event, and a
     * stale motivational message arriving late serves nobody. Bounded batches, oldest first.
     */
    @Query(
        "DELETE FROM partner_activity_outbox WHERE event_id IN (" +
            "SELECT event_id FROM partner_activity_outbox WHERE created_at_ms < :cutoffMs " +
            "ORDER BY created_at_ms ASC LIMIT :batch)",
    )
    suspend fun deleteJournalOlderThan(cutoffMs: Long, batch: Int): Int

    // --- Received-state projection (Step 8, §8.10) ------------------------------------------

    @Query("SELECT * FROM partner_activity_projection WHERE pair_id = :pairId")
    suspend fun projection(pairId: String): PartnerActivityProjection?

    /** Req 15 home card: the newest pinned message across all pairs (`[0]` wins by timestamp).
     *  A Flow, not a one-shot read: a message delivered by a background sync pass must reach a
     *  card that is already on screen. */
    @Query(
        "SELECT * FROM partner_activity_projection WHERE pinned_message_text IS NOT NULL " +
            "ORDER BY pinned_message_at_ms DESC LIMIT 1",
    )
    fun observeLatestPinned(): Flow<PartnerActivityProjection?>

    @Query(
        "INSERT OR IGNORE INTO partner_activity_projection (pair_id, days_free, relapse_count, " +
            "milestones_reached, coaching_progress, last_event_occurred_at_ms, last_sequence, " +
            "pinned_message_text, pinned_message_at_ms) " +
            "VALUES (:pairId, 0, 0, '', 0, 0, 0, NULL, NULL)",
    )
    suspend fun ensureProjectionRow(pairId: String)

    /** Monotonic guard: a replayed snapshot at or below [PartnerActivityProjection.lastSequence]
     *  is a no-op; only the delivered peer stream advances it. */
    @Query(
        "UPDATE partner_activity_projection SET days_free = :daysFree, " +
            "relapse_count = :relapseCount, milestones_reached = :milestonesReached, " +
            "coaching_progress = :coachingProgress, " +
            "last_event_occurred_at_ms = :occurredAtMs, last_sequence = :sequence " +
            "WHERE pair_id = :pairId AND :sequence > last_sequence",
    )
    suspend fun advanceProjection(
        pairId: String,
        daysFree: Int,
        relapseCount: Int,
        milestonesReached: String,
        coachingProgress: Int,
        occurredAtMs: Long,
        sequence: Long,
    ): Int

    /**
     * Wholesale application (§8.10, §4.6): duplicate delivery cannot double-count. A
     * motivational message applies its snapshot through this same call and additionally pins
     * [pinnedText] — one transaction, so a card update and its message can never tear apart.
     */
    @Transaction
    suspend fun applyStateAfter(
        pairId: String,
        daysFree: Int,
        relapseCount: Int,
        milestonesReached: String,
        coachingProgress: Int,
        occurredAtMs: Long,
        sequence: Long,
        pinnedText: String? = null,
        pinnedAtMs: Long? = null,
    ) {
        ensureProjectionRow(pairId)
        advanceProjection(
            pairId, daysFree, relapseCount, milestonesReached, coachingProgress, occurredAtMs, sequence,
        )
        if (pinnedText != null && pinnedAtMs != null) {
            updatePinnedMessage(pairId, pinnedText, pinnedAtMs)
        }
    }

    @Query(
        "UPDATE partner_activity_projection SET pinned_message_text = :text, " +
            "pinned_message_at_ms = :atMs WHERE pair_id = :pairId",
    )
    suspend fun updatePinnedMessage(pairId: String, text: String, atMs: Long): Int

    /** A message can arrive before any state event; the row is created placeholders-first. */
    @Transaction
    suspend fun pinMessage(pairId: String, text: String, atMs: Long) {
        ensureProjectionRow(pairId)
        updatePinnedMessage(pairId, text, atMs)
    }

    // --- Revocation / unpair prune (§8.17 delta) --------------------------------------------

    @Query("SELECT DISTINCT pair_id FROM partner_activity_projection")
    suspend fun projectedPairIds(): List<String>

    @Query("DELETE FROM partner_activity_link WHERE pair_id IN (:pairIds)")
    suspend fun deleteLinksFor(pairIds: List<String>)

    @Query("DELETE FROM partner_activity_projection WHERE pair_id IN (:pairIds)")
    suspend fun deleteProjectionsFor(pairIds: List<String>)
}
