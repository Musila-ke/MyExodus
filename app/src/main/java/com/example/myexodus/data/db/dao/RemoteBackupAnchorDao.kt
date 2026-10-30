package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.example.myexodus.data.db.entity.RemoteBackupAnchor

/** Account plan §6.4 item 11: the per-UID snapshot anchors. One singleton row. */
@Dao
interface RemoteBackupAnchorDao {

    @Query("SELECT * FROM remote_backup_anchor WHERE id = 0")
    suspend fun get(): RemoteBackupAnchor?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: RemoteBackupAnchor)
}
