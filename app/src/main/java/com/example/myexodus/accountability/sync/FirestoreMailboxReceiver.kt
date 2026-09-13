package com.example.myexodus.accountability.sync

import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.ChainStateStore
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * §7.5 Firestore listener, pull-based: fetches this pair's mailbox, verifies + decrypts +
 * appends partner events (FR4), and writes §9 backfill requests for any sequences left
 * gapped. Pulling instead of snapshot-listening keeps the cycle testable and matches the
 * relay's push model; both run on the same periodic sync schedule.
 */
class FirestoreMailboxReceiver(
    private val firestore: FirebaseFirestore,
    private val enrollmentDao: EnrollmentDao,
    private val store: ChainStateStore,
    private val keystore: AccountabilityKeyStore,
    private val cekDerivation: CekDerivation,
    private val eventCipher: EventCipher,
    private val processor: MailboxEventProcessor,
    private val onDeletionRequest: suspend (pairId: String) -> Unit = {},
    private val limit: Int = 500,
) {

    data class PullResult(
        val appended: Int = 0,
        val duplicates: Int = 0,
        val gaps: Int = 0,
        val forks: Int = 0,
        val invalid: Int = 0,
        val corrupt: Int = 0,
        val backfillRequestsWritten: Int = 0,
        /** §10: a verified DELETION_REQUEST was received; the pair was shredded. */
        val deletionRequested: Boolean = false,
        val isNotEnrolled: Boolean = false,
    )

    suspend fun pull(pairId: String, myUid: String): PullResult {
        val enrollment = enrollmentDao.getByPairId(pairId)
            ?: return PullResult(isNotEnrolled = true)
        val cek = cekDerivation.deriveCek(
            pairId,
            keystore.agreementPrivateKey(),
            decodePublicKey(enrollment.partnerAgreementPubKey),
        )
        val partnerSigningKey = decodePublicKey(enrollment.partnerSigningPubKey)

        var result = PullResult()
        // partnerUid equality is required for the rules engine's query-coverage check: the
        // read rule is an OR over authorUid/partnerUid, and an unconstrained collection query
        // is rejected wholesale (caught on-device 2026-08-31, sync suite run 1). Own events
        // are skipped by senderKeyId below, so the partnerUid leg alone preserves behavior.
        //
        // Quota: the mailbox is pulled after the last-pull watermark (createdAt asc, §7.5),
        // not re-read wholesale — the free tier's 50k reads/day would otherwise be exhausted
        // by one 500-doc pull every 15 minutes.
        var query = firestore.collection(FirestoreSchema.EVENTS_COLLECTION)
            .whereEqualTo("pairId", pairId)
            .whereEqualTo("partnerUid", myUid)
            .orderBy("createdAt")
        enrollment.mailboxWatermarkTsEpochMillis?.let {
            query = query.startAfter(timestampOf(it))
        }
        val snapshot = query.limit(limit.toLong()).get().await()
        var watermark = enrollment.mailboxWatermarkTsEpochMillis
        for (doc in snapshot.documents) {
            doc.getTimestamp("createdAt")?.let { createdAt ->
                watermark = maxOf(watermark ?: 0L, createdAt.toDate().time)
            }
            // The mailbox holds both devices' events; only the partner's are ours to verify.
            if (doc.getString("senderKeyId") == enrollment.mySenderKeyId) continue
            val envelope = doc.getBlob("envelope") ?: continue
            when (val outcome = processor.process(partnerSigningKey, cek, envelope.toBytes())) {
                is MailboxEventProcessor.EnvelopeResult.InvalidSignature -> result = result.copy(invalid = result.invalid + 1)
                is MailboxEventProcessor.EnvelopeResult.Corrupt -> result = result.copy(corrupt = result.corrupt + 1)
                is MailboxEventProcessor.EnvelopeResult.Valid -> {
                    result = when (store.receiveRemote(pairId, outcome.signed)) {
                        is ChainStateStore.AppendResult.Appended -> result.copy(appended = result.appended + 1)
                        is ChainStateStore.AppendResult.Duplicate -> result.copy(duplicates = result.duplicates + 1)
                        is ChainStateStore.AppendResult.Gap -> result.copy(gaps = result.gaps + 1)
                        is ChainStateStore.AppendResult.Fork -> result.copy(forks = result.forks + 1)
                        is ChainStateStore.AppendResult.Rejected -> result.copy(corrupt = result.corrupt + 1)
                    }
                    // §10: react after the loop — shredding destroys the CEK this pull runs on.
                    if (outcome.payloadType == EventPayload.TYPE_DELETION_REQUEST) {
                        result = result.copy(deletionRequested = true)
                    }
                }
            }
        }
        if (!result.deletionRequested && watermark != null &&
            watermark != enrollment.mailboxWatermarkTsEpochMillis
        ) {
            enrollmentDao.updateMailboxWatermark(pairId, watermark)
        }

        // §10: the verified deletion request shreds this device's agreement key + pair data.
        if (result.deletionRequested) onDeletionRequest(pairId)

        // §9 reverse channel: request any still-missing sequences (idempotent doc ids).
        val requests = firestore.collection(FirestoreSchema.BACKFILL_COLLECTION)
        var written = 0
        for (sequence in store.gapSequences(pairId)) {
            requests.document(
                FirestoreSchema.backfillRequestDocumentId(pairId, enrollment.partnerSigningKeyId, sequence),
            ).set(
                FirestoreSchema.backfillRequestData(
                    pairId = pairId,
                    senderKeyId = enrollment.partnerSigningKeyId,
                    sequence = sequence,
                    requesterUid = myUid,
                ),
            ).await()
            written++
        }
        return result.copy(backfillRequestsWritten = written)
    }

    private fun decodePublicKey(der: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))

    /** Watermark is persisted as epoch millis (server timestamps have microsecond precision —
     *  sub-ms ties are not reachable in practice, so the truncation loses nothing). */
    private fun timestampOf(epochMillis: Long): Timestamp =
        Timestamp(epochMillis / 1000, ((epochMillis % 1000) * 1_000_000).toInt())
}
