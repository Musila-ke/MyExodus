package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Device-level check-in diagnostics (plan Step 7, schema v19): the last monitoring snapshot
 * this device journaled (state + time, the change/interval debounce) and the last App Standby
 * bucket observed at a check-in. A singleton row ([id] = 0). The standby fields are a
 * low-sensitivity diagnostic — the bucket name only, never usage data.
 */
@Entity(tableName = "partner_monitoring_checkin")
data class PartnerMonitoringCheckin(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 0,
    @ColumnInfo(name = "last_state") val lastState: String? = null,
    @ColumnInfo(name = "last_recorded_at_ms") val lastRecordedAtMs: Long = 0,
    @ColumnInfo(name = "last_standby_bucket") val lastStandbyBucket: String? = null,
    @ColumnInfo(name = "standby_checked_at_ms") val standbyCheckedAtMs: Long = 0,
)
