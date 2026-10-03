package com.example.myexodus.ui.settings

import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.sos.NudgeSettingsRepository
import com.example.myexodus.ui.home.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * SOS plan §8.19 (Step 11): the block switch's lifecycle — refresh populates or hides the row,
 * the toggle flips optimistically and converges on the committed value, a failed write reverts
 * with the error flag, and no path can double-write or write before a load.
 */
class NudgeSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class Harness {
        var noEnrollment = false
        var blockedOnServer = false
        var writeResult = true
        var writeGate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Pair<String, Boolean>>()

        val repository = NudgeSettingsRepository(
            enrollments = {
                if (noEnrollment) emptyList() else listOf(enrollment("pair-1", 100, "Sam"))
            },
            selectedPairId = { null },
            ensureUid = { "uid-1" },
            readBlocked = { _, _ -> blockedOnServer },
            writeBlocked = { pairId, _, blocked ->
                writeGate?.await()
                writes += pairId to blocked
                if (!writeResult) throw IllegalStateException("denied")
            },
        )

        fun vm() = NudgeSettingsViewModel(repository)
    }

    @Test
    fun refresh_populatesTheRow() = runTest {
        val h = Harness()
        val vm = h.vm()
        vm.refresh()
        advanceUntilIdle()
        assertEquals("Sam", vm.state.value?.partnerName)
        assertEquals(false, vm.state.value?.blocked)
    }

    @Test
    fun refresh_noPartner_hidesTheRow() = runTest {
        val h = Harness().apply { noEnrollment = true }
        val vm = h.vm()
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value)
    }

    @Test
    fun setBlocked_optimisticFlip_thenCommitted() = runTest {
        val h = Harness()
        val vm = h.vm()
        vm.refresh()
        advanceUntilIdle()

        vm.setBlocked(true)
        advanceUntilIdle()
        assertEquals(true, vm.state.value?.blocked)
        assertEquals(false, vm.state.value?.saving)
        assertEquals(false, vm.state.value?.saveFailed)
        assertEquals(listOf("pair-1" to true), h.writes)
    }

    @Test
    fun setBlocked_failure_revertsAndRaisesTheErrorLine() = runTest {
        val h = Harness().apply { writeResult = false }
        val vm = h.vm()
        vm.refresh()
        advanceUntilIdle()

        vm.setBlocked(true)
        advanceUntilIdle()
        assertEquals("a failed write must revert the switch", false, vm.state.value?.blocked)
        assertEquals(true, vm.state.value?.saveFailed)
        assertEquals(false, vm.state.value?.saving)
    }

    @Test
    fun setBlocked_beforeLoad_isANoOp() = runTest {
        val h = Harness()
        val vm = h.vm()
        vm.setBlocked(true)
        advanceUntilIdle()
        assertEquals(0, h.writes.size)
        assertNull(vm.state.value)
    }

    @Test
    fun setBlocked_whileSaving_isIgnored() = runTest {
        val h = Harness().apply { writeGate = CompletableDeferred() }
        val vm = h.vm()
        vm.refresh()
        advanceUntilIdle()

        vm.setBlocked(true)
        advanceUntilIdle()
        assertEquals(true, vm.state.value?.saving)
        vm.setBlocked(false)
        advanceUntilIdle()
        assertEquals("a second tap while the first write is in flight must not write", 0, h.writes.size)

        h.writeGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals("only the first tap's value ever reaches Firestore", listOf("pair-1" to true), h.writes)
        assertEquals(true, vm.state.value?.blocked)
        assertEquals(false, vm.state.value?.saving)
    }
}

private fun enrollment(pairId: String, createdAt: Long, label: String?) = EnrollmentRecord(
    pairId = pairId,
    mySenderKeyId = "sender-$pairId",
    partnerSigningKeyId = "partner-$pairId",
    partnerSigningPubKey = ByteArray(0),
    partnerAgreementPubKey = ByteArray(0),
    createdAtEpochMillis = createdAt,
    partnerLabel = label,
)
