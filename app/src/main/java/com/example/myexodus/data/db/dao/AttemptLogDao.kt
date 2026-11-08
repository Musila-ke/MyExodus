package com.example.myexodus.data.db.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.example.myexodus.data.db.entity.AttemptLog
import kotlinx.coroutines.flow.Flow

/** Per-target interception count for the home dashboard (Req 9). */
data class TargetAttemptCount(
    val target: String,
    val count: Int,
)

/** Blocked/override attempts for one target inside a report window. */
data class TargetOutcomeCount(
    @ColumnInfo(name = "blocked_target") val target: String,
    val outcome: String,
    val count: Int,
)

/** The timestamp, outcome and target the Insights day strip buckets. */
data class AttemptStamp(
    @ColumnInfo(name = "occurred_at_epoch_millis") val occurredAtEpochMillis: Long,
    val outcome: String,
    @ColumnInfo(name = "blocked_target") val target: String,
)

@Dao
interface AttemptLogDao {

    @Insert
    suspend fun insert(attempt: AttemptLog): Long

    @Query("SELECT * FROM attempt_log ORDER BY occurred_at_epoch_millis DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AttemptLog>

    /** Whole table for the guest snapshot (account plan §6.3: read without mutating). */
    @Query("SELECT * FROM attempt_log")
    suspend fun getAll(): List<AttemptLog>

    /** §6.6 item 7: the staging restore replaces the logical tables wholesale. */
    @Query("DELETE FROM attempt_log")
    suspend fun deleteAll()

    /** Restored attempts get fresh auto-generated ids, so old per-pair links point nowhere. */
    @Query("DELETE FROM attempt_mirror_link")
    suspend fun deleteAllLinks()

    /**
     * §14 reconcile: attempts not yet mirrored to THIS pair, oldest first (ADR #111). The
     * link table — not `event_sequence` — is what the pending check reads, so one pair's
     * delivery never hides the row from another. `event_sequence` stays only as the pre-v12
     * single-link marker: a row carrying it was delivered before per-pair links existed and is
     * excluded for every pair (never re-mirrored, so no duplicates).
     *
     * [sinceMs] is the pair's enrollment time (owner ruling 2026-09-29): history that predates
     * the pairing is never handed to the newcomer. A row recorded before the pairing already
     * has no audience — it happened when this partner did not exist.
     */
    @Query(
        "SELECT * FROM attempt_log WHERE event_sequence IS NULL " +
            "AND occurred_at_epoch_millis >= :sinceMs " +
            "AND NOT EXISTS (SELECT 1 FROM attempt_mirror_link l " +
            "WHERE l.attempt_id = attempt_log.id AND l.pair_id = :pairId) " +
            "ORDER BY occurred_at_epoch_millis ASC, id ASC LIMIT :limit",
    )
    suspend fun pendingEventCreation(pairId: String, sinceMs: Long, limit: Int): List<AttemptLog>

    /** Exactly-once per (attempt, pair): re-linking the same pair is a no-op (ADR #111). */
    @Query(
        "INSERT OR IGNORE INTO attempt_mirror_link (attempt_id, pair_id, sequence) " +
            "VALUES (:id, :pairId, :sequence)",
    )
    suspend fun linkEvent(id: Long, pairId: String, sequence: Long)

    @Query("SELECT sequence FROM attempt_mirror_link WHERE attempt_id = :id AND pair_id = :pairId")
    suspend fun linkSequence(id: Long, pairId: String): Long?

    @Query("SELECT count(*) FROM attempt_log")
    suspend fun count(): Long

    /** Double-record guard (2026-11-02): both enforcement paths can observe one blocked
     *  launch — the accessibility service decides on the window-state event, the foreground
     *  service's 1.5 s poll on the resumed task. One wall is one attempt.
     *
     *  Bounded on BOTH sides: a row stamped after [nowMs] is clock skew (the device clock was
     *  set back after the write), not evidence of this launch — an open-ended lower bound let
     *  one future row suppress every later attempt forever (device 2026-11-02). */
    @Query(
        "SELECT COUNT(*) FROM attempt_log WHERE outcome = 'BLOCKED' " +
            "AND rule_id = :ruleId AND blocked_target = :target " +
            "AND occurred_at_epoch_millis >= :sinceMs " +
            "AND occurred_at_epoch_millis <= :nowMs",
    )
    suspend fun countRecentBlocked(ruleId: String, target: String, sinceMs: Long, nowMs: Long): Int

    /** Block-screen hits grouped by target ("BLOCKED" only — overrides are not attempts). */
    @Query(
        "SELECT blocked_target AS target, COUNT(*) AS count FROM attempt_log " +
            "WHERE outcome = 'BLOCKED' GROUP BY blocked_target",
    )
    fun observeBlockedCounts(): Flow<List<TargetAttemptCount>>

    /**
     * Exact per-target, per-outcome totals at or after [sinceMs] — the Insights report window.
     * Aggregated in SQL rather than by loading rows, so the numbers stay complete however many
     * attempts a heavy rule set produces (a `LIMIT` here would undercount silently).
     */
    @Query(
        "SELECT blocked_target AS blocked_target, outcome AS outcome, COUNT(*) AS count " +
            "FROM attempt_log WHERE occurred_at_epoch_millis >= :sinceMs " +
            "GROUP BY blocked_target, outcome",
    )
    suspend fun countsSince(sinceMs: Long): List<TargetOutcomeCount>

    /** Timestamps, outcomes and targets for the day strip. The strip needs every attempt to
     *  bucket days in the device's own zone, which SQL cannot spell without freezing a zone
     *  into the query; the target rides along so a tapped day can name what was blocked. */
    @Query(
        "SELECT occurred_at_epoch_millis AS occurred_at_epoch_millis, outcome AS outcome, " +
            "blocked_target AS blocked_target " +
            "FROM attempt_log WHERE occurred_at_epoch_millis >= :sinceMs",
    )
    suspend fun stampsSince(sinceMs: Long): List<AttemptStamp>
}
