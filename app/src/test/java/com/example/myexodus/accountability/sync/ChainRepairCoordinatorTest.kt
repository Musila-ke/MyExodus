package com.example.myexodus.accountability.sync

import android.app.Application
import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.crypto.IdentityEpochStore
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.SignedEnvelope
import com.example.myexodus.accountability.serialization.UnsignedEnvelope
import com.example.myexodus.accountability.serialization.decodePayload
import com.example.myexodus.accountability.serialization.decodeSigned
import com.example.myexodus.accountability.serialization.eventHash
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ChainEvent
import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.SessionKeyManager
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
 * Plan §8 Step 10 / AD-08 + AD-08b with real crypto: a partner-namespace gap opens
 * REPAIR_PENDING, the pass mints one signed SyncRequest per 24h carrying the stored head as
 * the anchor, the 30-day window lapses one stage per pass to the neutral CHAIN_INCOMPLETE
 * (never TAMPER_SUSPECTED), a warm biometric window is required (cold defers, never
 * prompts), and the responder replays this device's own stored bytes only after the
 * requested anchor hash-matches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChainRepairCoordinatorTest {

    private lateinit var context: Context
    private lateinit var db: AccountabilityDatabase
    private lateinit var store: ChainStateStore
    private lateinit var coordinator: ChainRepairCoordinator

    private val cipher = EventCipher()
    private val cekDerivation = CekDerivation()
    private val myAgreement = ecKeyPair()
    private val mySigning = ecKeyPair()
    private val partnerAgreement = ecKeyPair()
    private val partnerSigning = ecKeyPair()
    private val mySenderKeyId = sha256Hex(mySigning.public.encoded)
    private val partnerSenderKeyId = sha256Hex(partnerSigning.public.encoded)
    private val pairId = "pair-repair-test"

    private val cek = cekDerivation.deriveCek(pairId, myAgreement.private, partnerAgreement.public)

    private var clock = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, "test_${System.nanoTime()}.db")
            .build()
        store = ChainStateStore(db)
        coordinator = coordinator(warmSigner())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- requester side ----

    @Test
    fun gap_mintsOneSyncRequest_carryingTheStoredHeadAsTheAnchor() = runTest {
        enroll()
        val partnerE1 = partnerChainWithGap().first()

        val created = coordinator.repairPass(pairId)

        assertEquals(1, created)
        val request = store.chainFor(pairId).single { it.senderKeyId == mySenderKeyId }
        val payload = decodePayload(cipher.decrypt(cek, request.sequence, decodeSigned(request.envelope).unsigned.ciphertext))
        assertEquals(EventPayload.TYPE_SYNC_REQUEST, payload.type)
        // AD-08b: the anchor is the last partner event this device actually holds.
        assertEquals(1L, payload.expectedPreviousSequence)
        assertTrue(payload.expectedPreviousHash!!.contentEquals(eventHash(partnerE1.unsigned)))

        val head = store.localHead(pairId, partnerSenderKeyId)!!
        assertEquals(ChainHead.REPAIR_PENDING, head.repairState)
        assertEquals(clock, head.repairFirstRequestedAtEpochMillis)
        assertEquals(clock, head.repairLastRequestedAtEpochMillis)
    }

    @Test
    fun requestCadence_isOnePerDay_untilTheGapHeals() = runTest {
        enroll()
        partnerChainWithGap()

        assertEquals(1, coordinator.repairPass(pairId))
        assertEquals("same-day re-run sends nothing", 0, coordinator.repairPass(pairId))

        clock += ChainRepairCoordinator.REQUEST_RETRY_MILLIS
        assertEquals(1, coordinator.repairPass(pairId))

        val head = store.localHead(pairId, partnerSenderKeyId)!!
        assertEquals("the 30-day window still anchors on the first request", 1_700_000_000_000L, head.repairFirstRequestedAtEpochMillis)
        assertEquals(clock, head.repairLastRequestedAtEpochMillis)
    }

    @Test
    fun coldSigningWindow_skipsTheRequest_andTheNextWarmPassRetries() = runTest {
        enroll()
        partnerChainWithGap()
        val cold = coordinator(coldSigner())

        assertEquals(0, cold.repairPass(pairId))

        val head = store.localHead(pairId, partnerSenderKeyId)!!
        assertEquals(ChainHead.REPAIR_PENDING, head.repairState)
        assertNull("no request went out, so no window started", head.repairFirstRequestedAtEpochMillis)

        assertEquals(1, coordinator.repairPass(pairId))
    }

    @Test
    fun unpaired_createsNothing_andLeavesTheRepairPending() = runTest {
        partnerChainWithGap() // no enrollment row: no partner keys to build the request from

        assertEquals(0, coordinator.repairPass(pairId))

        val head = store.localHead(pairId, partnerSenderKeyId)!!
        assertEquals(ChainHead.REPAIR_PENDING, head.repairState)
        assertNull(head.repairFirstRequestedAtEpochMillis)
        assertTrue(store.chainFor(pairId).none { it.senderKeyId == mySenderKeyId })
    }

    @Test
    fun lapsedWindow_escalatesOneStagePerPass_neverATamperVerdict() = runTest {
        enroll()
        partnerChainWithGap()
        assertEquals(1, coordinator.repairPass(pairId))

        // 30 days with no replay: stage 2 — stop requesting, keep accepting a late replay.
        clock += ChainRepairCoordinator.RECOVERY_WINDOW_MILLIS
        assertEquals(0, coordinator.repairPass(pairId))
        assertEquals(ChainHead.REPAIR_RECOVERY_EXHAUSTED, store.localHead(pairId, partnerSenderKeyId)!!.repairState)

        // Next pass: stage 3 — the neutral terminal (§9 row 457), never a tamper verdict.
        assertEquals(0, coordinator.repairPass(pairId))
        val head = store.localHead(pairId, partnerSenderKeyId)!!
        assertEquals(ChainHead.REPAIR_CHAIN_INCOMPLETE, head.repairState)
        assertFalse(head.forked)
        assertTrue(store.gapSequences(pairId, partnerSenderKeyId).isEmpty())
    }

    @Test
    fun replayWithinTheWindow_healsTheChain_andClosesTheRepair() = runTest {
        enroll()
        val (_, missing) = partnerChainWithGap()
        assertEquals(1, coordinator.repairPass(pairId))

        // The partner answered with the missing middle event (its exact stored bytes).
        assertEquals(ChainStateStore.AppendResult.Appended, store.receiveRemote(pairId, missing))

        val head = store.localHead(pairId, partnerSenderKeyId)!!
        assertEquals(3L, head.lastSequence)
        assertEquals(ChainHead.REPAIR_NONE, head.repairState)
        assertEquals("no further requests once healed", 0, coordinator.repairPass(pairId))
    }

    // ---- responder side (AD-08b) ----

    @Test
    fun respondToRequest_matchingAnchor_replaysTheFollowingStoredBytes() = runTest {
        enroll()
        val mine = appendMyChain()
        val before = db.outboxDao().pendingFor(pairId, 10).map { it.messageId }.toSet()

        val replayed = coordinator.respondToRequest(pairId, 1, eventHash(decodeSigned(mine[0].envelope).unsigned))

        assertEquals(2, replayed)
        val fresh = db.outboxDao().pendingFor(pairId, 10).filter { it.messageId !in before }
        assertEquals(listOf(mine[1].id, mine[2].id), fresh.map { it.eventId })
        fresh.forEachIndexed { i, row -> assertArrayEquals(mine[i + 1].envelope, row.envelopeBytes) }
    }

    @Test
    fun respondToRequest_mismatchedAnchor_replaysNothing() = runTest {
        enroll()
        appendMyChain()
        val before = db.outboxDao().pendingFor(pairId, 10).size

        assertEquals(0, coordinator.respondToRequest(pairId, 1, ByteArray(32) { 9 }))

        assertEquals("histories diverged: nothing is honest to send", before, db.outboxDao().pendingFor(pairId, 10).size)
    }

    // ---- helpers ----

    private fun coordinator(signer: AccountabilitySigner) = ChainRepairCoordinator(
        enrollmentDao = db.enrollmentDao(),
        store = store,
        creator = EventCreator(
            store = store,
            enrollmentDao = db.enrollmentDao(),
            cekDerivation = cekDerivation,
            eventCipher = cipher,
            signer = signer,
            epochStore = IdentityEpochStore(db.identityEpochDao()),
            agreementPrivateKey = { myAgreement.private },
            sessionKeyManager = SessionKeyManager(store),
        ),
        signer = signer,
        now = { clock },
    )

    private fun enroll() = runBlocking {
        db.enrollmentDao().upsert(
            EnrollmentRecord(
                pairId = pairId,
                mySenderKeyId = mySenderKeyId,
                partnerSigningKeyId = sha256Hex(partnerSigning.public.encoded),
                partnerSigningPubKey = partnerSigning.public.encoded,
                partnerAgreementPubKey = partnerAgreement.public.encoded,
                createdAtEpochMillis = clock,
            ),
        )
    }

    /** Partner-namespace envelopes: the store never checks signatures (the pipeline does). */
    private fun partnerEnvelope(sequence: Long, prev: ByteArray? = null, seed: Int = 0) = SignedEnvelope(
        unsigned = UnsignedEnvelope(
            senderKeyId = partnerSenderKeyId,
            epoch = 0,
            sequence = sequence,
            previousEventHash = prev,
            nonce = ByteArray(12) { seed.toByte() },
            ciphertext = byteArrayOf(seed.toByte(), 7),
            createdAtEpochMillis = clock,
        ),
        signature = ByteArray(8),
    )

    /** Partner events 1 and 3 delivered, 2 lost in transit → the buffered gap opens the repair.
     *  Returns [e1, e2]: e2 is the event whose arrival heals the chain. */
    private suspend fun partnerChainWithGap(): List<SignedEnvelope> {
        val e1 = partnerEnvelope(1)
        val e2 = partnerEnvelope(2, prev = eventHash(e1.unsigned), seed = 2)
        store.receiveRemote(pairId, e1)
        store.receiveRemote(pairId, partnerEnvelope(3, prev = eventHash(e2.unsigned), seed = 3))
        return listOf(e1, e2)
    }

    /** This device's own chain: three SENT events, outbox rows included. */
    private suspend fun appendMyChain(): List<ChainEvent> {
        var prev: ByteArray? = null
        for (sequence in 1L..3L) {
            val envelope = SignedEnvelope(
                unsigned = UnsignedEnvelope(
                    senderKeyId = mySenderKeyId,
                    epoch = 0,
                    sequence = sequence,
                    previousEventHash = prev,
                    nonce = ByteArray(12) { sequence.toByte() },
                    ciphertext = byteArrayOf(sequence.toByte(), 7),
                    createdAtEpochMillis = clock,
                ),
                signature = ByteArray(8),
            )
            assertEquals(ChainStateStore.AppendResult.Appended, store.appendLocal(pairId, envelope))
            prev = eventHash(envelope.unsigned)
        }
        return store.chainFor(pairId).filter { it.senderKeyId == mySenderKeyId }
    }

    private fun warmSigner() = SignerFake(privateKey = mySigning.private)

    private fun coldSigner() = SignerFake(privateKey = mySigning.private, cold = true)

    /** Stands in for the Keystore boundary: warm returns a plain JCA Signature over the test
     *  keypair; cold throws exactly what AndroidKeyStore throws after the 900s window lapses. */
    private class SignerFake(
        private val privateKey: PrivateKey,
        private val cold: Boolean = false,
    ) : AccountabilitySigner() {
        override fun beginSign(): Signature {
            if (cold) throw UserNotAuthenticatedException()
            return Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }
        }
    }

    companion object {
        private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
