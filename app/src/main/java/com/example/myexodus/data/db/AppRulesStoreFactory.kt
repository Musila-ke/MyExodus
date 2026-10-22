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
        database ?: openInternal()?.also {
            // Fresh open only — a cached hit is silent (ADR #153 cold-start attribution).
            database = it
            Log.i(TAG, "Rules store opened fresh thread=${Thread.currentThread().name}")
        }
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

        val outcome = encryption
            ?.prepareForOpen(::migratePlaintextSource, ::buildTempDestination)
            ?: ConversionOutcome.Encrypted
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
            // Kept from Fix A (ADR #153), defensively: the device capture falsified the pool
            // attribution — Room itself only ever opens one connection (a single driver-open
            // per process), so the five cold-start keyings come from the zetetic handle's
            // internal pool, which this flag cannot reach. Raw-key mode (SqlCipherRawKey) is
            // the operative fix; this stays so Room-level reader pools can never multiply keying.
            .setSingleConnectionPool()
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(createCallback)
        if (encrypted) builder.setDriver(requireNotNull(encryption).newDriver())
        return builder.build()
    }

    /**
     * Brings a pre-encryption plaintext file up to the current schema before conversion:
     * Room runs migrations only when Room opens the file, and the conversion's shared-column
     * copy cannot invent NOT NULL columns added since the source's version.
     */
    private fun migratePlaintextSource() {
        val db = build(encrypted = false)
        try {
            runBlocking { db.restrictionRuleDao().getActiveRules() }
        } finally {
            db.close()
        }
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

        /**
         * ADR #111 / V1 Req 14 multi-pair: the per-pair mirror links (schema v12). The old
         * single-valued `event_sequence` columns let the first pair's reconcile pass claim
         * every pending row; the link tables make delivery exactly-once per (row, pair).
         * Additive only — both source tables are untouched, and rows that already carry the
         * legacy `event_sequence` stay excluded from every pair's pending set (their one
         * historical delivery is not duplicated, and the pre-v12 N−1 gap is accepted: no
         * on-device record says which pair received it). DDL is the exported v12 schema
         * verbatim, in this file's plain style (the 10→11 AD-11 exception does not apply).
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS attempt_mirror_link (" +
                        "attempt_id INTEGER NOT NULL, pair_id TEXT NOT NULL, " +
                        "sequence INTEGER NOT NULL, PRIMARY KEY(attempt_id, pair_id))",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS recovery_mirror_link (" +
                        "session_id TEXT NOT NULL, pair_id TEXT NOT NULL, " +
                        "sequence INTEGER NOT NULL, PRIMARY KEY(session_id, pair_id))",
                ).use { it.step() }
            }
        }

        /**
         * The rollback path, same rationale as [MIGRATION_10_9]: a v11 build reinstalled over
         * a database that briefly ran at v12 must still open (no destructive downgrade is
         * configured, deliberately). Drops only these two bookkeeping tables; on a later
         * re-upgrade every row re-qualifies for mirroring to every pair, whose worst case is
         * duplicate activity events — never data loss.
         */
        val MIGRATION_12_11 = object : Migration(12, 11) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare("DROP TABLE IF EXISTS attempt_mirror_link").use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS recovery_mirror_link").use { it.step() }
            }
        }

        /**
         * Partner-visible activity layer (schema v13): the local event journal, the per-pair
         * fan-out links (ADR #111 shape), and the received-state projection. Additive only —
         * no existing table is touched, and every leg is safe on a database with no active
         * partner enrollment (all three tables simply start empty). DDL is the exported v13
         * schema verbatim, in this file's plain style.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS partner_activity_outbox (" +
                        "event_id TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, " +
                        "occurred_at_ms INTEGER NOT NULL, days_free INTEGER NOT NULL, " +
                        "relapse_count INTEGER NOT NULL, milestones_reached TEXT NOT NULL, " +
                        "coaching_progress INTEGER NOT NULL, protocol_version INTEGER NOT NULL, " +
                        "schema_version INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, " +
                        "PRIMARY KEY(event_id))",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_partner_activity_outbox_created_at_ms " +
                        "ON partner_activity_outbox (created_at_ms)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS partner_activity_link (" +
                        "event_id TEXT NOT NULL, pair_id TEXT NOT NULL, " +
                        "sequence INTEGER NOT NULL, PRIMARY KEY(event_id, pair_id))",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS partner_activity_projection (" +
                        "pair_id TEXT NOT NULL, days_free INTEGER NOT NULL, " +
                        "relapse_count INTEGER NOT NULL, milestones_reached TEXT NOT NULL, " +
                        "coaching_progress INTEGER NOT NULL, " +
                        "last_event_occurred_at_ms INTEGER NOT NULL, " +
                        "last_sequence INTEGER NOT NULL, pinned_message_text TEXT, " +
                        "pinned_message_at_ms INTEGER, PRIMARY KEY(pair_id))",
                ).use { it.step() }
            }
        }

        /**
         * The v13 rollback path, same rationale as [MIGRATION_12_11]: a v12 build reinstalled
         * over a database that briefly ran at v13 must still open. Drops only these three
         * tables; the worst case on a later re-upgrade is un-mirrored activity events being
         * re-journaled by the live write paths — the projection re-fills from the peer's next
         * events, never data loss of user-entered state.
         */
        val MIGRATION_13_12 = object : Migration(13, 12) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare("DROP TABLE IF EXISTS partner_activity_outbox").use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS partner_activity_link").use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS partner_activity_projection").use { it.step() }
            }
        }

        /**
         * Message addressing (#96, schema v14): `target_pair_id` on the journal — null fans out
         * to every enrolled pair (the existing behavior), a pair id means only that partner may
         * receive the row. Additive column, defaulting to NULL on every existing row, which is
         * exactly the pre-v14 semantics: a message journaled before this upgrade still reaches
         * every pair once, and is addressed correctly from the next compose onward.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE partner_activity_outbox ADD COLUMN target_pair_id TEXT",
                ).use { it.step() }
            }
        }

        /**
         * The v14 rollback path, same rationale as [MIGRATION_13_12]: a v13 build reinstalled
         * over a database that briefly ran at v14 must still open, and Room validates the table
         * on open — an unknown column fails the open, so the column has to go, not just be
         * ignored. `ALTER TABLE … DROP COLUMN` needs SQLite ≥ 3.35, which not every supported
         * device has, so the table is recreated and its rows copied (journal rows are pending
         * transport state; an unfinished message survives the downgrade and simply fans out
         * again on the next re-upgrade).
         */
        val MIGRATION_14_13 = object : Migration(14, 13) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS partner_activity_outbox_old (" +
                        "event_id TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, " +
                        "occurred_at_ms INTEGER NOT NULL, days_free INTEGER NOT NULL, " +
                        "relapse_count INTEGER NOT NULL, milestones_reached TEXT NOT NULL, " +
                        "coaching_progress INTEGER NOT NULL, protocol_version INTEGER NOT NULL, " +
                        "schema_version INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, " +
                        "PRIMARY KEY(event_id))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO partner_activity_outbox_old (event_id, kind, text, " +
                        "occurred_at_ms, days_free, relapse_count, milestones_reached, " +
                        "coaching_progress, protocol_version, schema_version, created_at_ms) " +
                        "SELECT event_id, kind, text, occurred_at_ms, days_free, relapse_count, " +
                        "milestones_reached, coaching_progress, protocol_version, schema_version, " +
                        "created_at_ms FROM partner_activity_outbox",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS partner_activity_outbox").use { it.step() }
                connection.prepare(
                    "ALTER TABLE partner_activity_outbox_old RENAME TO partner_activity_outbox",
                ).use { it.step() }
                // Dropping the old table dropped its index with it; recreate it explicitly —
                // Room validates declared indices on open.
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_partner_activity_outbox_created_at_ms " +
                        "ON partner_activity_outbox (created_at_ms)",
                ).use { it.step() }
            }
        }

        /**
         * Sharing settings (ADR #134, 2026-10-04). Additive columns only — no table rebuild:
         *
         *  - `habit_progress.share_streak`: per-streak master visibility (the "which streaks
         *    the partner can see" switch). Default 1 (visible) matches the shipped default the
         *    new sharing surface presents.
         *  - `share_relapses` is FLIPPED to 1 on every existing row: the stored 0 could only
         *    ever have been the onboard non-choice default (no UI ever wrote this flag), and
         *    it was silently suppressing every relapse ever logged — the owner-reported defect
         *    this migration repairs. The value the user now sees and controls is the default
         *    the surface ships with.
         *  - `user_profile.share_check_ins` / `share_recovery`: profile-wide consent for the
         *    two categories that carry no habit link.
         *  - `partner_activity_outbox.habit_label` / `ended_days` / `milestone_day`: per-event
         *    display facts for the partner (which streak, how long it ran, which day reached).
         *    Nullable — rows journaled before this version simply have no name to show.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE habit_progress ADD COLUMN share_streak INTEGER NOT NULL DEFAULT 1",
                ).use { it.step() }
                connection.prepare(
                    "UPDATE habit_progress SET share_relapses = 1",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE user_profile ADD COLUMN share_check_ins INTEGER NOT NULL DEFAULT 1",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE user_profile ADD COLUMN share_recovery INTEGER NOT NULL DEFAULT 1",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE partner_activity_outbox ADD COLUMN habit_label TEXT",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE partner_activity_outbox ADD COLUMN ended_days INTEGER",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE partner_activity_outbox ADD COLUMN milestone_day INTEGER",
                ).use { it.step() }
            }
        }

        /**
         * The v15 rollback path, same rationale as [MIGRATION_14_13]: a v14 build reinstalled
         * over a database that briefly ran at v15 must still open. `ALTER TABLE … DROP COLUMN`
         * needs SQLite ≥ 3.35 (not guaranteed on every supported device), so the three tables
         * are recreated from the v14 DDL and their rows copied; the dropped consent/display
         * columns degrade exactly to v14 semantics (relapses unshared again, no names on wire).
         */
        val MIGRATION_15_14 = object : Migration(15, 14) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS habit_progress_old (" +
                        "progress_id TEXT NOT NULL, profile_id TEXT NOT NULL, " +
                        "target_identifier TEXT NOT NULL, current_clean_days_streak INTEGER NOT NULL, " +
                        "total_relapses INTEGER NOT NULL, access_attempts_count INTEGER NOT NULL, " +
                        "habit_duration_years INTEGER NOT NULL, " +
                        "onboarding_severity_level TEXT NOT NULL, last_relapse_timestamp INTEGER, " +
                        "share_access_attempts INTEGER NOT NULL, share_relapses INTEGER NOT NULL, " +
                        "share_milestones INTEGER NOT NULL, assigned_partnership_id TEXT, " +
                        "all_time_max_streak_elapsed_ms INTEGER NOT NULL DEFAULT 0, " +
                        "is_selected INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(progress_id), " +
                        "FOREIGN KEY(profile_id) REFERENCES user_profile(user_id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO habit_progress_old (progress_id, profile_id, target_identifier, " +
                        "current_clean_days_streak, total_relapses, access_attempts_count, " +
                        "habit_duration_years, onboarding_severity_level, last_relapse_timestamp, " +
                        "share_access_attempts, share_relapses, share_milestones, " +
                        "assigned_partnership_id, all_time_max_streak_elapsed_ms, is_selected) " +
                        "SELECT progress_id, profile_id, target_identifier, current_clean_days_streak, " +
                        "total_relapses, access_attempts_count, habit_duration_years, " +
                        "onboarding_severity_level, last_relapse_timestamp, share_access_attempts, " +
                        "share_relapses, share_milestones, assigned_partnership_id, " +
                        "all_time_max_streak_elapsed_ms, is_selected FROM habit_progress",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS habit_progress").use { it.step() }
                connection.prepare("ALTER TABLE habit_progress_old RENAME TO habit_progress").use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS user_profile_old (" +
                        "user_id TEXT NOT NULL, name TEXT NOT NULL, email TEXT NOT NULL, " +
                        "phone_number TEXT, date_of_birth INTEGER NOT NULL, " +
                        "has_accessibility_permission INTEGER NOT NULL, " +
                        "has_usage_stats_permission INTEGER NOT NULL, has_vpn_permission INTEGER NOT NULL, " +
                        "has_notification_permission INTEGER NOT NULL, " +
                        "has_device_admin_permission INTEGER NOT NULL, device_public_key TEXT, " +
                        "recovery_escrow_key TEXT, is_active INTEGER NOT NULL, PRIMARY KEY(user_id))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO user_profile_old (user_id, name, email, phone_number, date_of_birth, " +
                        "has_accessibility_permission, has_usage_stats_permission, has_vpn_permission, " +
                        "has_notification_permission, has_device_admin_permission, device_public_key, " +
                        "recovery_escrow_key, is_active) " +
                        "SELECT user_id, name, email, phone_number, date_of_birth, " +
                        "has_accessibility_permission, has_usage_stats_permission, has_vpn_permission, " +
                        "has_notification_permission, has_device_admin_permission, device_public_key, " +
                        "recovery_escrow_key, is_active FROM user_profile",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS user_profile").use { it.step() }
                connection.prepare("ALTER TABLE user_profile_old RENAME TO user_profile").use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS partner_activity_outbox_old (" +
                        "event_id TEXT NOT NULL, kind TEXT NOT NULL, target_pair_id TEXT, " +
                        "text TEXT NOT NULL, occurred_at_ms INTEGER NOT NULL, days_free INTEGER NOT NULL, " +
                        "relapse_count INTEGER NOT NULL, milestones_reached TEXT NOT NULL, " +
                        "coaching_progress INTEGER NOT NULL, protocol_version INTEGER NOT NULL, " +
                        "schema_version INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, " +
                        "PRIMARY KEY(event_id))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO partner_activity_outbox_old (event_id, kind, target_pair_id, text, " +
                        "occurred_at_ms, days_free, relapse_count, milestones_reached, " +
                        "coaching_progress, protocol_version, schema_version, created_at_ms) " +
                        "SELECT event_id, kind, target_pair_id, text, occurred_at_ms, days_free, " +
                        "relapse_count, milestones_reached, coaching_progress, protocol_version, " +
                        "schema_version, created_at_ms FROM partner_activity_outbox",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS partner_activity_outbox").use { it.step() }
                connection.prepare(
                    "ALTER TABLE partner_activity_outbox_old RENAME TO partner_activity_outbox",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_partner_activity_outbox_created_at_ms " +
                        "ON partner_activity_outbox (created_at_ms)",
                ).use { it.step() }
            }
        }

        /**
         * v15 → v16 (ADR #135): consent moves from per-habit / per-profile columns to a per
         * (pair, item) matrix — `pair_streak_sharing` / `pair_profile_sharing`. The legacy
         * consent columns are dropped outright, with no seed: the owner ruling is "nothing
         * until you choose" for every install, and a pre-v16 flag was written by a surface
         * that never asked per partner — seeding any pair from it would share on the user's
         * behalf. Recreate-table style because `DROP COLUMN` needs SQLite ≥ 3.35.
         *
         * `partner_activity_outbox` gains the consent key `habit_progress_id` (null on
         * pre-v16 rows — the reconcile filter matches them to no branch and skips them,
         * the conservative answer for rows whose habit slice can't be proven).
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS habit_progress_old (" +
                        "progress_id TEXT NOT NULL, profile_id TEXT NOT NULL, " +
                        "target_identifier TEXT NOT NULL, current_clean_days_streak INTEGER NOT NULL, " +
                        "total_relapses INTEGER NOT NULL, access_attempts_count INTEGER NOT NULL, " +
                        "habit_duration_years INTEGER NOT NULL, " +
                        "onboarding_severity_level TEXT NOT NULL, last_relapse_timestamp INTEGER, " +
                        "assigned_partnership_id TEXT, " +
                        "all_time_max_streak_elapsed_ms INTEGER NOT NULL DEFAULT 0, " +
                        "is_selected INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(progress_id), " +
                        "FOREIGN KEY(profile_id) REFERENCES user_profile(user_id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO habit_progress_old (progress_id, profile_id, target_identifier, " +
                        "current_clean_days_streak, total_relapses, access_attempts_count, " +
                        "habit_duration_years, onboarding_severity_level, last_relapse_timestamp, " +
                        "assigned_partnership_id, all_time_max_streak_elapsed_ms, is_selected) " +
                        "SELECT progress_id, profile_id, target_identifier, current_clean_days_streak, " +
                        "total_relapses, access_attempts_count, habit_duration_years, " +
                        "onboarding_severity_level, last_relapse_timestamp, " +
                        "assigned_partnership_id, all_time_max_streak_elapsed_ms, is_selected " +
                        "FROM habit_progress",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS habit_progress").use { it.step() }
                connection.prepare("ALTER TABLE habit_progress_old RENAME TO habit_progress").use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS user_profile_old (" +
                        "user_id TEXT NOT NULL, name TEXT NOT NULL, email TEXT NOT NULL, " +
                        "phone_number TEXT, date_of_birth INTEGER NOT NULL, " +
                        "has_accessibility_permission INTEGER NOT NULL, " +
                        "has_usage_stats_permission INTEGER NOT NULL, has_vpn_permission INTEGER NOT NULL, " +
                        "has_notification_permission INTEGER NOT NULL, " +
                        "has_device_admin_permission INTEGER NOT NULL, device_public_key TEXT, " +
                        "recovery_escrow_key TEXT, is_active INTEGER NOT NULL, PRIMARY KEY(user_id))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO user_profile_old (user_id, name, email, phone_number, date_of_birth, " +
                        "has_accessibility_permission, has_usage_stats_permission, has_vpn_permission, " +
                        "has_notification_permission, has_device_admin_permission, device_public_key, " +
                        "recovery_escrow_key, is_active) " +
                        "SELECT user_id, name, email, phone_number, date_of_birth, " +
                        "has_accessibility_permission, has_usage_stats_permission, has_vpn_permission, " +
                        "has_notification_permission, has_device_admin_permission, device_public_key, " +
                        "recovery_escrow_key, is_active FROM user_profile",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS user_profile").use { it.step() }
                connection.prepare("ALTER TABLE user_profile_old RENAME TO user_profile").use { it.step() }
                connection.prepare(
                    "ALTER TABLE partner_activity_outbox ADD COLUMN habit_progress_id TEXT",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS pair_streak_sharing (" +
                        "pair_id TEXT NOT NULL, habit_progress_id TEXT NOT NULL, " +
                        "share_streak INTEGER NOT NULL, share_relapses INTEGER NOT NULL, " +
                        "share_milestones INTEGER NOT NULL, " +
                        "streak_enabled_since_ms INTEGER NOT NULL, " +
                        "relapse_enabled_since_ms INTEGER NOT NULL, " +
                        "milestone_enabled_since_ms INTEGER NOT NULL, " +
                        "PRIMARY KEY(pair_id, habit_progress_id), " +
                        "FOREIGN KEY(habit_progress_id) REFERENCES habit_progress(progress_id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_pair_streak_sharing_habit_progress_id " +
                        "ON pair_streak_sharing (habit_progress_id)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS pair_profile_sharing (" +
                        "pair_id TEXT NOT NULL, share_checkins INTEGER NOT NULL, " +
                        "share_recovery INTEGER NOT NULL, " +
                        "checkins_enabled_since_ms INTEGER NOT NULL, " +
                        "recovery_enabled_since_ms INTEGER NOT NULL, PRIMARY KEY(pair_id))",
                ).use { it.step() }
            }
        }

        /**
         * The v16 rollback path, same rationale as [MIGRATION_15_14]: a v15 build reinstalled
         * over a database that briefly ran at v16 must still open. The pair tables carry the
         * only record of the user's choices, so each v15 flag is reconstructed as "any pair
         * has it on" before the pair tables are dropped — the closest single-column value
         * v15 can hold. `share_access_attempts` was never written by any UI: zero.
         */
        val MIGRATION_16_15 = object : Migration(16, 15) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS habit_progress_old (" +
                        "progress_id TEXT NOT NULL, profile_id TEXT NOT NULL, " +
                        "target_identifier TEXT NOT NULL, current_clean_days_streak INTEGER NOT NULL, " +
                        "total_relapses INTEGER NOT NULL, access_attempts_count INTEGER NOT NULL, " +
                        "habit_duration_years INTEGER NOT NULL, " +
                        "onboarding_severity_level TEXT NOT NULL, last_relapse_timestamp INTEGER, " +
                        "share_access_attempts INTEGER NOT NULL, share_relapses INTEGER NOT NULL, " +
                        "share_milestones INTEGER NOT NULL, " +
                        "share_streak INTEGER NOT NULL DEFAULT 1, assigned_partnership_id TEXT, " +
                        "all_time_max_streak_elapsed_ms INTEGER NOT NULL DEFAULT 0, " +
                        "is_selected INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(progress_id), " +
                        "FOREIGN KEY(profile_id) REFERENCES user_profile(user_id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO habit_progress_old (progress_id, profile_id, target_identifier, " +
                        "current_clean_days_streak, total_relapses, access_attempts_count, " +
                        "habit_duration_years, onboarding_severity_level, last_relapse_timestamp, " +
                        "share_access_attempts, share_relapses, share_milestones, share_streak, " +
                        "assigned_partnership_id, all_time_max_streak_elapsed_ms, is_selected) " +
                        "SELECT progress_id, profile_id, target_identifier, current_clean_days_streak, " +
                        "total_relapses, access_attempts_count, habit_duration_years, " +
                        "onboarding_severity_level, last_relapse_timestamp, 0, " +
                        "EXISTS (SELECT 1 FROM pair_streak_sharing s " +
                        "WHERE s.habit_progress_id = habit_progress.progress_id AND s.share_relapses = 1), " +
                        "EXISTS (SELECT 1 FROM pair_streak_sharing s " +
                        "WHERE s.habit_progress_id = habit_progress.progress_id AND s.share_milestones = 1), " +
                        "EXISTS (SELECT 1 FROM pair_streak_sharing s " +
                        "WHERE s.habit_progress_id = habit_progress.progress_id AND s.share_streak = 1), " +
                        "assigned_partnership_id, all_time_max_streak_elapsed_ms, is_selected " +
                        "FROM habit_progress",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS habit_progress").use { it.step() }
                connection.prepare("ALTER TABLE habit_progress_old RENAME TO habit_progress").use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS user_profile_old (" +
                        "user_id TEXT NOT NULL, name TEXT NOT NULL, email TEXT NOT NULL, " +
                        "phone_number TEXT, date_of_birth INTEGER NOT NULL, " +
                        "has_accessibility_permission INTEGER NOT NULL, " +
                        "has_usage_stats_permission INTEGER NOT NULL, has_vpn_permission INTEGER NOT NULL, " +
                        "has_notification_permission INTEGER NOT NULL, " +
                        "has_device_admin_permission INTEGER NOT NULL, device_public_key TEXT, " +
                        "recovery_escrow_key TEXT, is_active INTEGER NOT NULL, " +
                        "share_check_ins INTEGER NOT NULL DEFAULT 1, " +
                        "share_recovery INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(user_id))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO user_profile_old (user_id, name, email, phone_number, date_of_birth, " +
                        "has_accessibility_permission, has_usage_stats_permission, has_vpn_permission, " +
                        "has_notification_permission, has_device_admin_permission, device_public_key, " +
                        "recovery_escrow_key, is_active, share_check_ins, share_recovery) " +
                        "SELECT user_id, name, email, phone_number, date_of_birth, " +
                        "has_accessibility_permission, has_usage_stats_permission, has_vpn_permission, " +
                        "has_notification_permission, has_device_admin_permission, device_public_key, " +
                        "recovery_escrow_key, is_active, " +
                        "EXISTS (SELECT 1 FROM pair_profile_sharing WHERE share_checkins = 1), " +
                        "EXISTS (SELECT 1 FROM pair_profile_sharing WHERE share_recovery = 1) " +
                        "FROM user_profile",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS user_profile").use { it.step() }
                connection.prepare("ALTER TABLE user_profile_old RENAME TO user_profile").use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS partner_activity_outbox_old (" +
                        "event_id TEXT NOT NULL, kind TEXT NOT NULL, target_pair_id TEXT, " +
                        "text TEXT NOT NULL, occurred_at_ms INTEGER NOT NULL, days_free INTEGER NOT NULL, " +
                        "relapse_count INTEGER NOT NULL, milestones_reached TEXT NOT NULL, " +
                        "habit_label TEXT, ended_days INTEGER, milestone_day INTEGER, " +
                        "coaching_progress INTEGER NOT NULL, protocol_version INTEGER NOT NULL, " +
                        "schema_version INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, " +
                        "PRIMARY KEY(event_id))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO partner_activity_outbox_old (event_id, kind, target_pair_id, text, " +
                        "occurred_at_ms, days_free, relapse_count, milestones_reached, habit_label, " +
                        "ended_days, milestone_day, coaching_progress, protocol_version, " +
                        "schema_version, created_at_ms) " +
                        "SELECT event_id, kind, target_pair_id, text, occurred_at_ms, days_free, " +
                        "relapse_count, milestones_reached, habit_label, ended_days, milestone_day, " +
                        "coaching_progress, protocol_version, schema_version, created_at_ms " +
                        "FROM partner_activity_outbox",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS partner_activity_outbox").use { it.step() }
                connection.prepare(
                    "ALTER TABLE partner_activity_outbox_old RENAME TO partner_activity_outbox",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_partner_activity_outbox_created_at_ms " +
                        "ON partner_activity_outbox (created_at_ms)",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS pair_streak_sharing").use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS pair_profile_sharing").use { it.step() }
            }
        }

        /**
         * v16 → v17 (owner ask 2026-10-21): partner-authored plans. Two new tables — the plan
         * head (revision + the two pending flags that stand in for an outbox) and its ordered
         * checklist lines keyed (plan, task) so a recipient's checked state survives an edit.
         * No existing table changes.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS partner_plan (" +
                        "plan_id TEXT NOT NULL, pair_id TEXT, authored INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, revision INTEGER NOT NULL, " +
                        "updated_at_ms INTEGER NOT NULL, pending_publish INTEGER NOT NULL, " +
                        "pending_progress INTEGER NOT NULL, PRIMARY KEY(plan_id))",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_partner_plan_pair_id " +
                        "ON partner_plan (pair_id)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS partner_plan_task (" +
                        "plan_id TEXT NOT NULL, task_id TEXT NOT NULL, task_order INTEGER NOT NULL, " +
                        "text TEXT NOT NULL, done INTEGER NOT NULL, done_at_ms INTEGER, " +
                        "PRIMARY KEY(plan_id, task_id), " +
                        "FOREIGN KEY(plan_id) REFERENCES partner_plan(plan_id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_partner_plan_task_plan_id " +
                        "ON partner_plan_task (plan_id)",
                ).use { it.step() }
            }
        }

        /** v17 rollback: a v16 build has no plan surfaces, so the tables go outright. */
        val MIGRATION_17_16 = object : Migration(17, 16) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare("DROP TABLE IF EXISTS partner_plan_task").use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS partner_plan").use { it.step() }
            }
        }

        /**
         * v17 → v18 (partner-accountability Step 4): the journal-first minimized event body.
         * Lives in SQLCipher rather than the plain-FBE accountability DB because the body is
         * plaintext until the envelope exists (plan Appendix S1.7). `events.event_id` in the
         * accountability DB is the link — no second identity column, no link table to drift.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS partner_event_journal (" +
                        "event_id TEXT NOT NULL, category TEXT NOT NULL, " +
                        "occurred_at_minute INTEGER NOT NULL, monitoring_state TEXT, " +
                        "outbox_age_bucket TEXT, schema_version INTEGER NOT NULL, " +
                        "created_at_ms INTEGER NOT NULL, expires_at_ms INTEGER NOT NULL, " +
                        "PRIMARY KEY(event_id))",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_partner_event_journal_created_at_ms " +
                        "ON partner_event_journal (created_at_ms)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_partner_event_journal_expires_at_ms " +
                        "ON partner_event_journal (expires_at_ms)",
                ).use { it.step() }
            }
        }

        /** v18 rollback: a v17 build has no accountability event transport, so the journal goes. */
        val MIGRATION_18_17 = object : Migration(18, 17) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare("DROP TABLE IF EXISTS partner_event_journal").use { it.step() }
            }
        }

        /**
         * v18 → v19 (partner-accountability Step 7): this device's heartbeat counter
         * ([DeviceLiveness]), the check-in/standby diagnostics singleton
         * ([PartnerMonitoringCheckin]), and the recipient-local decrypted monitoring snapshot
         * ([PartnerMonitoringSnapshot]). All three carry pairing state, so they stay in the
         * SQLCipher store; DDL is the exported v19 schema verbatim.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS device_liveness (" +
                        "install_id TEXT NOT NULL, uid TEXT NOT NULL, counter INTEGER NOT NULL, " +
                        "last_sent_at_ms INTEGER NOT NULL, last_accepted_counter INTEGER NOT NULL, " +
                        "last_accepted_at_ms INTEGER NOT NULL, PRIMARY KEY(install_id))",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS partner_monitoring_checkin (" +
                        "id INTEGER NOT NULL, last_state TEXT, " +
                        "last_recorded_at_ms INTEGER NOT NULL, last_standby_bucket TEXT, " +
                        "standby_checked_at_ms INTEGER NOT NULL, PRIMARY KEY(id))",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS partner_monitoring_snapshot (" +
                        "pair_id TEXT NOT NULL, monitoring_state TEXT NOT NULL, " +
                        "outbox_age_bucket TEXT NOT NULL, snapshot_install_generation TEXT NOT NULL, " +
                        "latest_relationship_seq INTEGER NOT NULL, " +
                        "snapshot_event_sequence INTEGER NOT NULL, " +
                        "occurred_at_minute INTEGER NOT NULL, received_at_ms INTEGER NOT NULL, " +
                        "PRIMARY KEY(pair_id))",
                ).use { it.step() }
                // Device availability is a new consent category: off until the user enables it.
                connection.prepare(
                    "ALTER TABLE pair_profile_sharing ADD COLUMN share_availability INTEGER NOT NULL DEFAULT 0",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE pair_profile_sharing ADD COLUMN availability_enabled_since_ms INTEGER NOT NULL DEFAULT 0",
                ).use { it.step() }
            }
        }

        /**
         * v19 rollback: a v18 build has no heartbeat/snapshot surfaces, so those rows go. The
         * new consent columns are removed by a table rebuild (not DROP COLUMN — SQLite 3.35+
         * only, and the store must stay openable on every driver it runs under); every
         * pre-existing consent choice survives the rebuild. The liveness agent is device-local
         * — a re-upgrade starts a fresh counter namespace.
         */
        val MIGRATION_19_18 = object : Migration(19, 18) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare("DROP TABLE IF EXISTS partner_monitoring_snapshot").use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS partner_monitoring_checkin").use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS device_liveness").use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS pair_profile_sharing_old (" +
                        "pair_id TEXT NOT NULL, share_checkins INTEGER NOT NULL, " +
                        "share_recovery INTEGER NOT NULL, " +
                        "checkins_enabled_since_ms INTEGER NOT NULL, " +
                        "recovery_enabled_since_ms INTEGER NOT NULL, PRIMARY KEY(pair_id))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO pair_profile_sharing_old (pair_id, share_checkins, share_recovery, " +
                        "checkins_enabled_since_ms, recovery_enabled_since_ms) " +
                        "SELECT pair_id, share_checkins, share_recovery, " +
                        "checkins_enabled_since_ms, recovery_enabled_since_ms FROM pair_profile_sharing",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS pair_profile_sharing").use { it.step() }
                connection.prepare(
                    "ALTER TABLE pair_profile_sharing_old RENAME TO pair_profile_sharing",
                ).use { it.step() }
            }
        }

        /**
         * v19 → v20 (partner-accountability Step 10): the server-convergence marker for the
         * availability consent. Existing rows default to not-pending — their last acknowledged
         * value is whatever they hold; a toggle made after the upgrade sets the marker.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE pair_profile_sharing ADD COLUMN availability_consent_pending INTEGER NOT NULL DEFAULT 0",
                ).use { it.step() }
            }
        }

        /** v20 rollback: rebuilt without the convergence marker; every consent choice,
         *  including device availability and its watermark, survives. */
        val MIGRATION_20_19 = object : Migration(20, 19) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS pair_profile_sharing_old (" +
                        "pair_id TEXT NOT NULL, share_checkins INTEGER NOT NULL, " +
                        "share_recovery INTEGER NOT NULL, " +
                        "checkins_enabled_since_ms INTEGER NOT NULL, " +
                        "recovery_enabled_since_ms INTEGER NOT NULL, " +
                        "share_availability INTEGER NOT NULL DEFAULT 0, " +
                        "availability_enabled_since_ms INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(pair_id))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO pair_profile_sharing_old (pair_id, share_checkins, share_recovery, " +
                        "checkins_enabled_since_ms, recovery_enabled_since_ms, share_availability, " +
                        "availability_enabled_since_ms) " +
                        "SELECT pair_id, share_checkins, share_recovery, checkins_enabled_since_ms, " +
                        "recovery_enabled_since_ms, share_availability, availability_enabled_since_ms " +
                        "FROM pair_profile_sharing",
                ).use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS pair_profile_sharing").use { it.step() }
                connection.prepare(
                    "ALTER TABLE pair_profile_sharing_old RENAME TO pair_profile_sharing",
                ).use { it.step() }
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
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
            MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
            MIGRATION_10_9, MIGRATION_12_11, MIGRATION_13_12, MIGRATION_14_13, MIGRATION_15_14,
            MIGRATION_16_15, MIGRATION_17_16, MIGRATION_18_17, MIGRATION_19_18, MIGRATION_20_19,
        )
    }
}
