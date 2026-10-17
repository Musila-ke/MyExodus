package com.example.myexodus.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.data.db.entity.DomainEvent
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device suite for the encrypted activity log (Enhanced Mode spec §8 Step 1): real
 * Android Keystore KEK, real SQLCipher native encryption, fixed PRAGMAs verified on-device.
 */
@RunWith(AndroidJUnit4::class)
class ActivityLogStoreInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyManager = ActivityLogKeyManager(context)

    @Before
    fun setUp() {
        assumeEmulatorOnly()
        resetAll()
    }

    @After
    fun tearDown() {
        resetAll()
    }

    private fun resetAll() {
        val recovery = RecoveryManager(
            context,
            AndroidDatabaseKeyManager(context),
            keyManager,
            RulesStoreKeyManager(context),
            DatabaseHealthStore(),
        )
        recovery.resetAllDataEmulatorOnly()
    }

    private fun newStore(): ActivityLogStore = ActivityLogStore(context, keyManager)

    @Test
    fun open_activatesFixedPragmas() {
        val store = newStore()
        assertTrue(store.open())
        // Assert on the STORE's own connection: busy_timeout, synchronous, secure_delete and
        // cipher_memory_security are connection-scoped — a second connection sees framework
        // defaults (device-caught 2026-09-14: busy_timeout read back 2500 on a fresh handle).
        val db = store.databaseForTest()
        assertNotNull(db)
        assertEquals("wal", queryString(db!!, "PRAGMA journal_mode"))
        assertEquals("1", queryString(db, "PRAGMA synchronous"))
        assertEquals("5000", queryString(db, "PRAGMA busy_timeout"))
        assertEquals("1", queryString(db, "PRAGMA secure_delete"))
        assertEquals("1", queryString(db, "PRAGMA cipher_memory_security"))
        store.close()
    }

    @Test
    fun insertAndCount_roundTrip() {
        val store = newStore()
        assertTrue(store.open())
        store.insert(event("e1", "inst-1", createdAt = 100L))
        store.insert(event("e2", "inst-1", createdAt = 200L))
        assertEquals(2L, store.count())
    }

    @Test
    fun duplicateEvent_ignoredIdempotently() {
        val store = newStore()
        assertTrue(store.open())
        assertTrue(store.insert(event("e1", "inst-1", createdAt = 100L)))
        assertTrue(store.insert(event("e1", "inst-1", createdAt = 200L)))
        assertEquals(1L, store.count())
    }

    @Test
    fun purgeOlderThan_removesOnlyOldRows() {
        val store = newStore()
        assertTrue(store.open())
        val now = System.currentTimeMillis()
        store.insert(event("old", "inst-1", createdAt = now - 20L * 24 * 60 * 60 * 1000))
        store.insert(event("fresh", "inst-1", createdAt = now))
        val deleted = store.purgeOlderThan(now - 14L * 24 * 60 * 60 * 1000)
        assertEquals(1, deleted)
        // Pin WHICH row survived (2026-10-16 review): a bare deleted==1/count==1 pair also
        // passes for an inverted boundary (`created_at >= ?`), which would purge the newest
        // rows and keep the stale one — the exact opposite of the retention contract.
        val survivors = mutableListOf<String>()
        store.databaseForTest()!!.rawQuery(
            "SELECT event_id FROM ${ActivityLogSchema.TABLE}", null,
        ).use { cursor -> while (cursor.moveToNext()) survivors += cursor.getString(0) }
        assertEquals(listOf("fresh"), survivors)
    }

    @Test
    fun capEviction_keepsAtMostMaxEvents() {
        val store = newStore()
        assertTrue(store.open())
        val base = System.currentTimeMillis() - ActivityLogSchema.MAX_EVENTS
        for (i in 0 until ActivityLogSchema.MAX_EVENTS + 5) {
            store.insert(event("e$i", "inst-1", createdAt = base + i))
        }
        assertTrue("count=${store.count()}", store.count() <= ActivityLogSchema.MAX_EVENTS)
    }

    @Test
    fun wrongKey_fileIsUnreadable() {
        val store = newStore()
        assertTrue(store.open())
        store.insert(event("e1", "inst-1", createdAt = 1L))
        store.close()

        val wrongKey = ByteArray(32) { 'Z'.code.toByte() }
        val path = context.getDatabasePath(ActivityLogSchema.DB_NAME)
        var threw = false
        try {
            SQLiteDatabase.openOrCreateDatabase(path, wrongKey, null, null).use { db ->
                db.rawQuery("SELECT count(*) FROM activity_log", null).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getLong(0)
                }
            }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("Log opened successfully with the wrong key", threw)
    }

    @Test
    fun walSidecars_appearDuringWrites() {
        val store = newStore()
        assertTrue(store.open())
        val base = context.getDatabasePath(ActivityLogSchema.DB_NAME).absolutePath
        store.insert(event("e1", "inst-1", createdAt = 1L))
        // SQLCipher creates -wal/-shm eagerly when entering WAL mode at open (device-verified
        // 2026-09-14), so the meaningful claim is that sidecars carry the write, not that they
        // were absent before it.
        assertTrue(java.io.File(base + "-wal").exists())
        assertTrue(java.io.File(base + "-shm").exists())
        store.checkpoint()
    }

    private fun queryString(db: SQLiteDatabase, sql: String): String {
        db.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return ""
    }

    private fun event(eventId: String, installationId: String, createdAt: Long) = DomainEvent(
        eventId = eventId,
        installationId = installationId,
        eventType = "TEST",
        payload = ByteArray(0),
        syncStatus = "PENDING",
        createdAt = createdAt,
    )
}
