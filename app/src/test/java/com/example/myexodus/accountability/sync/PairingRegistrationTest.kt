package com.example.myexodus.accountability.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.enrollment.PairingProof
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §19.4 item 3, client half: the §9 same-requestId retry policy on the registerPairing
 * transport. The network seam is stubbed ([PairingRegistration.callRegisterPairing]), so
 * these are pure JVM checks of the retry decision and of wire-payload stability — the
 * server-side ordering itself is covered by the node guard tests and joins the emulator
 * suite in task #42.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingRegistrationTest {

    @Test
    fun ambiguousTransportFailure_isRetriedOnceWithTheIdenticalPayload() = runTest {
        val transport = ScriptedTransport(
            mutableListOf(unavailable(), activeData(pairId = "uid-a_uid-b", pairingEpoch = 3)),
        )

        val outcome = transport.submit(proof())

        assertEquals(PairingRegistration.SubmitResult.Registered("uid-a_uid-b", 3), outcome)
        assertEquals("exactly one retry", 2, transport.wires.size)
        // The retry must replay the identical request: same requestId, same requestHash
        // inputs — anything else and the server's idempotency lookup would reject it.
        assertEquals(transport.wires[0], transport.wires[1])
        val requestId = transport.wires[0]["requestId"] as String
        assertTrue(
            "requestId must satisfy the server's doc-id-safe pattern",
            Regex("[A-Za-z0-9_-]{8,64}").matches(requestId),
        )
    }

    @Test
    fun serverVerdict_isNotRetried() = runTest {
        val transport = ScriptedTransport(
            mutableListOf(verdict("TRANSCRIPT_MISMATCH", FirebaseFunctionsException.Code.FAILED_PRECONDITION)),
        )

        val outcome = transport.submit(proof())

        assertEquals(PairingRegistration.SubmitResult.Rejected("TRANSCRIPT_MISMATCH"), outcome)
        assertEquals("a function verdict is terminal", 1, transport.wires.size)
    }

    @Test
    fun ambiguousTwice_givesUpAfterOneRetry_asTransientNotVerdict() = runTest {
        val transport = ScriptedTransport(mutableListOf(unavailable(), unavailable()))

        val outcome = transport.submit(proof())

        assertTrue(outcome is PairingRegistration.SubmitResult.Failed)
        assertTrue(
            "the message names the transport, not a server verdict",
            (outcome as PairingRegistration.SubmitResult.Failed).message.contains("UNAVAILABLE"),
        )
        assertEquals("bounded: never a third attempt", 2, transport.wires.size)
    }

    @Test
    fun rateLimitedIsAVerdict_andNotRetried() = runTest {
        val transport = ScriptedTransport(
            mutableListOf(verdict("RATE_LIMITED", FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED)),
        )

        val outcome = transport.submit(proof())

        assertEquals(PairingRegistration.SubmitResult.Rejected("RATE_LIMITED"), outcome)
        assertEquals(1, transport.wires.size)
    }

    private class ScriptedTransport(
        private val outcomes: MutableList<Any>,
    ) : PairingRegistration(ApplicationProvider.getApplicationContext(), functionsInstance()) {

        val wires = mutableListOf<Map<String, Any>>()

        override suspend fun callRegisterPairing(wire: Map<String, Any>): Any? {
            wires += wire
            return when (val outcome = outcomes.removeAt(0)) {
                is Exception -> throw outcome
                else -> outcome
            }
        }
    }

    private companion object {
        fun proof(requestId: String = UUID.randomUUID().toString()) = PairingProof(
            ownUid = "uid-a",
            partnerUid = "uid-b",
            publicKeyHash = "00".repeat(32),
            transcriptHash = "11".repeat(32),
            protocolVersion = PairingProof.PROTOCOL_VERSION,
            signingPublicKey = byteArrayOf(1),
            signature = byteArrayOf(2),
            attestationChain = listOf(byteArrayOf(3)),
            attestationChallenge = byteArrayOf(4),
            requestId = requestId,
        )

        fun unavailable() = FunctionErrors.of(
            "UNAVAILABLE",
            FirebaseFunctionsException.Code.UNAVAILABLE,
        )

        fun verdict(message: String, code: FirebaseFunctionsException.Code) =
            FunctionErrors.of(message, code)

        fun activeData(pairId: String, pairingEpoch: Int) =
            mapOf("status" to "active", "pairId" to pairId, "pairingEpoch" to pairingEpoch)

        // The super constructor needs a real Firebase singleton; the scripted transport
        // never touches the network.
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
            return FirebaseFunctions.getInstance(app, PairingRegistration.REGION)
        }
    }
}
