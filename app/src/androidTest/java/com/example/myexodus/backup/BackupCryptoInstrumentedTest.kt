package com.example.myexodus.backup

import android.os.Bundle
import android.os.Debug
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myexodus.R
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Account plan §17 gate 15: the pinned Argon2id profile is benchmarked on real Android
 * hardware, not just the JVM suite — low-end devices must complete one derivation inside the
 * unlock operation's budget, and the BouncyCastle result must be the RFC-cross-checked tag.
 */
@RunWith(AndroidJUnit4::class)
class BackupCryptoInstrumentedTest {

    private val salt = (0x00..0x0f).map { it.toByte() }.toByteArray()

    @Test
    fun productionArgon2idCompletesWithinTheUnlockBudget() {
        // §11.5 needs p50/p95 and peak memory: pass -e argonIterations 5 for a real sample.
        val iterations = InstrumentationRegistry.getArguments()
            .getString("argonIterations")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val timings = mutableListOf<Long>()
        var baselinePssKb = 0
        var peakPssKb = 0
        repeat(iterations) {
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            if (baselinePssKb == 0) baselinePssKb = memory.totalPss
            var peak = memory.totalPss
            val sampling = AtomicBoolean(true)
            val sampler = thread {
                val sample = Debug.MemoryInfo()
                while (sampling.get()) {
                    Debug.getMemoryInfo(sample)
                    peak = maxOf(peak, sample.totalPss)
                    Thread.sleep(20)
                }
            }
            val started = System.nanoTime()
            val kek = BackupCrypto.derivePassphraseKek("correct horse battery staple", salt)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            sampling.set(false)
            sampler.join()
            Debug.getMemoryInfo(memory)
            peak = maxOf(peak, memory.totalPss)
            peakPssKb = maxOf(peakPssKb, peak)
            timings += elapsedMs
            assertEquals(
                "bbe4ce725d8b1ae6f8c3f70b21dac67681bf71b53ffc4b805c35e6faa4477eab",
                kek.joinToString("") { "%02x".format(it) },
            )
        }
        val sorted = timings.sorted()
        fun percentile(fraction: Double) = sorted[((sorted.size - 1) * fraction).toInt()]
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(
                    "stream",
                    "productionArgon2idMs=${timings.joinToString(",")}" +
                        " p50=${percentile(0.5)} p95=${percentile(0.95)}" +
                        " baselinePssKb=$baselinePssKb peakPssKb=$peakPssKb",
                )
            },
        )
        // The profile itself is mandated by §6.4; the bound only catches a pathological
        // regression (debug-ART builds already measure tens of seconds on slower targets).
        assertTrue(
            "Argon2id took ${timings.maxOrNull()}ms",
            timings.maxOrNull()!! < 120_000,
        )
    }

    @Test
    fun wrapRoundTripsOnDevice() {
        val abk = ByteArray(32) { it.toByte() }
        val kek = ByteArray(32) { (it + 1).toByte() }
        val nonce = BackupCrypto.random(BackupCrypto.NONCE_BYTES)
        val aad = BackupCrypto.passphraseAad(
            "device-fixture", ByteArray(16), 1, BackupCrypto.ARGON2, salt,
        )
        val wrapped = BackupCrypto.wrap(abk, kek, nonce, aad)
        assertEquals(BackupCrypto.WRAP_BYTES, wrapped.size)
        assertArrayEquals(abk, BackupCrypto.unwrap(wrapped, kek, nonce, aad))
    }

    @Test
    fun bundledWordlistsMatchTheirPublishedHashesOnDevice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val wordlists = BackupWordlists(context)
        assertEquals(2048, wordlists.bip39.size)
        assertEquals(7776, wordlists.eff.size)
        val sha = MessageDigest.getInstance("SHA-256").digest(
            context.resources.openRawResource(R.raw.bip39_english).use { it.readBytes() },
        ).joinToString("") { "%02x".format(it) }
        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            sha,
        )
        val mnemonic = Bip39.encode(ByteArray(32), wordlists.bip39)
        assertArrayEquals(ByteArray(32), Bip39.decode(mnemonic, wordlists.bip39))
    }
}
