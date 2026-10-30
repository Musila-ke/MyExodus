package com.example.myexodus.backup

import com.example.myexodus.accountability.crypto.Hkdf
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account plan §8.5: deterministic fixtures for the whole wrapping contract. The Argon2 tags
 * are RFC 9106 §5's, the production-profile tag was cross-checked against the C reference
 * (argon2-cffi 21.3.0), the HKDF tag against an independent HMAC-SHA256 implementation, and the
 * GCM tag against OpenSSL — none is a self-certified BouncyCastle/JCE output.
 */
class BackupCryptoTest {

    private val salt = (0x00..0x0f).map { it.toByte() }.toByteArray()
    private val keyId = (0xa0..0xaf).map { it.toByte() }.toByteArray()
    private val recoveryKeyId = (0xb0..0xbf).map { it.toByte() }.toByteArray()

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun toHex(value: ByteArray): String = value.joinToString("") { "%02x".format(it) }

    private fun rfcParams() = BackupCrypto.Argon2Params(
        memoryKiB = 32,
        iterations = 3,
        parallelism = 4,
    )

    private fun rfcInputs(): Pair<ByteArray, ByteArray> = Pair(
        ByteArray(32) { 0x01 },
        ByteArray(16) { 0x02 },
    )

    @Test
    fun argon2d_rfc9106() {
        val (password, rfcSalt) = rfcInputs()
        val tag = BackupCrypto.argon2(
            type = Argon2Parameters.ARGON2_d,
            password = password,
            salt = rfcSalt,
            secret = ByteArray(8) { 0x03 },
            associatedData = ByteArray(12) { 0x04 },
            params = rfcParams(),
            length = 32,
        )
        assertEquals(
            "512b391b6f1162975371d30919734294f868e3be3984f3c1a13a4db9fabe4acb",
            toHex(tag),
        )
    }

    @Test
    fun argon2i_rfc9106() {
        val (password, rfcSalt) = rfcInputs()
        val tag = BackupCrypto.argon2(
            type = Argon2Parameters.ARGON2_i,
            password = password,
            salt = rfcSalt,
            secret = ByteArray(8) { 0x03 },
            associatedData = ByteArray(12) { 0x04 },
            params = rfcParams(),
            length = 32,
        )
        assertEquals(
            "c814d9d1dc7f37aa13f0d77f2494bda1c8de6b016dd388d29952a4c4672b6ce8",
            toHex(tag),
        )
    }

    @Test
    fun argon2id_rfc9106() {
        val (password, rfcSalt) = rfcInputs()
        val tag = BackupCrypto.argon2(
            type = Argon2Parameters.ARGON2_id,
            password = password,
            salt = rfcSalt,
            secret = ByteArray(8) { 0x03 },
            associatedData = ByteArray(12) { 0x04 },
            params = rfcParams(),
            length = 32,
        )
        assertEquals(
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
            toHex(tag),
        )
    }

    @Test
    fun argon2id_productionProfileMatchesReferenceImplementation() {
        val kek = BackupCrypto.derivePassphraseKek("correct horse battery staple", salt)
        assertEquals(
            "bbe4ce725d8b1ae6f8c3f70b21dac67681bf71b53ffc4b805c35e6faa4477eab",
            toHex(kek),
        )
    }

