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

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /**
     * §12 profile pin, the envelope's counterpart of EventPayloadTest's golden vectors: the
     * unsigned envelope's canonical bytes and its domain-separated [eventHash] are the exact
     * material every signature covers (EventCreator signs it; MailboxEventProcessor verifies
     * partner events by recomputing it; ChainStateStore recomputes it for duplicate and
     * gap-reconciliation checks). If petero-cbor's encoding profile or [ENVELOPE_DOMAIN] ever
     * drifts, stored and in-flight envelopes stop re-verifying silently — so these are literal
     * encodings captured from petero-cbor 4.5.6, pinned rather than computed.
     */
    @Test
    fun goldenVector_unsignedEnvelope_byteStable() {
        val fixed = UnsignedEnvelope(
            senderKeyId = "ab".repeat(32),
            epoch = 0,
            sequence = 7,
            previousEventHash = ByteArray(32) { it.toByte() },
            nonce = ByteArray(12) { it.toByte() },
            ciphertext = byteArrayOf(1, 2, 3, 4, 5),
            createdAtEpochMillis = 1_700_000_000_000L,
        )
        assertEquals(
            "a86163450102030405616500616b784061626162616261626162616261626162616261626162616261626162616261626162616261626162616261626162616261626162616261626162616261626162616e4c000102030405060708090a0b61705820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f61730761741b0000018bcfe56800617601",
            hex(fixed.canonicalBytes()),
        )
        assertEquals(
            "41e86ddc4bf27d04648ece7303fea5e05ecb08c134864c2446fb3e4fd4c04cd2",
            hex(eventHash(fixed)),
        )

        // keyEpoch > 0 (AD-12): the appended field sorts after every 2-byte key under RFC 8949
        // length-first ordering — pinned so the extension bytes and their hash stay frozen too.
        val rotated = fixed.copy(keyEpoch = 3)
        assertEquals(
            "a96163450102030405616500616b784061626162616261626162616261626162616261626162616261626162616261626162616261626162616261626162616261626162616261626162616261626162616e4c000102030405060708090a0b61705820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f61730761741b0000018bcfe56800617601626b6503",
            hex(rotated.canonicalBytes()),
        )
        assertEquals(
            "b8938a45fdd0be184ee8826b22b7b0426252c55c4a591a389ec2b348c0197d4b",
            hex(eventHash(rotated)),
        )
    }

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
