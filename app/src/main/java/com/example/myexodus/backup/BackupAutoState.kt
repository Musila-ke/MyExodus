package com.example.myexodus.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * ADR #310: what this phone knows about its last committed snapshot. The digest is the SHA-256
 * of the exact compressed payload the uploader sealed, so the automatic pass can skip an upload
 * only when the current data would produce byte-identical content.
 */
data class BackupAutoRecord(
    val contentSha256: String?,
    val lastSuccessEpochMillis: Long?,
)

/** Protected local state, in the same Tink-wrapped DataStore as the other backup anchors. */
interface BackupAutoStateStore {
    suspend fun read(uid: String): BackupAutoRecord

    /** [contentSha256] null means the session predates the digest; the next pass re-derives it. */
    suspend fun recordSuccess(uid: String, contentSha256: String?, atEpochMillis: Long)
}

class DataStoreBackupAutoState(
    private val dataStore: DataStore<Preferences>,
) : BackupAutoStateStore {

    override suspend fun read(uid: String): BackupAutoRecord {
        val prefs = dataStore.data.first()
        return BackupAutoRecord(
            contentSha256 = prefs[digestKey(uid)],
            lastSuccessEpochMillis = prefs[successKey(uid)],
        )
    }

    override suspend fun recordSuccess(uid: String, contentSha256: String?, atEpochMillis: Long) {
        dataStore.edit { prefs ->
            contentSha256?.let { prefs[digestKey(uid)] = it } ?: prefs.remove(digestKey(uid))
            prefs[successKey(uid)] = atEpochMillis
        }
    }

    private fun digestKey(uid: String) = stringPreferencesKey("backup_auto_digest_$uid")
    private fun successKey(uid: String) = longPreferencesKey("backup_auto_success_$uid")
}
