package com.example.myexodus.accountability.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.enrollment.PairingProof
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.util.UUID
import kotlinx.coroutines.CancellationException
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

    @Test
    fun cancellation_isRethrown_neverClassifiedAsAnOutcome() = runTest {
        // A backed-out ceremony cancels the awaiting coroutine; that CE must not be
        // laundered into a Rejected/Failed "verdict" (the caller would then discard state
        // on the strength of a server answer that was never received).
        val transport = ScriptedTransport(mutableListOf(CancellationException("scope cancelled")))

        var propagated = false
        try {
            transport.submit(proof())
        } catch (e: CancellationException) {
            propagated = true
        }

        assertTrue("cancellation must propagate out of submit", propagated)
        assertEquals("no retry after cancellation", 1, transport.wires.size)
    }

    // --- parse() contract against the deployed function's response shape
    // (functions/index.js returns { pairId, status: 'active'|'pending', pairingEpoch }). ---

    @Test
    fun pendingResponse_mapsToAwaitingPartner() = runTest {
        // The two-sided handshake's first-half signal: our proof stored, partner's not in yet.
        val transport = ScriptedTransport(mutableListOf(mapOf("status" to "pending")))

        val outcome = transport.submit(proof())

        assertEquals(PairingRegistration.SubmitResult.AwaitingPartner, outcome)
        assertEquals(1, transport.wires.size)
    }

    @Test
    fun activeResponseMissingPairIdOrEpoch_isFailed_notRegistered() = runTest {
        val noEpoch = ScriptedTransport(
            mutableListOf(mapOf("status" to "active", "pairId" to "uid-a_uid-b")),
        )
        assertTrue(noEpoch.submit(proof()) is PairingRegistration.SubmitResult.Failed)

        val noPairId = ScriptedTransport(
            mutableListOf(mapOf("status" to "active", "pairingEpoch" to 3)),
        )
        assertTrue(noPairId.submit(proof()) is PairingRegistration.SubmitResult.Failed)
    }

    @Test
    fun malformedOrUnexpectedResponse_isFailed() = runTest {
        val nonMap = ScriptedTransport(mutableListOf<Any?>("garbage"))
        assertTrue(nonMap.submit(proof()) is PairingRegistration.SubmitResult.Failed)

        val nullData = ScriptedTransport(mutableListOf<Any?>(null))
        assertTrue(nullData.submit(proof()) is PairingRegistration.SubmitResult.Failed)

        val unknownStatus = ScriptedTransport(mutableListOf(mapOf("status" to "something-else")))
        assertTrue(unknownStatus.submit(proof()) is PairingRegistration.SubmitResult.Failed)
    }

    @Test
    fun ambiguousThenVerdict_surfacesTheRetrysVerdict() = runTest {
        // §9 replay in the wild: the first call committed but its response was lost, so the
        // same-requestId retry replays the committed outcome — here, an already-active pair.
        val transport = ScriptedTransport(
            mutableListOf(
                unavailable(),
                verdict("PAIRING_ACTIVE", FirebaseFunctionsException.Code.FAILED_PRECONDITION),
            ),
        )

        val outcome = transport.submit(proof())

        assertEquals(PairingRegistration.SubmitResult.Rejected("PAIRING_ACTIVE"), outcome)
        assertEquals("identical replay", transport.wires[0], transport.wires[1])
        assertEquals(2, transport.wires.size)
    }

    @Test
    fun ambiguousThenGenericFailure_isFailed() = runTest {
        val transport = ScriptedTransport(
            mutableListOf(unavailable(), RuntimeException("serializer blew up")),
        )

        val outcome = transport.submit(proof())

        assertTrue(outcome is PairingRegistration.SubmitResult.Failed)
        assertEquals(2, transport.wires.size)
    }

    @Test
    fun cancellationDuringTheRetry_isRethrown() = runTest {
        val transport = ScriptedTransport(
            mutableListOf(unavailable(), CancellationException("backed out during retry")),
        )

        var propagated = false
        try {
            transport.submit(proof())
        } catch (e: CancellationException) {
            propagated = true
        }

        assertTrue("the retry's CE must propagate too", propagated)
        assertEquals(2, transport.wires.size)
    }

    private class ScriptedTransport(
        private val outcomes: MutableList<out Any?>,
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
