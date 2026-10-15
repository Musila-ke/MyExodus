package com.example.myexodus.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR #153: the raw-key passphrase is SQLCipher's exact hex-literal form — 67 bytes of ASCII
 * `x'<64 lowercase hex>'`. A single wrong byte here reads as a wrong key at open time, so the
 * format is pinned rather than assumed.
 */
class SqlCipherRawKeyTest {

    @Test
    fun passphraseFor_isThe67ByteHexLiteral() {
        val dek = ByteArray(32) { it.toByte() } // 0x00..0x1f
        val raw = SqlCipherRawKey.passphraseFor(dek)
        assertEquals(67, raw.size)
        assertEquals(
            "x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'",
            String(raw, Charsets.US_ASCII),
        )
    }

    @Test
    fun passphraseFor_highBytesEncodeAsLowercaseHex() {
        val dek = ByteArray(32) { 0xAB.toByte() }
        dek[0] = 0x00
        dek[31] = 0xFF.toByte()
        assertEquals(
            "x'00" + "ab".repeat(30) + "ff'",
            String(SqlCipherRawKey.passphraseFor(dek), Charsets.US_ASCII),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun passphraseFor_rejectsNon256BitKeys() {
        SqlCipherRawKey.passphraseFor(ByteArray(16))
    }

    @Test
    fun isWrongKey_matchesSqliteNotADatabaseMessage() {
        assertTrue(SqlCipherRawKey.isWrongKey(RuntimeException("file is not a database (code 26)")))
        assertFalse(SqlCipherRawKey.isWrongKey(RuntimeException("disk I/O error (code 10)")))
    }
}
