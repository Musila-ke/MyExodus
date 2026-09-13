package com.example.myexodus.data.db

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * DDL and PRAGMAs for the encrypted activity log store (Enhanced Mode spec §8 Step 1).
 * Shared by [ActivityLogStore] and [DatabaseMigration] so both open the file identically.
 * The four PRAGMAs are fixed per spec — do not tune without a documented profiling result.
 */
object ActivityLogSchema {
    const val DB_NAME = "myexodus_activity.db"
    const val TABLE = "activity_log"
    const val MAX_EVENTS = 1_000 // V1 Req 27: the local sync queue is capped at 1,000 events.

    const val CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS $TABLE (" +
            "event_id TEXT NOT NULL PRIMARY KEY, " +
            "installation_id TEXT NOT NULL, " +
            "event_type TEXT NOT NULL, " +
            "payload BLOB NOT NULL, " +
            "sync_status TEXT NOT NULL, " +
            "created_at INTEGER NOT NULL)"

    const val CREATE_UNIQUE_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_activity_log_installation_event " +
            "ON $TABLE (installation_id, event_id)"

    /** Applies the fixed PRAGMAs; returns true only if journal_mode reports WAL. */
    fun applyPragmas(db: SQLiteDatabase): Boolean {
        val journalMode = queryString(db, "PRAGMA journal_mode=WAL")
        queryString(db, "PRAGMA synchronous=NORMAL")
        queryString(db, "PRAGMA busy_timeout=5000")
        queryString(db, "PRAGMA secure_delete=ON")
        queryString(db, "PRAGMA cipher_memory_security=ON")
        return journalMode.equals("wal", ignoreCase = true)
    }

    fun createSchema(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
        db.execSQL(CREATE_UNIQUE_INDEX)
    }

    private fun queryString(db: SQLiteDatabase, sql: String): String {
        db.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return ""
    }
}
