package com.example.myexodus.accountability.enrollment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EnrollmentPayloadTest {

    private fun payload() = EnrollmentPayload(
        pairId = "a1b2c3d4e5f60718",
        signingChain = listOf(ByteArray(64) { 1 }, ByteArray(64) { 2 }),
        attestationChallenge = ByteArray(32) { 7 },
        agreementPublicKey = ByteArray(91) { 3 },
    )

    @Test
    fun payload_roundTrips() {
        val decoded = decodeEnrollmentPayload(encodeEnrollmentPayload(payload()))
        assertEquals("a1b2c3d4e5f60718", decoded.pairId)
        assertEquals(2, decoded.signingChain.size)
        assertArrayEquals(ByteArray(64) { 1 }, decoded.signingChain[0])
        assertArrayEquals(ByteArray(32) { 7 }, decoded.attestationChallenge)
        assertArrayEquals(ByteArray(91) { 3 }, decoded.agreementPublicKey)
    }

    @Test
    fun tamperedBytes_areRejected() {
        val encoded = encodeEnrollmentPayload(payload())
        // Truncation breaks the final byte string's length framing; a bit-flip inside content is
        // structurally valid CBOR and is caught by the transcript signature at the flow layer, not here.
        val tampered = encoded.copyOfRange(0, encoded.size - 1)
        assertThrows(IllegalArgumentException::class.java) { decodeEnrollmentPayload(tampered) }
    }

    @Test
    fun garbage_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeEnrollmentPayload("not cbor".toByteArray())
        }
    }
}
