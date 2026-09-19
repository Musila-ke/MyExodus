package com.example.myexodus.accountability.state

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
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
        ProcessedEvent::class,
        UnpairState::class,
    ],
    version = 10,
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

        /**
         * Every migration, oldest schema to current — the one list every builder registers.
         * Hand-maintained copies have silently diverged twice (7→8, ADR #50; 9→10, ADR #58),
         * each time leaving an installed device unable to open its DB after the upgrade.
         * `AccountabilityDatabaseMigrationTest` asserts this list covers every version step.
         */
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
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
