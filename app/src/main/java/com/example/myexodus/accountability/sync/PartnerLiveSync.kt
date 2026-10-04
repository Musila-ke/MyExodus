package com.example.myexodus.accountability.sync

import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Foreground doorbell (owner mandate 2026-10-04: Firestore is already there, so updates must
 * feel realtime instead of waiting out the 15-minute period). While the app is on screen, a
 * snapshot listener watches this uid's own mailbox — newest message only — and rings whenever
 * the newest relayed message is newer than the highest watermark any pass has processed. The
 * collector then runs one ordinary in-process sync pass, so the pull stays the single ingress
 * path (verification, watermarks, tamper counters untouched); this class only decides *when*
 * to pull, never how.
 */
class PartnerLiveSync(
    private val firestore: FirebaseFirestore,
    private val enrollmentDao: EnrollmentDao,
    private val rollout: PartnerRolloutGate,
    private val ensureUid: suspend () -> String?,
) {

    /** This uid if listening makes sense at all: kill switch on (default on unreadable —
     *  same posture as push registration), at least one live pairing, and an anonymous uid
     *  actually resolvable. Null silences the listener entirely. */
    suspend fun myUidIfLive(): String? {
        if (!rollout.pushRegistrationEnabled()) return null
        val pairs = runCatching { enrollmentDao.all() }.getOrDefault(emptyList())
        if (pairs.isEmpty()) return null
        return runCatching { ensureUid() }.getOrNull()
    }

    /**
     * Rings (emits) when the mailbox's newest message is beyond everything processed. One
     * mailbox backs every pair, and a pass advances its watermark to the newest document it
     * saw, so the highest watermark across enrollments is the effective "last processed" mark.
     * A cleanup deletion or the server-timestamp refinement of an already-handled write does
     * not ring — only genuinely-new traffic does. Debounced because one relay write arrives as
     * create-then-timestamp events in quick succession.
     */
    fun changes(myUid: String): Flow<Unit> = callbackFlow {
        val registration = FirestoreSchema.mailboxCollection(firestore, myUid)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                // Cache hits just replay what the last pull already saw; wait for the server.
                if (snapshot.metadata.isFromCache || snapshot.isEmpty) return@addSnapshotListener
                val newest = snapshot.documents.first()
                    .getTimestamp("timestamp")?.toDate()?.time ?: return@addSnapshotListener
                launch {
                    val pairs = runCatching { enrollmentDao.all() }.getOrNull()
                        ?: return@launch
                    if (pairs.isEmpty()) return@launch
                    // Missing watermark (fresh pairing, never pulled) counts as 0 — the first
                    // relayed message must ring, not be mistaken for old traffic.
                    val highest = pairs.mapNotNull { it.mailboxWatermarkTsEpochMillis }
                        .maxOrNull() ?: 0L
                    if (newest > highest) trySend(Unit)
                }
            }
        awaitClose { registration.remove() }
    }.debounce(DEBOUNCE_MILLIS)

    private companion object {
        const val DEBOUNCE_MILLIS = 1_500L
    }
}
