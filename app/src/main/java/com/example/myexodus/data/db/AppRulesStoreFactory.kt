package com.example.myexodus.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.example.myexodus.BuildConfig
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking

/**
 * Builds the [AppRulesStore] Room database (Enhanced Mode spec §8 Step 1). Owns open
 * orchestration: corruption handling via [RecoveryManager], failure classification into
 * [DatabaseHealth], and — when [encryption] is configured (production) — the one-time
 * plaintext→encrypted conversion plus the SQLCipher driver (habit-streaks plan §8 Steps
 * 0/1). A null [encryption] runs the store plaintext (Robolectric tests; no native lib).
 */
class AppRulesStoreFactory(
    private val appContext: Context,
    private val recoveryManager: RecoveryManager,
    private val healthStore: DatabaseHealthStore,
    private val encryption: RulesStoreEncryption? = null,
) {
    @Volatile private var database: AppRulesStore? = null

    /** How the currently/last-opened file is sealed; drives [currentDriver] and restore logic. */
    @Volatile private var encryptedMode = false
    private val lock = Any()

    /** Thread-safe; returns null when the open failed into a defined recovery state. */
    fun open(): AppRulesStore? = synchronized(lock) {
        database ?: openInternal()?.also { database = it }
    }

    /**
     * Driver matching how the currently-open rules file is sealed, for components that must
     * open the same file directly (the pre-split legacy migration writes into it).
     */
    fun currentDriver(): SQLiteDriver =
        if (encryptedMode && encryption != null) encryption.newDriver() else AndroidSQLiteDriver()

    /** WAL growth bound: PASSIVE checkpoint, safe against reset races. */
    fun checkpoint() {
        synchronized(lock) {
            if (database == null) return
            try {
                currentDriver().open(dbPath()).use { conn ->
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

        val outcome = encryption?.prepareForOpen(::buildTempDestination) ?: ConversionOutcome.Encrypted
        if (outcome == ConversionOutcome.Pending) {
            // Non-destructive pending state (plan §8 Step 0): the store keeps running on the
            // plaintext database; the conversion is retried on the next launch.
            Log.w(TAG, "Encryption conversion pending; opening plaintext store")
            encryptedMode = false
            return openInMode(encrypted = false, pendingUpgrade = true)
        }
        encryptedMode = encryption != null
        return openInMode(encrypted = encryptedMode, pendingUpgrade = false)
    }

    private fun openInMode(encrypted: Boolean, pendingUpgrade: Boolean): AppRulesStore? {
        return try {
            build(encrypted = encrypted).also { db ->
                // Room 3 creates the schema lazily on first DAO access; the migration and the
                // snapshot publisher INSERT against a store whose schema must exist NOW.
                // runBlocking is fine here: every caller (MyExodusApp init, health worker,
                // device harness) is already off the main thread (caught 2026-08-28 review —
                // migration ran before open and failed with "no such table").
                runBlocking { db.restrictionRuleDao().getActiveRules() }
                if (encrypted) encryption?.onOpenedSuccessfully()
                // A .corrupt orphan may coexist with a working database; that state must win over Ready.
                when {
                    pendingUpgrade -> healthStore.set(DatabaseHealth.EncryptionUpgradePending)
                    recoveryManager.hasOrphanedCorruptFiles() -> healthStore.set(DatabaseHealth.RecoverableDataFound)
                    else -> healthStore.set(DatabaseHealth.Ready)
                }
            }
        } catch (e: SQLiteDatabaseCorruptException) {
            if (restorePlaintextIfPossible()) return openPlaintextAfterRestore()
            Log.w(TAG, "Rules DB open classified ConfirmedCorruption; recreating fresh", e)
            encryptedMode = encryption != null
            recoveryManager.renameCorruptRules()
            try {
                build(encrypted = encryptedMode).also { healthStore.set(DatabaseHealth.RecoverableDataFound) }
            } catch (e2: Throwable) {
                Log.e(TAG, "Recreate after corruption failed", e2)
                healthStore.set(DatabaseHealth.DatabaseIssue)
                null
            }
        } catch (e: SQLiteException) {
            if (restorePlaintextIfPossible()) return openPlaintextAfterRestore()
            Log.e(TAG, "Rules DB open failed with SQLiteException", e)
            healthStore.set(healthFor(e))
            null
        } catch (e: Throwable) {
            if (restorePlaintextIfPossible()) return openPlaintextAfterRestore()
            Log.e(TAG, "Rules DB open failed", e)
            healthStore.set(healthFor(e))
            null
        }
    }

    /**
     * Encrypted open failed and a plaintext backup exists: restore it (data before encryption).
     * Only meaningful in encrypted mode — in plaintext mode the backup is a leftover.
     */
    private fun restorePlaintextIfPossible(): Boolean {
        val enc = encryption ?: return false
        if (!encryptedMode) return false
        return enc.restorePlaintextFallback()
    }

    private fun openPlaintextAfterRestore(): AppRulesStore? = try {
        encryptedMode = false
        build(encrypted = false).also { db ->
            runBlocking { db.restrictionRuleDao().getActiveRules() }
            healthStore.set(DatabaseHealth.EncryptionUpgradePending)
        }
    } catch (e: Throwable) {
        Log.e(TAG, "Plaintext fallback after restore failed", e)
        healthStore.set(DatabaseHealth.DatabaseIssue)
        null
    }

    /** Keystore-key problems are recoverable only by reset; data problems are not (plan §9). */
    private fun healthFor(e: Throwable): DatabaseHealth =
        when (encryption?.classifyFailure(e)) {
            KeyFailure.KeyUnavailable -> DatabaseHealth.KeyUnavailable
            KeyFailure.Transient -> DatabaseHealth.Transient
            else -> DatabaseHealth.DatabaseIssue
        }

    private fun build(
        encrypted: Boolean,
        databaseName: String = DB_NAME,
        journalMode: RoomDatabase.JournalMode = RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING,
    ): AppRulesStore {
        val builder = Room.databaseBuilder(appContext, AppRulesStore::class.java, databaseName)
            .setJournalMode(journalMode)
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(createCallback)
        if (encrypted) builder.setDriver(requireNotNull(encryption).newDriver())
        return builder.build()
    }

    /**
     * Room builds the conversion destination's schema (and identity hash) at the temp path
     * before the row copy (§8 Step 0). TRUNCATE journal mode: no persistent WAL header and no
     * sidecars to carry across the swap; production re-opens the file as WAL afterwards.
     */
    private fun buildTempDestination(temp: File) {
        val db = build(
            encrypted = true,
            databaseName = temp.name,
            journalMode = RoomDatabase.JournalMode.TRUNCATE,
        )
        try {
            runBlocking { db.restrictionRuleDao().getActiveRules() }
        } finally {
            db.close()
        }
    }

    private val createCallback = object : RoomDatabase.Callback() {
        override suspend fun onCreate(connection: SQLiteConnection) {
            SchemaConstraints.apply(connection)
            seedPolicyRows(connection)
        }
    }

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

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Domain-usage metering (§8 S7.1): the per-(rule, revision, day) active-time
                // totals. Additive only — every other table is untouched (D-24). DDL is the
                // exported v10 schema verbatim, including the composite primary key's implicit
                // index, which Room validates on open.
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS rule_usage_daily (" +
                        "rule_id TEXT NOT NULL, revision INTEGER NOT NULL, day_key TEXT NOT NULL, " +
                        "active_ms INTEGER NOT NULL, PRIMARY KEY(rule_id, revision, day_key))",
                ).use { it.step() }
            }
        }

        /**
         * D-33's rollback path: a release APK built against v9 (mode `OFF`) must be able to
         * reopen a database that briefly ran at v10. Drops only this feature's table; every other
         * table survives intact — which is why `fallbackToDestructiveMigrationOnDowngrade()` is
         * deliberately not used (it would recreate the whole database). Registered in the same
         * `addMigrations` list as the forward migrations: `RoomDatabase.Builder` selects by
         * `(from, to)` regardless of direction.
         */
        val MIGRATION_10_9 = object : Migration(10, 9) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare("DROP TABLE IF EXISTS rule_usage_daily").use { it.step() }
            }
        }

        /**
         * Usage-ledger plan §8.10 / R10: the journal (schema v11) plus its day-aware seeding.
         * DDL is the exported v11 schema verbatim, spelled plain rather than `IF NOT EXISTS`
         * (AD-11) so an unexpected pre-existing `usage_ledger` fails the migration loudly
         * instead of being silently masked — a deliberate departure from this file's other
         * migrations, which the plan's AD-11 overrides for this step.
         *
         * Seeding is never a blind copy of the v10 scalar: the tracker row carries the local
         * date and the timezone it was anchored under (Decision 20), so a value whose anchor
         * does not match the migration's own (date, timezone) pair is a *yesterday* value that
         * no write ever reset — carrying it forward would hand today a budget it never used.
         * Those seed 0 and leave a local note recording the raw legacy value instead. The
         * resulting `event_id` is deterministic (`migration_seed:<pkg>`), so the seed row is
         * unique by construction and RE-runnable.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE `usage_ledger` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`event_id` TEXT NOT NULL, `kind` INTEGER NOT NULL, " +
                        "`wall_clock_ts_ms` INTEGER NOT NULL, `elapsed_start_ms` INTEGER NOT NULL, " +
                        "`elapsed_end_ms` INTEGER NOT NULL, `boot_count` INTEGER NOT NULL, " +
                        "`package_name` TEXT NOT NULL, `day_key` TEXT NOT NULL, " +
                        "`credited_ms` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                        "`source_version` TEXT NOT NULL, `references_event_id` TEXT)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE UNIQUE INDEX `index_usage_ledger_event_id` ON `usage_ledger` (`event_id`)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX `index_usage_ledger_package_name_day_key_kind_credited_ms` " +
                        "ON `usage_ledger` (`package_name`, `day_key`, `kind`, `credited_ms`)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX `index_usage_ledger_wall_clock_ts_ms` " +
                        "ON `usage_ledger` (`wall_clock_ts_ms`)",
                ).use { it.step() }

                val today = LocalDate.now().toString()
                val zoneNow = ZoneId.systemDefault().id
                // A carryover is an aggregate inherited from legacy state: no reconstructable
                // sub-timeline, so its interval bounds are 0/0 and it is never read as an
                // interval event (the §8.13 aggregation is the only thing that touches it).
                connection.prepare(
                    "INSERT INTO `usage_ledger` (event_id, kind, wall_clock_ts_ms, elapsed_start_ms, " +
                        "elapsed_end_ms, boot_count, package_name, day_key, credited_ms, source, " +
                        "source_version, references_event_id) " +
                        "SELECT 'migration_seed:' || dt.package_name, ?, ?, 0, 0, 0, " +
                        "dt.package_name, dt.local_date_anchor, dt.ms_used_today, ?, ?, NULL " +
                        "FROM daily_usage_tracker dt " +
                        "WHERE dt.ms_used_today > 0 AND dt.local_date_anchor = ? " +
                        "AND dt.timezone_id_at_anchor = ? " +
                        "AND dt.profile_id = (SELECT user_id FROM user_profile WHERE is_active = 1 LIMIT 1)",
                ).use { stmt ->
                    stmt.bindLong(1, UsageLedgerKind.MIGRATION_CARRYOVER.toLong())
                    stmt.bindLong(2, System.currentTimeMillis())
                    stmt.bindText(3, UsageLedgerSource.MAINTENANCE)
                    stmt.bindText(4, BuildConfig.VERSION_NAME)
                    stmt.bindText(5, today)
                    stmt.bindText(6, zoneNow)
                    stmt.step()
                }

                // The deliberately-not-carried-forward values, recorded once, locally, so the
                // decision is auditable rather than invisible.
                connection.prepare(
                    "SELECT dt.package_name, dt.ms_used_today, dt.local_date_anchor " +
                        "FROM daily_usage_tracker dt WHERE dt.ms_used_today > 0 " +
                        "AND (dt.local_date_anchor <> ? OR dt.timezone_id_at_anchor <> ?)",
                ).use { stmt ->
                    stmt.bindText(1, today)
                    stmt.bindText(2, zoneNow)
                    while (stmt.step()) {
                        Log.w(
                            TAG,
                            "ledger seed: dropped stale scalar pkg=${stmt.getText(0)} " +
                                "ms=${stmt.getLong(1)} anchor=${stmt.getText(2)}",
                        )
                    }
                }
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Recovery resource UI (plan v3 W7): the urge/recovery session log and the
                // willpower windows. DDL is the exported v9 schema verbatim — Room validates
                // the migrated file against it on open (AppRulesStoreMigrationTest covers the
                // 8→9 leg on-device through MigrationTestHelper).
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS recovery_session (" +
                        "id TEXT NOT NULL, technique TEXT NOT NULL, intensity_start INTEGER, " +
                        "intensity_end INTEGER, state TEXT NOT NULL, " +
                        "started_at_epoch_ms INTEGER NOT NULL, ended_at_epoch_ms INTEGER, " +
                        "duration_sec INTEGER, zone_id TEXT NOT NULL, entry TEXT NOT NULL, " +
                        "false_alarm INTEGER NOT NULL, event_sequence INTEGER, " +
                        "PRIMARY KEY(id))",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_recovery_session_started_at_epoch_ms " +
                        "ON recovery_session (started_at_epoch_ms)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_recovery_session_event_sequence " +
                        "ON recovery_session (event_sequence)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS risk_window (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "start_minute INTEGER NOT NULL, end_minute INTEGER NOT NULL, " +
                        "days_mask INTEGER NOT NULL, enabled INTEGER NOT NULL)",
                ).use { it.step() }
                // Existing steps predate the kind column: STEP is the v1 meaning (Req 20).
                connection.prepare(
                    "ALTER TABLE coaching_step ADD COLUMN kind TEXT NOT NULL DEFAULT 'STEP'",
                ).use { it.step() }
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Daily-usage enforcement (plan v2 §8 Step 1): day identity becomes the
                // (local date, IANA timezone) pair (Decision 20) and per-day warning/block
                // state travels with the day row. Rebuild instead of ALTER+DROP COLUMN so the
                // migration is valid on every SQLite the app can run against (Robolectric's
                // framework driver included; DROP COLUMN needs SQLite 3.35+). Pre-existing
                // tracker rows are dormant (nothing ever fed usage data); per the plan they
                // anchor to the migration's local date + device timezone.
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS daily_usage_tracker_new (" +
                        "tracker_id TEXT NOT NULL, profile_id TEXT NOT NULL, " +
                        "package_name TEXT NOT NULL, local_date_anchor TEXT NOT NULL, " +
                        "timezone_id_at_anchor TEXT NOT NULL, ms_used_today INTEGER NOT NULL, " +
                        "warning1_state TEXT NOT NULL, warning2_state TEXT NOT NULL, " +
                        "blocked_today INTEGER NOT NULL, " +
                        "PRIMARY KEY(tracker_id), " +
                        "FOREIGN KEY(profile_id) REFERENCES user_profile(user_id) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION)",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO daily_usage_tracker_new (tracker_id, profile_id, package_name, " +
                        "local_date_anchor, timezone_id_at_anchor, ms_used_today, warning1_state, " +
                        "warning2_state, blocked_today) " +
                        "SELECT tracker_id, profile_id, package_name, ?, ?, ms_used_today, " +
                        "'NOT_DUE', 'NOT_DUE', 0 FROM daily_usage_tracker",
                ).use { stmt ->
                    stmt.bindText(1, LocalDate.now().toString())
                    stmt.bindText(2, ZoneId.systemDefault().id)
                    stmt.step()
                }
                connection.prepare("DROP TABLE daily_usage_tracker").use { it.step() }
                connection.prepare(
                    "ALTER TABLE daily_usage_tracker_new RENAME TO daily_usage_tracker",
                ).use { it.step() }
                connection.prepare(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_daily_usage_tracker_profile_id_package_name_local_date_anchor " +
                        "ON daily_usage_tracker (profile_id, package_name, local_date_anchor)",
                ).use { it.step() }
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // V1 multi-habit UI: the displayed habit per profile. Backfill is safe because
                // pre-v7 setup could only ever create one habit per profile — every existing
                // row becomes its profile's selected habit.
                connection.prepare(
                    "ALTER TABLE habit_progress ADD COLUMN is_selected INTEGER NOT NULL DEFAULT 0",
                ).use { it.step() }
                connection.prepare("UPDATE habit_progress SET is_selected = 1").use { it.step() }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Habit-streaks plan §6/§8 Step 2: per-streak RecoveryTimer anchor (streak row
                // with dual-clock anchor + accumulator, stamped relapsed_at once superseded)
                // and the habit-level all-time record that survives streak rollovers. DDL
                // mirrors the entities exactly — Room validates the migrated schema on open.
                connection.prepare(
                    "ALTER TABLE habit_progress ADD COLUMN " +
                        "all_time_max_streak_elapsed_ms INTEGER NOT NULL DEFAULT 0",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS recovery_streak (" +
                        "streak_id TEXT NOT NULL, " +
                        "habit_id TEXT NOT NULL, " +
                        "recovery_start_at INTEGER NOT NULL, " +
                        "relapsed_at INTEGER, " +
                        "relapse_boot_count INTEGER, " +
                        "relapse_elapsed_realtime_ms INTEGER, " +
                        "t_wall_last_observed INTEGER NOT NULL, " +
                        "r_boot_last_observed INTEGER NOT NULL, " +
                        "boot_count_last_observed INTEGER NOT NULL, " +
                        "current_streak_elapsed_ms INTEGER NOT NULL, " +
                        "row_version INTEGER NOT NULL, " +
                        "PRIMARY KEY(streak_id), " +
                        "FOREIGN KEY(habit_id) REFERENCES habit_progress(progress_id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS milestone_events (" +
                        "streak_id TEXT NOT NULL, " +
                        "milestone_day INTEGER NOT NULL, " +
                        "achieved_at INTEGER, " +
                        "notification_sent_at INTEGER, " +
                        "acknowledged_at INTEGER, " +
                        "PRIMARY KEY(streak_id, milestone_day), " +
                        "FOREIGN KEY(streak_id) REFERENCES recovery_streak(streak_id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                ).use { it.step() }
            }
        }

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

        /**
         * Every migration, oldest schema to current, plus the one downgrade step (D-33) that
         * Room selects by `(from, to)` — the one list [build] registers. Declared last: the
         * array reads the migration vals, so it must initialize after them. The accountability
         * store lost two device upgrades to hand-maintained lists diverging (7→8 ADR #50, 9→10
         * ADR #58); this store had the same exposure, so
         * `AppRulesStoreMigrationTest#allMigrations_coverEveryVersionStepToTheDeclaredSchemaVersion`
         * asserts this list covers every step to the version Room creates for a fresh install.
         */
        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_10_9,
        )
    }
}
