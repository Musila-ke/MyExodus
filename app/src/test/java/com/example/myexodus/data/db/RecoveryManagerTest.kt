package com.example.myexodus.data.db

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryManagerTest {

    private lateinit var context: Context
    private lateinit var healthStore: DatabaseHealthStore
    private lateinit var fakeLegacyKeys: FakeKeyManager
    private lateinit var activityLogKeys: ActivityLogKeyManager
    private lateinit var manager: RecoveryManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        healthStore = DatabaseHealthStore()
        fakeLegacyKeys = FakeKeyManager()
        activityLogKeys = ActivityLogKeyManager(context)
        manager = RecoveryManager(context, fakeLegacyKeys, activityLogKeys, RulesStoreKeyManager(context), healthStore)
        wipeAll()
    }

    @After
    fun tearDown() {
        wipeAll()
    }

    private fun wipeAll() {
        for (base in listOf("myexodus_rules.db", "myexodus_activity.db", "myexodus.db")) {
            for (suffix in listOf("", "-wal", "-shm", ".corrupt")) {
                File(context.getDatabasePath(base).absolutePath + suffix).delete()
            }
        }
        File(context.filesDir, "rules_snapshot.json").delete()
    }

    private fun writeFile(file: File) {
        file.parentFile?.mkdirs()
        file.writeText("x")
    }

    @Test
    fun orphanedCorruptFileDetectedOnStartup() {
        writeFile(File(context.getDatabasePath("myexodus_rules.db").absolutePath + ".corrupt"))
        manager.checkOrphanedCorruptFiles()
        assertTrue(manager.hasOrphanedCorruptFiles())
        assertEquals(DatabaseHealth.RecoverableDataFound, healthStore.health.value)
    }

    @Test
    fun noCorruptFile_healthStaysOpening() {
        manager.checkOrphanedCorruptFiles()
        assertFalse(manager.hasOrphanedCorruptFiles())
        assertEquals(DatabaseHealth.Opening, healthStore.health.value)
    }

    @Test
    fun renameCorruptRules_preservesFileDropsSidecars() {
        val db = context.getDatabasePath("myexodus_rules.db")
        writeFile(db)
        writeFile(File(db.path + "-wal"))
        writeFile(File(db.path + "-shm"))
        manager.renameCorruptRules()
        assertFalse(db.exists())
        assertTrue(File(db.path + ".corrupt").exists())
        assertFalse(File(db.path + "-wal").exists())
        assertFalse(File(db.path + "-shm").exists())
    }

    @Test
    fun discardCorruptFiles_clearsRecoverableState() {
        writeFile(File(context.getDatabasePath("myexodus_activity.db").absolutePath + ".corrupt"))
        healthStore.set(DatabaseHealth.RecoverableDataFound)
        manager.discardCorruptFiles()
        assertFalse(File(context.getDatabasePath("myexodus_activity.db").absolutePath + ".corrupt").exists())
        assertEquals(DatabaseHealth.Ready, healthStore.health.value)
    }

    @Test
    fun resetAllData_destroysAllStoresKeysAndSnapshotCache() {
        for (base in listOf("myexodus_rules.db", "myexodus_activity.db", "myexodus.db")) {
            for (suffix in listOf("", "-wal", "-shm", ".corrupt")) {
                writeFile(File(context.getDatabasePath(base).absolutePath + suffix))
            }
        }
        writeFile(File(context.filesDir, "rules_snapshot.json"))
        // Seed the activity-log key blob so resetKeyMaterial has something to clear.
        context.getSharedPreferences("myexodus_activity_log_keys", Context.MODE_PRIVATE)
            .edit().putString("dek_iv", "iv").putString("dek_blob", "blob").commit()

        manager.resetAllData()

        for (base in listOf("myexodus_rules.db", "myexodus_activity.db", "myexodus.db")) {
            for (suffix in listOf("", "-wal", "-shm", ".corrupt")) {
                assertFalse(File(context.getDatabasePath(base).absolutePath + suffix).exists())
            }
        }
        assertFalse(File(context.filesDir, "rules_snapshot.json").exists())
        assertEquals(1, fakeLegacyKeys.resetCalls)
        val logPrefs = context.getSharedPreferences("myexodus_activity_log_keys", Context.MODE_PRIVATE)
        assertFalse(logPrefs.contains("dek_iv"))
        assertFalse(logPrefs.contains("dek_blob"))
        assertEquals(DatabaseHealth.Opening, healthStore.health.value)
    }

    private class FakeKeyManager : DatabaseKeyManager {
        var resetCalls = 0
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {
            resetCalls++
        }
    }
}
