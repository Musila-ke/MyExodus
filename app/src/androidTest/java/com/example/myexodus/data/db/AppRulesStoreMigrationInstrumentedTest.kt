package com.example.myexodus.data.db

import android.content.Context
import androidx.room3.migration.Migration
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
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
    fun migrate8To9_addsRecoveryTables_andDefaultsExistingStepsToStepKind() = runTest {
        val v8 = helper.createDatabase(8)
        seedProfileAndHabit(v8)
        exec(
            v8,
            "INSERT INTO coaching_step (step_id, profile_id, step_order, action_text, " +
                "associated_trigger_window) VALUES ('c-1', 'profile-1', 1, 'Call my brother', '')",
        )

        val v9 = helper.runMigrationsAndValidate(9, listOf(AppRulesStoreFactory.MIGRATION_8_9))

        // The pre-existing coaching item keeps its text and reads back as a plain STEP (Req 20:
        // everything authored before the kind column existed was a step).
        assertEquals(1L, count(v9, "coaching_step"))
        v9.prepare("SELECT action_text, kind FROM coaching_step WHERE step_id = ?").use { stmt ->
            stmt.bindText(1, "c-1")
            assertTrue(stmt.step())
            assertEquals("Call my brother", stmt.getText(0))
            assertEquals("STEP", stmt.getText(1))
        }

        // Both new tables are usable end to end after the migration, including the autoincrement
        // primary key (risk_window) and the terminal-state mirror filter (recovery_session).
        assertEquals(0L, count(v9, "recovery_session"))
        exec(
            v9,
            "INSERT INTO recovery_session (id, technique, state, started_at_epoch_ms, zone_id, " +
                "entry, false_alarm) VALUES ('s-1', 'urge_surfing', 'COMPLETED', 1, " +
                "'Africa/Nairobi', 'TAB', 0)",
        )
        exec(
            v9,
            "INSERT INTO risk_window (start_minute, end_minute, days_mask, enabled) " +
                "VALUES (1200, 360, 127, 1)",
        )
        assertEquals(1L, count(v9, "recovery_session"))
        assertEquals(1L, count(v9, "risk_window"))
        assertEquals(
            1L,
            v9.prepare("SELECT id FROM risk_window").use { it.step(); it.getLong(0) },
        )
    }

    @Test
    fun migrate9To10_addsTheUsageTable_andPreservesEveryEarlierTable() = runTest {
        val v9 = helper.createDatabase(9)
        seedProfileAndHabit(v9)

        val v10 = helper.runMigrationsAndValidate(10, listOf(AppRulesStoreFactory.MIGRATION_9_10))

        // The new table is empty and usable; the DDL is what Room validates on open.
        assertEquals(0L, count(v10, "rule_usage_daily"))
        exec(
            v10,
            "INSERT INTO rule_usage_daily (rule_id, revision, day_key, active_ms) " +
                "VALUES ('r-1', 0, '2026-09-20', 5000)",
        )
        assertEquals(1L, count(v10, "rule_usage_daily"))

        // The composite key rejects a duplicate (rule_id, revision, day_key).
        val duplicateRejected = runCatching {
            exec(
                v10,
                "INSERT INTO rule_usage_daily (rule_id, revision, day_key, active_ms) " +
                    "VALUES ('r-1', 0, '2026-09-20', 9000)",
            )
        }.isFailure
        assertTrue("the (rule_id, revision, day_key) key must hold", duplicateRejected)

        // Additive only: every earlier table and its rows survived.
        assertEquals(1L, count(v10, "user_profile"))
        assertEquals(1L, count(v10, "habit_progress"))
        assertEquals(
            3L,
            v10.prepare("SELECT current_clean_days_streak FROM habit_progress WHERE progress_id = 'h-1'")
                .use { it.step(); it.getLong(0) },
        )
    }

    /**
     * Usage-ledger plan §12: the v10 → v11 upgrade against a production-shaped v10 database,
     * exercising §8.10's day-aware seeding for the current-day and stale scalars (the zero case
     * is the fourth tracker row, which must simply produce nothing).
     */
    @Test
    fun migrate10To11_carriesOnlyTodaysScalar_intoTheLedger() = runTest {
        val v10 = helper.createDatabase(10)
        seedProfileAndHabit(v10)
        exec(
            v10,
            "INSERT INTO coaching_step (step_id, profile_id, step_order, action_text, " +
                "associated_trigger_window) VALUES ('c-1', 'profile-1', 1, 'Call my brother', '')",
        )
        val today = LocalDate.now().toString()
        val yesterday = LocalDate.now().minusDays(1).toString()
        val zone = ZoneId.systemDefault().id
        // Today with usage: carried. Yesterday with usage: stale, dropped. Today at zero: nothing.
        insertTracker(v10, "t-1", "com.instagram.android", 7_200_000L, today, zone)
        insertTracker(v10, "t-2", "com.google.android.youtube", 3_600_000L, yesterday, zone)
        insertTracker(v10, "t-3", "com.whatsapp", 0L, today, zone)

        val v11 = helper.runMigrationsAndValidate(11, listOf(AppRulesStoreFactory.MIGRATION_10_11))

        // One carryover row, for the current-day scalar only, shaped as an aggregate with no
        // reconstructable interval; the carried value is the scalar itself.
        assertEquals(1L, count(v11, "usage_ledger"))
        v11.prepare(
            "SELECT event_id, kind, package_name, day_key, credited_ms, elapsed_start_ms, " +
                "elapsed_end_ms, source FROM usage_ledger",
        ).use { stmt ->
            assertTrue(stmt.step())
            assertEquals("migration_seed:com.instagram.android", stmt.getText(0))
            assertEquals(UsageLedgerKind.MIGRATION_CARRYOVER.toLong(), stmt.getLong(1))
            assertEquals("com.instagram.android", stmt.getText(2))
            assertEquals("the carried scalar must land on the day the v10 anchor named", today, stmt.getText(3))
            assertEquals(7_200_000L, stmt.getLong(4))
            assertEquals("a carryover has no sub-timeline", 0L, stmt.getLong(5))
            assertEquals(0L, stmt.getLong(6))
            assertEquals(UsageLedgerSource.MAINTENANCE, stmt.getText(7))
        }

        // The unique event_id index is live: the same identity cannot be seeded twice.
        val duplicateRejected = runCatching {
            exec(
                v11,
                "INSERT INTO usage_ledger (event_id, kind, wall_clock_ts_ms, elapsed_start_ms, " +
                    "elapsed_end_ms, boot_count, package_name, day_key, credited_ms, source, " +
                    "source_version, references_event_id) VALUES " +
                    "('migration_seed:com.instagram.android', 5, 1, 0, 0, 0, " +
                    "'com.instagram.android', '$today', 1, 'maintenance', 'test', NULL)",
            )
        }.isFailure
        assertTrue("the unique event_id key must hold", duplicateRejected)

        // Additive only: the tracker rows the seed read from are untouched, and every earlier
        // table survived the upgrade.
        assertEquals(3L, count(v11, "daily_usage_tracker"))
        assertPreExistingDataSurvives(v11, "after the ledger upgrade")
    }

    /**
     * R10: the migration's DDL is plain (no `IF NOT EXISTS`), so an unexpected pre-existing
     * ledger table fails the upgrade loudly instead of being silently masked — and the failure
     * rolls back to a v10 database with everything intact.
     */
    @Test
    fun migrate10To11_failsLoudly_whenALedgerTableAlreadyExists() = runTest {
        val v10 = helper.createDatabase(10)
        seedProfileAndHabit(v10)
        exec(v10, "CREATE TABLE usage_ledger (id INTEGER)")

        val threw = runCatching {
            helper.runMigrationsAndValidate(11, listOf(AppRulesStoreFactory.MIGRATION_10_11))
        }.isFailure
        assertTrue("a pre-existing ledger table must fail the migration, not be masked", threw)

        SQLCipherDriver(TEST_KEY, null, null).open(dbFile.path).use { conn ->
            assertEquals(
                "the failed upgrade rolls back to the pre-migration version",
                10L,
                conn.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) },
            )
            assertEquals("every earlier table survives the rollback", 1L, count(conn, "user_profile"))
            assertEquals(1L, count(conn, "habit_progress"))
        }
    }

    /**
     * ADR #111: the v11 → v12 per-pair mirror links. Purely additive — both tables are created
     * empty and usable, the composite key makes each (row, pair) link exactly-once, and every
     * earlier table and row survives.
     */
    @Test
    fun migrate11To12_addsThePerPairMirrorLinkTables() = runTest {
        val v11 = helper.createDatabase(11)
        seedProfileAndHabit(v11)
        seedCoachingStep(v11)
        exec(
            v11,
            "INSERT INTO attempt_log (occurred_at_epoch_millis, rule_id, blocked_target, " +
                "outcome, event_sequence) VALUES (1, 'r-1', 'com.instagram.android', 'BLOCKED', NULL)",
        )

        val v12 = helper.runMigrationsAndValidate(12, listOf(AppRulesStoreFactory.MIGRATION_11_12))

        assertEquals(0L, count(v12, "attempt_mirror_link"))
        assertEquals(0L, count(v12, "recovery_mirror_link"))
        exec(v12, "INSERT INTO attempt_mirror_link (attempt_id, pair_id, sequence) VALUES (1, 'pair-a', 1)")
        exec(v12, "INSERT INTO recovery_mirror_link (session_id, pair_id, sequence) VALUES ('s-1', 'pair-b', 2)")
        assertEquals(1L, count(v12, "attempt_mirror_link"))
        assertEquals(1L, count(v12, "recovery_mirror_link"))

        // The composite key rejects a duplicate (row, pair) link — exactly-once per pair.
        val duplicateRejected = runCatching {
            exec(v12, "INSERT INTO attempt_mirror_link (attempt_id, pair_id, sequence) VALUES (1, 'pair-a', 9)")
        }.isFailure
        assertTrue("the (attempt_id, pair_id) key must hold", duplicateRejected)

        // Additive only: the source row and every earlier table survived.
        assertEquals("the attempt row survives the upgrade", 1L, count(v12, "attempt_log"))
        assertPreExistingDataSurvives(v12, "after the mirror-link upgrade")
    }

    /**
     * ADR #111 rollback, same rationale as the 10→9 leg: a v11 build reinstalled over a database
     * that briefly ran at v12 drops only the mirror-link tables (every other table and row
     * survives), and the re-upgrade rebuilds them empty.
     */
    @Test
    fun migrate11To12To11_dropsOnlyTheMirrorLinks_andReupgradesEmpty() = runTest {
        val v11 = helper.createDatabase(11)
        seedProfileAndHabit(v11)
        seedCoachingStep(v11)

        val v12 = helper.runMigrationsAndValidate(12, listOf(AppRulesStoreFactory.MIGRATION_11_12))
        exec(v12, "INSERT INTO attempt_mirror_link (attempt_id, pair_id, sequence) VALUES (1, 'pair-a', 1)")

        val down = helper.runMigrationsAndValidate(11, listOf(AppRulesStoreFactory.MIGRATION_12_11))
        assertTrue(
            "the downgrade must drop the attempt link table",
            runCatching { count(down, "attempt_mirror_link") }.isFailure,
        )
        assertTrue(
            "the downgrade must drop the recovery link table",
            runCatching { count(down, "recovery_mirror_link") }.isFailure,
        )
        assertPreExistingDataSurvives(down, "after the mirror-link downgrade")

        val up = helper.runMigrationsAndValidate(12, listOf(AppRulesStoreFactory.MIGRATION_11_12))
        assertEquals(
            "the re-upgrade rebuilds the tables empty",
            0L,
            count(up, "attempt_mirror_link"),
        )
        assertEquals(0L, count(up, "recovery_mirror_link"))
        assertPreExistingDataSurvives(up, "after the re-upgrade")
    }

    /**
     * Partner-visible activity layer: the v12 → v13 journal, per-pair links, and received-state
     * projection. Purely additive — all three tables are created empty and usable, the journal
     * row pends per pair until that pair is linked, the projection's monotonic guard holds, and
     * every earlier table and row survives.
     */
    @Test
    fun migrate12To13_addsThePartnerActivityTables() = runTest {
        val v12 = helper.createDatabase(12)
        seedProfileAndHabit(v12)
        seedCoachingStep(v12)
        exec(
            v12,
            "INSERT INTO attempt_mirror_link (attempt_id, pair_id, sequence) VALUES (1, 'pair-a', 1)",
        )

        val v13 = helper.runMigrationsAndValidate(13, listOf(AppRulesStoreFactory.MIGRATION_12_13))

        assertEquals(0L, count(v13, "partner_activity_outbox"))
        assertEquals(0L, count(v13, "partner_activity_link"))
        assertEquals(0L, count(v13, "partner_activity_projection"))

        exec(
            v13,
            "INSERT INTO partner_activity_outbox (event_id, kind, text, occurred_at_ms, days_free, " +
                "relapse_count, milestones_reached, coaching_progress, protocol_version, " +
                "schema_version, created_at_ms) VALUES " +
                "('evt-1', 'streak_updated', '', 100, 4, 1, '7,30', 2, 1, 1, 100)",
        )
        exec(
            v13,
            "INSERT INTO partner_activity_link (event_id, pair_id, sequence) VALUES ('evt-1', 'pair-a', 9)",
        )
        exec(
            v13,
            "INSERT INTO partner_activity_projection (pair_id, days_free, relapse_count, " +
                "milestones_reached, coaching_progress, last_event_occurred_at_ms, last_sequence, " +
                "pinned_message_text, pinned_message_at_ms) VALUES ('pair-a', 4, 1, '7,30', 2, 100, 9, NULL, NULL)",
        )
        assertEquals(1L, count(v13, "partner_activity_outbox"))
        assertEquals(1L, count(v13, "partner_activity_link"))
        assertEquals(1L, count(v13, "partner_activity_projection"))

        // Composite key rejects a duplicate (event, pair) link — exactly-once per pair.
        val duplicateRejected = runCatching {
            exec(
                v13,
                "INSERT INTO partner_activity_link (event_id, pair_id, sequence) VALUES ('evt-1', 'pair-a', 99)",
            )
        }.isFailure
        assertTrue("the (event_id, pair_id) key must hold", duplicateRejected)

        // Additive only: the mirror link and every earlier table survived.
        assertEquals("the mirror link survives the upgrade", 1L, count(v13, "attempt_mirror_link"))
        assertPreExistingDataSurvives(v13, "after the activity-layer upgrade")
    }

    /**
     * The v13 rollback, same rationale as the other downgrade legs: a v12 build reinstalled over
     * a database that briefly ran at v13 drops only the three activity tables (every other table
     * and row survives), and the re-upgrade rebuilds them empty.
     */
    @Test
    fun migrate13To12_dropsOnlyThePartnerActivityTables_andReupgradesEmpty() = runTest {
        val v12 = helper.createDatabase(12)
        seedProfileAndHabit(v12)
        seedCoachingStep(v12)

        val v13 = helper.runMigrationsAndValidate(13, listOf(AppRulesStoreFactory.MIGRATION_12_13))
        exec(
            v13,
            "INSERT INTO partner_activity_outbox (event_id, kind, text, occurred_at_ms, days_free, " +
                "relapse_count, milestones_reached, coaching_progress, protocol_version, " +
                "schema_version, created_at_ms) VALUES " +
                "('evt-1', 'relapse_recorded', '', 100, 0, 1, '', 0, 1, 1, 100)",
        )

        val down = helper.runMigrationsAndValidate(12, listOf(AppRulesStoreFactory.MIGRATION_13_12))
        listOf(
            "partner_activity_outbox",
            "partner_activity_link",
            "partner_activity_projection",
        ).forEach { table ->
            assertTrue(
                "the downgrade must drop $table",
                runCatching { count(down, table) }.isFailure,
            )
        }
        assertPreExistingDataSurvives(down, "after the activity-layer downgrade")

        val up = helper.runMigrationsAndValidate(13, listOf(AppRulesStoreFactory.MIGRATION_12_13))
        assertEquals("the re-upgrade rebuilds the journal empty", 0L, count(up, "partner_activity_outbox"))
        assertEquals(0L, count(up, "partner_activity_link"))
        assertEquals(0L, count(up, "partner_activity_projection"))
        assertPreExistingDataSurvives(up, "after the re-upgrade")
    }

    /**
     * #96 message addressing: the v13 → v14 additive column. Pre-existing journal rows must read
     * back untargeted (they fan out — the pre-v14 semantics), and the transformed predicate is
     * live, not just the DDL: an addressed row is invisible to every other pair.
     */
    @Test
    fun migrate13To14_addsTargetPairId_andPreservesJournalRows() = runTest {
        val v13 = helper.createDatabase(13)
        seedProfileAndHabit(v13)
        seedCoachingStep(v13)
        exec(
            v13,
            "INSERT INTO partner_activity_outbox (event_id, kind, text, occurred_at_ms, days_free, " +
                "relapse_count, milestones_reached, coaching_progress, protocol_version, " +
                "schema_version, created_at_ms) VALUES " +
                "('evt-legacy', 'streak_updated', '', 100, 4, 1, '7,30', 2, 1, 1, 100)",
        )

        val v14 = helper.runMigrationsAndValidate(14, listOf(AppRulesStoreFactory.MIGRATION_13_14))

        assertEquals("the pre-v14 row survives", 1L, count(v14, "partner_activity_outbox"))
        v14.prepare(
            "SELECT kind, target_pair_id FROM partner_activity_outbox WHERE event_id = 'evt-legacy'",
        ).use { stmt ->
            assertTrue(stmt.step())
            assertEquals("streak_updated", stmt.getText(0))
            assertTrue("a pre-v14 row must read back untargeted", stmt.isNull(1))
        }
        exec(
            v14,
            "INSERT INTO partner_activity_outbox (event_id, kind, text, occurred_at_ms, days_free, " +
                "relapse_count, milestones_reached, coaching_progress, protocol_version, " +
                "schema_version, created_at_ms, target_pair_id) VALUES " +
                "('evt-addressed', 'motivational_message', 'Keep going', 200, 4, 1, '7,30', 2, 1, 1, 200, 'pair-b')",
        )
        assertEquals(
            "pair-b pends on the untargeted fan-out row and its own addressed row",
            2L,
            v14.prepare(
                "SELECT count(*) FROM partner_activity_outbox WHERE target_pair_id IS NULL " +
                    "OR target_pair_id = 'pair-b'",
            ).use { it.step(); it.getLong(0) },
        )
        assertEquals(
            "pair-a pends on the fan-out row only, never pair-b's addressed row",
            1L,
            v14.prepare(
                "SELECT count(*) FROM partner_activity_outbox WHERE target_pair_id IS NULL " +
                    "OR target_pair_id = 'pair-a'",
            ).use { it.step(); it.getLong(0) },
        )
        assertPreExistingDataSurvives(v14, "after the addressing upgrade")
    }

    /**
     * The v14 rollback: a v13 build reinstalled over a database that briefly ran at v14 must
     * open, and Room rejects unknown columns — so the addressing column is physically dropped
     * by recreating the table, keeping every journal row and the declared index.
     */
    @Test
    fun migrate14To13_dropsOnlyTheTargetColumn_andKeepsJournalRows() = runTest {
        val v13 = helper.createDatabase(13)
        seedProfileAndHabit(v13)
        seedCoachingStep(v13)

        val v14 = helper.runMigrationsAndValidate(14, listOf(AppRulesStoreFactory.MIGRATION_13_14))
        exec(
            v14,
            "INSERT INTO partner_activity_outbox (event_id, kind, text, occurred_at_ms, days_free, " +
                "relapse_count, milestones_reached, coaching_progress, protocol_version, " +
                "schema_version, created_at_ms, target_pair_id) VALUES " +
                "('evt-addressed', 'motivational_message', 'Keep going', 200, 4, 1, '7,30', 2, 1, 1, 200, 'pair-b')",
        )

        val down = helper.runMigrationsAndValidate(13, listOf(AppRulesStoreFactory.MIGRATION_14_13))

        assertTrue(
            "the downgrade must drop the addressing column",
            runCatching {
                down.prepare("SELECT target_pair_id FROM partner_activity_outbox LIMIT 1")
                    .use { it.step() }
            }.isFailure,
        )
        down.prepare(
            "SELECT text FROM partner_activity_outbox WHERE event_id = 'evt-addressed'",
        ).use { stmt ->
            assertTrue("the journal row survives the recreate", stmt.step())
            assertEquals("Keep going", stmt.getText(0))
        }
        // By name, not by count: the TEXT primary key also leaves a `sqlite_autoindex_…` row in
        // sqlite_master, so counting every index on the table would admit the wrong one.
        assertEquals(
            "the index the recreate dropped must be back (Room validates indices on open)",
            1L,
            down.prepare(
                "SELECT count(*) FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_partner_activity_outbox_created_at_ms'",
            ).use { it.step(); it.getLong(0) },
        )
        assertPreExistingDataSurvives(down, "after the addressing downgrade")

        // The re-upgrade restores the v14 shape with the surviving row intact and untargeted.
        val up = helper.runMigrationsAndValidate(14, listOf(AppRulesStoreFactory.MIGRATION_13_14))
        assertEquals(1L, count(up, "partner_activity_outbox"))
        up.prepare(
            "SELECT target_pair_id FROM partner_activity_outbox WHERE event_id = 'evt-addressed'",
        ).use { stmt ->
            assertTrue(stmt.step())
            assertTrue("the re-added column defaults the surviving row to fan-out", stmt.isNull(0))
        }
    }

    /**
     * Sharing settings (ADR #134, 2026-10-04): the v14 → v15 additive columns. The harness's
     * own rule is a test per schema bump — this leg and its rollback below were the pair the
     * v15 bump skipped (found 2026-10-16 review). Pins the migration's whole point: the stored
     * 0 on `share_relapses` flips to 1 (the owner-reported suppression repair), the new
     * visibility master and profile-wide flags default to on, the journal's display columns
     * read back NULL on pre-v15 rows ("no name to show"), and every pre-existing row survives.
     */
    @Test
    fun migrate14To15_addsSharingColumns_andFlipsTheStoredRelapseFlag() = runTest {
        val v14 = helper.createDatabase(14)
        seedProfileAndHabit(v14)
        seedCoachingStep(v14)
        // The stored 0 the migration exists to repair (no pre-#134 surface ever wrote it).
        exec(v14, "UPDATE habit_progress SET share_relapses = 0 WHERE progress_id = 'h-1'")
        exec(
            v14,
            "INSERT INTO partner_activity_outbox (event_id, kind, target_pair_id, text, " +
                "occurred_at_ms, days_free, relapse_count, milestones_reached, coaching_progress, " +
                "protocol_version, schema_version, created_at_ms) VALUES " +
                "('evt-legacy', 'streak_updated', NULL, '', 100, 4, 1, '7,30', 2, 1, 1, 100)",
        )

        val v15 = helper.runMigrationsAndValidate(15, listOf(AppRulesStoreFactory.MIGRATION_14_15))

        v15.prepare(
            "SELECT target_identifier, current_clean_days_streak, share_relapses, share_streak " +
                "FROM habit_progress WHERE progress_id = 'h-1'",
        ).use { stmt ->
            assertTrue(stmt.step())
            assertEquals("Instagram", stmt.getText(0))
            assertEquals(3L, stmt.getLong(1))
            assertEquals("the stored 0 must flip to on (ADR #134 repair)", 1L, stmt.getLong(2))
            assertEquals("the new visibility master defaults to on", 1L, stmt.getLong(3))
        }
        v15.prepare(
            "SELECT share_check_ins, share_recovery FROM user_profile WHERE user_id = 'profile-1'",
        ).use { stmt ->
            assertTrue(stmt.step())
            assertEquals("profile-wide check-ins consent defaults to on", 1L, stmt.getLong(0))
            assertEquals("profile-wide recovery consent defaults to on", 1L, stmt.getLong(1))
        }
        v15.prepare(
            "SELECT text, habit_label, ended_days, milestone_day FROM partner_activity_outbox " +
                "WHERE event_id = 'evt-legacy'",
        ).use { stmt ->
            assertTrue("the journal row survives", stmt.step())
            assertTrue("a pre-v15 row has no habit name", stmt.isNull(1))
            assertTrue("a pre-v15 row has no streak length", stmt.isNull(2))
            assertTrue("a pre-v15 row has no milestone day", stmt.isNull(3))
        }
        assertPreExistingDataSurvives(v15, "after the sharing-settings upgrade")
    }

    /**
     * The v15 rollback, same rationale as the other downgrade legs: a v14 build reinstalled
     * over a database that briefly ran at v15 must open. The three added columns are physically
     * dropped by recreating the tables and copying rows — every row survives with the values
     * of the columns v14 knows, and the declared outbox index is back.
     */
    @Test
    fun migrate14To15To14_dropsOnlyTheNewColumns_andKeepsEveryRow() = runTest {
        val v14 = helper.createDatabase(14)
        seedProfileAndHabit(v14)
        seedCoachingStep(v14)

        val v15 = helper.runMigrationsAndValidate(15, listOf(AppRulesStoreFactory.MIGRATION_14_15))
        exec(
            v15,
            "INSERT INTO partner_activity_outbox (event_id, kind, target_pair_id, text, " +
                "occurred_at_ms, days_free, relapse_count, milestones_reached, coaching_progress, " +
                "protocol_version, schema_version, created_at_ms, habit_label, ended_days, " +
                "milestone_day) VALUES " +
                "('evt-named', 'streak_updated', 'pair-a', 'Keep going', 200, 4, 1, '7,30', 2, 1, 1, 200, " +
                "'Instagram', 30, 7)",
        )

        val down = helper.runMigrationsAndValidate(14, listOf(AppRulesStoreFactory.MIGRATION_15_14))

        assertTrue(
            "the downgrade must drop habit_progress.share_streak",
            runCatching {
                down.prepare("SELECT share_streak FROM habit_progress LIMIT 1").use { it.step() }
            }.isFailure,
        )
        assertTrue(
            "the downgrade must drop user_profile.share_check_ins",
            runCatching {
                down.prepare("SELECT share_check_ins FROM user_profile LIMIT 1").use { it.step() }
            }.isFailure,
        )
        assertTrue(
            "the downgrade must drop the journal display columns",
            runCatching {
                down.prepare("SELECT habit_label FROM partner_activity_outbox LIMIT 1").use { it.step() }
            }.isFailure,
        )
        down.prepare(
            "SELECT text FROM partner_activity_outbox WHERE event_id = 'evt-named'",
        ).use { stmt ->
            assertTrue("the journal row survives the recreate", stmt.step())
            assertEquals("Keep going", stmt.getText(0))
        }
        down.prepare(
            "SELECT share_relapses FROM habit_progress WHERE progress_id = 'h-1'",
        ).use { stmt ->
            assertTrue(stmt.step())
            assertEquals("columns that survive keep their values", 1L, stmt.getLong(0))
        }
        assertEquals(
            "the index the recreate dropped must be back (Room validates indices on open)",
            1L,
            down.prepare(
                "SELECT count(*) FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_partner_activity_outbox_created_at_ms'",
            ).use { it.step(); it.getLong(0) },
        )
        assertPreExistingDataSurvives(down, "after the sharing-settings downgrade")
    }

    /**
     * The v16 consent matrix (ADR #135) on the real SQLCipher stack: Room validates the whole
     * migrated file against 16.json (the strongest drift check for the hand-written recreate),
     * the legacy consent columns are physically gone, the pair tables exist empty ("nothing
     * until you choose"), pre-existing data survives, and the pre-v16 journal row reads back
     * with a NULL consent key (it will match no pair's slice).
     */
    @Test
    fun migrate15To16_dropsLegacyConsentColumns_andCreatesThePairMatrix() = runTest {
        val v15 = helper.createDatabase(15)
        seedProfileAndHabit(v15)
        seedCoachingStep(v15)
        exec(v15, "UPDATE habit_progress SET share_relapses = 0, share_milestones = 1 WHERE progress_id = 'h-1'")
        exec(v15, "UPDATE user_profile SET share_check_ins = 0, share_recovery = 1 WHERE user_id = 'profile-1'")
        exec(
            v15,
            "INSERT INTO partner_activity_outbox (event_id, kind, target_pair_id, text, " +
                "occurred_at_ms, days_free, relapse_count, milestones_reached, habit_label, " +
                "ended_days, milestone_day, coaching_progress, protocol_version, schema_version, " +
                "created_at_ms) VALUES ('evt-legacy', 'streak_updated', NULL, '', 100, 4, 1, " +
                "'7,30', 'Instagram', NULL, NULL, 2, 1, 2, 100)",
        )

        val v16 = helper.runMigrationsAndValidate(16, listOf(AppRulesStoreFactory.MIGRATION_15_16))

        listOf("share_access_attempts", "share_relapses", "share_milestones", "share_streak").forEach { col ->
            assertTrue(
                "the legacy per-habit column $col must be gone",
                runCatching {
                    v16.prepare("SELECT $col FROM habit_progress LIMIT 1").use { it.step() }
                }.isFailure,
            )
        }
        listOf("share_check_ins", "share_recovery").forEach { col ->
            assertTrue(
                "the legacy per-profile column $col must be gone",
                runCatching {
                    v16.prepare("SELECT $col FROM user_profile LIMIT 1").use { it.step() }
                }.isFailure,
            )
        }
        assertEquals("the matrix starts empty — nothing until you choose", 0L, count(v16, "pair_streak_sharing"))
        assertEquals(0L, count(v16, "pair_profile_sharing"))
        assertEquals("the pre-v16 journal row survives", 1L, count(v16, "partner_activity_outbox"))
        v16.prepare(
            "SELECT habit_progress_id FROM partner_activity_outbox WHERE event_id = 'evt-legacy'",
        ).use { stmt ->
            assertTrue(stmt.step())
            assertTrue("a pre-v16 row carries no consent key", stmt.isNull(0))
        }
        assertPreExistingDataSurvives(v16, "after the v16 upgrade")
    }

    /**
     * The v16 rollback: a v15 build reinstalled over a briefly-v16 database must open. Each
     * legacy flag is reconstructed as "any pair had it on" before the pair tables are dropped —
     * exercised with two pairs whose flags disagree, so the aggregation (not a single row) is
     * what's pinned. `share_access_attempts` was never writable by any UI → 0.
     */
    @Test
    fun migrate16To15_reconstructsLegacyFlags_andDropsOnlyThePairTables() = runTest {
        val v15 = helper.createDatabase(15)
        seedProfileAndHabit(v15)
        seedCoachingStep(v15)

        val v16 = helper.runMigrationsAndValidate(16, listOf(AppRulesStoreFactory.MIGRATION_15_16))
        exec(
            v16,
            "INSERT INTO pair_streak_sharing (pair_id, habit_progress_id, share_streak, " +
                "share_relapses, share_milestones, streak_enabled_since_ms, " +
                "relapse_enabled_since_ms, milestone_enabled_since_ms) VALUES " +
                "('pair-1', 'h-1', 0, 0, 0, 0, 0, 0), ('pair-2', 'h-1', 1, 1, 0, 150, 160, 0)",
        )
        exec(
            v16,
            "INSERT INTO pair_profile_sharing (pair_id, share_checkins, share_recovery, " +
                "checkins_enabled_since_ms, recovery_enabled_since_ms) VALUES ('pair-1', 0, 1, 0, 170)",
        )

        val down = helper.runMigrationsAndValidate(15, listOf(AppRulesStoreFactory.MIGRATION_16_15))

        assertTrue(
            "the downgrade must drop pair_streak_sharing",
            runCatching { count(down, "pair_streak_sharing") }.isFailure,
        )
        assertTrue(
            "the downgrade must drop pair_profile_sharing",
            runCatching { count(down, "pair_profile_sharing") }.isFailure,
        )
        down.prepare(
            "SELECT share_relapses, share_milestones, share_streak, share_access_attempts " +
                "FROM habit_progress WHERE progress_id = 'h-1'",
        ).use { stmt ->
            assertTrue(stmt.step())
            assertEquals("pair-2 had relapses on", 1L, stmt.getLong(0))
            assertEquals("no pair had milestones on", 0L, stmt.getLong(1))
            assertEquals("pair-2 had the streak on", 1L, stmt.getLong(2))
            assertEquals("access attempts were never writable", 0L, stmt.getLong(3))
        }
        down.prepare(
            "SELECT share_check_ins, share_recovery FROM user_profile WHERE user_id = 'profile-1'",
        ).use { stmt ->
            assertTrue(stmt.step())
            assertEquals("pair-1 had check-ins off", 0L, stmt.getLong(0))
            assertEquals("pair-1 had recovery on", 1L, stmt.getLong(1))
        }
        assertPreExistingDataSurvives(down, "after the v16 downgrade")
    }

    @Test
    fun migrate10To9_dropsOnlyTheUsageTable_leavingAValidV9Database() = runTest {
        val v10 = helper.createDatabase(10)
        seedProfileAndHabit(v10)
        exec(
            v10,
            "INSERT INTO rule_usage_daily (rule_id, revision, day_key, active_ms) " +
                "VALUES ('r-1', 0, '2026-09-20', 5000)",
        )

        // D-33's rollback: a v9 APK (mode OFF) reopening a database that briefly ran at v10.
        val v9 = helper.runMigrationsAndValidate(9, listOf(AppRulesStoreFactory.MIGRATION_10_9))

        val usageGone = runCatching { count(v9, "rule_usage_daily") }.isFailure
        assertTrue("the rollback must drop the usage table", usageGone)
        assertEquals("every other table survives the downgrade", 1L, count(v9, "user_profile"))
        assertEquals(1L, count(v9, "habit_progress"))
        assertEquals(
            3L,
            v9.prepare("SELECT current_clean_days_streak FROM habit_progress WHERE progress_id = 'h-1'")
                .use { it.step(); it.getLong(0) },
        )
    }

    /**
     * §12.2 I-21b / AC-15: the D-33 rollback is not a one-shot. A user who installs the metering
     * build, rolls back, then reinstalls runs this cycle for real — the downgrade must leave a
     * database the *old* APK can open, and the re-upgrade must rebuild the new table from the
     * schema JSON without a destructive recreate of everything else.
     */
    @Test
    fun migrate9To10To9To10_survivesRepeatedRollbackCycles() = runTest {
        val v9 = helper.createDatabase(9)
        seedProfileAndHabit(v9)
        exec(
            v9,
            "INSERT INTO coaching_step (step_id, profile_id, step_order, action_text, " +
                "associated_trigger_window) VALUES ('c-1', 'profile-1', 1, 'Call my brother', '')",
        )

        val up1 = helper.runMigrationsAndValidate(10, listOf(AppRulesStoreFactory.MIGRATION_9_10))
        assertEquals(0L, count(up1, "rule_usage_daily"))
        exec(
            up1,
            "INSERT INTO rule_usage_daily (rule_id, revision, day_key, active_ms) " +
                "VALUES ('r-1', 0, '2026-09-20', 5000)",
        )

        val down1 = helper.runMigrationsAndValidate(9, listOf(AppRulesStoreFactory.MIGRATION_10_9))
        assertTrue(
            "the first rollback must drop the usage table",
            runCatching { count(down1, "rule_usage_daily") }.isFailure,
        )
        assertPreExistingDataSurvives(down1, "after the first downgrade")

        val up2 = helper.runMigrationsAndValidate(10, listOf(AppRulesStoreFactory.MIGRATION_9_10))
        assertEquals(
            "the re-upgrade rebuilds the table empty, not half-restored",
            0L,
            count(up2, "rule_usage_daily"),
        )
        exec(
            up2,
            "INSERT INTO rule_usage_daily (rule_id, revision, day_key, active_ms) " +
                "VALUES ('r-1', 0, '2026-09-21', 7000)",
        )
        assertEquals(1L, count(up2, "rule_usage_daily"))
        assertPreExistingDataSurvives(up2, "after the second upgrade")
    }

    /** The pre-v10 tables and their rows — [seedProfileAndHabit] plus a `coaching_step` row the
     *  caller must insert itself. (A missing row surfaces as SQLCipher's misleading
     *  "step() must be called before reading column values", not as a clean assertion.) */
    private fun assertPreExistingDataSurvives(conn: SQLiteConnection, step: String) {
        assertEquals("user_profile row lost $step", 1L, count(conn, "user_profile"))
        assertEquals("habit_progress row lost $step", 1L, count(conn, "habit_progress"))
        assertEquals(
            "habit_progress data changed $step",
            3L,
            conn.prepare("SELECT current_clean_days_streak FROM habit_progress WHERE progress_id = 'h-1'")
                .use { it.step(); it.getLong(0) },
        )
        assertEquals(
            "coaching_step data changed $step",
            "Call my brother",
            conn.prepare("SELECT action_text FROM coaching_step WHERE step_id = 'c-1'")
                .use { it.step(); it.getText(0) },
        )
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

    private fun insertTracker(
        conn: SQLiteConnection,
        trackerId: String,
        pkg: String,
        msUsedToday: Long,
        localDate: String,
        zoneId: String,
    ) = exec(
        conn,
        "INSERT INTO daily_usage_tracker (tracker_id, profile_id, package_name, " +
            "local_date_anchor, timezone_id_at_anchor, ms_used_today, warning1_state, " +
            "warning2_state, blocked_today) VALUES ('$trackerId', 'profile-1', '$pkg', " +
            "'$localDate', '$zoneId', $msUsedToday, 'NOT_DUE', 'NOT_DUE', 0)",
    )

    /** Valid for any schema ≥5: the columns it names are not changed by 5→6 or 6→7. */
    /** The `coaching_step` row [assertPreExistingDataSurvives] checks — callers that assert
     *  legacy-data survival must seed it first (the v9+ shape: `kind` defaults to STEP). */
    private fun seedCoachingStep(conn: SQLiteConnection) {
        exec(
            conn,
            "INSERT INTO coaching_step (step_id, profile_id, step_order, action_text, " +
                "associated_trigger_window) VALUES ('c-1', 'profile-1', 1, 'Call my brother', '')",
        )
    }

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
