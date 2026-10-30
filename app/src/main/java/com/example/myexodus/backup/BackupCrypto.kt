package com.example.myexodus.backup

import com.example.myexodus.accountability.crypto.Hkdf
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Account plan §6.4/§8.5: the ABK wrapping contract. The pinned Argon2id implementation is
 * BouncyCastle's pure-Java `Argon2BytesGenerator` — already a direct app dependency, no ABI or
 * 16 KB-page surface, and covered by the RFC 9106 known-answer vectors in the unit suite.
 * Nothing here logs; wrapped blobs, salts, nonces, passphrases and keys never leave memory.
 */
object BackupCrypto {

    const val BUNDLE_FORMAT_VERSION = 1
    const val ABK_BYTES = 32
    const val KEY_ID_BYTES = 16
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    /** 32-byte ABK plus the 16-byte AES-GCM tag. */
    const val WRAP_BYTES = 48

    /**
     * §8.5: the only accepted parameter set. §9: a bundle carrying anything else is rejected
     * before any KDF memory is allocated — server-side data never chooses the cost profile.
     */
    data class Argon2Params(
        val memoryKiB: Int,
        val iterations: Int,
        val parallelism: Int,
    ) {
        val supported: Boolean get() = this == ARGON2
    }

    val ARGON2 = Argon2Params(memoryKiB = 32768, iterations = 3, parallelism = 1)

    const val RECOVERY_INFO = "MyExodus ABK recovery wrap v1"

    /** The §8.5 AAD prefixes, each including its trailing NUL. */
    const val PASSPHRASE_AAD_PREFIX = "MYEXODUS-ABK-PASSPHRASE-WRAP-V1\u0000"
    const val RECOVERY_AAD_PREFIX = "MYEXODUS-ABK-RECOVERY-WRAP-V1\u0000"

    /** Wrapper-kind and KDF identifiers bound into the AAD (deterministic fixtures, §8.5). */
    const val WRAPPER_KIND_PASSPHRASE = 0x01
    const val WRAPPER_KIND_RECOVERY = 0x02
    const val KDF_ID_ARGON2ID = 0x01

    private val secureRandom = SecureRandom()

    fun random(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

    /** Argon2id with the pinned set, always at version 0x13. */
    fun derivePassphraseKek(
        passphrase: String,
        salt: ByteArray,
        params: Argon2Params = ARGON2,
    ): ByteArray {
        require(params.supported) { "unsupported Argon2 parameters" }
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        return argon2(
            type = Argon2Parameters.ARGON2_id,
            password = passphrase.toByteArray(Charsets.UTF_8),
            salt = salt,
            secret = null,
            associatedData = null,
            params = params,
            length = ABK_BYTES,
        )
    }

    fun deriveRecoveryKek(recoveryKey: ByteArray, hkdfSalt: ByteArray): ByteArray {
        require(recoveryKey.size == ABK_BYTES) { "recovery key must be $ABK_BYTES bytes" }
        require(hkdfSalt.size == SALT_BYTES) { "HKDF salt must be $SALT_BYTES bytes" }
        return Hkdf.derive(
            salt = hkdfSalt,
            ikm = recoveryKey,
            info = RECOVERY_INFO.toByteArray(Charsets.UTF_8),
            length = ABK_BYTES,
        )
    }

    /**
     * Exposed for the RFC 9106 vectors, which exercise the Argon2d/i/id types with a secret and
     * associated data that production never uses.
     */
    internal fun argon2(
        type: Int,
        password: ByteArray,
        salt: ByteArray,
        secret: ByteArray?,
        associatedData: ByteArray?,
        params: Argon2Params,
        length: Int,
    ): ByteArray {
        val builder = Argon2Parameters.Builder(type)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .withSalt(salt)
        if (secret != null) builder.withSecret(secret)
        if (associatedData != null) builder.withAdditional(associatedData)
        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val out = ByteArray(length)
        generator.generateBytes(password, out)
        return out
    }

    fun wrap(abk: ByteArray, kek: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        require(abk.size == ABK_BYTES) { "ABK must be $ABK_BYTES bytes" }
        require(kek.size == ABK_BYTES) { "KEK must be $ABK_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(abk)
    }

    /** Null on a tag failure: the caller shows one generic unlock failure (§9). */
    fun unwrap(wrapped: ByteArray, kek: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray? {
        if (wrapped.size != WRAP_BYTES || kek.size != ABK_BYTES || nonce.size != NONCE_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(wrapped)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    fun passphraseAad(
        uid: String,
        keyId: ByteArray,
        bundleFormatVersion: Int,
        params: Argon2Params,
        salt: ByteArray,
    ): ByteArray {
        require(keyId.size == KEY_ID_BYTES)
        require(salt.size == SALT_BYTES)
        return aad(
            prefix = PASSPHRASE_AAD_PREFIX,
            uid = uid,
            parts = listOf(
                keyId,
                uint32(bundleFormatVersion),
                byte(WRAPPER_KIND_PASSPHRASE),
                byte(KDF_ID_ARGON2ID),
                uint32(params.memoryKiB),
                uint32(params.iterations),
                uint32(params.parallelism),
                salt,
            ),
        )
    }

    fun recoveryAad(
        uid: String,
        keyId: ByteArray,
        recoveryKeyId: ByteArray,
        bundleFormatVersion: Int,
        hkdfSalt: ByteArray,
    ): ByteArray {
        require(keyId.size == KEY_ID_BYTES)
        require(recoveryKeyId.size == KEY_ID_BYTES)
        require(hkdfSalt.size == SALT_BYTES)
        return aad(
            prefix = RECOVERY_AAD_PREFIX,
            uid = uid,
            parts = listOf(
                keyId,
                recoveryKeyId,
                uint32(bundleFormatVersion),
                byte(WRAPPER_KIND_RECOVERY),
                hkdfSalt,
            ),
        )
    }

    /** Constant-time comparison for unwrapped key material. */
    fun sameSecret(a: ByteArray?, b: ByteArray?): Boolean =
        a != null && b != null && MessageDigest.isEqual(a, b)

    private fun aad(prefix: String, uid: String, parts: List<ByteArray>): ByteArray {
        val uidBytes = uid.toByteArray(Charsets.UTF_8)
        require(uidBytes.isNotEmpty() && uidBytes.size <= 0xFFFF) { "UID length out of range" }
        val out = ByteArrayOutputStream(prefix.length + 2 + uidBytes.size + parts.sumOf { it.size })
        out.write(prefix.toByteArray(Charsets.US_ASCII))
        out.write((uidBytes.size ushr 8) and 0xFF)
        out.write(uidBytes.size and 0xFF)
        out.write(uidBytes)
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun uint32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun byte(value: Int): ByteArray = byteArrayOf(value.toByte())
}
