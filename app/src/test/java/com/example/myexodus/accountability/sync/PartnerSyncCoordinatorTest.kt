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
import com.example.myexodus.accountability.serialization.decodePayload
import com.example.myexodus.accountability.serialization.decodeSigned
import com.example.myexodus.accountability.serialization.eventHash
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.SessionKeyManager
import com.example.myexodus.blocking.AttemptRecorder
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.RecoverySession
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Partner sync (§14): recorded attempts become chain events (only while the biometric auth
 * window is warm) and the outbox drains to the partner mailbox. Covers the plan §6 flow end
 * to end with real crypto: blocked → "blocked:<target>" event, override → "override:<target>",
 * cold window defers (never prompts from background), and plan line 278's idempotency —
 * a second run links nothing and creates no duplicate event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerSyncCoordinatorTest {

    private lateinit var context: Context
    private lateinit var db: AccountabilityDatabase
    private lateinit var store: ChainStateStore
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var recovery: RecoveryManager
    private lateinit var recorder: AttemptRecorder
    private lateinit var coordinator: PartnerSyncCoordinator

    private val cipher = EventCipher()
    private val cekDerivation = CekDerivation()
    private val myAgreement = ecKeyPair()
    private val mySigning = ecKeyPair()
    private val partnerAgreement = ecKeyPair()
    private val partnerSigning = ecKeyPair()
    private val mySenderKeyId = sha256Hex(mySigning.public.encoded)
    private val pairId = "pair-sync-test"

    private val cek = cekDerivation.deriveCek(pairId, myAgreement.private, partnerAgreement.public)

    private var drainCalls = mutableListOf<Triple<String, String, String>>()
    private var pullCalls = mutableListOf<String>()
    private var pullWakeIds = mutableListOf<String?>()
    private var repairCalls = 0
    private var rejectionChecks = mutableListOf<Pair<String, String>>()
    private var rejectionsToReport = 0
    private val stepOrder = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, "test_${System.nanoTime()}.db")
            .build()
        store = ChainStateStore(db)
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        recorder = AttemptRecorder(factory)
        coordinator = coordinator(warmSigner())
    }

    @After
    fun tearDown() {
        db.close()
        recovery.resetAllData()
    }

    @Test
    fun unpaired_keepsAttemptsLocalAndCallsNoRemoteStep() = runTest {
        recorder.recordBlocked("r-1", "com.instagram.android")

        val report = coordinator.syncOnce()

        assertEquals(false, report.enrolled)
        assertEquals(0, report.eventsLinked)
        assertNull("attempt stays unlinked without a pair", pending().single().eventSequence)
        assertTrue(drainCalls.isEmpty())
        assertTrue(pullCalls.isEmpty())
        assertEquals(0, repairCalls)
    }

    @Test
    fun blockedAttempt_warmWindow_becomesLinkedDrainedEvent() = runTest {
        enroll()
        recorder.recordBlocked("r-1", "com.instagram.android")

        val report = coordinator.syncOnce()

        assertEquals(true, report.enrolled)
        assertEquals(1, report.eventsLinked)
        assertEquals("linked attempt carries the chain sequence", 1L, recent().single().eventSequence)
        assertEquals(true, report.drained)
        assertEquals(2, report.drainedCount)
        assertEquals(3, report.pulledCount)
        assertEquals(1, repairCalls)
        assertEquals(2, report.repairRequestsCreated)

        val signed = decodeSigned(store.chainFor(pairId).single().envelope)
        assertTrue("partner can verify the event", signer.verify(mySigning.public, eventHash(signed.unsigned), signed.signature))
        val payload = decodePayload(cipher.decrypt(cek, signed.unsigned.sequence, signed.unsigned.ciphertext))
        assertEquals("blocked:com.instagram.android", payload.text)

        val (pId, authorUid, partnerUid) = drainCalls.single()
        assertEquals(pairId, pId)
        assertEquals("me", authorUid)
        assertEquals("partner", partnerUid)
        assertEquals(listOf(pairId), pullCalls)
    }

    @Test
    fun overrideAttempt_producesOverridePayload() = runTest {
        enroll()
        recorder.recordOverride("r-1", "com.instagram.android")

        coordinator.syncOnce()

        val payload = decodePayload(
            cipher.decrypt(cek, 1L, decodeSigned(store.chainFor(pairId).single().envelope).unsigned.ciphertext),
        )
        assertEquals("override:com.instagram.android", payload.text)
    }

    @Test
    fun coldWindow_defersEventCreationButStillDrains() = runTest {
        enroll()
        recorder.recordBlocked("r-1", "com.a")
        coordinator = coordinator(coldSigner())

        val report = coordinator.syncOnce()

        assertEquals(0, report.eventsLinked)
        assertNull("cold window: attempt stays unlinked, delivered next run", pending().single().eventSequence)
        assertTrue("drain is independent of the signing window", report.drained)
        assertEquals(3, report.pulledCount)
    }

    @Test
    fun secondRun_createsNoDuplicateEvent() = runTest {
        enroll()
        recorder.recordBlocked("r-1", "com.a")
        val first = coordinator.syncOnce()
        assertEquals(1, first.eventsLinked)

        val second = coordinator.syncOnce()

        assertEquals(0, second.eventsLinked)
        assertEquals("one event, one delivery — idempotent retry (plan §278)", 1, store.chainFor(pairId).size)
        assertEquals(2, drainCalls.size)
    }

    @Test
    fun drainFailure_doesNotSkipPullOrRepair() = runTest {
        enroll()
        coordinator = coordinator(warmSigner(), drain = MailboxOutboxDrainer.DrainResult.Failed("network down"))

        val report = coordinator.syncOnce()

        assertEquals(false, report.drained)
        assertEquals("pull still ran", 3, report.pulledCount)
        assertEquals(1, repairCalls)
    }

    /** §8 Step 18: the relay-rejection read-back runs before the drain — this pass's own
     *  writes are not yet SENT, so they can never be checked against a relay that has not
     *  run — and its finding rides the report. */
    @Test
    fun rejectionCheck_runsBeforeTheDrain_andItsFindingRidesTheReport() = runTest {
        enroll()
        rejectionsToReport = 2

        val report = coordinator.syncOnce()

        assertEquals(listOf(pairId to "partner"), rejectionChecks)
        assertEquals(2, report.rejectionsFound)
        assertEquals("rejection check precedes the drain", listOf("rejection", "drain"), stepOrder)
        assertEquals("rejections do not disturb the drain", true, report.drained)
    }

    /** §6 step 3 expedited path: the wake's messageId rides the work input into the pull, so
     *  the out-of-order arrival is handled before the watermark query could miss it. */
    @Test
    fun wakeMessageId_isForwardedToThePull() = runTest {
        enroll()

        coordinator.syncOnce("m-42")
        coordinator.syncOnce()

        assertEquals(listOf<String?>("m-42", null), pullWakeIds)
    }

    /** W5: a finished technique session rides the same drain as attempts — linked under the
     *  signing window, payload `recovery:<tech>:<outcome>`, technique + outcome only (INV-13). */
    @Test
    fun recoverySession_warmWindow_becomesLinkedRecoveryEvent() = runTest {
        enroll()
        insertSession()

        val report = coordinator.syncOnce()

        assertEquals(1, report.eventsLinked)
        assertEquals(
            "linked session carries the chain sequence",
            1L,
            factory.open()!!.recoverySessionDao().byId("s-1")!!.eventSequence,
        )
        val signed = decodeSigned(store.chainFor(pairId).single().envelope)
        val payload = decodePayload(cipher.decrypt(cek, signed.unsigned.sequence, signed.unsigned.ciphertext))
        assertEquals("recovery:urge_surfing:completed", payload.text)
    }

    /** W5 unpaired case: no pair, no remote step, row keeps its null sequence. */
    @Test
    fun unpaired_recoverySessionStaysLocalAndCallsNoRemoteStep() = runTest {
        insertSession()

        val report = coordinator.syncOnce()

        assertEquals(false, report.enrolled)
        assertEquals(0, report.eventsLinked)
        assertNull(
            "session stays unlinked without a pair",
            factory.open()!!.recoverySessionDao().byId("s-1")!!.eventSequence,
        )
        assertTrue(drainCalls.isEmpty())
    }

    /** A log-only episode (Req 10 — no technique practised) is born terminal and must never
     *  reach the partner, even from an enrolled device with a warm window. */
    @Test
    fun logOnlySession_isNeverMirrored() = runTest {
        enroll()
        insertSession(technique = RecoverySession.TECHNIQUE_NONE)

        val report = coordinator.syncOnce()

        assertEquals(0, report.eventsLinked)
        assertTrue("no chain event for a technique-less log", store.chainFor(pairId).isEmpty())
    }

    // ---- helpers ----

    private fun coordinator(
        signer: AccountabilitySigner,
        drain: MailboxOutboxDrainer.DrainResult = MailboxOutboxDrainer.DrainResult.Sent(2),
    ) = PartnerSyncCoordinator(
        enrollmentDao = db.enrollmentDao(),
        storeFactory = factory,
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
        resolveUids = { pairId -> if (pairId == this.pairId) "me" to "partner" else null },
        drainOutbox = { pId, authorUid, partnerUid ->
            stepOrder += "drain"
            drainCalls += Triple(pId, authorUid, partnerUid)
            drain
        },
        checkRejections = { pId, partnerUid ->
            stepOrder += "rejection"
            rejectionChecks += pId to partnerUid
            rejectionsToReport
        },
        pullMailbox = { pId, myUid, messageId ->
            pullCalls += pId
            pullWakeIds += messageId
            FirestoreMailboxReceiver.PullResult(appended = 3)
        },
        repairPass = { repairCalls++; 2 },
    )

    private fun enroll() = runBlocking {
        db.enrollmentDao().upsert(
            EnrollmentRecord(
                pairId = pairId,
                mySenderKeyId = mySenderKeyId,
                partnerSigningKeyId = sha256Hex(partnerSigning.public.encoded),
                partnerSigningPubKey = partnerSigning.public.encoded,
                partnerAgreementPubKey = partnerAgreement.public.encoded,
                createdAtEpochMillis = 1_700_000_000_000L,
            ),
        )
    }

    private fun warmSigner() = SignerFake(privateKey = mySigning.private)

    private fun coldSigner() = SignerFake(privateKey = mySigning.private, cold = true)

    private val signer = warmSigner()

    private suspend fun pending() = factory.open()!!.attemptLogDao().pendingEventCreation(10)

    private suspend fun insertSession(
        id: String = "s-1",
        technique: String = "urge_surfing",
        state: String = RecoverySession.STATE_COMPLETED,
        startedAt: Long = 1_700_000_000_000L,
    ) = factory.open()!!.recoverySessionDao().insert(
        RecoverySession(
            id = id,
            technique = technique,
            state = state,
            startedAtEpochMs = startedAt,
            endedAtEpochMs = startedAt + 60_000,
            durationSec = 60,
            zoneId = "Africa/Nairobi",
            entry = RecoverySession.ENTRY_TAB,
        ),
    )

    private suspend fun recent() = factory.open()!!.attemptLogDao().recent(1)

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

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    companion object {
        private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
