package com.example.myexodus.accountability.sos

import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOS plan §12.2 (Step 9): the recipient inbox — the §6.9 query's client semantics. Expired
 * docs are ignored (TTL lags; rules-test R-F2q pins that lists return them), only the newest
 * live doc is decrypted, an unreadable newest suppresses the card with one safe report and no
 * fallback, and the ack batch covers every fetched pending id with notifications cancelled
 * strictly after it commits.
 *
 * Plain JVM: every seam is a function or a Flow of the repository's own record type, so no
 * Firebase, keystore, or Android class is constructed.
 */
class NudgeInboxRepositoryTest {

    private val now = 1_000_000L

    private class Harness {
        val cek = ByteArray(32) { (it + 1).toByte() }
        val cipher = EventCipher()
        var uid: String? = "uid-1"
        var enrollments: List<EnrollmentRecord> = listOf(enrollment("pair-1", 100))
        var selected: String? = null
        var cekFailure = false
        var listenCalls = 0
        var lastListenedPair: String? = null
        var lastListenedUid: String? = null
        var emissions: Flow<List<NudgeInboxRepository.NudgeDoc>> = flowOf(emptyList())
        val ackCalls = mutableListOf<Pair<String, List<String>>>()
        val effects = mutableListOf<String>()
        var ackFailure = false
        val reports = mutableListOf<String>()

        val repository = NudgeInboxRepository(
            enrollments = { enrollments },
            selectedPairId = { selected },
            ensureUid = { uid },
            deriveCek = {
                if (cekFailure) throw IllegalStateException("no key") else cek
            },
            listenNudges = { pairId, listenedUid ->
                listenCalls++
                lastListenedPair = pairId
                lastListenedUid = listenedUid
                emissions
            },
            ackBatch = { pairId, ids ->
                effects += "batch"
                if (ackFailure) throw IllegalStateException("offline")
                ackCalls += pairId to ids
            },
            cancelNotifications = { effects += "cancel" },
            reportUnreadable = { reports += it },
            cipher = cipher,
            now = { 1_000_000L },
        )

        fun doc(
            nudgeId: String,
            createdAtMs: Long = 900_000L,
            expiresAtMillis: Long? = 2_000_000L,
            senderUid: String? = "sender-1",
            envelope: String? = null,
        ) = NudgeInboxRepository.NudgeDoc(
            nudgeId = nudgeId,
            senderUid = senderUid,
            envelope = envelope ?: NudgeEnvelope.seal(cipher, cek, "pair-1", nudgeId, senderUid ?: "sender-1"),
            createdAtMs = createdAtMs,
            expiresAtMillis = expiresAtMillis,
        )
    }

    // --- gating: no pair / uid / key means no listener and a null card -----------------------

    @Test
    fun noPairOrUidOrKey_emitsNull_withoutListening() = runTest {
        val noPair = Harness().apply { enrollments = emptyList() }
        assertNull(noPair.repository.inbox().toList().single())
        assertEquals(0, noPair.listenCalls)

        val noUid = Harness().apply { uid = null }
        assertNull(noUid.repository.inbox().toList().single())
        assertEquals("a null uid must never reach the listener", 0, noUid.listenCalls)

        val noCek = Harness().apply { cekFailure = true }
        assertNull(noCek.repository.inbox().toList().single())
        assertEquals("a failed key derivation must never reach the listener", 0, noCek.listenCalls)
    }

    // --- target pair (§19.5 rule: selected live else newest) ---------------------------------

    @Test
    fun listensOnTheSelectedLivePair_elseTheNewest() = runTest {
        val h = Harness().apply {
            enrollments = listOf(enrollment("pair-old", 100), enrollment("pair-new", 200))
            uid = "uid-7"
        }
        h.repository.inbox().toList()
        assertEquals("no pick -> newest enrollment", "pair-new", h.lastListenedPair)
        assertEquals("the listener is scoped to the recipient uid", "uid-7", h.lastListenedUid)

        h.selected = "pair-old"
        h.repository.inbox().toList()
        assertEquals("pick names a live enrollment", "pair-old", h.lastListenedPair)

        h.selected = "pair-gone"
        h.repository.inbox().toList()
        assertEquals("stale pick -> newest enrollment", "pair-new", h.lastListenedPair)
    }

    // --- card selection: expiry ignore, newest-only, all-ids pending -------------------------

    @Test
    fun emptyInbox_isNullCard() = runTest {
        val h = Harness()
        assertNull(h.repository.inbox().toList().single())
    }

