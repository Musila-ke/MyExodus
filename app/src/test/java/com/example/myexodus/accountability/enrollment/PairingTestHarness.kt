package com.example.myexodus.accountability.enrollment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
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
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Shared pairing-ceremony harness (§12.1): both devices run the real [PairingFlow] against a
 * fake relay + fake registerPairing that mirror the deployed shapes — token document as
 * transport (single-use response, rules-enforced expiry gate), signed proofs to the function,
 * activation on matching transcript hashes within the window. Extracted from
 * [PairingFlowTest] so the adversarial matrix ([PairingAdversarialTest]) exercises byte-identical
 * relay semantics instead of a second copy that could drift.
 */
internal val pairingTestEnrollmentFlow = EnrollmentFlow(
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

/**
 * [exchangeNowMillis] is the server-side clock model: the relay's expiry gate reads it, the
 * flow's own checks read [nowMillis] (the trailing lambda). They are the same lambda in every
 * honest run; a test that skews them models client-clock lag against the deployed rules.
 */
internal fun pairingTestFlow(
    keys: DeviceKeys,
    cloud: FakeCloud,
    scheduler: TestCoroutineScheduler,
    dao: FakeEnrollmentDao,
    secrets: FakeSecrets,
    exchangeNowMillis: (() -> Long)? = null,
    // §19.5 (ADR #106) per-pair keys: the harness models the keystore as one test keypair —
    // alias-labelled calls all resolve to it; these seams let a test record what the flow
    // asked to create and destroy.
    generateAgreementKey: (String) -> Unit = { },
    destroyAgreementKey: (String) -> Unit = { },
    // §8.1 device identity mint (2026-09-28 fix): the harness models it as pre-provisioned
    // (the keystore has the keys), so tests record the call instead of exercising generation.
    ensureIdentityKeys: () -> AccountabilityKeyStore.GenerationResult = {
        AccountabilityKeyStore.GenerationResult.Success(strongBox = false)
    },
    // AD-22 wipe canary: recorded so tests can prove it is minted only by a durable enrollment.
    markPairingCompleted: () -> Unit = { },
    prunePartnerActivity: suspend (String) -> Unit = { },
    nowMillis: () -> Long,
): PairingFlow = PairingFlow(
    enrollmentFlow = pairingTestEnrollmentFlow,
    enrollmentDao = dao,
    exchange = FakeExchange(cloud, exchangeNowMillis ?: nowMillis, scheduler),
    registration = FakeRegistration(cloud),
    secretsRepository = secrets,
    nowEpochMillis = nowMillis,
    ensureIdentityKeys = ensureIdentityKeys,
    signingCertificateChain = { keys.chain },
    signingPublicKey = { keys.signing.public },
    generateAgreementKey = generateAgreementKey,
    agreementPublicKey = { keys.agreement.public },
    agreementPrivateKey = { keys.agreement.private },
    destroyAgreementKey = destroyAgreementKey,
    markPairingCompleted = markPairingCompleted,
    prunePartnerActivity = prunePartnerActivity,
)

internal fun pairingTestSigning(privateKey: PrivateKey): Signature =
    Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }

internal fun pairingTestOwnPayload(keys: DeviceKeys, sessionId: String) = EnrollmentPayload(
    sessionId = sessionId,
    signingChain = keys.chain.map { it.encoded },
    attestationChallenge = AttestationVerifier.embeddedChallengeOf(keys.chain[0])!!,
    agreementPublicKey = EnrollmentTranscript.pubKeyBytes(keys.agreement.public),
)

