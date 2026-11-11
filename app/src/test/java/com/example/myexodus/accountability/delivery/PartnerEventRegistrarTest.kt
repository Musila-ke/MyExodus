package com.example.myexodus.accountability.delivery

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.sync.FunctionErrors
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.util.Base64
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
 * Partner-delivery plan Phase 4 / §8.3: the client half of registration — the exact
 * `registerDeviceChallenge` / `registerOrRotateDevice` wire, the JCS message that is signed,
 * the attestation chain rule, and the two documented intent preconditions. The network seam
 * is stubbed, so these are pure JVM checks of payload construction and decision handling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerEventRegistrarTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var outbox: ServiceEventOutbox
    private lateinit var keyStore: FakeKeyStore

    private val challengeBytes = ByteArray(32) { it.toByte() }
    private val challenge = PartnerEventProtocol.base64Url(challengeBytes)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "registrar_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()
        outbox = ServiceEventOutbox(db)
        keyStore = FakeKeyStore()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun enroll_signsTheDocumentedJcsMessage_andSendsTheExactWire() = runBlocking {
        keyStore.chain = null
        val registrar = registrar(recipientSpki = RECIPIENT_SPKI, allowUnattested = true)
        val installation = outbox.ensureInstallation("uid-1")

        val outcome = registrar.register(installation.installationId)

        assertEquals(PartnerEventRegistrar.Outcome.Registered("ACTIVE_UNATTESTED", 1), outcome)
        assertEquals(
            listOf(mapOf("installationId" to installation.installationId, "intent" to "ENROLL")),
            registrar.challenges,
        )
        val wire = registrar.registers.single()
        assertEquals(FIXED_REQUEST_ID, wire["requestId"])
        assertEquals("cid-1", wire["challengeId"])
        assertEquals("ENROLL", wire["intent"])
        assertEquals(installation.installationId, wire["installationId"])
        assertEquals(FakeKeyStore.KEY_ID, wire["keyId"])
        assertEquals("the challenge string is echoed byte-for-byte", challenge, wire["challenge"])
        assertEquals(
            PartnerEventProtocol.base64Url(FakeKeyStore.SIGNATURE),
            wire["registrationSignature"],
        )
        assertEquals(
            "SPKI travels as standard base64, not base64url",
            Base64.getEncoder().encodeToString(FakeKeyStore.SPKI),
            wire["signingPublicKey"],
        )
        assertEquals(
            Base64.getEncoder().encodeToString(RECIPIENT_SPKI),
            wire["recipientPublicKey"],
        )
        assertEquals(true, wire["allowUnattested"])
        assertFalse("no chain may accompany the unattested opt-in", wire.containsKey("attestationChain"))

        assertArrayEquals(
            "the attestation challenge is baked in at keygen",
            challengeBytes,
            keyStore.lastChallenge,
        )
        assertArrayEquals(
            "the signed bytes are exactly the JCS registration message",
            Jcs.canonicalize(
                linkedMapOf(
                    "v" to 1,
                    "ownerUid" to "uid-1",
                    "installationId" to installation.installationId,
                    "keyId" to FakeKeyStore.KEY_ID,
                    "challenge" to challenge,
                ),
            ).toByteArray(Charsets.UTF_8),
            keyStore.lastSigned,
        )

        val committed = checkNotNull(outbox.installation("uid-1"))
        assertEquals(InstallationState.REGISTRATION_ACTIVE, committed.registrationState)
        assertEquals(FakeKeyStore.KEY_ID, committed.activeKeyId)
        assertEquals(RESPONSE_RECIPIENT_KEY_ID, committed.recipientKeyId)
    }

    @Test
    fun attestedChain_isPresentedWithTheMatchingChallenge_andNeverTheOptIn() = runBlocking {
        keyStore.chain = listOf(byteArrayOf(9, 9), byteArrayOf(8, 8))
        val registrar = registrar(recipientSpki = RECIPIENT_SPKI, allowUnattested = false)
        val installation = outbox.ensureInstallation("uid-1")

        val outcome = registrar.register(installation.installationId)

        assertEquals(PartnerEventRegistrar.Outcome.Registered("ACTIVE_UNATTESTED", 1), outcome)
        val wire = registrar.registers.single()
        assertFalse(wire.containsKey("allowUnattested"))
        assertEquals(
            keyStore.chain!!.map { Base64.getEncoder().encodeToString(it) },
            wire["attestationChain"],
        )
        assertEquals("the server string-compares this with `challenge`", challenge, wire["attestationChallenge"])
    }

    @Test
    fun missingChainWithoutTheDebugOptIn_isAttestationRequired_beforeAnyRegisterCall() = runBlocking {
        keyStore.chain = null
        val registrar = registrar(recipientSpki = RECIPIENT_SPKI, allowUnattested = false)
        val installation = outbox.ensureInstallation("uid-1")

        val outcome = registrar.register(installation.installationId)

        assertEquals(PartnerEventRegistrar.Outcome.AttestationRequired, outcome)
        assertEquals(1, registrar.challenges.size)
        assertTrue("a chainless key is never presented", registrar.registers.isEmpty())
        assertNull(outbox.installation("uid-1")!!.registrationState)
    }

    @Test
    fun rotationPrecondition_isRetriedWithTheOtherIntent() = runBlocking {
        keyStore.chain = null
        val registrar = registrar(recipientSpki = RECIPIENT_SPKI, allowUnattested = true)
        registrar.failures += FunctionErrors.of(
            "ROTATION_REQUIRED", FirebaseFunctionsException.Code.FAILED_PRECONDITION,
        )
        val installation = outbox.ensureInstallation("uid-1")

        val outcome = registrar.register(installation.installationId)

        assertEquals(PartnerEventRegistrar.Outcome.Registered("ACTIVE_UNATTESTED", 1), outcome)
        assertEquals(
            "each attempt requests a challenge for the intent it will send",
            listOf("ENROLL", "ROTATE"),
            registrar.challenges.map { it["intent"] },
        )
        assertEquals(listOf("ENROLL", "ROTATE"), registrar.registers.map { it["intent"] })
        assertArrayEquals(
            "the second challenge is baked into a fresh key before the rotation is signed",
            challengeBytes,
            keyStore.lastChallenge,
        )
    }

    @Test
    fun rotationNotEnrolled_isRetriedAsEnroll_whenLocalStateClaimedActive() = runBlocking {
        keyStore.chain = null
        val registrar = registrar(recipientSpki = RECIPIENT_SPKI, allowUnattested = true)
        val installation = outbox.ensureInstallation("uid-1")
        outbox.markRegistered(installation.installationId, FakeKeyStore.KEY_ID, "old-recipient-key")
        registrar.failures += FunctionErrors.of(
            "ROTATION_NOT_ENROLLED", FirebaseFunctionsException.Code.FAILED_PRECONDITION,
        )

        val outcome = registrar.register(installation.installationId)

        assertEquals(PartnerEventRegistrar.Outcome.Registered("ACTIVE_UNATTESTED", 1), outcome)
        assertEquals(listOf("ROTATE", "ENROLL"), registrar.registers.map { it["intent"] })
    }

    @Test
    fun staleAuth_makesNoNetworkCall() = runBlocking {
        val registrar = registrar(
            recipientSpki = RECIPIENT_SPKI,
            allowUnattested = true,
            auth = PartnerEventRegistrar.PartnerAuth("uid-1", fresh = false),
        )
        val installation = outbox.ensureInstallation("uid-1")

        val outcome = registrar.register(installation.installationId)

        assertEquals(PartnerEventRegistrar.Outcome.AuthNotFresh, outcome)
        assertTrue(registrar.challenges.isEmpty())
        assertTrue(registrar.registers.isEmpty())
    }

    @Test
    fun noRecipientKey_makesNoNetworkCall() = runBlocking {
        val registrar = registrar(recipientSpki = null, allowUnattested = true)
        val installation = outbox.ensureInstallation("uid-1")

        val outcome = registrar.register(installation.installationId)

        assertEquals(PartnerEventRegistrar.Outcome.NoRecipientKey, outcome)
        assertTrue(registrar.challenges.isEmpty())
        assertTrue(registrar.registers.isEmpty())
    }

    @Test
    fun serverKeyIdMismatch_isRetryable_andNothingIsCommitted() = runBlocking {
        keyStore.chain = null
        val registrar = registrar(recipientSpki = RECIPIENT_SPKI, allowUnattested = true)
        registrar.response = mapOf(
            "keyId" to "X".repeat(43),
            "recipientKeyId" to RESPONSE_RECIPIENT_KEY_ID,
            "integrityTier" to "ACTIVE",
            "registrationRevision" to 1,
        )
        val installation = outbox.ensureInstallation("uid-1")

        val outcome = registrar.register(installation.installationId)

        assertEquals(PartnerEventRegistrar.Outcome.Retryable, outcome)
        assertNull("a registration for a different key is never committed", outbox.installation("uid-1")!!.registrationState)
    }

    private fun registrar(
        recipientSpki: ByteArray?,
        allowUnattested: Boolean,
        auth: PartnerEventRegistrar.PartnerAuth? = PartnerEventRegistrar.PartnerAuth("uid-1", fresh = true),
    ) = ScriptedRegistrar(context, keyStore, outbox, recipientSpki, auth, allowUnattested)

    private class FakeKeyStore : PartnerEventKeyStore() {
        var chain: List<ByteArray>? = null
        var lastChallenge: ByteArray? = null
        var lastSigned: ByteArray? = null

        override fun generate(
            installationId: String,
            attestationChallenge: ByteArray?,
            strongBox: Boolean,
        ): GenerationResult {
            lastChallenge = attestationChallenge
            return GenerationResult.Success(
                strongBox = false,
                attested = attestationChallenge != null && attestationChain(
                    installationId, attestationChallenge,
                ) != null,
            )
        }

        override fun keyId(installationId: String): String = KEY_ID

        override fun attestationChain(
            installationId: String,
            expectedChallenge: ByteArray,
        ): List<ByteArray>? = chain

        override fun publicKeyDer(installationId: String): ByteArray = SPKI

        override fun sign(installationId: String, data: ByteArray): ByteArray {
            lastSigned = data
            return SIGNATURE
        }

        override fun deleteKey(installationId: String) = Unit

        companion object {
            val KEY_ID = "K".repeat(43)
            val SPKI = byteArrayOf(4, 5, 6)
            val SIGNATURE = byteArrayOf(1, 2, 3)
        }
    }

    private class ScriptedRegistrar(
        context: Context,
        keyStore: FakeKeyStore,
        outbox: ServiceEventOutbox,
        recipientSpki: ByteArray?,
        auth: PartnerEventRegistrar.PartnerAuth?,
        allowUnattested: Boolean,
    ) : PartnerEventRegistrar(
        appContext = context,
        functions = functionsInstance(),
        keyStore = keyStore,
        outbox = outbox,
        recipientPublicKeySpki = { recipientSpki },
        currentAuth = { auth },
        allowUnattested = allowUnattested,
        newRequestId = { FIXED_REQUEST_ID },
    ) {
        val challenges = mutableListOf<Map<String, Any>>()
        val registers = mutableListOf<Map<String, Any>>()
        val failures = ArrayDeque<FirebaseFunctionsException>()
        var challengeData: Any? = mapOf(
            "challengeId" to "cid-1",
            "challenge" to TEST_CHALLENGE,
            "expiresAt" to 1_000L,
        )
        var response: Any? = mapOf(
            "keyId" to FakeKeyStore.KEY_ID,
            "recipientKeyId" to RESPONSE_RECIPIENT_KEY_ID,
            "integrityTier" to "ACTIVE_UNATTESTED",
            "registrationRevision" to 1,
        )

        override suspend fun callChallenge(wire: Map<String, Any>): Any? {
            challenges += wire
            return challengeData
        }

        override suspend fun callRegister(wire: Map<String, Any>): Any? {
            registers += wire
            failures.removeFirstOrNull()?.let { throw it }
            return response
        }
    }

    private companion object {
        const val FIXED_REQUEST_ID = "0123456789abcdef0123456789abcdef"
        val RESPONSE_RECIPIENT_KEY_ID = "R".repeat(43)
        val RECIPIENT_SPKI = byteArrayOf(7, 7, 7)
        val TEST_CHALLENGE: String = PartnerEventProtocol.base64Url(ByteArray(32) { it.toByte() })

        // The super constructor needs a Firebase singleton; the scripted seams never
        // touch the network.
        fun functionsInstance(): FirebaseFunctions {
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
            return FirebaseFunctions.getInstance(app, FirebasePartnerDeliveryTransport.REGION)
        }
    }
}
