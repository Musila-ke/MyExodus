package com.example.myexodus.accountability.sync

import android.content.Context
import com.example.myexodus.accountability.enrollment.EnrollmentPayload
import com.example.myexodus.accountability.enrollment.PairingToken
import com.example.myexodus.accountability.enrollment.decodeEnrollmentPayload
import com.example.myexodus.accountability.enrollment.encodeEnrollmentPayload
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.delay

/**
 * §10 steps 2-3 relay exchange. The token document id is SHA-256(token) — a token document
 * is unaddressable without the token — and the redemption transaction (read token -> create
 * pair state -> delete token) makes single-use atomic: a concurrent second redemption reads
 * nothing and is rejected as AlreadyUsed. Expiry is enforced server-side by the rules
 * (expiresAt > request.time gate on the token document read) and re-checked in the
 * transaction. The pair document carries the redeemer's enrollment payload; the initiator's
 * transcript signature arrives as a follow-up update ([confirmPairing]) that the redeemer
 * polls for before finalizing.
 */
open class PairingExchange(
    private val appContext: Context,
    private val firestore: FirebaseFirestore,
) {

    sealed interface RedeemResult {
        /** Token consumed, pair state created; the redeemer now waits for the initiator's signature. */
        data class Redeemed(val initiatorPayload: EnrollmentPayload) : RedeemResult
        data object Expired : RedeemResult
        data object AlreadyUsed : RedeemResult
        data class Failed(val reason: String) : RedeemResult
    }

    /** §10 step 1: register the token (with the initiator's payload, see [FirestoreSchema.pairingTokenData]) before the QR is shown. */
    open suspend fun registerToken(token: PairingToken, creatorUid: String, payload: EnrollmentPayload) {
        AppCheckInstaller.install(appContext)
        firestore.collection(FirestoreSchema.PAIRING_TOKENS_COLLECTION)
            .document(FirestoreSchema.pairingTokenDocumentId(token))
            .set(FirestoreSchema.pairingTokenData(token, creatorUid, encodeEnrollmentPayload(payload)))
            .await()
    }

    /**
     * §10 step 2: atomically verify the token (exists, unexpired, creatorUid intact), create
     * the pair state document with the redeemer's enrollment payload, and delete the token
     * document. Any rules rejection (expired token) surfaces as [RedeemResult.Expired].
     */
    open suspend fun redeem(token: PairingToken, myUid: String, myPayload: EnrollmentPayload): RedeemResult {
        AppCheckInstaller.install(appContext)
        val tokenRef = firestore.collection(FirestoreSchema.PAIRING_TOKENS_COLLECTION)
            .document(FirestoreSchema.pairingTokenDocumentId(token))
        val pairRef = FirestoreSchema.pairStateDocument(firestore, token.pairId)
        return try {
            firestore.runTransaction { tx: Transaction ->
                val tokenDoc = tx.get(tokenRef)
                if (!tokenDoc.exists()) return@runTransaction RedeemResult.AlreadyUsed
                val creatorUid = tokenDoc.getString("creatorUid")
                    ?: return@runTransaction RedeemResult.Failed("token document missing creatorUid")
                val expiresAt = tokenDoc.getDate("expiresAt")
                if (expiresAt == null || token.isExpired(expiresAt.time)) {
                    return@runTransaction RedeemResult.Expired
                }
                if (tx.get(pairRef).exists()) return@runTransaction RedeemResult.AlreadyUsed
                val payloadBytes = tokenDoc.getBlob("payload")?.toBytes()
                    ?: return@runTransaction RedeemResult.Failed("token document missing payload")
                val initiatorPayload = try {
                    decodeEnrollmentPayload(payloadBytes)
                } catch (e: Exception) {
                    return@runTransaction RedeemResult.Failed("token payload unparseable")
                }
                tx.set(
                    pairRef,
                    FirestoreSchema.pairStateData(
                        setOf(creatorUid, myUid),
                        encodeEnrollmentPayload(myPayload),
                    ),
                )
                tx.delete(tokenRef)
                RedeemResult.Redeemed(initiatorPayload)
            }.await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                // The token document read is rules-gated on expiry; a denial here is an
                // expired token (or a forged/unaddressable id, which is the same outcome).
                RedeemResult.Expired
            } else {
                RedeemResult.Failed(e.message ?: "exchange failed")
            }
        }
    }

    /**
     * §10 step 3: the initiator's transcript signature lands in the pair document. The
     * redeemer's poll ([pollPairState] with [requireInitiatorSignature]) completes only
     * once this update is visible.
     */
    open suspend fun confirmPairing(pairId: String, initiatorSignature: ByteArray) {
        AppCheckInstaller.install(appContext)
        FirestoreSchema.pairStateDocument(firestore, pairId)
            .update("initiatorSignature", Blob.fromBytes(initiatorSignature))
            .await()
    }

    /**
     * §10 step 2 (partner half): the partner's transcript signature lands in the pair
     * document after redemption — the partner signs only once the initiator's agreement key
     * arrives from the token document (§6.1 ordering). The initiator's poll
     * ([pollPairState] with [requirePartnerSignature]) completes once this is visible.
     */
    open suspend fun confirmPartnerSignature(pairId: String, partnerSignature: ByteArray) {
        AppCheckInstaller.install(appContext)
        FirestoreSchema.pairStateDocument(firestore, pairId)
            .update("partnerSignature", Blob.fromBytes(partnerSignature))
            .await()
    }

    /**
     * Deletes the pair state document. Used by the initiator to abort a pairing whose
     * verification failed, and by [com.example.myexodus.accountability.BilateralDeletion]
     * for §10 step 4 revocation — once deleted, the mailbox rules (which gate on the
     * pair document's existence) reject further writes for the pair.
     */
    open suspend fun deletePairState(pairId: String) {
        AppCheckInstaller.install(appContext)
        try {
            FirestoreSchema.pairStateDocument(firestore, pairId).delete().await()
        } catch (e: FirebaseFirestoreException) {
            // Not yet a member / never created — nothing to delete.
        }
    }

    /**
     * The pair state document as read by either member: the redeemer's enrollment payload
     * plus, once they land, the transcript signatures — [partnerSignature] from
     * [confirmPartnerSignature], [initiatorSignature] from [confirmPairing].
     */
    data class PairState(
        val enrollmentPayload: EnrollmentPayload,
        val initiatorSignature: ByteArray?,
        val partnerSignature: ByteArray?,
    )

    /**
     * Polls the pair state document until it exists — with the requested signatures present
     * ([requireInitiatorSignature] for the redeemer, [requirePartnerSignature] for the
     * initiator) — or the timeout elapses.
     */
    open suspend fun pollPairState(
        pairId: String,
        timeoutMillis: Long,
        requireInitiatorSignature: Boolean,
        requirePartnerSignature: Boolean = false,
    ): PairState? {
        AppCheckInstaller.install(appContext)
        val ref = FirestoreSchema.pairStateDocument(firestore, pairId)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val doc = try {
                ref.get().await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) null else throw e
            }
            if (doc != null && doc.exists()) {
                val enrollment = doc.getBlob("enrollment")?.toBytes() ?: return null
                val payload = try {
                    decodeEnrollmentPayload(enrollment)
                } catch (e: Exception) {
                    return null
                }
                val initiatorSignature = doc.getBlob("initiatorSignature")?.toBytes()
                val partnerSignature = doc.getBlob("partnerSignature")?.toBytes()
                val missing = (requireInitiatorSignature && initiatorSignature == null) ||
                    (requirePartnerSignature && partnerSignature == null)
                if (!missing) return PairState(payload, initiatorSignature, partnerSignature)
            }
            if (System.currentTimeMillis() >= deadline) return null
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    companion object {
        const val POLL_INTERVAL_MILLIS = 750L
    }
}
