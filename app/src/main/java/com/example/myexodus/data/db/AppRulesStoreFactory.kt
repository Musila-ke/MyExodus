package com.example.myexodus.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.runBlocking

/**
 * Builds the plain (unencrypted) [AppRulesStore] Room database (Enhanced Mode spec §8
 * Step 1). Owns open orchestration: corruption handling via [RecoveryManager] and failure
 * classification into [DatabaseHealth]. No key material — FBE protects the file at rest.
 */
class AppRulesStoreFactory(
    private val appContext: Context,
    private val recoveryManager: RecoveryManager,
    private val healthStore: DatabaseHealthStore,
) {
    @Volatile private var database: AppRulesStore? = null
    private val lock = Any()

    /** Thread-safe; returns null when the open failed into a defined recovery state. */
    fun open(): AppRulesStore? = synchronized(lock) {
        database ?: openInternal()?.also { database = it }
    }

    /** WAL growth bound: PASSIVE checkpoint, safe against reset races. */
    fun checkpoint() {
        synchronized(lock) {
            if (database == null) return
            try {
                AndroidSQLiteDriver().open(dbPath()).use { conn ->
                    conn.prepare("PRAGMA wal_checkpoint(PASSIVE)").use { it.step() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "wal_checkpoint failed", e)
            }
        }
    }

    /** User-confirmed reset: destroys all data + key material, then reopens a fresh database. */
    fun resetAndReopen(): AppRulesStore? = synchronized(lock) {
        database?.close()
        database = null
        recoveryManager.resetAllData()
        openInternal()?.also { database = it }
    }

    private fun openInternal(): AppRulesStore? {
        recoveryManager.checkOrphanedCorruptFiles()
        return try {
            build().also { db ->
                // Room 3 creates the schema lazily on first DAO access; the migration and the
                // snapshot publisher INSERT against a store whose schema must exist NOW.
                // runBlocking is fine here: every caller (MyExodusApp init, health worker,
                // device harness) is already off the main thread (caught 2026-08-28 review —
                // migration ran before open and failed with "no such table").
                runBlocking { db.restrictionRuleDao().getActiveRules() }
                // A .corrupt orphan may coexist with a working database; that state must win over Ready.
                if (recoveryManager.hasOrphanedCorruptFiles()) {
                    healthStore.set(DatabaseHealth.RecoverableDataFound)
                } else {
                    healthStore.set(DatabaseHealth.Ready)
                }
            }
        } catch (e: SQLiteDatabaseCorruptException) {
            Log.w(TAG, "Rules DB open classified ConfirmedCorruption; recreating fresh", e)
            recoveryManager.renameCorruptRules()
            try {
                build().also { healthStore.set(DatabaseHealth.RecoverableDataFound) }
            } catch (e2: Throwable) {
                Log.e(TAG, "Recreate after corruption failed", e2)
                healthStore.set(DatabaseHealth.DatabaseIssue)
                null
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Rules DB open failed with SQLiteException", e)
            healthStore.set(DatabaseHealth.DatabaseIssue)
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Rules DB open failed", e)
            healthStore.set(DatabaseHealth.DatabaseIssue)
            null
        }
    }

    private fun build(): AppRulesStore = Room.databaseBuilder(appContext, AppRulesStore::class.java, DB_NAME)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .addCallback(object : RoomDatabase.Callback() {
            override suspend fun onCreate(connection: SQLiteConnection) {
                SchemaConstraints.apply(connection)
                seedPolicyRows(connection)
            }
        })
        .build()

    /** Policy-domain singleton rows (policy_version, recovery_policy) on fresh installs. */
    private fun seedPolicyRows(connection: SQLiteConnection) {
        connection.prepare(
            "INSERT OR IGNORE INTO policy_version (id, version) VALUES (0, 0)",
        ).use { it.step() }
        connection.prepare(
            "INSERT OR IGNORE INTO recovery_policy (id, default_friction_seconds, override_cooldown_seconds) VALUES (0, 60, 600)",
        ).use { it.step() }
    }

    private fun dbPath(): String = appContext.getDatabasePath(DB_NAME).absolutePath

    companion object {
        const val DB_NAME = "myexodus_rules.db"
        private const val TAG = "MyExodusDb"

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Pause switch for the Rules screen (V1 UI Phase 1): disabled rules stay
                // stored but are excluded from the enforcement snapshot (RulesSnapshot.build).
                connection.prepare(
                    "ALTER TABLE restriction_rules ADD COLUMN is_enabled INTEGER NOT NULL DEFAULT 1",
                ).use { it.step() }
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Block/attempt history (blocking-core plan §6): durable local source of
                // truth for stats + partner-alert reconciliation (§14).
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS attempt_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "occurred_at_epoch_millis INTEGER NOT NULL, " +
                        "rule_id TEXT NOT NULL, " +
                        "blocked_target TEXT NOT NULL, " +
                        "outcome TEXT NOT NULL, " +
                        "event_sequence INTEGER)",
                ).use { it.step() }
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Recovery/friction session (plan §8.3): dual-clock deadline + boot-session id.
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS recovery_state (" +
                        "id INTEGER NOT NULL PRIMARY KEY, " +
                        "rule_id TEXT NOT NULL, " +
                        "deadline_wall_clock_utc_millis INTEGER NOT NULL, " +
                        "deadline_elapsed_realtime_this_boot INTEGER NOT NULL, " +
                        "boot_session_id INTEGER NOT NULL, " +
                        "last_override_start_wall_utc_millis INTEGER)",
                ).use { it.step() }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // v1-era SchemaConstraints created a partial unique index (ux_user_active) that
                // the current entity does not declare and Room cannot model — drop it so the
                // migrated schema matches the entity exactly (Room's post-migration validation
                // otherwise rejects every open of a v1 database).
                connection.prepare("DROP INDEX IF EXISTS ux_user_active").use { it.step() }
                // Explicit per-rule timezone (plan §7.1); legacy rules get UTC rather than
                // silently inheriting the device zone that may differ per device.
                connection.prepare(
                    "ALTER TABLE restriction_rules ADD COLUMN timezone TEXT NOT NULL DEFAULT 'UTC'",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS policy_version (id INTEGER NOT NULL, version INTEGER NOT NULL, PRIMARY KEY(id))",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS recovery_policy (id INTEGER NOT NULL, default_friction_seconds INTEGER NOT NULL, override_cooldown_seconds INTEGER NOT NULL, PRIMARY KEY(id))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT OR IGNORE INTO policy_version (id, version) VALUES (0, 0)",
                ).use { it.step() }
                connection.prepare(
                    "INSERT OR IGNORE INTO recovery_policy (id, default_friction_seconds, override_cooldown_seconds) VALUES (0, 60, 600)",
                ).use { it.step() }
            }
        }
    }
}