    @Test
    fun derivePassphraseKek_rejectsUnsupportedCostProfile() {
        listOf(
            BackupCrypto.Argon2Params(memoryKiB = 1024, iterations = 3, parallelism = 1),
            BackupCrypto.Argon2Params(memoryKiB = 32768, iterations = 2, parallelism = 1),
            BackupCrypto.Argon2Params(memoryKiB = 32768, iterations = 3, parallelism = 4),
        ).forEach { params ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupCrypto.derivePassphraseKek("irrelevant", salt, params)
            }
        }
    }

    @Test
    fun derivePassphraseKek_rejectsWrongSaltLength() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.derivePassphraseKek("irrelevant", ByteArray(8))
        }
    }

    @Test
    fun onlyPinnedCostProfileIsSupported() {
        assertTrue(BackupCrypto.ARGON2.supported)
        assertFalse(BackupCrypto.ARGON2.copy(memoryKiB = 65536).supported)
    }

    @Test
    fun recoveryKek_matchesIndependentHkdf() {
        val recoveryKey = (0x00..0x1f).map { it.toByte() }.toByteArray()
        val kek = BackupCrypto.deriveRecoveryKek(recoveryKey, salt)
        assertEquals(
            "30da5240e9ac58c248833d9b42e37a87212293d584044ec960b187e28d871bc8",
            toHex(kek),
        )
        assertEquals(
            "MyExodus ABK recovery wrap v1",
            BackupCrypto.RECOVERY_INFO,
        )
    }

    @Test
    fun recoveryKek_bindsSaltAndInputLength() {
        val recoveryKey = (0x00..0x1f).map { it.toByte() }.toByteArray()
        val first = BackupCrypto.deriveRecoveryKek(recoveryKey, salt)
        val otherSalt = salt.copyOf().also { it[0] = 0x7f }
        assertFalse(first.contentEquals(BackupCrypto.deriveRecoveryKek(recoveryKey, otherSalt)))
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.deriveRecoveryKek(ByteArray(16), salt)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.deriveRecoveryKek(recoveryKey, ByteArray(32))
        }
    }

    @Test
    fun wrap_matchesIndependentAesGcm() {
        val abk = (0x00..0x1f).map { it.toByte() }.toByteArray()
        val kek = (0x20..0x3f).map { it.toByte() }.toByteArray()
        val nonce = hex("cafebabefacedbaddecaf888")
        val aad = BackupCrypto.passphraseAad(
            "uid-fixture-1",
            keyId,
            BackupCrypto.BUNDLE_FORMAT_VERSION,
            BackupCrypto.ARGON2,
            salt,
        )
        val wrapped = BackupCrypto.wrap(abk, kek, nonce, aad)
        assertEquals(BackupCrypto.WRAP_BYTES, wrapped.size)
        assertEquals(
            "87fee4747cb5838b756ed62b9fca2da7dc5ef5acd33b27df3bfb61baea81222f" +
                "2696037f19e27d72089150c2626d247a",
            toHex(wrapped),
        )
        assertArrayEquals(abk, BackupCrypto.unwrap(wrapped, kek, nonce, aad))
    }

    @Test
    fun unwrap_rejectsTamperedWrapper() {
        val abk = ByteArray(32) { 7 }
        val kek = ByteArray(32) { 9 }
        val nonce = ByteArray(12) { 3 }
        val aad = ByteArray(16) { 1 }
        val wrapped = BackupCrypto.wrap(abk, kek, nonce, aad)

        val badCiphertext = wrapped.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(BackupCrypto.unwrap(badCiphertext, kek, nonce, aad))

        assertNull(BackupCrypto.unwrap(wrapped, ByteArray(32) { 10 }, nonce, aad))
        assertNull(BackupCrypto.unwrap(wrapped, kek, ByteArray(12) { 4 }, aad))
        assertNull(BackupCrypto.unwrap(wrapped, kek, nonce, aad.copyOf().also { it[0] = 2 }))

        assertNull(BackupCrypto.unwrap(wrapped.copyOf(wrapped.size - 1), kek, nonce, aad))
        assertNull(BackupCrypto.unwrap(wrapped, kek, ByteArray(16), aad))
    }

    @Test
    fun unwrap_isBoundToUid() {
        val abk = ByteArray(32) { 11 }
        val kek = ByteArray(32) { 12 }
        val nonce = ByteArray(12) { 13 }
        val aadA = BackupCrypto.passphraseAad(
            "uid-a", keyId, 1, BackupCrypto.ARGON2, salt,
        )
        val aadB = BackupCrypto.passphraseAad(
            "uid-b", keyId, 1, BackupCrypto.ARGON2, salt,
        )
        val wrapped = BackupCrypto.wrap(abk, kek, nonce, aadA)
        assertNotNull(BackupCrypto.unwrap(wrapped, kek, nonce, aadA))
        assertNull(BackupCrypto.unwrap(wrapped, kek, nonce, aadB))
    }

    @Test
    fun passphraseAad_exactByteLayout() {
        val aad = BackupCrypto.passphraseAad(
            "uid-fixture-1",
            keyId,
            BackupCrypto.BUNDLE_FORMAT_VERSION,
            BackupCrypto.ARGON2,
            salt,
        )
        assertEquals(
            "4d5945584f4455532d41424b2d504153535048524153452d575241502d563100" +
                "000d7569642d666978747572652d31" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf" +
                "00000001" +
                "01" +
                "01" +
                "00008000" +
                "00000003" +
                "00000001" +
                "000102030405060708090a0b0c0d0e0f",
            toHex(aad),
        )
    }

    @Test
    fun recoveryAad_exactByteLayout() {
        val aad = BackupCrypto.recoveryAad(
            "uid-fixture-1",
            keyId,
            recoveryKeyId,
            BackupCrypto.BUNDLE_FORMAT_VERSION,
            salt,
        )
        assertEquals(
            "4d5945584f4455532d41424b2d5245434f564552592d575241502d563100" +
                "000d7569642d666978747572652d31" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf" +
                "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                "00000001" +
                "02" +
                "000102030405060708090a0b0c0d0e0f",
            toHex(aad),
        )
    }

    @Test
    fun aad_usesUtf8ByteLengthForUid() {
        val aad = BackupCrypto.passphraseAad(
            "üser-1",
            keyId,
            BackupCrypto.BUNDLE_FORMAT_VERSION,
            BackupCrypto.ARGON2,
            salt,
        )
        assertEquals(
            "4d5945584f4455532d41424b2d504153535048524153452d575241502d563100" +
                "0007c3bc7365722d31",
            toHex(aad).take(2 * (32 + 2 + 7)),
        )
        assertEquals(2, "ü".toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun passphraseAad_bindsEveryField() {
        val base = BackupCrypto.passphraseAad(
            "uid-fixture-1", keyId, 1, BackupCrypto.ARGON2, salt,
        )
        val variants = listOf(
            BackupCrypto.passphraseAad("uid-fixture-2", keyId, 1, BackupCrypto.ARGON2, salt),
            BackupCrypto.passphraseAad(
                "uid-fixture-1", keyId.copyOf().also { it[0] = 1 }, 1, BackupCrypto.ARGON2, salt,
            ),
            BackupCrypto.passphraseAad("uid-fixture-1", keyId, 2, BackupCrypto.ARGON2, salt),
            BackupCrypto.passphraseAad(
                "uid-fixture-1", keyId, 1, BackupCrypto.ARGON2.copy(memoryKiB = 65536), salt,
            ),
            BackupCrypto.passphraseAad(
                "uid-fixture-1", keyId, 1, BackupCrypto.ARGON2, salt.copyOf().also { it[1] = 9 },
            ),
        )
        variants.forEach { variant ->
            assertFalse(toHex(base) == toHex(variant))
        }
        // Wrapper-kind byte: passphrase layout at 48 + 16 + 4, recovery layout at 45 + 32 + 4.
        assertEquals(0x01, base[68].toInt())
        val recovery = BackupCrypto.recoveryAad("uid-fixture-1", keyId, recoveryKeyId, 1, salt)
        assertEquals(0x02, recovery[81].toInt())
    }

    @Test
    fun aad_rejectsEmptyOrOversizedUid() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.passphraseAad("", keyId, 1, BackupCrypto.ARGON2, salt)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.passphraseAad("u".repeat(0x10000), keyId, 1, BackupCrypto.ARGON2, salt)
        }
    }

    @Test
    fun aad_rejectsWrongKeyOrSaltLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.passphraseAad("uid", ByteArray(8), 1, BackupCrypto.ARGON2, salt)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.passphraseAad("uid", keyId, 1, BackupCrypto.ARGON2, ByteArray(8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.recoveryAad("uid", keyId, ByteArray(8), 1, salt)
        }
    }

    @Test
    fun sameSecret_isNullSafeAndLengthAware() {
        assertTrue(BackupCrypto.sameSecret(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(BackupCrypto.sameSecret(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(BackupCrypto.sameSecret(null, byteArrayOf(1, 2)))
        assertFalse(BackupCrypto.sameSecret(byteArrayOf(1, 2), null))
        assertFalse(BackupCrypto.sameSecret(null, null))
    }

    @Test
    fun randomBytesHaveTheRequestedLengthAndDoNotRepeat() {
        assertEquals(12, BackupCrypto.random(BackupCrypto.NONCE_BYTES).size)
        assertEquals(16, BackupCrypto.random(BackupCrypto.SALT_BYTES).size)
        assertEquals(0, BackupCrypto.random(0).size)
        val nonces = (1..2048).map { toHex(BackupCrypto.random(BackupCrypto.NONCE_BYTES)) }
        assertEquals(nonces.size, nonces.toSet().size)
    }

    @Test
    fun hkdfWrapperAgreesWithRfc5869TestOne() {
        val ikm = ByteArray(22) { 0x0b }
        val hkdfSalt = (0x00..0x0c).map { it.toByte() }.toByteArray()
        val info = (0xf0..0xf9).map { it.toByte() }.toByteArray()
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            toHex(Hkdf.derive(hkdfSalt, ikm, info, 42)),
        )
    }
}
