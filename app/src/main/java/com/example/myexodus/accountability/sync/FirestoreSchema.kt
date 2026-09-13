package com.example.myexodus.accountability.sync

import com.example.myexodus.accountability.enrollment.PairingToken
import com.example.myexodus.accountability.state.ChainEvent
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import java.security.MessageDigest

/**
 * §8.4 Firestore schema: `pairs/{pairId}` (state) + `accountability_events/{eventId}` (mailbox),
 * `backfill_requests/{requestId}` — the §9 reverse channel for gap recovery — and
 * `pairing_tokens/{tokenId}` — the §10 single-use exchange register. The cloud relay is
 * untrusted (NFR2): documents carry ciphertext envelopes and public key ids only. Document
 * ids are deterministic so re-draining the outbox never duplicates a mailbox entry —
 * idempotent by construction.
 */
object FirestoreSchema {

    const val PAIRS_COLLECTION = "pairs"
    const val EVENTS_COLLECTION = "accountability_events"
    const val BACKFILL_COLLECTION = "backfill_requests"
    const val PAIRING_TOKENS_COLLECTION = "pairing_tokens"
    const val DELETED_PAIRS_COLLECTION = "deleted_pairs"

    fun eventDocumentId(pairId: String, senderKeyId: String, sequence: Long): String {
        val preimage = "$pairId|$senderKeyId|$sequence"
        val digest = MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** The canonical `pairs/{pairId}` state document the cross-document rules validate against. */
    fun pairStateDocument(firestore: FirebaseFirestore, pairId: String): DocumentReference =
        firestore.collection(PAIRS_COLLECTION).document(pairId)

    /** Membership map `{uid: true}` — the rules test `request.auth.uid in resource.data.members`. */
    fun pairStateData(members: Set<String>): Map<String, Any> =
        mapOf("members" to members.associateWith { true }, "updatedAt" to FieldValue.serverTimestamp())

    /**
     * §10 step 2: the pair state document the redeemer creates carries its enrollment
     * payload (attested chain + transcript signature + agreement key) for the initiator
     * to verify; the initiator's transcript signature lands here later as
     * `initiatorSignature` (see [PairingExchange.confirmPairing]).
     */
    fun pairStateData(members: Set<String>, enrollmentPayload: ByteArray): Map<String, Any> =
        pairStateData(members) + mapOf("enrollment" to Blob.fromBytes(enrollmentPayload))

    /** Mailbox document for one chain event (§8.4 field contract: authorUid, partnerUid, pairId, envelope). */
    fun eventDocumentData(
        event: ChainEvent,
        authorUid: String,
        partnerUid: String,
    ): Map<String, Any> = mapOf(
        "pairId" to event.pairId,
        "senderKeyId" to event.senderKeyId,
        "sequence" to event.sequence,
        "authorUid" to authorUid,
        "partnerUid" to partnerUid,
        "envelope" to Blob.fromBytes(event.envelope),
        "createdAt" to FieldValue.serverTimestamp(),
    )

    /** §9 reverse channel: deterministic id so repeated gap scans never duplicate a request. */
    fun backfillRequestDocumentId(pairId: String, senderKeyId: String, sequence: Long): String {
        val preimage = "$pairId|$senderKeyId|$sequence|backfill"
        val digest = MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** §9 request for the sender to re-publish one missing sequence of its own chain. */
    fun backfillRequestData(pairId: String, senderKeyId: String, sequence: Long, requesterUid: String): Map<String, Any> =
        mapOf(
            "pairId" to pairId,
            "senderKeyId" to senderKeyId,
            "sequence" to sequence,
            "requesterUid" to requesterUid,
            "createdAt" to FieldValue.serverTimestamp(),
        )

    /**
     * §10 step 1: the relay stores only SHA-256(token) as the document id — a token document
     * is unaddressable without the token itself, so read/delete rules can gate purely on
     * possession + expiry (no uid knowledge needed at exchange time).
     */
    fun pairingTokenDocumentId(token: PairingToken): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.token)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * §10 step 1: relay-registered token fields. expiresAt is a Firestore Timestamp so the
     * rules' lifetime gate compares Timestamp to `request.time` (a number epoch would not
     * be comparable in rules).
     */
    /**
     * §10 step 1: relay-registered token fields. The initiator's enrollment payload rides
     * here (not in the QR — real attestation chains overflow QR capacity, 2953 B ceiling;
     * caught on-device on the X6532, 2026-08-30) and is returned by the redemption
     * transaction. [payload] is public key material + attestation certs, gated by token
     * possession and expiry like the token itself.
     */
    /** §10 revocation tombstone: written just before the pair doc is deleted; append-only in rules. */
    fun deletedPairData(pairId: String): Map<String, Any> = mapOf(
        "pairId" to pairId,
        "revokedAt" to FieldValue.serverTimestamp(),
    )

    fun pairingTokenData(token: PairingToken, creatorUid: String, payload: ByteArray): Map<String, Any> = mapOf(
        "pairId" to token.pairId,
        "expiresAt" to Timestamp(
            token.expiresAtEpochMillis / 1000,
            ((token.expiresAtEpochMillis % 1000) * 1_000_000).toInt(),
        ),
        "creatorUid" to creatorUid,
        "createdAt" to FieldValue.serverTimestamp(),
        "payload" to Blob.fromBytes(payload),
    )
}
