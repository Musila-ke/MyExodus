package com.example.myexodus.data.db

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device migration suite (Enhanced Mode spec §8 Step 1 / §12): seeds a genuine
 * pre-split combined SQLCipher database, runs the split migration, and asserts row-count
 * parity per table, legacy-file deletion, and failure preservation.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val legacyKeys = AndroidDatabaseKeyManager(context)
    private val activityKeys = ActivityLogKeyManager(context)

    private lateinit var recovery: RecoveryManager
    private lateinit var healthStore: DatabaseHealthStore

    @Before
    fun setUp() {
        healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(context, legacyKeys, activityKeys, RulesStoreKeyManager(context), healthStore)
        recovery.resetAllData()
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    private fun rulesFactory(): AppRulesStoreFactory = AppRulesStoreFactory(context, recovery, healthStore)

    @Test
    fun migration_movesAllRowsWithParityAndDeletesLegacy() = runTest {
        seedLegacyDatabase()

        // The rules store must exist (schema present) before the migration copies into it.
        val rules = rulesFactory().open()
        assertTrue(rules != null)

        val migrated = DatabaseMigration(context, legacyKeys, recovery, activityKeys, rulesFactory()).runOnceIfNeeded()
        assertTrue("Migration should succeed", migrated)

        assertEquals(1L, rulesCount("user_profile"))
        assertEquals(2L, rulesCount("restriction_rules"))
        assertEquals(1L, rulesCount("daily_usage_tracker"))
        assertEquals(1L, rulesCount("habit_progress"))
        assertEquals(1L, rulesCount("coaching_step"))

        // Row-count parity alone passed while every value was shifted one column left
        // (out-of-range reads return junk instead of throwing), so assert real values.
        assertEquals("Legacy User", rulesText("user_profile", "name", "user_id", "profile-1"))
        assertEquals("com.instagram.android", rulesText("restriction_rules", "blocked_package", "rule_id", "rule-1"))
        assertEquals("Go for a walk", rulesText("coaching_step", "action_text", "step_id", "c-1"))

        val log = ActivityLogStore(context, activityKeys)
        assertTrue(log.open())
        assertEquals(3L, log.count())

        assertFalse("Legacy database must be deleted after verified migration",
            recovery.legacyDbFile.exists())
        val legacyPrefs = context.getSharedPreferences("myexodus_db_keys", Context.MODE_PRIVATE)
        assertFalse("Legacy wrapped DEK must be cleared", legacyPrefs.contains("dek_iv"))
        assertFalse(legacyPrefs.contains("dek_blob"))
    }

    @Test
    fun migration_isIdempotent() = runTest {
        seedLegacyDatabase()
        rulesFactory().open()
        val migration = DatabaseMigration(context, legacyKeys, recovery, activityKeys, rulesFactory())

        assertTrue(migration.runOnceIfNeeded())
        // A second run (e.g. concurrent bootstrap) must no-op.
        assertTrue(migration.runOnceIfNeeded())
        assertEquals(2L, rulesCount("restriction_rules"))
    }

    @Test
    fun migration_failurePreservesLegacyFile() = runTest {
        // A file that is not a database at all: open fails, migration must preserve it.
        recovery.legacyDbFile.writeText("not a database")
        rulesFactory().open()

        val migrated = DatabaseMigration(context, legacyKeys, recovery, activityKeys, rulesFactory()).runOnceIfNeeded()
        assertFalse("Migration must fail on an unreadable legacy database", migrated)
        assertTrue("Legacy file must be preserved on failure", recovery.legacyDbFile.exists())
        assertEquals("No rows may be copied on failure", 0L, rulesCount("restriction_rules"))
    }

    private fun rulesCount(table: String): Long {
        return AndroidSQLiteDriver().open(recovery.rulesDbFile.absolutePath).use { conn ->
            conn.prepare("SELECT count(*) FROM $table").use { stmt ->
                stmt.step()
                stmt.getLong(0)
            }
        }
    }

    private fun rulesText(table: String, column: String, idColumn: String, id: String): String? {
        return AndroidSQLiteDriver().open(recovery.rulesDbFile.absolutePath).use { conn ->
            conn.prepare("SELECT $column FROM $table WHERE $idColumn = ?").use { stmt ->
                stmt.bindText(1, id)
                if (stmt.step()) stmt.getText(0) else null
            }
        }
    }

    // --- legacy database seeding (mirrors the pre-split combined schema) ---

    private fun seedLegacyDatabase() {
        System.loadLibrary("sqlcipher")
        val dek = legacyKeys.getOrCreateDek()
        val path = recovery.legacyDbFile.absolutePath
        SQLCipherDriver(dek, null, null).open(path).use { conn ->
            exec(
                conn,
                "CREATE TABLE user_profile (user_id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, " +
                    "email TEXT NOT NULL, phone_number TEXT, date_of_birth INTEGER NOT NULL, " +
                    "has_accessibility_permission INTEGER NOT NULL, has_usage_stats_permission INTEGER NOT NULL, " +
                    "has_vpn_permission INTEGER NOT NULL, has_notification_permission INTEGER NOT NULL, " +
                    "has_device_admin_permission INTEGER NOT NULL, device_public_key TEXT, " +
                    "recovery_escrow_key TEXT, is_active INTEGER NOT NULL)",
            )
            exec(
                conn,
                "CREATE TABLE restriction_rules (rule_id TEXT PRIMARY KEY NOT NULL, " +
                    "profile_id TEXT NOT NULL, blocked_package TEXT NOT NULL, target_type TEXT NOT NULL, " +
                    "is_always_on INTEGER NOT NULL, daily_limit_ms INTEGER NOT NULL, " +
                    "time_range_start INTEGER, time_range_end INTEGER, active_days_of_week TEXT NOT NULL, " +
                    "block_expires_at INTEGER NOT NULL, severity_level TEXT NOT NULL)",
            )
            exec(
                conn,
                "CREATE TABLE daily_usage_tracker (tracker_id TEXT PRIMARY KEY NOT NULL, " +
                    "profile_id TEXT NOT NULL, package_name TEXT NOT NULL, date_epoch INTEGER NOT NULL, " +
                    "ms_used_today INTEGER NOT NULL)",
            )
            exec(
                conn,
                "CREATE TABLE habit_progress (progress_id TEXT PRIMARY KEY NOT NULL, " +
                    "profile_id TEXT NOT NULL, target_identifier TEXT NOT NULL, " +
                    "current_clean_days_streak INTEGER NOT NULL, total_relapses INTEGER NOT NULL, " +
                    "access_attempts_count INTEGER NOT NULL, habit_duration_years INTEGER NOT NULL, " +
                    "onboarding_severity_level TEXT NOT NULL, last_relapse_timestamp INTEGER, " +
                    "share_access_attempts INTEGER NOT NULL, share_relapses INTEGER NOT NULL, " +
                    "share_milestones INTEGER NOT NULL, assigned_partnership_id TEXT)",
            )
            exec(
                conn,
                "CREATE TABLE coaching_step (step_id TEXT PRIMARY KEY NOT NULL, " +
                    "profile_id TEXT NOT NULL, step_order INTEGER NOT NULL, action_text TEXT NOT NULL, " +
                    "associated_trigger_window TEXT NOT NULL)",
            )
            exec(
                conn,
                "CREATE TABLE domain_event (event_id TEXT PRIMARY KEY NOT NULL, " +
                    "installation_id TEXT NOT NULL, event_type TEXT NOT NULL, payload BLOB NOT NULL, " +
                    "sync_status TEXT NOT NULL, created_at INTEGER NOT NULL)",
            )

            insert(
                conn,
                "INSERT INTO user_profile (user_id, name, email, phone_number, date_of_birth, " +
                    "has_accessibility_permission, has_usage_stats_permission, has_vpn_permission, " +
                    "has_notification_permission, has_device_admin_permission, device_public_key, " +
                    "recovery_escrow_key, is_active) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
                "profile-1", "Legacy User", "legacy@example.com", 0L, 0L, 0L, 0L, 0L, 0L, 1L,
            )
            insert(
                conn,
                "INSERT INTO restriction_rules (rule_id, profile_id, blocked_package, target_type, " +
                    "is_always_on, daily_limit_ms, time_range_start, time_range_end, active_days_of_week, " +
                    "block_expires_at, severity_level) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?)",
                "rule-1", "profile-1", "com.instagram.android", "APP", 1L, 0L, "1111111", 0L, "HIGH",
            )
            insert(
                conn,
                "INSERT INTO restriction_rules (rule_id, profile_id, blocked_package, target_type, " +
                    "is_always_on, daily_limit_ms, time_range_start, time_range_end, active_days_of_week, " +
                    "block_expires_at, severity_level) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?)",
                "rule-2", "profile-1", "betting.example.com", "WEBSITE", 1L, 0L, "1111111", 0L, "CRITICAL",
            )
            insert(
                conn,
                "INSERT INTO daily_usage_tracker (tracker_id, profile_id, package_name, date_epoch, " +
                    "ms_used_today) VALUES (?, ?, ?, ?, ?)",
                "t-1", "profile-1", "com.instagram.android", 20820, 1_000L,
            )
            insert(
                conn,
                "INSERT INTO habit_progress (progress_id, profile_id, target_identifier, " +
                    "current_clean_days_streak, total_relapses, access_attempts_count, " +
                    "habit_duration_years, onboarding_severity_level, last_relapse_timestamp, " +
                    "share_access_attempts, share_relapses, share_milestones, assigned_partnership_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, NULL)",
                "h-1", "profile-1", "Instagram", 3L, 1L, 5L, 2L, "MODERATE", 1L, 1L, 1L,
            )
            insert(
                conn,
                "INSERT INTO coaching_step (step_id, profile_id, step_order, action_text, " +
                    "associated_trigger_window) VALUES (?, ?, ?, ?, ?)",
                "c-1", "profile-1", 1L, "Go for a walk", "ANY_TIME",
            )
            for (i in 1..3) {
                insert(
                    conn,
                    "INSERT INTO domain_event (event_id, installation_id, event_type, payload, " +
                        "sync_status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    "event-$i", "inst-1", "BLOCK_ATTEMPT", byteArrayOf(1, 2, 3), "PENDING",
                    System.currentTimeMillis() - i,
                )
            }
        }
    }

    private fun exec(conn: SQLiteConnection, sql: String) {
        conn.prepare(sql).use { it.step() }
    }

    private fun insert(conn: SQLiteConnection, sql: String, vararg args: Any) {
        conn.prepare(sql).use { stmt ->
            for ((index, arg) in args.withIndex()) {
                when (arg) {
                    is Long -> stmt.bindLong(index + 1, arg)
                    is Int -> stmt.bindLong(index + 1, arg.toLong())
                    is String -> stmt.bindText(index + 1, arg)
                    is ByteArray -> stmt.bindBlob(index + 1, arg)
                    else -> error("Unsupported bind type ${arg::class}")
                }
            }
            stmt.step()
        }
    }
}
