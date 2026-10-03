package com.example.myexodus.accountability.sos

import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOS plan §8.19 (Step 11): the block switch's repository — the §19.5 target-pair resolution
 * (same rule the sender and inbox use), a missing/failed read reading as no-row, and a write
 * that is exactly one document set (nothing else — no retroactive notification touch).
 *
 * Plain JVM: every seam is a function, so no Firebase or Android class is constructed.
 */
class NudgeSettingsRepositoryTest {

    private class Harness {
        var enrollments: List<EnrollmentRecord> = listOf(enrollment("pair-1", 100, "Sam"))
        var selected: String? = null
        var uid: String? = "uid-1"
        var blockedOnServer = false
        var readFailure = false
        var writeFailure = false
        val reads = mutableListOf<Pair<String, String>>()
        val writes = mutableListOf<Triple<String, String, Boolean>>()

        val repository = NudgeSettingsRepository(
            enrollments = { enrollments },
            selectedPairId = { selected },
            ensureUid = { uid },
            readBlocked = { pairId, readUid ->
                reads += pairId to readUid
                if (readFailure) throw IllegalStateException("offline") else blockedOnServer
            },
            writeBlocked = { pairId, writeUid, blocked ->
                writes += Triple(pairId, writeUid, blocked)
                if (writeFailure) throw IllegalStateException("denied")
            },
        )
    }

    // --- load: target resolution + honest no-row cases ---------------------------------------

    @Test
    fun load_noEnrollment_isNull_withoutReading() = runTest {
        val h = Harness().apply { enrollments = emptyList() }
        assertNull(h.repository.load())
        assertTrue("nothing to configure means no network read", h.reads.isEmpty())
    }

    @Test
    fun load_noUid_isNull_withoutReading() = runTest {
        val h = Harness().apply { uid = null }
        assertNull(h.repository.load())
        assertTrue(h.reads.isEmpty())
    }

    @Test
    fun load_resolvesTheSharedTargetRule_pickWhenLiveElseNewest() = runTest {
        val h = Harness().apply {
            enrollments = listOf(enrollment("pair-old", 100, "Old"), enrollment("pair-new", 200, "New"))
            blockedOnServer = true
        }
        val fallback = h.repository.load()
        assertEquals("no pick -> newest enrollment", "pair-new", fallback?.pairId)
        assertEquals("pair-new", h.reads.last().first)
        assertEquals("the read is scoped to the owner uid", "uid-1", h.reads.last().second)
        assertEquals("New", fallback?.partnerName)
        assertTrue("server state is reported as-is", fallback?.blocked == true)

        h.selected = "pair-old"
        val picked = h.repository.load()
        assertEquals("pick names a live enrollment", "pair-old", picked?.pairId)
        assertEquals("Old", picked?.partnerName)

        h.selected = "pair-gone"
        assertEquals("stale pick -> newest", "pair-new", h.repository.load()?.pairId)
    }

    @Test
    fun load_readFailure_isNull_neverAFabricatedDefault() = runTest {
        val h = Harness().apply { readFailure = true }
        assertNull(h.repository.load())
    }

    // --- setBlocked: one document, honest outcome --------------------------------------------

    @Test
    fun setBlocked_writesTheResolvedPairAndOwnerUid() = runTest {
        val h = Harness()
        val settings = h.repository.load()!!
        assertTrue(h.repository.setBlocked(settings, blocked = true))
        assertEquals(listOf(Triple("pair-1", "uid-1", true)), h.writes)

        assertTrue(h.repository.setBlocked(settings, blocked = false))
        assertEquals(Triple("pair-1", "uid-1", false), h.writes.last())
    }

    @Test
    fun setBlocked_writeFailure_returnsFalse() = runTest {
        val h = Harness().apply { writeFailure = true }
        val settings = h.repository.load()!!
        assertEquals(false, h.repository.setBlocked(settings, blocked = true))
    }

    @Test
    fun setBlocked_noUid_false_withoutWriting() = runTest {
        val h = Harness()
        val settings = h.repository.load()!!
        h.uid = null
        assertEquals(false, h.repository.setBlocked(settings, blocked = true))
        assertTrue("a uid-less write must never reach Firestore", h.writes.isEmpty())
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
