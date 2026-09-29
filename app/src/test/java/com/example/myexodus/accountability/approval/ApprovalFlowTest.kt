package com.example.myexodus.accountability.approval

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.crypto.IdentityEpochStore
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.decodePayload
import com.example.myexodus.accountability.serialization.decodeSigned
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.SessionKeyManager
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §8 Step 12 / §9 row 459 / §10 line 624 with real crypto + real Room: every approval transition
 * is exactly-once (CAS-guarded), epoch-bound (AD-07), and expiry-anchored (AD-16: responder =
 * server timestamp, requester = display-only wall clock). Rejections leave state untouched and
 * chain nothing — the responder of an untrustworthy request must not create evidence that looks
 * like a decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApprovalFlowTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var store: ChainStateStore
    private lateinit var creator: EventCreator
    private lateinit var flow: ApprovalFlow

    private val signer = AccountabilitySigner()
    private val cipher = EventCipher()
    private val cekDerivation = CekDerivation()
    private val myAgreement = ecKeyPair()
    private val mySigning = ecKeyPair()
    private val partnerAgreement = ecKeyPair()
    private val partnerSigning = ecKeyPair()
    private val mySenderKeyId = sha256Hex(mySigning.public.encoded)
    private val pairId = "pair-approval-test"

    private val cek = cekDerivation.deriveCek(pairId, myAgreement.private, partnerAgreement.public)

    /** A read-paired device: epoch 1, so the AD-07 checks are exercised with a live nonzero value. */
    private val epoch = 1
    private val now0 = 1_700_000_000_000L
    private var clock = now0
    private var requestNotifies = 0
    private var decisionNotifies = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()
        store = ChainStateStore(db)
        creator = EventCreator(
            store = store,
            enrollmentDao = db.enrollmentDao(),
            cekDerivation = cekDerivation,
            eventCipher = cipher,
            signer = signer,
            epochStore = IdentityEpochStore(db.identityEpochDao()),
            agreementPrivateKey = { myAgreement.private },
            sessionKeyManager = SessionKeyManager(store),
        )
        flow = ApprovalFlow(
            dao = db.approvalRequestDao(),
            enrollmentDao = db.enrollmentDao(),
            creator = creator,
            notifyRequest = { requestNotifies++ },
            notifyDecision = { decisionNotifies++ },
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
                    createdAtEpochMillis = now0,
                    pairingEpoch = epoch,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    // ---- requester side ----

    @Test
    fun requestApproval_chainsEventAndPersistsRequesterRow() = runTest {
        val result = flow.requestApproval(pairId, "please disable strict mode", signing())
        assertEquals(
            ApprovalFlow.RequestResult.Submitted(ApprovalRequest.eventIdOf(pairId, mySenderKeyId, 1L)),
            result,
        )

        val payload = payloadAt(0)
        assertEquals(EventPayload.TYPE_APPROVAL_REQUEST, payload.type)
        assertEquals("please disable strict mode", payload.text)
        assertEquals(epoch, payload.pairingEpoch)

        val row = db.approvalRequestDao()
            .byId(ApprovalRequest.eventIdOf(pairId, mySenderKeyId, 1L))!!
        assertEquals(ApprovalRequest.ROLE_REQUESTER, row.role)
        assertEquals(ApprovalRequest.STATUS_REQUESTED, row.status)
        assertEquals(epoch, row.pairingEpoch)
        assertEquals(now0, row.createdAtEpochMillis)
        assertEquals(now0 + ApprovalRequest.TTL_MILLIS, row.expiresAtEpochMillis)
        assertNull(row.decidedAtEpochMillis)
    }

    @Test
    fun requestApproval_notEnrolled_returnsNotEnrolled() = runTest {
        assertEquals(
            ApprovalFlow.RequestResult.NotEnrolled,
            flow.requestApproval("pair-unknown", "x", signing()),
        )
    }

    // ---- responder side: wire path ----

    @Test
    fun onRequestAppended_insertsServerAnchoredRow_andNotifiesOnce() = runTest {
        val serverTs = now0 + 5_000
        flow.onRequestAppended(pairId, "partnerKey", 4L, requestPayload("help"), serverTs)

        val row = db.approvalRequestDao()
            .byId(ApprovalRequest.eventIdOf(pairId, "partnerKey", 4L))!!
        assertEquals(ApprovalRequest.ROLE_RESPONDER, row.role)
        assertEquals(ApprovalRequest.STATUS_REQUESTED, row.status)
        assertEquals("help", row.message)
        // AD-16: the security-relevant deadline rides the server timestamp, not the device clock.
        assertEquals(serverTs, row.createdAtEpochMillis)
        assertEquals(serverTs + ApprovalRequest.TTL_MILLIS, row.expiresAtEpochMillis)
        assertEquals(1, requestNotifies)

        // Step 11 replay / mailbox re-drain: IGNORE insert, no second notification.
        flow.onRequestAppended(pairId, "partnerKey", 4L, requestPayload("help"), serverTs)
        assertEquals(1, requestNotifies)
    }

    /**
     * ADR #120 regression: the signing key is device-level and every pair's chain starts at
     * sequence 1, so (senderKeyId, sequence) identifies a request only WITH its pair. The old
     * pair-less canonical id made a second pair's RESPONDER row collide with the first pair's on
     * the request_event_id primary key — insertIgnore no-op'd and the request silently never
     * appeared (found on-device 2026-09-29, task #94).
     */
    @Test
    fun onRequestAppended_sameChainPositionInTwoPairs_producesTwoRows() = runTest {
        val otherPairId = "pair-approval-test-2"
        db.enrollmentDao().upsert(
            EnrollmentRecord(
                pairId = otherPairId,
                mySenderKeyId = mySenderKeyId,
                partnerSigningKeyId = sha256Hex(partnerSigning.public.encoded),
                partnerSigningPubKey = partnerSigning.public.encoded,
                partnerAgreementPubKey = partnerAgreement.public.encoded,
                createdAtEpochMillis = now0,
                pairingEpoch = epoch,
            ),
        )

        flow.onRequestAppended(pairId, "partnerKey", 1L, requestPayload("first pair"), now0)
        flow.onRequestAppended(otherPairId, "partnerKey", 1L, requestPayload("second pair"), now0)

        val first = db.approvalRequestDao()
            .byId(ApprovalRequest.eventIdOf(pairId, "partnerKey", 1L))!!
        val second = db.approvalRequestDao()
            .byId(ApprovalRequest.eventIdOf(otherPairId, "partnerKey", 1L))!!
        assertEquals(pairId, first.pairId)
        assertEquals(otherPairId, second.pairId)
        assertEquals("first pair", first.message)
        assertEquals("second pair", second.message)
        assertEquals("each pair's request must notify its own post", 2, requestNotifies)
    }

    @Test
    fun onRequestAppended_wrongOrMissingEpoch_isDroppedSilently() = runTest {
        flow.onRequestAppended(pairId, "partnerKey", 1L, requestPayload("x", epoch = epoch + 1), now0)
        flow.onRequestAppended(pairId, "partnerKey", 2L, requestPayload("x", epoch = null), now0)

        assertNull(db.approvalRequestDao().byId(ApprovalRequest.eventIdOf(pairId, "partnerKey", 1L)))
        assertNull(db.approvalRequestDao().byId(ApprovalRequest.eventIdOf(pairId, "partnerKey", 2L)))
        assertEquals(0, requestNotifies)
    }

    // ---- responder side: decision ----

    @Test
    fun respond_approve_movesRowAndChainsEpochBoundResponse() = runTest {
        insertResponderRow("partnerKey:4", message = "help")
        val at = now0 + 1_000
        clock = at

        assertEquals(ApprovalFlow.RespondResult.Decided(true), flow.respond("partnerKey:4", true, signing()))

        val row = db.approvalRequestDao().byId("partnerKey:4")!!
        assertEquals(ApprovalRequest.STATUS_APPROVED, row.status)
        assertEquals(at, row.decidedAtEpochMillis)

        val payload = payloadAt(0)
        assertEquals(EventPayload.TYPE_APPROVAL_RESPONSE, payload.type)
        assertEquals("partnerKey:4", payload.requestEventId)
        assertEquals(epoch, payload.pairingEpoch)
        assertEquals(true, payload.decision)
    }

    @Test
    fun respond_reject_movesRowToRejected() = runTest {
        insertResponderRow("partnerKey:4", message = "help")
        clock = now0 + 1_000

        assertEquals(ApprovalFlow.RespondResult.Decided(false), flow.respond("partnerKey:4", false, signing()))

        val row = db.approvalRequestDao().byId("partnerKey:4")!!
        assertEquals(ApprovalRequest.STATUS_REJECTED, row.status)
        assertEquals(false, payloadAt(db.chainEventDao().chainFor(pairId).size - 1).decision)
    }

    @Test
    fun respond_secondTime_isRefusedAndChainsNoSecondEvent() = runTest {
        insertResponderRow("partnerKey:4", message = "help")
        clock = now0 + 1_000
        flow.respond("partnerKey:4", true, signing())
        val chainSize = db.chainEventDao().chainFor(pairId).size

        val refused = flow.respond("partnerKey:4", false, signing())
        assertTrue(refused is ApprovalFlow.RespondResult.Refused)
        assertEquals(ApprovalRequest.STATUS_APPROVED, db.approvalRequestDao().byId("partnerKey:4")!!.status)
        assertEquals(chainSize, db.chainEventDao().chainFor(pairId).size)
    }

    @Test
    fun respond_expiredRow_isRefusedAndChainsNothing() = runTest {
        insertResponderRow("partnerKey:4", message = "help", expiresAt = now0 + 5_000)
        clock = now0 + 5_001

        val refused = flow.respond("partnerKey:4", true, signing())
        assertTrue(refused is ApprovalFlow.RespondResult.Refused)
        assertEquals(ApprovalRequest.STATUS_REQUESTED, db.approvalRequestDao().byId("partnerKey:4")!!.status)
        assertTrue(db.chainEventDao().chainFor(pairId).isEmpty())
    }

    @Test
    fun respond_wrongEpoch_isRefusedAndChainsNothing() = runTest {
        insertResponderRow("partnerKey:4", message = "help", epoch = epoch - 1)
        clock = now0 + 1_000

        val refused = flow.respond("partnerKey:4", true, signing())
        assertTrue(refused is ApprovalFlow.RespondResult.Refused)
        assertEquals(ApprovalRequest.STATUS_REQUESTED, db.approvalRequestDao().byId("partnerKey:4")!!.status)
        assertTrue(db.chainEventDao().chainFor(pairId).isEmpty())
    }

    @Test
    fun respond_clockRolledBack_guardRefuses() = runTest {
        // AD-16: now < created_at means the device clock moved backwards — nothing may be decided.
        insertResponderRow("partnerKey:4", message = "help", createdAt = now0 + 60_000)
        clock = now0

        val refused = flow.respond("partnerKey:4", true, signing())
        assertTrue(refused is ApprovalFlow.RespondResult.Refused)
        assertEquals(ApprovalRequest.STATUS_REQUESTED, db.approvalRequestDao().byId("partnerKey:4")!!.status)
    }

    @Test
    fun respond_requesterRowOrUnknown_isRefused() = runTest {
        db.approvalRequestDao().insertIgnore(
            ApprovalRequest(
                requestEventId = "own:1",
                pairId = pairId,
                role = ApprovalRequest.ROLE_REQUESTER,
                status = ApprovalRequest.STATUS_REQUESTED,
                pairingEpoch = epoch,
                message = "mine",
                createdAtEpochMillis = now0,
                expiresAtEpochMillis = now0 + ApprovalRequest.TTL_MILLIS,
            ),
        )
        assertTrue(flow.respond("own:1", true, signing()) is ApprovalFlow.RespondResult.Refused)
        assertTrue(flow.respond("nope:9", true, signing()) is ApprovalFlow.RespondResult.Refused)
        assertTrue(db.chainEventDao().chainFor(pairId).isEmpty())
    }

    // ---- requester side: wire path ----

    @Test
    fun applyResponse_appliesDecisionAndNotifies() = runTest {
        insertRequesterRow("own:1", message = "please")
        val serverTs = now0 + 60_000

        flow.applyResponse(
            pairId,
            EventPayload(
                type = EventPayload.TYPE_APPROVAL_RESPONSE,
                text = "",
                requestEventId = "own:1",
                pairingEpoch = epoch,
                decision = false,
            ),
            serverTs,
        )

        val row = db.approvalRequestDao().byId("own:1")!!
        assertEquals(ApprovalRequest.STATUS_REJECTED, row.status)
        assertEquals(serverTs, row.decidedAtEpochMillis)
        assertEquals(1, decisionNotifies)
    }

    @Test
    fun applyResponse_replayedResponse_doesNotMoveDecidedRowAgain() = runTest {
        insertRequesterRow("own:1", message = "please", status = ApprovalRequest.STATUS_REJECTED)
        flow.applyResponse(pairId, responsePayload("own:1", approve = true), now0)
        assertEquals(ApprovalRequest.STATUS_REJECTED, db.approvalRequestDao().byId("own:1")!!.status)
        assertEquals(0, decisionNotifies)
    }

    @Test
    fun applyResponse_consumedOrExpiredRequest_isRejected() = runTest {
        // §11 acceptance line 624: non-existent, already-consumed, wrong-epoch — all rejected.
        insertRequesterRow("own:1", message = "please", status = ApprovalRequest.STATUS_CONSUMED)
        insertRequesterRow("own:2", message = "please", status = ApprovalRequest.STATUS_EXPIRED)
        flow.applyResponse(pairId, responsePayload("own:1", approve = true), now0)
        flow.applyResponse(pairId, responsePayload("own:2", approve = true), now0)

        assertEquals(ApprovalRequest.STATUS_CONSUMED, db.approvalRequestDao().byId("own:1")!!.status)
        assertEquals(ApprovalRequest.STATUS_EXPIRED, db.approvalRequestDao().byId("own:2")!!.status)
        assertEquals(0, decisionNotifies)
    }

    @Test
    fun applyResponse_epochMismatch_isRejected() = runTest {
        insertRequesterRow("own:1", message = "please")
        // Response claims a different epoch than the row + enrollment (AD-07 replay after re-pair).
        flow.applyResponse(pairId, responsePayload("own:1", approve = true, epoch = epoch + 1), now0)
        // Row epoch and enrollment disagree (stale row from a previous partnership).
        db.approvalRequestDao().insertIgnore(
            ApprovalRequest(
                requestEventId = "own:2",
                pairId = pairId,
                role = ApprovalRequest.ROLE_REQUESTER,
                status = ApprovalRequest.STATUS_REQUESTED,
                pairingEpoch = epoch - 1,
                message = "old",
                createdAtEpochMillis = now0,
                expiresAtEpochMillis = now0 + ApprovalRequest.TTL_MILLIS,
            ),
        )
        flow.applyResponse(pairId, responsePayload("own:2", approve = true, epoch = epoch - 1), now0)

        assertEquals(ApprovalRequest.STATUS_REQUESTED, db.approvalRequestDao().byId("own:1")!!.status)
        assertEquals(ApprovalRequest.STATUS_REQUESTED, db.approvalRequestDao().byId("own:2")!!.status)
        assertEquals(0, decisionNotifies)
    }

    @Test
    fun applyResponse_unknownOrMalformed_isDroppedSilently() = runTest {
        // §10 line 624: a response for a non-existent request is rejected, logged, not applied.
        flow.applyResponse(pairId, responsePayload("ghost:7", approve = true), now0)
        flow.applyResponse(
            pairId,
            EventPayload(type = EventPayload.TYPE_APPROVAL_RESPONSE, text = "", requestEventId = null, decision = true),
            now0,
        )
        flow.applyResponse(
            pairId,
            EventPayload(type = EventPayload.TYPE_APPROVAL_RESPONSE, text = "", requestEventId = "ghost:8", decision = null),
            now0,
        )
        assertEquals(0, decisionNotifies)
    }

    // ---- sweep + one-shot consume ----

    @Test
    fun expireDue_marksOnlyPastDeadlineRows() = runTest {
        insertRequesterRow("own:1", message = "old", expiresAt = now0 + 1_000)
        db.approvalRequestDao().insertIgnore(
            ApprovalRequest(
                requestEventId = "own:2",
                pairId = pairId,
                role = ApprovalRequest.ROLE_REQUESTER,
                status = ApprovalRequest.STATUS_REQUESTED,
                pairingEpoch = epoch,
                message = "fresh",
                createdAtEpochMillis = now0,
                expiresAtEpochMillis = now0 + ApprovalRequest.TTL_MILLIS,
            ),
        )
        clock = now0 + 1_001

        assertEquals(1, flow.expireDue(pairId))
        assertEquals(ApprovalRequest.STATUS_EXPIRED, db.approvalRequestDao().byId("own:1")!!.status)
        assertEquals(ApprovalRequest.STATUS_REQUESTED, db.approvalRequestDao().byId("own:2")!!.status)
    }

    @Test
    fun consume_isOneShotFromApprovedOnly() = runTest {
        insertRequesterRow("own:1", message = "please", status = ApprovalRequest.STATUS_APPROVED)
        assertTrue(flow.consume("own:1"))
        assertEquals(ApprovalRequest.STATUS_CONSUMED, db.approvalRequestDao().byId("own:1")!!.status)
        assertTrue(!flow.consume("own:1"))

        insertRequesterRow("own:2", message = "pending")
        assertTrue(!flow.consume("own:2"))
    }

    // ---- helpers ----

    private fun signing(): Signature = Signature.getInstance("SHA256withECDSA").apply {
        initSign(mySigning.private)
    }

    private fun requestPayload(text: String, epoch: Int? = this.epoch) =
        EventPayload(type = EventPayload.TYPE_APPROVAL_REQUEST, text = text, pairingEpoch = epoch)

    private fun responsePayload(id: String, approve: Boolean, epoch: Int? = this.epoch) =
        EventPayload(
            type = EventPayload.TYPE_APPROVAL_RESPONSE,
            text = "",
            requestEventId = id,
            pairingEpoch = epoch,
            decision = approve,
        )

    private suspend fun insertResponderRow(
        id: String,
        message: String,
        epoch: Int = this.epoch,
        createdAt: Long = now0,
        expiresAt: Long = now0 + ApprovalRequest.TTL_MILLIS,
    ) {
        db.approvalRequestDao().insertIgnore(
            ApprovalRequest(
                requestEventId = id,
                pairId = pairId,
                role = ApprovalRequest.ROLE_RESPONDER,
                status = ApprovalRequest.STATUS_REQUESTED,
                pairingEpoch = epoch,
                message = message,
                createdAtEpochMillis = createdAt,
                expiresAtEpochMillis = expiresAt,
            ),
        )
    }

    private suspend fun insertRequesterRow(
        id: String,
        message: String,
        status: String = ApprovalRequest.STATUS_REQUESTED,
        expiresAt: Long = now0 + ApprovalRequest.TTL_MILLIS,
    ) {
        db.approvalRequestDao().insertIgnore(
            ApprovalRequest(
                requestEventId = id,
                pairId = pairId,
                role = ApprovalRequest.ROLE_REQUESTER,
                status = status,
                pairingEpoch = epoch,
                message = message,
                createdAtEpochMillis = now0,
                expiresAtEpochMillis = expiresAt,
            ),
        )
    }

    /** Decrypts the payload of the [index]-th chained event (same pipeline the partner runs). */
    private suspend fun payloadAt(index: Int): EventPayload {
        val signed = decodeSigned(db.chainEventDao().chainFor(pairId)[index].envelope)
        assertNotNull(signed)
        return decodePayload(cipher.decrypt(cek, signed.unsigned.sequence, signed.unsigned.ciphertext))
    }

    companion object {
        private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
