package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.example.myexodus.data.db.entity.PartnerMonitoringSnapshot

/**
 * Received monitoring snapshots (Step 7, plan §8.9). [recordIfNewer] is the replay guard: a
 * replayed older snapshot must never roll the card backwards, so the compare-and-write runs in
 * one transaction. The guard admits a snapshot from a newer install generation regardless of
 * sequence — a re-pair restarts the sender's chain at 1, and a message from the old generation
 * cannot verify against the new pair key anyway.
 */
@Dao
interface PartnerMonitoringSnapshotDao {

    @Query("SELECT * FROM partner_monitoring_snapshot WHERE pair_id = :pairId")
    suspend fun byPair(pairId: String): PartnerMonitoringSnapshot?

    @Query("SELECT * FROM partner_monitoring_snapshot")
    suspend fun all(): List<PartnerMonitoringSnapshot>

    @Upsert
    suspend fun upsert(row: PartnerMonitoringSnapshot)

    /** Returns true when [row] was stored, false when a newer (or equal) snapshot held. */
    @Transaction
    suspend fun recordIfNewer(row: PartnerMonitoringSnapshot): Boolean {
        val current = byPair(row.pairId)
        val admission = current == null ||
            row.snapshotInstallGeneration != current.snapshotInstallGeneration ||
            row.snapshotEventSequence > current.snapshotEventSequence
        if (admission) upsert(row)
        return admission
    }

    @Query("DELETE FROM partner_monitoring_snapshot WHERE pair_id IN (:pairIds)")
    suspend fun deleteFor(pairIds: List<String>)
}
