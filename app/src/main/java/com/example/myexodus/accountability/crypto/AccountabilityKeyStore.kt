package com.example.myexodus.accountability.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §8.1 dual-key engine. Two hardware-backed P-256 keys per device:
 * - [SIGNING_ALIAS]: ECDSA identity key, PURPOSE_SIGN|VERIFY, biometric-authenticated
 *   (900s timeout per NFR4), attested with a fresh challenge at generation.
 * - [AGREEMENT_ALIAS]: ECDH key-agreement key, PURPOSE_AGREE_KEY, never user-authenticated
 *   (derivation must not prompt; plan §5). Destroying this key is the crypto-shredding lever.
 *
 * §19.5 multi-pair: from schema v12 each pairing gets its OWN agreement keypair
 * ([pairAgreementAliasOf], destroyed pair-scoped on unpair), so shredding one pair leaves the
 * rest intact. [AGREEMENT_ALIAS] remains for pre-v12 enrollments (whose records carry no
 * alias) and as the AD-22 wipe canary — it is destroyed only when the last enrollment goes.
 *
 * StrongBox is requested first; on [StrongBoxUnavailableException] the key is retried on TEE
 * (plan §9). Attestation is only requested for the signing key (§8.1 sets the challenge
 * there only).
 */
@Singleton
open class AccountabilityKeyStore @Inject constructor() {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Result of key generation; [securityLevel] is where the keys actually landed. */
    sealed interface GenerationResult {
        data class Success(val strongBox: Boolean) : GenerationResult
        /** StrongBox requested but unavailable; TEE fallback succeeded. §9: log telemetry. */
        data class StrongBoxFallback(val strongBox: Boolean, val reason: String) : GenerationResult
        data class Failed(val error: String) : GenerationResult
    }

    /** Generates both keys if missing. No-op when both already exist. */
    fun ensureKeys(): GenerationResult {
        if (hasSigningKey() && hasAgreementKey()) return GenerationResult.Success(isStrongBoxBacked())
        return generateKeys()
    }

    fun generateKeys(): GenerationResult {
        // A previous attempt may have died between the two generations (e.g. agreement-key
        // UnsupportedOperationException on API<31); retrying with one key present would die
        // on KeyExistsException. Pair generation is atomic: clean either side first.
        deleteKeysIfPartial()
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return try {
            generateSigningKey(challenge, strongBox = true)
            generateAgreementKey(strongBox = true)
            GenerationResult.Success(strongBox = true)
        } catch (e: Exception) {
            // StrongBoxUnavailableException is API 28+; the walker below is version-guarded.
            if (isStrongBoxUnavailable(e)) {
                Log.w(TAG, "StrongBox unavailable, falling back to TEE: ${e.message}")
                fallbackToTee(challenge, e)
            } else {
                GenerationResult.Failed(e.message ?: "key generation failed")
            }
        }
    }

    private fun fallbackToTee(challenge: ByteArray, cause: Throwable): GenerationResult =
        try {
            deleteKeysIfPartial()
            generateSigningKey(challenge, strongBox = false)
            generateAgreementKey(strongBox = false)
            GenerationResult.StrongBoxFallback(strongBox = false, reason = cause.message ?: "StrongBox unavailable")
        } catch (e: Exception) {
            Log.e(TAG, "TEE fallback generation failed", e)
            GenerationResult.Failed(e.message ?: "TEE fallback failed")
        }

    private fun deleteKeysIfPartial() {
        val signing = hasSigningKey()
        val agreement = hasAgreementKey()
        if (signing == agreement) return
        try {
            keyStore.deleteEntry(if (signing) SIGNING_ALIAS else AGREEMENT_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "cleanup of partial key failed", e)
        }
    }

    fun hasSigningKey(): Boolean = keyStore.containsAlias(SIGNING_ALIAS)

    fun hasAgreementKey(): Boolean = keyStore.containsAlias(AGREEMENT_ALIAS)

