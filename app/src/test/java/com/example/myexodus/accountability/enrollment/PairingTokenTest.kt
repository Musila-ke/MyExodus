package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.serialization.CanonicalCbor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §10 step 1: the one-time pairing token — 60 random bits zero-padded into the fixed
 * 32-byte field (the typed-code domain, §19.4 item 1), 10-minute TTL, CBOR codec.
 */
class PairingTokenTest {

    @Test
    fun generateProducesFreshUnrelatedTokens() {
        val now = 1_700_000_000_000L
        val a = PairingToken.generate(now)
        val b = PairingToken.generate(now)
        assertFalse(a.token.contentEquals(b.token))
        assertNotEquals(a.sessionId, b.sessionId)
        assertEquals(PairingToken.TOKEN_BYTE_LENGTH, a.token.size)
        assertEquals(now + PairingToken.TTL_MILLIS, a.expiresAtEpochMillis)
    }

    @Test
    fun expiryDeniesAtTheDeadline_notAfterIt() {
        val now = 1_700_000_000_000L
        val token = PairingToken.generate(now)
        assertFalse(token.isExpired(token.expiresAtEpochMillis - 1))
        // Deployed rules read `expiresAt > request.time` — the deadline instant is denied.
        assertTrue(token.isExpired(token.expiresAtEpochMillis))
        assertTrue(token.isExpired(token.expiresAtEpochMillis + 1))
    }

    @Test
    fun cborRoundTripPreservesEveryField() {
        val token = PairingToken.generate(1_700_000_000_000L)
        val decoded = decodePairingToken(encodePairingToken(token))
        assertArrayEquals(token.token, decoded.token)
        assertEquals(token.sessionId, decoded.sessionId)
        assertEquals(token.expiresAtEpochMillis, decoded.expiresAtEpochMillis)
    }

    @Test
    fun decodeRejectsATokenOfWrongLength() {
        val bytes = CanonicalCbor.encodeMap(
            listOf(
                "tk" to CanonicalCbor.bytes(ByteArray(16)),
                "s" to CanonicalCbor.text("a1b2"),
                "e" to CanonicalCbor.int(1_700_000_000_000L),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { decodePairingToken(bytes) }
    }
}
