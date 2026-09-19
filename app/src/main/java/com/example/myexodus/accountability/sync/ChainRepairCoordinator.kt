package com.example.myexodus.accountability.sync

import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.accountability.state.ChainStateStore

/**
 * Plan §8 Step 10 / AD-08 (+ AD-08b), Step 11 — the staged chain-repair protocol.
 *
 * Requester side ([repairPass], run inside the sync pass): heads whose gap is already marked
 * `REPAIR_PENDING` get a `SyncRequest(expectedPreviousSequence, expectedPreviousHash)` — an
 * ordinary signed chain event in *this* device's chain, so it travels the existing
 * outbox → mailbox → verify pipeline with no parallel transport. One request per 24h
 * ([REQUEST_RETRY_MILLIS]) until the 30-day window ([RECOVERY_WINDOW_MILLIS]) lapses, then
 * one stage per pass: `REPAIR_PENDING → RECOVERY_EXHAUSTED → CHAIN_INCOMPLETE`. The middle
 * stage is observably persisted and keeps accepting a late replay, which is what "no
 * successful recovery" is checked against; the terminal stage is neutral, never
 * TAMPER_SUSPECTED (§9 row 457).
 *
 * Responder side ([respondToRequest]): the anchor event named by the request must hash-match
 * the stored event at that exact sequence before anything is replayed (AD-08b — proof of
 * connection, not sequence arithmetic). On mismatch nothing is sent: the two devices hold
 * different histories, which no replay can honestly fix.
 *
 * Signing is best-effort exactly like [PartnerSyncCoordinator]'s reconcile: the warm
 * biometric window (NFR4) gates request creation; a cold window skips the request and the
 * next pass retries. No biometric prompt is ever shown from background work.
 */
class ChainRepairCoordinator(
    private val enrollmentDao: EnrollmentDao,
    private val store: ChainStateStore,
    private val creator: EventCreator,
    private val signer: AccountabilitySigner,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Returns how many SyncRequests were created this pass. Escalations advance one stage. */
    suspend fun repairPass(pairId: String): Int {
        val nowMs = now()

        for (head in store.repairHeads(pairId)) {
            when (head.repairState) {
                ChainHead.REPAIR_PENDING ->
                    if (windowLapsed(head, nowMs) && store.gapSequences(pairId, head.senderKeyId).isNotEmpty()) {
                        Log.w(TAG, "repair window lapsed (30d) for pair=$pairId sender=${head.senderKeyId} — RECOVERY_EXHAUSTED")
                        store.markRecoveryExhausted(pairId, head.senderKeyId, nowMs)
                    }

                ChainHead.REPAIR_RECOVERY_EXHAUSTED -> {
                    Log.w(TAG, "repair unrecoverable for pair=$pairId sender=${head.senderKeyId} — CHAIN_INCOMPLETE")
                    store.markChainIncomplete(pairId, head.senderKeyId)
                }
            }
        }

        var requested = 0
        for (head in store.repairHeads(pairId)) {
            if (head.repairState != ChainHead.REPAIR_PENDING || !requestDue(head, nowMs)) continue
            if (store.gapSequences(pairId, head.senderKeyId).isEmpty()) continue
            if (!sendRequest(pairId, head)) break // cold window: every later request would fail alike
            requested++
        }
        return requested
    }

    /** AD-08b responder: replays this device's own chain after the requested anchor, or
     *  nothing when the anchor does not connect. Returns the number of events replayed. */
    suspend fun respondToRequest(
        pairId: String,
        expectedPreviousSequence: Long,
        expectedPreviousHash: ByteArray,
    ): Int {
        val mySenderKeyId = enrollmentDao.getByPairId(pairId)?.mySenderKeyId ?: return 0
        val anchor = store.storedEvent(pairId, mySenderKeyId, expectedPreviousSequence)
        if (anchor == null || !anchor.eventHash.contentEquals(expectedPreviousHash)) {
            Log.w(
                TAG,
                "sync request anchor does not connect (pair=$pairId seq=$expectedPreviousSequence) — no replay, histories diverged",
            )
            return 0
        }
        return store.enqueueReplay(pairId, mySenderKeyId, afterSequence = expectedPreviousSequence)
    }

    private suspend fun sendRequest(pairId: String, head: ChainHead): Boolean {
        val signature = try {
            signer.beginSign()
        } catch (e: Exception) {
            if (signer.isUserNotAuthenticated(e)) Log.i(TAG, "sync request skipped: signing window cold")
            else Log.w(TAG, "signing init failed", e)
            return false
        }
        val payload = EventPayload(
            type = EventPayload.TYPE_SYNC_REQUEST,
            text = "sync_request",
            expectedPreviousSequence = head.lastSequence,
            expectedPreviousHash = head.lastEventHash,
        )
        return try {
            when (val result = creator.create(pairId, payload, signature)) {
                is EventCreator.CreateResult.Created -> {
                    store.markRepairRequested(pairId, head.senderKeyId, now())
                    true
                }
                is EventCreator.CreateResult.Duplicate -> {
                    // Practically unreachable (fresh nonce per envelope) — retry next pass.
                    Log.w(TAG, "sync request reported duplicate (pair=$pairId) — retried next pass")
                    false
                }
                is EventCreator.CreateResult.Failed -> {
                    Log.w(TAG, "sync request creation failed (pair=$pairId): ${result.reason}")
                    false
                }
            }
        } catch (e: UserNotAuthenticatedException) {
            Log.i(TAG, "sync request failed: signing window went cold")
            false
        }
    }

    private fun windowLapsed(head: ChainHead, nowMs: Long): Boolean =
        (head.repairFirstRequestedAtEpochMillis ?: nowMs).let { nowMs - it >= RECOVERY_WINDOW_MILLIS }

    private fun requestDue(head: ChainHead, nowMs: Long): Boolean =
        head.repairLastRequestedAtEpochMillis?.let { nowMs - it >= REQUEST_RETRY_MILLIS } ?: true

    companion object {
        private const val TAG = "MyExodusChainRepair"

        /** AD-08: repeated unanswered requests — one per day keeps the ask visible without noise. */
        const val REQUEST_RETRY_MILLIS = 24L * 60 * 60 * 1000

        /** AD-08: the 30-day recovery window before history is declared gone. */
        const val RECOVERY_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
