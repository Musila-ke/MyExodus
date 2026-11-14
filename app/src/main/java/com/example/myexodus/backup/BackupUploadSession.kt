package com.example.myexodus.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Account plan §6.4 item 11: one backup operation's resumable state — the random backup ID, the
 * reserved Storage path, the exact object identity, and the upload session URI. The session URI
 * carries an upload authorisation token: it lives only in the Tink-wrapped DataStore and is never
 * logged. It is honoured only when the UID, session generation and backup ID all still match.
 *
 * [uploaded] records that the object finished uploading; a retry then goes straight to commit
 * instead of re-uploading an object the create-only Storage rule would reject.
 *
 * [contentSha256] is ADR #310's change detector: the SHA-256 of the compressed plaintext sealed
 * into this session, recorded only once the server commits it.
 */
data class BackupUploadSession(
    val uid: String,
    val generation: Long,
    val backupId: String,
    val objectPath: String,
    val sha256: String,
    val objectBytes: Long,
    val compressedBytes: Long,
    val uploaded: Boolean,
    val sessionUri: String?,
    val contentSha256: String? = null,
)

/** The encrypted home of the active backup operation (§6.4 items 11/14). */
interface BackupUploadSessionStore {
    suspend fun get(): BackupUploadSession?
    suspend fun save(session: BackupUploadSession)
    suspend fun markUploaded(backupId: String)
    suspend fun clear()
}

class DataStoreBackupUploadSessionStore(
    private val dataStore: DataStore<Preferences>,
) : BackupUploadSessionStore {

    override suspend fun get(): BackupUploadSession? {
        val prefs = dataStore.data.first()
        return BackupUploadSession(
            uid = prefs[KEY_UID] ?: return null,
            generation = prefs[KEY_GENERATION] ?: return null,
            backupId = prefs[KEY_BACKUP_ID] ?: return null,
            objectPath = prefs[KEY_OBJECT_PATH] ?: return null,
            sha256 = prefs[KEY_SHA256] ?: return null,
            objectBytes = prefs[KEY_OBJECT_BYTES] ?: return null,
            compressedBytes = prefs[KEY_COMPRESSED_BYTES] ?: return null,
            uploaded = prefs[KEY_UPLOADED] ?: false,
            sessionUri = prefs[KEY_SESSION_URI],
            contentSha256 = prefs[KEY_CONTENT_SHA256],
        )
    }

    override suspend fun save(session: BackupUploadSession): Unit {
        dataStore.edit { prefs ->
            prefs[KEY_UID] = session.uid
            prefs[KEY_GENERATION] = session.generation
            prefs[KEY_BACKUP_ID] = session.backupId
            prefs[KEY_OBJECT_PATH] = session.objectPath
            prefs[KEY_SHA256] = session.sha256
            prefs[KEY_OBJECT_BYTES] = session.objectBytes
            prefs[KEY_COMPRESSED_BYTES] = session.compressedBytes
            prefs[KEY_UPLOADED] = session.uploaded
            session.sessionUri?.let { prefs[KEY_SESSION_URI] = it } ?: prefs.remove(KEY_SESSION_URI)
            session.contentSha256?.let { prefs[KEY_CONTENT_SHA256] = it }
                ?: prefs.remove(KEY_CONTENT_SHA256)
        }
    }

    override suspend fun markUploaded(backupId: String): Unit {
        dataStore.edit { prefs ->
            if (prefs[KEY_BACKUP_ID] == backupId) prefs[KEY_UPLOADED] = true
        }
    }

    // The DataStore is shared with the secrets vault and the bundle-revision anchors; only this
    // operation's own keys may be removed.
    override suspend fun clear(): Unit {
        dataStore.edit { prefs ->
            prefs.remove(KEY_UID)
            prefs.remove(KEY_GENERATION)
            prefs.remove(KEY_BACKUP_ID)
            prefs.remove(KEY_OBJECT_PATH)
            prefs.remove(KEY_SHA256)
            prefs.remove(KEY_OBJECT_BYTES)
            prefs.remove(KEY_COMPRESSED_BYTES)
            prefs.remove(KEY_UPLOADED)
            prefs.remove(KEY_SESSION_URI)
            prefs.remove(KEY_CONTENT_SHA256)
        }
    }

    private companion object {
        val KEY_UID = stringPreferencesKey("backup_upload_uid")
        val KEY_GENERATION = longPreferencesKey("backup_upload_generation")
        val KEY_BACKUP_ID = stringPreferencesKey("backup_upload_backup_id")
        val KEY_OBJECT_PATH = stringPreferencesKey("backup_upload_object_path")
        val KEY_SHA256 = stringPreferencesKey("backup_upload_sha256")
        val KEY_OBJECT_BYTES = longPreferencesKey("backup_upload_object_bytes")
        val KEY_COMPRESSED_BYTES = longPreferencesKey("backup_upload_compressed_bytes")
        val KEY_UPLOADED = booleanPreferencesKey("backup_upload_uploaded")
        val KEY_SESSION_URI = stringPreferencesKey("backup_upload_session_uri")
        val KEY_CONTENT_SHA256 = stringPreferencesKey("backup_upload_content_sha256")
    }
}
