package com.example.myexodus.data.db

import android.content.ContentValues
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.myexodus.data.db.entity.DomainEvent
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.util.Arrays

/**
 * Encrypted activity/attempt log (Enhanced Mode spec §8 Step 1): raw SQLCipher file opened
 * via the byte-array passphrase overload of [SQLiteDatabase.openOrCreateDatabase]. The key is
 * the Keystore-wrapped DEK turned into the raw-key passphrase `x'<hex>'` (ADR #153), so
 * SQLCipher skips PBKDF2. The DEK is decrypted from Keystore immediately before the open and
 * zeroed right after; the raw passphrase must outlive the open — the zetetic handle re-reads
 * it for every internal pool connection — and is zeroed in [close]. Never on the
 * accessibility-event hot path — all writes are background and best-effort: a failing log
 * write is logged and skipped, never thrown at the caller (spec §9).
 */
class ActivityLogStore(
    private val appContext: Context,
    private val keyManager: ActivityLogKeyManager,
) {
    @Volatile private var db: SQLiteDatabase? = null
    private var rawKey: ByteArray? = null
    private val lock = Any()

    val isOpen: Boolean get() = synchronized(lock) { db != null }

    /** Test-only: the live handle, so device suites can assert the connection-scoped PRAGMAs
     *  that [ActivityLogSchema.applyPragmas] fixed (a second connection cannot see them). */
    fun databaseForTest(): SQLiteDatabase? = synchronized(lock) { db }

    /**
     * Opens (or creates) the encrypted log file. Returns false on failure after recovery
     * attempts (key loss → fresh empty log; corruption → preserved .corrupt copy, fresh
     * empty log). Failures are logged, never thrown.
     */
    fun open(): Boolean = synchronized(lock) {
        if (db != null) return true
        val path = appContext.getDatabasePath(
            LocalDatabaseMigration.activityDbName(appContext),
        )
        return try {
            // Self-contained like every other SQLCipher consumer (finding #66): without this,
            // the store's first open in a process that has not yet touched the rules DB dies
            // with UnsatisfiedLinkError — order-dependent, and silent because open() returns
            // false on the fail-open path (device-caught 2026-10-17).
            System.loadLibrary("sqlcipher")
            val t0 = SystemClock.uptimeMillis()
            val key = keyManager.getOrCreateKey()
            val t1 = SystemClock.uptimeMillis()
            var raw: ByteArray? = null
            // Owned until the store takes it: a throw from the pragma/schema steps must not
            // leave a live handle behind (open() is retried, so a leak would accumulate).
            var opened: SQLiteDatabase? = null
            try {
                raw = SqlCipherRawKey.passphraseFor(key)
                val t2 = SystemClock.uptimeMillis()
                val migrated = SqlCipherRawKey.ensureRawKeyed(path, key, raw)
                val handle = SQLiteDatabase.openOrCreateDatabase(path, raw, null, null)
                opened = handle
                // Cold-start attribution (ADR #153): keystore unwrap / raw probe / keying splits.
                // Only elapsed milliseconds are logged; the labels avoid secret-shaped names
                // so the no-secret-logging rule stays signal (its name filter matches text).
                Log.i(TAG, "Activity log timings: unwrap=${t1 - t0}ms probe=${t2 - t1}ms " +
                    "open=${SystemClock.uptimeMillis() - t2}ms migrated=$migrated " +
                    "thread=${Thread.currentThread().name}")
                val walOk = ActivityLogSchema.applyPragmas(handle)
                ActivityLogSchema.createSchema(handle)
                if (!walOk) {
                    Log.e(TAG, "Activity log open: WAL not active; closing")
                    false
                } else {
                    db = handle
                    opened = null
                    rawKey = raw
                    raw = null
                    Log.i(TAG, "Activity log open: WAL confirmed")
                    purgeOlderThan(System.currentTimeMillis() - RETENTION_WINDOW_MS)
                    true
                }
            } finally {
                Arrays.fill(key, 0.toByte())
                raw?.let { Arrays.fill(it, 0.toByte()) }
                opened?.let { runCatching { it.close() } }
            }
        } catch (e: Throwable) {
            val failure = keyManager.classifyFailure(e) // classify once (2026-08-28 review)
            Log.e(TAG, "Activity log open failed: $failure", e)
            when (failure) {
                KeyFailure.ConfirmedCorruption -> {
                    renameCorrupt(path)
                    reopenFresh(path)
                }
                KeyFailure.KeyUnavailable -> {
                    // The old file is encrypted with a lost key; preserve it as evidence,
                    // drop the stale wrapped key, and start a fresh empty log.
                    renameCorrupt(path)
                    keyManager.resetKeyMaterial()
                    reopenFresh(path)
                }
                else -> false
            }
        }
    }

    private fun reopenFresh(path: File): Boolean {
        return try {
            val freshKey = keyManager.getOrCreateKey()
            var freshRaw: ByteArray? = null
            var opened: SQLiteDatabase? = null
            try {
                freshRaw = SqlCipherRawKey.passphraseFor(freshKey)
                val handle = SQLiteDatabase.openOrCreateDatabase(path, freshRaw, null, null)
                opened = handle
                val walOk = ActivityLogSchema.applyPragmas(handle)
                ActivityLogSchema.createSchema(handle)
                // Same WAL gate as open(): a non-WAL handle would grow unboundedly (2026-08-28 review).
                if (!walOk) {
                    Log.e(TAG, "Activity log fresh recreate: WAL not active; closing")
                    false
                } else {
                    db = handle
                    opened = null
                    rawKey = freshRaw
                    freshRaw = null
                    Log.i(TAG, "Activity log recreated fresh after failure")
                    true
                }
            } finally {
                Arrays.fill(freshKey, 0.toByte())
                freshRaw?.let { Arrays.fill(it, 0.toByte()) }
                opened?.let { runCatching { it.close() } }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Activity log fresh recreate failed", e)
            false
        }
    }

    private fun renameCorrupt(path: File) {
        Log.w(TAG, "Activity log confirmed corrupt; preserving as .corrupt")
        File(path.path + "-wal").delete()
        File(path.path + "-shm").delete()
        if (path.exists() && !path.renameTo(File(path.path + ".corrupt"))) {
            Log.e(TAG, "Failed to rename corrupt activity log to .corrupt")
        }
    }

    /** Best-effort append; INSERT OR IGNORE keeps the (installation_id, event_id) idempotency. */
    fun insert(event: DomainEvent): Boolean = synchronized(lock) {
        val handle = db ?: return false
        return try {
            val values = ContentValues().apply {
                put("event_id", event.eventId)
                put("installation_id", event.installationId)
                put("event_type", event.eventType)
                put("payload", event.payload)
                put("sync_status", event.syncStatus)
                put("created_at", event.createdAt)
            }
            val ignored = handle.insertWithOnConflict(
                ActivityLogSchema.TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE,
            ) == -1L
            if (!ignored) evictOverflow(handle)
            true
        } catch (e: Throwable) {
            // busy_timeout covers contention; anything beyond that is logged and skipped (spec §9).
            Log.w(TAG, "Activity log insert failed: ${keyManager.classifyFailure(e)}", e)
            false
        }
    }

    fun count(): Long = synchronized(lock) {
        val handle = db ?: return 0L
        try {
            handle.rawQuery("SELECT count(*) FROM ${ActivityLogSchema.TABLE}", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Activity log count failed", e)
            0L
        }
    }

    /** V1 Req 29: rolling retention purge of raw behavioral logs. Returns rows deleted. */
    fun purgeOlderThan(epochMillis: Long): Int = synchronized(lock) {
        val handle = db ?: return 0
        try {
            handle.delete(
                ActivityLogSchema.TABLE,
                "created_at < ?",
                arrayOf(epochMillis.toString()),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Activity log purge failed", e)
            0
        }
    }

    /** Bounded WAL growth: PASSIVE checkpoint, safe against reset races. */
    fun checkpoint() {
        synchronized(lock) {
            val handle = db ?: return
            try {
                handle.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        Log.d(TAG, "wal_checkpoint(PASSIVE): busy=${cursor.getLong(1)}")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "wal_checkpoint failed", e)
            }
        }
    }

    fun close() = synchronized(lock) {
        db?.close()
        db = null
        rawKey?.let { Arrays.fill(it, 0.toByte()) }
        rawKey = null
    }

    private fun evictOverflow(handle: SQLiteDatabase) {
        handle.execSQL(
            "DELETE FROM ${ActivityLogSchema.TABLE} WHERE event_id IN (" +
                "SELECT event_id FROM ${ActivityLogSchema.TABLE} " +
                "ORDER BY created_at DESC LIMIT -1 OFFSET ${ActivityLogSchema.MAX_EVENTS})",
        )
    }

    private companion object {
        const val TAG = "MyExodusActivityLog"
        /** A duration, not a cutoff: [purgeOlderThan] takes an absolute epoch
         *  (finding #62 — passing this raw made the open-time purge a no-op). */
        const val RETENTION_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    }
}
