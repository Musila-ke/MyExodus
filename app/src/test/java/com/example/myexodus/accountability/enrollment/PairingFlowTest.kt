package com.example.myexodus.accountability.enrollment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.AttestationTestCertificates
import com.example.myexodus.accountability.crypto.AttestationVerifier
import com.example.myexodus.accountability.sync.FirestoreSchema
import com.example.myexodus.accountability.sync.PairingExchange
import com.example.myexodus.accountability.sync.PairingRegistration
import com.example.myexodus.secrets.SecretsRepository
import com.example.myexodus.secrets.SecretsStatus
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §10 pairing end-to-end, v2 model (§8 Step 3). Both devices run the real [PairingFlow]
 * against a fake relay + fake registerPairing that mirror the deployed shapes: token
 * document as transport (single-use response, rules-enforced), signed proofs to the
 * function, activation on matching transcript hashes within the window. The durable
 * pairId is min_max(uids), computed independently on both sides and by the "server".
 * The attestation verifier is stubbed per the §12 on-device convention.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingFlowTest {

    private val enrollmentFlow = EnrollmentFlow(
        signer = AccountabilitySigner(),
        attestationVerifier = object : AttestationVerifier() {
            override fun verify(
                chain: List<X509Certificate>,
                expectedChallenge: ByteArray,
            ): AttestationVerifier.VerificationResult =
                AttestationVerifier.VerificationResult.Verified(AttestationVerifier.SecurityLevel.TRUSTED_ENVIRONMENT)
        },
        cekDerivation = CekDerivation(),
    )

    @Test
    fun fullRoundTrip_pairsBothDevices_sameSas_durablePairId_tokenDeleted() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val partnerDao = FakeEnrollmentDao()
        val initiatorSecrets = FakeSecrets()
        val partnerSecrets = FakeSecrets()
        val initiatorFlow = flow(initiator, cloud, testScheduler, initiatorDao, initiatorSecrets) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, partnerDao, partnerSecrets) { nowEpochMillis }

        val initiation = initiatorFlow.initiate("uid-a")
        val sessionId = initiation.token.sessionId
        val durablePairId = EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b")

        val partnerJob = async {
            partnerFlow.complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000)) {
            "initiator never saw the partner's token response"
        }
        val initiatorOutcome = initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", signing(initiator.signing.private))
        val partnerOutcome = partnerJob.await()

        // §6.1: both devices agree on the same SAS for the out-of-band comparison; the
        // pair is keyed by the AD-02 durable id, not the per-attempt session id.
        assertEquals(PairingFlow.Outcome.Paired(durablePairId, await.sas), initiatorOutcome)
        val partnerPaired = partnerOutcome as PairingFlow.Outcome.Paired
        assertEquals(durablePairId, partnerPaired.pairId)
        assertEquals(await.sas, partnerPaired.sas)
        assertEquals(5, await.sas.length)
        assertEquals(sessionId, await.sessionId)
        assertTrue("QR carries only the token (payload rides the token doc)", initiation.qrBytes.size < 200)
        assertEquals("active", cloud.pairings.getValue(durablePairId).status)

        // Both sides persisted the mirror enrollment record and the same pairing key.
        val initiatorRecord = checkNotNull(initiatorDao.getByPairId(durablePairId))
        val partnerRecord = checkNotNull(partnerDao.getByPairId(durablePairId))
        assertArrayEquals(partner.signing.public.encoded, initiatorRecord.partnerSigningPubKey)
        assertArrayEquals(initiator.signing.public.encoded, partnerRecord.partnerSigningPubKey)
        assertEquals(initiatorRecord.mySenderKeyId, partnerRecord.partnerSigningKeyId)
        assertArrayEquals(
            checkNotNull(initiatorSecrets.pairingKey.value),
            checkNotNull(partnerSecrets.pairingKey.value),
        )
        assertEquals(32, checkNotNull(partnerSecrets.pairingKey.value).size)

        assertTrue("token deleted after activation", cloud.tokenDocs.isEmpty())
    }

    @Test
    fun expiredToken_neverTouchesTheRelay() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        nowEpochMillis += PairingToken.TTL_MILLIS + 1 // §10 step 1: time-limited
        val outcome = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))

        assertEquals(PairingFlow.Outcome.TokenExpired(initiation.token.sessionId), outcome)
        assertTrue("expired token never consumed", cloud.tokenDocs.isNotEmpty())
    }

    @Test
    fun alreadyRespondedToken_isAlreadyUsedWithoutSigning() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val firstPartner = DeviceKeys()
        val secondPartner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        // Another device already answered on the token document (single-use).
        cloud.tokenDocs.getValue(FirestoreSchema.pairingTokenDocumentId(initiation.token)).partnerResponse =
            PairingExchange.PartnerResponse("uid-x", ownPayload(firstPartner, initiation.token.sessionId), ByteArray(8))

        val outcome = flow(secondPartner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .complete(initiation.qrBytes, "uid-b", signing(secondPartner.signing.private))
        assertEquals(PairingFlow.Outcome.TokenAlreadyUsed(initiation.token.sessionId), outcome)
        assertTrue("conflicting token response untouched", cloud.pairings.isEmpty())
    }

    @Test
    fun responseWriteDeniedBySingleUseRule_isAlreadyUsed() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        cloud.denyNextRespond = true // concurrent second responder: rules deny the write
        val outcome = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        assertEquals(PairingFlow.Outcome.TokenAlreadyUsed(initiation.token.sessionId), outcome)
        assertTrue(cloud.pairings.isEmpty())
    }

    @Test
    fun corruptPartnerPayload_initiatorAbortsAndDeletesTheToken() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        cloud.corruptPartnerPayload = true
        val partnerJob = async {
            flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
                .complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        assertNull(
            "initiator rejects unverifiable partner material",
            initiatorFlow.awaitPartner(initiation.token, 30_000),
        )
        assertTrue("abort deletes the token document", cloud.tokenDocs.isEmpty())
        assertTrue("no pairing ever activates", cloud.pairings.values.none { it.status == "active" })
        assertTrue("partner never sees activation", partnerJob.await() is PairingFlow.Outcome.Rejected)
    }

    @Test
    fun corruptPartnerTranscriptSignature_initiatorAbortsAndDeletesTheToken() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        cloud.corruptPartnerResponseSignature = true
        val partnerJob = async {
            flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
                .complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        assertNull(
            "initiator rejects a forged transcript signature",
            initiatorFlow.awaitPartner(initiation.token, 30_000),
        )
        assertTrue(cloud.tokenDocs.isEmpty())
        assertTrue(partnerJob.await() is PairingFlow.Outcome.Rejected)
    }

    @Test
    fun transcriptMismatchAtTheFunction_rejectsBothDevices_noEnrollmentRecord() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val initiatorFlow = flow(initiator, cloud, testScheduler, initiatorDao, FakeSecrets()) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        // The initiator's proof arrives with a transcript hash the function cannot match
        // to the partner's stored proof (mirrors the server's TRANSCRIPT_MISMATCH).
        cloud.tamperTranscriptHashForUid = "uid-a"
        val partnerJob = async {
            partnerFlow.complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        val initiatorOutcome = initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", signing(initiator.signing.private))

        assertEquals(PairingFlow.Outcome.Rejected("TRANSCRIPT_MISMATCH"), initiatorOutcome)
        assertTrue("no enrollment record persisted", initiatorDao.records.isEmpty())
        assertEquals("pending", cloud.pairings.getValue(EnrollmentTranscript.durablePairIdOf("uid-a", "uid-b")).status)
        assertTrue(partnerJob.await() is PairingFlow.Outcome.Rejected)
    }

    @Test
    fun partnerRegistrationLost_initiatorSignsIntoPending_andAborts() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorDao = FakeEnrollmentDao()
        val initiatorFlow = flow(initiator, cloud, testScheduler, initiatorDao, FakeSecrets()) { nowEpochMillis }
        val partnerFlow = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        // The partner answers on the token but its proof call dies: only one proof ever
        // reaches the function, so the pairing can never activate.
        cloud.failNextRegistration = true
        val partnerJob = async {
            partnerFlow.complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        val initiatorOutcome = initiatorFlow.submitInitiatorSignature(await, initiation.token, "uid-a", signing(initiator.signing.private))

        assertTrue(initiatorOutcome is PairingFlow.Outcome.Rejected)
        assertTrue("no enrollment record persisted", initiatorDao.records.isEmpty())
        assertTrue(partnerJob.await() is PairingFlow.Outcome.Rejected)
    }

    @Test
    fun qrClaimingADifferentSession_isRejectedBeforeTheRelay() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val partner = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        val content = PairingCodec.decode(initiation.qrBytes)
        val tampered = PairingCodec.encodeWithSessionId(content.token, "deadbeefdeadbeef")

        val outcome = flow(partner, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
            .complete(tampered, "uid-b", signing(partner.signing.private))
        assertTrue(outcome is PairingFlow.Outcome.Rejected)
        assertTrue("nothing consumed before the codec check", cloud.tokenDocs.isNotEmpty())
    }

    @Test
    fun awaitPartner_timesOutWhenNobodyResponds() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val cloud = FakeCloud()
        val initiator = DeviceKeys()
        val initiatorFlow = flow(initiator, cloud, testScheduler, FakeEnrollmentDao(), FakeSecrets()) { nowEpochMillis }
        val initiation = initiatorFlow.initiate("uid-a")

        assertNull(initiatorFlow.awaitPartner(initiation.token, 30_000))
        assertTrue(cloud.pairings.isEmpty())
        assertFalse("timeout alone does not consume the token", cloud.tokenDocs.isEmpty())
    }

    private fun ownPayload(keys: DeviceKeys, sessionId: String) = EnrollmentPayload(
        sessionId = sessionId,
        signingChain = keys.chain.map { it.encoded },
        attestationChallenge = AttestationVerifier.embeddedChallengeOf(keys.chain[0])!!,
        agreementPublicKey = EnrollmentTranscript.pubKeyBytes(keys.agreement.public),
    )

    private fun flow(
        keys: DeviceKeys,
        cloud: FakeCloud,
        scheduler: TestCoroutineScheduler,
        dao: FakeEnrollmentDao,
        secrets: FakeSecrets,
        nowMillis: () -> Long,
    ): PairingFlow = PairingFlow(
        enrollmentFlow = enrollmentFlow,
        enrollmentDao = dao,
        exchange = FakeExchange(cloud, nowMillis, scheduler),
        registration = FakeRegistration(cloud),
        secretsRepository = secrets,
        nowEpochMillis = nowMillis,
        signingCertificateChain = { keys.chain },
        signingPublicKey = { keys.signing.public },
        agreementPublicKey = { keys.agreement.public },
        agreementPrivateKey = { keys.agreement.private },
    )

    private fun signing(privateKey: PrivateKey): Signature =
        Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }

    private class DeviceKeys {
        val signing = AttestationTestCertificates.keyPair()
        val agreement = AttestationTestCertificates.keyPair()
        val chain = arrayOf(
            AttestationTestCertificates.selfSignedWithChallenge(signing, AttestationTestCertificates.randomChallenge()),
        )
    }

    /**
     * Shared relay + function state. Corruption flags model an active attacker on the
     * relay (payload/signature tampering) and dropped server calls, exactly the failure
     * modes PairingFlow must survive.
     */
    private class FakeCloud {
        val tokenDocs = mutableMapOf<String, TokenDocState>()
        val pairings = mutableMapOf<String, PairingState>()
        var corruptPartnerPayload = false
        var corruptPartnerResponseSignature = false
        var denyNextRespond = false
        var failNextRegistration = false
        var tamperTranscriptHashForUid: String? = null

        class TokenDocState(
            val token: PairingToken,
            val creatorUid: String,
            val payload: EnrollmentPayload,
            var partnerResponse: PairingExchange.PartnerResponse? = null,
        )

        class PairingState(
            val proofs: MutableMap<String, PairingProof> = mutableMapOf(),
            var status: String = "pending",
            var pairingEpoch: Int = 0,
        )
    }

    /** Relay fake: token map + pairing map, polling bounded by the test scheduler's virtual clock. */
    private class FakeExchange(
        private val cloud: FakeCloud,
        private val nowMillis: () -> Long,
        private val scheduler: TestCoroutineScheduler,
    ) : PairingExchange(ApplicationProvider.getApplicationContext(), firestoreInstance()) {

        override suspend fun registerToken(token: PairingToken, creatorUid: String, payload: EnrollmentPayload) {
            cloud.tokenDocs[FirestoreSchema.pairingTokenDocumentId(token)] =
                FakeCloud.TokenDocState(token, creatorUid, payload)
        }

        override suspend fun readToken(token: PairingToken): TokenReadResult {
            if (token.isExpired(nowMillis())) return TokenReadResult.Denied // rules expiry gate
            val doc = cloud.tokenDocs[FirestoreSchema.pairingTokenDocumentId(token)]
                ?: return TokenReadResult.NotFound
            return TokenReadResult.Found(doc.creatorUid, doc.payload, doc.partnerResponse)
        }

        override suspend fun respondOnToken(
            token: PairingToken,
            myUid: String,
            myPayload: EnrollmentPayload,
            partnerSignature: ByteArray,
        ): RespondResult {
            val doc = cloud.tokenDocs[FirestoreSchema.pairingTokenDocumentId(token)]
                ?: return RespondResult.AlreadyUsed
            if (token.isExpired(nowMillis())) return RespondResult.AlreadyUsed
            if (cloud.denyNextRespond) {
                cloud.denyNextRespond = false
                return RespondResult.AlreadyUsed
            }
            if (doc.partnerResponse != null) return RespondResult.AlreadyUsed // single-use
            val payload = if (cloud.corruptPartnerPayload) {
                myPayload.copy(signingChain = myPayload.signingChain.map { flip(it) })
            } else {
                myPayload
            }
            val signature = if (cloud.corruptPartnerResponseSignature) flip(partnerSignature) else partnerSignature
            doc.partnerResponse = PairingExchange.PartnerResponse(myUid, payload, signature)
            return RespondResult.Written
        }

        override suspend fun pollTokenResponse(token: PairingToken, timeoutMillis: Long): PairingExchange.PartnerResponse? {
            val deadline = scheduler.currentTime + timeoutMillis
            while (true) {
                when (val read = readToken(token)) {
                    is TokenReadResult.Found -> read.partnerResponse?.let { return it }
                    TokenReadResult.NotFound, TokenReadResult.Denied -> return null
                    is TokenReadResult.Failed -> Unit
                }
                if (scheduler.currentTime >= deadline) return null
                delay(PairingExchange.POLL_INTERVAL_MILLIS)
            }
        }

        override suspend fun awaitPairingActivation(
            pairId: String,
            myUid: String,
            expectedTranscriptHash: String,
            timeoutMillis: Long,
        ): PairingStatus {
            val deadline = scheduler.currentTime + timeoutMillis
            while (true) {
                val state = cloud.pairings[pairId]
                if (state != null && state.status == "active") {
                    val hash = state.proofs[myUid]?.transcriptHash
                    return if (hash == expectedTranscriptHash) PairingStatus.Active(state.pairingEpoch) else PairingStatus.ActiveDifferentSession
                }
                if (scheduler.currentTime >= deadline) return PairingStatus.Pending
                delay(PairingExchange.POLL_INTERVAL_MILLIS)
            }
        }

        override suspend fun deleteToken(token: PairingToken) {
            cloud.tokenDocs.remove(FirestoreSchema.pairingTokenDocumentId(token))
        }

        override suspend fun terminatePair(pairId: String) {
            cloud.pairings.remove(pairId)
        }

        private fun flip(bytes: ByteArray): ByteArray =
            bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    }

    /**
     * registerPairing fake mirroring functions/index.js + functions/proof.js: structural
     * checks, signature verification over the canonical message, AD-02 pairId computation,
     * pending → active on matching transcript hashes. The wire round trip is what the
     * client test needs to pin — the crypto itself is also covered by the node suite.
     */
    private class FakeRegistration(private val cloud: FakeCloud) :
        PairingRegistration(ApplicationProvider.getApplicationContext(), functionsInstance()) {

        override suspend fun submit(proof: PairingProof): SubmitResult {
            if (cloud.failNextRegistration) {
                cloud.failNextRegistration = false
                return SubmitResult.Failed("simulated network failure")
            }
            if (proof.ownUid == proof.partnerUid) return SubmitResult.Rejected("SELF_PAIRING")
            if (proof.protocolVersion != PairingProof.PROTOCOL_VERSION) {
                return SubmitResult.Rejected("PROTOCOL_VERSION_UNSUPPORTED")
            }
            if (EnrollmentTranscript.senderKeyId(proof.signingPublicKey) != proof.publicKeyHash) {
                return SubmitResult.Rejected("KEY_HASH_MISMATCH")
            }
            val message = PairingProof.canonicalMessageOf(
                ownUid = proof.ownUid,
                partnerUid = proof.partnerUid,
                publicKeyHash = proof.publicKeyHash,
                transcriptHash = proof.transcriptHash,
                protocolVersion = proof.protocolVersion,
            )
            val verified = runCatching {
                AccountabilitySigner().verify(
                    EnrollmentTranscript.parsePublicKey(proof.signingPublicKey),
                    message,
                    proof.signature,
                )
            }.getOrDefault(false)
            if (!verified) return SubmitResult.Rejected("SIGNATURE_INVALID")

            val pairId = EnrollmentTranscript.durablePairIdOf(proof.ownUid, proof.partnerUid)
            val state = cloud.pairings.getOrPut(pairId) { FakeCloud.PairingState() }
            if (state.status == "active") {
                // Idempotent re-submission of the same proof (functions/index.js contract).
                return if (state.proofs[proof.ownUid]?.transcriptHash == proof.transcriptHash) {
                    SubmitResult.Registered(pairId, state.pairingEpoch)
                } else {
                    SubmitResult.Rejected("PAIRING_ACTIVE")
                }
            }
            val stored = if (proof.ownUid == cloud.tamperTranscriptHashForUid) {
                proof.copy(transcriptHash = EnrollmentTranscript.sha256Hex("tampered".toByteArray()))
            } else {
                proof
            }
            state.proofs[proof.ownUid] = stored
            val other = state.proofs[proof.partnerUid] ?: return SubmitResult.AwaitingPartner
            if (other.transcriptHash != stored.transcriptHash) {
                return SubmitResult.Rejected("TRANSCRIPT_MISMATCH")
            }
            state.status = "active"
            return SubmitResult.Registered(pairId, state.pairingEpoch)
        }
    }

    private class FakeEnrollmentDao : EnrollmentDao {
        val records = mutableListOf<EnrollmentRecord>()

        override suspend fun upsert(record: EnrollmentRecord) {
            records.removeAll { it.pairId == record.pairId }
            records += record
        }

        override suspend fun getByPairId(pairId: String): EnrollmentRecord? =
            records.firstOrNull { it.pairId == pairId }

        override suspend fun all(): List<EnrollmentRecord> = records.toList()

        override suspend fun delete(record: EnrollmentRecord) {
            records.remove(record)
        }

        override suspend fun updateMailboxWatermark(pairId: String, ts: Long) {
            records.replaceAll {
                if (it.pairId == pairId) it.copy(mailboxWatermarkTsEpochMillis = ts) else it
            }
        }
    }

    private class FakeSecrets : SecretsRepository {
        override val status = MutableStateFlow(SecretsStatus.OK)
        override val pairingKey = MutableStateFlow<ByteArray?>(null)
        override val escapeCode = MutableStateFlow<ByteArray?>(null)

        override suspend fun storePairingKey(material: ByteArray) {
            pairingKey.value = material
        }

        override suspend fun storeEscapeCode(code: ByteArray) {
            escapeCode.value = code
        }
    }

    private companion object {
        // The super constructors need real Firebase singletons; the fakes never touch the network.
        private fun firebaseApp(): FirebaseApp {
            val context: Context = ApplicationProvider.getApplicationContext()
            return FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId("test-app")
                        .setProjectId("test-project")
                        .setApiKey("test-key")
                        .build(),
                )!!
        }

        private fun firestoreInstance(): FirebaseFirestore = FirebaseFirestore.getInstance(firebaseApp())

        private fun functionsInstance(): FirebaseFunctions =
            FirebaseFunctions.getInstance(firebaseApp(), PairingRegistration.REGION)
    }
}
