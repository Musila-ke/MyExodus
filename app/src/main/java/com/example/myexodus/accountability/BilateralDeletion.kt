package com.example.myexodus.accountability

import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.ChainStateStore
import android.util.Log
import com.example.myexodus.accountability.sync.FirestoreRelay
import java.security.Signature

/**
 * §10 bilateral crypto-shredding. Local ECDH destruction alone is insufficient: a
 * `DELETION_REQUEST` control event (FR6) is signed, encrypted, chained, and transmitted
 * first; only then is this device's Key Agreement key destroyed. The partner's app must
 * likewise destroy its agreement key upon receipt — which this device does for incoming
 * requests via [handleIncoming]. Destruction is device-level (one agreement key serves all
 * pairs), so shredding one pair renders every pair's ciphertext unrecoverable — per §10.
 *
 * §10 step 4: revocation also deletes the relay's pair state document
 * ([deletePairState]) — the long-term pairing credential is invalidated server-side, and
 * the mailbox rules (which gate on the pair document's existence) reject any further
 * writes for that pair. An append-only tombstone ([writeDeletionTombstone]) is written
 * first so the pair document can never be re-created after revocation (rules gate create
 * on the tombstone's absence).
 *
 * [drainOutbox], [destroyAgreementKey], [deletePairState], and [writeDeletionTombstone]
 * are seams behind the Firestore/Keystore boundary so the destroy-then-wipe order is
 * unit-testable with fakes (same pattern as the rotate lambda in IdentityMigration).
 */
class BilateralDeletion(
    private val creator: EventCreator,
    private val drainOutbox: suspend (pairId: String, authorUid: String, partnerUid: String) -> FirestoreRelay.DrainResult,
    private val enrollmentDao: EnrollmentDao,
    private val store: ChainStateStore,
    private val destroyAgreementKey: () -> Unit,
    private val deletePairState: suspend (String) -> Unit,
    private val writeDeletionTombstone: suspend (String) -> Unit,
) {

    sealed interface DeletionResult {
        /** Request transmitted, local ECDH key destroyed, pair data wiped. */
        data object Deleted : DeletionResult

        /** Event chained but the relay could not transmit — retry or fall back to QR. */
        data object TransmissionPending : DeletionResult

        data class Failed(val reason: String) : DeletionResult
    }

    /**
     * Initiator half: chain + transmit the request, then shred locally. A failed
     * transmission leaves the event in the outbox (retryable via relay or QR) — local
     * destruction without transmission would break the bilateral contract (§10).
     *
     * Drains until the outbox is empty, not just until the request itself is sent:
     * the agreement key dies with everything encrypted under it, so shredding while
     * older events still sit in the outbox would strand them forever. The loop is
     * bounded — Req 27 caps the chain at 1000 events, so 40 batches (50 each) is
     * 2x headroom even with concurrent attempt writes.
     */
    suspend fun initiate(
        pairId: String,
        authorUid: String,
        partnerUid: String,
        signature: Signature,
    ): DeletionResult {
        val created = creator.create(
            pairId,
            EventPayload(type = EventPayload.TYPE_DELETION_REQUEST, text = ""),
            signature,
        )
        // Duplicate = an identical deletion request is already chained — proceed to transmit it.
        if (created is EventCreator.CreateResult.Failed) {
            return DeletionResult.Failed(created.reason)
        }
        var transmitted = 0L
        for (batch in 0 until MAX_DRAIN_BATCHES) {
            when (val drained = drainOutbox(pairId, authorUid, partnerUid)) {
                is FirestoreRelay.DrainResult.Sent -> {
                    transmitted += drained.eventsDelivered
                    if (drained.eventsDelivered == 0) break
                }

                // §9: authorization failure is not retryable — surface it, shred anyway? No:
                // the event never reached the mailbox, so the partner never gets it.
                is FirestoreRelay.DrainResult.PermissionDenied ->
                    return DeletionResult.Failed("rules rejected the deletion event")

                is FirestoreRelay.DrainResult.Failed -> return DeletionResult.TransmissionPending
            }
        }
        return if (transmitted > 0) {
            destroyAgreementKey()
            wipePair(pairId)
            // Tombstone BEFORE deletePairState: the rules gate the tombstone's create on the
            // pair doc existing, and the tombstone gates any future re-creation of the pair
            // doc (revocation-reopen gap, 2026-08-31 review fix). Best-effort: a tombstone
            // failure must not block the deletion itself — the contract is already honored
            // (request transmitted, key destroyed); the gap is only exploitable by a former
            // member deliberately re-creating the doc, which the app never does.
            runCatching { writeDeletionTombstone(pairId) }
                .onFailure { Log.w(TAG, "Deletion tombstone write failed; pair doc still deletable", it) }
            deletePairState(pairId) // §10 step 4: server-side credential invalidation
            DeletionResult.Deleted
        } else {
            // Sent(0) on the first drain: nothing was pending — the request must have been
            // delivered by an earlier session that crashed between transmit and shred.
            DeletionResult.Failed("outbox still has events after $MAX_DRAIN_BATCHES batches")
        }
    }

    /**
     * Receiver half: an incoming `DELETION_REQUEST` was verified + appended. Destroy this
     * device's agreement key and wipe the pair — the request's own row was encrypted under
     * the CEK, so it dies with the key like everything else.
     */
    suspend fun handleIncoming(pairId: String) {
        destroyAgreementKey()
        wipePair(pairId)
        // Same tombstone-before-delete order as initiate(); see there for the rationale.
        runCatching { writeDeletionTombstone(pairId) }
            .onFailure { Log.w(TAG, "Deletion tombstone write failed; pair doc still deletable", it) }
        deletePairState(pairId) // §10 step 4: server-side credential invalidation
    }

    private suspend fun wipePair(pairId: String) {
        store.deletePairData(pairId)
        enrollmentDao.getByPairId(pairId)?.let { enrollmentDao.delete(it) }
    }

    companion object {
        const val TAG = "MyExodusDeletion"

        /** Drain cap: 40 batches x 50 events covers 2x the Req 27 chain cap of 1000. */
        const val MAX_DRAIN_BATCHES = 40
    }
}
