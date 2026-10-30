package com.example.myexodus.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.R
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account plan §8.5: both lists are pinned by their published SHA-256. These are byte-for-byte
 * the official BIP-39 English list and the EFF large wordlist (die-roll column included).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupWordlistsTest {

    private lateinit var context: Context
    private lateinit var wordlists: BackupWordlists

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        wordlists = BackupWordlists(context)
    }

    private fun sha256(resourceId: Int): String {
        val bytes = context.resources.openRawResource(resourceId).use { it.readBytes() }
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun bip39List_isTheOfficialList() {
        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            sha256(R.raw.bip39_english),
        )
        assertEquals(2048, wordlists.bip39.size)
        assertEquals("abandon", wordlists.bip39.first())
        assertEquals("zoo", wordlists.bip39.last())
    }

    @Test
    fun effList_isTheOfficialList() {
        assertEquals(
            "addd35536511597a02fa0a9ff1e5284677b8883b83e986e43f15a3db996b903e",
            sha256(R.raw.eff_large_wordlist),
        )
        assertEquals(7776, wordlists.eff.size)
        assertEquals("abacus", wordlists.eff.first())
        assertEquals("zoom", wordlists.eff.last())
    }

    @Test
    fun listsContainNoDuplicatesOrDieRollNumbers() {
        assertEquals(2048, wordlists.bip39.toSet().size)
        assertEquals(7776, wordlists.eff.toSet().size)
        assertTrue(wordlists.eff.none { it.any(Char::isDigit) })
        assertTrue(wordlists.eff.none { it.contains('\t') })
        assertTrue(wordlists.bip39.all { it == it.lowercase() })
    }

    @Test
    fun generatedPassphrase_isSixIndependentEffWords() {
        val eff = wordlists.eff.toSet()
        val first = wordlists.generatePassphrase()
        assertEquals(BackupWordlists.GENERATED_WORDS, first.size)
        assertTrue(first.all { it in eff })
        assertTrue(wordlists.generatePassphrase().all { it in eff })
    }
}
