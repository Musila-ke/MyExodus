package com.example.myexodus.accountability.serialization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
