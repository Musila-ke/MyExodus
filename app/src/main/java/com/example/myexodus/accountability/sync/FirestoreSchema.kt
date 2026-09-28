package com.example.myexodus.accountability.sync

import com.example.myexodus.accountability.enrollment.PairingToken
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import java.security.MessageDigest

/**
 * §8.4 Firestore schema. v2 (Partner Pillar): `pairings/{pairId}` (function-written
 * registration state; client writes limited to the Step 13 status→terminated revocation),
 * `pairing_tokens/{tokenId}` (the §10 single-use rendezvous), the mailbox layout, and push
 * installations. The v1 `pairs` / `accountability_events` / `deleted_pairs` remainders were
 * retired with the unpair rework (plan §8 Step 14) and the repair protocol (AD-08/AD-08b)
 * replaced the v1 `backfill_requests` channel with `sync_request` chain events. The cloud
 * relay is untrusted (NFR2): documents carry ciphertext envelopes and public key ids only.
 * Document ids are deterministic so re-draining the outbox never duplicates a mailbox entry —
 * idempotent by construction.
 */
object FirestoreSchema {

    const val PAIRINGS_COLLECTION = "pairings"
    const val PAIRING_TOKENS_COLLECTION = "pairing_tokens"
    const val USERS_COLLECTION = "users"
    const val PUSH_INSTALLATIONS_COLLECTION = "push_installations"
    const val MAILBOXES_COLLECTION = "mailboxes"
    const val MESSAGES_COLLECTION = "messages"
    const val ACKS_COLLECTION = "acks"

    /** v2 `pairings/{pairId}` document — the uid-resolution read (members is the array the
     *  rules' `request.auth.uid in resource.data.members` checks). */
    fun pairingDocument(firestore: FirebaseFirestore, pairId: String): DocumentReference =
        firestore.collection(PAIRINGS_COLLECTION).document(pairId)

    /** `mailboxes/{recipientUid}/messages` — the v2 read rule is path-scoped to the owner, so
     *  a plain ordered query on it needs no where-clause coverage (unlike v1). */
    fun mailboxCollection(
        firestore: FirebaseFirestore,
        recipientUid: String,
    ): CollectionReference = firestore.collection(MAILBOXES_COLLECTION).document(recipientUid)
        .collection(MESSAGES_COLLECTION)

    /**
     * Plan §8 Steps 1/9 (AD-06b, AD-18a sibling): one mailbox message per outbox row, keyed by
     * the client-generated `messageId` — deterministic, so a re-drain is idempotent by
     * construction. Recipient-addressed: the *partner's* mailbox is the only one the sender
     * may write, and the rules gate the create against the active pairing.
     */
    fun mailboxMessageDocument(
        firestore: FirebaseFirestore,
        recipientUid: String,
        messageId: String,
    ): DocumentReference = mailboxCollection(firestore, recipientUid).document(messageId)

    /**
     * Exactly the six keys the v2 rules' create `hasOnly` allows — an extra field fails every
     * write with PERMISSION_DENIED. [timestamp] must be the server timestamp (`request.time`),
     * an epoch number would not be comparable in rules. [senderId] is the sender's
     * chain-scoping key id (AD-06a `senderPublicKeyHash`); the plan's field list does not
     * define it further, and the recipient needs it to pick the right chain.
     */
    fun mailboxMessageData(
        senderId: String,
        senderUid: String,
        pairId: String,
        payloadBase64: String,
        keyEpoch: Int,
    ): Map<String, Any> = mapOf(
        "senderId" to senderId,
        "senderUid" to senderUid,
        "pairId" to pairId,
        "payload" to payloadBase64,
        "keyEpoch" to keyEpoch,
        "timestamp" to FieldValue.serverTimestamp(),
    )

    /**
     * AD-18b acknowledgment: one ack per (message, installation) — the cleanup job deletes a
     * message only once every registered non-stale installation has acked (or the 30-day cap
     * hits). Rules: create gated on `request.auth.uid == recipientUid`, `hasOnly(['ackedAt'])`,
     * `ackedAt == request.time`; update/delete denied (append-only).
     */
    fun mailboxAckDocument(
        firestore: FirebaseFirestore,
        recipientUid: String,
        messageId: String,
        installationId: String,
    ): DocumentReference = mailboxMessageDocument(firestore, recipientUid, messageId)
        .collection(ACKS_COLLECTION).document(installationId)

    fun mailboxAckData(): Map<String, Any> = mapOf("ackedAt" to FieldValue.serverTimestamp())

    /**
     * AD-18a push-installation registration (plan §8 Step 7, client FCM transport): exactly
     * the keys the v2 rules' create/update `hasOnly` allows, nothing else. `updatedAt` must
     * equal the server timestamp (rules gate), so every write refreshes AD-18c staleness.
     * Writers replace the document (no merge) — a server-written `stale: true` flag must
     * disappear on re-registration, and merge would keep it.
     */
    fun pushInstallationData(
        provider: String,
        token: String,
        platform: String,
        appVersion: String,
    ): Map<String, Any> = mapOf(
        "provider" to provider,
        "token" to token,
        "platform" to platform,
        "appVersion" to appVersion,
        "updatedAt" to FieldValue.serverTimestamp(),
        "createdAt" to FieldValue.serverTimestamp(),
    )

    fun pushInstallationDocument(
        firestore: FirebaseFirestore,
        uid: String,
        installationId: String,
    ): DocumentReference = firestore.collection(USERS_COLLECTION).document(uid)
        .collection(PUSH_INSTALLATIONS_COLLECTION).document(installationId)

    /**
     * §10 step 1: the relay stores only SHA-256(token) as the document id — a token document
     * is unaddressable without the token itself, so read/delete rules can gate purely on
     * possession + expiry (no uid knowledge needed at exchange time).
     */
    fun pairingTokenDocumentId(token: PairingToken): String = pairingTokenDocumentId(token.token)

    /** Typed-code entry (§19.4 item 1): the same id, addressed by the raw token value. */
    fun pairingTokenDocumentId(tokenBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(tokenBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * §10 step 1: relay-registered token fields — exactly the keys the rules' create
     * hasOnly allows. The initiator's enrollment payload rides here (not in the QR — real
     * attestation chains overflow QR capacity, 2953 B ceiling; caught on-device on the
     * X6532, 2026-08-30); [payload] is public key material + attestation certs, gated by
     * token possession and expiry like the token itself. expiresAt is a Firestore Timestamp
     * so the rules' lifetime gate can compare it to `request.time` (a number epoch would
     * not be comparable in rules). The partner's response lands as partnerUid /
     * partnerPayload / partnerSignature via [PairingExchange.respondOnToken].
     */
    fun pairingTokenData(token: PairingToken, creatorUid: String, payload: ByteArray): Map<String, Any> = mapOf(
        "creatorUid" to creatorUid,
        "expiresAt" to Timestamp(
            token.expiresAtEpochMillis / 1000,
            ((token.expiresAtEpochMillis % 1000) * 1_000_000).toInt(),
        ),
        "payload" to Blob.fromBytes(payload),
    )
}