    fun signingPublicKey(): PublicKey = keyStore.getCertificate(SIGNING_ALIAS)?.publicKey
        ?: error("signing key missing")

    fun agreementPublicKey(): PublicKey = keyStore.getCertificate(AGREEMENT_ALIAS)?.publicKey
        ?: error("agreement key missing")

    /** Non-extractable ECDH private key for CEK re-derivation (§6.1 step 5); never leaves Keystore.
     *  Open so the virtual-partner harness can substitute its attested test key (ADR #46) — the
     *  same seam [AccountabilitySigner.beginSign] provides for signing. */
    open fun agreementPrivateKey(): PrivateKey =
        keyStore.getKey(AGREEMENT_ALIAS, null) as? PrivateKey ?: error("agreement key missing")

    /**
     * The pair's own ECDH key (§19.5, schema v12): the record's alias when it has one, the
     * legacy device-level key for pre-v12 enrollments. Open for the same harness seam as
     * [agreementPrivateKey].
     */
    open fun agreementPrivateKeyFor(alias: String?): PrivateKey =
        alias?.let { pairAgreementPrivateKey(it) } ?: agreementPrivateKey()

    /** §19.5: fresh ECDH keypair scoped to one pairing attempt ([pairAgreementAliasOf]). */
    fun generatePairAgreementKey(alias: String): PublicKey {
        requirePairAlias(alias)
        if (Build.VERSION.SDK_INT < 31) {
            throw UnsupportedOperationException("in-Keystore ECDH requires API 31+")
        }
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setUserAuthenticationRequired(false)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(builder.build())
        return generator.generateKeyPair().public
    }

    fun pairAgreementPublicKey(alias: String): PublicKey =
        keyStore.getCertificate(alias)?.publicKey ?: error("pair agreement key missing")

    fun pairAgreementPrivateKey(alias: String): PrivateKey =
        keyStore.getKey(alias, null) as? PrivateKey ?: error("pair agreement key missing")

    /** §19.5 pair-scoped shred: destroys only this pair's key. */
    fun destroyPairAgreementKey(alias: String) {
        // A wrong alias here deletes the wrong key — the prefix keeps a caller bug (or a
        // null/blank alias) away from the signing key and the legacy device key.
        requirePairAlias(alias)
        keyStore.deleteEntry(alias)
    }

    private fun requirePairAlias(alias: String) {
        require(alias.startsWith(PAIR_AGREEMENT_ALIAS_PREFIX) && alias.length > PAIR_AGREEMENT_ALIAS_PREFIX.length) {
            "not a pair agreement alias"
        }
    }

    /**
     * Signing key for a BiometricPrompt [javax.crypto.Signature] CryptoObject. The keystore
     * itself is the gate: sign() without fresh biometric authentication fails (NFR4 timeout
     * 900s; enrollment change invalidates — app polls instead, §5/§8.2).
     */
    fun signingPrivateKey(): PrivateKey =
        keyStore.getKey(SIGNING_ALIAS, null) as? PrivateKey ?: error("signing key missing")

    /** Leaf-first chain (leaf = index 0); attestation extension lives in the leaf. */
    fun signingCertificateChain(): Array<X509Certificate> {
        val chain = keyStore.getCertificateChain(SIGNING_ALIAS) ?: error("signing key missing")
        return chain.map { it as X509Certificate }.toTypedArray()
    }

    fun isStrongBoxBacked(): Boolean {
        val chain = signingCertificateChain()
        if (chain.isEmpty()) return false
        return AttestationVerifier.keySecurityLevelOf(chain[0]) == AttestationVerifier.SecurityLevel.STRONG_BOX
    }

