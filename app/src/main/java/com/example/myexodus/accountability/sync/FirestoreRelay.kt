package com.example.myexodus.accountability.sync

import com.example.myexodus.accountability.state.ChainEvent
import com.example.myexodus.accountability.state.ChainEventDao
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * §8.4/§9 Firestore mailbox relay: drains the transactional outbox (PENDING events, oldest
 * first) into `accountability_events/{eventId}` and marks each SENT on write. A
 * PERMISSION_DENIED stops the drain immediately and is never retried (§9) — it means the
 * security rules rejected this device for this pair, which re-queuing cannot fix.
 */
class FirestoreRelay(
    private val appContext: android.content.Context,
    private val firestore: FirebaseFirestore,
    private val eventDao: ChainEventDao,
    private val batchSize: Int = 50,
) {

    sealed interface DrainResult {
        /** Every queued event was delivered and marked SENT. */
        data class Sent(val eventsDelivered: Int) : DrainResult

        /** §9: rules rejected the write — authorization failure, do not retry. */
        data object PermissionDenied : DrainResult

        data class Failed(val reason: String) : DrainResult
    }

    suspend fun drainOutbox(pairId: String, authorUid: String, partnerUid: String): DrainResult {
        AppCheckInstaller.install(appContext) // §8.5: provider in place before any Firestore operation
        val events = eventDao.pendingSentEvents(pairId, batchSize)
        if (events.isEmpty()) return DrainResult.Sent(0)
        val mailbox = firestore.collection(FirestoreSchema.EVENTS_COLLECTION)
        for (event in events) {
            val docId = FirestoreSchema.eventDocumentId(event.pairId, event.senderKeyId, event.sequence)
            val write = mailbox.document(docId).set(FirestoreSchema.eventDocumentData(event, authorUid, partnerUid))
            try {
                write.await()
            } catch (e: FirebaseFirestoreException) {
                return classifyOutcome(e)
            }
            eventDao.update(event.copy(syncState = ChainEvent.SYNC_STATE_SENT))
        }
        return DrainResult.Sent(events.size)
    }

    /**
     * §9 reverse channel, sender side: fulfills backfill requests for this device's own chain
     * (re-publishing is idempotent — deterministic doc ids) and removes the requests. Requests
     * for the partner's chain are not ours to answer.
     */
    suspend fun respondToBackfillRequests(
        pairId: String,
        mySenderKeyId: String,
        authorUid: String,
        partnerUid: String,
    ): Int {
        val requests = firestore.collection(FirestoreSchema.BACKFILL_COLLECTION)
            .whereEqualTo("pairId", pairId)
            .get()
            .await()
        var fulfilled = 0
        val mailbox = firestore.collection(FirestoreSchema.EVENTS_COLLECTION)
        for (doc in requests.documents) {
            val senderKeyId = doc.getString("senderKeyId")
            val sequence = doc.getLong("sequence") ?: continue
            if (senderKeyId == null || senderKeyId != mySenderKeyId) continue
            val local = eventDao.getBySequence(pairId, mySenderKeyId, sequence)
            if (local != null) {
                mailbox.document(FirestoreSchema.eventDocumentId(pairId, mySenderKeyId, sequence))
                    .set(FirestoreSchema.eventDocumentData(local, authorUid, partnerUid))
                    .await()
                fulfilled++
            }
            doc.reference.delete().await()
        }
        return fulfilled
    }

    companion object {
        /** §9 contract: PERMISSION_DENIED is never retried — the rules rejected this write. */
        internal fun classifyOutcome(e: FirebaseFirestoreException): DrainResult =
            when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> DrainResult.PermissionDenied
                else -> DrainResult.Failed(e.message ?: "firestore write failed")
            }
    }
}
