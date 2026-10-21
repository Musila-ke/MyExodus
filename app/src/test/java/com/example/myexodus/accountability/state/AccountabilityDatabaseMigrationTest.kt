package com.example.myexodus.accountability.state

import android.app.Application
import android.content.Context
import java.io.RandomAccessFile
import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

    private fun columnNames(conn: SQLiteConnection, table: String): Set<String> {
        val names = mutableSetOf<String>()
        conn.prepare("PRAGMA table_info(`$table`)").use { s ->
            while (s.step()) names += s.getText(1)
        }
        return names
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
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
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
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
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
            db.outboxDao().recordAttempt("m1", 7, 67, null)
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
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
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
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
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
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
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
            // Step 10: an unacknowledged server-side exit is reported as pending even though
            // the local revocation is already in force.
            assertNull(loaded.revokedAtEpochMillis)
            assertEquals(UnpairPhase.AWAITING_REVOCATION, loaded.phaseAt(11))
            db.unpairStateDao().markRevoked("pair-a", 11)
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
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
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
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
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
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
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
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
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
     * Partner-accountability Step 4 (v12→v13): `events.event_id` + the unique
     * `(pair_id, sender_key_id, event_id)` index, and the §8.5/§8.7 outbox columns. A
     * pre-existing PENDING outbox row keeps its bytes and gets a retention window anchored to
     * its creation (the exact window the new write path would have stamped); the relay-check
     * columns survive untouched; a pre-v13 event reads back with a null wire id.
     */
    @Test
    fun migration12to13_addsEventIdAndOutboxRetryColumns_preservingQueuedWork() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath
        val createdAt = 100L

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V12_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            conn.prepare(
                "INSERT INTO events (pair_id, sender_key_id, epoch, sequence, previous_event_hash, " +
                    "event_hash, envelope, direction, created_at, sync_state) " +
                    "VALUES ('pair-a', 'k1', 0, 1, NULL, x'01', x'02', 'SENT', 1, 'PENDING')",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO outbox (message_id, event_id, destination_uid, envelope_bytes, status, " +
                    "created_at, last_attempt_at, attempt_count, relay_checked_at, relay_rejection) " +
                    "VALUES ('m-pending', 1, 'uid-b', x'03', 'PENDING', $createdAt, NULL, 0, NULL, NULL)",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO outbox (message_id, event_id, destination_uid, envelope_bytes, status, " +
                    "created_at, last_attempt_at, attempt_count, relay_checked_at, relay_rejection) " +
                    "VALUES ('m-sent', 1, 'uid-b', x'04', 'SENT', 1, 2, 1, 7, 'RATE_LIMITED')",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 12").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
        runBlocking {
            // Pre-v13 chain rows survive with a null wire id (they predate the contract).
            val chain = db.chainEventDao().chainFor("pair-a").single()
            assertNull(chain.eventId)

            // The pre-v13 pending row is due (next_attempt_at defaulted to 0) and retention is
            // anchored to its own creation time.
            val due = db.outboxDao().pendingForDrain("pair-a", nowEpochMillis = 0, limit = 10).single()
            assertEquals("m-pending", due.messageId)
            assertArrayEquals(byteArrayOf(3), due.envelopeBytes)
            assertEquals(0, due.attemptCount)
            assertTrue(
                "the backfilled window expires at created_at + 30d",
                db.outboxDao().pendingForDrain("pair-a", createdAt + Outbox.RETENTION_MILLIS, 10).isEmpty(),
            )

            // The relay-check stamp on the pre-existing SENT row survived 12→13 untouched.
            assertEquals(1, db.outboxDao().rejectionCountSince("pair-a", sinceEpochMillis = 0))

            // §8.7 eviction records the state and clears the ciphertext — never a silent delete.
            // Both rows are past their window by now (the SENT row was created at t=1), and the
            // SENT bytes die on the same 30-day cap as the relay's own copy.
            assertEquals(2, db.outboxDao().expireDue(createdAt + Outbox.RETENTION_MILLIS))
            val expired = checkNotNull(db.outboxDao().snapshotOf("m-pending"))
            assertEquals(Outbox.STATUS_EXPIRED, expired.status)
            assertTrue(expired.envelopeBytes.isEmpty())
            assertTrue(db.outboxDao().pendingForDrain("pair-a", Long.MAX_VALUE, 10).isEmpty())

            // The Step 3 wire id is now a live column and unique per relationship + sender
            // install: the second insert of the same id is refused by the index, and the
            // first row is untouched.
            val wireId = java.util.UUID.randomUUID().toString()
            db.chainEventDao().insert(
                ChainEvent(
                    pairId = "pair-a",
                    senderKeyId = "k1",
                    eventId = wireId,
                    epoch = 0,
                    sequence = 2,
                    previousEventHash = byteArrayOf(1),
                    eventHash = byteArrayOf(2),
                    envelope = byteArrayOf(3),
                    direction = ChainEvent.DIRECTION_SENT,
                    createdAtEpochMillis = 2,
                    syncState = ChainEvent.SYNC_STATE_PENDING,
                ),
            )
            assertEquals(wireId, db.chainEventDao().getByEventId("pair-a", "k1", wireId)?.eventId)
            val duplicate = runCatching {
                db.chainEventDao().insert(
                    ChainEvent(
                        pairId = "pair-a",
                        senderKeyId = "k1",
                        eventId = wireId,
                        epoch = 0,
                        sequence = 3,
                        previousEventHash = byteArrayOf(2),
                        eventHash = byteArrayOf(4),
                        envelope = byteArrayOf(5),
                        direction = ChainEvent.DIRECTION_SENT,
                        createdAtEpochMillis = 3,
                        syncState = ChainEvent.SYNC_STATE_PENDING,
                    ),
                )
            }
            assertTrue("the unique event-id index refuses a second row", duplicate.isFailure)
            assertEquals(2, db.chainEventDao().chainFor("pair-a").size)
            // NULL ids stay distinct under the unique index (gap markers / pre-contract rows).
            assertNull(db.chainEventDao().getByEventId("pair-a", "k1", "not-a-row"))
        }
        db.close()
    }

    /**
     * D-33 rollback (v12→v13→v12): the down step restores the exact v12 shape — the v13 index
     * and columns gone — while every chain row and queued envelope survives with its bytes and
     * ids intact. A downgrade is a schema step, never a data loss.
     */
    @Test
    fun migration13to12_restoresTheV12Shape_preservingEveryRow() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V12_DDL.forEach { ddl -> conn.prepare(ddl).use { it.step() } }
            conn.prepare(
                "INSERT INTO chain_heads (pair_id, sender_key_id, last_sequence, last_event_hash, forked) " +
                    "VALUES ('pair-a', 'k1', 2, x'02', 0)",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 12").use { it.step() }
        }

        // Forward through Room's real upgrade path, then write v13-only state.
        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
        val wireId = java.util.UUID.randomUUID().toString()
        runBlocking {
            db.chainEventDao().insert(
                ChainEvent(
                    pairId = "pair-a",
                    senderKeyId = "k1",
                    eventId = wireId,
                    epoch = 0,
                    sequence = 1,
                    previousEventHash = null,
                    eventHash = byteArrayOf(1),
                    envelope = byteArrayOf(2, 3),
                    direction = ChainEvent.DIRECTION_SENT,
                    createdAtEpochMillis = 5,
                    syncState = ChainEvent.SYNC_STATE_PENDING,
                ),
            )
            val eventId = db.chainEventDao().chainFor("pair-a").single().id
            db.outboxDao().insert(
                Outbox(
                    messageId = "m1",
                    eventId = eventId,
                    destinationUid = "uid-b",
                    envelopeBytes = byteArrayOf(2, 3),
                    status = Outbox.STATUS_PENDING,
                    createdAtEpochMillis = 5,
                ),
            )
            db.outboxDao().recordAttempt("m1", 6, 66, "TRANSPORT")
        }
        db.close()

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            runBlocking { AccountabilityDatabase.MIGRATION_13_12.migrate(conn) }

            val eventColumns = columnNames(conn, "events")
            assertFalse("v13's wire id is gone", "event_id" in eventColumns)
            assertTrue("every v12 column survives", "sync_state" in eventColumns)
            val outboxColumns = columnNames(conn, "outbox")
            assertFalse("next_attempt_at" in outboxColumns)
            assertFalse("expires_at" in outboxColumns)
            assertFalse("last_error_code" in outboxColumns)

            conn.prepare("SELECT COUNT(*) FROM events").use { s ->
                s.step()
                assertEquals("the chain row survives", 1, s.getLong(0))
            }
            conn.prepare(
                "SELECT message_id, event_id, envelope_bytes, status, attempt_count FROM outbox",
            ).use { s ->
                s.step()
                assertEquals("m1", s.getText(0))
                assertEquals(1L, s.getLong(1))
                assertArrayEquals(byteArrayOf(2, 3), s.getBlob(2))
                assertEquals(Outbox.STATUS_PENDING, s.getText(3))
                assertEquals("the v12 counter survives, v13 retry state does not", 1L, s.getLong(4))
            }
        }
    }

    /**
     * Partner-accountability Step 10 (v13→v14): the unpair journal gains the server-revocation
     * acknowledgement. An upgraded device's live row keeps every field and reads back with a
     * null `revoked_at` — the honest "pending" that keeps the retry alive and self-heals on the
     * next sync pass — and the acknowledgement is write-once.
     */
    @Test
    fun migration13to14_addsTheServerRevocationAck_preservingTheJournalRow() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V12_DDL.forEach { ddl -> conn.prepare(ddl).use { it.step() } }
            conn.prepare(
                "INSERT INTO unpair_state (pair_id, role, partner_uid, message_id, sender_key_id, " +
                    "key_epoch, request_envelope, delivered, ack_sent, local_shredded, requested_at, " +
                    "confirmed_at) VALUES ('pair-a', 'REQUESTER', 'uid-b', 'm-1', 'k1', 2, x'03', 1, 0, 1, 10, NULL)",
            ).use { it.step() }
            // Build an exact v13 file: the real 12→13 step, stamped as the schema version.
            runBlocking { AccountabilityDatabase.MIGRATION_12_13.migrate(conn) }
            conn.prepare("PRAGMA user_version = 13").use { it.step() }
        }

        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
        runBlocking {
            val row = checkNotNull(db.unpairStateDao().get("pair-a"))
            assertEquals("uid-b", row.partnerUid)
            assertEquals(2, row.keyEpoch)
            assertArrayEquals(byteArrayOf(3), row.requestEnvelope)
            assertTrue(row.delivered && row.localShredded && !row.ackSent)
            assertEquals(10L, row.requestedAtEpochMillis)
            assertNull("an upgraded row has not claimed a server exit it never observed", row.revokedAtEpochMillis)
            assertEquals(UnpairPhase.AWAITING_REVOCATION, row.phaseAt(11))
            assertFalse("no dismissal while the exit acknowledgement is owed", row.isDismissible(11))

            // The acknowledgement is live and write-once: the first ack stamps the moment; a
            // later pass cannot overwrite it.
            db.unpairStateDao().markRevoked("pair-a", 20)
            db.unpairStateDao().markRevoked("pair-a", 99)
            val revoked = checkNotNull(db.unpairStateDao().get("pair-a"))
            assertEquals(20L, revoked.revokedAtEpochMillis)
            assertEquals("the server exit is settled; the partner's own shred is not", UnpairPhase.AWAITING_CONFIRMATION, revoked.phaseAt(21))
            db.unpairStateDao().markConfirmed("pair-a", 22)
            assertEquals(UnpairPhase.TERMINAL, db.unpairStateDao().get("pair-a")!!.phaseAt(23))
        }
        db.close()
    }

    /**
     * D-33 rollback (v14→v13): the down step restores the exact v13 `unpair_state` shape — the
     * acknowledgement column gone — while every journal field and row survives. A downgrade is
     * a schema step, never a data loss; the exit simply reads as pending again on the older
     * build.
     */
    @Test
    fun migration14to13_restoresTheV13Shape_preservingTheJournalRow() {
        val dbPath = context.getDatabasePath(AccountabilityDatabase.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V12_DDL.forEach { ddl -> conn.prepare(ddl).use { it.step() } }
            conn.prepare("PRAGMA user_version = 12").use { it.step() }
        }

        // Forward through Room's real path (12→13→14), then write v14-only state.
        val db = Room.databaseBuilder(
            context,
            AccountabilityDatabase::class.java,
            AccountabilityDatabase.DB_NAME,
        ).addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS).build()
        runBlocking {
            db.unpairStateDao().upsert(
                UnpairState(
                    pairId = "pair-a",
                    role = UnpairState.ROLE_REQUESTER,
                    partnerUid = "uid-b",
                    messageId = "m-1",
                    senderKeyId = "k1",
                    keyEpoch = 2,
                    requestEnvelope = byteArrayOf(3, 4),
                    delivered = true,
                    ackSent = false,
                    localShredded = true,
                    requestedAtEpochMillis = 10,
                    confirmedAtEpochMillis = 12,
                    revokedAtEpochMillis = 11,
                ),
            )
        }
        db.close()

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            runBlocking { AccountabilityDatabase.MIGRATION_14_13.migrate(conn) }

            assertFalse("v14's acknowledgement is gone", "revoked_at" in columnNames(conn, "unpair_state"))
            conn.prepare(
                "SELECT partner_uid, message_id, sender_key_id, request_envelope, delivered, " +
                    "confirmed_at FROM unpair_state",
            ).use { s ->
                assertTrue("the journal row survives", s.step())
                assertEquals("uid-b", s.getText(0))
                assertEquals("m-1", s.getText(1))
                assertEquals("k1", s.getText(2))
                assertArrayEquals(byteArrayOf(3, 4), s.getBlob(3))
                assertEquals(1L, s.getLong(4))
                assertEquals(12L, s.getLong(5))
            }
        }
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

        /** v12 baseline: v11 + MIGRATION_11_12's per-pair agreement alias (ADR #106); every
         *  other table is unchanged in 11→12. */
        private val V12_DDL = V11_DDL.filterNot { it.contains("enrollments") } + listOf(
            "CREATE TABLE IF NOT EXISTS enrollments (" +
                "pair_id TEXT NOT NULL PRIMARY KEY, " +
                "my_sender_key_id TEXT NOT NULL, " +
                "partner_signing_key_id TEXT NOT NULL, " +
                "partner_signing_pubkey BLOB NOT NULL, " +
                "partner_agreement_pubkey BLOB NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "mailbox_watermark_ts INTEGER, " +
                "pairing_epoch INTEGER NOT NULL DEFAULT 0, " +
                "partner_label TEXT, " +
                "my_agreement_key_alias TEXT)",
        )
    }
}
