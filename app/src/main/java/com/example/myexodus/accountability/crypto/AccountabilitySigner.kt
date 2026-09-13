package com.example.myexodus.accountability.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signs event hashes with the biometric-gated identity key (§6.2.7).
 *
 * Flow: [beginSign] returns a [Signature] the caller updates with the event hash and passes
 * to `BiometricPrompt.CryptoObject`. On prompt success the caller invokes [finishSign].
 * If the 900s window (§8.1) is still warm, [finishSign] succeeds without any prompt —
 * `UserNotAuthenticatedException` only surfaces when the window has expired.
 */
@Singleton
open class AccountabilitySigner @Inject constructor() {

    // KeyStore loaded lazily per call so construction is side-effect-free (unit-testable;
    // AndroidKeyStore does not exist in Robolectric). Open for test fakes that stand in
    // plain JCA signatures (warm window) or throw UserNotAuthenticatedException (cold).
    open fun beginSign(): Signature {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        // No explicit provider: on XOS (X6532, 2026-08-31) the AndroidKeyStoreBCWorkaround
        // shadow breaks provider-name lookup ("no such algorithm ... for provider
        // AndroidKeyStore") while the default provider chain resolves it. verify() below
        // already uses the default chain; beginSign must match.
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(keyStore.getKey(AccountabilityKeyStore.SIGNING_ALIAS, null) as java.security.PrivateKey)
        return signature
    }

    /**
     * §10 Low-S: normalizes the raw JCA signature so every signature this device emits passes
     * the partner's low-S verifier — raw JCA output is high-S ~50% of the time, which would
     * break the protocol at the verification step (rejects S > N/2) for both enrollment
     * transcripts and event hashes.
     */
    fun finishSign(signature: Signature): ByteArray = EcdsaLowS.normalize(signature.sign())

    /**
     * Wraps the UserNotAuthenticatedException contract for the UI: the caller should retry via
     * BiometricPrompt with a fresh [beginSign] CryptoObject (§9).
     */
    fun isUserNotAuthenticated(e: Throwable): Boolean = e is UserNotAuthenticatedException

    /**
     * §10 domain separation + Low-S: verifies [data] against the partner's public key,
     * rejecting high-S signatures. Domain string is part of the signed input (§6.2.6),
     * applied by the caller before this is invoked.
     */
    fun verify(publicKey: PublicKey, data: ByteArray, derSignature: ByteArray): Boolean {
        return try {
            if (!EcdsaLowS.isLowS(derSignature)) return false
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(data)
            verifier.verify(derSignature)
        } catch (e: Exception) {
            // Malformed DER (e.g. a tampered signature from the relay) is a rejection, not a crash.
            false
        }
    }

    companion object {
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
