package com.example.myexodus.accountability

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Round-trip harness (§12 device rows): a SECOND attested hardware keypair under test-only
 * aliases — the virtual partner's identity. Distinct key material from the app's real keys,
 * so `registerPairing`'s SAME_KEY clause passes on distinct publicKeyHash, while the chain
 * stays a genuine TEE chain rooted in the pinned Google attestation roots — the device's own
 * [com.example.myexodus.accountability.crypto.AttestationVerifier] accepts it exactly as it
 * would a second physical device's (emulator software keys cannot; see vault 2026-09-16).
 *
 * TEE, not StrongBox: StrongBox availability is a per-device budget for app identities, and
 * the verifier accepts TRUSTED_ENVIRONMENT. Warm-auth window (900s) is device-level, so one
 * biometric tap covers the real identity and this one alike.
 */
object PartnerTestKeys {

    const val SIGNING_ALIAS = "test_partner_signing_key"
    const val AGREEMENT_ALIAS = "test_partner_agreement_key"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Generates both keys if missing; cleans a half-generated pair first (KeyExistsException trap). */
    fun ensureKeys() {
        if (hasBoth()) return
        if (keyStore.containsAlias(SIGNING_ALIAS) != keyStore.containsAlias(AGREEMENT_ALIAS)) destroy()
        generateSigning()
        generateAgreement()
    }

    fun destroy() {
        keyStore.deleteEntry(SIGNING_ALIAS)
        keyStore.deleteEntry(AGREEMENT_ALIAS)
    }

    fun signingPrivateKey(): PrivateKey =
        keyStore.getKey(SIGNING_ALIAS, null) as? PrivateKey ?: error("partner signing key missing")

    fun signingPublicKey(): PublicKey =
        keyStore.getCertificate(SIGNING_ALIAS)?.publicKey ?: error("partner signing key missing")

    fun agreementPrivateKey(): PrivateKey =
        keyStore.getKey(AGREEMENT_ALIAS, null) as? PrivateKey ?: error("partner agreement key missing")

    fun agreementPublicKey(): PublicKey =
        keyStore.getCertificate(AGREEMENT_ALIAS)?.publicKey ?: error("partner agreement key missing")

    /** Leaf-first chain; the leaf carries the fresh attestation challenge read by PairingFlow. */
    fun signingChain(): Array<X509Certificate> =
        (keyStore.getCertificateChain(SIGNING_ALIAS) ?: error("partner signing key missing"))
            .map { it as X509Certificate }
            .toTypedArray()

    private fun hasBoth(): Boolean =
        keyStore.containsAlias(SIGNING_ALIAS) && keyStore.containsAlias(AGREEMENT_ALIAS)

    private fun generateSigning() {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val spec = KeyGenParameterSpec.Builder(
            SIGNING_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
            .setAttestationChallenge(challenge)
            .setUserAuthenticationParameters(900, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    private fun generateAgreement() {
        val spec = KeyGenParameterSpec.Builder(AGREEMENT_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setUserAuthenticationRequired(false)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }
}
