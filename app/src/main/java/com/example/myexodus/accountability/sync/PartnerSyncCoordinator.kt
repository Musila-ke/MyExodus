package com.example.myexodus.accountability.sync

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.enrollment.EnrollmentDao
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.recovery.RecoveryMirror
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
 * and the link write), advance the Step 11 chain-repair protocol (escalations + due
 * SyncRequests, before the drain so a fresh request ships this pass — the responder side
 * runs inline in the pull's handler), drain the outbox into the partner's mailbox, pull
 * this device's own mailbox. Steps are independent — a drain failure never skips the pull.
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
    private val drainOutbox: suspend (pairId: String, authorUid: String, partnerUid: String) -> MailboxOutboxDrainer.DrainResult,
    private val pullMailbox: suspend (pairId: String, myUid: String, messageId: String?) -> FirestoreMailboxReceiver.PullResult,
    private val repairPass: suspend (pairId: String) -> Int,
    /** Step 13: the 72h approval sweep — run before the pull so an expired request the pull
     *  is about to answer can never be decided past its deadline by the responder UI. */
    private val expireApprovals: suspend (pairId: String) -> Int = { 0 },
    /** Step 13 unpair: continue in-flight unpairs (re-delivery, owed ack, ack poll, timeout).
     *  Runs BEFORE the enrollment early-return — a shredded device has no enrollment, and its
     *  unfinished unpair is exactly what still needs this pass. */
    private val resumeUnpairs: suspend () -> Unit = {},
    /** Step 15 (AD-12): the rotation sweep. [EventCreator] also checks before every envelope,
     *  so this covers the idle pair — a 30-day-old epoch with no new traffic still rolls. */
    private val rotateKeys: suspend (pairId: String, mySenderKeyId: String) -> Int? = { _, _ -> null },
    /** Step 18 (AD-23): read back the relay's rejection marker for sent rows. Runs BEFORE the
     *  drain so this pass's own writes are never checked against a relay that has not run. */
    private val checkRejections: suspend (pairId: String, partnerUid: String) -> Int = { _, _ -> 0 },
) {

    data class SyncReport(
        val enrolled: Boolean = false,
        val uidsResolved: Boolean = false,
        val eventsLinked: Int = 0,
        val drained: Boolean = false,
        val drainedCount: Int = 0,
        val pulledCount: Int = 0,
        val repairRequestsCreated: Int = 0,
        val approvalsExpired: Int = 0,
        /** Non-null when this pass rotated the pair's session key (Step 15). */
        val rotatedKeyEpoch: Int? = null,
        /** Sent messages this pass read back as rejected by the relay (Step 18; any marker —
         *  `RATE_LIMITED` is the one the §9 error table names). */
        val rejectionsFound: Int = 0,
    )

    /** [wakeMessageId] carries the FCM wake's own message id (null on periodic/deleted-messages
     *  runs): the pull handles it first so an out-of-order arrival cannot be missed by the
     *  watermark query (plan §6 step 3's expedited path).
     *
     *  §19.5 multi-pair: one device can hold several enrollments (a partner supporting several
     *  users), and every one of them syncs — the single-enrollment `firstOrNull()` this loop
     *  replaced was OPD #15's "second pairing silently half-works" gap. Per-pair steps are
     *  independent; one pair's failure never skips another's. The report aggregates the pass
     *  (counters summed, [SyncReport.uidsResolved] true only when every pair resolved). */
    suspend fun syncOnce(wakeMessageId: String? = null): SyncReport {
        runCatching { resumeUnpairs() }.onFailure { Log.w(TAG, "unpair resume failed", it) }
        val enrollments = enrollmentDao.all()
        if (enrollments.isEmpty()) {
            return SyncReport() // Unpaired: attempts stay local (§6 attempt log is the record).
        }
        var report = SyncReport(enrolled = true)
        var resolved = 0
        for (enrollment in enrollments) {
            val one = syncPair(enrollment, wakeMessageId) ?: continue
            resolved++
            report = report.copy(
                eventsLinked = report.eventsLinked + one.eventsLinked,
                drained = report.drained || one.drained,
                drainedCount = report.drainedCount + one.drainedCount,
                pulledCount = report.pulledCount + one.pulledCount,
                repairRequestsCreated = report.repairRequestsCreated + one.repairRequestsCreated,
                approvalsExpired = report.approvalsExpired + one.approvalsExpired,
                rotatedKeyEpoch = one.rotatedKeyEpoch ?: report.rotatedKeyEpoch,
                rejectionsFound = report.rejectionsFound + one.rejectionsFound,
            )
        }
        return report.copy(uidsResolved = resolved == enrollments.size)
    }

    /** One pair's slice of the pass; null when its uids could not be resolved (the pair is
     *  skipped, every other pair still runs). */
    private suspend fun syncPair(enrollment: EnrollmentRecord, wakeMessageId: String?): SyncReport? {
        val uids = resolveUids(enrollment.pairId) ?: return null
        val (myUid, partnerUid) = uids

        val rotated = runCatching { rotateKeys(enrollment.pairId, enrollment.mySenderKeyId) }
            .onFailure { Log.w(TAG, "session key rotation failed", it) }
            .getOrNull()
        if (rotated != null) Log.i(TAG, "session key rotated to epoch $rotated")

        val linked = reconcileEvents(enrollment.pairId)

        // Step 11 before the drain: a request created now is delivered by this same pass.
        val repairs = runCatching { repairPass(enrollment.pairId) }
            .onFailure { Log.w(TAG, "repair pass failed", it) }
            .getOrDefault(0)

        val expiredApprovals = runCatching { expireApprovals(enrollment.pairId) }
            .onFailure { Log.w(TAG, "approval expiry sweep failed", it) }
            .getOrDefault(0)

        // Before the drain: rows sent by earlier passes are settled by the relay by now, and
        // this pass's own writes are not yet SENT, so they are never read back prematurely.
        val rejected = runCatching { checkRejections(enrollment.pairId, partnerUid) }
            .onFailure { Log.w(TAG, "rejection check failed", it) }
            .getOrDefault(0)

        var drained = false
        var drainedCount = 0
        when (val outcome = drainOutbox(enrollment.pairId, myUid, partnerUid)) {
            is MailboxOutboxDrainer.DrainResult.Sent -> {
                drained = true
                drainedCount = outcome.eventsDelivered
                Log.i(TAG, "drain: ${outcome.eventsDelivered} event(s) delivered")
            }
            is MailboxOutboxDrainer.DrainResult.PermissionDenied -> Log.w(TAG, "drain: PERMISSION_DENIED — rows left pending, retried next run")
            is MailboxOutboxDrainer.DrainResult.Failed -> Log.w(TAG, "drain failed: ${outcome.reason} (retried next run)")
        }

        val pulled = runCatching { pullMailbox(enrollment.pairId, myUid, wakeMessageId) }
            .onFailure { Log.w(TAG, "mailbox pull failed", it) }
            .getOrNull()
        if (pulled != null && pulled.isNotEnrolled) Log.w(TAG, "mailbox pull: not enrolled (pair state gone?)")

        return SyncReport(
            enrolled = true,
            uidsResolved = true,
            eventsLinked = linked,
            drained = drained,
            drainedCount = drainedCount,
            pulledCount = pulled?.appended ?: 0,
            repairRequestsCreated = repairs,
            approvalsExpired = expiredApprovals,
            rotatedKeyEpoch = rotated,
            rejectionsFound = rejected,
        )
    }

    /** Unlinked rows get a chain event now — but only while the auth window is warm; a cold
     *  window stops the pass (every remaining row would fail the same way). Two queues share the
     *  pass: block/override attempts (existing) and recovery sessions (W5), each linked in its
     *  own table. */
    private suspend fun reconcileEvents(pairId: String): Int {
        val store = storeFactory.open() ?: return 0
        val attempts = store.attemptLogDao()
        var linked = 0
        for (attempt in attempts.pendingEventCreation(RECONCILE_BATCH)) {
            when (val emit = emitActivity(pairId, "attempt ${attempt.id}", eventText(attempt))) {
                is Emit.Created -> { attempts.linkEvent(attempt.id, emit.sequence); linked++ }
                Emit.Skip -> Unit
                Emit.Stop -> return linked
            }
        }
        val sessions = store.recoverySessionDao()
        for (session in sessions.pendingMirror(RECONCILE_BATCH)) {
            when (val emit = emitActivity(pairId, "session ${session.id}", RecoveryMirror.text(session.technique, session.state))) {
                is Emit.Created -> { sessions.linkEvent(session.id, emit.sequence); linked++ }
                Emit.Skip -> Unit
                Emit.Stop -> return linked
            }
        }
        return linked
    }

    /** Signs and creates one `TYPE_ACTIVITY` event. [Stop] is the cold-auth-window signal the
     *  caller ends the pass on; the row stays unlinked for the next run either way. */
    private suspend fun emitActivity(pairId: String, label: String, text: String): Emit {
        val signature = try {
            signer.beginSign()
        } catch (e: Exception) {
            if (signer.isUserNotAuthenticated(e)) return Emit.Stop
            Log.w(TAG, "signing init failed", e)
            return Emit.Skip
        }
        return try {
            when (val result = creator.create(pairId, EventPayload(type = EventPayload.TYPE_ACTIVITY, text = text), signature)) {
                is EventCreator.CreateResult.Created -> Emit.Created(result.sequence)
                is EventCreator.CreateResult.Duplicate -> {
                    Log.w(TAG, "event duplicate for $label — left unlinked")
                    Emit.Skip
                }
                is EventCreator.CreateResult.Failed -> {
                    Log.w(TAG, "event creation failed for $label: ${result.reason}")
                    Emit.Skip
                }
            }
        } catch (e: UserNotAuthenticatedException) {
            Emit.Stop
        }
    }

    private sealed interface Emit {
        data class Created(val sequence: Long) : Emit
        data object Skip : Emit
        data object Stop : Emit
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
 * Resolves (myUid, partnerUid) for a pair: anonymous sign-in if needed, then the v2
 * `/pairings/{pairId}` document's `members` **array** (Step 1 rules shape — an array, unlike
 * the retired v1 `pairs` map). The rules gate the read to members; the doc is the only local
 * source of the partner's uid — it is deliberately never persisted on-device.
 */
class PairUidResolver(
    private val appContext: Context,
    private val firestore: FirebaseFirestore,
) {
    /** The local device's uid — anonymous sign-in if needed. [resolve]'s first half, on its
     *  own for the pairing ceremony, which needs a uid before any pair id exists. */
    suspend fun ensureUid(): String? {
        AppCheckInstaller.install(appContext)
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
            ?: runCatching { auth.signInAnonymously().await() }
                .onFailure { Log.w(TAG, "anonymous sign-in failed", it) }
                .getOrNull()
                ?.user
        if (user == null) Log.w(TAG, "no uid — sign-in unavailable")
        return user?.uid
    }

    suspend fun resolve(pairId: String): Pair<String, String>? {
        val uid = ensureUid() ?: return null
        val members = runCatching {
            FirestoreSchema.pairingDocument(firestore, pairId).get().await().get("members")
        }
            .onFailure { Log.w(TAG, "pair $pairId: pairing document read failed", it) }
            .getOrNull()
        if (members == null) {
            Log.w(TAG, "pair $pairId: pairing document missing or has no members field")
            return null
        }
        val partner = (members as? List<*>)?.filterIsInstance<String>()?.firstOrNull { it != uid }
        if (partner == null) {
            Log.w(TAG, "pair $pairId: no other member (members=${(members as? List<*>)?.size ?: "not a list"})")
            return null
        }
        return uid to partner
    }

    private companion object {
        const val TAG = "MyExodusPairUidResolver"
    }
}
