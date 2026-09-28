package com.example.myexodus.accountability

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.crypto.IdentityEpochStore
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.SessionKeyManager
import com.example.myexodus.accountability.state.UnpairPhase
import com.example.myexodus.accountability.state.UnpairState
import com.example.myexodus.accountability.sync.MailboxOutboxDrainer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §10/§12/AD-13a/b: the unpair protocol's ordering and honesty properties. The Keystore,
 * mailbox, and ack endpoints are constructor-seam fakes; the journal and chain state are the
 * real Room database. §12's two mandated end states are pinned here (partner-reachable ->
 * confirmed TERMINAL; partner-unreachable through the 7-day timeout -> LOCAL_ONLY_TERMINATED,
 * never conflated), alongside the AD-13b crash invariants: the journal row outlives the wipe,
 * and a row whose shred never finished is re-shredded, never dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BilateralDeletionTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var store: ChainStateStore
    private lateinit var creator: EventCreator
    private lateinit var deletion: BilateralDeletion

    private val signer = AccountabilitySigner()
    private val cipher = EventCipher()
    private val cekDerivation = CekDerivation()
    private val myAgreement = ecKeyPair()
    private val mySigning = ecKeyPair()
    private val partnerAgreement = ecKeyPair()
    private val partnerSigning = ecKeyPair()
    private val mySenderKeyId = sha256Hex(mySigning.public.encoded)
    private val pairId = "pair-deletion-test"
    private val pairAlias = AccountabilityKeyStore.pairAgreementAliasOf("session-main")
    private val otherPairId = "pair-deletion-test-2"
    private val otherAlias = AccountabilityKeyStore.pairAgreementAliasOf("session-other")

    private var clock = 1_700_000_000_000L

    /** True once the shred asked for the device-level key (only legal with no enrollment left). */
    private var destroyedDeviceKey = false
    private val destroyedPairAliases = mutableListOf<String>()
    private val terminated = mutableListOf<String>()
    private val ops = mutableListOf<String>()

    /** Simulates the 2026-09-28 crypto-dead pair: EventCreator cannot derive a CEK. */
    private var agreementKeyMissing = false

    /** drainOutbox fake: queued outcomes, optionally marking the pair's real outbox rows SENT
     *  (what the production drainer does) so the journal's delivered bookkeeping is exercised. */
    private val drainQueue = ArrayDeque<MailboxOutboxDrainer.DrainResult>()
    private val drained = mutableListOf<Triple<String, String, String>>()
    private var markOnDrain = false

    /** deliverRequest fake: records the journaled document fields and reports success. */
    private var deliverResult = true
    private val deliveredRequests = mutableListOf<Triple<String, String, Int>>()

    private var partnerAcked = false
    private var ackWorks = true
    private val acked = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName)
            .addMigrations(AccountabilityDatabase.MIGRATION_1_2)
            .build()
        store = ChainStateStore(db)
        val epochStore = IdentityEpochStore(db.identityEpochDao())
        creator = EventCreator(
            store = store,
            enrollmentDao = db.enrollmentDao(),
            cekDerivation = cekDerivation,
            eventCipher = cipher,
            signer = signer,
            epochStore = epochStore,
            agreementPrivateKey = {
                if (agreementKeyMissing) error("agreement key missing")
                myAgreement.private
            },
            sessionKeyManager = SessionKeyManager(store),
        )
        deletion = BilateralDeletion(
            creator = creator,
            drainOutbox = { pair, authorUid, partnerUid ->
                drained += Triple(pair, authorUid, partnerUid)
                val outcome = drainQueue.removeFirstOrNull()
                    ?: MailboxOutboxDrainer.DrainResult.Sent(0)
                if (markOnDrain) {
                    for (row in db.outboxDao().pendingFor(pair, 100)) {
                        db.outboxDao().markSent(row.messageId)
                    }
                }
                outcome
            },
            enrollmentDao = db.enrollmentDao(),
            store = store,
            journal = db.unpairStateDao(),
            destroyPairAgreementKey = { alias ->
                destroyedPairAliases += alias
                ops += "destroy"
            },
            destroyDeviceAgreementKey = {
                destroyedDeviceKey = true
                ops += "destroy-device"
            },
            terminatePair = { terminated += it },
            deliverRequest = { partnerUid, row ->
                deliveredRequests += Triple(partnerUid, row.messageId, row.requestEnvelope.size)
                deliverResult
            },
            pollPartnerAck = { _, _ -> partnerAcked },
            ackReceived = { messageId ->
                acked += messageId
                ackWorks
            },
            now = { clock },
        )
        runBlocking {
            db.enrollmentDao().upsert(
                EnrollmentRecord(
                    pairId = pairId,
                    mySenderKeyId = mySenderKeyId,
                    partnerSigningKeyId = sha256Hex(partnerSigning.public.encoded),
                    partnerSigningPubKey = partnerSigning.public.encoded,
                    partnerAgreementPubKey = partnerAgreement.public.encoded,
                    createdAtEpochMillis = 1_700_000_000_000L,
                    myAgreementKeyAlias = pairAlias,
                ),
            )
            // Seed one ordinary event so the wipe has chain data to remove.
            creator.create(pairId, EventPayload(type = "activity", text = "seeded"), signing())
            // Seed an approval row too: the shred must take it (ADR #48 Consequences → Step 14,
            // local shred completeness) — a stale REQUESTED row must not outlive the pair.
            db.approvalRequestDao().insertIgnore(
                ApprovalRequest(
                    requestEventId = "k:1",
                    pairId = pairId,
                    role = ApprovalRequest.ROLE_REQUESTER,
                    status = ApprovalRequest.STATUS_REQUESTED,
                    pairingEpoch = 0,
                    message = "please",
                    createdAtEpochMillis = 1_700_000_000_000L,
                    expiresAtEpochMillis = 1_700_000_000_000L + ApprovalRequest.TTL_MILLIS,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    /** A second live pair on the same device: its own agreement-key alias, its own chain event. */
    private fun seedSecondPair() = runBlocking {
        db.enrollmentDao().upsert(
            EnrollmentRecord(
                pairId = otherPairId,
                mySenderKeyId = mySenderKeyId,
                partnerSigningKeyId = sha256Hex(partnerSigning.public.encoded),
                partnerSigningPubKey = partnerSigning.public.encoded,
                partnerAgreementPubKey = partnerAgreement.public.encoded,
                createdAtEpochMillis = 1_700_000_000_000L,
                myAgreementKeyAlias = otherAlias,
            ),
        )
        creator.create(otherPairId, EventPayload(type = "activity", text = "sibling"), signing())
    }

    // --- Initiator: §12 partner-reachable vs partner-unreachable ---

    @Test
    fun partnerReachable_confirmsOnAck_terminalPhase() = runTest {
        markOnDrain = true
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(1)
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(0)

        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))

        // Local shred ran unconditionally; the request is out; the wait is honest.
        assertTrue("last enrollment gone — device key destroyed", destroyedDeviceKey)
        assertEquals("only this pair's key shredded", listOf(pairAlias), destroyedPairAliases)
        assertNull(db.enrollmentDao().getByPairId(pairId))
        assertTrue(store.chainFor(pairId).isEmpty())
        assertNull("approval rows shredded with the pair", db.approvalRequestDao().byId("k:1"))
        val paired = db.unpairStateDao().get(pairId)!!
        assertEquals(UnpairState.ROLE_REQUESTER, paired.role)
        assertTrue("delivered bookkeeping", paired.delivered)
        assertTrue("shred recorded", paired.localShredded)
        assertNull("no confirmation yet — no erasure claim", paired.confirmedAtEpochMillis)
        assertEquals(UnpairPhase.AWAITING_CONFIRMATION, paired.phaseAt(clock))
        assertEquals(listOf(pairId), terminated) // credential revoked once the request is out

        // The partner's device processes it and acks: the next pass confirms — TERMINAL.
        partnerAcked = true
        deletion.resumePending()
        val confirmed = db.unpairStateDao().get(pairId)!!
        assertEquals(clock, confirmed.confirmedAtEpochMillis)
        assertEquals(UnpairPhase.TERMINAL, confirmed.phaseAt(clock))
    }

    @Test
    fun partnerUnreachable_sevenDayTimeout_localOnlyTerminated_neverTerminal() = runTest {
        markOnDrain = true
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(1)
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(0)
        deletion.initiate(pairId, "uid-a", "uid-b", signing())

        // Well before the window: still waiting, not claiming anything about the partner.
        clock += UnpairState.CONFIRM_TIMEOUT_MILLIS - 1
        deletion.resumePending()
        assertEquals(UnpairPhase.AWAITING_CONFIRMATION, db.unpairStateDao().get(pairId)!!.phaseAt(clock))

        // Time crosses the 7-day window (accelerated): LOCAL_ONLY_TERMINATED — a state the UI
        // must report distinctly from TERMINAL — and no confirmation timestamp exists.
        clock += 1
        deletion.resumePending()
        val row = db.unpairStateDao().get(pairId)!!
        assertNull(row.confirmedAtEpochMillis)
        assertEquals(UnpairPhase.LOCAL_ONLY_TERMINATED, row.phaseAt(clock))
    }

    @Test
    fun lateAckAfterTimeout_upgradesToTerminal() = runTest {
        markOnDrain = true
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(1)
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(0)
        deletion.initiate(pairId, "uid-a", "uid-b", signing())

        clock += UnpairState.CONFIRM_TIMEOUT_MILLIS + 1
        deletion.resumePending()
        assertEquals(UnpairPhase.LOCAL_ONLY_TERMINATED, db.unpairStateDao().get(pairId)!!.phaseAt(clock))

        // Newer evidence is strictly better evidence: a late ack upgrades the report — it is
        // the partner's own device confirming, never a delivery-shaped inference.
        partnerAcked = true
        deletion.resumePending()
        assertEquals(UnpairPhase.TERMINAL, db.unpairStateDao().get(pairId)!!.phaseAt(clock))
    }

    // --- §19.5 / ADR #106: pair-scoped key destruction ---

    @Test
    fun unpairOfOnePair_destroysOnlyItsKey_leavesSiblingPairIntact() = runTest {
        seedSecondPair()
        markOnDrain = true
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(1)
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(0)

        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))

        // The unpair's blast radius is exactly the one pair (the 2026-09-28 defect).
        assertEquals(listOf(pairAlias), destroyedPairAliases)
        assertFalse("sibling still enrolled — the device key must live", destroyedDeviceKey)
        assertNull(db.enrollmentDao().getByPairId(pairId))
        assertTrue(store.chainFor(pairId).isEmpty())
        val sibling = db.enrollmentDao().getByPairId(otherPairId)
        assertNotNull("sibling enrollment intact", sibling)
        assertEquals(otherAlias, sibling!!.myAgreementKeyAlias)
        assertTrue("sibling chain untouched", store.chainFor(otherPairId).isNotEmpty())
    }

    @Test
    fun deviceLevelKeyDiesOnlyWithTheLastEnrollment() = runTest {
        seedSecondPair()
        markOnDrain = true
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(1)
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(0)
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(1)
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(0)

        deletion.initiate(pairId, "uid-a", "uid-b", signing())
        assertFalse("one pair left — the pre-v12 shared key must survive", destroyedDeviceKey)

        deletion.initiate(otherPairId, "uid-a", "uid-c", signing())
        assertTrue("last enrollment gone — the device key dies with it (AD-22 canary)", destroyedDeviceKey)
        assertTrue(db.enrollmentDao().all().isEmpty())
    }

    @Test
    fun cryptoDeadPair_stillErasesLocally_neverClaimingThePartner() = runTest {
        // The 2026-09-28 incident shape: the key is already gone, so the request event cannot
        // be created. The tap must still erase — and the empty envelope keeps the report honest
        // (nothing to deliver, so the 7-day timeout, never a confirmation claim).
        agreementKeyMissing = true
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))

        assertEquals("pair key shredded", listOf(pairAlias), destroyedPairAliases)
        assertNull(db.enrollmentDao().getByPairId(pairId))
        assertTrue(store.chainFor(pairId).isEmpty())
        assertNull("approval rows shredded with the pair", db.approvalRequestDao().byId("k:1"))
        val row = db.unpairStateDao().get(pairId)!!
        assertTrue(row.localShredded)
        assertTrue("no request bytes — nothing to deliver", row.requestEnvelope.isEmpty())
        assertNull(row.confirmedAtEpochMillis)
        assertEquals(UnpairPhase.AWAITING_CONFIRMATION, row.phaseAt(clock))
    }

    @Test
    fun unresolvablePartner_localShredRuns_neverClaimingThePartner() = runTest {
        // ADR #114: both uids null (the pair document is gone, or this account is not a
        // member) — no request can exist, so no delivery and no ack are ever attempted, but
        // the tap must still erase: §9's "partner unreachable" row is a local-first unpair.
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, null, null, signing()))

        assertEquals("pair key shredded", listOf(pairAlias), destroyedPairAliases)
        assertNull(db.enrollmentDao().getByPairId(pairId))
        assertTrue(store.chainFor(pairId).isEmpty())
        assertNull("approval rows shredded with the pair", db.approvalRequestDao().byId("k:1"))
        assertTrue("no drain attempted without a delivery target", drained.isEmpty())
        val row = db.unpairStateDao().get(pairId)!!
        assertEquals(UnpairState.ROLE_REQUESTER, row.role)
        assertNull(row.partnerUid)
        assertTrue("no request exists — nothing to deliver", row.messageId.isEmpty())
        assertTrue(row.requestEnvelope.isEmpty())
        assertTrue(row.localShredded)

        // A re-tap continues the same attempt — the row (the only report of the remote leg)
        // survives, and the 7-day window is never extended.
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, null, null, signing()))
        val reEntered = db.unpairStateDao().get(pairId)!!
        assertEquals(row.requestedAtEpochMillis, reEntered.requestedAtEpochMillis)
        assertEquals("", reEntered.messageId)

        // Nothing is in flight: no delivery, no ack — and no revocation before the window
        // lapses (a live grid must not be terminated while the wait is still honest).
        deletion.resumePending()
        assertTrue(deliveredRequests.isEmpty())
        assertTrue(terminated.isEmpty())
        assertEquals(UnpairPhase.AWAITING_CONFIRMATION, db.unpairStateDao().get(pairId)!!.phaseAt(clock))

        // The window lapses: LOCAL_ONLY_TERMINATED, and the revocation attempt rides along
        // (a denied terminate for a non-member is the documented no-op).
        clock += UnpairState.CONFIRM_TIMEOUT_MILLIS
        deletion.resumePending()
        val ended = db.unpairStateDao().get(pairId)!!
        assertNull(ended.confirmedAtEpochMillis)
        assertEquals(UnpairPhase.LOCAL_ONLY_TERMINATED, ended.phaseAt(clock))
        assertEquals(listOf(pairId), terminated)
    }

    // --- AD-13b crash invariants ---

    @Test
    fun crashedBeforeEventCreation_resumeStillShreds() = runTest {
        // The journal row made it; the process died before the request event existed (empty
        // envelope). The next pass must still erase — the row is what makes the shred
        // unskippable — and must not terminate before the request is out.
        db.unpairStateDao().upsert(
            UnpairState(
                pairId = pairId,
                role = UnpairState.ROLE_REQUESTER,
                partnerUid = "uid-b",
                messageId = "pinned-message-id",
                delivered = false,
                ackSent = false,
                requestedAtEpochMillis = clock,
            ),
        )
        deletion.resumePending()

        assertTrue("shred completed from the journal row alone", destroyedDeviceKey)
        assertEquals("shred targeted the pair's key", listOf(pairAlias), destroyedPairAliases)
        assertNull(db.enrollmentDao().getByPairId(pairId))
        val row = db.unpairStateDao().get(pairId)!!
        assertTrue(row.localShredded)
        assertNull(row.confirmedAtEpochMillis)
        assertEquals(UnpairPhase.AWAITING_CONFIRMATION, row.phaseAt(clock))
    }

    @Test
    fun rePairedPair_dropsShreddedRow_butKeepsUnshreddedOne() = runTest {
        // Shredded row + live enrollment = a superseded unpair (re-pair reuses the pairId).
        db.unpairStateDao().upsert(
            UnpairState(
                pairId = pairId,
                role = UnpairState.ROLE_REQUESTER,
                partnerUid = "uid-b",
                messageId = "m-old",
                delivered = true,
                ackSent = false,
                localShredded = true,
                requestedAtEpochMillis = clock,
            ),
        )
        deletion.resumePending()
        assertNull("superseded row dropped", db.unpairStateDao().get(pairId))
        assertFalse("live pair never re-shredded", destroyedDeviceKey)
        assertTrue("no key touched at all", destroyedPairAliases.isEmpty())
        assertTrue("enrollment intact", db.enrollmentDao().getByPairId(pairId) != null)
    }

    @Test
    fun rePairedPair_tapActsOnTheLivePair_notTheShreddedRow() = runTest {
        // Same superseded shape as above, but reached through the tap: a stale shredded row
        // (old envelope, delivered=true) with a LIVE enrollment for the reused pairId (AD-07).
        // Without the guard, initiate() would see the envelope and "resume" the old request —
        // terminating the pair's server state while the live pair stays enrolled, unshredded.
        db.unpairStateDao().upsert(
            UnpairState(
                pairId = pairId,
                role = UnpairState.ROLE_REQUESTER,
                partnerUid = "uid-old",
                messageId = "m-old",
                delivered = true,
                ackSent = false,
                localShredded = true,
                requestedAtEpochMillis = clock,
                requestEnvelope = byteArrayOf(1, 2, 3),
            ),
        )
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))

        val fresh = db.unpairStateDao().get(pairId)!!
        assertEquals("a NEW request, not the old row's", "uid-b", fresh.partnerUid)
        assertTrue("old message id not resumed", fresh.messageId != "m-old" && fresh.messageId.isNotEmpty())
        assertEquals("shred targeted the live pair's key", listOf(pairAlias), destroyedPairAliases)
        assertNull("live pair erased, not just the old row dropped", db.enrollmentDao().getByPairId(pairId))
    }

    @Test
    fun failedCreation_leavesNothingBehind() = runTest {
        // No enrollment: the request cannot be created, so nothing may be journaled or
        // shredded — the tap fails cleanly and the (absent) pair state is untouched.
        db.enrollmentDao().getByPairId(pairId)?.let { db.enrollmentDao().delete(it) }
        val result = deletion.initiate(pairId, "uid-a", "uid-b", signing())

        assertTrue(result is BilateralDeletion.DeletionResult.Failed)
        assertNull(db.unpairStateDao().get(pairId))
        assertFalse(destroyedDeviceKey)
        assertTrue(destroyedPairAliases.isEmpty())
    }

    @Test
    fun reEnteringInitiate_continuesWithoutSecondRequest() = runTest {
        markOnDrain = true
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(1)
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(0)
        deletion.initiate(pairId, "uid-a", "uid-b", signing())
        val afterFirst = db.unpairStateDao().get(pairId)!!
        val drainsAfterFirst = drained.size

        // Re-tap (e.g. restored screen): resumes the same attempt — same message id, same
        // timeline, no second request event, no second drain pass.
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))
        val afterSecond = db.unpairStateDao().get(pairId)!!
        assertEquals(afterFirst.messageId, afterSecond.messageId)
        assertEquals(afterFirst.requestedAtEpochMillis, afterSecond.requestedAtEpochMillis)
        assertEquals(drainsAfterFirst, drained.size)
        // A second request would have needed a new outbox row; the wiped pair has none.
        assertTrue(store.chainFor(pairId).isEmpty())
        assertTrue(db.outboxDao().pendingFor(pairId, 100).isEmpty())
    }

    // --- Draining and delivery ---

    @Test
    fun initiate_drainsEveryPendingBatch_beforeShredding() = runTest {
        // Older attempts queued behind the deletion event must all reach the mailbox before
        // the key dies — otherwise they would be stranded forever.
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(2)
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(1)
        drainQueue += MailboxOutboxDrainer.DrainResult.Sent(0)
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))

        assertTrue(destroyedDeviceKey)
        assertEquals(3, drained.size)
    }

    @Test
    fun undeliveredRequest_journalBytesSurviveForLaterRedelivery() = runTest {
        // The drain fails (offline): the shred still runs (AD-13b), and the journal must carry
        // everything a later redelivery needs — bytes, sender key id, key epoch.
        drainQueue += MailboxOutboxDrainer.DrainResult.Failed("offline")
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))

        val row = db.unpairStateDao().get(pairId)!!
        assertTrue(row.requestEnvelope.isNotEmpty())
        assertTrue(row.senderKeyId == mySenderKeyId)
        assertTrue(row.localShredded)

        // Next pass delivers from the journal alone (no chain state, no enrollment left).
        deletion.resumePending()
        assertEquals(listOf(Triple("uid-b", row.messageId, row.requestEnvelope.size)), deliveredRequests)
        assertTrue(db.unpairStateDao().get(pairId)!!.delivered)
    }

    // --- Responder ---

    @Test
    fun responder_shredsThenAcks_thenDismisses() = runTest {
        assertTrue(deletion.handleIncoming(pairId, "msg-1"))

        assertTrue(destroyedDeviceKey)
        assertNull(db.enrollmentDao().getByPairId(pairId))
        assertTrue(store.chainFor(pairId).isEmpty())
        assertNull("approval rows shredded with the pair", db.approvalRequestDao().byId("k:1"))
        val row = db.unpairStateDao().get(pairId)!!
        assertEquals(UnpairState.ROLE_RESPONDER, row.role)
        assertTrue(row.localShredded)
        assertTrue(row.ackSent)
        assertTrue(acked.contains("msg-1"))
        assertEquals(listOf(pairId), terminated)
        assertEquals(UnpairPhase.TERMINAL, row.phaseAt(clock))

        deletion.dismiss(pairId)
        assertNull(db.unpairStateDao().get(pairId))
    }

    @Test
    fun responder_owedAckBlocksDismissal_untilItGoesOut() = runTest {
        ackWorks = false
        assertTrue(deletion.handleIncoming(pairId, "msg-2"))
        assertFalse("shred happened even though the ack is owed", ackWorks)
        assertTrue(db.unpairStateDao().get(pairId)!!.localShredded)

        // Dismissing now would strand the initiator's confirmation — refused.
        deletion.dismiss(pairId)
        assertTrue(db.unpairStateDao().get(pairId) != null)

        ackWorks = true
        deletion.resumePending()
        assertTrue(db.unpairStateDao().get(pairId)!!.ackSent)
        deletion.dismiss(pairId)
        assertNull(db.unpairStateDao().get(pairId))
    }

    private fun signing(): Signature = Signature.getInstance("SHA256withECDSA").apply {
        initSign(mySigning.private)
    }

    companion object {
        private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
