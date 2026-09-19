package com.example.myexodus.accountability.serialization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPayloadTest {

    @Test
    fun payload_roundTrips() {
        val p = EventPayload(type = EventPayload.TYPE_ACTIVITY, text = "watched youtube 12min")
        val decoded = decodePayload(encodePayload(p))
        assertEquals(p, decoded)
    }

    @Test
    fun nfcNormalization_happensBeforeEncoding() {
        val decomposed = "café" // "café" with combining acute
        val composed = "café"
        assertNotEquals(decomposed, composed)
        // Both forms produce identical ciphertext-bound bytes: the encode is NFC-normalized.
        val a = encodePayload(EventPayload(text = decomposed))
        val b = encodePayload(EventPayload(text = composed))
        assertEquals(a.toList(), b.toList())
        assertEquals("café", decodePayload(b).text)
    }

    @Test
    fun deletionRequest_typeSurvivesRoundTrip() {
        val p = EventPayload(type = EventPayload.TYPE_DELETION_REQUEST, text = "delete")
        assertEquals(EventPayload.TYPE_DELETION_REQUEST, decodePayload(encodePayload(p)).type)
    }

    /** Plan §8 Step 4 / AD-07: the DisableApprovalResponse reference fields. */
    @Test
    fun approvalReferenceFields_roundTrip() {
        val p = EventPayload(
            type = "approval-response", // concrete type value lands with Step 13
            text = "approved",
            requestEventId = "req-event-1",
            pairingEpoch = 3,
        )
        assertEquals(p, decodePayload(encodePayload(p)))
    }

    /** Unset fields must stay ABSENT (not null) so existing payload bytes are untouched. */
    @Test
    fun approvalReferenceFields_areOmittedWhenUnset() {
        val bytes = encodePayload(EventPayload(text = "watched youtube 12min"))
        val keys = CanonicalCbor.decodeMap(bytes).getKeys().map { it.AsString() }.toSet()
        assertEquals(setOf("ty", "tx"), keys)
        val decoded = decodePayload(bytes)
        assertEquals(null, decoded.requestEventId)
        assertEquals(null, decoded.pairingEpoch)
    }

    /** Plan §8 Step 11 / AD-08b: the repair request's anchor fields round-trip. (Field-wise:
     *  data-class equality is reference equality for the ByteArray.) */
    @Test
    fun syncRequestAnchorFields_roundTrip() {
        val p = EventPayload(
            type = EventPayload.TYPE_SYNC_REQUEST,
            text = "sync_request",
            expectedPreviousSequence = 42L,
            expectedPreviousHash = ByteArray(32) { 7 },
        )
        val decoded = decodePayload(encodePayload(p))
        assertEquals(EventPayload.TYPE_SYNC_REQUEST, decoded.type)
        assertEquals("sync_request", decoded.text)
        assertEquals(42L, decoded.expectedPreviousSequence)
        assertTrue(decoded.expectedPreviousHash!!.contentEquals(ByteArray(32) { 7 }))
    }

    @Test
    fun syncRequestAnchorFields_areOmittedWhenUnset() {
        val bytes = encodePayload(EventPayload(type = EventPayload.TYPE_SYNC_REQUEST, text = "sync_request"))
        val keys = CanonicalCbor.decodeMap(bytes).getKeys().map { it.AsString() }.toSet()
        assertEquals(setOf("ty", "tx"), keys)
        assertEquals(null, decodePayload(bytes).expectedPreviousSequence)
        assertEquals(null, decodePayload(bytes).expectedPreviousHash)
    }
}
