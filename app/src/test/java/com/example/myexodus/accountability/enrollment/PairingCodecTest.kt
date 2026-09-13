package com.example.myexodus.accountability.enrollment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §10 step 1: the QR carries the single-use token (plus its pairId) — NOT the enrollment
 * payload, which overflows QR capacity with a real attestation chain (2953 B ceiling,
 * caught on-device 2026-08-30). A pairId disagreement between token and wrapper is
 * rejected before anything touches the relay.
 */
class PairingCodecTest {

    private val pairId = "a1b2c3d4e5f60718"

    private fun token(pairId: String = this.pairId) = PairingToken(
        token = ByteArray(32) { 1 },
        pairId = pairId,
        expiresAtEpochMillis = 1_700_000_000_000L,
    )

    @Test
    fun roundTripPreservesToken() {
        val t = token()
        val decoded = PairingCodec.decode(PairingCodec.encode(t))
        assertArrayEquals(t.token, decoded.token.token)
        assertEquals(t.pairId, decoded.token.pairId)
        assertEquals(t.pairId, decoded.pairId)
        assertEquals(t.expiresAtEpochMillis, decoded.token.expiresAtEpochMillis)
    }

    @Test
    fun qrStaysWellUnderTheQRv40Ceiling() {
        // The point of the design: with the payload in the token document, the QR is tiny
        // even for a multi-cert attestation chain (2953 B is QR v40's binary ceiling).
        assertTrue(PairingCodec.encode(token()).size < 200)
    }

    @Test
    fun pairIdMismatchBetweenTokenAndContentIsRejected() {
        val qr = PairingCodec.encodeWithPairId(token(pairId), "different-pair")
        assertThrows(IllegalArgumentException::class.java) { PairingCodec.decode(qr) }
    }
}
