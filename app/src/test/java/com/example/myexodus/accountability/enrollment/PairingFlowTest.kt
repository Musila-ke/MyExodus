package com.example.myexodus.accountability.enrollment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.AttestationTestCertificates
import com.example.myexodus.accountability.crypto.AttestationVerifier
import com.example.myexodus.accountability.sync.FirestoreSchema
import com.example.myexodus.accountability.sync.PairingExchange
import com.example.myexodus.secrets.SecretsRepository
import com.example.myexodus.secrets.SecretsStatus
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §10 pairing end-to-end (§7 step 8): token issuance → QR → relay redemption (single-use,
 * time-limited) → mutual transcript-signature verification (§6.1) → finalize (CEK,
 * enrollment record, pairing key). Both devices run the real [PairingFlow] against a fake
 * relay; the attestation verifier is stubbed per the §12 on-device convention.
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
    fun fullRoundTrip_pairsBothDevices_sameSas_samePairingKey() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val exchange = FakeExchange({ nowEpochMillis }, testScheduler)
        val initiator = DeviceKeys("uid-a")
        val partner = DeviceKeys("uid-b")
        val initiatorDao = FakeEnrollmentDao()
        val partnerDao = FakeEnrollmentDao()
        val initiatorSecrets = FakeSecrets()
        val partnerSecrets = FakeSecrets()
        val initiatorFlow = flow(initiator, exchange, initiatorDao, initiatorSecrets, { nowEpochMillis })
        val partnerFlow = flow(partner, exchange, partnerDao, partnerSecrets, { nowEpochMillis })

        val initiation = initiatorFlow.initiate("uid-a")
        val pairId = initiation.token.pairId

        val partnerJob = async {
            partnerFlow.complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(pairId, 30_000)) {
            "initiator never saw the partner's redemption"
        }
        val initiatorOutcome = initiatorFlow.submitInitiatorSignature(await, "uid-a", signing(initiator.signing.private))
        val partnerOutcome = partnerJob.await()

        // §6.1: both devices agree on the same SAS for the out-of-band comparison.
        assertEquals(PairingFlow.Outcome.Paired(pairId, await.sas), initiatorOutcome)
        val partnerPaired = partnerOutcome as PairingFlow.Outcome.Paired
        assertEquals(pairId, partnerPaired.pairId)
        assertEquals(await.sas, partnerPaired.sas)
        assertEquals(5, await.sas.length)
        assertTrue("QR carries only the token (payload rides the token doc)", initiation.qrBytes.size < 200)

        // Both sides persisted the mirror enrollment record and the same pairing key.
        val initiatorRecord = checkNotNull(initiatorDao.getByPairId(pairId))
        val partnerRecord = checkNotNull(partnerDao.getByPairId(pairId))
        assertArrayEquals(partner.signing.public.encoded, initiatorRecord.partnerSigningPubKey)
        assertArrayEquals(initiator.signing.public.encoded, partnerRecord.partnerSigningPubKey)
        assertEquals(initiatorRecord.mySenderKeyId, partnerRecord.partnerSigningKeyId)
        assertArrayEquals(
            checkNotNull(initiatorSecrets.pairingKey.value),
            checkNotNull(partnerSecrets.pairingKey.value),
        )
        assertEquals(32, checkNotNull(partnerSecrets.pairingKey.value).size)

        assertTrue("token consumed by the redemption", exchange.tokenDocs.isEmpty())
    }

    @Test
    fun expiredToken_neverReachesTheRelay() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val exchange = FakeExchange({ nowEpochMillis }, testScheduler)
        val initiator = DeviceKeys("uid-a")
        val partner = DeviceKeys("uid-b")
        val initiatorFlow = flow(initiator, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
        val initiation = initiatorFlow.initiate("uid-a")

        nowEpochMillis += PairingToken.TTL_MILLIS + 1 // §10 step 1: time-limited
        val outcome = flow(partner, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
            .complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))

        assertEquals(PairingFlow.Outcome.TokenExpired(initiation.token.pairId), outcome)
        assertTrue("expired token never consumed", exchange.tokenDocs.isNotEmpty())
    }

    @Test
    fun secondRedeemOfTheSameToken_isAlreadyUsed() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val exchange = FakeExchange({ nowEpochMillis }, testScheduler)
        val initiator = DeviceKeys("uid-a")
        val firstPartner = DeviceKeys("uid-b")
        val initiatorFlow = flow(initiator, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
        val initiation = initiatorFlow.initiate("uid-a")

        val firstRedeem = async {
            flow(firstPartner, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
                .complete(initiation.qrBytes, "uid-b", signing(firstPartner.signing.private))
        }
        runCurrent() // the first redeemer consumes the token before the second attempt
        val secondPartner = DeviceKeys("uid-c")
        val secondOutcome = flow(secondPartner, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
            .complete(initiation.qrBytes, "uid-c", signing(secondPartner.signing.private))

        assertEquals(PairingFlow.Outcome.TokenAlreadyUsed(initiation.token.pairId), secondOutcome)
        // The first redeemer never got the initiator's signature, so it aborts at timeout.
        assertTrue(firstRedeem.await() is PairingFlow.Outcome.Rejected)
    }

    @Test
    fun corruptInitiatorSignature_partnerAbortsAndDeletesPairState() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val exchange = FakeExchange({ nowEpochMillis }, testScheduler)
        val initiator = DeviceKeys("uid-a")
        val partner = DeviceKeys("uid-b")
        val initiatorFlow = flow(initiator, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
        val partnerFlow = flow(partner, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
        val initiation = initiatorFlow.initiate("uid-a")

        val partnerJob = async {
            partnerFlow.complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        val await = checkNotNull(initiatorFlow.awaitPartner(initiation.token.pairId, 30_000))
        exchange.corruptInitiatorSignature = true
        initiatorFlow.submitInitiatorSignature(await, "uid-a", signing(initiator.signing.private))

        val outcome = partnerJob.await()
        assertTrue("partner must not pair with a forged signature", outcome is PairingFlow.Outcome.Rejected)
        assertTrue("abort deletes the pair document", exchange.pairStates.isEmpty())
    }

    @Test
    fun corruptPartnerPayload_initiatorAbortsAndDeletesPairState() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val exchange = FakeExchange({ nowEpochMillis }, testScheduler)
        val initiator = DeviceKeys("uid-a")
        val partner = DeviceKeys("uid-b")
        val initiatorFlow = flow(initiator, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
        val partnerFlow = flow(partner, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
        val initiation = initiatorFlow.initiate("uid-a")

        exchange.corruptPartnerPayload = true
        val partnerJob = async {
            partnerFlow.complete(initiation.qrBytes, "uid-b", signing(partner.signing.private))
        }
        assertNull("initiator rejects unverifiable partner material", initiatorFlow.awaitPartner(initiation.token.pairId, 30_000))
        assertTrue(exchange.pairStates.isEmpty())
        assertTrue(partnerJob.await() is PairingFlow.Outcome.Rejected)
    }

    @Test
    fun qrCarryingMismatchedPairId_isRejectedBeforeTheRelay() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val exchange = FakeExchange({ nowEpochMillis }, testScheduler)
        val initiator = DeviceKeys("uid-a")
        val partner = DeviceKeys("uid-b")
        val initiatorFlow = flow(initiator, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
        val initiation = initiatorFlow.initiate("uid-a")

        val content = PairingCodec.decode(initiation.qrBytes)
        val tampered = PairingCodec.encodeWithPairId(content.token, "deadbeefdeadbeef")

        val outcome = flow(partner, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
            .complete(tampered, "uid-b", signing(partner.signing.private))
        assertTrue(outcome is PairingFlow.Outcome.Rejected)
        assertTrue("nothing consumed before the codec check", exchange.tokenDocs.isNotEmpty())
    }

    @Test
    fun awaitPartner_timesOutWhenNobodyRedeems() = runTest {
        var nowEpochMillis = 1_700_000_000_000L
        val exchange = FakeExchange({ nowEpochMillis }, testScheduler)
        val initiator = DeviceKeys("uid-a")
        val initiatorFlow = flow(initiator, exchange, FakeEnrollmentDao(), FakeSecrets(), { nowEpochMillis })
        val initiation = initiatorFlow.initiate("uid-a")

        assertNull(initiatorFlow.awaitPartner(initiation.token.pairId, 30_000))
        assertTrue(exchange.pairStates.isEmpty())
    }

    private fun flow(
        keys: DeviceKeys,
        exchange: FakeExchange,
        dao: FakeEnrollmentDao,
        secrets: FakeSecrets,
        nowMillis: () -> Long,
    ): PairingFlow = PairingFlow(
        enrollmentFlow = enrollmentFlow,
        enrollmentDao = dao,
        exchange = exchange,
        secretsRepository = secrets,
        nowEpochMillis = nowMillis,
        signingCertificateChain = { keys.chain },
        signingPublicKey = { keys.signing.public },
        agreementPublicKey = { keys.agreement.public },
        agreementPrivateKey = { keys.agreement.private },
    )

    private fun signing(privateKey: PrivateKey): Signature =
        Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }

    private class DeviceKeys(val uid: String) {
        val signing = AttestationTestCertificates.keyPair()
        val agreement = AttestationTestCertificates.keyPair()
        val chain = arrayOf(
            AttestationTestCertificates.selfSignedWithChallenge(signing, AttestationTestCertificates.randomChallenge()),
        )
    }

    /** Relay fake: token map + pair-state map, polling bounded by the test scheduler's virtual clock. */
    private class FakeExchange(
        private val nowMillis: () -> Long,
        private val scheduler: TestCoroutineScheduler,
    ) : PairingExchange(ApplicationProvider.getApplicationContext(), firestoreInstance()) {
        val tokenDocs = mutableMapOf<String, PairingToken>()
        val tokenPayloads = mutableMapOf<String, EnrollmentPayload>()
        val pairStates = mutableMapOf<String, PairingExchange.PairState>()
        var corruptInitiatorSignature = false
        var corruptPartnerPayload = false

        override suspend fun registerToken(token: PairingToken, creatorUid: String, payload: EnrollmentPayload) {
            val id = FirestoreSchema.pairingTokenDocumentId(token)
            tokenDocs[id] = token
            tokenPayloads[id] = payload
        }

        override suspend fun redeem(
            token: PairingToken,
            myUid: String,
            myPayload: EnrollmentPayload,
        ): PairingExchange.RedeemResult {
            val id = FirestoreSchema.pairingTokenDocumentId(token)
            if (!tokenDocs.containsKey(id)) return PairingExchange.RedeemResult.AlreadyUsed
            if (token.isExpired(nowMillis())) return PairingExchange.RedeemResult.Expired
            val initiatorPayload = tokenPayloads.remove(id) ?: return PairingExchange.RedeemResult.Failed("missing payload")
            tokenDocs.remove(id)
            val payload = if (corruptPartnerPayload) {
                myPayload.copy(signingChain = myPayload.signingChain.map { flip(it) })
            } else {
                myPayload
            }
            pairStates[token.pairId] = PairingExchange.PairState(payload, null, null)
            return PairingExchange.RedeemResult.Redeemed(initiatorPayload)
        }

        override suspend fun confirmPairing(pairId: String, initiatorSignature: ByteArray) {
            val state = pairStates[pairId] ?: return
            val signature = if (corruptInitiatorSignature) flip(initiatorSignature) else initiatorSignature
            pairStates[pairId] = PairingExchange.PairState(state.enrollmentPayload, signature, state.partnerSignature)
        }

        override suspend fun confirmPartnerSignature(pairId: String, partnerSignature: ByteArray) {
            val state = pairStates[pairId] ?: return
            pairStates[pairId] = PairingExchange.PairState(state.enrollmentPayload, state.initiatorSignature, partnerSignature)
        }

        override suspend fun deletePairState(pairId: String) {
            pairStates.remove(pairId)
        }

        override suspend fun pollPairState(
            pairId: String,
            timeoutMillis: Long,
            requireInitiatorSignature: Boolean,
            requirePartnerSignature: Boolean,
        ): PairingExchange.PairState? {
            val deadline = scheduler.currentTime + timeoutMillis
            while (true) {
                val state = pairStates[pairId]
                val missing = (requireInitiatorSignature && state?.initiatorSignature == null) ||
                    (requirePartnerSignature && state?.partnerSignature == null)
                if (state != null && !missing) return state
                if (scheduler.currentTime >= deadline) return null
                delay(PairingExchange.POLL_INTERVAL_MILLIS)
            }
        }

        private fun flip(bytes: ByteArray): ByteArray =
            bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    }

    private companion object {
        // The super constructor needs a real FirebaseApp; the fake never touches the network.
        private fun firestoreInstance(): FirebaseFirestore {
            val context: Context = ApplicationProvider.getApplicationContext()
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId("test-app")
                        .setProjectId("test-project")
                        .setApiKey("test-key")
                        .build(),
                )!!
            return FirebaseFirestore.getInstance(app)
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
}
