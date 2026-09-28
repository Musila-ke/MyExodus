package com.example.myexodus.accountability.enrollment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EnrollmentPayloadTest {

    private fun payload() = EnrollmentPayload(
        sessionId = "a1b2c3d4e5f60718",
        signingChain = listOf(ByteArray(64) { 1 }, ByteArray(64) { 2 }),
        attestationChallenge = ByteArray(32) { 7 },
        agreementPublicKey = ByteArray(91) { 3 },
    )

    @Test
    fun payload_roundTrips() {
        val decoded = decodeEnrollmentPayload(encodeEnrollmentPayload(payload()))
        assertEquals("a1b2c3d4e5f60718", decoded.sessionId)
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

    /** §19.5 item 3: the label rides the existing payload — absent means absent, not a null
     *  placeholder, so a label-less payload stays byte-identical to the pre-label format. */
    @Test
    fun label_isOmittedWhenNull_soLabelLessPayloadsStayByteIdentical() {
        val withoutLabel = encodeEnrollmentPayload(payload())
        // The label's CBOR entry is the one-character text string "l" → 0x61 0x6C. This fixed
        // payload's raw bytes contain no such pair, so finding one would mean the encoder
        // emitted an entry it promised to omit.
        assertFalse(
            "a null label must not be encoded",
            withoutLabel.toList().windowed(2).contains(listOf(0x61, 0x6C)),
        )
        assertNull(decodeEnrollmentPayload(withoutLabel).label)
    }

    @Test
    fun label_roundTrips() {
        val decoded = decodeEnrollmentPayload(
            encodeEnrollmentPayload(payload().copy(label = "Ana Maria")),
        )
        assertEquals("Ana Maria", decoded.label)
    }

    /**
     * The decode boundary is the trust boundary: the encoder writes whatever the caller hands
     * it, so a partner's device can put control characters on the wire. What comes back from
     * [decodeEnrollmentPayload] is what the dashboard renders — it must already be clean.
     */
    @Test
    fun hostileLabel_fromTheWire_isSanitizedOnDecode() {
        val rawRlo = "Ana‮Maria"
        val decoded = decodeEnrollmentPayload(
            encodeEnrollmentPayload(payload().copy(label = rawRlo)),
        )
        assertEquals("AnaMaria", decoded.label)
    }

    @Test
    fun blankLabel_onTheWire_decodesToNull_notAnEmptyName() {
        val decoded = decodeEnrollmentPayload(
            encodeEnrollmentPayload(payload().copy(label = "   ")),
        )
        assertNull(decoded.label)
    }
}
