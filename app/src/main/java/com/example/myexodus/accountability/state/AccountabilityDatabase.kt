package com.example.myexodus.accountability.state

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import com.example.myexodus.accountability.delivery.InstallationState
import com.example.myexodus.accountability.delivery.PendingPartnerEvent
import com.example.myexodus.accountability.delivery.ServiceEventOutboxDao
import com.example.myexodus.accountability.delivery.ServiceOutboxHealth
import com.example.myexodus.accountability.delivery.ServiceOutboxWakeState
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.enrollment.EnrollmentRecord

/**
 * §8.3 chain database. Holds only AEAD-encrypted envelopes, hash pointers, and enrollment
 * public-key material — no plaintext, no key material — so it uses the plain-FBE Room
 * pattern of [com.example.myexodus.data.db.AppRulesStore].
 */
@Database(
    entities = [
        ApprovalRequest::class,
        ChainEvent::class,
        ChainHead::class,
        EnrollmentRecord::class,
        IdentityEpoch::class,
        Outbox::class,
        PendingPartnerEvent::class,
        InstallationState::class,
        ServiceOutboxHealth::class,
        ServiceOutboxWakeState::class,
        ProcessedEvent::class,
        UnpairState::class,
    ],
    version = 17,
    exportSchema = false,
)
abstract class AccountabilityDatabase : RoomDatabase() {

    abstract fun approvalRequestDao(): ApprovalRequestDao
    abstract fun chainEventDao(): ChainEventDao
    abstract fun chainHeadDao(): ChainHeadDao
    abstract fun enrollmentDao(): EnrollmentDao
    abstract fun identityEpochDao(): IdentityEpochDao
    abstract fun outboxDao(): OutboxDao
    abstract fun processedEventDao(): ProcessedEventDao
    abstract fun serviceEventOutboxDao(): ServiceEventOutboxDao
    abstract fun unpairStateDao(): UnpairStateDao

