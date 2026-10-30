package com.example.myexodus.backup

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Account plan §4.3/§8.5: the wire document the server stores and echoes. The server validates
 * exact keys, canonical padded base64 and exact lengths; the read path must reject anything
 * that could steer the KDF or the wrappers.
 */
class BackupKeyBundleTest {

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun fixture() = BackupKeyBundle(
        bundleFormatVersion = BackupCrypto.BUNDLE_FORMAT_VERSION,
        keyId = (0xa0..0xaf).map { it.toByte() }.toByteArray(),
        argon2 = BackupCrypto.ARGON2,
        passphraseSalt = (0x00..0x0f).map { it.toByte() }.toByteArray(),
        passphraseWrapper = WrappedKey(
            nonce = hex("cafebabefacedbaddecaf888"),
            ciphertext = ByteArray(BackupCrypto.WRAP_BYTES) { it.toByte() },
        ),
        recoveryKeyId = (0xb0..0xbf).map { it.toByte() }.toByteArray(),
        recoveryHkdfSalt = (0x10..0x1f).map { it.toByte() }.toByteArray(),
        recoveryWrapper = WrappedKey(
            nonce = hex("deadbeefcafebabe00001111"),
            ciphertext = ByteArray(BackupCrypto.WRAP_BYTES) { (255 - it).toByte() },
        ),
    )

    @Test
    fun wireRoundTripPreservesEveryField() {
        val original = fixture()
        val parsed = BackupKeyBundle.fromWire(original.toWire())
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals(original.bundleFormatVersion, parsed.bundleFormatVersion)
        assertArrayEquals(original.keyId, parsed.keyId)
        assertEquals(original.argon2, parsed.argon2)
        assertArrayEquals(original.passphraseSalt, parsed.passphraseSalt)
        assertArrayEquals(original.passphraseWrapper.nonce, parsed.passphraseWrapper.nonce)
        assertArrayEquals(
            original.passphraseWrapper.ciphertext,
            parsed.passphraseWrapper.ciphertext,
        )
        assertArrayEquals(original.recoveryKeyId, parsed.recoveryKeyId)
        assertArrayEquals(original.recoveryHkdfSalt, parsed.recoveryHkdfSalt)
        assertArrayEquals(original.recoveryWrapper.nonce, parsed.recoveryWrapper.nonce)
        assertArrayEquals(original.recoveryWrapper.ciphertext, parsed.recoveryWrapper.ciphertext)
    }

    @Test
    fun wireUsesCanonicalPaddedStandardBase64() {
        val wire = fixture().toWire()
        val encoded = listOf(
            wire["keyId"] as String,
            wire["passphraseSalt"] as String,
            (wire["passphraseWrapper"] as Map<*, *>)["nonce"] as String,
            (wire["passphraseWrapper"] as Map<*, *>)["ciphertext"] as String,
            wire["recoveryKeyId"] as String,
            wire["recoveryHkdfSalt"] as String,
            (wire["recoveryWrapper"] as Map<*, *>)["nonce"] as String,
            (wire["recoveryWrapper"] as Map<*, *>)["ciphertext"] as String,
        )
        encoded.forEach { value ->
            assertFalse(value.contains('-'))
            assertFalse(value.contains('_'))
            assertFalse(value.contains(' '))
        }
        assertEquals(24, encoded[0].length)
        assertEquals(24, encoded[1].length)
        assertEquals(16, encoded[2].length)
        assertEquals(64, encoded[3].length)
    }

    @Test
    fun fromWire_ignoresServerRevisionAndExtraFields() {
        val wire = fixture().toWire() + mapOf("revision" to 7L, "updatedAt" to "server-time")
        assertNotNull(BackupKeyBundle.fromWire(wire))
        assertEquals(7L, BackupKeyBundle.revisionOf(wire["revision"]))
    }

    @Test
    fun fromWire_rejectsMalformedDocuments() {
        val good = fixture().toWire()
        val cases = listOf(
            emptyMap<String, Any?>(),
            good - "argon2",
            good - "keyId",
            good - "passphraseWrapper",
            good - "recoveryWrapper",
            good + ("bundleFormatVersion" to 2),
            good + ("bundleFormatVersion" to "1"),
            good + ("keyId" to b64(ByteArray(8))),
            good + ("passphraseSalt" to b64(ByteArray(32))),
            good + ("recoveryKeyId" to b64(ByteArray(17))),
            good + ("recoveryHkdfSalt" to b64(ByteArray(15))),
            good + ("keyId" to "not base64!!"),
            good + ("passphraseWrapper" to mapOf("nonce" to b64(ByteArray(12)))),
            good + (
                "passphraseWrapper" to mapOf(
                    "nonce" to b64(ByteArray(16)),
                    "ciphertext" to b64(ByteArray(BackupCrypto.WRAP_BYTES)),
                )
                ),
            good + (
                "recoveryWrapper" to mapOf(
                    "nonce" to b64(ByteArray(12)),
                    "ciphertext" to b64(ByteArray(47)),
                )
                ),
            good + ("argon2" to mapOf("memoryKiB" to 32768, "iterations" to 3)),
        )
        cases.forEach { case ->
            assertNull("expected rejection of $case", BackupKeyBundle.fromWire(case))
        }
    }

    @Test
    fun fromWire_rejectsEveryUnsupportedCostProfile() {
        val good = fixture().toWire()
        val base = BackupCrypto.ARGON2
        listOf(
            mapOf("memoryKiB" to 1024, "iterations" to 3, "parallelism" to 1),
            mapOf("memoryKiB" to 65536, "iterations" to 3, "parallelism" to 1),
            mapOf("memoryKiB" to 32768, "iterations" to 1, "parallelism" to 1),
            mapOf("memoryKiB" to 32768, "iterations" to 3, "parallelism" to 4),
            mapOf("memoryKiB" to 32768, "iterations" to 3, "parallelism" to 0),
            mapOf("memoryKiB" to "32768", "iterations" to 3, "parallelism" to 1),
        ).forEach { argon2 ->
            assertNull(BackupKeyBundle.fromWire(good + ("argon2" to argon2)))
        }
        assertEquals(32768, base.memoryKiB)
    }

    @Test
    fun revisionOf_acceptsEveryNumberTypeFirestoreMayDeliver() {
        assertEquals(1L, BackupKeyBundle.revisionOf(1))
        assertEquals(4_000_000_000L, BackupKeyBundle.revisionOf(4_000_000_000L))
        assertEquals(3L, BackupKeyBundle.revisionOf(3.0))
        assertNull(BackupKeyBundle.revisionOf("3"))
        assertNull(BackupKeyBundle.revisionOf(null))
    }
}
