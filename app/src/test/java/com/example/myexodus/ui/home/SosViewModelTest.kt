package com.example.myexodus.ui.home

import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.sos.NudgeEnvelope
import com.example.myexodus.accountability.sos.NudgeRepository
import com.example.myexodus.accountability.sync.FunctionErrors
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SOS plan §12.2 (Step 8): the Reach out state machine — every transition, taps ignored while
 * a send is in flight, the retry-stable `(nudgeId, envelope)` pair across timeouts, the 5 s
 * auto-returns, and the rate-limit copy formatting.
 *
 * Robolectric because `FirebaseFunctionsException.Code`'s static initializer touches
 * `android.util.SparseArray`. `runCurrent()` (not `advanceUntilIdle()`) is deliberate after
 * each tap: it runs the send without advancing virtual time past the 5 s auto-returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SosViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeSender {
        val cek = ByteArray(32) { (it + 1).toByte() }
        val calls = mutableListOf<Map<String, Any>>()
        var online = true
        var deriveCekCount = 0
        var outcome: (suspend (Map<String, Any>) -> Any?)? = null

        /** Lets a test hold the callable open while asserting the in-flight states. */
        var sendGate: CompletableDeferred<Unit>? = null

        val repository = NudgeRepository(
            enrollments = { listOf(enrollment("pair-1", 100)) },
            selectedPairId = { null },
            ensureUid = { "uid-1" },
            deriveCek = {
                deriveCekCount++
                cek
            },
            callSendNudge = { wire ->
                calls += wire
                sendGate?.await()
                if (outcome != null) outcome!!.invoke(wire) else mapOf("status" to "accepted")
            },
            validatedNetwork = { online },
            cipher = EventCipher(),
        )
    }

    private fun viewModel(sender: FakeSender) = SosViewModel(repository = sender.repository)

    private fun rateLimited(reason: String, retryAfterSeconds: Int) = FunctionErrors.of(
        "rate_limited",
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
        mapOf("reason" to reason, "retryAfterSeconds" to retryAfterSeconds),
    )

    // --- happy path + auto-return -------------------------------------------------------------

    @Test
    fun tap_sendsShowsSent_thenAutoReturnsToIdle() = runTest {
        val sender = FakeSender()
        val vm = viewModel(sender)
        advanceUntilIdle()

        vm.onTap()
        assertEquals(SosViewModel.SosSendState.Sending, vm.state.value)
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Sent, vm.state.value)
        assertEquals(1, sender.calls.size)

        advanceTimeBy(SosViewModel.RESULT_DISPLAY_MILLIS)
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Idle, vm.state.value)
    }

    @Test
    fun tapDuringSending_isIgnored() = runTest {
        val sender = FakeSender().apply { sendGate = CompletableDeferred() }
        val vm = viewModel(sender)
        advanceUntilIdle()

        vm.onTap()
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Sending, vm.state.value)

        vm.onTap()
        vm.onTap()
        runCurrent()
        assertEquals("taps while in flight must not start a second send", 1, sender.calls.size)
        assertEquals(SosViewModel.SosSendState.Sending, vm.state.value)

        sender.sendGate!!.complete(Unit)
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Sent, vm.state.value)
    }

    @Test
    fun tapWhileAResultIsShowing_isIgnored() = runTest {
        val sender = FakeSender()
        val vm = viewModel(sender)
        advanceUntilIdle()
        vm.onTap()
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Sent, vm.state.value)

        vm.onTap()
        runCurrent()
        assertEquals("Sent, RateLimited, Offline and Unavailable show a result, never a send", 1, sender.calls.size)
        assertEquals(SosViewModel.SosSendState.Sent, vm.state.value)
    }

    // --- ambiguous outcome: the retry-stable pair ---------------------------------------------

    @Test
    fun couldNotConfirm_doesNotAutoReturn_andRetryReplaysTheSameIdentity() = runTest {
        val sender = FakeSender().apply { outcome = { throw RuntimeException("connection reset") } }
        val vm = viewModel(sender)
        advanceUntilIdle()

        vm.onTap()
        runCurrent()
        assertEquals(SosViewModel.SosSendState.CouldNotConfirm, vm.state.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("only CouldNotConfirm may not auto-return", SosViewModel.SosSendState.CouldNotConfirm, vm.state.value)

        sender.outcome = null
        vm.onTap()
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Sent, vm.state.value)
        assertEquals("retry must replay the byte-identical request", sender.calls[0], sender.calls[1])
        assertEquals("no second encryption on retry", 1, sender.deriveCekCount)
    }

    @Test
    fun tap_carriesTheDialogMessage_andARetryIgnoresAMessageEdit() = runTest {
        val sender = FakeSender().apply { outcome = { throw RuntimeException("connection reset") } }
        val vm = viewModel(sender)
        advanceUntilIdle()

        vm.onTap("Please call me")
        runCurrent()
        assertEquals(SosViewModel.SosSendState.CouldNotConfirm, vm.state.value)

        // A "retry" whose dialog text was edited: the sealed envelope is authoritative —
        // the edit must never re-encrypt or leak into the replayed request.
        vm.onTap("edited text that must be ignored")
        runCurrent()
        sender.outcome = null
        vm.onTap("yet another edit")
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Sent, vm.state.value)
        assertEquals("every retry replays the original envelope verbatim", sender.calls[0], sender.calls[1])
        assertEquals(sender.calls[0], sender.calls[2])
        assertEquals("exactly one encryption: the fresh send", 1, sender.deriveCekCount)

        val wire = sender.calls.first()
        val plaintext = EventCipher().decryptWithAad(
            sender.cek,
            NudgeEnvelope.aad("pair-1", wire["nudgeId"] as String, "uid-1"),
            java.util.Base64.getDecoder().decode(wire["envelope"] as String),
        )
        assertEquals("Please call me", NudgeEnvelope.messageOf(plaintext))
    }

    @Test
    fun offlineRetryOfAnAmbiguousSend_keepsTheRetryIdentityAcrossTheOutage() = runTest {
        val sender = FakeSender().apply { outcome = { throw RuntimeException("connection reset") } }
        val vm = viewModel(sender)
        advanceUntilIdle()

        vm.onTap()
        runCurrent() // CouldNotConfirm, envelope retained

        sender.online = false
        vm.onTap()
        runCurrent()
        assertEquals(SosViewModel.SosSendState.NotSentOffline, vm.state.value)
        assertEquals("offline must not call the backend", 1, sender.calls.size)
        advanceTimeBy(SosViewModel.RESULT_DISPLAY_MILLIS)
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Idle, vm.state.value)

        sender.online = true
        sender.outcome = null
        vm.onTap()
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Sent, vm.state.value)
        assertEquals("back online: the original logical request replays", sender.calls[0], sender.calls[1])
    }

    // --- offline / unavailable / rate-limited --------------------------------------------------

    @Test
    fun offline_freshAttempt_isNotSentOffline_andAutoReturns() = runTest {
        val sender = FakeSender().apply { online = false }
        val vm = viewModel(sender)
        advanceUntilIdle()

        vm.onTap()
        runCurrent()
        assertEquals(SosViewModel.SosSendState.NotSentOffline, vm.state.value)
        assertTrue(sender.calls.isEmpty())

        advanceTimeBy(SosViewModel.RESULT_DISPLAY_MILLIS)
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Idle, vm.state.value)
    }

    @Test
    fun unavailable_showsAndClearsPending_soTheNextTapMintsFreshIdentity() = runTest {
        val sender = FakeSender().apply {
            outcome = { throw FunctionErrors.of("e", FirebaseFunctionsException.Code.INVALID_ARGUMENT) }
        }
        val vm = viewModel(sender)
        advanceUntilIdle()

        vm.onTap()
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Unavailable, vm.state.value)
        advanceTimeBy(SosViewModel.RESULT_DISPLAY_MILLIS)
        runCurrent()

        sender.outcome = null
        vm.onTap()
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Sent, vm.state.value)
        assertNotEquals(
            "a refused attempt must not be replayed as-is",
            sender.calls[0]["nudgeId"],
            sender.calls[1]["nudgeId"],
        )
        assertEquals("a fresh attempt encrypts anew", 2, sender.deriveCekCount)
    }

    @Test
    fun rateLimited_showsReasonAndRetryAfter_thenAutoReturns() = runTest {
        val sender = FakeSender().apply { outcome = { throw rateLimited("cooldown", 90) } }
        val vm = viewModel(sender)
        advanceUntilIdle()

        vm.onTap()
        runCurrent()
        assertEquals(
            SosViewModel.SosSendState.RateLimited(NudgeRepository.RateLimitReason.COOLDOWN, 90L),
            vm.state.value,
        )

        advanceTimeBy(SosViewModel.RESULT_DISPLAY_MILLIS)
        runCurrent()
        assertEquals(SosViewModel.SosSendState.Idle, vm.state.value)
    }

    @Test
    fun rateLimited_dailyCap_surfacesTheCapReason() = runTest {
        val sender = FakeSender().apply { outcome = { throw rateLimited("daily_cap", 0) } }
        val vm = viewModel(sender)
        advanceUntilIdle()
        vm.onTap()
        runCurrent()
        assertEquals(
            SosViewModel.SosSendState.RateLimited(NudgeRepository.RateLimitReason.DAILY_CAP, 0L),
            vm.state.value,
        )
    }

    // --- rate-limit copy formatting ------------------------------------------------------------

    @Test
    fun cooldownMinutes_neverShowsZero_roundsUp() {
        assertEquals(1, SosViewModel.cooldownMinutes(0))
        assertEquals(1, SosViewModel.cooldownMinutes(1))
        assertEquals(1, SosViewModel.cooldownMinutes(59))
        assertEquals(1, SosViewModel.cooldownMinutes(60))
        assertEquals(2, SosViewModel.cooldownMinutes(61))
        assertEquals(2, SosViewModel.cooldownMinutes(120))
        assertEquals(3, SosViewModel.cooldownMinutes(121))
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
