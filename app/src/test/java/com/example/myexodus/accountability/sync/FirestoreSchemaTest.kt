package com.example.myexodus.accountability.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.enrollment.PairingToken
import com.example.myexodus.accountability.state.ChainEvent
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FirestoreSchemaTest {

    private fun event(seed: Long, sequence: Long) = ChainEvent(
        pairId = "a1b2c3d4e5f60718",
        senderKeyId = "sender-$seed",
        epoch = 0,
        sequence = sequence,
        previousEventHash = ByteArray(32) { seed.toByte() },
        eventHash = ByteArray(32) { (seed + 1).toByte() },
        envelope = byteArrayOf(1, 2, 3),
        direction = ChainEvent.DIRECTION_SENT,
        createdAtEpochMillis = 1_700_000_000_000L + seed,
        syncState = ChainEvent.SYNC_STATE_PENDING,
    )

    @Test
    fun eventDocumentId_isDeterministicAndDistinctPerEvent() {
        val a = FirestoreSchema.eventDocumentId("pair-1", "sender-1", 1)
        val a2 = FirestoreSchema.eventDocumentId("pair-1", "sender-1", 1)
        val b = FirestoreSchema.eventDocumentId("pair-1", "sender-1", 2)
        assertEquals(a, a2) // idempotent: re-draining the outbox hits the same doc (§8.4)
        assertNotEquals(a, b)
        assertNotEquals(a, FirestoreSchema.eventDocumentId("pair-2", "sender-1", 1))
        assertEquals(64, a.length) // hex sha256
    }

    @Test
    fun eventDocumentData_carriesTheRulesContract() {
        @Suppress("UNCHECKED_CAST")
        val data = FirestoreSchema.eventDocumentData(event(7, 3), "uid-a", "uid-b") as Map<String, Any>
        assertEquals("a1b2c3d4e5f60718", data["pairId"])
        assertEquals("sender-7", data["senderKeyId"])
        assertEquals(3L, data["sequence"])
        assertEquals("uid-a", data["authorUid"])
        assertEquals("uid-b", data["partnerUid"])
        val blob = (data["envelope"] as Blob).toBytes()
        // §8.4 rule contract: envelope.size() < 1048576
        assertTrue(blob.size < 1_048_576)
        assertEquals(3, blob.size)
    }

    @Test
    fun pairStateData_membershipMapMatchesTheRule() {
        @Suppress("UNCHECKED_CAST")
        val members = (FirestoreSchema.pairStateData(setOf("uid-a", "uid-b"))["members"] as Map<String, Any>)
        assertEquals(true, members["uid-a"])
        assertEquals(true, members["uid-b"])
        assertFalse(members.containsKey("uid-c"))
    }

    @Test
    fun backfillRequestId_isDeterministicPerMissingSequence() {
        val a = FirestoreSchema.backfillRequestDocumentId("pair-1", "sender-1", 4)
        assertEquals(a, FirestoreSchema.backfillRequestDocumentId("pair-1", "sender-1", 4))
        assertNotEquals(a, FirestoreSchema.backfillRequestDocumentId("pair-1", "sender-1", 5))
        assertNotEquals(a, FirestoreSchema.backfillRequestDocumentId("pair-1", "sender-2", 4))
        assertEquals(64, a.length)
    }

    @Test
    fun backfillRequestData_carriesTheRulesContract() {
        @Suppress("UNCHECKED_CAST")
        val data = FirestoreSchema.backfillRequestData("pair-1", "sender-1", 4, "uid-a") as Map<String, Any>
        assertEquals("pair-1", data["pairId"])
        assertEquals("sender-1", data["senderKeyId"])
        assertEquals(4L, data["sequence"])
        assertEquals("uid-a", data["requesterUid"]) // rule: requesterUid == request.auth.uid
    }

    @Test
    fun permissionDenied_isClassifiedWithoutRetry() {
        val denied = FirebaseFirestoreException("no", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        assertSame(FirestoreRelay.DrainResult.PermissionDenied, FirestoreRelay.classifyOutcome(denied))
        val transient = FirebaseFirestoreException("boom", FirebaseFirestoreException.Code.UNAVAILABLE)
        assertTrue(FirestoreRelay.classifyOutcome(transient) is FirestoreRelay.DrainResult.Failed)
    }

    @Test
    fun pairingTokenDocumentId_isDeterministicAndDistinctPerToken() {
        val token = PairingToken.generate(1_700_000_000_000L)
        val a = FirestoreSchema.pairingTokenDocumentId(token)
        assertEquals(a, FirestoreSchema.pairingTokenDocumentId(token))
        assertEquals(64, a.length) // hex sha256
        assertNotEquals(a, FirestoreSchema.pairingTokenDocumentId(PairingToken.generate(1_700_000_000_000L)))
    }

    @Test
    fun pairingTokenData_expiryIsATimestampSoRulesCanCompareToRequestTime() {
        @Suppress("UNCHECKED_CAST")
        val data = FirestoreSchema.pairingTokenData(PairingToken.generate(1_700_000_000_000L), "uid-a", byteArrayOf(1, 2, 3)) as Map<String, Any>
        val expiresAt = data["expiresAt"] as com.google.firebase.Timestamp
        assertEquals(1_700_000_600L, expiresAt.seconds) // 1_700_000_000_000ms + TTL 600_000ms = 1_700_000_600s
        assertEquals(0L, expiresAt.nanoseconds.toLong())
        assertEquals("uid-a", data["creatorUid"]) // rule: creatorUid == request.auth.uid
        assertTrue(data.containsKey("pairId"))
        assertTrue(data.containsKey("createdAt"))
        assertArrayEquals(byteArrayOf(1, 2, 3), (data["payload"] as Blob).toBytes())
    }

    @Test
    fun pairStateData_withEnrollmentCarriesRedeemerPayload() {
        @Suppress("UNCHECKED_CAST")
        val data = FirestoreSchema.pairStateData(setOf("uid-a", "uid-b"), byteArrayOf(1, 2, 3)) as Map<String, Any>
        val members = data["members"] as Map<String, Any>
        assertEquals(true, members["uid-a"])
        assertEquals(true, members["uid-b"])
        assertArrayEquals(byteArrayOf(1, 2, 3), (data["enrollment"] as Blob).toBytes())
    }
}
