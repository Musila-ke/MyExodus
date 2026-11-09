package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.data.db.entity.PartnerActivityOutbox
import com.example.myexodus.data.db.entity.PartnerActivityProjection
import com.example.myexodus.data.db.entity.PartnerStreakProjection
import kotlinx.coroutines.flow.Flow

@Dao
interface PartnerActivityDao {

    // --- Journal + per-pair fan-out links (Steps 5/6, ADR #111 shape) ----------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJournal(row: PartnerActivityOutbox)

    /**
     * Journal rows deliverable to THIS pair, oldest first — consent-aware since v16
     * (ADR #135). Per-pair: a row pending for one pair stays pending for every pair that has
     * no link yet (§19.5 / ADR #111).
     *
     * Addressing (#96, schema v14): a row with a null target fans out (state snapshots); a
     * targeted row — a Req 15 message or the enable-time snapshot — is pending for that pair
     * alone, so no other supported partner can ever be handed it.
     *
     * [sinceMs] is the pair's enrollment time (owner ruling 2026-09-29): a newcomer gets the
     * pairing-time snapshot (written at pair-complete, so on or after this bound) and
     * everything after it — never the pre-pairing backlog.
     *
     * The CASE is the per-(pair, habit, kind) consent rule, evaluated in SQL so a filtered
     * row can never stall the batch window (the why lives in ADR #135):
     *  - habit kinds need the pair's streak master for the row's own habit, plus the kind's
     *    own switch, plus the row's write time at/after the switch's enable watermark —
     *    events journaled while the switch was off are never delivered retroactively;
     *  - habit-kind rows with a null habit anchor (pre-v16 journal) match no branch and are
     *    skipped;
     *  - coaching needs the pair's recovery switch (watermarked) and a visible habit — its
     *    snapshot must anchor somewhere truthful;
     *  - the motivational message needs nothing (ADR #285): the user pressed send for THIS
     *    pair, and the text carries no state anyone could have withheld. With no visible
     *    habit it ships snapshot-less; the dashboard never changes.
     */
    @Query(
        "SELECT * FROM partner_activity_outbox o WHERE o.created_at_ms >= :sinceMs " +
            "AND NOT EXISTS (SELECT 1 FROM partner_activity_link l " +
            "WHERE l.event_id = o.event_id AND l.pair_id = :pairId) " +
            "AND (o.target_pair_id IS NULL OR o.target_pair_id = :pairId) " +
            "AND CASE o.kind " +
            // Owner directive 2026-11-09: the add/rename acts ride the streak master and its
            // watermark exactly like the daily snapshot they replace or annotate. (Simple CASE
            // compares values, so each kind needs its own branch; an IN here would compare
            // o.kind to 0/1 and silently fall through to ELSE.)
            "WHEN '${EventPayload.TYPE_STREAK_UPDATED}' THEN EXISTS (" +
            "SELECT 1 FROM pair_streak_sharing s " +
            "WHERE s.pair_id = :pairId AND s.habit_progress_id = o.habit_progress_id " +
            "AND s.share_streak = 1 AND o.created_at_ms >= s.streak_enabled_since_ms) " +
            "WHEN '${EventPayload.TYPE_STREAK_SHARED}' THEN EXISTS (" +
            "SELECT 1 FROM pair_streak_sharing s " +
            "WHERE s.pair_id = :pairId AND s.habit_progress_id = o.habit_progress_id " +
            "AND s.share_streak = 1 AND o.created_at_ms >= s.streak_enabled_since_ms) " +
            "WHEN '${EventPayload.TYPE_STREAK_RENAMED}' THEN EXISTS (" +
            "SELECT 1 FROM pair_streak_sharing s " +
            "WHERE s.pair_id = :pairId AND s.habit_progress_id = o.habit_progress_id " +
            "AND s.share_streak = 1 AND o.created_at_ms >= s.streak_enabled_since_ms) " +
            "WHEN '${EventPayload.TYPE_MILESTONE_REACHED}' THEN EXISTS (" +
            "SELECT 1 FROM pair_streak_sharing s " +
            "WHERE s.pair_id = :pairId AND s.habit_progress_id = o.habit_progress_id " +
            "AND s.share_streak = 1 AND s.share_milestones = 1 " +
            "AND o.created_at_ms >= s.milestone_enabled_since_ms) " +
            "WHEN '${EventPayload.TYPE_RELAPSE_RECORDED}' THEN EXISTS (" +
            "SELECT 1 FROM pair_streak_sharing s " +
            "WHERE s.pair_id = :pairId AND s.habit_progress_id = o.habit_progress_id " +
            "AND s.share_streak = 1 AND s.share_relapses = 1 " +
            "AND o.created_at_ms >= s.relapse_enabled_since_ms) " +
            "WHEN '${EventPayload.TYPE_COACHING_STEP_UPDATED}' THEN EXISTS (" +
            "SELECT 1 FROM pair_profile_sharing p WHERE p.pair_id = :pairId " +
            "AND p.share_recovery = 1 AND o.created_at_ms >= p.recovery_enabled_since_ms) " +
            "AND EXISTS (SELECT 1 FROM pair_streak_sharing s " +
            "WHERE s.pair_id = :pairId AND s.share_streak = 1) " +
            "WHEN '${EventPayload.TYPE_MOTIVATIONAL_MESSAGE}' THEN 1 " +
            // Owner ruling 2026-11-09: a streak revoke must land after share_streak was
            // switched off — existence of the consent row is the only condition.
            "WHEN '${EventPayload.TYPE_STREAK_SHARING_STOPPED}' THEN EXISTS (" +
            "SELECT 1 FROM pair_streak_sharing s " +
            "WHERE s.pair_id = :pairId AND s.habit_progress_id = o.habit_progress_id) " +
            // STREAK_DELETED lands here: always pair-targeted (the outer clause is its whole
            // gate) because its consent rows are gone by delivery time.
            "ELSE 1 END " +
            "ORDER BY o.created_at_ms ASC LIMIT :limit",
    )
    suspend fun pendingForPair(pairId: String, sinceMs: Long, limit: Int): List<PartnerActivityOutbox>

