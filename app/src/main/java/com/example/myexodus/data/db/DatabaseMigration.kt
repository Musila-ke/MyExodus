package com.example.myexodus.data.db

import android.content.ContentValues
import android.content.Context
import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Arrays
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

/**
 * One-time split migration (Enhanced Mode spec §8 Step 1): opens the pre-split combined
 * SQLCipher database (`myexodus.db`) with the existing DEK, copies every table's rows into
 * the new stores (rules/V1 tables → plain [AppRulesStore], activity log → encrypted
 * [ActivityLogStore] re-keyed with a freshly generated Keystore-wrapped key), verifies row
 * counts match per table, and only then deletes the old file and its key material.
 *
 * On any mismatch or read failure the old file is preserved and the migration retries on the
 * next launch — a database is never deleted that wasn't fully verified. INSERT OR IGNORE
 * makes re-runs idempotent. The rules store must already be open (schema present) before
 * this runs; the activity log key is guaranteed fresh because it never existed pre-split.
 * The rules destination is opened through [AppRulesStoreFactory.currentDriver] because the
 * store is SQLCipher-encrypted since the habit-streaks plan (§8 Step 1) — a plaintext open
 * of an encrypted file fails.
 */
class DatabaseMigration(
    private val appContext: Context,
    private val legacyKeyManager: DatabaseKeyManager,
    private val recoveryManager: RecoveryManager,
    private val activityLogKeyManager: ActivityLogKeyManager,
    private val rulesStoreFactory: AppRulesStoreFactory,
) {
    private val lock = Any()
    @Volatile private var completed = false

    /** Returns true when no migration is needed or it finished successfully. */
    fun runOnceIfNeeded(): Boolean = synchronized(lock) {
        if (completed) return true
        val legacyFile = recoveryManager.legacyDbFile
        if (!legacyFile.exists()) {
            completed = true
            return true
        }
        val ok = try {
            runMigration(legacyFile)
        } catch (e: Throwable) {
            Log.e(TAG, "Migration failed; old database preserved for retry", e)
            false
        }
        if (ok) {
            completed = true
            Log.i(TAG, "Migration verified and complete; old database deleted")
        }
        ok
    }

    private fun runMigration(legacyFile: File): Boolean {
        System.loadLibrary("sqlcipher")
        val dek = legacyKeyManager.getOrCreateDek()
        try {
            return migrateInternal(legacyFile, dek)
        } finally {
            // The DEK outlives the migration otherwise — same zeroing contract as logKey.
            Arrays.fill(dek, 0.toByte())
        }
    }

    private fun migrateInternal(legacyFile: File, dek: ByteArray): Boolean {
        // The pre-split legacy file predates raw-key mode (ADR #153) and must be opened with
        // its own legacy keying — a raw-passphrase open would fail this read.
        SQLCipherDriver(dek, null, null).open(legacyFile.absolutePath).use { legacy ->
            val rulesPath = appContext.getDatabasePath(AppRulesStoreFactory.DB_NAME)
            rulesStoreFactory.currentDriver().open(rulesPath.absolutePath).use { rules ->
                val activityPath = appContext.getDatabasePath(ActivityLogSchema.DB_NAME)
                val logKey = activityLogKeyManager.getOrCreateKey()
                val logRaw = SqlCipherRawKey.passphraseFor(logKey)
                try {
                    // Born raw-keyed (ADR #153): the destination must match what ActivityLogStore opens.
                    val activity = SQLiteDatabase.openOrCreateDatabase(activityPath, logRaw, null, null)
                    try {
                        ActivityLogSchema.applyPragmas(activity)
                        ActivityLogSchema.createSchema(activity)

                        var verified = true
                        for (spec in TABLES) {
                            verified = copyTable(legacy, rules, spec) && verified
                        }
                        verified = copyDailyUsageTracker(legacy, rules) && verified
                        verified = copyActivityLog(legacy, activity) && verified
                        if (!verified) return false
                    } finally {
                        activity.close()
                        Arrays.fill(logRaw, 0.toByte())
                    }
                    // Zeroing must come AFTER verifiedCounts: countActivity reopens the DB
                    // with logKey, and a zeroed key throws SQLiteNotADatabaseException — the
                    // migration then failed on every launch and the legacy file was never
                    // finalized (caught 2026-08-28 review).
                    if (!verifiedCounts(legacy, rules, activityPath, logKey)) return false
                } finally {
                    Arrays.fill(logKey, 0.toByte())
                }
            }
        }
        deleteLegacy(legacyFile)
        legacyKeyManager.resetKeyMaterial()
        return true
    }

    private fun copyTable(
        legacy: SQLiteConnection,
        rules: SQLiteConnection,
        spec: TableSpec,
    ): Boolean {
        if (!tableExists(legacy, spec.old)) return true
        val columns = spec.columns.joinToString(", ") { it.name }
        val placeholders = spec.columns.joinToString(", ") { "?" }
        legacy.prepare("SELECT $columns FROM ${spec.old}").use { reader ->
            rules.prepare("INSERT OR IGNORE INTO ${spec.new} ($columns) VALUES ($placeholders)").use { writer ->
                while (reader.step()) {
                    for ((index, column) in spec.columns.withIndex()) {
                        bind(reader, writer, index, column)
                    }
                    writer.step()
                    // reset() after each row per the androidx driver contract — the zetetic
                    // writer would otherwise insert only the first row of each table
                    // (device-verified 2026-09-14); a no-op on the framework driver.
                    writer.reset()
                }
            }
        }
        return true
    }

    /**
     * Reader and writer have different index bases in the androidx.sqlite driver API: reads are
     * 0-based (the driver delegates to a framework cursor — out-of-range reads return junk, they
     * don't throw), binds are 1-based. A single shared index shifted every copied value one
     * column left, silently (device-verified 2026-09-14; the same class of bug as
     * PlaintextToEncryptedMigration.bind).
     */
    private fun bind(reader: SQLiteStatement, writer: SQLiteStatement, readIndex: Int, column: Column) {
        val writeIndex = readIndex + 1
        when (column.kind) {
            Kind.TEXT -> {
                // getText is @NonNull-annotated but the cursor still returns null for NULL
                // columns; isNull is the honest check (2026-09-14, same class of bug as LONG).
                if (reader.isNull(readIndex)) writer.bindNull(writeIndex)
                else writer.bindText(writeIndex, reader.getText(readIndex))
            }
            Kind.LONG -> {
                // getLong coerces NULL to 0; the instrumented migration test seeds NULL
                // time_range_start/end, and 0 would silently mean "midnight" (2026-08-28 review).
                if (reader.isNull(readIndex)) writer.bindNull(writeIndex)
                else writer.bindLong(writeIndex, reader.getLong(readIndex))
            }
            Kind.BLOB -> writer.bindBlob(writeIndex, reader.getBlob(readIndex))
        }
    }

    /**
     * daily_usage_tracker left the generic spec list at v8 (enforcement plan v2 §8 Step 1):
     * the legacy UTC epoch-day identity is retired for the (local date, IANA timezone) pair,
     * so the copy stamps the plan's stated migration defaults instead of carrying date_epoch
     * across. Legacy tracker rows are dormant (nothing ever fed usage), which is what makes
     * anchoring them to the migration moment lossless in practice.
     */
    private fun copyDailyUsageTracker(legacy: SQLiteConnection, rules: SQLiteConnection): Boolean {
        if (!tableExists(legacy, "daily_usage_tracker")) return true
        legacy.prepare(
            "SELECT tracker_id, profile_id, package_name, ms_used_today FROM daily_usage_tracker",
        ).use { reader ->
            rules.prepare(
                "INSERT OR IGNORE INTO daily_usage_tracker (tracker_id, profile_id, package_name, " +
                    "local_date_anchor, timezone_id_at_anchor, ms_used_today, warning1_state, " +
                    "warning2_state, blocked_today) VALUES (?, ?, ?, ?, ?, ?, 'NOT_DUE', 'NOT_DUE', 0)",
            ).use { writer ->
                while (reader.step()) {
                    writer.bindText(1, reader.getText(0) ?: "")
                    writer.bindText(2, reader.getText(1) ?: "")
                    writer.bindText(3, reader.getText(2) ?: "")
                    writer.bindText(4, LocalDate.now().toString())
                    writer.bindText(5, ZoneId.systemDefault().id)
                    writer.bindLong(6, reader.getLong(3))
                    writer.step()
                    writer.reset()
                }
            }
        }
        return true
    }

    private fun copyActivityLog(legacy: SQLiteConnection, activity: SQLiteDatabase): Boolean {
        if (!tableExists(legacy, "domain_event")) return true
        legacy.prepare(
            "SELECT event_id, installation_id, event_type, payload, sync_status, created_at " +
                "FROM domain_event",
        ).use { reader ->
            val values = ContentValues()
            while (reader.step()) {
                values.clear()
                // 0-based reads (see bind); the old 1-based indices read the payload BLOB as a
                // string and crashed every migration (device-verified 2026-09-14).
                values.put("event_id", reader.getText(0) ?: "")
                values.put("installation_id", reader.getText(1) ?: "")
                values.put("event_type", reader.getText(2) ?: "")
                values.put("payload", reader.getBlob(3))
                values.put("sync_status", reader.getText(4) ?: "")
                values.put("created_at", reader.getLong(5))
                activity.insertWithOnConflict(
                    ActivityLogSchema.TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
        }
        return true
    }

    private fun verifiedCounts(
        legacy: SQLiteConnection,
        rules: SQLiteConnection,
        activityPath: File,
        logKey: ByteArray,
    ): Boolean {
        var ok = true
        for (spec in TABLES) {
            val oldCount = if (tableExists(legacy, spec.old)) count(legacy, spec.old) else 0L
            val newCount = count(rules, spec.new)
            ok = ok && oldCount == newCount
            Log.i(TAG, "Migration verify ${spec.old}->${spec.new}: $oldCount == $newCount")
        }
        val oldTracker =
            if (tableExists(legacy, "daily_usage_tracker")) count(legacy, "daily_usage_tracker") else 0L
        val newTracker = count(rules, "daily_usage_tracker")
        ok = ok && oldTracker == newTracker
        Log.i(TAG, "Migration verify daily_usage_tracker: $oldTracker == $newTracker")
        val oldLog = if (tableExists(legacy, "domain_event")) count(legacy, "domain_event") else 0L
        val newLog = countActivity(activityPath, logKey)
        ok = ok && oldLog == newLog
        Log.i(TAG, "Migration verify domain_event->activity_log: $oldLog == $newLog")
        return ok
    }

    private fun countActivity(activityPath: File, logKey: ByteArray): Long {
        val raw = SqlCipherRawKey.passphraseFor(logKey)
        val activity = SQLiteDatabase.openOrCreateDatabase(activityPath, raw, null, null)
        try {
            activity.rawQuery("SELECT count(*) FROM ${ActivityLogSchema.TABLE}", null).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
        } finally {
            activity.close()
            Arrays.fill(raw, 0.toByte())
        }
        return 0L
    }

    private fun count(conn: SQLiteConnection, table: String): Long =
        conn.prepare("SELECT count(*) FROM $table").use { it.step(); it.getLong(0) }

    private fun tableExists(conn: SQLiteConnection, table: String): Boolean =
        conn.prepare("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?").use { stmt ->
            stmt.bindText(1, table)
            stmt.step()
            stmt.getLong(0) > 0
        }

    private fun deleteLegacy(legacyFile: File) {
        File(legacyFile.path + "-wal").delete()
        File(legacyFile.path + "-shm").delete()
        legacyFile.delete()
    }

    private data class TableSpec(val old: String, val new: String, val columns: List<Column>)

    private data class Column(val name: String, val kind: Kind)

    private enum class Kind { TEXT, LONG, BLOB }

    private companion object {
        const val TAG = "MyExodusMigration"

        val TABLES = listOf(
            TableSpec("user_profile", "user_profile", listOf(
                Column("user_id", Kind.TEXT), Column("name", Kind.TEXT), Column("email", Kind.TEXT),
                Column("phone_number", Kind.TEXT), Column("date_of_birth", Kind.LONG),
                Column("has_accessibility_permission", Kind.LONG),
                Column("has_usage_stats_permission", Kind.LONG),
                Column("has_vpn_permission", Kind.LONG),
                Column("has_notification_permission", Kind.LONG),
                Column("has_device_admin_permission", Kind.LONG),
                Column("device_public_key", Kind.TEXT),
                Column("recovery_escrow_key", Kind.TEXT), Column("is_active", Kind.LONG),
            )),
            TableSpec("restriction_rules", "restriction_rules", listOf(
                Column("rule_id", Kind.TEXT), Column("profile_id", Kind.TEXT),
                Column("blocked_package", Kind.TEXT), Column("target_type", Kind.TEXT),
                Column("is_always_on", Kind.LONG), Column("daily_limit_ms", Kind.LONG),
                Column("time_range_start", Kind.LONG), Column("time_range_end", Kind.LONG),
                Column("active_days_of_week", Kind.TEXT), Column("block_expires_at", Kind.LONG),
                Column("severity_level", Kind.TEXT),
            )),
            TableSpec("habit_progress", "habit_progress", listOf(
                Column("progress_id", Kind.TEXT), Column("profile_id", Kind.TEXT),
                Column("target_identifier", Kind.TEXT),
                Column("current_clean_days_streak", Kind.LONG),
                Column("total_relapses", Kind.LONG), Column("access_attempts_count", Kind.LONG),
                Column("habit_duration_years", Kind.LONG),
                Column("onboarding_severity_level", Kind.TEXT),
                Column("last_relapse_timestamp", Kind.LONG),
                Column("share_access_attempts", Kind.LONG),
                Column("share_relapses", Kind.LONG), Column("share_milestones", Kind.LONG),
                Column("assigned_partnership_id", Kind.TEXT),
            )),
            TableSpec("coaching_step", "coaching_step", listOf(
                Column("step_id", Kind.TEXT), Column("profile_id", Kind.TEXT),
                Column("step_order", Kind.LONG), Column("action_text", Kind.TEXT),
                Column("associated_trigger_window", Kind.TEXT),
            )),
        )
    }
}
