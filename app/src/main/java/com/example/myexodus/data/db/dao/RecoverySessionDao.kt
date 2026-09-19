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

    @Query(
        "SELECT * FROM recovery_session ORDER BY started_at_epoch_ms DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun page(limit: Int, offset: Int): List<RecoverySession>

    @Query("SELECT count(*) FROM recovery_session")
    suspend fun count(): Long

    /** One statement = one transaction: every row a process death left STARTED past the cutoff
     *  closes as INTERRUPTED with the v2 M2.4 bound (`endedAt = startedAt + 15 min`). Rows are
     *  NOT selected first — a session cannot be interrupted by this if it ended in between. */
    @Query(
        "UPDATE recovery_session SET state = 'INTERRUPTED', " +
            "ended_at_epoch_ms = started_at_epoch_ms + :staleMs, duration_sec = :staleSec " +
            "WHERE state = 'STARTED' AND started_at_epoch_ms < :cutoffEpochMs",
    )
    suspend fun interruptStale(cutoffEpochMs: Long, staleMs: Long, staleSec: Int): Int

    /** Mirror queue (W5): terminal rows with no chain event yet, oldest first. STARTED rows and
     *  technique-less logs are excluded by construction — never mirrored. */
    @Query(
        "SELECT * FROM recovery_session " +
            "WHERE event_sequence IS NULL AND state != 'STARTED' AND technique != 'none' " +
            "ORDER BY started_at_epoch_ms ASC LIMIT :limit",
    )
    suspend fun pendingMirror(limit: Int): List<RecoverySession>

    @Query("UPDATE recovery_session SET event_sequence = :sequence WHERE id = :id")
    suspend fun linkEvent(id: String, sequence: Long)
}
