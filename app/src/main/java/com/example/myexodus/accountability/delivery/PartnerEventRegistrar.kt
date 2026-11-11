package com.example.myexodus.accountability.delivery

import android.content.Context
import com.example.myexodus.accountability.sync.AppCheckInstaller
import com.example.myexodus.accountability.sync.await
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import java.util.Base64
import java.util.UUID

/**
 * §8.3 client half of device registration/rotation: request a challenge, mint the background
 * signing key attested to it, sign the JCS registration message, and commit the server's
 * registration into [ServiceEventOutbox]. Runs only from a foreground drain (§8.3.1's
 * reauthentication and App Lock confirmation are foreground acts); never from the background
 * worker.
 */
open class PartnerEventRegistrar(
    private val appContext: Context,
    private val functions: FirebaseFunctions,
    private val keyStore: PartnerEventKeyStore,
    private val outbox: ServiceEventOutbox,
    private val recipientPublicKeySpki: suspend () -> ByteArray?,
    private val currentAuth: suspend () -> PartnerAuth?,
    /**
     * Debug builds have no hardware attestation (emulators), so they may register as
     * ACTIVE_UNATTESTED. A release build without a chain is a hard stop.
     */
    private val allowUnattested: Boolean = false,
    private val newRequestId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {

    /** §8.3.1's auth seam: uid plus the caller's `auth_time`-within-five-minutes verdict. */
    data class PartnerAuth(val uid: String, val fresh: Boolean)

    sealed interface Outcome {
        data class Registered(val integrityTier: String, val revision: Int) : Outcome
        data object NoAccount : Outcome
        data object AuthNotFresh : Outcome
        /** No paired agreement key to register — registration waits for a pairing. */
        data object NoRecipientKey : Outcome
        data object AttestationRequired : Outcome
        data class Rejected(val reason: String) : Outcome
        data object Retryable : Outcome
    }

    /** Seams the transport tests script through, mirroring [com.example.myexodus.accountability.sync.PairingRegistration]. */
    protected open suspend fun callChallenge(wire: Map<String, Any>): Any? =
        functions.getHttpsCallable("registerDeviceChallenge").call(wire).await().data

    protected open suspend fun callRegister(wire: Map<String, Any>): Any? =
        functions.getHttpsCallable("registerOrRotateDevice").call(wire).await().data

    open suspend fun register(installationId: String): Outcome {
        val auth = currentAuth() ?: return Outcome.NoAccount
        if (!auth.fresh) return Outcome.AuthNotFresh
        val recipientSpki = recipientPublicKeySpki() ?: return Outcome.NoRecipientKey

        val state = outbox.installationById(installationId)?.registrationState
        val preferRotate = state == InstallationState.REGISTRATION_ACTIVE ||
            state == InstallationState.REGISTRATION_REENROLL_REQUIRED
        val first = attempt(
            installationId, auth.uid, recipientSpki,
            if (preferRotate) INTENT_ROTATE else INTENT_ENROLL,
        )
        // The server is authoritative on which intent is legal; a local-state drift is
        // recoverable exactly when its precondition names the other intent.
        return when ((first as? Outcome.Rejected)?.reason) {
            "ROTATION_REQUIRED" -> attempt(installationId, auth.uid, recipientSpki, INTENT_ROTATE)
            "ROTATION_NOT_ENROLLED" -> attempt(installationId, auth.uid, recipientSpki, INTENT_ENROLL)
            else -> first
        }
    }

    private suspend fun attempt(
        installationId: String,
        uid: String,
        recipientSpki: ByteArray,
        intent: String,
    ): Outcome {
        val challengeData = try {
            AppCheckInstaller.install(appContext)
            callChallenge(mapOf("installationId" to installationId, "intent" to intent))
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFunctionsException) {
            return classify(e)
        } catch (_: Exception) {
            return Outcome.Retryable
        }
        val root = challengeData as? Map<*, *> ?: return Outcome.Retryable
        val challengeId = root["challengeId"] as? String ?: return Outcome.Retryable
        val challenge = root["challenge"] as? String ?: return Outcome.Retryable
        val challengeBytes = try {
            Base64.getUrlDecoder().decode(challenge)
        } catch (_: IllegalArgumentException) {
            return Outcome.Retryable
        }
        if (challengeBytes.size != 32) return Outcome.Retryable

        // The attestation challenge is baked in at keygen, so the key is minted only now,
        // with the server's fresh challenge. Replacing any prior key is deliberate: the
        // registration about to be signed must describe the key being registered.
        val generated = keyStore.generate(installationId, challengeBytes)
        if (generated is PartnerEventKeyStore.GenerationResult.Failed) {
            return Outcome.Rejected("KEY_GENERATION_FAILED")
        }
        val keyId = keyStore.keyId(installationId)
        val chain = keyStore.attestationChain(installationId, challengeBytes)
        if (chain == null && !allowUnattested) return Outcome.AttestationRequired

        val message = Jcs.canonicalize(
            linkedMapOf(
                "v" to 1,
                "ownerUid" to uid,
                "installationId" to installationId,
                "keyId" to keyId,
                "challenge" to challenge,
            ),
        ).toByteArray(Charsets.UTF_8)
        val signature = keyStore.sign(installationId, message)

        val wire = linkedMapOf<String, Any>(
            "requestId" to newRequestId(),
            "challengeId" to challengeId,
            "intent" to intent,
            "installationId" to installationId,
            "keyId" to keyId,
            "challenge" to challenge,
            "registrationSignature" to PartnerEventProtocol.base64Url(signature),
            "signingPublicKey" to Base64.getEncoder().encodeToString(
                keyStore.publicKeyDer(installationId),
            ),
            "recipientPublicKey" to Base64.getEncoder().encodeToString(recipientSpki),
        )
        if (chain != null) {
            wire["attestationChain"] = chain.map { Base64.getEncoder().encodeToString(it) }
            // The server string-compares this with `challenge` (§8.3.6).
            wire["attestationChallenge"] = challenge
        } else {
            wire["allowUnattested"] = true
        }

        val response = try {
            callRegister(wire)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFunctionsException) {
            return classify(e)
        } catch (_: Exception) {
            return Outcome.Retryable
        }
        val result = response as? Map<*, *> ?: return Outcome.Retryable
        val serverKeyId = result["keyId"] as? String ?: return Outcome.Retryable
        val recipientKeyId = result["recipientKeyId"] as? String ?: return Outcome.Retryable
        if (serverKeyId != keyId) return Outcome.Retryable
        val tier = result["integrityTier"] as? String ?: "ACTIVE_UNATTESTED"
        val revision = (result["registrationRevision"] as? Number)?.toInt() ?: 1
        return if (outbox.markRegistered(installationId, keyId, recipientKeyId)) {
            Outcome.Registered(tier, revision)
        } else {
            Outcome.Retryable
        }
    }

    private fun classify(e: FirebaseFunctionsException): Outcome = when (e.code) {
        FirebaseFunctionsException.Code.UNAVAILABLE,
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
        FirebaseFunctionsException.Code.INTERNAL,
        -> Outcome.Retryable
        else -> Outcome.Rejected(e.message ?: e.code.name)
    }

    companion object {
        const val INTENT_ENROLL = "ENROLL"
        const val INTENT_ROTATE = "ROTATE"
    }
}