    /**
     * Owner ask 2026-10-01: the sender's own latest composed message for this pair — what the
     * Activity subpage shows back to the writer. The composer addresses rows (#96), so the
     * common case is the targeted branch; a pre-#96 fan-out row counts once this pair was
     * actually linked, never merely because it exists.
     */
    @Query(
        "SELECT text FROM partner_activity_outbox WHERE kind = :kind " +
            "AND (target_pair_id = :pairId OR (target_pair_id IS NULL AND EXISTS (" +
            "SELECT 1 FROM partner_activity_link l " +
            "WHERE l.event_id = partner_activity_outbox.event_id AND l.pair_id = :pairId))) " +
            "ORDER BY created_at_ms DESC LIMIT 1",
    )
    suspend fun latestSentMessageText(pairId: String, kind: String): String?

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
        "DELETE FROM partner_activity_link WHERE event_id IN (" +
            "SELECT event_id FROM partner_activity_outbox WHERE created_at_ms < :cutoffMs " +
            "ORDER BY created_at_ms ASC LIMIT :batch)",
    )
    suspend fun deleteLinksForExpired(cutoffMs: Long, batch: Int): Int

    @Query(
        "DELETE FROM partner_activity_outbox WHERE event_id IN (" +
            "SELECT event_id FROM partner_activity_outbox WHERE created_at_ms < :cutoffMs " +
            "ORDER BY created_at_ms ASC LIMIT :batch)",
    )
    suspend fun deleteExpiredJournalRows(cutoffMs: Long, batch: Int): Int

    /** Retention as one unit (finding #63): the links of an expired journal row die with it.
     *  Deleting the journal alone left one link per (event, pair) behind forever — the tables
     *  are only ever joined by these queries, so the orphans were harmless but unbounded.
     *  Both statements select the same batch; the outbox is untouched by the link delete. */
    @Transaction
    suspend fun deleteJournalOlderThan(cutoffMs: Long, batch: Int): Int {
        deleteLinksForExpired(cutoffMs, batch)
        return deleteExpiredJournalRows(cutoffMs, batch)
    }

    // --- Received-state projection (Step 8, §8.10) ------------------------------------------

    @Query("SELECT * FROM partner_activity_projection WHERE pair_id = :pairId")
    suspend fun projection(pairId: String): PartnerActivityProjection?

    /** Req 15 home card (#12): every pair's newest pinned message, newest first — with several
     *  partners the card pages across senders, so the whole list is handed over, not just the
     *  winner. A Flow, not a one-shot read: a message delivered by a background sync pass must
     *  reach a card that is already on screen. The pair-id tiebreak keeps the order stable when
     *  two arrivals share a millisecond (clock skew between senders makes that real). */
    @Query(
        "SELECT * FROM partner_activity_projection WHERE pinned_message_text IS NOT NULL " +
            "ORDER BY pinned_message_at_ms DESC, pair_id ASC",
    )
    fun observePinnedMessages(): Flow<List<PartnerActivityProjection>>

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

    /** The pin's own monotonic guard, matching [advanceProjection]'s: a message redelivered
     *  late cannot roll the card back to older text. Equal times re-apply (a duplicate
     *  delivery of the same message is idempotent, §4.6). */
    @Query(
        "UPDATE partner_activity_projection SET pinned_message_text = :text, " +
            "pinned_message_at_ms = :atMs WHERE pair_id = :pairId " +
            "AND (pinned_message_at_ms IS NULL OR :atMs >= pinned_message_at_ms)",
    )
    suspend fun updatePinnedMessage(pairId: String, text: String, atMs: Long): Int

    /** A message can arrive before any state event; the row is created placeholders-first. */
    @Transaction
    suspend fun pinMessage(pairId: String, text: String, atMs: Long) {
        ensureProjectionRow(pairId)
        updatePinnedMessage(pairId, text, atMs)
    }

    // --- Per-streak projection (owner directive 2026-11-09) ---------------------------------

    /** Newest event first: the selector opens on the streak that most recently changed. */
    @Query(
        "SELECT * FROM partner_streak_projection WHERE pair_id = :pairId " +
            "ORDER BY last_event_occurred_at_ms DESC, habit_id ASC",
    )
    suspend fun streaksFor(pairId: String): List<PartnerStreakProjection>

    @Query(
        "INSERT OR IGNORE INTO partner_streak_projection (pair_id, habit_id, habit_label, " +
            "days_free, relapse_count, milestones_reached, last_event_occurred_at_ms, last_sequence) " +
            "VALUES (:pairId, :habitId, :label, 0, 0, '', 0, 0)",
    )
    suspend fun ensureStreakRow(pairId: String, habitId: String, label: String)

    /** Same monotonic guard as [advanceProjection], scoped to one streak. */
    @Query(
        "UPDATE partner_streak_projection SET habit_label = :label, days_free = :daysFree, " +
            "relapse_count = :relapseCount, milestones_reached = :milestonesReached, " +
            "last_event_occurred_at_ms = :occurredAtMs, last_sequence = :sequence " +
            "WHERE pair_id = :pairId AND habit_id = :habitId AND :sequence > last_sequence",
    )
    suspend fun advanceStreakProjection(
        pairId: String,
        habitId: String,
        label: String,
        daysFree: Int,
        relapseCount: Int,
        milestonesReached: String,
        occurredAtMs: Long,
        sequence: Long,
    ): Int

    @Transaction
    suspend fun applyStreakState(
        pairId: String,
        habitId: String,
        label: String,
        daysFree: Int,
        relapseCount: Int,
        milestonesReached: String,
        occurredAtMs: Long,
        sequence: Long,
    ) {
        ensureStreakRow(pairId, habitId, label)
        advanceStreakProjection(
            pairId, habitId, label, daysFree, relapseCount, milestonesReached, occurredAtMs, sequence,
        )
    }

    /** The streak left the partner's view (STOPPED or DELETED). */
    @Query("DELETE FROM partner_streak_projection WHERE pair_id = :pairId AND habit_id = :habitId")
    suspend fun deleteStreakProjection(pairId: String, habitId: String)

    /** Owner report 2026-11-09: with the last shared streak stopped, the dashboard kept
     *  showing its days-free forever, because every v3 row also lands in the pair-level
     *  aggregate. After a stop/delete the aggregate follows the newest survivor, and with
     *  none left it stops claiming state (`last_event_occurred_at_ms = 0`; the read model
     *  maps that to null). `last_sequence` is deliberately kept so the monotonic guard still
     *  rejects replays; the pin and coaching count are the pair's, not the streak's. */
    @Transaction
    suspend fun refreshAggregateAfterStreakRemoved(pairId: String) {
        val newest = streaksFor(pairId).firstOrNull()
        overwriteAggregateState(
            pairId,
            newest?.daysFree ?: 0,
            newest?.relapseCount ?: 0,
            newest?.milestonesReached ?: "",
            newest?.lastEventOccurredAtMs ?: 0L,
        )
    }

    @Query(
        "UPDATE partner_activity_projection SET days_free = :daysFree, " +
            "relapse_count = :relapseCount, milestones_reached = :milestonesReached, " +
            "last_event_occurred_at_ms = :occurredAtMs WHERE pair_id = :pairId",
    )
    suspend fun overwriteAggregateState(
        pairId: String,
        daysFree: Int,
        relapseCount: Int,
        milestonesReached: String,
        occurredAtMs: Long,
    )

    // --- Revocation / unpair prune (§8.17 delta) --------------------------------------------

    @Query("SELECT DISTINCT pair_id FROM partner_activity_projection")
    suspend fun projectedPairIds(): List<String>

    @Query("DELETE FROM partner_streak_projection WHERE pair_id IN (:pairIds)")
    suspend fun deleteStreakProjectionsFor(pairIds: List<String>)

    @Query("DELETE FROM partner_activity_link WHERE pair_id IN (:pairIds)")
    suspend fun deleteLinksFor(pairIds: List<String>)

    @Query("DELETE FROM partner_activity_projection WHERE pair_id IN (:pairIds)")
    suspend fun deleteProjectionsFor(pairIds: List<String>)
}
