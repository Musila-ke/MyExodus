package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Account plan §6.4 item 11 / §6.6 item 3: the local snapshot anchors, one singleton row in
 * each UID-scoped database. [materializedRevision]/[materializedSha256] identify the cloud
 * snapshot represented by current local state (advanced only after a successful restore or
 * backup commit); [highWaterRevision]/[highWaterSha256] are the installation's monotonic
 * high-water mark. Both are nullable as a pair: a new device has no anchor yet.
 */
@Entity(tableName = "remote_backup_anchor")
data class RemoteBackupAnchor(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "materialized_revision") val materializedRevision: Long? = null,
    @ColumnInfo(name = "materialized_sha256") val materializedSha256: String? = null,
    @ColumnInfo(name = "high_water_revision") val highWaterRevision: Long? = null,
    @ColumnInfo(name = "high_water_sha256") val highWaterSha256: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
