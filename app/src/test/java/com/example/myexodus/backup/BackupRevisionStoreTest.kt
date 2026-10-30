package com.example.myexodus.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Account plan §8.5: the rollback anchor. A revision is a monotonically increasing high-water
 * mark per UID, so a replay of an older bundle is detectable on this device.
 */
class BackupRevisionStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStoreBackupRevisionStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(temp.newFolder(), "revisions.preferences_pb")
        }
        store = DataStoreBackupRevisionStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun unknownUidHasNoAnchor() = runBlocking {
        assertNull(store.highest("uid-a"))
    }

    @Test
    fun recordsPerUid() = runBlocking {
        store.record("uid-a", 3)
        store.record("uid-b", 8)
        assertEquals(3L, store.highest("uid-a"))
        assertEquals(8L, store.highest("uid-b"))
    }

    @Test
    fun recordIsMonotonic() = runBlocking {
        store.record("uid-a", 5)
        store.record("uid-a", 4)
        store.record("uid-a", 2)
        assertEquals(5L, store.highest("uid-a"))
        store.record("uid-a", 6)
        assertEquals(6L, store.highest("uid-a"))
    }
}
