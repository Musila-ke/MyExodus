package com.example.myexodus.accountability.sync

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.AttemptLog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * §14 partner-sync housekeeping (blocking-core plan §6 + §14): the periodic pass that turns
 * locally-recorded attempts into delivered partner alerts. Runs on the 15-minute WorkManager
 * cadence (plan §7 Decision 7 — partner sync is explicitly a housekeeping job, never inline
 * on an enforcement path).
 *
 * Per run: create chain events for attempts not yet linked (reconcile — heals the §6
 * attempt-first/cross-DB ordering, cold signing window, or a crash between event creation
 * and the link write), drain the outbox, pull the partner's mailbox, answer backfill
 * requests. Steps are independent — a drain failure never skips the pull.
 *
 * Signing is best-effort: the biometric auth window (NFR4, 900s) gates [AccountabilitySigner];
 * when it is cold the events stay unlinked and the next run with a warm window delivers them.
 * No biometric prompt is ever shown from background work.
 */
class PartnerSyncCoordinator(
    private val enrollmentDao: EnrollmentDao,
    private val storeFactory: AppRulesStoreFactory,
    private val creator: EventCreator,
    private val signer: AccountabilitySigner,
    private val resolveUids: suspend (pairId: String) -> Pair<String, String>?,
    private val drainOutbox: suspend (pairId: String, authorUid: String, partnerUid: String) -> FirestoreRelay.DrainResult,
    private val pullMailbox: suspend (pairId: String, myUid: String) -> FirestoreMailboxReceiver.PullResult,
    private val respondToBackfill: suspend (pairId: String, mySenderKeyId: String, authorUid: String, partnerUid: String) -> Int,
) {

    data class SyncReport(
        val enrolled: Boolean = false,
        val uidsResolved: Boolean = false,
        val eventsLinked: Int = 0,
        val drained: Boolean = false,
        val drainedCount: Int = 0,
        val pulledCount: Int = 0,
        val backfilledCount: Int = 0,
    )

    suspend fun syncOnce(): SyncReport {
        val enrollment = enrollmentDao.all().firstOrNull()
            ?: return SyncReport() // Unpaired: attempts stay local (§6 attempt log is the record).
        val uids = resolveUids(enrollment.pairId) ?: return SyncReport(enrolled = true)
        val (myUid, partnerUid) = uids

        val linked = reconcileEvents(enrollment.pairId)

        var drained = false
        var drainedCount = 0
        when (val outcome = drainOutbox(enrollment.pairId, myUid, partnerUid)) {
            is FirestoreRelay.DrainResult.Sent -> {
                drained = true
                drainedCount = outcome.eventsDelivered
                Log.i(TAG, "drain: ${outcome.eventsDelivered} event(s) delivered")
            }
            is FirestoreRelay.DrainResult.PermissionDenied -> Log.w(TAG, "drain: PERMISSION_DENIED — not retried (§9)")
            is FirestoreRelay.DrainResult.Failed -> Log.w(TAG, "drain failed: ${outcome.reason} (retried next run)")
        }

        val pulled = runCatching { pullMailbox(enrollment.pairId, myUid) }
            .onFailure { Log.w(TAG, "mailbox pull failed", it) }
            .getOrNull()
        if (pulled != null && pulled.isNotEnrolled) Log.w(TAG, "mailbox pull: not enrolled (pair state gone?)")

        val backfilled = runCatching {
            respondToBackfill(enrollment.pairId, enrollment.mySenderKeyId, myUid, partnerUid)
        }.onFailure { Log.w(TAG, "backfill response failed", it) }.getOrDefault(0)

        return SyncReport(
            enrolled = true,
            uidsResolved = true,
            eventsLinked = linked,
            drained = drained,
            drainedCount = drainedCount,
            pulledCount = pulled?.appended ?: 0,
            backfilledCount = backfilled,
        )
    }

    /** Attempts with no linked chain event get one now — but only while the auth window is
     *  warm; a cold window stops the pass (every remaining attempt would fail the same way). */
    private suspend fun reconcileEvents(pairId: String): Int {
        val store = storeFactory.open() ?: return 0
        val dao = store.attemptLogDao()
        var linked = 0
        for (attempt in dao.pendingEventCreation(RECONCILE_BATCH)) {
            val signature = try {
                signer.beginSign()
            } catch (e: Exception) {
                if (signer.isUserNotAuthenticated(e)) break
                Log.w(TAG, "signing init failed", e)
                continue
            }
            try {
                when (val result = creator.create(pairId, EventPayload(type = EventPayload.TYPE_ACTIVITY, text = eventText(attempt)), signature)) {
                    is EventCreator.CreateResult.Created -> {
                        dao.linkEvent(attempt.id, result.sequence)
                        linked++
                    }
                    is EventCreator.CreateResult.Duplicate -> Log.w(TAG, "event duplicate for attempt ${attempt.id} — left unlinked")
                    is EventCreator.CreateResult.Failed -> Log.w(TAG, "event creation failed for attempt ${attempt.id}: ${result.reason}")
                }
            } catch (e: UserNotAuthenticatedException) {
                break
            }
        }
        return linked
    }

    private fun eventText(attempt: AttemptLog): String = when (attempt.outcome) {
        AttemptLog.OUTCOME_OVERRIDE -> "override:${attempt.blockedTarget}"
        else -> "blocked:${attempt.blockedTarget}"
    }

    private companion object {
        const val TAG = "MyExodusPartnerSync"
        const val RECONCILE_BATCH = 100
    }
}

/**
 * Resolves (myUid, partnerUid) for a pair: anonymous sign-in if needed, then the pair
 * document's `members` map (the rules gate the read to members; the doc is the only local
 * source of the partner's uid — it is deliberately never persisted on-device).
 */
class PairUidResolver(
    private val appContext: Context,
    private val firestore: FirebaseFirestore,
) {
    suspend fun resolve(pairId: String): Pair<String, String>? {
        AppCheckInstaller.install(appContext)
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
            ?: runCatching { auth.signInAnonymously().await() }.getOrNull()?.user
            ?: return null
        val members = runCatching {
            firestore.collection(FirestoreSchema.PAIRS_COLLECTION).document(pairId).get()
                .await()
                .get("members")
        }.getOrNull() ?: return null
        val partner = (members as? Map<*, *>)?.keys
            ?.mapNotNull { it as? String }
            ?.firstOrNull { it != user.uid } ?: return null
        return user.uid to partner
    }
}
