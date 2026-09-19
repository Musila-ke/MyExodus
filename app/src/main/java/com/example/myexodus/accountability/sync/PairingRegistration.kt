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
 * The function is idempotent per proof, so reporting a dropped response as [SubmitResult.Failed]
 * is safe for the caller to re-run end to end.
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

    open suspend fun submit(proof: PairingProof): SubmitResult {
        AppCheckInstaller.install(appContext)
        return try {
            val result: HttpsCallableResult = functions
                .getHttpsCallable("registerPairing")
                .call(wireMapOf(proof))
                .await()
            val data = result.data as? Map<*, *>
                ?: return SubmitResult.Failed("malformed function response")
            when (data["status"]) {
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
        } catch (e: FirebaseFunctionsException) {
            // Stable server reason codes travel in the message (HttpsError detail); surfaced
            // verbatim so on-device runs can tell TRANSCRIPT_MISMATCH from PAIRING_ACTIVE.
            SubmitResult.Rejected(e.message ?: e.code.name)
        } catch (e: Exception) {
            SubmitResult.Failed(e.message ?: "registerPairing call failed")
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
    )

    companion object {
        /** AD-17b: must equal the deployed function's region and the Firestore database region. */
        const val REGION = "us-central1"
    }
}
