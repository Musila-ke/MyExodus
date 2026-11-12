package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.example.myexodus.data.db.entity.RecoverySession

@Dao
interface RecoverySessionDao {

    @Insert
    suspend fun insert(session: RecoverySession)

    @Update
    suspend fun update(session: RecoverySession)

    @Query("SELECT * FROM recovery_session WHERE id = :id")
    suspend fun byId(id: String): RecoverySession?

    /** Whole table for the guest snapshot (account plan §6.3: read without mutating). */
    @Query("SELECT * FROM recovery_session")
    suspend fun getAll(): List<RecoverySession>

    /** §6.6 item 7: the staging restore replaces the logical tables wholesale. */
    @Query("DELETE FROM recovery_session")
    suspend fun deleteAll()

    /** Sessions return with the same ids, but their events were never in the snapshot. */
    @Query("DELETE FROM recovery_mirror_link")
    suspend fun deleteAllLinks()

    /** One user-visible history row ([deleteLinksFor] follows it, not precedes: see the
     *  repository). */
    @Query("DELETE FROM recovery_session WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** This table has no foreign key, so a removed session's links are cleaned by hand. */
    @Query("DELETE FROM recovery_mirror_link WHERE session_id = :id")
    suspend fun deleteLinksFor(id: String)

    /** A deleted habit must not keep claiming its urge rows: they survive as unlinked. */
    @Query("UPDATE recovery_session SET habit_id = NULL WHERE habit_id = :habitId")
    suspend fun clearHabitLink(habitId: String)

    /** History page; [habitId] null = every row (including unlinked legacy rows). */
    @Query(
        "SELECT * FROM recovery_session " +
            "WHERE (:habitId IS NULL OR habit_id = :habitId) " +
            "ORDER BY started_at_epoch_ms DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun page(limit: Int, offset: Int, habitId: String? = null): List<RecoverySession>

    @Query("SELECT count(*) FROM recovery_session")
    suspend fun count(): Long

    /**
     * Rows per (state, technique) at or after [fromMs] (`0` = all time). Counted in SQL rather
     * than by loading rows: the summary sits above a paged list, and reading the whole table to
     * show one number is the unbounded-query shape the page otherwise avoids.
     *
     * `technique` is part of the key because a log-only row is born COMPLETED
     * (`RecoverySessionRepository.logOnly`), so a state-only count reads "logged an urge and
     * went home" as a finished technique session.
     *
     * `action_id` splits the technique-less rows further (v26): an Urge Assist episode that ran
     * a step rather than a technique is also born COMPLETED with `technique = none`, but it is
     * not a bare log and must not be folded into "Logged only".
     */
    @Query(
        "SELECT state AS state, technique AS technique, action_id AS actionId, COUNT(*) AS count " +
            "FROM recovery_session " +
            "WHERE started_at_epoch_ms >= :fromMs AND (:habitId IS NULL OR habit_id = :habitId) " +
            "GROUP BY state, technique, action_id",
    )
    suspend fun countsByStateSince(fromMs: Long, habitId: String? = null): List<SessionCount>

    /** One statement = one transaction: every row a process death left STARTED past the cutoff
     *  closes as INTERRUPTED with the v2 M2.4 bound (`endedAt = startedAt + 15 min`). Rows are
     *  NOT selected first — a session cannot be interrupted by this if it ended in between. */
    @Query(
        "UPDATE recovery_session SET state = 'INTERRUPTED', " +
            "ended_at_epoch_ms = started_at_epoch_ms + :staleMs, duration_sec = :staleSec " +
            "WHERE state = 'STARTED' AND started_at_epoch_ms < :cutoffEpochMs",
    )
    suspend fun interruptStale(cutoffEpochMs: Long, staleMs: Long, staleSec: Int): Int

}

/** One `GROUP BY state, technique, action_id` bucket from
 *  [RecoverySessionDao.countsByStateSince]. The split only matters for
 *  [RecoverySession.TECHNIQUE_NONE] rows — every other technique buckets the same way and is
 *  folded back together by the caller. [actionId] null means a bare log; non-null means an
 *  Urge Assist episode. */
data class SessionCount(
    val state: String,
    val technique: String,
    val count: Long,
    val actionId: String? = null,
)
