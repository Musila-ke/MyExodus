package com.example.myexodus.accountability.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** RFC 5869 Appendix A test vectors (SHA-256). */
class HkdfTest {

    private fun hex(s: String): ByteArray = s
        .replace(" ", "")
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private fun hexStr(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    @Test
    fun rfc5869_A1_basic() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = (0x00..0x0c).map { it.toByte() }.toByteArray()
        val info = (0xf0..0xf9).map { it.toByte() }.toByteArray()
        val okm = Hkdf.derive(salt, ikm, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hexStr(okm),
        )
    }

    @Test
    fun rfc5869_A2_longInputs() {
        val ikm = (0x00..0x4f).map { it.toByte() }.toByteArray()
        val salt = (0x60..0xaf).map { it.toByte() }.toByteArray()
        val info = (0xb0..0xff).map { it.toByte() }.toByteArray()
        val okm = Hkdf.derive(salt, ikm, info, 82)
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            hexStr(okm),
        )
    }

    @Test
    fun rfc5869_A3_zeroLengthSaltAndInfo() {
        val ikm = ByteArray(22) { 0x0b }
        // Salt omitted -> zero-filled 32-byte salt, per RFC 5869 §2.2.
        val okm = Hkdf.derive(ikm, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            hexStr(okm),
        )
    }

    @Test
    fun expand_lengthBoundaries() {
        val prk = ByteArray(32) { 1 }
        val okm = Hkdf.expand(prk, ByteArray(0), 32 * 255)
        assertEquals(32 * 255, okm.size)
        try {
            Hkdf.expand(prk, ByteArray(0), 32 * 255 + 1)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertArrayEquals(ByteArray(0), ByteArray(0))
        }
    }
}
