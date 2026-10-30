package com.example.myexodus.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Account plan §6.4 item 11: the resumable session journal. It lives in the DataStore shared
 * with the secrets vault and the bundle anchors, so clearing one backup must never remove
 * another feature's keys.
 */
class BackupUploadSessionStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStoreBackupUploadSessionStore
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>

    private val session = BackupUploadSession(
        uid = "uid-a",
        generation = 7,
        backupId = "backup-1",
        objectPath = "backups/uid-a/backup-1.bin",
        sha256 = "sha-1",
        objectBytes = 1_024,
        compressedBytes = 512,
        uploaded = false,
        sessionUri = "https://upload.example/session",
    )

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(temp.newFolder(), "sessions.preferences_pb")
        }
        store = DataStoreBackupUploadSessionStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun emptyStoreHasNoSession() = runBlocking {
        assertNull(store.get())
    }

    @Test
    fun saveRoundTripsEveryField() = runBlocking {
        store.save(session)
        assertEquals(session, store.get())
    }

    @Test
    fun sessionUriMayBeAbsentUntilTheServiceIssuesIt() = runBlocking {
        store.save(session.copy(sessionUri = null))
        assertNull(store.get()?.sessionUri)
        store.save(session.copy(sessionUri = "https://upload.example/2"))
        assertEquals("https://upload.example/2", store.get()?.sessionUri)
    }

    @Test
    fun markUploadedOnlyAppliesToTheNamedBackup() = runBlocking {
        store.save(session)
        store.markUploaded("someone-else")
        assertTrue(store.get()?.uploaded == false)
        store.markUploaded(session.backupId)
        assertTrue(store.get()?.uploaded == true)
    }

    @Test
    fun clearRemovesTheSessionButNotOtherFeaturesKeys() = runBlocking {
        val foreign = stringPreferencesKey("backup_revision_uid-a")
        dataStore.edit { it[foreign] = "5" }
        store.save(session)

        store.clear()

        assertNull(store.get())
        assertEquals("5", dataStore.data.first()[foreign])
    }
}
