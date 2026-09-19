package com.example.myexodus.data.db

import android.content.Context
import androidx.room3.migration.Migration
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration harness for schema bumps (habit-streaks plan §12): builds the real legacy schema
 * from the exported schema JSONs (`app/schemas/`), runs the production migration objects, and
 * asserts Room's post-migration schema validation plus data preservation. The helper runs
 * over the production driver stack (SQLCipher), so the migration SQL is exercised exactly as
 * an upgrading install runs it. Every future schema version adds a test here.
 */
@RunWith(AndroidJUnit4::class)
class AppRulesStoreMigrationInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbFile = File(context.getDatabasePath(DB_NAME).absolutePath)

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        dbFile,
        SQLCipherDriver(TEST_KEY, null, null),
        AppRulesStore::class,
    )

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        deleteDbFiles()
    }

    @After
    fun tearDown() {
        deleteDbFiles()
    }

    @Test
    fun migrate5To6_validatesSchemaAndPreservesData() = runTest {
        val v5 = helper.createDatabase(5)
        seedProfileAndHabit(v5)

        val v6 = helper.runMigrationsAndValidate(6, listOf(AppRulesStoreFactory.MIGRATION_5_6))

        // Pre-existing rows survive with the new column defaulted in.
        assertEquals(1L, count(v6, "user_profile"))
        assertEquals(1L, count(v6, "habit_progress"))
        v6.prepare(
            "SELECT target_identifier, current_clean_days_streak, all_time_max_streak_elapsed_ms " +
                "FROM habit_progress WHERE progress_id = ?",
        ).use { stmt ->
            stmt.bindText(1, "h-1")
            assertTrue(stmt.step())
            assertEquals("Instagram", stmt.getText(0))
            assertEquals(3L, stmt.getLong(1))
            assertEquals("new column must default to no recorded streak", 0L, stmt.getLong(2))
        }

        // The migrated schema is usable end to end: a streak row with its milestone rows.
        exec(
            v6,
            "INSERT INTO recovery_streak (streak_id, habit_id, recovery_start_at, relapsed_at, " +
                "relapse_boot_count, relapse_elapsed_realtime_ms, t_wall_last_observed, " +
                "r_boot_last_observed, boot_count_last_observed, current_streak_elapsed_ms, " +
                "row_version) VALUES ('s-1', 'h-1', 1, NULL, NULL, NULL, 1, 1, 1, 0, 0)",
        )
        exec(
            v6,
            "INSERT INTO milestone_events (streak_id, milestone_day, achieved_at, " +
                "notification_sent_at, acknowledged_at) VALUES ('s-1', 7, NULL, NULL, NULL)",
        )
        assertEquals(1L, count(v6, "recovery_streak"))
        assertEquals(1L, count(v6, "milestone_events"))
    }

    @Test
    fun migrate6To7_validatesSchemaAndBackfillsSelection() = runTest {
        val v6 = helper.createDatabase(6)
        seedProfileAndHabit(v6)

        val v7 = helper.runMigrationsAndValidate(7, listOf(AppRulesStoreFactory.MIGRATION_6_7))

        // Pre-existing rows survive; the sole pre-v7 habit becomes its profile's selection.
        assertEquals(1L, count(v7, "user_profile"))
        assertEquals(1L, count(v7, "habit_progress"))
        v7.prepare(
            "SELECT target_identifier, all_time_max_streak_elapsed_ms, is_selected " +
                "FROM habit_progress WHERE progress_id = ?",
        ).use { stmt ->
            stmt.bindText(1, "h-1")
            assertTrue(stmt.step())
            assertEquals("Instagram", stmt.getText(0))
            assertEquals(0L, stmt.getLong(1))
            assertEquals("the backfill must select the existing habit", 1L, stmt.getLong(2))
        }

        // A habit added after the migration defaults to unselected until onboarding selects it.
        exec(
            v7,
            "INSERT INTO habit_progress (progress_id, profile_id, target_identifier, " +
                "current_clean_days_streak, total_relapses, access_attempts_count, " +
                "habit_duration_years, onboarding_severity_level, last_relapse_timestamp, " +
                "share_access_attempts, share_relapses, share_milestones, assigned_partnership_id) " +
                "VALUES ('h-2', 'profile-1', 'Betting apps', 0, 0, 0, 2, 'MODERATE', NULL, 0, 0, 1, NULL)",
        )
        assertEquals(
            0L,
            v7.prepare("SELECT is_selected FROM habit_progress WHERE progress_id = 'h-2'")
                .use { it.step(); it.getLong(0) },
        )
    }

    @Test
    fun migrate7To8_rebuildsDailyTrackerWithDayIdentity_andPreservesUsage() = runTest {
        val v7 = helper.createDatabase(7)
        seedProfileAndHabit(v7)
        exec(
            v7,
            "INSERT INTO daily_usage_tracker (tracker_id, profile_id, package_name, date_epoch, " +
                "ms_used_today) VALUES ('t-1', 'profile-1', 'com.instagram.android', 123456, 900000)",
        )

        val v8 = helper.runMigrationsAndValidate(8, listOf(AppRulesStoreFactory.MIGRATION_7_8))

        // The rebuild keeps the row; the day identity becomes (local date, IANA timezone) and
        // the per-day warning/block state starts fresh (Decision 20, ADR #39).
        assertEquals(1L, count(v8, "daily_usage_tracker"))
        v8.prepare(
            "SELECT profile_id, package_name, ms_used_today, local_date_anchor, " +
                "timezone_id_at_anchor, warning1_state, warning2_state, blocked_today " +
                "FROM daily_usage_tracker WHERE tracker_id = ?",
        ).use { stmt ->
            stmt.bindText(1, "t-1")
            assertTrue(stmt.step())
            assertEquals("profile-1", stmt.getText(0))
            assertEquals("com.instagram.android", stmt.getText(1))
            assertEquals("usage must survive the rebuild", 900000L, stmt.getLong(2))
            assertEquals("old rows anchor to the migration's local day", LocalDate.now().toString(), stmt.getText(3))
            assertEquals(ZoneId.systemDefault().id, stmt.getText(4))
            assertEquals("NOT_DUE", stmt.getText(5))
            assertEquals("NOT_DUE", stmt.getText(6))
            assertEquals(0L, stmt.getLong(7))
        }

        // The uniqueness key is now the new day identity: a second row for the same
        // (profile, package, local date) is rejected.
        val duplicateRejected = runCatching {
            exec(
                v8,
                "INSERT INTO daily_usage_tracker (tracker_id, profile_id, package_name, " +
                    "local_date_anchor, timezone_id_at_anchor, ms_used_today, warning1_state, " +
                    "warning2_state, blocked_today) VALUES ('t-2', 'profile-1', " +
                    "'com.instagram.android', '${LocalDate.now()}', '${ZoneId.systemDefault().id}', " +
                    "0, 'NOT_DUE', 'NOT_DUE', 0)",
            )
        }.isFailure
        assertTrue("the (profile, package, day) uniqueness key must hold", duplicateRejected)
    }

    @Test
    fun interruptedMigration_rollsBackAtomically_leavingTheOldSchemaAndData() = runTest {
        val v7 = helper.createDatabase(7)
        seedProfileAndHabit(v7)
        exec(
            v7,
            "INSERT INTO daily_usage_tracker (tracker_id, profile_id, package_name, date_epoch, " +
                "ms_used_today) VALUES ('t-1', 'profile-1', 'com.instagram.android', 123456, 900000)",
        )

        // Process death mid-upgrade, deterministically: the production migration's own SQL up
        // to a point, then a throw. Room runs each migration in one transaction, so the
        // partial work must not survive.
        val dying = object : Migration(7, 8) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS daily_usage_tracker_new (" +
                        "tracker_id TEXT NOT NULL, profile_id TEXT NOT NULL, " +
                        "package_name TEXT NOT NULL)",
                ).use { it.step() }
                throw IllegalStateException("process died mid-migration")
            }
        }
        val threw = runCatching { helper.runMigrationsAndValidate(8, listOf(dying)) }.isFailure
        assertTrue("the failure must propagate (the upgrade did not complete)", threw)

        SQLCipherDriver(TEST_KEY, null, null).open(dbFile.path).use { conn ->
            assertEquals(
                "rolled back to the pre-migration version",
                7L,
                conn.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) },
            )
            // The old shape and its data are intact — no half-migrated file.
            assertEquals(1L, count(conn, "daily_usage_tracker"))
            assertEquals(
                900000L,
                conn.prepare("SELECT ms_used_today FROM daily_usage_tracker WHERE tracker_id = 't-1'")
                    .use { it.step(); it.getLong(0) },
            )
            val halfTable = runCatching { count(conn, "daily_usage_tracker_new") }.isSuccess
            assertTrue("the half-created table must not survive the rollback", !halfTable)
        }
    }

    /** Valid for any schema ≥5: the columns it names are not changed by 5→6 or 6→7. */
    private fun seedProfileAndHabit(conn: SQLiteConnection) {
        exec(
            conn,
            "INSERT INTO user_profile (user_id, name, email, phone_number, date_of_birth, " +
                "has_accessibility_permission, has_usage_stats_permission, has_vpn_permission, " +
                "has_notification_permission, has_device_admin_permission, device_public_key, " +
                "recovery_escrow_key, is_active) VALUES " +
                "('profile-1', 'Legacy User', 'u@example.com', NULL, 0, 0, 0, 0, 0, 0, NULL, NULL, 1)",
        )
        exec(
            conn,
            "INSERT INTO habit_progress (progress_id, profile_id, target_identifier, " +
                "current_clean_days_streak, total_relapses, access_attempts_count, " +
                "habit_duration_years, onboarding_severity_level, last_relapse_timestamp, " +
                "share_access_attempts, share_relapses, share_milestones, assigned_partnership_id) " +
                "VALUES ('h-1', 'profile-1', 'Instagram', 3, 1, 5, 2, 'MODERATE', NULL, 1, 1, 1, NULL)",
        )
    }

    private fun exec(conn: SQLiteConnection, sql: String) {
        conn.prepare(sql).use { it.step() }
    }

    private fun count(conn: SQLiteConnection, table: String): Long =
        conn.prepare("SELECT count(*) FROM $table").use { it.step(); it.getLong(0) }

    private fun deleteDbFiles() {
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        dbFile.delete()
    }

    private companion object {
        const val DB_NAME = "migration-helper-test.db"
        val TEST_KEY = ByteArray(32) { 7 }
    }
}
