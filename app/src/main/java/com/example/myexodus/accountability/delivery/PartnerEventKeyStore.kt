package com.example.myexodus.accountability.delivery

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.example.myexodus.accountability.crypto.AttestationVerifier
import com.example.myexodus.accountability.crypto.EcdsaLowS
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §8.3 background signing key: one P-256 ECDSA keypair per installation, alias-prefixed so
 * the full wipe purges it and a second account never reuses the first's identity.
 *
 * Deliberately NOT biometric-gated, unlike the identity key: §4.3 drains run from WorkManager
 * while the screen is locked, so the key must sign without user presence. Its containment is
 * the Keystore itself — non-exportable, hardware-backed when available, attested at keygen
 * when the registrar supplies the server challenge.
 */
@Singleton
open class PartnerEventKeyStore @Inject constructor() {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    sealed interface GenerationResult {
        /** [attested] is true only when the certificate carries the requested challenge. */
        data class Success(val strongBox: Boolean, val attested: Boolean) : GenerationResult
        data class Failed(val error: String) : GenerationResult
    }

    open fun hasKey(installationId: String): Boolean = keyStore.containsAlias(aliasFor(installationId))

    /**
     * Mints (or replaces) the installation's signing key. The attestation challenge is baked
     * into the certificate at keygen and can never be added afterwards, so a caller that wants
     * attestation must pass the server challenge here and read [attestationChain] before
     * registering. An existing alias is deleted first: a rotation always produces a fresh key
     * whose ID matches the registration it is about to sign.
     */
    open fun generate(
        installationId: String,
        attestationChallenge: ByteArray?,
        strongBox: Boolean = true,
    ): GenerationResult {
        deleteKey(installationId)
        return try {
            generateAt(installationId, attestationChallenge, strongBox && Build.VERSION.SDK_INT >= 28)
            GenerationResult.Success(
                strongBox = strongBox && Build.VERSION.SDK_INT >= 28,
                attested = attestationChallenge != null && attestationChain(
                    installationId, attestationChallenge,
                ) != null,
            )
        } catch (e: Exception) {
            if (!isStrongBoxUnavailable(e)) {
                deleteKey(installationId)
                return GenerationResult.Failed(e.message ?: "key generation failed")
            }
            try {
                deleteKey(installationId)
                generateAt(installationId, attestationChallenge, strongBox = false)
                GenerationResult.Success(
                    strongBox = false,
                    attested = attestationChallenge != null && attestationChain(
                        installationId, attestationChallenge,
                    ) != null,
                )
            } catch (retry: Exception) {
                deleteKey(installationId)
                GenerationResult.Failed(retry.message ?: "key generation failed")
            }
        }
    }

    fun publicKey(installationId: String): PublicKey =
        keyStore.getCertificate(aliasFor(installationId))?.publicKey
            ?: error("partner event signing key missing")

    /** SPKI DER — the exact bytes whose base64 is registered and whose hash is the key ID. */
    open fun publicKeyDer(installationId: String): ByteArray = publicKey(installationId).encoded

    open fun keyId(installationId: String): String =
        PartnerEventProtocol.keyIdOf(publicKeyDer(installationId))

    /**
     * Leaf-first attestation chain, or null when the leaf does not carry an extension with
     * exactly [expectedChallenge]. A chain that cannot pass that check must not be presented —
     * the server treats a presented-but-invalid chain as a hard rejection (§8.3.6).
     */
    open fun attestationChain(installationId: String, expectedChallenge: ByteArray): List<ByteArray>? {
        val chain = keyStore.getCertificateChain(aliasFor(installationId)) ?: return null
        val leaf = chain.firstOrNull() as? java.security.cert.X509Certificate ?: return null
        val embedded = AttestationVerifier.embeddedChallengeOf(leaf) ?: return null
        if (!Arrays.equals(embedded, expectedChallenge)) return null
        return chain.map { it.encoded }
    }

    /** DER ECDSA over [data], low-S normalized like every other signature this app emits. */
    open fun sign(installationId: String, data: ByteArray): ByteArray {
        val privateKey = privateKey(installationId)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(data)
        return EcdsaLowS.normalize(signature.sign())
    }

    fun privateKey(installationId: String): PrivateKey =
        keyStore.getKey(aliasFor(installationId), null) as? PrivateKey
            ?: error("partner event signing key missing")

    open fun deleteKey(installationId: String) {
        keyStore.deleteEntry(aliasFor(installationId))
    }

    private fun generateAt(installationId: String, challenge: ByteArray?, strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(
            aliasFor(installationId),
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
        if (challenge != null) builder.setAttestationChallenge(challenge)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .apply { initialize(builder.build()) }
            .generateKeyPair()
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
        const val ALIAS_PREFIX = "partner_event_signing_key_"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        fun aliasFor(installationId: String): String = ALIAS_PREFIX + installationId
    }
}
