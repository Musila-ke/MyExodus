package com.example.myexodus.accountability.monitoring

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan Step 8 (§8.10/§8.11) client: consent convergence is idempotent per value and retried
 * after a failed ack; the status read is bounded by the 60 s cache, never invents a state on
 * failure, and parses exactly the fields the `readPartnerStatus` callable returns.
 */
class PartnerDmsClientTest {

    private var nowMs = START
    private var uid: String? = "uid-a"
    private val consentWrites = mutableListOf<Pair<String, Boolean>>()
    private var consentAcks = true
    private var statusReads = 0
    private var remoteStatus: PartnerStatus? = PartnerStatus(
        consent = true,
        connection = PartnerConnection.CHECKING_IN,
        bucket = PartnerConnectionBucket.WITHIN_1H,
    )

    private fun client() = PartnerDmsClient(
        uid = { uid },
        requestConsent = { pairId, enabled ->
            consentWrites += pairId to enabled
            consentAcks
        },
        readRemoteStatus = {
            statusReads++
            remoteStatus
        },
        now = { nowMs },
    )

    @Test
    fun syncConsent_repeatsTheSameValueWithoutAnotherCall() = runTest {
        val client = client()

        assertTrue(client.syncConsent(PAIR, true))
        assertTrue(client.syncConsent(PAIR, true))

        assertEquals("one callable per confirmed value", listOf(PAIR to true), consentWrites)
    }

    @Test
    fun syncConsent_changedValue_isSentAgain() = runTest {
        val client = client()
        client.syncConsent(PAIR, true)

        assertTrue(client.syncConsent(PAIR, false))

        assertEquals(listOf(PAIR to true, PAIR to false), consentWrites)
    }

    /** Offline revocation is "pending until acknowledged": an unacked call is retried on the
     *  next pass instead of being remembered as done. */
    @Test
    fun syncConsent_unacked_failureIsRetried() = runTest {
        val client = client()
        consentAcks = false
        assertFalse(client.syncConsent(PAIR, false))

        consentAcks = true
        assertTrue(client.syncConsent(PAIR, false))

        assertEquals(listOf(PAIR to false, PAIR to false), consentWrites)
    }

    @Test
    fun syncConsent_withoutIdentity_sendsNothing() = runTest {
        uid = null

        assertFalse(client().syncConsent(PAIR, true))

        assertTrue(consentWrites.isEmpty())
    }

    @Test
    fun status_isCachedInsideTheTtl_andRefreshesAfterIt() = runTest {
        val client = client()

        assertEquals(PartnerConnection.CHECKING_IN, client.status(PAIR)?.connection)
        nowMs += PartnerDmsClient.STATUS_TTL_MILLIS
        assertEquals("still cached at the boundary", PartnerConnection.CHECKING_IN, client.status(PAIR)?.connection)
        assertEquals(1, statusReads)

        nowMs += 1
        assertEquals(PartnerConnection.CHECKING_IN, client.status(PAIR)?.connection)
        assertEquals("past the TTL the server is asked again", 2, statusReads)
    }

    @Test
    fun status_forceBypassesTheCache() = runTest {
        val client = client()
        client.status(PAIR)

        client.status(PAIR, force = true)

        assertEquals(2, statusReads)
    }

    @Test
    fun status_failedRead_neverInventsAState() = runTest {
        val client = client()
        remoteStatus = null

        assertNull(client.status(PAIR))
        assertEquals(1, statusReads)
    }

    @Test
    fun status_forcedRefreshFailure_keepsAReadingStillInsideTheTtl() = runTest {
        val client = client()
        client.status(PAIR)
        remoteStatus = null
        nowMs += 30_000

        assertEquals(
            "a failed refresh repeats only what is still fresh — never an older claim",
            PartnerConnection.CHECKING_IN,
            client.status(PAIR, force = true)?.connection,
        )
    }

    @Test
    fun status_expiredCacheAndFailedRead_claimsNothing() = runTest {
        val client = client()
        client.status(PAIR)
        remoteStatus = null
        nowMs += PartnerDmsClient.STATUS_TTL_MILLIS + 1

        assertNull(client.status(PAIR))
    }

    @Test
    fun status_withoutIdentity_claimsNothing() = runTest {
        uid = null

        assertNull(client().status(PAIR))
        assertEquals(0, statusReads)
    }

    @Test
    fun parsePartnerStatus_acceptsTheCallableShape() {
        assertEquals(
            PartnerStatus(true, PartnerConnection.LATE, PartnerConnectionBucket.HOURS_1_TO_6),
            parsePartnerStatus(
                mapOf("pairId" to PAIR, "consent" to true, "connection" to "LATE", "bucket" to "HOURS_1_TO_6"),
            ),
        )
        assertEquals(
            "consent off is a real answer, not an error",
            PartnerStatus(false, PartnerConnection.NO_DATA, PartnerConnectionBucket.NO_DATA),
            parsePartnerStatus(
                mapOf("consent" to false, "connection" to "NO_DATA", "bucket" to "NO_DATA"),
            ),
        )
    }

    @Test
    fun parsePartnerStatus_rejectsMalformedAnswers() {
        assertNull(parsePartnerStatus(null))
        assertNull(parsePartnerStatus("not a map"))
        assertNull(parsePartnerStatus(mapOf("connection" to "LATE", "bucket" to "WITHIN_1H")))
        assertNull(
            "an unknown connection is not guessed",
            parsePartnerStatus(mapOf("consent" to true, "connection" to "HEALTHY", "bucket" to "WITHIN_1H")),
        )
        assertNull(
            "an unknown bucket is not guessed",
            parsePartnerStatus(mapOf("consent" to true, "connection" to "LATE", "bucket" to "SOON")),
        )
    }

    private companion object {
        const val PAIR = "pair-dms"
        const val START = 1_800_000_000_000L
    }
}
