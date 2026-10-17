package com.example.myexodus.accountability.state

import android.app.Application
import android.content.Context
import java.io.RandomAccessFile
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * v1→v12 in one open — the path a pre-Phase-4 install takes on its first launch after an
     * update. This is the only test whose baseline lacks the enrollments table, so it is the
     * only one that executes [AccountabilityDatabase.MIGRATION_1_2]'s hand-written DDL at all;
     * Room's post-migration validation then proves that DDL still matches the entity schema.
     * Registers the production [AccountabilityDatabase.ALL_MIGRATIONS] list verbatim.
     */
    @Test
    fun migrationFromV1_runsTheEntireChain_creatingEnrollmentsAndKeepingChainData() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V1_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            conn.prepare(
                "INSERT INTO events (pair_id, sender_key_id, epoch, sequence, previous_event_hash, " +
                    "event_hash, envelope, direction, created_at, sync_state) " +
                    "VALUES ('pair-a', 'k1', 0, 1, NULL, x'01', x'02', 'OUT', 5, 'PENDING')",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO chain_heads (pair_id, sender_key_id, last_sequence, last_event_hash, forked) " +
                    "VALUES ('pair-a', 'k1', 1, x'01', 0)",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 1").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
        runBlocking {
            // MIGRATION_1_2 ran for real: the table exists, matches Room's expected schema
            // (validated on this very query), and is writable.
            assertNull(db.enrollmentDao().getByPairId("pair-a"))
            db.enrollmentDao().upsert(
                EnrollmentRecord(
                    pairId = "pair-a",
                    mySenderKeyId = "k1",
                    partnerSigningKeyId = "k2",
                    partnerSigningPubKey = ByteArray(1),
                    partnerAgreementPubKey = ByteArray(1),
                    createdAtEpochMillis = 6,
                ),
            )
            assertEquals("k1", db.enrollmentDao().getByPairId("pair-a")?.mySenderKeyId)

            // The v1 chain data survived the whole chain, and every later table is live.
            assertEquals(1L, db.chainEventDao().chainFor("pair-a").single().sequence)
            assertEquals(0, db.chainHeadDao().get("pair-a", "k1")?.keyEpoch)
            db.identityEpochDao().upsert(IdentityEpoch(epoch = 1))
            assertEquals(1, db.identityEpochDao().current())
        }
        db.close()
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
            AccountabilityDatabase.MIGRATION_4_5, // outbox, processed_events, key_epoch
            AccountabilityDatabase.MIGRATION_5_6, // AD-08 repair columns
            AccountabilityDatabase.MIGRATION_6_7, // approval_requests + pairing epoch
            AccountabilityDatabase.MIGRATION_7_8, // unpair journal (AD-13a/b)
            AccountabilityDatabase.MIGRATION_8_9, // Step 15 rotation anchors
            AccountabilityDatabase.MIGRATION_9_10, // Step 18 rejection-check columns
            AccountabilityDatabase.MIGRATION_10_11, // §19.5 partner label
            AccountabilityDatabase.MIGRATION_11_12, // ADR #106 per-pair agreement key alias
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

    /**
     * Plan §8 Step 4 (v4→v5): outbox + processed_events appear, chain_heads gains key_epoch
     * with a 0 default for rows that predate the column, and pre-existing chain data survives.
     */
    @Test
    fun migration4to5_addsStep4Schema_preservingChainData() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V4_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            // Pre-existing chain data: must survive the migration, with key_epoch defaulted to 0.
            conn.prepare(
                "INSERT INTO events (pair_id, sender_key_id, epoch, sequence, previous_event_hash, " +
                    "event_hash, envelope, direction, created_at, sync_state) " +
                    "VALUES ('pair-a', 'k1', 0, 1, NULL, x'01', x'02', 'SENT', 5, 'PENDING')",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO chain_heads (pair_id, sender_key_id, last_sequence, last_event_hash, forked) " +
                    "VALUES ('pair-a', 'k1', 1, x'01', 0)",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 4").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(
            AccountabilityDatabase.MIGRATION_1_2,
            AccountabilityDatabase.MIGRATION_2_3,
            AccountabilityDatabase.MIGRATION_3_4,
            AccountabilityDatabase.MIGRATION_4_5,
            AccountabilityDatabase.MIGRATION_5_6,
            AccountabilityDatabase.MIGRATION_6_7,
            AccountabilityDatabase.MIGRATION_7_8, // unpair journal (AD-13a/b)
            AccountabilityDatabase.MIGRATION_8_9, // Step 15 rotation anchors
            AccountabilityDatabase.MIGRATION_9_10, // Step 18 rejection-check columns
            AccountabilityDatabase.MIGRATION_10_11, // §19.5 partner label
            AccountabilityDatabase.MIGRATION_11_12, // ADR #106 per-pair agreement key alias
        ).build()
        runBlocking {
            // The ADD COLUMN default backfills the pre-existing head row.
            val head = db.chainHeadDao().get("pair-a", "k1")
            assertEquals(1L, head?.lastSequence)
            assertEquals(0, head?.keyEpoch)
            // Chain event intact and its id usable as the outbox FK.
            val event = db.chainEventDao().chainFor("pair-a").single()
            db.outboxDao().insert(
                Outbox(
                    messageId = "m1",
                    eventId = event.id,
                    envelopeBytes = event.envelope,
                    status = Outbox.STATUS_PENDING,
                    createdAtEpochMillis = 6,
                ),
            )
            val pending = db.outboxDao().pendingFor("pair-a", 10).single()
            assertEquals("m1", pending.messageId)
            assertNull(pending.destinationUid)
            db.outboxDao().markSent("m1")
            db.outboxDao().recordAttempt("m1", 7)
            assertTrue(db.outboxDao().pendingFor("pair-a", 10).isEmpty())
            // processed_events table is live.
            db.processedEventDao().insert(ProcessedEvent("m1", 8))
            assertTrue(db.processedEventDao().contains("m1"))
            assertFalse(db.processedEventDao().contains("m2"))
        }
        db.close()
    }

    /**
     * Plan §8 Step 11 (v5→v6): the AD-08 repair columns land on chain_heads with NONE
     * defaults — a pre-existing head means "no repair" — and the retired v1 terminal value
     * is renamed so old rows speak the new constant.
     */
    @Test
    fun migration5to6_addsRepairColumns_andRenamesTheRetiredV1TerminalState() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V5_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            conn.prepare(
                "INSERT INTO chain_heads (pair_id, sender_key_id, last_sequence, last_event_hash, forked, key_epoch) " +
                    "VALUES ('pair-a', 'k1', 1, x'01', 0, 0)",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO events (pair_id, sender_key_id, epoch, sequence, previous_event_hash, " +
                    "event_hash, envelope, direction, created_at, sync_state) " +
                    "VALUES ('pair-a', 'k1', 0, 5, NULL, x'03', x'04', 'RECEIVED', 9, 'PERMANENTLY_INCOMPLETE')",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 5").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(
            AccountabilityDatabase.MIGRATION_1_2,
            AccountabilityDatabase.MIGRATION_2_3,
            AccountabilityDatabase.MIGRATION_3_4,
            AccountabilityDatabase.MIGRATION_4_5,
            AccountabilityDatabase.MIGRATION_5_6,
            AccountabilityDatabase.MIGRATION_6_7,
            AccountabilityDatabase.MIGRATION_7_8, // unpair journal (AD-13a/b)
            AccountabilityDatabase.MIGRATION_8_9, // Step 15 rotation anchors
            AccountabilityDatabase.MIGRATION_9_10, // Step 18 rejection-check columns
            AccountabilityDatabase.MIGRATION_10_11, // §19.5 partner label
            AccountabilityDatabase.MIGRATION_11_12, // ADR #106 per-pair agreement key alias
        ).build()
        runBlocking {
            val head = db.chainHeadDao().get("pair-a", "k1")
            assertEquals(ChainHead.REPAIR_NONE, head?.repairState)
            assertNull(head?.repairFirstRequestedAtEpochMillis)
            assertNull(head?.repairLastRequestedAtEpochMillis)
            assertTrue(db.chainHeadDao().repairHeads("pair-a").isEmpty())
            assertEquals(
                ChainEvent.SYNC_STATE_CHAIN_INCOMPLETE,
                db.chainEventDao().chainFor("pair-a").single().syncState,
            )
        }
        db.close()
    }

    /**
     * Plan §8 Step 12 (v6→v7): the approval_requests state-machine table appears, enrollments
     * gains the AD-07 pairing epoch (backfilled to 0 = "pre-v7 enrollment", which cannot match
     * any live epoch at epoch 1+), and existing enrollment data survives.
     */
    @Test
    fun migration6to7_addsApprovalRequestsAndPairingEpoch_preservingEnrollment() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V6_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            conn.prepare(
                "INSERT INTO enrollments (pair_id, my_sender_key_id, partner_signing_key_id, " +
                    "partner_signing_pubkey, partner_agreement_pubkey, created_at, mailbox_watermark_ts) " +
                    "VALUES ('pair-a', 'k1', 'k2', x'01', x'02', 5, NULL)",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 6").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(
            AccountabilityDatabase.MIGRATION_1_2,
            AccountabilityDatabase.MIGRATION_2_3,
            AccountabilityDatabase.MIGRATION_3_4,
            AccountabilityDatabase.MIGRATION_4_5,
            AccountabilityDatabase.MIGRATION_5_6,
            AccountabilityDatabase.MIGRATION_6_7,
            AccountabilityDatabase.MIGRATION_7_8, // unpair journal (AD-13a/b)
            AccountabilityDatabase.MIGRATION_8_9, // Step 15 rotation anchors
            AccountabilityDatabase.MIGRATION_9_10, // Step 18 rejection-check columns
            AccountabilityDatabase.MIGRATION_10_11, // §19.5 partner label
            AccountabilityDatabase.MIGRATION_11_12, // ADR #106 per-pair agreement key alias
        ).build()
        runBlocking {
            val enrollment = db.enrollmentDao().getByPairId("pair-a")!!
            assertEquals("k1", enrollment.mySenderKeyId)
            assertEquals(0, enrollment.pairingEpoch)

            // Fresh approval_requests table: insert + CAS guard both live after the migration.
            val dao = db.approvalRequestDao()
            dao.insertIgnore(
                ApprovalRequest(
                    requestEventId = "k1:1",
                    pairId = "pair-a",
                    role = ApprovalRequest.ROLE_REQUESTER,
                    status = ApprovalRequest.STATUS_REQUESTED,
                    pairingEpoch = 0,
                    message = "please",
                    createdAtEpochMillis = 10,
                    expiresAtEpochMillis = 10 + ApprovalRequest.TTL_MILLIS,
                ),
            )
            assertEquals(ApprovalRequest.STATUS_REQUESTED, dao.byId("k1:1")?.status)
            assertEquals(1, dao.casApply("k1:1", ApprovalRequest.STATUS_APPROVED, 11))
            assertEquals(0, dao.casApply("k1:1", ApprovalRequest.STATUS_REJECTED, 12))
        }
        db.close()
    }

    /**
     * Plan §8 Step 13 (v7→v8): the unpair journal (AD-13a/b) appears; the migration is purely
     * additive — existing v7 data (the enrollment, including its pairing epoch) survives, and
     * the fresh table is fully usable, including the derived phase logic.
     */
    @Test
    fun migration7to8_addsUnpairJournal_preservingV7Enrollment() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V7_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            conn.prepare(
                "INSERT INTO enrollments (pair_id, my_sender_key_id, partner_signing_key_id, " +
                    "partner_signing_pubkey, partner_agreement_pubkey, created_at, mailbox_watermark_ts, " +
                    "pairing_epoch) VALUES ('pair-a', 'k1', 'k2', x'01', x'02', 5, NULL, 2)",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 7").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(
            AccountabilityDatabase.MIGRATION_1_2,
            AccountabilityDatabase.MIGRATION_2_3,
            AccountabilityDatabase.MIGRATION_3_4,
            AccountabilityDatabase.MIGRATION_4_5,
            AccountabilityDatabase.MIGRATION_5_6,
            AccountabilityDatabase.MIGRATION_6_7,
            AccountabilityDatabase.MIGRATION_7_8,
            AccountabilityDatabase.MIGRATION_8_9, // Step 15 rotation anchors
            AccountabilityDatabase.MIGRATION_9_10, // Step 18 rejection-check columns
            AccountabilityDatabase.MIGRATION_10_11, // §19.5 partner label
            AccountabilityDatabase.MIGRATION_11_12, // ADR #106 per-pair agreement key alias
        ).build()
        runBlocking {
            val enrollment = db.enrollmentDao().getByPairId("pair-a")!!
            assertEquals("k1", enrollment.mySenderKeyId)
            assertEquals(2, enrollment.pairingEpoch) // the v7 epoch survives untouched

            // Fresh unpair journal: repository round trip + derived phases after the migration.
            db.unpairStateDao().upsert(
                UnpairState(
                    pairId = "pair-a",
                    role = UnpairState.ROLE_REQUESTER,
                    partnerUid = "uid-b",
                    messageId = "m-1",
                    delivered = true,
                    ackSent = false,
                    localShredded = true,
                    requestedAtEpochMillis = 10,
                ),
            )
            val loaded = db.unpairStateDao().get("pair-a")!!
            assertTrue(loaded.localShredded)
            assertEquals(UnpairPhase.AWAITING_CONFIRMATION, loaded.phaseAt(11))
            db.unpairStateDao().markConfirmed("pair-a", 12)
            assertEquals(UnpairPhase.TERMINAL, db.unpairStateDao().get("pair-a")!!.phaseAt(12))
        }
        db.close()
    }

    /**
     * Plan §8 Step 15 / AD-12 (v8→v9): the rotation anchors appear on chain_heads. A
     * pre-existing head keeps its epoch and gains no anchor — `key_epoch_started_at` stays
     * NULL and `key_epoch_start_sequence` defaults to 0 — because a fabricated start point
     * would claim an observed age the device never measured. The next event stamps one.
     */
    @Test
    fun migration8to9_addsRotationAnchors_preservingTheHead() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V8_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            conn.prepare(
                "INSERT INTO chain_heads (pair_id, sender_key_id, last_sequence, last_event_hash, " +
                    "forked, key_epoch, repair_state, repair_first_requested_at, repair_last_requested_at) " +
                    "VALUES ('pair-a', 'k1', 500, x'01', 0, 2, 'NONE', NULL, NULL)",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 8").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(
            AccountabilityDatabase.MIGRATION_1_2,
            AccountabilityDatabase.MIGRATION_2_3,
            AccountabilityDatabase.MIGRATION_3_4,
            AccountabilityDatabase.MIGRATION_4_5,
            AccountabilityDatabase.MIGRATION_5_6,
            AccountabilityDatabase.MIGRATION_6_7,
            AccountabilityDatabase.MIGRATION_7_8,
            AccountabilityDatabase.MIGRATION_8_9, // Step 15 rotation anchors
            AccountabilityDatabase.MIGRATION_9_10, // Step 18 rejection-check columns
            AccountabilityDatabase.MIGRATION_10_11, // §19.5 partner label
            AccountabilityDatabase.MIGRATION_11_12, // ADR #106 per-pair agreement key alias
        ).build()
        runBlocking {
            val head = db.chainHeadDao().get("pair-a", "k1")!!
            assertEquals(500L, head.lastSequence)
            assertEquals(2, head.keyEpoch) // the pre-v9 epoch survives
            assertNull(head.keyEpochStartedAtEpochMillis) // no anchor until an event stamps one
            assertEquals(0L, head.keyEpochStartSequence)
            // Sibling tables intact after the migration.
            assertNull(db.enrollmentDao().getByPairId("pair-a"))
        }
        db.close()
    }

    /**
     * Plan §8 Step 18 / AD-23 (v9→v10): the outbox gains the rejection-check columns. A
     * pre-existing row keeps its status and bytes and is simply due for one read-back — the
     * honest reading, since the client has never looked at its relay marker.
     */
    @Test
    fun migration9to10_addsRelayCheckColumns_preservingTheOutbox() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V9_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            conn.prepare(
                "INSERT INTO events (pair_id, sender_key_id, epoch, sequence, previous_event_hash, " +
                    "event_hash, envelope, direction, created_at, sync_state) " +
                    "VALUES ('pair-a', 'k1', 0, 1, NULL, x'01', x'02', 'OUT', 1, 'SYNCED')",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO outbox (message_id, event_id, destination_uid, envelope_bytes, status, " +
                    "created_at, last_attempt_at, attempt_count) " +
                    "VALUES ('m-1', 1, 'uid-b', x'03', 'SENT', 1, NULL, 0)",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 9").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(
            AccountabilityDatabase.MIGRATION_1_2,
            AccountabilityDatabase.MIGRATION_2_3,
            AccountabilityDatabase.MIGRATION_3_4,
            AccountabilityDatabase.MIGRATION_4_5,
            AccountabilityDatabase.MIGRATION_5_6,
            AccountabilityDatabase.MIGRATION_6_7,
            AccountabilityDatabase.MIGRATION_7_8,
            AccountabilityDatabase.MIGRATION_8_9,
            AccountabilityDatabase.MIGRATION_9_10,
            AccountabilityDatabase.MIGRATION_10_11, // §19.5 partner label
            AccountabilityDatabase.MIGRATION_11_12, // ADR #106 per-pair agreement key alias
        ).build()
        runBlocking {
            // The migrated row survives byte-for-byte and is due exactly one read-back.
            val snapshot = db.outboxDao().snapshotOf("m-1")!!
            assertEquals(Outbox.STATUS_SENT, snapshot.status)
            assertEquals(3, snapshot.envelopeBytes.single().toInt())
            val due = db.outboxDao().relayCheckDue("pair-a", settleBeforeEpochMillis = 0, limit = 10)
            assertEquals(listOf("m-1"), due.map { it.messageId })

            // The new columns are live: stamping a rejection removes the row from the due set
            // and makes it countable for the dashboard window.
            db.outboxDao().stampRelayCheck("m-1", Outbox.REJECTION_RATE_LIMITED, 5)
            assertTrue(db.outboxDao().relayCheckDue("pair-a", settleBeforeEpochMillis = 0, limit = 10).isEmpty())
            assertEquals(1, db.outboxDao().rejectionCountSince("pair-a", sinceEpochMillis = 0))
        }
        db.close()
    }

    /**
     * §19.5 item 3 (v10→v11): enrollments gains the partner label. A pre-existing enrollment
     * keeps every byte of its identity material and reads back with a null label — the honest
     * "no name was ever exchanged" — and the column is writable from then on. The v10 outbox
     * (with its Step 18 relay-check columns) must survive untouched: 10→11 touches only
     * enrollments.
     */
    @Test
    fun migration10to11_addsThePartnerLabel_preservingTheEnrollment() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V10_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            conn.prepare(
                "INSERT INTO enrollments (pair_id, my_sender_key_id, partner_signing_key_id, " +
                    "partner_signing_pubkey, partner_agreement_pubkey, created_at, mailbox_watermark_ts, " +
                    "pairing_epoch) VALUES ('pair-a', 'k1', 'k2', x'01', x'02', 5, 9, 3)",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO events (pair_id, sender_key_id, epoch, sequence, previous_event_hash, " +
                    "event_hash, envelope, direction, created_at, sync_state) " +
                    "VALUES ('pair-a', 'k1', 0, 1, NULL, x'01', x'02', 'OUT', 1, 'SYNCED')",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO outbox (message_id, event_id, destination_uid, envelope_bytes, status, " +
                    "created_at, last_attempt_at, attempt_count, relay_checked_at, relay_rejection) " +
                    "VALUES ('m-1', 1, 'uid-b', x'03', 'SENT', 1, NULL, 0, 7, 'RATE_LIMITED')",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 10").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(
            AccountabilityDatabase.MIGRATION_1_2,
            AccountabilityDatabase.MIGRATION_2_3,
            AccountabilityDatabase.MIGRATION_3_4,
            AccountabilityDatabase.MIGRATION_4_5,
            AccountabilityDatabase.MIGRATION_5_6,
            AccountabilityDatabase.MIGRATION_6_7,
            AccountabilityDatabase.MIGRATION_7_8,
            AccountabilityDatabase.MIGRATION_8_9,
            AccountabilityDatabase.MIGRATION_9_10,
            AccountabilityDatabase.MIGRATION_10_11,
            AccountabilityDatabase.MIGRATION_11_12, // ADR #106 per-pair agreement key alias
        ).build()
        runBlocking {
            val enrollment = checkNotNull(db.enrollmentDao().getByPairId("pair-a"))
            assertEquals("k1", enrollment.mySenderKeyId)
            assertEquals("k2", enrollment.partnerSigningKeyId)
            assertEquals(3, enrollment.pairingEpoch)
            assertEquals(9L, enrollment.mailboxWatermarkTsEpochMillis)
            assertNull("a pre-v11 enrollment never exchanged a name", enrollment.partnerLabel)

            // The label column is live: a re-pair or a name backfill can write one.
            db.enrollmentDao().upsert(enrollment.copy(partnerLabel = "Ana"))
            assertEquals("Ana", db.enrollmentDao().getByPairId("pair-a")?.partnerLabel)
            assertEquals(1, db.enrollmentDao().all().size)

            // The v10 outbox and its already-stamped relay check are untouched by 10→11.
            assertTrue(
                "a stamped row is not due again",
                db.outboxDao().relayCheckDue("pair-a", settleBeforeEpochMillis = 0, limit = 10).isEmpty(),
            )
            assertEquals(1, db.outboxDao().rejectionCountSince("pair-a", sinceEpochMillis = 0))
        }
        db.close()
    }

    /**
     * ADR #106 (v11→v12): enrollments gains the pair's own agreement-key alias. A pre-existing
     * enrollment keeps every byte of its identity material, reads back with a null alias — the
     * honest "this pair shares the device-level agreement key" — and the column is writable from
     * then on. The v11 label and outbox survive untouched: 11→12 touches only enrollments.
     */
    @Test
    fun migration11to12_addsThePairAgreementAlias_preservingTheEnrollment() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V11_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            conn.prepare(
                "INSERT INTO enrollments (pair_id, my_sender_key_id, partner_signing_key_id, " +
                    "partner_signing_pubkey, partner_agreement_pubkey, created_at, mailbox_watermark_ts, " +
                    "pairing_epoch, partner_label) VALUES ('pair-a', 'k1', 'k2', x'01', x'02', 5, 9, 3, 'Ana')",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO events (pair_id, sender_key_id, epoch, sequence, previous_event_hash, " +
                    "event_hash, envelope, direction, created_at, sync_state) " +
                    "VALUES ('pair-a', 'k1', 0, 1, NULL, x'01', x'02', 'OUT', 1, 'SYNCED')",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO outbox (message_id, event_id, destination_uid, envelope_bytes, status, " +
                    "created_at, last_attempt_at, attempt_count, relay_checked_at, relay_rejection) " +
                    "VALUES ('m-1', 1, 'uid-b', x'03', 'SENT', 1, NULL, 0, 7, 'RATE_LIMITED')",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 11").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(
            AccountabilityDatabase.MIGRATION_1_2,
            AccountabilityDatabase.MIGRATION_2_3,
            AccountabilityDatabase.MIGRATION_3_4,
            AccountabilityDatabase.MIGRATION_4_5,
            AccountabilityDatabase.MIGRATION_5_6,
            AccountabilityDatabase.MIGRATION_6_7,
            AccountabilityDatabase.MIGRATION_7_8,
            AccountabilityDatabase.MIGRATION_8_9,
            AccountabilityDatabase.MIGRATION_9_10,
            AccountabilityDatabase.MIGRATION_10_11,
            AccountabilityDatabase.MIGRATION_11_12,
        ).build()
        runBlocking {
            val enrollment = checkNotNull(db.enrollmentDao().getByPairId("pair-a"))
            assertEquals("k1", enrollment.mySenderKeyId)
            assertEquals("Ana", enrollment.partnerLabel)
            assertEquals(3, enrollment.pairingEpoch)
            assertEquals(9L, enrollment.mailboxWatermarkTsEpochMillis)
            assertNull(
                "a pre-v12 enrollment used the device-level agreement key",
                enrollment.myAgreementKeyAlias,
            )

            // The alias column is live: the next pairing records its own key here.
            db.enrollmentDao().upsert(enrollment.copy(myAgreementKeyAlias = "accountability_pair_agreement_key_s1"))
            assertEquals(
                "accountability_pair_agreement_key_s1",
                db.enrollmentDao().getByPairId("pair-a")?.myAgreementKeyAlias,
            )
            assertEquals(1, db.enrollmentDao().all().size)

            // The v11 outbox and its already-stamped relay check are untouched by 11→12.
            assertTrue(
                "a stamped row is not due again",
                db.outboxDao().relayCheckDue("pair-a", settleBeforeEpochMillis = 0, limit = 10).isEmpty(),
            )
            assertEquals(1, db.outboxDao().rejectionCountSince("pair-a", sinceEpochMillis = 0))
        }
        db.close()
    }

    /**
     * The guard for the bug class this suite structurally missed twice: the per-test migration
     * lists are hand-written and were always complete, while the DI builder's copy silently
     * lagged a schema bump (7→8 fixed in ADR #50; 9→10 caught on-device by the ADR #58 approval
     * leg — an installed v9 device could not open its DB). [AccountabilityDatabase.ALL_MIGRATIONS]
     * is now the single list both builders register; this asserts it covers every step to the
     * declared version, so the next bump fails here instead of on a user's device.
     */
    @Test
    fun allMigrations_coverEveryVersionStepToTheDeclaredSchemaVersion() {
        // The declared version is read from the DB Room actually creates for a fresh install
        // (the @Database annotation is not retained at runtime; a freshly opened empty DB is
        // created at exactly the declared schema version).
        val scratch = "migration-coverage.db"
        context.deleteDatabase(scratch)
        val db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, scratch).build()
        runBlocking { db.identityEpochDao().current() }
        db.close()
        val declared = RandomAccessFile(context.getDatabasePath(scratch), "r").use {
            it.seek(60) // SQLite header: user_version, 4 bytes big-endian
            it.readInt()
        }
        context.deleteDatabase(scratch)

        val covered = AccountabilityDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()
        (1 until declared).forEach { from ->
            assertTrue(
                "no migration registered for v$from → v${from + 1} (declared schema v$declared)",
                (from to from + 1) in covered,
            )
        }
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

        /** v1 baseline: chain tables only — a pre-Phase-4 install had no enrollments table,
         *  which no other test baseline omits (they all subsume MIGRATION_1_2's work). */
        private val V1_DDL = V2_DDL.take(3)

        /** v4 baseline: V2 + identity_epoch (2→3) + mailbox_watermark_ts (3→4); no Step-4 tables. */
        private val V4_DDL = V2_DDL.take(3) + listOf(
            "CREATE TABLE IF NOT EXISTS enrollments (" +
                "pair_id TEXT NOT NULL PRIMARY KEY, " +
                "my_sender_key_id TEXT NOT NULL, " +
                "partner_signing_key_id TEXT NOT NULL, " +
                "partner_signing_pubkey BLOB NOT NULL, " +
                "partner_agreement_pubkey BLOB NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "mailbox_watermark_ts INTEGER)",
            "CREATE TABLE IF NOT EXISTS identity_epoch (" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "epoch INTEGER NOT NULL)",
        )

        /** v5 baseline: v4 + Step-4 schema — chain_heads.key_epoch, outbox, processed_events. */
        private val V5_DDL = V2_DDL.take(2) + listOf(
            "CREATE TABLE IF NOT EXISTS `chain_heads` (" +
                "`pair_id` TEXT NOT NULL, " +
                "`sender_key_id` TEXT NOT NULL, " +
                "`last_sequence` INTEGER NOT NULL, " +
                "`last_event_hash` BLOB NOT NULL, " +
                "`forked` INTEGER NOT NULL, " +
                "`key_epoch` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`pair_id`, `sender_key_id`))",
            V4_DDL[3],
            V4_DDL[4],
            "CREATE TABLE IF NOT EXISTS `outbox` (" +
                "`message_id` TEXT NOT NULL, " +
                "`event_id` INTEGER NOT NULL, " +
                "`destination_uid` TEXT, " +
                "`envelope_bytes` BLOB NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`last_attempt_at` INTEGER, " +
                "`attempt_count` INTEGER NOT NULL, " +
                "PRIMARY KEY(`message_id`))",
            "CREATE TABLE IF NOT EXISTS `processed_events` (" +
                "`message_id` TEXT NOT NULL, " +
                "`processed_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`message_id`))",
        )

        /** v6 baseline: v5 + AD-08 repair columns on chain_heads; enrollments has no pairing_epoch. */
        private val V6_DDL = listOf(
            V5_DDL[0],
            V5_DDL[1],
            "CREATE TABLE IF NOT EXISTS `chain_heads` (" +
                "`pair_id` TEXT NOT NULL, " +
                "`sender_key_id` TEXT NOT NULL, " +
                "`last_sequence` INTEGER NOT NULL, " +
                "`last_event_hash` BLOB NOT NULL, " +
                "`forked` INTEGER NOT NULL, " +
                "`key_epoch` INTEGER NOT NULL DEFAULT 0, " +
                "`repair_state` TEXT NOT NULL DEFAULT 'NONE', " +
                "`repair_first_requested_at` INTEGER, " +
                "`repair_last_requested_at` INTEGER, " +
                "PRIMARY KEY(`pair_id`, `sender_key_id`))",
            V5_DDL[3],
            V5_DDL[4],
            V5_DDL[5],
            V5_DDL[6],
        )

        /** v7 baseline: v6 + MIGRATION_6_7's shape — the enrollments DDL gains the appended
         *  pairing_epoch column and the approval_requests table appears. */
        private val V7_DDL = V6_DDL.filterNot { it.contains("enrollments") } + listOf(
            "CREATE TABLE IF NOT EXISTS enrollments (" +
                "pair_id TEXT NOT NULL PRIMARY KEY, " +
                "my_sender_key_id TEXT NOT NULL, " +
                "partner_signing_key_id TEXT NOT NULL, " +
                "partner_signing_pubkey BLOB NOT NULL, " +
                "partner_agreement_pubkey BLOB NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "mailbox_watermark_ts INTEGER, " +
                "pairing_epoch INTEGER NOT NULL DEFAULT 0)",
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
        )

        /** v8 baseline: v7 + MIGRATION_7_8's unpair_state table (AD-13a/b); the chain_heads
         *  DDL is unchanged in 7→8. */
        private val V8_DDL = V7_DDL + listOf(
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
        )

        /** v9 baseline: v8 + MIGRATION_8_9's rotation anchors on chain_heads (Step 15); the
         *  outbox DDL is unchanged in 8→9. */
        private val V9_DDL = V8_DDL.filterNot { it.contains("`chain_heads`") } + listOf(
            "CREATE TABLE IF NOT EXISTS `chain_heads` (" +
                "`pair_id` TEXT NOT NULL, " +
                "`sender_key_id` TEXT NOT NULL, " +
                "`last_sequence` INTEGER NOT NULL, " +
                "`last_event_hash` BLOB NOT NULL, " +
                "`forked` INTEGER NOT NULL, " +
                "`key_epoch` INTEGER NOT NULL DEFAULT 0, " +
                "`repair_state` TEXT NOT NULL DEFAULT 'NONE', " +
                "`repair_first_requested_at` INTEGER, " +
                "`repair_last_requested_at` INTEGER, " +
                "`key_epoch_started_at` INTEGER, " +
                "`key_epoch_start_sequence` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`pair_id`, `sender_key_id`))",
        )

        /** v10 baseline: v9 + MIGRATION_9_10's relay-check columns on outbox; chain_heads and
         *  enrollments are unchanged in 9→10 (enrollments gains its label only in 10→11). */
        private val V10_DDL = V9_DDL.filterNot { it.contains("`outbox`") } + listOf(
            "CREATE TABLE IF NOT EXISTS `outbox` (" +
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
        )

        /** v11 baseline: v10 + MIGRATION_10_11's partner_label on enrollments (§19.5 item 3);
         *  every other table is unchanged in 10→11. */
        private val V11_DDL = V10_DDL.filterNot { it.contains("enrollments") } + listOf(
            "CREATE TABLE IF NOT EXISTS enrollments (" +
                "pair_id TEXT NOT NULL PRIMARY KEY, " +
                "my_sender_key_id TEXT NOT NULL, " +
                "partner_signing_key_id TEXT NOT NULL, " +
                "partner_signing_pubkey BLOB NOT NULL, " +
                "partner_agreement_pubkey BLOB NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "mailbox_watermark_ts INTEGER, " +
                "pairing_epoch INTEGER NOT NULL DEFAULT 0, " +
                "partner_label TEXT)",
        )
    }
}
