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
    entities = [ChainEvent::class, ChainHead::class, EnrollmentRecord::class, IdentityEpoch::class],
    version = 4,
    exportSchema = false,
)
abstract class AccountabilityDatabase : RoomDatabase() {

    abstract fun chainEventDao(): ChainEventDao
    abstract fun chainHeadDao(): ChainHeadDao
    abstract fun enrollmentDao(): EnrollmentDao
    abstract fun identityEpochDao(): IdentityEpochDao

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

        @Volatile private var instance: AccountabilityDatabase? = null

        fun get(context: Context): AccountabilityDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AccountabilityDatabase::class.java,
                DB_NAME,
            ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
