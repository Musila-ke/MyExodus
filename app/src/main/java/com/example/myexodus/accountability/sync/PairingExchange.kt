package com.example.myexodus.accountability.sync

import android.content.Context
import com.example.myexodus.accountability.enrollment.EnrollmentPayload
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.enrollment.PairingToken
import com.example.myexodus.accountability.enrollment.decodeEnrollmentPayload
import com.example.myexodus.accountability.enrollment.encodeEnrollmentPayload
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.delay
import java.util.Base64

/**
 * §10 steps 1-3 relay exchange, v2 model (§8 Step 3). The token document id is
 * SHA-256(token) — a token document is unaddressable without the token, which is what
 * makes the possession-gated read the capability. Since Release 0 (account plan §7 step 2)
 * the writes are server-mediated: `createPairInvitation`, `respondToPairInvitation` and
 * `terminatePair` validate expiry, single-use, self-response and membership server-side;
 * the direct-write rules are gone. Reads stay direct and expiry-gated by the rules.
 *
 * The token document is transport only — it grants no authority. Authority is the signed
 * pairing proof each device submits to registerPairing ([PairingRegistration]), which
 * writes `/pairings/{pairId}` (function-only in the rules). [awaitPairingActivation] is
 * how each device observes that record flip to active.
 */
open class PairingExchange(
    private val appContext: Context,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
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

    /**
     * §10 step 1: register the token (with the initiator's payload) before the QR is shown.
     * The creator uid is auth-derived server-side; [creatorUid] stays in the signature
     * because the test seam models the document it would have written.
     */
    open suspend fun registerToken(token: PairingToken, creatorUid: String, payload: EnrollmentPayload) {
        AppCheckInstaller.install(appContext)
        functions.getHttpsCallable("createPairInvitation").call(
            mapOf(
                "tokenId" to FirestoreSchema.pairingTokenDocumentId(token),
                "expiresAtEpochMillis" to token.expiresAtEpochMillis,
                "payloadB64" to Base64.getEncoder().encodeToString(encodeEnrollmentPayload(payload)),
            ),
        ).await()
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
     * signature onto the token document through `respondToPairInvitation`. Single-use,
     * expiry and self-response are server-validated — expired/already-used both surface as
     * [RespondResult.AlreadyUsed]. The caller uid is auth-derived; [myUid] stays in the
     * signature for the test seam.
     */
    open suspend fun respondOnToken(
        token: PairingToken,
        myUid: String,
        myPayload: EnrollmentPayload,
        partnerSignature: ByteArray,
    ): RespondResult {
        AppCheckInstaller.install(appContext)
        return try {
            functions.getHttpsCallable("respondToPairInvitation").call(
                mapOf(
                    "tokenId" to FirestoreSchema.pairingTokenDocumentId(token),
                    "partnerPayloadB64" to Base64.getEncoder()
                        .encodeToString(encodeEnrollmentPayload(myPayload)),
                    "partnerSignatureB64" to Base64.getEncoder().encodeToString(partnerSignature),
                ),
            ).await()
            RespondResult.Written
        } catch (e: FirebaseFunctionsException) {
            when (e.code) {
                // Expired and already-used are the same dead-attempt outcome.
                FirebaseFunctionsException.Code.NOT_FOUND,
                FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                -> RespondResult.AlreadyUsed

                else -> RespondResult.Failed(e.message ?: "token response write failed")
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

    /**
     * True when the server already holds an ACTIVE pairing between these two uids. The id is
     * derived exactly as registerPairing derives it, so this is the same record that would
     * reject the ceremony at submit time with PAIRING_ACTIVE — the read makes the refusal
     * arrive before the two people compare a safety code. Any read failure returns false:
     * the submit-time check stays the authority.
     */
    open suspend fun existingActivePair(uidA: String, uidB: String): Boolean {
        val pairId = EnrollmentTranscript.durablePairIdOf(uidA, uidB)
        return runCatching {
            val doc = firestore.collection(FirestoreSchema.PAIRINGS_COLLECTION)
                .document(pairId)
                .get()
                .await()
            doc.exists() && doc.getString("status") == "active"
        }.getOrDefault(false)
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
     * §8 Step 13 / Step 10 revocation: `terminatePair` performs the member-only,
     * idempotent active→terminated transition server-side and writes the deterministic
     * `system_events/pair_terminated` notice. `terminated` (missing pair included) makes
     * further partner writes structurally impossible (Req 29: ending a partnership stops
     * all future syncs); registerPairing reads `terminated` as a re-pair and increments
     * the AD-07 epoch.
     *
     * Returns true only when the server-side exit is acknowledged: success, or a denial
     * that reads back `terminated` (the partner terminated first). Network, attestation,
     * and ambiguous denials return false — Step 10's journal keeps that row pending and
     * retries.
     */
    open suspend fun terminatePair(pairId: String): Boolean {
        AppCheckInstaller.install(appContext)
        return try {
            functions.getHttpsCallable("terminatePair").call(mapOf("pairId" to pairId)).await()
            true
        } catch (e: FirebaseFunctionsException) {
            when (e.code) {
                FirebaseFunctionsException.Code.NOT_FOUND -> true
                FirebaseFunctionsException.Code.PERMISSION_DENIED,
                FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                -> runCatching {
                    val snapshot = FirestoreSchema.pairingDocument(firestore, pairId).get().await()
                    !snapshot.exists() || snapshot.getString("status") == "terminated"
                }.getOrDefault(false)

                else -> false
            }
        }
    }

    companion object {
        const val POLL_INTERVAL_MILLIS = 750L
    }
}
