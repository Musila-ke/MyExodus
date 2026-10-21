package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * This installation's heartbeat counter (plan §4.7, schema v19). One row per Firebase
 * Installation ID — the install identity the relay and DMS already use.
 *
 * The counter is persisted BEFORE the network call (see `PartnerHeartbeatClient`), so it is
 * monotonic across crashes even when a send's outcome is unknown. [uid] is part of the
 * namespace: an anonymous-identity rotation mints a new server document, and the counter
 * restarts at 1 with it — a lost counter must never lock a re-enrolled install out (§8.10).
 */
@Entity(tableName = "device_liveness")
data class DeviceLiveness(
    @PrimaryKey @ColumnInfo(name = "install_id") val installId: String,
    @ColumnInfo(name = "uid") val uid: String,
    @ColumnInfo(name = "counter") val counter: Long,
    @ColumnInfo(name = "last_sent_at_ms") val lastSentAtMs: Long,
    @ColumnInfo(name = "last_accepted_counter") val lastAcceptedCounter: Long = 0,
    @ColumnInfo(name = "last_accepted_at_ms") val lastAcceptedAtMs: Long = 0,
)
