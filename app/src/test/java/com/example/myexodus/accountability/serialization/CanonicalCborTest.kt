package com.example.myexodus.accountability.serialization

import com.upokecenter.cbor.CBORObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 8949 §2 and §4.2.1 (canonical/deterministic) vectors. */
class CanonicalCborTest {

    private fun hex(s: String): ByteArray = s
        .replace(" ", "")
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private fun hexStr(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun encodeOf(o: CBORObject): String = hexStr(CanonicalCbor.encode(o))

    @Test
    fun integers_encodeInMinimalForm() {
        assertEquals("00", encodeOf(CBORObject.FromObject(0)))
        assertEquals("01", encodeOf(CBORObject.FromObject(1)))
        assertEquals("17", encodeOf(CBORObject.FromObject(23)))
        assertEquals("1818", encodeOf(CBORObject.FromObject(24)))
        assertEquals("1864", encodeOf(CBORObject.FromObject(100)))
        assertEquals("1903e8", encodeOf(CBORObject.FromObject(1000)))
        assertEquals("1a000f4240", encodeOf(CBORObject.FromObject(1_000_000)))
        assertEquals("1b000000e8d4a51000", encodeOf(CBORObject.FromObject(1_000_000_000_000L)))
        assertEquals("20", encodeOf(CBORObject.FromObject(-1)))
        assertEquals("3863", encodeOf(CBORObject.FromObject(-100)))
    }

    @Test
    fun textStrings_encodeUtf8() {
        assertEquals("6449455446", encodeOf(CanonicalCbor.text("IETF")))
        assertEquals("62225c", encodeOf(CanonicalCbor.text("\"\\")))
        assertEquals("62c3bc", encodeOf(CanonicalCbor.text("ü")))
        assertEquals("63e6b0b4", encodeOf(CanonicalCbor.text("水")))
        assertEquals("64f0908591", encodeOf(CanonicalCbor.text("𐅑")))
    }

    @Test
    fun byteStrings_plain() {
        assertEquals("43010203", encodeOf(CanonicalCbor.bytes(byteArrayOf(1, 2, 3))))
    }

    @Test
    fun array_plain() {
        val arr = CBORObject.NewArray().apply {
            Add(CBORObject.FromObject(1))
            Add(CBORObject.FromObject(2))
            Add(CBORObject.FromObject(3))
        }
        assertEquals("83010203", encodeOf(arr))
    }

    @Test
    fun map_plain() {
        val encoded = CanonicalCbor.encodeMap(
            listOf(
                "a" to CanonicalCbor.int(1),
                "b" to CBORObject.NewArray().apply {
                    Add(CBORObject.FromObject(2))
                    Add(CBORObject.FromObject(3))
                },
            ),
        )
        assertEquals("a26161016162820203", hexStr(encoded))
    }

    @Test
    fun map_canonicalSortByLengthThenBytes() {
        // 39 (2-byte encoding) sorts before 385 (3-byte encoding); values preserve pairing.
        val m = CBORObject.NewMap()
        m[CBORObject.FromObject(39)] = CanonicalCbor.text("Watson")
        m[CBORObject.FromObject(385)] = CanonicalCbor.text("Holmes")
        assertEquals("a2182766576174736f6e19018166486f6c6d6573", hexStr(CanonicalCbor.encode(m)))
    }

    @Test
    fun nfc_normalizesBeforeEncoding() {
        // "e + combining acute" -> NFC "é" (U+00E9, encoded as c3a9).
        assertEquals("62c3a9", encodeOf(CanonicalCbor.text("é")))
    }

    // §12 fuzzing requirement: the CBOR parser must fail fast on adversarial input —
    // throw, never hang, never allocate on claimed huge lengths, never crash on depth bombs.

    @Test(expected = IllegalArgumentException::class)
    fun truncatedBytes_throw() {
        CanonicalCbor.decode(byteArrayOf(0x62, 0x41)) // text(2) but only 1 byte
    }

    @Test(expected = IllegalArgumentException::class)
    fun garbageBytes_throw() {
        CanonicalCbor.decode(byteArrayOf(0x7f, 0x5a, 0x00, 0x1f, -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyBytes_throw() {
        CanonicalCbor.decode(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun hugeLengthPrefix_isRejectedWithoutAllocating() {
        // byte-string length claimed as 2^32-1 over a tiny input — must fail, not allocate.
        CanonicalCbor.decode(byteArrayOf(0x5a.toByte(), -1, -1, -1, -1, 0x41))
    }

    @Test
    fun deepNesting_isBoundedByDecoderDepthLimit() {
        val bomb = ByteArray(1501) { 0x81.toByte() } // 1500 nested arrays + inner value
        try {
            val decoded = CanonicalCbor.decode(bomb)
            // No crash is the hard requirement; some decoder versions cap nesting.
            assertTrue(decoded != null)
        } catch (e: IllegalArgumentException) {
            // Depth-limit rejection is the expected outcome.
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeMap_rejectsNonMapPayloads() {
        CanonicalCbor.decodeMap(byteArrayOf(0x07)) // CBOR integer 7
    }
}
