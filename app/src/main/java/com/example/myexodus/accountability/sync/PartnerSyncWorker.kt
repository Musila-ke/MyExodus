package com.example.myexodus.accountability.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** §14 housekeeping (plan §7 Decision 7): 15-minute partner sync — attempt → event reconcile,
 *  outbox drain, mailbox pull, backfill response. Delivery latency is bounded by this cadence
 *  (at-least-once; the attempt log is the local source of truth meanwhile). Doubles as the
 *  push-registration refresh point (plan §8 Step 7 / AD-18c): every run rewrites this
 *  device's push installation, keeping `updatedAt` (and therefore staleness) current. */
@HiltWorker
class PartnerSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: PartnerSyncCoordinator,
    private val registrar: PushInstallationRegistrar,
    private val rollout: PartnerRolloutGate,
    private val telemetry: PartnerSyncTelemetry,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val report = runPartnerSyncPass(
            registrar,
            rollout,
            coordinator,
            wakeMessageId = inputData.getString(PARTNER_WAKE_MESSAGE_ID),
            forceHeartbeat = inputData.getBoolean(PARTNER_FORCE_HEARTBEAT, false),
        )
        Log.i(TAG, "sync: $report")
        runCatching { telemetry.afterPass(report) }
            .onFailure { Log.w(TAG, "telemetry report failed", it) }
        return Result.success()
    }

    private companion object {
        const val TAG = "MyExodusPartnerSync"
    }
}

/**
 * One full sync pass — the worker body, shared with the dashboard's "Check for updates"
 * (Step 12) so the manual path runs the exact production pass in-process rather than
 * depending on WorkManager dispatch, which cannot be relied on for latency on OEM builds
 * (XOS dispatch was observed working 2026-10-01, but that is not a guarantee — ADR #27 as
 * amended). A manual run racing the periodic one is safe: every write on this path is
 * transactional or dedup-guarded.
 */
suspend fun runPartnerSyncPass(
    registrar: PushInstallationRegistrar,
    rollout: PartnerRolloutGate,
    coordinator: PartnerSyncCoordinator,
    wakeMessageId: String? = null,
    forceHeartbeat: Boolean = false,
): PartnerSyncCoordinator.SyncReport {
    if (rollout.pushRegistrationEnabled()) {
        if (!registrar.register()) Log.w("MyExodusPartnerSync", "push installation not (re)registered this run")
    }
    val report = coordinator.syncOnce(wakeMessageId, forceHeartbeat)
    // Unpair progress (peer shred pulled, journal row advanced, ack confirmed) repaints too:
    // deletion passes report pulledCount=0 by design (the wake path returns before the
    // watermark query), yet they are exactly the passes the Partner page must react to
    // (owner-observed stale page after a partnership ended, 2026-10-01).
    if (report.pulledCount > 0 || report.unpairAdvanced) PartnerSyncSignals.pulledEvents.tryEmit(Unit)
    // ADR #216: a cold signing window is the one pass "failure" the user alone can fix, and
    // only from the foreground — surface it so the Activity can prompt and re-run.
    if (report.signingBlocked) PartnerSyncSignals.signingNeeded.tryEmit(Unit)
    return report
}

/** §14 housekeeping enqueue (MyExodusApp, post-unlock process start). UPDATE, not KEEP: the
 *  Step 7 heartbeat fallback adds a connected-network constraint and update-period jitter, and
 *  KEEP would leave devices already holding the old unconstrained request on the old shape
 *  forever. The work name and cadence are unchanged, so this is a specification refresh, not
 *  a second schedule. */
fun enqueuePartnerSync(context: Context) {
    val request = PeriodicWorkRequestBuilder<PartnerSyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PARTNER_SYNC_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
    )
}

/** internal (not private): the instrumented execution-leg test re-enqueues this same work. */
internal const val PARTNER_SYNC_WORK_NAME = "partner-sync"

/** The wake input key — shared with [PartnerMessagingService] so the producer and consumer of
 *  the wake payload can never drift apart. */
internal const val PARTNER_WAKE_MESSAGE_ID = "messageId"

/** Set only by a liveness-probe push: the server has not accepted a beat for 45 minutes, so
 *  bypass the local 15-minute floor and answer even if this device sent a failed beat
 *  recently. */
internal const val PARTNER_FORCE_HEARTBEAT = "forceHeartbeat"
