package com.example.myexodus.data.db

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RiskWindow
import com.example.myexodus.recovery.RecoveryResourceCatalog
import java.io.RandomAccessFile
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rules-store migration chain 2→9 (recovery_state, attempt_log, is_enabled, streak tables,
 * daily-usage tracker day-identity reshape, recovery-session + risk-window tables). The v2 baseline is the exact DDL Room generates
 * for version 2 (verbatim copies of the SQL emitted by AppRulesStore_Impl), so the test proves
 * the real upgrade path end to end, including Room's post-migration schema validation (the
 * 2026-08-28 migration failure class) and that pre-existing rule rows survive the 4→5 column
 * add as enabled. The 5→6 specifics are covered on-device by
 * AppRulesStoreMigrationInstrumentedTest (MigrationTestHelper).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppRulesStoreMigrationTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var healthStore: DatabaseHealthStore
    private lateinit var factory: AppRulesStoreFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun migration2to9_addsEveryTableAndRoomAcceptsTheSchema() {
        val dbPath = context.getDatabasePath(AppRulesStoreFactory.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V2_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            // Pre-existing v2 data: a v2-era rule must survive the whole chain as enabled.
            conn.prepare(
                "INSERT INTO user_profile (user_id, name, email, date_of_birth, has_accessibility_permission, " +
                    "has_usage_stats_permission, has_vpn_permission, has_notification_permission, " +
                    "has_device_admin_permission, is_active) VALUES " +
                    "('p-1', 'Test', 't@example.com', 0, 0, 0, 0, 0, 0, 1)",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO restriction_rules (rule_id, profile_id, blocked_package, target_type, is_always_on, " +
                    "daily_limit_ms, active_days_of_week, timezone, block_expires_at, severity_level) VALUES " +
                    "('r-1', 'p-1', 'com.instagram.android', 'APP', 1, 0, '1111111', 'UTC', 0, 'HIGH')",
            ).use { it.step() }
            // v2-era dormant tracker row (enforcement plan §12): must survive 7→8 losslessly
            // with the specified defaults.
            conn.prepare(
                "INSERT INTO daily_usage_tracker (tracker_id, profile_id, package_name, date_epoch, " +
                    "ms_used_today) VALUES ('t-legacy', 'p-1', 'com.instagram.android', 20820, 1000)",
            ).use { it.step() }
            // v2-era coaching step (the pre-8 shape, no kind column): must read back as STEP.
            conn.prepare(
                "INSERT INTO coaching_step (step_id, profile_id, step_order, action_text, " +
                    "associated_trigger_window) VALUES ('c-1', 'p-1', 1, 'Call my brother', '')",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 2").use { it.step() }
        }

        val store = factory.open()
        assertNotNull("Store must open after the whole 2→9 chain", store)
        assertEquals(DatabaseHealth.Ready, healthStore.health.value)

        runBlocking {
            // Fresh recovery_state table: no rows yet, fully usable.
            assertNull(store!!.recoveryStateDao().current())
            // Sibling tables intact and queryable after the migration.
            assertEquals(60L, store.recoveryPolicyDao().current()!!.defaultFrictionSeconds)
            // The v2-era rule survived with the v5 column defaulted to enabled.
            val rule = store.restrictionRuleDao().getById("r-1")
            assertNotNull("Pre-existing rule must survive the chain", rule)
            assertEquals(true, rule!!.isEnabled)
            // attempt_log (v4) exists and is fully usable post-migration.
            val dao = store.attemptLogDao()
            assertEquals(0L, dao.count())
            val id = dao.insert(
                AttemptLog(
                    occurredAtEpochMillis = 1_700_000_000_000L,
                    ruleId = "r-1",
                    blockedTarget = "com.instagram.android",
                    outcome = "BLOCKED",
                ),
            )
            assertEquals(1L, dao.count())
            assertEquals(id, dao.recent(1).single().id)

            // Tracker 7→8: the dormant legacy row survives with the plan's stated defaults —
            // anchored to the migration's local date + device timezone, warnings NOT_DUE,
            // not blocked.
            val tracker = store.dailyUsageTrackerDao()
                .findByKey("p-1", "com.instagram.android", LocalDate.now().toString())
            assertNotNull("Legacy tracker row must survive migration 7→8", tracker)
            assertEquals(1_000L, tracker!!.msUsedToday)
            assertEquals("NOT_DUE", tracker.warning1State)
            assertEquals("NOT_DUE", tracker.warning2State)
            assertEquals(false, tracker.blockedToday)
            assertTrue(tracker.timezoneIdAtAnchor.isNotBlank())

            // Recovery resource 8→9: both new tables are created and fully usable, and the
            // pre-existing coaching_step table gains the kind column defaulted to STEP.
            val sessions = store.recoverySessionDao()
            assertEquals(0L, sessions.count())
            sessions.insert(
                RecoverySession(
                    id = "s-1",
                    technique = RecoveryResourceCatalog.URGE_SURFING,
                    startedAtEpochMs = 1_700_000_000_000L,
                    zoneId = "Africa/Nairobi",
                    entry = RecoverySession.ENTRY_TAB,
                ),
            )
            assertEquals(1, sessions.page(10, 0).size)
            assertEquals(
                "a STARTED row is not mirrorable",
                0,
                sessions.pendingMirror(10).size,
            )

            val windows = store.riskWindowDao()
            assertEquals(0L, windows.count())
            windows.insert(RiskWindow.DEFAULT)
            assertEquals(1, windows.all().size)

            // The legacy coaching_step row (inserted above with the v2 DDL) reads back as STEP.
            val step = store.coachingStepDao().getForProfile("p-1").single()
            assertEquals(CoachingStep.KIND_STEP, step.kind)
        }
    }

    /**
     * The ADR #58 guard, applied to this store: a hand-maintained migration list that misses a
     * step leaves installed devices unable to open their DB. Reads the version Room actually
     * creates for a fresh install (SQLite header offset 60) and requires [ALL_MIGRATIONS] to
     * cover every step to it.
     */
    @Test
    fun allMigrations_coverEveryVersionStepToTheDeclaredSchemaVersion() {
        val scratch = "rules-migration-coverage.db"
        context.deleteDatabase(scratch)
        val db = Room.databaseBuilder(context, AppRulesStore::class.java, scratch).build()
        runBlocking { db.restrictionRuleDao().getActiveRules() }
        db.close()
        val declared = RandomAccessFile(context.getDatabasePath(scratch), "r").use {
            it.seek(60) // SQLite header: user_version, 4 bytes big-endian
            it.readInt()
        }
        context.deleteDatabase(scratch)

        val covered = AppRulesStoreFactory.ALL_MIGRATIONS
            .map { it.startVersion to it.endVersion }
            .toSet()
        (1 until declared).forEach { from ->
            assertTrue(
                "no migration registered for v$from → v${from + 1} (declared schema v$declared)",
                (from to from + 1) in covered,
            )
        }
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    companion object {
        /** Verbatim v2 schema DDL from Room's generated AppRulesStore_Impl (v3 minus recovery_state). */
        private val V2_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `user_profile` (`user_id` TEXT NOT NULL, `name` TEXT NOT NULL, `email` TEXT NOT NULL, `phone_number` TEXT, `date_of_birth` INTEGER NOT NULL, `has_accessibility_permission` INTEGER NOT NULL, `has_usage_stats_permission` INTEGER NOT NULL, `has_vpn_permission` INTEGER NOT NULL, `has_notification_permission` INTEGER NOT NULL, `has_device_admin_permission` INTEGER NOT NULL, `device_public_key` TEXT, `recovery_escrow_key` TEXT, `is_active` INTEGER NOT NULL, PRIMARY KEY(`user_id`))",
            "CREATE TABLE IF NOT EXISTS `restriction_rules` (`rule_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `blocked_package` TEXT NOT NULL, `target_type` TEXT NOT NULL, `is_always_on` INTEGER NOT NULL, `daily_limit_ms` INTEGER NOT NULL, `time_range_start` INTEGER, `time_range_end` INTEGER, `active_days_of_week` TEXT NOT NULL, `timezone` TEXT NOT NULL DEFAULT 'UTC', `block_expires_at` INTEGER NOT NULL, `severity_level` TEXT NOT NULL, PRIMARY KEY(`rule_id`), FOREIGN KEY(`profile_id`) REFERENCES `user_profile`(`user_id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_restriction_rules_profile_id_blocked_package` ON `restriction_rules` (`profile_id`, `blocked_package`)",
            "CREATE TABLE IF NOT EXISTS `daily_usage_tracker` (`tracker_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `package_name` TEXT NOT NULL, `date_epoch` INTEGER NOT NULL, `ms_used_today` INTEGER NOT NULL, PRIMARY KEY(`tracker_id`), FOREIGN KEY(`profile_id`) REFERENCES `user_profile`(`user_id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_daily_usage_tracker_profile_id_package_name_date_epoch` ON `daily_usage_tracker` (`profile_id`, `package_name`, `date_epoch`)",
            "CREATE TABLE IF NOT EXISTS `habit_progress` (`progress_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `target_identifier` TEXT NOT NULL, `current_clean_days_streak` INTEGER NOT NULL, `total_relapses` INTEGER NOT NULL, `access_attempts_count` INTEGER NOT NULL, `habit_duration_years` INTEGER NOT NULL, `onboarding_severity_level` TEXT NOT NULL, `last_relapse_timestamp` INTEGER, `share_access_attempts` INTEGER NOT NULL, `share_relapses` INTEGER NOT NULL, `share_milestones` INTEGER NOT NULL, `assigned_partnership_id` TEXT, PRIMARY KEY(`progress_id`), FOREIGN KEY(`profile_id`) REFERENCES `user_profile`(`user_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `coaching_step` (`step_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `step_order` INTEGER NOT NULL, `action_text` TEXT NOT NULL, `associated_trigger_window` TEXT NOT NULL, PRIMARY KEY(`step_id`), FOREIGN KEY(`profile_id`) REFERENCES `user_profile`(`user_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `policy_version` (`id` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `recovery_policy` (`id` INTEGER NOT NULL, `default_friction_seconds` INTEGER NOT NULL, `override_cooldown_seconds` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            // Real v2 databases carry the seeded singleton rows (onCreate / MIGRATION_1_2).
            "INSERT OR IGNORE INTO policy_version (id, version) VALUES (0, 0)",
            "INSERT OR IGNORE INTO recovery_policy (id, default_friction_seconds, override_cooldown_seconds) VALUES (0, 60, 600)",
        )
    }
}