    /** Deletes BOTH keys — the irreversible full-wipe path (uninstalls, reset-all) — plus every
     *  per-pair agreement key (ADR #113): Identity Migration's rotation IS revocation, so the
     *  old identity's pair keys must not linger unreferenced in the keystore afterwards. */
    fun destroyAllKeys() {
        keyStore.deleteEntry(SIGNING_ALIAS)
        keyStore.deleteEntry(AGREEMENT_ALIAS)
        // Collected first — deleting while enumerating the alias set is not safe.
        keyStore.aliases().toList()
            .filter { it.startsWith(PAIR_AGREEMENT_ALIAS_PREFIX) }
            .forEach { keyStore.deleteEntry(it) }
    }

    /** §10 bilateral crypto-shredding: destroys only the ECDH key (FR6 — the shredding target). */
    fun destroyAgreementKey() {
        keyStore.deleteEntry(AGREEMENT_ALIAS)
    }

    /** §7.6 key rotation: fresh identity + agreement keys in one irreversible step. */
    fun rotateKeys(): GenerationResult {
        destroyAllKeys()
        return generateKeys()
    }

    private fun generateSigningKey(challenge: ByteArray, strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(SIGNING_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // Ignored by the OS for positive timeouts; enrollment-change invalidation is
            // handled by app-level polling instead (§5, §8.2).
            .setInvalidatedByBiometricEnrollment(false)
            .setAttestationChallenge(challenge)
        if (strongBox && Build.VERSION.SDK_INT >= 28) {
            builder.setIsStrongBoxBacked(true)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // Owner ruling 2026-09-28 (ADR #112): device credential joins the strong class so a
            // phone with a screen lock but no enrolled biometrics can still sign. Time-bound
            // (positive timeout) keys resolve via the lock screen or the confirm-credential flow
            // (KeyGenParameterSpec javadoc); the fallback prompt uses exactly that flow. Keys are
            // immutable once generated — pre-ruling biometric-only keys keep working via the
            // biometric path and are refused by the fallback with an explicit message.
            builder.setUserAuthenticationParameters(
                AUTH_TIMEOUT_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            // Legacy API maps to (timeout, AUTH_DEVICE_CREDENTIAL or AUTH_BIOMETRIC_STRONG) —
            // verified in android-36 sources, KeyGenParameterSpec.java:1511.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_TIMEOUT_SECONDS)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(builder.build())
        generator.generateKeyPair()
    }

    private fun generateAgreementKey(strongBox: Boolean) {
        // PURPOSE_AGREE_KEY exists only from API 31; below that the Keystore cannot host an
        // ECDH key at all (platform constraint, verified via android-37.0 stubs). Enrollment
        // surfaces this as "device unsupported" — in-Keystore ECDH is a hard floor for the
        // protocol (plan §5 assumes it everywhere).
        if (Build.VERSION.SDK_INT < 31) {
            throw UnsupportedOperationException("in-Keystore ECDH requires API 31+")
        }
        val builder = KeyGenParameterSpec.Builder(AGREEMENT_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setUserAuthenticationRequired(false)
        if (strongBox && Build.VERSION.SDK_INT >= 28) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(builder.build())
        generator.generateKeyPair()
    }

    private fun isStrongBoxUnavailable(e: Throwable): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is StrongBoxUnavailableException) return true
            cause = cause.cause
        }
        return false
    }

    companion object {
        const val SIGNING_ALIAS = "accountability_signing_key"
        const val AGREEMENT_ALIAS = "accountability_agreement_key"

        /** §19.5 per-pair key alias = this prefix plus the pairing session id. The session id
         *  (not the pairId) scopes it because the initiator's key must exist — and be named —
         *  before the partner (and so the durable pairId) is known; the alias is recorded on
         *  the enrollment so every later derivation finds the right key. */
        const val PAIR_AGREEMENT_ALIAS_PREFIX = "accountability_pair_agreement_key_"

        fun pairAgreementAliasOf(sessionId: String): String = PAIR_AGREEMENT_ALIAS_PREFIX + sessionId
        /** NFR4: biometric authentication timeout. */
        const val AUTH_TIMEOUT_SECONDS = 900
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TAG = "AccountabilityKeys"
    }
}
