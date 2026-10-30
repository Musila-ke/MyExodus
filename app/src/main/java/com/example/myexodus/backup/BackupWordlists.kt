package com.example.myexodus.backup

import android.content.Context
import com.example.myexodus.R
import java.io.InputStream
import java.security.SecureRandom

/**
 * Account plan §8.5: the two reviewed word lists, loaded from `res/raw`. The BIP-39 list is
 * the official English 2048 (its published SHA-256 is pinned in the unit suite); the EFF large
 * list is the 7,776-word CC-BY list. A wrong count or a duplicate means a corrupted build
 * resource and fails fast rather than silently weakening a mnemonic or a generated passphrase.
 */
class BackupWordlists(context: Context) {

    val bip39: List<String> = load(
        input = context.resources.openRawResource(R.raw.bip39_english),
        expected = Bip39.WORD_COUNT,
        transform = { line -> line.trim().lowercase() },
    )

    val eff: List<String> = load(
        input = context.resources.openRawResource(R.raw.eff_large_wordlist),
        expected = 7776,
        transform = ::effWord,
    )

    private val secureRandom = SecureRandom()

    /** Six words, each an independent uniform draw — `nextInt(bound)` is unbiased. */
    fun generatePassphrase(): List<String> =
        List(GENERATED_WORDS) { eff[secureRandom.nextInt(eff.size)] }

    private fun load(input: InputStream, expected: Int, transform: (String) -> String?): List<String> {
        val words = input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull(transform).filter { it.isNotEmpty() }.toList()
        }
        check(words.size == expected) { "word list has ${words.size} words, expected $expected" }
        check(words.toHashSet().size == expected) { "word list contains duplicates" }
        return words
    }

    private fun effWord(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        // "11111\tabacus" — keep only the word half, never the die-roll number.
        return trimmed.substringAfter('\t', trimmed).trim().split(WHITESPACE).last()
    }

    companion object {
        const val GENERATED_WORDS = 6
        private val WHITESPACE = Regex("\\s+")
    }
}
