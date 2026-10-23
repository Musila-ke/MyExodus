package com.example.myexodus.accountability.state

import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §8 Step 17 (AD-22, NFR7): wipe detection is canary-present + enrollment-absent, idempotent,
 *  and never lets a single failure take down app startup or the clear it owes. The canary is
 *  minted only by a durable pairing (PairingFlow's markPairingCompleted), so an aborted
 *  ceremony — which does mint identity keys — can never read as a wipe. */
class LocalWipeDetectorTest {

    private fun enrollment() = EnrollmentRecord(
        pairId = "pair-1",
        mySenderKeyId = "my-key",
        partnerSigningKeyId = "partner-key",
        partnerSigningPubKey = byteArrayOf(1),
        partnerAgreementPubKey = byteArrayOf(2),
        createdAtEpochMillis = 1_000L,
        mailboxWatermarkTsEpochMillis = null,
    )

    private class Harness(
        canaryPresent: Boolean,
        enrollments: List<EnrollmentRecord> = emptyList(),
        enrollmentReadThrows: Boolean = false,
        signalThrows: Boolean = false,
        wipeThrows: Boolean = false,
    ) {
        var clears = 0

        val detector = LocalWipeDetector(
            hasPriorPairingSignal = {
                if (signalThrows) throw IllegalStateException("keystore unavailable") else canaryPresent
            },
            enrollments = {
                if (enrollmentReadThrows) throw IllegalStateException("db unopenable") else enrollments
            },
            wipeOrphanPairData = {
                clears++
                if (wipeThrows) throw IllegalStateException("db unopenable")
            },
        )
    }

    @Test
    fun noKeystoreSignal_neverDetects_neverClears() = runTest {
        val h = Harness(canaryPresent = false)
        assertFalse(h.detector.detect())
        assertEquals(0, h.clears)
    }

    @Test
    fun liveEnrollment_wins_evenWithAStrayKey() = runTest {
        // E.g. a re-pair landed a new enrollment while an old key lingers: not a wipe.
        val h = Harness(canaryPresent = true, enrollments = listOf(enrollment()))
        assertFalse(h.detector.detect())
        assertEquals(0, h.clears)
    }

    @Test
    fun canaryPresent_noEnrollment_detectsAndClearsOrphanedData() = runTest {
        val h = Harness(canaryPresent = true, enrollments = emptyList())
        assertTrue(h.detector.detect())
        assertEquals(1, h.clears)
    }

    @Test
    fun unopenableDatabase_isTheWipedOrWorseCase_reportedWithoutClearing() = runTest {
        val h = Harness(canaryPresent = true, enrollmentReadThrows = true)
        assertTrue(h.detector.detect())
        // Nothing could be read to clear; the report still goes out (re-derived next start).
        assertEquals(0, h.clears)
    }

    @Test
    fun signalCheckFailure_neverCrashesStartup_andNeverFalselyReports() = runTest {
        val h = Harness(canaryPresent = true, signalThrows = true)
        assertFalse(h.detector.detect())
        assertEquals(0, h.clears)
    }

    @Test
    fun clearFailure_stillReports_andReDetectsOnTheNextStart() = runTest {
        val h = Harness(canaryPresent = true, wipeThrows = true)
        assertTrue(h.detector.detect())
        assertEquals(1, h.clears)
        assertTrue(h.detector.detect())
        assertEquals(2, h.clears)
    }

    @Test
    fun rePair_stopsReporting() = runTest {
        val h = Harness(canaryPresent = true)
        assertTrue(h.detector.detect())
        val rePaired = Harness(canaryPresent = true, enrollments = listOf(enrollment()))
        assertFalse(rePaired.detector.detect())
    }
}
