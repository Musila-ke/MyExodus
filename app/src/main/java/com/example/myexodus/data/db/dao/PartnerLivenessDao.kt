package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.example.myexodus.data.db.entity.DeviceLiveness
import com.example.myexodus.data.db.entity.PartnerMonitoringCheckin

/**
 * Step 7 device-level liveness state (plan §4.7): the persisted heartbeat counter and the
 * check-in diagnostics row. Both are this device's own state — nothing here is partner data,
 * so a counter reset costs at most one re-enrollment, never a locked-out install (§8.10).
 */
@Dao
interface PartnerLivenessDao {

    @Query("SELECT * FROM device_liveness WHERE install_id = :installId")
    suspend fun liveness(installId: String): DeviceLiveness?

    /** INSERT OR REPLACE, not @Upsert: Room's @Upsert attempts the INSERT first and logs a
     *  UNIQUE-constraint exception before falling back to UPDATE on every existing row, which
     *  floods logcat with error-shaped noise on each check-in. The only caller writes a full
     *  row, so REPLACE is semantically identical here (no FKs or triggers on this table). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLiveness(row: DeviceLiveness)

    @Query("SELECT * FROM partner_monitoring_checkin WHERE id = 0")
    suspend fun checkin(): PartnerMonitoringCheckin?

    /** The row has two independent writers (snapshot recorder, heartbeat standby check), so
     *  each writes only its own columns — a full-row upsert from either would clobber the
     *  other's fields with a stale read. Insert-if-absent establishes the singleton row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCheckinIfAbsent(row: PartnerMonitoringCheckin = PartnerMonitoringCheckin())

    @Query(
        "UPDATE partner_monitoring_checkin SET last_state = :state, last_recorded_at_ms = :atMs " +
            "WHERE id = 0",
    )
    suspend fun updateLastSnapshot(state: String, atMs: Long)

    @Query(
        "UPDATE partner_monitoring_checkin SET last_standby_bucket = :bucket, " +
            "standby_checked_at_ms = :atMs WHERE id = 0",
    )
    suspend fun updateStandbyBucket(bucket: String, atMs: Long)
}