    @Test
    fun allExpired_isNullCard() = runTest {
        val h = Harness().apply {
            emissions = flowOf(listOf(doc("d1", createdAtMs = 10L, expiresAtMillis = now - 1)))
        }
        assertNull("TTL lags cleanup; expired docs must not render", h.repository.inbox().toList().single())
        assertTrue(h.reports.isEmpty())
    }

    @Test
    fun newestLiveDocDrivesTheCard_andPendingIdsCoverEveryFetchedDoc() = runTest {
        val h = Harness().apply {
            val expired = doc("d-expired", createdAtMs = 950_000L, expiresAtMillis = now - 1)
            val old = doc("d-old", createdAtMs = 900_000L)
            val newest = doc("d-new", createdAtMs = 990_000L)
            emissions = flowOf(listOf(old, expired, newest))
        }
        val card = h.repository.inbox().toList().single()
        assertEquals(990_000L, card?.createdAtMs)
        assertEquals("pair-1", card?.pairId)
        assertEquals(
            "the ack batch covers every fetched pending id — expired included",
            listOf("d-old", "d-expired", "d-new"),
            card?.pendingNudgeIds,
        )
        assertTrue("a live newest opens silently", h.reports.isEmpty())
    }

    @Test
    fun nullExpiresAt_isKept_clientCannotProveItDead() = runTest {
        val h = Harness().apply {
            emissions = flowOf(listOf(doc("d1", expiresAtMillis = null)))
        }
        assertEquals(900_000L, h.repository.inbox().toList().single()?.createdAtMs)
    }

    @Test
    fun malformedOlderDoc_isNeverTouched_whenTheNewestIsReadable() = runTest {
        val h = Harness().apply {
            val garbage = doc("d-old", createdAtMs = 900_000L, envelope = "not base64!!")
            val newest = doc("d-new", createdAtMs = 990_000L)
            emissions = flowOf(listOf(garbage, newest))
        }
        assertEquals(
            "only the newest is decrypted — the older garbage never reaches the cipher",
            990_000L,
            h.repository.inbox().toList().single()?.createdAtMs,
        )
        assertTrue(h.reports.isEmpty())
    }

    // --- unreadable newest: one safe report, no card, no fallback, no ack --------------------

    @Test
    fun schemaMalformedNewest_reportsSchemaAndSuppressesTheCard() = runTest {
        val h = Harness().apply {
            val malformed = NudgeInboxRepository.NudgeDoc(
                nudgeId = "d1",
                senderUid = "sender-1",
                envelope = null,
                createdAtMs = 900_000L,
                expiresAtMillis = 2_000_000L,
            )
            emissions = flowOf(listOf(malformed))
        }
        assertNull(h.repository.inbox().toList().single())
        assertEquals(listOf("schema"), h.reports)
    }

    @Test
    fun undecryptableNewest_reportsDecrypt_neverFallsBackToOlderDocuments() = runTest {
        for (bad in listOf(
            "not base64!!",
            // Sealed for a different pair — AAD mismatch at open time.
            NudgeEnvelope.seal(EventCipher(), ByteArray(32) { 9 }, "pair-other", "d-new", "sender-1"),
        )) {
            val h = Harness().apply {
                val olderValid = doc("d-old", createdAtMs = 900_000L)
                val broken = doc("d-new", createdAtMs = 990_000L, envelope = bad)
                emissions = flowOf(listOf(olderValid, broken))
            }
            assertNull("bad=$bad", h.repository.inbox().toList().single())
            assertEquals("bad=$bad", listOf("decrypt"), h.reports)
        }
    }

    // --- acknowledgment ordering (§6.9 steps 1-4) --------------------------------------------

    @Test
    fun acknowledge_batchesEveryPendingId_thenCancelsNotifications() = runTest {
        val h = Harness()
        val card = NudgeInboxRepository.NudgeCard("pair-1", 900_000L, listOf("d1", "d2"))
        assertTrue(h.repository.acknowledge(card))
        assertEquals(listOf("pair-1" to listOf("d1", "d2")), h.ackCalls)
        assertEquals("cancel strictly after the batch commits", listOf("batch", "cancel"), h.effects)
    }

    @Test
    fun acknowledge_batchFailure_returnsFalse_andNeverCancels() = runTest {
        val h = Harness().apply { ackFailure = true }
        val card = NudgeInboxRepository.NudgeCard("pair-1", 900_000L, listOf("d1"))
        assertEquals(false, h.repository.acknowledge(card))
        assertEquals("nothing was written, so the tray entry must survive", listOf("batch"), h.effects)
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
