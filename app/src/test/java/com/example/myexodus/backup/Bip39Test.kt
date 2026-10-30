package com.example.myexodus.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account plan §6.4/§8.5: the recovery key's 24-word encoding. Vectors are the official
 * Trezor BIP-39 256-bit  vectors (entropy + checksum only — the PBKDF2 seed step is
 * deliberately not part of this format).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Bip39Test {

    private lateinit var words: List<String>

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        words = BackupWordlists(context).bip39
    }

    private fun entropy(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun officialVector_allZero() {
        val mnemonic = Bip39.encode(ByteArray(32), words)
        assertEquals(List(23) { "abandon" } + "art", mnemonic)
        assertArrayEquals(ByteArray(32), Bip39.decode(mnemonic, words))
    }

    @Test
    fun officialVector_7f() {
        val entropy = ByteArray(32) { 0x7f }
        val expected = (
            "legal winner thank year wave sausage worth useful " +
                "legal winner thank year wave sausage worth useful " +
                "legal winner thank year wave sausage worth title"
            ).split(" ")
        assertEquals(expected, Bip39.encode(entropy, words))
        assertArrayEquals(entropy, Bip39.decode(expected, words))
    }

    @Test
    fun officialVector_80() {
        val entropy = ByteArray(32) { 0x80.toByte() }
        val expected = (
            "letter advice cage absurd amount doctor acoustic avoid " +
                "letter advice cage absurd amount doctor acoustic avoid " +
                "letter advice cage absurd amount doctor acoustic bless"
            ).split(" ")
        assertEquals(expected, Bip39.encode(entropy, words))
        assertArrayEquals(entropy, Bip39.decode(expected, words))
    }

    @Test
    fun officialVector_allOnes() {
        val entropy = ByteArray(32) { 0xff.toByte() }
        val mnemonic = Bip39.encode(entropy, words)
        assertEquals(List(23) { "zoo" } + "vote", mnemonic)
        assertArrayEquals(entropy, Bip39.decode(mnemonic, words))
    }

    @Test
    fun roundTripIsLossless() {
        val entropy = ByteArray(32) { index -> (index * 37 + 11).toByte() }
        val mnemonic = Bip39.encode(entropy, words)
        assertEquals(Bip39.WORDS_FOR_256_BITS, mnemonic.size)
        assertArrayEquals(entropy, Bip39.decode(mnemonic, words))
    }

    @Test
    fun decodeRejectsWrongWordCount() {
        val mnemonic = Bip39.encode(ByteArray(32), words)
        assertNull(Bip39.decode(mnemonic.dropLast(1), words))
        assertNull(Bip39.decode(mnemonic + "abandon", words))
        assertNull(Bip39.decode(emptyList(), words))
    }

    @Test
    fun decodeRejectsUnknownWord() {
        val mnemonic = Bip39.encode(ByteArray(32), words).toMutableList()
        mnemonic[5] = "notaword"
        assertNull(Bip39.decode(mnemonic, words))
    }

    @Test
    fun decodeRejectsBadChecksum() {
        // Last word carries the 8-bit SHA-256 checksum; index 0 cannot match it for zero entropy.
        val mnemonic = Bip39.encode(ByteArray(32), words).toMutableList()
        mnemonic[23] = "abandon"
        assertNull(Bip39.decode(mnemonic, words))
    }

    @Test
    fun decodeAgainstAForeignListRejectsEveryWord() {
        val mnemonic = Bip39.encode(ByteArray(32), words)
        assertNull(Bip39.decode(mnemonic, List(2048) { "word$it" }))
        assertThrows(IllegalArgumentException::class.java) {
            Bip39.decode(mnemonic, List(17) { "word$it" })
        }
    }

    @Test
    fun encodeRejectsInvalidEntropySizes() {
        assertThrows(IllegalArgumentException::class.java) { Bip39.encode(ByteArray(15), words) }
        assertThrows(IllegalArgumentException::class.java) { Bip39.encode(ByteArray(33), words) }
        assertThrows(IllegalArgumentException::class.java) { Bip39.encode(ByteArray(0), words) }
    }

    @Test
    fun splitNormalizesCaseAndWhitespace() {
        val mnemonic = Bip39.encode(ByteArray(32), words)
        val typed = "  " + mnemonic.joinToString("\n\t  ").uppercase() + " "
        val decoded = Bip39.decode(Bip39.split(typed), words)
        assertNotNull(decoded)
        assertArrayEquals(ByteArray(32), decoded)
    }

    @Test
    fun describeJoinsWithSingleSpaces() {
        val mnemonic = Bip39.encode(ByteArray(32) { 0x7f }, words)
        assertEquals(mnemonic.joinToString(" "), Bip39.describe(mnemonic))
        assertEquals(mnemonic, Bip39.split(Bip39.describe(mnemonic)))
    }
}
