package com.example.myexodus.backup

import java.util.Base64

/** An AES-256-GCM wrapper: the 12-byte nonce plus ciphertext+tag. */
data class WrappedKey(val nonce: ByteArray, val ciphertext: ByteArray)

/**
 * Account plan §4.3/§8.5: the wrapped-ABK key bundle exactly as `users/{uid}/backupControl/
 * keyBundle` stores it. The server never sees the ABK, the passphrase or the recovery key.
 */
data class BackupKeyBundle(
    val bundleFormatVersion: Int,
    val keyId: ByteArray,
    val argon2: BackupCrypto.Argon2Params,
    val passphraseSalt: ByteArray,
    val passphraseWrapper: WrappedKey,
    val recoveryKeyId: ByteArray,
    val recoveryHkdfSalt: ByteArray,
    val recoveryWrapper: WrappedKey,
) {

    fun toWire(): Map<String, Any?> = mapOf(
        "bundleFormatVersion" to bundleFormatVersion,
        "keyId" to b64(keyId),
        "argon2" to mapOf(
            "memoryKiB" to argon2.memoryKiB,
            "iterations" to argon2.iterations,
            "parallelism" to argon2.parallelism,
        ),
        "passphraseSalt" to b64(passphraseSalt),
        "passphraseWrapper" to mapOf(
            "nonce" to b64(passphraseWrapper.nonce),
            "ciphertext" to b64(passphraseWrapper.ciphertext),
        ),
        "recoveryKeyId" to b64(recoveryKeyId),
        "recoveryHkdfSalt" to b64(recoveryHkdfSalt),
        "recoveryWrapper" to mapOf(
            "nonce" to b64(recoveryWrapper.nonce),
            "ciphertext" to b64(recoveryWrapper.ciphertext),
        ),
    )

    /** A read-path parse; null means the stored document is malformed and must not be used. */
    companion object {
        fun fromWire(map: Map<String, Any?>): BackupKeyBundle? {
            val argon2 = map["argon2"] as? Map<*, *> ?: return null
            val params = BackupCrypto.Argon2Params(
                memoryKiB = number(argon2["memoryKiB"])?.toInt() ?: return null,
                iterations = number(argon2["iterations"])?.toInt() ?: return null,
                parallelism = number(argon2["parallelism"])?.toInt() ?: return null,
            )
            // §9: refuse an unknown cost profile here, before any caller can run Argon2id.
            if (!params.supported) return null
            val version = number(map["bundleFormatVersion"])?.toInt() ?: return null
            if (version != BackupCrypto.BUNDLE_FORMAT_VERSION) return null
            return try {
                BackupKeyBundle(
                    bundleFormatVersion = version,
                    keyId = bytesOfLength(map["keyId"], BackupCrypto.KEY_ID_BYTES) ?: return null,
                    argon2 = params,
                    passphraseSalt = bytesOfLength(map["passphraseSalt"], BackupCrypto.SALT_BYTES)
                        ?: return null,
                    passphraseWrapper = wrapper(map["passphraseWrapper"]) ?: return null,
                    recoveryKeyId = bytesOfLength(map["recoveryKeyId"], BackupCrypto.KEY_ID_BYTES)
                        ?: return null,
                    recoveryHkdfSalt = bytesOfLength(
                        map["recoveryHkdfSalt"],
                        BackupCrypto.SALT_BYTES,
                    ) ?: return null,
                    recoveryWrapper = wrapper(map["recoveryWrapper"]) ?: return null,
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        fun revisionOf(value: Any?): Long? = number(value)?.toLong()

        private fun wrapper(value: Any?): WrappedKey? {
            val map = value as? Map<*, *> ?: return null
            return WrappedKey(
                nonce = bytesOfLength(map["nonce"], BackupCrypto.NONCE_BYTES) ?: return null,
                ciphertext = bytesOfLength(map["ciphertext"], BackupCrypto.WRAP_BYTES) ?: return null,
            )
        }

        private fun bytesOfLength(value: Any?, expected: Int): ByteArray? =
            bytes(value)?.takeIf { it.size == expected }

        private fun bytes(value: Any?): ByteArray? = try {
            (value as? String)?.let { Base64.getDecoder().decode(it) }
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun number(value: Any?): Number? = value as? Number
    }
}

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** A bundle plus the server-assigned monotonic revision. */
data class RemoteBundle(val revision: Long, val bundle: BackupKeyBundle)
