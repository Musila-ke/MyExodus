package com.example.myexodus.backup

import java.security.MessageDigest

/**
 * Account plan §6.4/§8.5: the recovery key as a 24-word BIP-39 mnemonic over its raw 256-bit
 * entropy and checksum. The BIP-39 seed derivation (PBKDF2 with "mnemonic"+passphrase) is
 * deliberately NOT applied — the words encode the recovery key itself, so the checksum is the
 * only transform. Same 2048-word English list as the standard (checked against its published
 * SHA-256 in the unit suite).
 */
object Bip39 {

    const val WORD_COUNT = 2048
    const val WORDS_FOR_256_BITS = 24

    /** Encodes 16/20/24/28/32 entropy bytes; production always passes 32 (24 words). */
    fun encode(entropy: ByteArray, words: List<String>): List<String> {
        require(words.size == WORD_COUNT) { "BIP-39 word list must have $WORD_COUNT words" }
        require(entropy.size in ENTROPY_SIZES) { "entropy must be one of $ENTROPY_SIZES bytes" }
        val checksumLength = entropy.size / 4 // bits
        val checksum = MessageDigest.getInstance("SHA-256").digest(entropy)
        val totalBits = entropy.size * 8 + checksumLength
        val out = ArrayList<String>(totalBits / 11)
        var buffer = 0
        var buffered = 0
        for (i in 0 until totalBits) {
            val source = if (i < entropy.size * 8) {
                entropy[i / 8].toInt()
            } else {
                checksum[(i - entropy.size * 8) / 8].toInt()
            }
            val bit = (source ushr (7 - (i % 8))) and 1
            buffer = (buffer shl 1) or bit
            if (++buffered == 11) {
                out += words[buffer]
                buffer = 0
                buffered = 0
            }
        }
        return out
    }

    /**
     * Decodes exactly 24 words and validates the checksum. Null for an unknown word, a wrong
     * word count or a mangled checksum — the caller shows one generic unlock failure.
     */
    fun decode(mnemonic: List<String>, words: List<String>): ByteArray? {
        require(words.size == WORD_COUNT) { "BIP-39 word list must have $WORD_COUNT words" }
        if (mnemonic.size != WORDS_FOR_256_BITS) return null
        val index = wordIndex(words) ?: return null
        val entropy = ByteArray(32)
        var buffer = 0
        var buffered = 0
        var byteIndex = 0
        var checksumByte = 0
        for (word in mnemonic) {
            val value = index[word] ?: return null
            for (shift in 10 downTo 0) {
                buffer = (buffer shl 1) or ((value ushr shift) and 1)
                if (++buffered == 8) {
                    if (byteIndex < 32) entropy[byteIndex] = buffer.toByte() else checksumByte = buffer
                    buffered = 0
                    buffer = 0
                    byteIndex++
                }
            }
        }
        val expected = MessageDigest.getInstance("SHA-256").digest(entropy)[0]
        return if (checksumByte.toByte() == expected) entropy else null
    }

    /** Normalizes user input: case-insensitive, any whitespace runs become separators. */
    fun split(input: String): List<String> =
        input.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

    fun describe(words: List<String>): String = words.joinToString(" ")

    private fun wordIndex(words: List<String>): Map<String, Int>? {
        if (words.size != WORD_COUNT) return null
        val map = HashMap<String, Int>(WORD_COUNT * 2)
        words.forEachIndexed { i, word -> if (map.put(word, i) != null) return null }
        return map
    }

    private val ENTROPY_SIZES = setOf(16, 20, 24, 28, 32)
    private val WHITESPACE = Regex("\\s+")
}
