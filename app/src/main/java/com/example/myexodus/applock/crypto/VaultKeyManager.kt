package com.example.myexodus.applock.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authentication-bound AES-256-GCM wrapping key for the SQLCipher DEK (plan §8.4-§8.6).
 *
 * API 30+: every cipher use needs its own OS authentication
 * (`setUserAuthenticationParameters(0, BIOMETRIC_STRONG | DEVICE_CREDENTIAL)`), matched to a
 * `BiometricPrompt.CryptoObject`. API 26-29: one key with a 30-second legacy validity window
 * (`setUserAuthenticationValidityDurationSeconds(30)`), authorized by the Keyguard
 * confirmation flow — deliberately lower assurance, per plan §8.6.
 *
 * The IV is never caller-supplied for encryption: the Keystore generates it at init and the
 * envelope stores it. Decryption accepts only the strict 12-byte IV the envelope parser
 * validated. Nothing here persists plaintext outside the caller's own arrays.
 */
class VaultKeyManager(
    private val keyLookup: (String) -> SecretKey? = { alias ->
        androidKeyStore().getKey(alias, null) as? SecretKey
    },
    // Keystore generation/deletion cannot run on the JVM (no AndroidKeyStore provider in
    // Robolectric); the seams exist only so enrollment logic is unit-testable, and both
    // defaults are the production Keystore path.
    private val keyGenerator: (String) -> Unit = { alias -> generateAndroidKey(alias) },
    private val keyDeleter: (String) -> Unit = { alias -> androidKeyStore().deleteEntry(alias) },
) {

    fun hasKey(keyVersion: Int = KEY_VERSION): Boolean =
        keyLookup(aliasFor(keyVersion)) != null

    /** Creates the wrapping key once; existing material is never replaced here. */
    fun createKey(keyVersion: Int = KEY_VERSION) {
        if (hasKey(keyVersion)) return
        keyGenerator(aliasFor(keyVersion))
    }

    fun deleteKey(keyVersion: Int = KEY_VERSION) {
        keyDeleter(aliasFor(keyVersion))
    }

    /**
     * ENCRYPT_MODE with no IV parameters — the Keystore generates the IV at init (plan §8.4).
     * The caller must hand this exact cipher to the authenticator before [seal].
     */
    fun encryptionCipher(keyVersion: Int = KEY_VERSION): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey(keyVersion))
        }

    /** The cipher carries `CryptoObject` authentication on API 30+; time-window gated below. */
    fun decryptionCipher(iv: ByteArray, keyVersion: Int = KEY_VERSION): Cipher {
        require(iv.size == VaultEnvelope.IV_BYTES) { "IV must be ${VaultEnvelope.IV_BYTES} bytes" }
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(keyVersion), GCMParameterSpec(VaultEnvelope.GCM_TAG_BITS, iv))
        }
    }

    /**
     * §8.4 encryption half. [cipher] must come from [encryptionCipher] (or a software key in
     * tests) and, on a real device, must have been authenticated via its `CryptoObject`.
     */
    fun seal(
        cipher: Cipher,
        secret: ByteArray,
        applicationId: String,
        databaseId: String,
        keyVersion: Int = KEY_VERSION,
        kind: Int = VaultEnvelope.KIND_RULES_DEK,
    ): ByteArray {
        cipher.updateAAD(VaultEnvelope.canonicalAad(applicationId, databaseId, keyVersion, kind))
        val ciphertext = cipher.doFinal(secret)
        val iv = cipher.iv ?: throw VaultEnvelopeException("cipher produced no IV")
        return VaultEnvelope.encode(
            VaultEnvelope.Envelope(keyVersion = keyVersion, kind = kind, iv = iv, ciphertext = ciphertext),
        )
    }

    /**
     * §8.4 decryption half. Parses strictly, derives the AAD from the envelope's own bound
     * fields, then authenticates before releasing plaintext: a wrong alias, wrong database,
     * wrong version or any tampered byte throws (AEADBadTagException / [VaultEnvelopeException])
     * and no secret is returned.
     */
    fun open(
        encoded: ByteArray,
        applicationId: String,
        databaseId: String,
        authenticatedCipher: Cipher? = null,
    ): ByteArray {
        val envelope = VaultEnvelope.parse(encoded)
        val cipher = authenticatedCipher ?: decryptionCipher(envelope.iv, envelope.keyVersion)
        val cipherIv = cipher.iv
        if (cipherIv != null && !cipherIv.contentEquals(envelope.iv)) {
            throw VaultEnvelopeException("cipher IV does not match the envelope")
        }
        cipher.updateAAD(
            VaultEnvelope.canonicalAad(applicationId, databaseId, envelope.keyVersion, envelope.kind),
        )
        return cipher.doFinal(envelope.ciphertext)
    }

    private fun secretKey(keyVersion: Int): SecretKey =
        keyLookup(aliasFor(keyVersion))
            ?: throw VaultEnvelopeException("no wrapping key for version $keyVersion")

    private fun aliasFor(keyVersion: Int): String = when (keyVersion) {
        KEY_VERSION -> ALIAS_V1
        else -> throw VaultEnvelopeException("unsupported key version $keyVersion")
    }

    companion object {
        const val KEY_VERSION: Int = 1
        const val ALIAS_V1: String = "myexodus_vault_kek_v1"

        /** Legacy API 26-29 time-bound window (§8.6). */
        const val LEGACY_WINDOW_SECONDS: Int = 30

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256

        private fun androidKeyStore(): KeyStore =
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        private fun generateAndroidKey(alias: String) {
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(LEGACY_WINDOW_SECONDS)
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(builder.build())
            generator.generateKey()
        }
    }
}
