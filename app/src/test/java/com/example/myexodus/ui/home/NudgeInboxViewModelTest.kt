package com.example.myexodus.ui.home

import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.sos.NudgeEnvelope
import com.example.myexodus.accountability.sos.NudgeInboxRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * SOS plan §8.16 (Step 9): the recipient card's lifecycle — the listener attaches while Home
 * is visible, start is idempotent, stop detaches (later emissions must not repaint), and
 * acknowledge replays the card's own ids.
 */
class NudgeInboxViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Harness {
        val cek = ByteArray(32) { (it + 1).toByte() }
        val emissions = MutableSharedFlow<List<NudgeInboxRepository.NudgeDoc>>()
        var listenCalls = 0
        val ackCalls = mutableListOf<Pair<String, List<String>>>()

        val repository = NudgeInboxRepository(
            enrollments = { listOf(enrollment("pair-1", 100)) },
            selectedPairId = { null },
            ensureUid = { "uid-1" },
            deriveCek = { cek },
            listenNudges = { _, _ ->
                listenCalls++
                emissions
            },
            ackBatch = { pairId, ids -> ackCalls += pairId to ids },
            cancelNotifications = {},
            reportUnreadable = {},
            cipher = EventCipher(),
        )

        fun doc(nudgeId: String, createdAtMs: Long) = NudgeInboxRepository.NudgeDoc(
            nudgeId = nudgeId,
            senderUid = "sender-1",
            envelope = NudgeEnvelope.seal(EventCipher(), cek, "pair-1", nudgeId, "sender-1"),
            createdAtMs = createdAtMs,
            expiresAtMillis = Long.MAX_VALUE,
        )
    }

    @Test
    fun start_collectsTheCard_andStopDetaches() = runTest {
        val h = Harness()
        val vm = NudgeInboxViewModel(h.repository)

        vm.start()
        advanceUntilIdle()
        h.emissions.emit(listOf(h.doc("d1", createdAtMs = 500L)))
        advanceUntilIdle()
        assertEquals(500L, vm.card.value?.createdAtMs)

        vm.stop()
        advanceUntilIdle()
        h.emissions.emit(listOf(h.doc("d2", createdAtMs = 700L)))
        advanceUntilIdle()
        assertEquals("a stopped listener must not repaint the card", 500L, vm.card.value?.createdAtMs)
    }

    @Test
    fun start_isIdempotent_oneListenerPerHomeEntry() = runTest {
        val h = Harness()
        val vm = NudgeInboxViewModel(h.repository)
        vm.start()
        advanceUntilIdle()
        vm.start()
        advanceUntilIdle()
        assertEquals(1, h.listenCalls)
    }

    @Test
    fun acknowledge_withNoCard_doesNothing() = runTest {
        val h = Harness()
        val vm = NudgeInboxViewModel(h.repository)
        vm.start()
        advanceUntilIdle()
        vm.acknowledge()
        advanceUntilIdle()
        assertEquals(emptyList<Pair<String, List<String>>>(), h.ackCalls)
    }

    @Test
    fun acknowledge_replaysTheCardsPendingIds() = runTest {
        val h = Harness()
        val vm = NudgeInboxViewModel(h.repository)
        vm.start()
        advanceUntilIdle()
        h.emissions.emit(listOf(h.doc("d1", 500L), h.doc("d2", 600L)))
        advanceUntilIdle()

        vm.acknowledge()
        advanceUntilIdle()
        assertEquals(listOf("pair-1" to listOf("d1", "d2")), h.ackCalls)
    }

    @Test
    fun listenerFailure_degradesToNoCard_neverCrashes() = runTest {
        val h = Harness()
        val failing = NudgeInboxRepository(
            enrollments = { listOf(enrollment("pair-1", 100)) },
            selectedPairId = { null },
            ensureUid = { "uid-1" },
            deriveCek = { h.cek },
            listenNudges = { _, _ -> throw IllegalStateException("listener died") },
            ackBatch = { _, _ -> },
            cancelNotifications = {},
            reportUnreadable = {},
            cipher = EventCipher(),
        )
        val vm = NudgeInboxViewModel(failing)
        vm.start()
        advanceUntilIdle()
        assertNull(vm.card.value)
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
