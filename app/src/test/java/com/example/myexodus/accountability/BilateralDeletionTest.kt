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
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.UnsignedEnvelope
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.SessionKeyManager
import com.example.myexodus.accountability.state.UnpairPhase
import com.example.myexodus.accountability.state.UnpairState
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
    private val prunedPairs = mutableListOf<String>()
    private val ops = mutableListOf<String>()

    /** Simulates the 2026-09-28 crypto-dead pair: EventCreator cannot derive a CEK. */
    private var agreementKeyMissing = false

    /** terminatePair fake: records every attempt and reports the server acknowledgement. */
    private var terminateResult = true
    private var terminateThrows = false

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
            terminatePair = {
                terminated += it
                if (terminateThrows) error("attestation unavailable")
                terminateResult
            },
            deliverRequest = { partnerUid, row ->
                deliveredRequests += Triple(partnerUid, row.messageId, row.requestEnvelope.size)
                deliverResult
            },
            pollPartnerAck = { _, _ -> partnerAcked },
            ackReceived = { messageId ->
                acked += messageId
                ackWorks
            },
            prunePartnerActivity = { prunedPairs += it },
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
    fun shredPrunesThePartnerActivityRowsForThePair() = runTest {
        // AD-07: the chain wipe leaves the rules-store partner rows, and a re-pair reuses the
        // pairId — so the shred itself must take them, not wait for an orphan sweep that will
        // never fire once the pair is enrolled again (owner report 2026-11-10).
        deletion.initiate(pairId, "uid-a", "uid-b", signing())
        assertEquals(listOf(pairId), prunedPairs)
    }

    @Test
    fun partnerReachable_confirmsOnAck_terminalPhase() = runTest {
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))

        // Local shred ran unconditionally; only the deletion request left the device.
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
        assertEquals("server-side exit acknowledged", clock, paired.revokedAtEpochMillis)
        assertEquals(UnpairPhase.AWAITING_CONFIRMATION, paired.phaseAt(clock))
        assertEquals(listOf(pairId), terminated) // credential revoked once the request is out
        // Step 10: the seeded ordinary event was cancelled with the relationship — the only
        // mailbox write the unpair makes is the deletion request itself, from the journal.
        assertEquals(1, deliveredRequests.size)
        assertEquals("uid-b", deliveredRequests.single().first)

        // The partner's device processes it and acks: the next pass confirms — TERMINAL.
        partnerAcked = true
        deletion.resumePending()
        val confirmed = db.unpairStateDao().get(pairId)!!
        assertEquals(clock, confirmed.confirmedAtEpochMillis)
        assertEquals(UnpairPhase.TERMINAL, confirmed.phaseAt(clock))
    }

    @Test
    fun partnerUnreachable_sevenDayTimeout_localOnlyTerminated_neverTerminal() = runTest {
        deliverResult = false // offline: nothing leaves this device
        deletion.initiate(pairId, "uid-a", "uid-b", signing())

        // Well before the window: the local exit is already in force, the server flip is
        // honestly reported as pending, and nothing claims anything about the partner.
        clock += UnpairState.CONFIRM_TIMEOUT_MILLIS - 1
        deletion.resumePending()
        val waiting = db.unpairStateDao().get(pairId)!!
        assertNull(waiting.revokedAtEpochMillis)
        assertTrue("no revocation attempt before delivery or the timeout", terminated.isEmpty())
        assertEquals(UnpairPhase.AWAITING_REVOCATION, waiting.phaseAt(clock))
        assertFalse("an unacknowledged revocation can never be dismissed", waiting.isDismissible(clock))

        // Time crosses the 7-day window (accelerated): the exit is forced even for an
        // undelivered request, and the state is LOCAL_ONLY_TERMINATED — reported distinctly
        // from TERMINAL — with no confirmation timestamp.
        clock += 1
        deletion.resumePending()
        val row = db.unpairStateDao().get(pairId)!!
        assertNull(row.confirmedAtEpochMillis)
        assertEquals("the exit is attempted at the timeout", clock, row.revokedAtEpochMillis)
        assertEquals(listOf(pairId), terminated)
        assertEquals(UnpairPhase.LOCAL_ONLY_TERMINATED, row.phaseAt(clock))
        assertTrue("the row is now dismissible (the retry has a bound)", row.isDismissible(clock))
    }

    @Test
    fun lateAckAfterTimeout_upgradesToTerminal() = runTest {
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
        assertNull("with nothing delivered, no server exit is claimed", row.revokedAtEpochMillis)
        assertTrue(deliveredRequests.isEmpty())
        assertTrue(terminated.isEmpty())
        assertEquals(UnpairPhase.AWAITING_REVOCATION, row.phaseAt(clock))
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
        assertTrue("no delivery attempted without a delivery target", deliveredRequests.isEmpty())
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
        // lapses (a live pair must not be terminated while the wait is still honest).
        deletion.resumePending()
        assertTrue(deliveredRequests.isEmpty())
        assertTrue(terminated.isEmpty())
        assertEquals(UnpairPhase.AWAITING_REVOCATION, db.unpairStateDao().get(pairId)!!.phaseAt(clock))

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
        assertNull("the server exit is still owed", row.revokedAtEpochMillis)
        assertTrue(terminated.isEmpty())
        assertEquals(UnpairPhase.AWAITING_REVOCATION, row.phaseAt(clock))
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
        deletion.initiate(pairId, "uid-a", "uid-b", signing())
        val afterFirst = db.unpairStateDao().get(pairId)!!
        var deliveredAfterFirst = deliveredRequests.size

        // Re-tap (e.g. restored screen): resumes the same attempt — same message id, same
        // timeline, no second request event, no second delivery.
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))
        val afterSecond = db.unpairStateDao().get(pairId)!!
        assertEquals(afterFirst.messageId, afterSecond.messageId)
        assertEquals(afterFirst.requestedAtEpochMillis, afterSecond.requestedAtEpochMillis)
        assertEquals(1, deliveredAfterFirst)
        assertEquals(deliveredAfterFirst, deliveredRequests.size)
        assertEquals(listOf(pairId), terminated)
        // A second request would have needed a new outbox row; the wiped pair has none.
        assertTrue(store.chainFor(pairId).isEmpty())
        assertTrue(db.outboxDao().pendingFor(pairId, 100).isEmpty())
    }

    // --- Step 10 §8.4: revocation cancels queued envelopes; only the request is delivered ---

    @Test
    fun revocation_cancelsTheQueuedEnvelopes_theRequestIsTheOnlyMailboxWrite() = runTest {
        // A second ordinary event is queued behind the seeded one — neither may be rerouted,
        // flushed, or delivered after the relationship ends.
        creator.create(pairId, EventPayload(type = "activity", text = "second"), signing())
        val queued = db.outboxDao().pendingFor(pairId, 100)
        assertEquals(2, queued.size)
        val queuedIds = queued.map { it.messageId }

        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))

        assertTrue(destroyedDeviceKey)
        assertEquals("exactly the request was delivered", 1, deliveredRequests.size)
        assertTrue("no queued event was delivered", queuedIds.none { it == deliveredRequests.single().second })
        // Every queued row is gone with the wipe, and the gate that a racing drain consults is
        // closed for each of them.
        assertTrue(db.outboxDao().pendingFor(pairId, 100).isEmpty())
        for (id in queuedIds) {
            assertFalse("upload gate closed for $id", store.stillAuthorized(pairId, id))
        }
    }

    @Test
    fun futureEvents_afterRevocation_areRefusedAtCreationAndAtTheStore() = runTest {
        deletion.initiate(pairId, "uid-a", "uid-b", signing())

        // §8.2: the same check that guards creation also guards the append transaction, so a
        // race that slipped past creation still cannot write a chain/outbox row.
        val created = creator.create(pairId, EventPayload(type = "activity", text = "late"), signing())
        assertTrue(created is EventCreator.CreateResult.Failed)
        assertEquals(
            ChainStateStore.AppendResult.NotEnrolled,
            store.appendLocal(pairId, seedEnvelopeForAppend()),
        )
        assertTrue(store.chainFor(pairId).isEmpty())
        assertTrue(db.outboxDao().pendingFor(pairId, 100).isEmpty())
        assertEquals("no extra delivery", 1, deliveredRequests.size)
    }

    // --- Delivery from the journal ---

    @Test
    fun undeliveredRequest_journalBytesSurviveForLaterRedelivery() = runTest {
        // The write fails (offline): the shred still runs (AD-13b), and the journal must carry
        // everything a later redelivery needs — bytes, sender key id, key epoch.
        deliverResult = false
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))

        val row = db.unpairStateDao().get(pairId)!!
        assertTrue(row.requestEnvelope.isNotEmpty())
        assertTrue(row.senderKeyId == mySenderKeyId)
        assertTrue(row.localShredded)
        assertFalse(row.delivered)
        assertNull("nothing delivered, so no server exit claimed", row.revokedAtEpochMillis)

        // Next pass delivers from the journal alone (no chain state, no enrollment left). The
        // first attempt was the failed one recorded above; both carry the same journaled bytes.
        deliverResult = true
        deletion.resumePending()
        assertEquals(2, deliveredRequests.size)
        assertEquals(deliveredRequests[0], deliveredRequests[1])
        assertEquals(Triple("uid-b", row.messageId, row.requestEnvelope.size), deliveredRequests.last())
        val redelivered = db.unpairStateDao().get(pairId)!!
        assertTrue(redelivered.delivered)
        assertEquals(clock, redelivered.revokedAtEpochMillis)
    }

    @Test
    fun offlineRevocation_isRetriedEveryPass_andReportedPendingUntilAcknowledged() = runTest {
        deliverResult = false
        terminateResult = false
        deletion.initiate(pairId, "uid-a", "uid-b", signing())
        // Offline: no delivery means the exit is not even attempted yet — still pending.
        assertEquals(UnpairPhase.AWAITING_REVOCATION, db.unpairStateDao().get(pairId)!!.phaseAt(clock))
        assertFalse(db.unpairStateDao().get(pairId)!!.isDismissible(clock))

        // Back online: the request lands; the server flip is attempted but not acknowledged.
        deliverResult = true
        deletion.resumePending()
        val delivered = db.unpairStateDao().get(pairId)!!
        assertTrue(delivered.delivered)
        assertNull("a denied/ambiguous terminate is NOT an acknowledgement", delivered.revokedAtEpochMillis)
        assertEquals(UnpairPhase.AWAITING_REVOCATION, delivered.phaseAt(clock))
        assertFalse("the card must keep showing the exit as pending", delivered.isDismissible(clock))

        // Even the partner's ack cannot outrank the unacknowledged server exit in the window:
        // the relationship is still active server-side, so the card says so.
        partnerAcked = true
        deletion.resumePending()
        val confirmed = db.unpairStateDao().get(pairId)!!
        assertEquals(clock, confirmed.confirmedAtEpochMillis)
        assertNull(confirmed.revokedAtEpochMillis)

        // The next pass retries the terminate (every pass, no backoff cliff); success journals
        // the acknowledgement exactly once and the phase settles.
        terminateResult = true
        deletion.resumePending()
        val settled = db.unpairStateDao().get(pairId)!!
        assertEquals(clock, settled.revokedAtEpochMillis)
        assertEquals(UnpairPhase.TERMINAL, settled.phaseAt(clock))
        assertTrue(settled.isDismissible(clock))
        assertEquals(
            "one journaled request: a failed attempt then the acknowledgement attempt",
            2, deliveredRequests.size,
        )
        assertEquals("both attempts carry the same journaled bytes", deliveredRequests[0], deliveredRequests[1])
    }

    @Test
    fun terminateThrows_doesNotEscapeThePass_andIsRetried() = runTest {
        terminateThrows = true
        assertEquals(BilateralDeletion.DeletionResult.Deleted, deletion.initiate(pairId, "uid-a", "uid-b", signing()))
        assertNull(db.unpairStateDao().get(pairId)!!.revokedAtEpochMillis)

        terminateThrows = false
        deletion.resumePending()
        assertEquals(clock, db.unpairStateDao().get(pairId)!!.revokedAtEpochMillis)
        assertEquals(2, terminated.size) // the failed attempt + the retry
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

    // --- OPD #43: the partner's account was deleted (server wrote terminationReason) ---

    @Test
    fun accountDeleted_shredsWithoutAck_andIsDismissible() = runTest {
        assertTrue(deletion.handleAccountDeleted(pairId))

        assertTrue("last enrollment gone — device key destroyed", destroyedDeviceKey)
        assertNull(db.enrollmentDao().getByPairId(pairId))
        assertTrue(store.chainFor(pairId).isEmpty())
        val row = db.unpairStateDao().get(pairId)!!
        assertEquals(UnpairState.ROLE_RESPONDER, row.role)
        assertEquals("no mailbox message exists to ack", "", row.messageId)
        assertTrue(row.localShredded)
        assertFalse(row.ackSent)
        assertEquals(UnpairPhase.TERMINAL, row.phaseAt(clock))
        assertTrue("nothing is owed, so the card can be dismissed", row.isDismissible(clock))
        assertTrue("no ack was sent for a message that does not exist", acked.isEmpty())
        assertTrue("the pair is already terminated server-side", terminated.isEmpty())

        assertFalse("resume finds nothing left to do", deletion.resumePending())
        assertTrue(acked.isEmpty())
        assertTrue(terminated.isEmpty())

        deletion.dismiss(pairId)
        assertNull(db.unpairStateDao().get(pairId))
    }

    @Test
    fun accountDeleted_shredsOnlyThatPair() = runTest {
        seedSecondPair()

        assertTrue(deletion.handleAccountDeleted(pairId))

        assertNotNull("the sibling pair keeps working", db.enrollmentDao().getByPairId(otherPairId))
        assertFalse("the shared device key survives while a pair remains", destroyedDeviceKey)
        assertTrue(store.chainFor(otherPairId).isNotEmpty())
        assertNull(db.unpairStateDao().get(otherPairId))
    }

    // --- resumePending's change report (drives the UI repaint tick, owner UX ruling 2026-10-01) ---

    @Test
    fun resumePending_reportsTrue_onConfirmation_falseWhenSettled() = runTest {
        deletion.initiate(pairId, "uid-a", "uid-b", signing())

        // Settled row (delivered, awaiting the ack): nothing advances — keep the tick quiet
        // or every pass would repaint every screen forever.
        assertFalse("no row change on a settled wait", deletion.resumePending())

        partnerAcked = true
        assertTrue("confirmation is progress the UI must see", deletion.resumePending())
        assertEquals(UnpairPhase.TERMINAL, db.unpairStateDao().get(pairId)!!.phaseAt(clock))
        assertFalse("settled again after the confirmation", deletion.resumePending())
    }

    @Test
    fun resumePending_reportsTrue_whenOwedAckGoesOut() = runTest {
        ackWorks = false
        assertTrue(deletion.handleIncoming(pairId, "msg-ack"))
        assertFalse("ack still owed and failing — no progress", deletion.resumePending())

        ackWorks = true
        assertTrue("a written ack is progress the UI must see", deletion.resumePending())
        assertTrue(db.unpairStateDao().get(pairId)!!.ackSent)
        assertFalse("settled again after the ack", deletion.resumePending())
    }

    private fun signing(): Signature = Signature.getInstance("SHA256withECDSA").apply {
        initSign(mySigning.private)
    }

    /** A structurally valid first event for the append-guard test (the store never verifies
     *  signatures; the guard refuses before any structure is examined). */
    private fun seedEnvelopeForAppend() = SignedEnvelope(
        unsigned = UnsignedEnvelope(
            senderKeyId = mySenderKeyId,
            epoch = 0,
            sequence = 1,
            previousEventHash = null,
            nonce = ByteArray(12),
            ciphertext = byteArrayOf(9),
            createdAtEpochMillis = clock,
        ),
        signature = ByteArray(8),
    )

    companion object {
        private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
