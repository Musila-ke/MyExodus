package com.example.myexodus.accountability.state

import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §8 Step 17 (AD-22, NFR7): wipe detection is key-present + enrollment-absent, idempotent,
 *  and never lets a single failure take down app startup or the clear it owes. */
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
        keyPresent: Boolean,
        enrollments: List<EnrollmentRecord> = emptyList(),
        enrollmentReadThrows: Boolean = false,
        signalThrows: Boolean = false,
        wipeThrows: Boolean = false,
    ) {
        var clears = 0

        val detector = LocalWipeDetector(
            hasPriorPairingSignal = {
                if (signalThrows) throw IllegalStateException("keystore unavailable") else keyPresent
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
        val h = Harness(keyPresent = false)
        assertFalse(h.detector.detect())
        assertEquals(0, h.clears)
    }

    @Test
    fun liveEnrollment_wins_evenWithAStrayKey() = runTest {
        // E.g. a re-pair landed a new enrollment while an old key lingers: not a wipe.
        val h = Harness(keyPresent = true, enrollments = listOf(enrollment()))
        assertFalse(h.detector.detect())
        assertEquals(0, h.clears)
    }

    @Test
    fun keyPresent_noEnrollment_detectsAndClearsOrphanedData() = runTest {
        val h = Harness(keyPresent = true, enrollments = emptyList())
        assertTrue(h.detector.detect())
        assertEquals(1, h.clears)
    }

    @Test
    fun unopenableDatabase_isTheWipedOrWorseCase_reportedWithoutClearing() = runTest {
        val h = Harness(keyPresent = true, enrollmentReadThrows = true)
        assertTrue(h.detector.detect())
        // Nothing could be read to clear; the report still goes out (re-derived next start).
        assertEquals(0, h.clears)
    }

    @Test
    fun signalCheckFailure_neverCrashesStartup_andNeverFalselyReports() = runTest {
        val h = Harness(keyPresent = true, signalThrows = true)
        assertFalse(h.detector.detect())
        assertEquals(0, h.clears)
    }

    @Test
    fun clearFailure_stillReports_andReDetectsOnTheNextStart() = runTest {
        val h = Harness(keyPresent = true, wipeThrows = true)
        assertTrue(h.detector.detect())
        assertEquals(1, h.clears)
        assertTrue(h.detector.detect())
        assertEquals(2, h.clears)
    }

    @Test
    fun rePair_stopsReporting() = runTest {
        val h = Harness(keyPresent = true)
        assertTrue(h.detector.detect())
        val rePaired = Harness(keyPresent = true, enrollments = listOf(enrollment()))
        assertFalse(rePaired.detector.detect())
    }
}
