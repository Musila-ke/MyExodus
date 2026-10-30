package com.example.myexodus.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** The snapshot a completed restore activated; the swap relaunches the process. */
data class BackupRestoreMark(
    val uid: String,
    val revision: Long,
    val sha256: String,
    val completedAtMs: Long,
)

/**
 * §6.6 item 9: the restore ends in a process relaunch, so the completion the user is owed is
 * persisted first. The mark is bound to the UID that performed the restore.
 */
interface BackupRestoreMarkStore {
    suspend fun get(): BackupRestoreMark?
    suspend fun save(mark: BackupRestoreMark)
    suspend fun clear()
}

class DataStoreBackupRestoreMarkStore(
    private val dataStore: DataStore<Preferences>,
) : BackupRestoreMarkStore {

    override suspend fun get(): BackupRestoreMark? {
        val prefs = dataStore.data.first()
        return BackupRestoreMark(
            uid = prefs[KEY_UID] ?: return null,
            revision = prefs[KEY_REVISION] ?: return null,
            sha256 = prefs[KEY_SHA256] ?: return null,
            completedAtMs = prefs[KEY_COMPLETED_AT_MS] ?: return null,
        )
    }

    override suspend fun save(mark: BackupRestoreMark): Unit {
        dataStore.edit { prefs ->
            prefs[KEY_UID] = mark.uid
            prefs[KEY_REVISION] = mark.revision
            prefs[KEY_SHA256] = mark.sha256
            prefs[KEY_COMPLETED_AT_MS] = mark.completedAtMs
        }
    }

    // The DataStore is shared with the secrets vault and the other backup anchors; only this
    // mark's own keys are removed.
    override suspend fun clear(): Unit {
        dataStore.edit { prefs ->
            prefs.remove(KEY_UID)
            prefs.remove(KEY_REVISION)
            prefs.remove(KEY_SHA256)
            prefs.remove(KEY_COMPLETED_AT_MS)
        }
    }

    private companion object {
        val KEY_UID = stringPreferencesKey("backup_restore_uid")
        val KEY_REVISION = longPreferencesKey("backup_restore_revision")
        val KEY_SHA256 = stringPreferencesKey("backup_restore_sha256")
        val KEY_COMPLETED_AT_MS = longPreferencesKey("backup_restore_completed_at_ms")
    }
}
