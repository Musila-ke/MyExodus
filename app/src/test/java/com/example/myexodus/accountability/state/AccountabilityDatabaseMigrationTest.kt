package com.example.myexodus.accountability.state

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Accountability-store migration 2→3 (identity_epoch, Decision 12). The v2 baseline is the
 * exact DDL Room generates for version 2 (v3's generated DDL minus the identity_epoch
 * table), so the test proves the real upgrade path end to end, including Room's
 * post-migration schema validation (the 2026-08-28 migration failure class).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountabilityDatabaseMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(AccountabilityDatabase.DB_NAME)
    }

    @Test
    fun migration2to3_addsIdentityEpochAndRoomAcceptsSchema() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V2_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            conn.prepare("PRAGMA user_version = 2").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(
            AccountabilityDatabase.MIGRATION_1_2,
            AccountabilityDatabase.MIGRATION_2_3,
            AccountabilityDatabase.MIGRATION_3_4, // watermark column (mailbox quota fix)
        ).build()
        runBlocking {
            // Fresh identity_epoch table: no rows yet, fully usable.
            assertNull(db.identityEpochDao().current())
            db.identityEpochDao().upsert(IdentityEpoch(epoch = 7))
            assertEquals(7, db.identityEpochDao().current())
            // Sibling tables intact and queryable after the migration.
            assertNull(db.enrollmentDao().getByPairId("pair-a"))
        }
        db.close()
    }

    companion object {
        /** Verbatim v2 schema DDL from Room's generated AccountabilityDatabase_Impl (v3 minus identity_epoch). */
        private val V2_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `events` (" +
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
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_events_pair_id_sender_key_id_sequence` " +
                "ON `events` (`pair_id`, `sender_key_id`, `sequence`)",
            "CREATE TABLE IF NOT EXISTS `chain_heads` (" +
                "`pair_id` TEXT NOT NULL, " +
                "`sender_key_id` TEXT NOT NULL, " +
                "`last_sequence` INTEGER NOT NULL, " +
                "`last_event_hash` BLOB NOT NULL, " +
                "`forked` INTEGER NOT NULL, " +
                "PRIMARY KEY(`pair_id`, `sender_key_id`))",
            "CREATE TABLE IF NOT EXISTS enrollments (" +
                "pair_id TEXT NOT NULL PRIMARY KEY, " +
                "my_sender_key_id TEXT NOT NULL, " +
                "partner_signing_key_id TEXT NOT NULL, " +
                "partner_signing_pubkey BLOB NOT NULL, " +
                "partner_agreement_pubkey BLOB NOT NULL, " +
                "created_at INTEGER NOT NULL)",
        )
    }
}
