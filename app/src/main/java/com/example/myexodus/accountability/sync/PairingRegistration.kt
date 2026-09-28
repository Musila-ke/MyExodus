package com.example.myexodus.accountability.sync

import android.content.Context
import android.util.Base64
import com.example.myexodus.accountability.enrollment.PairingProof
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableResult

/**
 * §8 Step 3 client for the registerPairing Cloud Function (region us-central1 — AD-17b:
 * same region as the Firestore database, never separated). App Check and the anonymous
 * auth token attach automatically to the callable (`AppCheckInstaller` must have run).
 *
 * §9 idempotency: every proof carries a client-generated [PairingProof.requestId]; an
 * ambiguous transport outcome is retried once with the same wire map (same requestId), so
 * a response lost after the server committed replays the committed result instead of
 * re-executing the transition. Other failures stay terminal, as the function reported them.
 */
open class PairingRegistration(
    private val appContext: Context,
    private val functions: FirebaseFunctions,
) {

    sealed interface SubmitResult {
        /** Both proofs registered — the pairing document is active. [pairingEpoch] is the
         *  server-assigned AD-07 epoch, persisted on the enrollment record. */
        data class Registered(val pairId: String, val pairingEpoch: Int) : SubmitResult

        /** Our proof is stored; the partner's has not arrived within the function's window yet. */
        data object AwaitingPartner : SubmitResult

        /** The function rejected the proof (stable reason code, e.g. TRANSCRIPT_MISMATCH). */
        data class Rejected(val reason: String) : SubmitResult

        data class Failed(val message: String) : SubmitResult
    }

    /** One registerPairing round trip's payload — the seam transport tests script through. */
    protected open suspend fun callRegisterPairing(wire: Map<String, Any>): Any? =
        functions.getHttpsCallable("registerPairing").call(wire).await().data

    open suspend fun submit(proof: PairingProof): SubmitResult {
        AppCheckInstaller.install(appContext)
        // Built once: the retry below must send the identical payload (hence the identical
        // §9 requestHash) or the server's replay lookup rejects it as REQUEST_ID_REUSED.
        val wire = wireMapOf(proof)
        return try {
            parse(callRegisterPairing(wire))
        } catch (e: FirebaseFunctionsException) {
            if (!isTransportAmbiguous(e)) {
                // The function's own verdict — a stable reason code travels in the message
                // (HttpsError detail), surfaced verbatim so device runs can tell
                // TRANSCRIPT_MISMATCH from PAIRING_ACTIVE.
                SubmitResult.Rejected(e.message ?: e.code.name)
            } else {
                try {
                    parse(callRegisterPairing(wire))
                } catch (retry: FirebaseFunctionsException) {
                    // Still unreachable after the retry: transient, not a verdict — the
                    // caller may re-run the ceremony; nothing was rejected server-side.
                    if (isTransportAmbiguous(retry)) {
                        SubmitResult.Failed("registerPairing unreachable: ${retry.code.name}")
                    } else {
                        SubmitResult.Rejected(retry.message ?: retry.code.name)
                    }
                } catch (retry: Exception) {
                    SubmitResult.Failed(retry.message ?: "registerPairing call failed")
                }
            }
        } catch (e: Exception) {
            SubmitResult.Failed(e.message ?: "registerPairing call failed")
        }
    }

    /**
     * §9: UNAVAILABLE / DEADLINE_EXCEEDED are ambiguous — the request may have committed
     * server-side with only the response lost. Those (and only those) are retried with the
     * same requestId; the function's idempotency cache then returns the committed result
     * rather than re-executing the transition.
     */
    private fun isTransportAmbiguous(e: FirebaseFunctionsException): Boolean =
        e.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
            e.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED

    private fun parse(responseData: Any?): SubmitResult {
        val data = responseData as? Map<*, *>
            ?: return SubmitResult.Failed("malformed function response")
        return when (data["status"]) {
            "active" -> {
                val pairId = data["pairId"] as? String
                val epoch = (data["pairingEpoch"] as? Number)?.toInt()
                if (pairId == null || epoch == null) {
                    SubmitResult.Failed("active response lacks pairId/pairingEpoch")
                } else {
                    SubmitResult.Registered(pairId, epoch)
                }
            }
            "pending" -> SubmitResult.AwaitingPartner
            else -> SubmitResult.Failed("unexpected status ${data["status"]}")
        }
    }

    /**
     * Wire shape mirrors functions/proof.js verifyProof: standard base64 (the node side
     * decodes with Buffer.from(…, 'base64')), hashes as lowercase hex.
     */
    private fun wireMapOf(proof: PairingProof): Map<String, Any> = mapOf(
        "ownUid" to proof.ownUid,
        "partnerUid" to proof.partnerUid,
        "publicKeyHash" to proof.publicKeyHash,
        "transcriptHash" to proof.transcriptHash,
        "protocolVersion" to proof.protocolVersion,
        "signingPublicKey" to Base64.encodeToString(proof.signingPublicKey, Base64.NO_WRAP),
        "signature" to Base64.encodeToString(proof.signature, Base64.NO_WRAP),
        "attestationChain" to proof.attestationChain.map { Base64.encodeToString(it, Base64.NO_WRAP) },
        "attestationChallenge" to Base64.encodeToString(proof.attestationChallenge, Base64.NO_WRAP),
        // §9 idempotency key — unsigned, validated server-side against the doc-id-safe pattern.
        "requestId" to proof.requestId,
    )

    companion object {
        /** AD-17b: must equal the deployed function's region and the Firestore database region. */
        const val REGION = "us-central1"
    }
}