internal class DeviceKeys {
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
internal class FakeCloud {
    val tokenDocs = mutableMapOf<String, TokenDocState>()
    val pairings = mutableMapOf<String, PairingState>()
    var corruptPartnerPayload = false
    var corruptPartnerResponseSignature = false
    var denyNextRespond = false
    var failNextRegistration = false
    var failNextTokenRegistration = false
    var failNextActivationWait = false
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
internal class FakeExchange(
    private val cloud: FakeCloud,
    private val nowMillis: () -> Long,
    private val scheduler: TestCoroutineScheduler,
) : PairingExchange(ApplicationProvider.getApplicationContext(), firestoreInstance(), functionsInstance()) {

    override suspend fun registerToken(token: PairingToken, creatorUid: String, payload: EnrollmentPayload) {
        if (cloud.failNextTokenRegistration) {
            cloud.failNextTokenRegistration = false
            throw IllegalStateException("token registration denied")
        }
        cloud.tokenDocs[FirestoreSchema.pairingTokenDocumentId(token)] =
            FakeCloud.TokenDocState(token, creatorUid, payload)
    }

    override suspend fun readToken(token: PairingToken): TokenReadResult {
        if (token.isExpired(nowMillis())) return TokenReadResult.Denied // rules expiry gate
        val doc = cloud.tokenDocs[FirestoreSchema.pairingTokenDocumentId(token)]
            ?: return TokenReadResult.NotFound
        return foundOf(doc)
    }

    override suspend fun existingActivePair(uidA: String, uidB: String): Boolean =
        cloud.pairings[EnrollmentTranscript.durablePairIdOf(uidA, uidB)]?.status == "active"

    override suspend fun readTokenByValue(tokenBytes: ByteArray): TokenReadResult {
        val doc = cloud.tokenDocs[FirestoreSchema.pairingTokenDocumentId(tokenBytes)]
            ?: return TokenReadResult.NotFound
        if (doc.token.isExpired(nowMillis())) return TokenReadResult.Denied // rules expiry gate
        return foundOf(doc)
    }

    private fun foundOf(doc: FakeCloud.TokenDocState): TokenReadResult.Found =
        TokenReadResult.Found(
            doc.creatorUid,
            doc.payload,
            doc.partnerResponse,
            doc.token.expiresAtEpochMillis,
        )

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
        if (cloud.failNextActivationWait) {
            cloud.failNextActivationWait = false
            // Models the production rethrow: awaitPairingActivation propagates non-denial
            // transport failures out of the flow's submit wrappers (PairingExchange).
            throw FirebaseFirestoreException(
                "simulated transport drop",
                FirebaseFirestoreException.Code.UNAVAILABLE,
            )
        }
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

    override suspend fun terminatePair(pairId: String): Boolean {
        cloud.pairings.remove(pairId)
        return true
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
internal class FakeRegistration(private val cloud: FakeCloud) :
    PairingRegistration(ApplicationProvider.getApplicationContext(), functionsInstance()) {

    override suspend fun submit(proof: PairingProof): SubmitResult {
        if (cloud.failNextRegistration) {
            cloud.failNextRegistration = false
            return SubmitResult.Failed("simulated network failure")
        }
        // functions/index.js contract: requestId shape is validated before anything else.
        if (!Regex("[A-Za-z0-9_-]{8,64}").matches(proof.requestId)) {
            return SubmitResult.Rejected("REQUEST_ID_INVALID")
        }
        if (proof.ownUid == proof.partnerUid) return SubmitResult.Rejected("SELF_PAIRING")
        if (proof.protocolVersion != PairingProof.PROTOCOL_VERSION) {
            return SubmitResult.Rejected("PROTOCOL_VERSION_UNSUPPORTED")
        }
        if (EnrollmentTranscript.senderKeyId(proof.signingPublicKey) != proof.publicKeyHash) {
            return SubmitResult.Rejected("KEY_HASH_MISMATCH")
        }
        // Server-side attestation contract (functions/attestation.js): the proof must
        // carry the leaf-first chain, its challenge must equal the leaf's embedded one,
        // and the leaf public key must BE the proof signing key.
        val leaf = CertificateFactory.getInstance("X.509")
            .generateCertificate(proof.attestationChain.first().inputStream()) as X509Certificate
        if (AttestationVerifier.embeddedChallengeOf(leaf)?.contentEquals(proof.attestationChallenge) != true) {
            return SubmitResult.Rejected("CHALLENGE_MISMATCH")
        }
        if (!EnrollmentTranscript.pubKeyBytes(leaf.publicKey).contentEquals(proof.signingPublicKey)) {
            return SubmitResult.Rejected("KEY_CHAIN_MISMATCH")
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

internal class FakeEnrollmentDao : EnrollmentDao {
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

    override suspend fun updatePartnerLabel(pairId: String, label: String?) {
        records.replaceAll {
            if (it.pairId == pairId) it.copy(partnerLabel = label) else it
        }
    }
}

internal class FakeSecrets : SecretsRepository {
    override val status = MutableStateFlow(SecretsStatus.OK)
    override val pairingKey = MutableStateFlow<ByteArray?>(null)
    override val escapeCode = MutableStateFlow<ByteArray?>(null)

    /** Models the disk-full class: DataStore edit / keystore write throws after the
     *  enrollment row is already durable (finding #12). */
    var failStorePairingKey = false

    override suspend fun storePairingKey(material: ByteArray) {
        if (failStorePairingKey) throw java.io.IOException("simulated vault write failure")
        pairingKey.value = material
    }

    override suspend fun storeEscapeCode(code: ByteArray) {
        escapeCode.value = code
    }
}

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
