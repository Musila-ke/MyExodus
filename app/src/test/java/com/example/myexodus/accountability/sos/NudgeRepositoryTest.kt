package com.example.myexodus.accountability.sos

import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.sync.FunctionErrors
import com.google.firebase.functions.FirebaseFunctionsException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SOS plan §12.2 (Step 8): the sender transport — offline makes no call and encrypts nothing,
 * the seal binds the exact plan AAD, a retry replays the byte-identical envelope (no second
 * encryption), and every callable outcome maps to the §8.15 state contract.
 *
 * Robolectric because `FirebaseFunctionsException.Code`'s static initializer builds an
 * `android.util.SparseArray` (same reason PairingRegistrationTest runs under it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NudgeRepositoryTest {

    private class Harness {
        val cek = ByteArray(32) { (it + 1).toByte() }
        val calls = mutableListOf<Map<String, Any>>()
        var online = true
        var enrollFailure = false
        var enrollments: List<EnrollmentRecord> = listOf(enrollment("pair-1", 100))
        var selected: String? = null
        var uid = "uid-1"
        var uidFailure = false
        var cekFailure = false
        var deriveCekCount = 0
        var nextOutcome: (suspend (Map<String, Any>) -> Any?)? = null

        val cipher = EventCipher()
        val repository = NudgeRepository(
            enrollments = { if (enrollFailure) throw IllegalStateException("db gone") else enrollments },
            selectedPairId = { selected },
            ensureUid = { if (uidFailure) throw IllegalStateException("no uid") else uid },
            deriveCek = {
                deriveCekCount++
                if (cekFailure) throw IllegalStateException("no key") else cek
            },
            callSendNudge = { wire ->
                calls += wire
                // Elvis here would mask a scripted null body — it means "server returned
                // something malformed", which is exactly a case under test.
                if (nextOutcome != null) nextOutcome!!.invoke(wire) else mapOf("status" to "accepted")
            },
            validatedNetwork = { online },
            cipher = cipher,
        )
    }

    private fun deferred(h: Harness, code: FirebaseFunctionsException.Code, message: String = "e") {
        h.nextOutcome = { throw FunctionErrors.of(message, code) }
    }

    // --- FR-3 offline: no call, no encryption ----------------------------------------------

    @Test
    fun offline_sendNew_isNotSentOffline_withNoCallAndNoEncryption() = runTest {
        val h = Harness().apply { online = false }
        assertEquals(NudgeRepository.SendResult.NotSentOffline, h.repository.sendNew("n1"))
        assertTrue(h.calls.isEmpty())
        assertEquals("offline must not even derive a key or encrypt", 0, h.deriveCekCount)
    }

    @Test
    fun offline_resend_isNotSentOffline_withNoCall() = runTest {
        val h = Harness().apply { online = false }
        val pending = NudgeRepository.PendingNudge("pair-1", "n1", "envelope")
        assertEquals(NudgeRepository.SendResult.NotSentOffline, h.repository.resend(pending))
        assertTrue(h.calls.isEmpty())
    }

    // --- accepted path: wire shape, exact AAD, exact plaintext ------------------------------

    @Test
    fun accepted_callsSendNudgeWithExactlyTheWireContract() = runTest {
        val h = Harness()
        assertEquals(NudgeRepository.SendResult.Accepted, h.repository.sendNew("n1"))
        val wire = h.calls.single()
        assertEquals(setOf("pairId", "nudgeId", "envelope"), wire.keys)
        assertEquals("pair-1", wire["pairId"])
        assertEquals("n1", wire["nudgeId"])
    }

    @Test
    fun envelope_isSealedUnderTheExactPlanAadAndConstantPlaintext() = runTest {
        val h = Harness()
        h.repository.sendNew("n1")
        val envelope = h.calls.single()["envelope"] as String
        // Decrypt with the AAD the plan fixes — succeeds only if the sender used exactly it.
        val plaintext = EventCipher().decryptWithAad(
            h.cek,
            NudgeEnvelope.aad("pair-1", "n1", "uid-1"),
            Base64.getDecoder().decode(envelope),
        )
        assertEquals("""{"v":1,"kind":"nudge"}""", plaintext.toString(Charsets.UTF_8))
    }

    @Test
    fun sendNew_carriesTheMessage_insideTheCiphertext_onlyBase64OnTheWire() = runTest {
        val h = Harness()
        h.repository.sendNew("n1", "Please call me")
        val wire = h.calls.single()
        val envelope = wire["envelope"] as String
        assertTrue(
            "the message never appears in the wire body",
            wire.values.none { it.toString().contains("Please call me") },
        )
        val plaintext = EventCipher().decryptWithAad(
            h.cek,
            NudgeEnvelope.aad("pair-1", "n1", "uid-1"),
            Base64.getDecoder().decode(envelope),
        )
        assertEquals("Please call me", NudgeEnvelope.messageOf(plaintext))
    }

    // --- retry: byte-identical replay, never a second encryption ----------------------------

    @Test
    fun retryOfAmbiguousAttempt_replaysByteIdenticalEnvelope_withoutReencrypting() = runTest {
        val h = Harness()
        h.nextOutcome = { throw RuntimeException("connection reset") }
        val first = h.repository.sendNew("n1")
        val pending = (first as NudgeRepository.SendResult.CouldNotConfirm).pending
        assertEquals("pair-1", pending.pairId)
        assertEquals("n1", pending.nudgeId)

        h.nextOutcome = null
        assertEquals(NudgeRepository.SendResult.Accepted, h.repository.resend(pending))
        assertEquals("byte-identical replay under the same nudgeId", h.calls[0], h.calls[1])
        assertEquals("retry must never re-derive the CEK or re-encrypt", 1, h.deriveCekCount)
    }

    // --- callable error mapping (§8.15) ------------------------------------------------------

    @Test
    fun rateLimited_cooldown_parsesReasonAndRetryAfter() = runTest {
        val h = Harness()
        h.nextOutcome = {
            throw FunctionErrors.of(
                "rate_limited",
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
                mapOf("reason" to "cooldown", "retryAfterSeconds" to 42),
            )
        }
        val r = h.repository.sendNew("n1") as NudgeRepository.SendResult.RateLimited
        assertEquals(NudgeRepository.RateLimitReason.COOLDOWN, r.reason)
        assertEquals(42L, r.retryAfterSeconds)
    }

    @Test
    fun rateLimited_dailyCap_parsesReason() = runTest {
        val h = Harness()
        h.nextOutcome = {
            throw FunctionErrors.of(
                "rate_limited",
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
                mapOf("reason" to "daily_cap", "retryAfterSeconds" to 0),
            )
        }
        val r = h.repository.sendNew("n1") as NudgeRepository.SendResult.RateLimited
        assertEquals(NudgeRepository.RateLimitReason.DAILY_CAP, r.reason)
    }

    @Test
    fun rateLimited_unknownReasonOrMissingDetails_degradesToUnknown() = runTest {
        val unknown = Harness().apply {
            nextOutcome = {
                throw FunctionErrors.of(
                    "rate_limited",
                    FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
                    mapOf("reason" to "mystery", "retryAfterSeconds" to 7),
                )
            }
        }
        val r1 = unknown.repository.sendNew("n1") as NudgeRepository.SendResult.RateLimited
        assertEquals(NudgeRepository.RateLimitReason.UNKNOWN, r1.reason)
        assertEquals(7L, r1.retryAfterSeconds)

        val bare = Harness()
        bare.nextOutcome = { throw FunctionErrors.of("rate_limited", FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) }
        val r2 = bare.repository.sendNew("n1") as NudgeRepository.SendResult.RateLimited
        assertEquals(NudgeRepository.RateLimitReason.UNKNOWN, r2.reason)
        assertEquals(0L, r2.retryAfterSeconds)
    }

    @Test
    fun resourceExhaustedWithoutRateLimitedMessage_isAmbiguous() = runTest {
        val h = Harness()
        deferred(h, FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED, "platform quota")
        assertTrue(h.repository.sendNew("n1") is NudgeRepository.SendResult.CouldNotConfirm)
    }

    @Test
    fun serverRefusals_mapToUnavailable() = runTest {
        for (code in listOf(
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.INVALID_ARGUMENT,
        )) {
            val h = Harness()
            deferred(h, code)
            assertEquals("code=$code", NudgeRepository.SendResult.Unavailable, h.repository.sendNew("n1"))
        }
    }

    @Test
    fun transportAmbiguousCodes_mapToCouldNotConfirmWithPendingIdentity() = runTest {
        for (code in listOf(
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
            FirebaseFunctionsException.Code.INTERNAL,
        )) {
            val h = Harness()
            deferred(h, code)
            val r = h.repository.sendNew("n1") as NudgeRepository.SendResult.CouldNotConfirm
            assertEquals("code=$code", "pair-1", r.pending.pairId)
            assertEquals("code=$code", "n1", r.pending.nudgeId)
        }
    }

    @Test
    fun plainException_isAmbiguousNotUnavailable() = runTest {
        val h = Harness()
        h.nextOutcome = { throw IllegalStateException("socket closed") }
        assertTrue(h.repository.sendNew("n1") is NudgeRepository.SendResult.CouldNotConfirm)
    }

    @Test
    fun cancellation_isRethrownNeverSwallowed() = runTest {
        val h = Harness()
        h.nextOutcome = { throw CancellationException("caller left") }
        val thrown = runCatching { h.repository.sendNew("n1") }.exceptionOrNull()
        assertTrue("CancellationException must propagate, got $thrown", thrown is CancellationException)
    }

    @Test
    fun malformedSuccessBody_isAmbiguousNeverAccepted() = runTest {
        for (bad in listOf(null, "accepted", 7, mapOf("status" to "queued"), mapOf("stat" to "accepted"))) {
            val h = Harness()
            h.nextOutcome = { bad }
            assertTrue("body=$bad", h.repository.sendNew("n1") is NudgeRepository.SendResult.CouldNotConfirm)
        }
    }

    // --- target pair resolution (§19.5 rule, server re-verifies) ----------------------------

    @Test
    fun targetPair_prefersTheSelectedLiveEnrollment_elseNewest() = runTest {
        val h = Harness().apply {
            enrollments = listOf(enrollment("pair-old", 100), enrollment("pair-new", 200))
        }
        h.repository.sendNew("n1")
        assertEquals("no pick -> newest enrollment", "pair-new", h.calls.last()["pairId"])

        h.selected = "pair-old"
        h.repository.sendNew("n2")
        assertEquals("pick names a live enrollment", "pair-old", h.calls.last()["pairId"])

        h.selected = "pair-gone"
        h.repository.sendNew("n3")
        assertEquals("stale pick -> newest enrollment", "pair-new", h.calls.last()["pairId"])
    }

    @Test
    fun explicitRecipient_isUsedVerbatim_evenWhenTheDefaultWouldDiffer() = runTest {
        val h = Harness().apply {
            enrollments = listOf(enrollment("pair-old", 100), enrollment("pair-new", 200))
            selected = "pair-new"
        }

        assertEquals(
            NudgeRepository.SendResult.Accepted,
            h.repository.sendNew("n1", targetPairId = "pair-old"),
        )
        assertEquals("pair-old", h.calls.single()["pairId"])
    }

    @Test
    fun explicitRecipientThatIsGone_isUnavailable_neverRedirected() = runTest {
        val h = Harness().apply {
            enrollments = listOf(enrollment("pair-new", 200))
        }

        assertEquals(
            NudgeRepository.SendResult.Unavailable,
            h.repository.sendNew("n1", targetPairId = "pair-gone"),
        )
        assertTrue("no silent redirect to another partner", h.calls.isEmpty())
    }

    @Test
    fun noActivePair_isUnavailable_withNoCall() = runTest {
        val h = Harness().apply { enrollments = emptyList() }
        assertEquals(NudgeRepository.SendResult.Unavailable, h.repository.sendNew("n1"))
        assertTrue(h.calls.isEmpty())
    }

    @Test
    fun localFailuresBeforeTheCall_areUnavailable_withNoCall() = runTest {
        val enroll = Harness().apply { enrollFailure = true }
        assertEquals(NudgeRepository.SendResult.Unavailable, enroll.repository.sendNew("n1"))

        val uid = Harness().apply { uidFailure = true }
        assertEquals(NudgeRepository.SendResult.Unavailable, uid.repository.sendNew("n1"))

        val cek = Harness().apply { cekFailure = true }
        assertEquals(NudgeRepository.SendResult.Unavailable, cek.repository.sendNew("n1"))

        for (h in listOf(enroll, uid, cek)) assertTrue(h.calls.isEmpty())
    }

    @Test
    fun accepted_returnsTheResultObjectFromTheServerNotFromLocalState() = runTest {
        val h = Harness()
        assertSame(NudgeRepository.SendResult.Accepted, h.repository.sendNew("n1"))
        assertNotNull(h.calls.single())
    }
}

private fun enrollment(pairId: String, createdAt: Long) = EnrollmentRecord(
    pairId = pairId,
    mySenderKeyId = "sender-$pairId",
    partnerSigningKeyId = "partner-$pairId",
    partnerSigningPubKey = ByteArray(0),
    partnerAgreementPubKey = ByteArray(0),
    createdAtEpochMillis = createdAt,
)