    companion object {
        const val DB_NAME = "myexodus_accountability.db"

        /** v2 adds the enrollments table (Phase 4); v1 chain data is preserved. */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS enrollments (" +
                        "pair_id TEXT NOT NULL PRIMARY KEY, " +
                        "my_sender_key_id TEXT NOT NULL, " +
                        "partner_signing_key_id TEXT NOT NULL, " +
                        "partner_signing_pubkey BLOB NOT NULL, " +
                        "partner_agreement_pubkey BLOB NOT NULL, " +
                        "created_at INTEGER NOT NULL)",
                ).use { it.step() }
            }
        }

        /** v3 adds the identity-epoch table (Decision 12: epoch leaves EncryptedSharedPreferences). */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS identity_epoch (" +
                        "id INTEGER NOT NULL PRIMARY KEY, " +
                        "epoch INTEGER NOT NULL)",
                ).use { it.step() }
            }
        }

        /** v4 adds the §7.5 mailbox pull watermark (quota: pull-after-watermark, not re-read). */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE enrollments ADD COLUMN mailbox_watermark_ts INTEGER",
                ).use { it.step() }
            }
        }

        /** v5 adds the plan §8 Step 4 schema: outbox (AD-06b), processed_events (AD-10b), chain_heads.key_epoch. */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS outbox (" +
                        "message_id TEXT NOT NULL PRIMARY KEY, " +
                        "event_id INTEGER NOT NULL, " +
                        "destination_uid TEXT, " +
                        "envelope_bytes BLOB NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "created_at INTEGER NOT NULL, " +
                        "last_attempt_at INTEGER, " +
                        "attempt_count INTEGER NOT NULL)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS processed_events (" +
                        "message_id TEXT NOT NULL PRIMARY KEY, " +
                        "processed_at INTEGER NOT NULL)",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE chain_heads ADD COLUMN key_epoch INTEGER NOT NULL DEFAULT 0",
                ).use { it.step() }
            }
        }

        /** v6 adds the plan §8 Step 11 repair columns (AD-08 staged repair on chain_heads) and
         *  renames the retired v1 terminal value. v1 wrote `PERMANENTLY_INCOMPLETE` only via a
         *  7-day expiry that had no production caller, but the UPDATE is cheap insurance for a
         *  device that ran an older build. */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE chain_heads ADD COLUMN repair_state TEXT NOT NULL DEFAULT 'NONE'",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE chain_heads ADD COLUMN repair_first_requested_at INTEGER",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE chain_heads ADD COLUMN repair_last_requested_at INTEGER",
                ).use { it.step() }
                connection.prepare(
                    "UPDATE events SET sync_state = 'CHAIN_INCOMPLETE' WHERE sync_state = 'PERMANENTLY_INCOMPLETE'",
                ).use { it.step() }
            }
        }

        /** v7 adds the plan §8 Step 12 approval_requests table and the AD-07 pairing epoch
         *  column on enrollments (the response's epoch is compared against it). */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS approval_requests (" +
                        "request_event_id TEXT NOT NULL PRIMARY KEY, " +
                        "pair_id TEXT NOT NULL, " +
                        "role TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "pairing_epoch INTEGER NOT NULL, " +
                        "message TEXT NOT NULL, " +
                        "created_at INTEGER NOT NULL, " +
                        "expires_at INTEGER NOT NULL, " +
                        "decided_at INTEGER)",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE enrollments ADD COLUMN pairing_epoch INTEGER NOT NULL DEFAULT 0",
                ).use { it.step() }
            }
        }

        /** v8 adds the plan §8 Step 13 unpair journal (AD-13a/b) — one row per unpaired pair,
         *  deliberately NOT part of [ChainStateStore.deletePairData]'s wipe: it is the record
         *  the UI reports the erasure from after the pair's chain tables are gone. */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS unpair_state (" +
                        "pair_id TEXT NOT NULL PRIMARY KEY, " +
                        "role TEXT NOT NULL, " +
                        "partner_uid TEXT, " +
                        "message_id TEXT NOT NULL, " +
                        "sender_key_id TEXT, " +
                        "key_epoch INTEGER NOT NULL DEFAULT 0, " +
                        "request_envelope BLOB NOT NULL, " +
                        "delivered INTEGER NOT NULL, " +
                        "ack_sent INTEGER NOT NULL, " +
                        "local_shredded INTEGER NOT NULL, " +
                        "requested_at INTEGER NOT NULL, " +
                        "confirmed_at INTEGER)",
                ).use { it.step() }
            }
        }

        /** v9 adds the plan §8 Step 15 (AD-12) rotation anchors on chain_heads: the current
         *  epoch's start time and start sequence. Nullable/zero defaults mean a pre-existing
         *  head keeps its epoch and simply has no anchor until its next event stamps one. */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE chain_heads ADD COLUMN key_epoch_started_at INTEGER",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE chain_heads ADD COLUMN key_epoch_start_sequence INTEGER NOT NULL DEFAULT 0",
                ).use { it.step() }
            }
        }

        /** v10 adds the plan §8 Step 18 rejection-check columns on outbox: when the client last
         *  read back a sent message's relay marker, and the marker it found. Both nullable — a
         *  pre-existing row is simply unchecked, which is exactly what it is. */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE outbox ADD COLUMN relay_checked_at INTEGER",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE outbox ADD COLUMN relay_rejection TEXT",
                ).use { it.step() }
            }
        }

        /** v11 adds the §19.5 partner label on enrollments — a nullable presentation column, so
         *  a pre-existing enrollment keeps its identity material and simply has no label. */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE enrollments ADD COLUMN partner_label TEXT",
                ).use { it.step() }
            }
        }

        /** v12 adds the §19.5 (ADR #106) per-pair agreement key alias on enrollments — nullable,
         *  so a pre-existing enrollment keeps using the legacy device-level key. */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE enrollments ADD COLUMN my_agreement_key_alias TEXT",
                ).use { it.step() }
            }
        }

        /**
         * v13 adds the partner-accountability Step 4 delivery columns: `events.event_id` (the
         * Step 3 wire UUID, unique per relationship + sender install; NULL on pre-contract and
         * gap-marker rows, and SQLite treats NULLs as distinct so the unique index exempts
         * them), plus the §8.5/§8.7 outbox retry and retention columns. Pre-existing PENDING
         * rows get a retention window anchored to their creation, exactly what a row created
         * under the new rule would have had.
         */
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare("ALTER TABLE events ADD COLUMN event_id TEXT").use { it.step() }
                connection.prepare(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_events_pair_id_sender_key_id_event_id " +
                        "ON events (pair_id, sender_key_id, event_id)",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE outbox ADD COLUMN next_attempt_at INTEGER NOT NULL DEFAULT 0",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE outbox ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE outbox ADD COLUMN last_error_code TEXT",
                ).use { it.step() }
                connection.prepare(
                    "UPDATE outbox SET expires_at = created_at + ${Outbox.RETENTION_MILLIS} " +
                        "WHERE expires_at = 0",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_outbox_status_next_attempt_at " +
                        "ON outbox (status, next_attempt_at)",
                ).use { it.step() }
            }
        }

        /**
         * v13 rollback (D-33): a v12 build has no wire event id and no retry/retention state.
         * `DROP COLUMN` needs SQLite 3.35 (API 34), so both tables are rebuilt from the exact
         * v12 DDL and every row is copied — a downgrade is a schema step, never a data loss.
         */
        internal val MIGRATION_13_12 = object : Migration(13, 12) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE `events_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`pair_id` TEXT NOT NULL, " +
                        "`sender_key_id` TEXT NOT NULL, " +
                        "`epoch` INTEGER NOT NULL, " +
                        "`sequence` INTEGER NOT NULL, " +
                        "`previous_event_hash` BLOB, " +
                        "`event_hash` BLOB NOT NULL, " +
                        "`envelope` BLOB NOT NULL, " +
                        "`direction` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`sync_state` TEXT NOT NULL)",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO `events_new` (id, pair_id, sender_key_id, epoch, sequence, " +
                        "previous_event_hash, event_hash, envelope, direction, created_at, sync_state) " +
                        "SELECT id, pair_id, sender_key_id, epoch, sequence, previous_event_hash, " +
                        "event_hash, envelope, direction, created_at, sync_state FROM `events`",
                ).use { it.step() }
                connection.prepare("DROP TABLE `events`").use { it.step() }
                connection.prepare("ALTER TABLE `events_new` RENAME TO `events`").use { it.step() }
                connection.prepare(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_events_pair_id_sender_key_id_sequence` " +
                        "ON `events` (`pair_id`, `sender_key_id`, `sequence`)",
                ).use { it.step() }

                connection.prepare(
                    "CREATE TABLE `outbox_new` (" +
                        "`message_id` TEXT NOT NULL, " +
                        "`event_id` INTEGER NOT NULL, " +
                        "`destination_uid` TEXT, " +
                        "`envelope_bytes` BLOB NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`last_attempt_at` INTEGER, " +
                        "`attempt_count` INTEGER NOT NULL, " +
                        "`relay_checked_at` INTEGER, " +
                        "`relay_rejection` TEXT, " +
                        "PRIMARY KEY(`message_id`))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO `outbox_new` (message_id, event_id, destination_uid, envelope_bytes, " +
                        "status, created_at, last_attempt_at, attempt_count, relay_checked_at, relay_rejection) " +
                        "SELECT message_id, event_id, destination_uid, envelope_bytes, status, " +
                        "created_at, last_attempt_at, attempt_count, relay_checked_at, relay_rejection FROM `outbox`",
                ).use { it.step() }
                connection.prepare("DROP TABLE `outbox`").use { it.step() }
                connection.prepare("ALTER TABLE `outbox_new` RENAME TO `outbox`").use { it.step() }
            }
        }

        /**
         * v14 adds the Step 10 server-revocation acknowledgement on the unpair journal. Nullable,
         * so an upgraded device's existing row simply reports "revocation pending" until the
         * next sync pass retries the terminate and journals the acknowledgement — honest and
         * self-healing, never a claim.
         */
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE unpair_state ADD COLUMN revoked_at INTEGER",
                ).use { it.step() }
            }
        }

        /**
         * v14 rollback (D-33): rebuilds the v13 `unpair_state` from its exact DDL and copies
         * every row — the acknowledgement timestamp is the only column dropped.
         */
        internal val MIGRATION_14_13 = object : Migration(14, 13) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE `unpair_state_new` (" +
                        "`pair_id` TEXT NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`partner_uid` TEXT, " +
                        "`message_id` TEXT NOT NULL, " +
                        "`sender_key_id` TEXT, " +
                        "`key_epoch` INTEGER NOT NULL DEFAULT 0, " +
                        "`request_envelope` BLOB NOT NULL, " +
                        "`delivered` INTEGER NOT NULL, " +
                        "`ack_sent` INTEGER NOT NULL, " +
                        "`local_shredded` INTEGER NOT NULL, " +
                        "`requested_at` INTEGER NOT NULL, " +
                        "`confirmed_at` INTEGER, " +
                        "PRIMARY KEY(`pair_id`))",
                ).use { it.step() }
                connection.prepare(
                    "INSERT INTO `unpair_state_new` (pair_id, role, partner_uid, message_id, " +
                        "sender_key_id, key_epoch, request_envelope, delivered, ack_sent, " +
                        "local_shredded, requested_at, confirmed_at) " +
                        "SELECT pair_id, role, partner_uid, message_id, sender_key_id, key_epoch, " +
                        "request_envelope, delivered, ack_sent, local_shredded, requested_at, " +
                        "confirmed_at FROM `unpair_state`",
                ).use { it.step() }
                connection.prepare("DROP TABLE `unpair_state`").use { it.step() }
                connection.prepare("ALTER TABLE `unpair_state_new` RENAME TO `unpair_state`").use { it.step() }
            }
        }

        /**
         * v15 adds partner delivery plan §8.1's owner-bound service outbox: per-account
         * installation state, the durable pending-event queue, and the singleton capacity
         * incident row. New tables only — no existing row is touched.
         */
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS `installation_state` (" +
                        "`installation_id` TEXT NOT NULL, " +
                        "`owner_uid` TEXT NOT NULL, " +
                        "`current_sequence` INTEGER NOT NULL, " +
                        "`heartbeat_counter` INTEGER NOT NULL DEFAULT 0, " +
                        "`active_key_id` TEXT, " +
                        "`registration_state` TEXT, " +
                        "`schema_version` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`installation_id`))",
                ).use { it.step() }
                connection.prepare(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_installation_state_owner_uid` " +
                        "ON `installation_state` (`owner_uid`)",
                ).use { it.step() }

                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS `pending_partner_events` (" +
                        "`event_id` TEXT NOT NULL, " +
                        "`installation_id` TEXT NOT NULL, " +
                        "`owner_uid` TEXT NOT NULL, " +
                        "`sequence` INTEGER NOT NULL, " +
                        "`schema_version` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`safe_metadata_json` TEXT NOT NULL, " +
                        "`recipient_envelopes_blob` BLOB, " +
                        "`temporary_local_ciphertext` BLOB, " +
                        "`device_occurred_at_ms` INTEGER NOT NULL, " +
                        "`elapsed_realtime_at_capture_ms` INTEGER, " +
                        "`signed_envelope` BLOB, " +
                        "`signing_key_id` TEXT, " +
                        "`status` TEXT NOT NULL, " +
                        "`recipient_key_revision` INTEGER, " +
                        "`attempted_wire_digest` TEXT, " +
                        "`wire_outcome` TEXT NOT NULL DEFAULT 'NOT_SENT', " +
                        "`attempts` INTEGER NOT NULL DEFAULT 0, " +
                        "`next_attempt_at_ms` INTEGER, " +
                        "`lease_until_ms` INTEGER, " +
                        "`last_error_code` TEXT, " +
                        "`created_local_at_ms` INTEGER NOT NULL, " +
                        "`acked_server_at_ms` INTEGER, " +
                        "PRIMARY KEY(`event_id`))",
                ).use { it.step() }
                connection.prepare(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_pending_partner_events_installation_id_sequence` " +
                        "ON `pending_partner_events` (`installation_id`, `sequence`)",
                ).use { it.step() }
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_pending_partner_events_owner_uid_status_next_attempt_at_ms` " +
                        "ON `pending_partner_events` (`owner_uid`, `status`, `next_attempt_at_ms`)",
                ).use { it.step() }

                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS `service_outbox_health` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`incident_code` TEXT NOT NULL, " +
                        "`raised_at_ms` INTEGER NOT NULL, " +
                        "`pending_row_count` INTEGER NOT NULL, " +
                        "`pending_byte_count` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                ).use { it.step() }
            }
        }

        /**
         * v15 rollback (D-33): the three delivery tables are new in v15, so a downgrade drops
         * them whole. Every pre-v15 table and row is untouched.
         */
        internal val MIGRATION_15_14 = object : Migration(15, 14) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare("DROP TABLE IF EXISTS `service_outbox_health`").use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS `pending_partner_events`").use { it.step() }
                connection.prepare("DROP TABLE IF EXISTS `installation_state`").use { it.step() }
            }
        }

        /**
         * v16 adds Phase 4's background-signing state: the installation's registered recipient
         * key id, the raw ECDSA signature bytes (the signature cannot be re-derived from the
         * envelope), and the singleton wake-marker row that bounds WorkManager append churn.
         * Additive only — every v15 row survives unchanged.
         */
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE `installation_state` ADD COLUMN `recipient_key_id` TEXT",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE `pending_partner_events` ADD COLUMN `signing_signature` BLOB",
                ).use { it.step() }
                connection.prepare(
                    "CREATE TABLE IF NOT EXISTS `service_outbox_wake_state` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`wake_requested` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                ).use { it.step() }
            }
        }

        /** v16 rollback (D-33): drop the wake table and the two additive columns. */
        internal val MIGRATION_16_15 = object : Migration(16, 15) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare("DROP TABLE IF EXISTS `service_outbox_wake_state`").use { it.step() }
                connection.prepare(
                    "ALTER TABLE `pending_partner_events` DROP COLUMN `signing_signature`",
                ).use { it.step() }
                connection.prepare(
                    "ALTER TABLE `installation_state` DROP COLUMN `recipient_key_id`",
                ).use { it.step() }
            }
        }

        /**
         * v17 adds the terminal-rejection timestamp: a PERMANENT_REJECTED row erases its
         * content at the transition, and this column bounds the empty diagnostic shell's
         * retention. Additive only — every v16 row survives with a NULL timestamp.
         */
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE `pending_partner_events` ADD COLUMN `terminal_at_ms` INTEGER",
                ).use { it.step() }
            }
        }

        /** v17 rollback (D-33): drop the additive column. */
        internal val MIGRATION_17_16 = object : Migration(17, 16) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE `pending_partner_events` DROP COLUMN `terminal_at_ms`",
                ).use { it.step() }
            }
        }

        /**
         * Every migration, oldest schema to current, plus the one downgrade step (D-33) that
         * Room selects by `(from, to)` — the one list every builder registers.
         * Hand-maintained copies have silently diverged twice (7→8, ADR #50; 9→10, ADR #58),
         * each time leaving an installed device unable to open its DB after the upgrade.
         * `AccountabilityDatabaseMigrationTest` asserts this list covers every version step.
         */
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_12, MIGRATION_13_14, MIGRATION_14_13,
            MIGRATION_14_15, MIGRATION_15_14, MIGRATION_15_16, MIGRATION_16_15,
            MIGRATION_16_17, MIGRATION_17_16,
        )

        @Volatile private var instance: AccountabilityDatabase? = null

        fun get(context: Context): AccountabilityDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AccountabilityDatabase::class.java,
                DB_NAME,
            ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}
