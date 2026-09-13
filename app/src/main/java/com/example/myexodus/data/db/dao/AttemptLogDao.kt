package com.example.myexodus.data.db.dao

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

@Dao
interface AttemptLogDao {

    @Insert
    suspend fun insert(attempt: AttemptLog): Long

    @Query("SELECT * FROM attempt_log ORDER BY occurred_at_epoch_millis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AttemptLog>

    /** §14 reconcile: attempts not yet linked to a chain event, oldest first. */
    @Query(
        "SELECT * FROM attempt_log WHERE event_sequence IS NULL " +
            "ORDER BY occurred_at_epoch_millis ASC LIMIT :limit",
    )
    suspend fun pendingEventCreation(limit: Int): List<AttemptLog>

    @Query("UPDATE attempt_log SET event_sequence = :sequence WHERE id = :id")
    suspend fun linkEvent(id: Long, sequence: Long)

    @Query("SELECT count(*) FROM attempt_log")
    suspend fun count(): Long

    /** Block-screen hits grouped by target ("BLOCKED" only — overrides are not attempts). */
    @Query(
        "SELECT blocked_target AS target, COUNT(*) AS count FROM attempt_log " +
            "WHERE outcome = 'BLOCKED' GROUP BY blocked_target",
    )
    fun observeBlockedCounts(): Flow<List<TargetAttemptCount>>
}
