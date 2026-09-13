package com.example.myexodus.data.db

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.example.myexodus.data.db.entity.DomainEvent
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.util.Arrays

/**
 * Encrypted activity/attempt log (Enhanced Mode spec §8 Step 1): raw SQLCipher file opened
 * via the char[] password overload of [SQLiteDatabase.openOrCreateDatabase]. The password is
 * decrypted from Keystore immediately before the open and zeroed right after keying. Never
 * on the accessibility-event hot path — all writes are background and best-effort: a failing
 * log write is logged and skipped, never thrown at the caller (spec §9).
 */
class ActivityLogStore(
    private val appContext: Context,
    private val keyManager: ActivityLogKeyManager,
) {
    @Volatile private var db: SQLiteDatabase? = null
    private val lock = Any()

    val isOpen: Boolean get() = synchronized(lock) { db != null }

    /**
     * Opens (or creates) the encrypted log file. Returns false on failure after recovery
     * attempts (key loss → fresh empty log; corruption → preserved .corrupt copy, fresh
     * empty log). Failures are logged, never thrown.
     */
    fun open(): Boolean = synchronized(lock) {
        if (db != null) return true
        val path = appContext.getDatabasePath(ActivityLogSchema.DB_NAME)
        return try {
            val key = keyManager.getOrCreateKey()
            try {
                val opened = SQLiteDatabase.openOrCreateDatabase(path, key, null, null)
                val walOk = ActivityLogSchema.applyPragmas(opened)
                ActivityLogSchema.createSchema(opened)
                if (!walOk) {
                    Log.e(TAG, "Activity log open: WAL not active; closing")
                    opened.close()
                    false
                } else {
                    db = opened
                    Log.i(TAG, "Activity log open: WAL confirmed")
                    purgeOlderThan(RETENTION_MS)
                    true
                }
            } finally {
                Arrays.fill(key, 0.toByte())
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
            try {
                val opened = SQLiteDatabase.openOrCreateDatabase(path, freshKey, null, null)
                val walOk = ActivityLogSchema.applyPragmas(opened)
                ActivityLogSchema.createSchema(opened)
                // Same WAL gate as open(): a non-WAL handle would grow unboundedly (2026-08-28 review).
                if (!walOk) {
                    Log.e(TAG, "Activity log fresh recreate: WAL not active; closing")
                    opened.close()
                    false
                } else {
                    db = opened
                    Log.i(TAG, "Activity log recreated fresh after failure")
                    true
                }
            } finally {
                Arrays.fill(freshKey, 0.toByte())
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
        const val RETENTION_MS = 14L * 24 * 60 * 60 * 1000
    }
}
