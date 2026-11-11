package com.example.myexodus.accountability.sync

import com.example.myexodus.accountability.delivery.PartnerMailboxDocument
import com.example.myexodus.accountability.enrollment.PairingToken
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import kotlinx.coroutines.tasks.await
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
    const val PARTNER_MAILBOXES_COLLECTION = "partnerMailboxes"
    const val MESSAGES_COLLECTION = "messages"
    const val ACKS_COLLECTION = "acks"
    const val NUDGES_COLLECTION = "nudges"
    const val NUDGE_SETTINGS_COLLECTION = "nudgeSettings"

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
     * §7 item 10 pull half: the watermark-ordered mailbox page — `timestamp` asc, strictly
     * after the last pass's watermark (§7.5 quota: re-reading the whole 500-doc mailbox every
     * 15 minutes would exhaust the free tier). Documents missing the field never appear in an
     * `orderBy("timestamp")` result, so a null-timestamp document cannot poison the scan.
     */
    suspend fun mailboxDocsAfter(
        firestore: FirebaseFirestore,
        recipientUid: String,
        afterEpochMillis: Long?,
        limit: Int,
    ): List<MailboxDocument> {
        var query: Query = mailboxCollection(firestore, recipientUid).orderBy("timestamp")
        if (afterEpochMillis != null) query = query.startAfter(timestampOf(afterEpochMillis))
        return query.limit(limit.toLong()).get().await().documents.map(::mailboxDocumentOf)
    }

    /** §6 step 3 expedited path: the wake's own document, fetched before the watermark query
     *  can miss it. Null when the document is gone (cleanup/cap) or the id addresses nothing. */
    suspend fun mailboxDoc(
        firestore: FirebaseFirestore,
        recipientUid: String,
        messageId: String,
    ): MailboxDocument? {
        val doc = mailboxMessageDocument(firestore, recipientUid, messageId).get().await()
        return if (doc.exists()) mailboxDocumentOf(doc) else null
    }

    fun mailboxDocumentOf(doc: DocumentSnapshot): MailboxDocument = MailboxDocument(
        messageId = doc.id,
        senderId = doc.getString("senderId"),
        payloadBase64 = doc.getString("payload"),
        timestampEpochMillis = doc.getTimestamp("timestamp")?.toDate()?.time,
        pairId = doc.getString("pairId"),
    )

    /**
     * §8.6 Phase 5 recipient page: `partnerMailboxes/{uid}/messages` filtered to this
     * installation and ordered by `createdAt` from the persisted watermark. The composite
     * index (recipientInstallationId, createdAt) in firestore.indexes.json backs this shape.
     * `>=` not `>`: documents tying the watermark are re-read and dedupe through the delivery
     * receipt, so a tie can never be skipped. `orderBy` also excludes any document missing
     * `createdAt` — one that cannot be ordered cannot be placed behind the watermark anyway.
     */
    suspend fun partnerMailboxDocsAfter(
        firestore: FirebaseFirestore,
        recipientUid: String,
        installationId: String,
        createdAtFromMs: Long,
        limit: Int,
    ): List<PartnerMailboxDocument> = firestore
        .collection(PARTNER_MAILBOXES_COLLECTION).document(recipientUid)
        .collection(MESSAGES_COLLECTION)
        .whereEqualTo("recipientInstallationId", installationId)
        .whereGreaterThanOrEqualTo("createdAt", timestampOf(createdAtFromMs))
        .orderBy("createdAt")
        .limit(limit.toLong())
        .get().await()
        .documents.map(::partnerMailboxDocumentOf)

    /** Backend field names (partner-ingest.js): the sender's `ownerUid`/`installationId` are
     *  exactly the AAD inputs the receiver needs to open the envelope. */
    fun partnerMailboxDocumentOf(doc: DocumentSnapshot): PartnerMailboxDocument =
        PartnerMailboxDocument(
            messageId = doc.id,
            eventId = doc.getString("eventId"),
            senderInstallationId = doc.getString("installationId"),
            senderOwnerUid = doc.getString("ownerUid"),
            kind = doc.getString("kind"),
            recipientKeyId = doc.getString("recipientKeyId"),
            payload = doc.getString("payload"),
            encoding = doc.getString("encoding"),
            pairId = doc.getString("pairId"),
            createdAtMs = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
        )

    /** The watermark is persisted as epoch millis (server timestamps have microsecond
     *  precision — sub-ms ties are not reachable in practice, so the truncation loses
     *  nothing). */
    private fun timestampOf(epochMillis: Long): Timestamp =
        Timestamp(epochMillis / 1000, ((epochMillis % 1000) * 1_000_000).toInt())

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
     * AD-18a push-installation registration (plan §8 Step 7, client FCM transport; SOS §4.3
     * delta): exactly the keys the v2 rules' create/update `hasOnly` allows, nothing else.
     * `updatedAt` must equal the server timestamp (rules gate), so every write refreshes
     * AD-18c staleness. `pushEligible` is the §8.12 capability snapshot the SOS dispatcher
     * queries; optional under the rules so pre-SOS clients keep registering. `registrationKind`
     * (§8.6 Phase 5) tells the partner notifier how the token is addressed — it refuses a
     * non-`TOKEN` row rather than passing an FID to a token-addressing API. Writers replace
     * the document (no merge) — a server-written `stale: true` flag must disappear on
     * re-registration, and merge would keep it.
     */
    fun pushInstallationData(
        provider: String,
        token: String,
        platform: String,
        appVersion: String,
        pushEligible: Boolean,
        registrationKind: String,
    ): Map<String, Any> = mapOf(
        "provider" to provider,
        "token" to token,
        "platform" to platform,
        "appVersion" to appVersion,
        "pushEligible" to pushEligible,
        "registrationKind" to registrationKind,
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
     * Partner SOS (V1 Req 35, plan §8.2): `pairings/{pairId}/nudges/{nudgeId}` — written
     * exclusively by the sendNudge callable; the recipient device reads and acknowledges.
     * The client never creates or deletes a nudge document (rules deny both).
     */
    fun nudgeDocument(
        firestore: FirebaseFirestore,
        pairId: String,
        nudgeId: String,
    ): DocumentReference = firestore.collection(PAIRINGS_COLLECTION).document(pairId)
        .collection(NUDGES_COLLECTION).document(nudgeId)

    /** Exactly the one key the nudge ack rule allows (`hasOnly(['ackedAt'])`,
     *  `ackedAt == request.time` — server-timestamp form, not a number). */
    fun nudgeAckData(): Map<String, Any> = mapOf("ackedAt" to FieldValue.serverTimestamp())

    /**
     * The §6.9 recipient inbox query, verbatim — the only shape the list rule admits (its
     * eligibility is pinned by rules-test R-F2q: recipient equality is what proves the rule;
     * expiry is client-side because a LIST cannot evaluate `request.time`). The composite
     * index in firestore.indexes.json matches these three equalities + the descending order.
     * On the local rig the two null equalities are dropped ([EmulatorCompat]) and the
     * repository filters those documents client-side instead.
     */
    fun nudgeInboxQuery(firestore: FirebaseFirestore, pairId: String, uid: String): Query {
        val recipientOnly = firestore.collection(PAIRINGS_COLLECTION).document(pairId)
            .collection(NUDGES_COLLECTION)
            .whereEqualTo("recipientUid", uid)
        val pendingOnly = if (EmulatorCompat.matchesExplicitNulls) {
            recipientOnly.whereEqualTo("suppressedAt", null).whereEqualTo("ackedAt", null)
        } else {
            recipientOnly
        }
        return pendingOnly
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(10)
    }

    /**
     * `pairings/{pairId}/nudgeSettings/{uid}` — owner-owned block preference (plan §8.2);
     * rules gate writes on an active pair + membership and allow exactly these two keys.
     */
    fun nudgeSettingsDocument(
        firestore: FirebaseFirestore,
        pairId: String,
        uid: String,
    ): DocumentReference = firestore.collection(PAIRINGS_COLLECTION).document(pairId)
        .collection(NUDGE_SETTINGS_COLLECTION).document(uid)

    fun nudgeSettingsData(blocked: Boolean): Map<String, Any> = mapOf(
        "blocked" to blocked,
        "updatedAt" to FieldValue.serverTimestamp(),
    )

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

}
