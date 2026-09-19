package com.example.myexodus.accountability.serialization

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeTest {

    private fun envelope(sequence: Long = 1, prev: ByteArray? = null) = UnsignedEnvelope(
        senderKeyId = "a".repeat(64),
        epoch = 0,
        sequence = sequence,
        previousEventHash = prev,
        nonce = ByteArray(12) { 7 },
        ciphertext = byteArrayOf(1, 2, 3, 4),
        createdAtEpochMillis = 1_700_000_000_000L,
    )

    @Test
    fun canonicalBytes_areDeterministic() {
        val e = envelope()
        assertArrayEquals(e.canonicalBytes(), e.canonicalBytes())
        // Same logical envelope built twice must encode identically (byte-for-byte §12).
        assertArrayEquals(e.canonicalBytes(), envelope().canonicalBytes())
    }

    @Test
    fun signedEnvelope_roundTrips() {
        val e = envelope(sequence = 3, prev = ByteArray(32) { 9 })
        val sig = byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02)
        val signed = SignedEnvelope(e, sig)
        val encoded = encodeSigned(signed)
        val decoded = decodeSigned(encoded)
        // Data-class equality is referential for ByteArray fields; compare field-wise.
        assertEquals(e.senderKeyId, decoded.unsigned.senderKeyId)
        assertEquals(e.epoch, decoded.unsigned.epoch)
        assertEquals(e.sequence, decoded.unsigned.sequence)
        assertArrayEquals(e.previousEventHash, decoded.unsigned.previousEventHash)
        assertArrayEquals(e.nonce, decoded.unsigned.nonce)
        assertArrayEquals(e.ciphertext, decoded.unsigned.ciphertext)
        assertEquals(e.createdAtEpochMillis, decoded.unsigned.createdAtEpochMillis)
        assertArrayEquals(sig, decoded.signature)
    }

    @Test
    fun firstEvent_hasNullPreviousHash() {
        val e = envelope(sequence = 1, prev = null)
        assertTrue(encodeSigned(SignedEnvelope(e, ByteArray(8))).isNotEmpty())
        val decoded = decodeSigned(encodeSigned(SignedEnvelope(e, ByteArray(8))))
        assertEquals(null, decoded.unsigned.previousEventHash)
    }

    @Test
    fun eventHash_stableAndInputSensitive() {
        val e = envelope()
        assertArrayEquals(eventHash(e), eventHash(e))
        // Changing any field changes the hash.
        val e2 = e.copy(sequence = 2)
        assertTrue(!eventHash(e).contentEquals(eventHash(e2)))
        val e3 = e.copy(ciphertext = byteArrayOf(9))
        assertTrue(!eventHash(e).contentEquals(eventHash(e3)))
    }

    @Test
    fun keyEpoch_zero_leavesTheWireFormatUntouched() {
        // AD-12 back-compat: an epoch-0 envelope must be byte-identical to a pre-Step-15 one,
        // so every stored envelope (and its signature) stays valid — the field is absent, not
        // zero-encoded.
        val encoded = encodeUnsigned(envelope())
        val map = CanonicalCbor.decodeMap(encoded)
        assertTrue(!map.ContainsKey(com.upokecenter.cbor.CBORObject.FromObject("ke")))
        assertEquals(0, decodeSigned(encodeSigned(SignedEnvelope(envelope(), ByteArray(8)))).unsigned.keyEpoch)
    }

    @Test
    fun keyEpoch_roundTripsAboveZero() {
        val e = envelope().copy(keyEpoch = 5)
        val decoded = decodeSigned(encodeSigned(SignedEnvelope(e, ByteArray(8))))
        assertEquals(5, decoded.unsigned.keyEpoch)
        // The epoch is signed material — changing it changes the hash the signature covers.
        assertTrue(!eventHash(e).contentEquals(eventHash(envelope())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_rejectsTruncated() {
        decodeSigned(byteArrayOf(0xa2.toByte(), 0x61))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_rejectsGarbage() {
        decodeSigned("not cbor at all".toByteArray())
    }
}
