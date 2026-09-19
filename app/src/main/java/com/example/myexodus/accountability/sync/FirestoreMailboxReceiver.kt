package com.example.myexodus.accountability.sync

import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.SessionKeyManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * §7 item 10 pull half (v2): reads this device's own mailbox `/mailboxes/{myUid}/messages` and
 * runs each document through [MailboxMessageHandler]. On a push wake the specific `messageId`
 * is fetched first — the mailbox read is watermark-ordered (`timestamp` asc), and an
 * out-of-order arrival (older timestamp delivered after the watermark passed it) would
 * otherwise be skipped forever. The bulk query then reconciles everything after the
 * watermark, which also covers `onDeletedMessages()` (the same pass IS the full
 * reconciliation — no extra persisted flag needed).
 *
 * The mailbox holds only the partner's messages in the v2 topology (senders write to the
 * partner's mailbox), so no uid-based query constraint is needed — the read rule is
 * path-scoped to `request.auth.uid == recipientUid`.
 */
class FirestoreMailboxReceiver(
    private val firestore: FirebaseFirestore,
    private val enrollmentDao: EnrollmentDao,
    private val store: ChainStateStore,
    private val keystore: AccountabilityKeyStore,
    private val cekDerivation: CekDerivation,
    private val sessionKeyManager: SessionKeyManager,
    private val handler: MailboxMessageHandler,
    private val limit: Int = 500,
) {

    data class PullResult(
        val appended: Int = 0,
        val duplicates: Int = 0,
        val gaps: Int = 0,
        val forks: Int = 0,
        val invalid: Int = 0,
        val corrupt: Int = 0,
        /** §8 Step 15: envelopes whose keyEpoch the receive policy refuses (never applied). */
        val unknownKeyEpoch: Int = 0,
        /** Non-actionable no-ops: already processed (AD-10b), missing doc, own sender id. */
        val skipped: Int = 0,
        /** §9 row 458 — counted separately from [invalid] by design (§14). */
        val tamperSuspected: Int = 0,
        /** §10: a verified DELETION_REQUEST was received; the pair was shredded. The pull ends
         *  there — every remaining document belongs to a shredded pair and must not re-create
         *  chain rows. */
        val deletionRequested: Boolean = false,
        /** A verified deletion request whose local shred failed — left unacked for re-delivery. */
        val deletionFailed: Boolean = false,
        val isNotEnrolled: Boolean = false,
    )

    suspend fun pull(pairId: String, myUid: String, messageId: String? = null): PullResult {
        val enrollment = enrollmentDao.getByPairId(pairId)
            ?: return PullResult(isNotEnrolled = true)
        val cek = cekDerivation.deriveCek(
            pairId,
            keystore.agreementPrivateKey(),
            decodePublicKey(enrollment.partnerAgreementPubKey),
        )
        val partnerSigningKey = decodePublicKey(enrollment.partnerSigningPubKey)

        var result = PullResult()
        var deletionRequested = false

        suspend fun handle(id: String, load: suspend () -> MailboxDocument?) {
            // AD-12/§8 Step 15: the epoch policy is evaluated against the partner's chain head
            // as of this message — fresh head, then one deterministic key for that envelope.
            val head = store.localHead(pairId, enrollment.partnerSigningKeyId)
            val r = handler.handle(
                pairId = pairId,
                recipientUid = myUid,
                mySenderKeyId = enrollment.mySenderKeyId,
                partnerSigningKey = partnerSigningKey,
                keyForEpoch = { epoch ->
                    if (sessionKeyManager.acceptsIncoming(head, epoch, System.currentTimeMillis())) {
                        cekDerivation.deriveSessionKey(cek, epoch)
                    } else {
                        null // the handler logs the UNKNOWN_KEY_EPOCH security event
                    }
                },
                messageId = id,
                load = load,
            )
            result = when (r.kind) {
                MailboxMessageHandler.Kind.APPENDED -> result.copy(appended = result.appended + 1)
                MailboxMessageHandler.Kind.DUPLICATE -> result.copy(duplicates = result.duplicates + 1)
                MailboxMessageHandler.Kind.GAP -> result.copy(gaps = result.gaps + 1)
                MailboxMessageHandler.Kind.FORK -> result.copy(forks = result.forks + 1)
                MailboxMessageHandler.Kind.BAD_SIGNATURE -> result.copy(invalid = result.invalid + 1)
                MailboxMessageHandler.Kind.TAMPER_SUSPECTED ->
                    // Also a signature failure, but §14 tracks it as its own security signal.
                    result.copy(invalid = result.invalid + 1, tamperSuspected = result.tamperSuspected + 1)
                // REJECTED joins corrupt, matching the v1 counter's reading.
                MailboxMessageHandler.Kind.CORRUPT,
                MailboxMessageHandler.Kind.REJECTED -> result.copy(corrupt = result.corrupt + 1)

                MailboxMessageHandler.Kind.UNKNOWN_KEY_EPOCH ->
                    result.copy(unknownKeyEpoch = result.unknownKeyEpoch + 1)

                MailboxMessageHandler.Kind.DELETION_REQUESTED -> {
                    deletionRequested = true
                    result.copy(deletionRequested = true)
                }

                MailboxMessageHandler.Kind.DELETION_FAILED -> result.copy(deletionFailed = true)

                MailboxMessageHandler.Kind.ALREADY_PROCESSED,
                MailboxMessageHandler.Kind.MISSING,
                MailboxMessageHandler.Kind.SELF,
                MailboxMessageHandler.Kind.NO_PAYLOAD -> result.copy(skipped = result.skipped + 1)
            }
        }

        // §6 step 3 expedited path: the wake's own id, before the watermark query can miss it.
        if (messageId != null) {
            handle(messageId) { fetchDocument(myUid, messageId) }
            if (deletionRequested) return result
        }

        // Quota: pulled after the last-pull watermark (§7.5) — one 500-doc re-read every 15
        // minutes would exhaust the free tier.
        var query: Query = FirestoreSchema.mailboxCollection(firestore, myUid).orderBy("timestamp")
        enrollment.mailboxWatermarkTsEpochMillis?.let {
            query = query.startAfter(timestampOf(it))
        }
        val snapshot = query.limit(limit.toLong()).get().await()
        var watermark = enrollment.mailboxWatermarkTsEpochMillis
        for (doc in snapshot.documents) {
            doc.getTimestamp("timestamp")?.let { ts ->
                watermark = maxOf(watermark ?: 0L, ts.toDate().time)
            }
            handle(doc.id) { doc.toMailboxDocument() }
            // The pair is shredded — stop before any remaining document re-creates chain rows
            // for it (they belong to a wiped pairing and would never be readable again).
            if (deletionRequested) return result
        }
        if (watermark != null && watermark != enrollment.mailboxWatermarkTsEpochMillis) {
            enrollmentDao.updateMailboxWatermark(pairId, watermark)
        }

        return result
    }

    private suspend fun fetchDocument(myUid: String, messageId: String): MailboxDocument? {
        val doc = FirestoreSchema.mailboxMessageDocument(firestore, myUid, messageId).get().await()
        return if (doc.exists()) doc.toMailboxDocument() else null
    }

    private fun DocumentSnapshot.toMailboxDocument(): MailboxDocument = MailboxDocument(
        messageId = id,
        senderId = getString("senderId"),
        payloadBase64 = getString("payload"),
        timestampEpochMillis = getTimestamp("timestamp")?.toDate()?.time,
    )

    /** Watermark is persisted as epoch millis (server timestamps have microsecond precision —
     *  sub-ms ties are not reachable in practice, so the truncation loses nothing). */
    private fun timestampOf(epochMillis: Long): Timestamp =
        Timestamp(epochMillis / 1000, ((epochMillis % 1000) * 1_000_000).toInt())
}

/** DER SubjectPublicKeyInfo → public key. Top-level so the dashboard's re-decode path
 *  (Step 12) uses the exact decoding this receiver's CEK derivation does. */
internal fun decodePublicKey(der: ByteArray): PublicKey =
    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
