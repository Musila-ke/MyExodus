package com.example.myexodus.accountability.sync

import android.content.Context
import com.example.myexodus.accountability.enrollment.EnrollmentPayload
import com.example.myexodus.accountability.enrollment.PairingToken
import com.example.myexodus.accountability.enrollment.decodeEnrollmentPayload
import com.example.myexodus.accountability.enrollment.encodeEnrollmentPayload
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.delay

/**
 * §10 steps 1-3 relay exchange, v2 model (§8 Step 3). The token document id is
 * SHA-256(token) — a token document is unaddressable without the token — and expiry is
 * enforced server-side by the rules (expiresAt > request.time). Single-use partner
 * response is enforced by the rules' `!('partnerUid' in resource.data)` precondition on
 * update: the first response commits, a concurrent second is denied (the caller maps
 * that to [RespondResult.AlreadyUsed]).
 *
 * The token document is transport only — it grants no authority. Authority is the signed
 * pairing proof each device submits to registerPairing ([PairingRegistration]), which
 * writes `/pairings/{pairId}` (function-only in the rules). [awaitPairingActivation] is
 * how each device observes that record flip to active.
 */
open class PairingExchange(
    private val appContext: Context,
    private val firestore: FirebaseFirestore,
) {

    /** The partner's response fields written into the token document. */
    data class PartnerResponse(
        val partnerUid: String,
        val partnerPayload: EnrollmentPayload,
        /** Partner's transcript signature — the initiator verifies it (§6.1, unchanged). */
        val partnerSignature: ByteArray,
    )

    sealed interface TokenReadResult {
        data class Found(
            val creatorUid: String,
            val initiatorPayload: EnrollmentPayload,
            val partnerResponse: PartnerResponse?,
            /** The document's own expiry — the typed-code path (§19.4 item 1) hydrates a
             *  [PairingToken] from it, since the code itself cannot carry one. */
            val expiresAtEpochMillis: Long,
        ) : TokenReadResult

        /** Token never registered or already deleted. */
        data object NotFound : TokenReadResult

        /** Rules denied the read — expired token, or a forged/unaddressable id. */
        data object Denied : TokenReadResult

        data class Failed(val reason: String) : TokenReadResult
    }

    sealed interface RespondResult {
        data object Written : RespondResult

        /** Another device responded first, or the token expired under us — attempt is dead. */
        data object AlreadyUsed : RespondResult

        data class Failed(val reason: String) : RespondResult
    }

    sealed interface PairingStatus {
        /** Active and bound to this session's transcript. [pairingEpoch] is the AD-07
         *  server-assigned epoch read off the pair doc. */
        data class Active(val pairingEpoch: Int) : PairingStatus

        /** Active, but bound to different material — a previous completed attempt. */
        data object ActiveDifferentSession : PairingStatus

        /** Still pending at timeout — the partner's proof never arrived. */
        data object Pending : PairingStatus
    }

    /** §10 step 1: register the token (with the initiator's payload) before the QR is shown. */
    open suspend fun registerToken(token: PairingToken, creatorUid: String, payload: EnrollmentPayload) {
        AppCheckInstaller.install(appContext)
        firestore.collection(FirestoreSchema.PAIRING_TOKENS_COLLECTION)
            .document(FirestoreSchema.pairingTokenDocumentId(token))
            .set(FirestoreSchema.pairingTokenData(token, creatorUid, encodeEnrollmentPayload(payload)))
            .await()
    }

    /**
     * §10 step 2: read the token document — the partner's entry point (initiator payload
     * returns from here; the QR cannot carry it) and the initiator's poll target for the
     * partner's response.
     */
    open suspend fun readToken(token: PairingToken): TokenReadResult =
        readDocument(FirestoreSchema.pairingTokenDocumentId(token))

    /**
     * Typed-code entry (§19.4 item 1): the identical read, addressed by the raw token
     * value. The code carries neither session id nor expiry — [TokenReadResult.Found]
     * supplies both (session id from the initiator payload, expiry from the document).
     */
    open suspend fun readTokenByValue(tokenBytes: ByteArray): TokenReadResult =
        readDocument(FirestoreSchema.pairingTokenDocumentId(tokenBytes))

    private suspend fun readDocument(documentId: String): TokenReadResult {
        AppCheckInstaller.install(appContext)
        val ref = firestore.collection(FirestoreSchema.PAIRING_TOKENS_COLLECTION)
            .document(documentId)
        val doc = try {
            ref.get().await()
        } catch (e: FirebaseFirestoreException) {
            return if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                TokenReadResult.Denied
            } else {
                TokenReadResult.Failed(e.message ?: "token read failed")
            }
        }
        if (!doc.exists()) return TokenReadResult.NotFound
        // The accessors below throw plain RuntimeExceptions on a present-but-wrong-type field
        // (the rules pin the token-doc key set, not field types), so a hostile partner response
        // could violate this method's sealed-result contract. The outer catch makes the
        // function total: every unreadable document is a typed Failed, never a raw throw.
        return try {
            val creatorUid = doc.getString("creatorUid")
                ?: return TokenReadResult.Failed("token document missing creatorUid")
            val payloadBytes = doc.getBlob("payload")?.toBytes()
                ?: return TokenReadResult.Failed("token document missing payload")
            val initiatorPayload = try {
                decodeEnrollmentPayload(payloadBytes)
            } catch (e: Exception) {
                return TokenReadResult.Failed("token payload unparseable")
            }
            // Rules require expiresAt on create; its absence means a corrupt or foreign doc.
            val expiresAt = doc.getTimestamp("expiresAt")?.toDate()?.time
                ?: return TokenReadResult.Failed("token document missing expiresAt")
            val partnerUid = doc.getString("partnerUid")
            val partner = if (partnerUid == null) {
                null
            } else {
                val partnerPayloadBytes = doc.getBlob("partnerPayload")?.toBytes()
                val partnerSignature = doc.getBlob("partnerSignature")?.toBytes()
                if (partnerPayloadBytes == null || partnerSignature == null) {
                    return TokenReadResult.Failed("token response incomplete")
                }
                val partnerPayload = try {
                    decodeEnrollmentPayload(partnerPayloadBytes)
                } catch (e: Exception) {
                    return TokenReadResult.Failed("partner payload unparseable")
                }
                PartnerResponse(partnerUid, partnerPayload, partnerSignature)
            }
            TokenReadResult.Found(creatorUid, initiatorPayload, partner, expiresAt)
        } catch (e: Exception) {
            // Non-local returns above pass through; only accessor throws land here.
            TokenReadResult.Failed("token document malformed")
        }
    }

    /**
     * §10 step 2 (partner half): write the partner's enrollment payload + transcript
     * signature into the token document. Single-use is the rules' precondition (see class
     * doc) — the denied case surfaces here as [RespondResult.AlreadyUsed].
     */
    open suspend fun respondOnToken(
        token: PairingToken,
        myUid: String,
        myPayload: EnrollmentPayload,
        partnerSignature: ByteArray,
    ): RespondResult {
        AppCheckInstaller.install(appContext)
        val ref = firestore.collection(FirestoreSchema.PAIRING_TOKENS_COLLECTION)
            .document(FirestoreSchema.pairingTokenDocumentId(token))
        return try {
            ref.update(
                mapOf(
                    "partnerUid" to myUid,
                    "partnerPayload" to Blob.fromBytes(encodeEnrollmentPayload(myPayload)),
                    "partnerSignature" to Blob.fromBytes(partnerSignature),
                ),
            ).await()
            RespondResult.Written
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                // Doc exists (update on a missing doc is NOT_FOUND, not denial) and the
                // write was refused: either a concurrent response won the single-use race
                // or the token expired — same outcome either way (v1 had this same
                // denial-mapping ambiguity on the redeem read).
                RespondResult.AlreadyUsed
            } else {
                RespondResult.Failed(e.message ?: "token response write failed")
            }
        }
    }

    /**
     * The initiator's poll: waits for the partner's response to appear on the token
     * document. Null on timeout or a definitive token failure; transient read errors keep
     * polling.
     */
    open suspend fun pollTokenResponse(token: PairingToken, timeoutMillis: Long): PartnerResponse? {
        AppCheckInstaller.install(appContext)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            when (val read = readToken(token)) {
                is TokenReadResult.Found -> read.partnerResponse?.let { return it }
                TokenReadResult.NotFound, TokenReadResult.Denied -> return null
                is TokenReadResult.Failed -> Unit // transient — keep polling
            }
            if (System.currentTimeMillis() >= deadline) return null
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    /**
     * Waits for `/pairings/{pairId}` to flip active with this session's transcript hash in
     * [myUid]'s proof entry (the registerPairing function writes it; a not-yet-visible
     * record reads as denial — keep polling). [PairingStatus.ActiveDifferentSession] stops
     * the wait without success: the pair is active but bound to other material.
     */
    open suspend fun awaitPairingActivation(
        pairId: String,
        myUid: String,
        expectedTranscriptHash: String,
        timeoutMillis: Long,
    ): PairingStatus {
        AppCheckInstaller.install(appContext)
        val ref = firestore.collection(FirestoreSchema.PAIRINGS_COLLECTION).document(pairId)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val doc = try {
                ref.get().await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    null // not registered yet (or not a member yet) — keep polling
                } else {
                    throw e
                }
            }
            if (doc != null && doc.exists() && doc.getString("status") == "active") {
                val proof = (doc.get("proofs") as? Map<*, *>)?.get(myUid) as? Map<*, *>
                return if (proof?.get("transcriptHash") == expectedTranscriptHash) {
                    PairingStatus.Active((doc.getLong("pairingEpoch") ?: 0L).toInt())
                } else {
                    PairingStatus.ActiveDifferentSession
                }
            }
            if (System.currentTimeMillis() >= deadline) return PairingStatus.Pending
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    /** Deletes the token document (initiator abort cleanup / post-registration hygiene). */
    open suspend fun deleteToken(token: PairingToken) {
        AppCheckInstaller.install(appContext)
        try {
            firestore.collection(FirestoreSchema.PAIRING_TOKENS_COLLECTION)
                .document(FirestoreSchema.pairingTokenDocumentId(token))
                .delete()
                .await()
        } catch (e: FirebaseFirestoreException) {
            // Expired or already gone — nothing to clean up.
        }
    }

    /**
     * §8 Step 13 revocation: flips `/pairings/{pairId}` status to `terminated` — the rules pin
     * the update to members + active→terminated (no other field, no un-terminating), and the
     * mailbox create rule (status == 'active') makes further partner writes structurally
     * impossible (Req 29: ending a partnership stops all future syncs). The registerPairing
     * function reads `terminated` as a re-pair and increments the AD-07 epoch. Denial is a
     * no-op: the row is already terminated (the partner got there first) or gone entirely.
     */
    open suspend fun terminatePair(pairId: String) {
        AppCheckInstaller.install(appContext)
        try {
            FirestoreSchema.pairingDocument(firestore, pairId)
                .update("status", "terminated")
                .await()
        } catch (e: FirebaseFirestoreException) {
            // Already terminated, or not (yet) a member — nothing to revoke.
        }
    }

    companion object {
        const val POLL_INTERVAL_MILLIS = 750L
    }
}
