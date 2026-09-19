package com.example.myexodus.accountability.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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
        val report = runPartnerSyncPass(registrar, rollout, coordinator, inputData.getString(PARTNER_WAKE_MESSAGE_ID))
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
 * depending on WorkManager dispatch (which XOS never performs, ADR #27). A manual run racing
 * the periodic one is safe: every write on this path is transactional or dedup-guarded.
 */
suspend fun runPartnerSyncPass(
    registrar: PushInstallationRegistrar,
    rollout: PartnerRolloutGate,
    coordinator: PartnerSyncCoordinator,
    wakeMessageId: String? = null,
): PartnerSyncCoordinator.SyncReport {
    if (rollout.pushRegistrationEnabled()) {
        if (!registrar.register()) Log.w("MyExodusPartnerSync", "push installation not (re)registered this run")
    }
    return coordinator.syncOnce(wakeMessageId)
}

/** §14 housekeeping enqueue (MyExodusApp, post-unlock process start): KEEP, so the first
 *  enqueue on the device pins the cadence and every later process start leaves it alone. */
fun enqueuePartnerSync(context: Context) {
    val request = PeriodicWorkRequestBuilder<PartnerSyncWorker>(15, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PARTNER_SYNC_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}

/** internal (not private): the instrumented execution-leg test re-enqueues this same work. */
internal const val PARTNER_SYNC_WORK_NAME = "partner-sync"

/** The wake input key — shared with [PartnerMessagingService] so the producer and consumer of
 *  the wake payload can never drift apart. */
internal const val PARTNER_WAKE_MESSAGE_ID = "messageId"
