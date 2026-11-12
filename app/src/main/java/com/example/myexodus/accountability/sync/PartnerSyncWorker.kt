package com.example.myexodus.accountability.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.myexodus.data.db.VaultLockedException
import com.example.myexodus.util.canExpediteWork
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
        val report = try {
            runPartnerSyncPass(
                registrar,
                rollout,
                coordinator,
                wakeMessageId = inputData.getString(PARTNER_WAKE_MESSAGE_ID),
                forceHeartbeat = inputData.getBoolean(PARTNER_FORCE_HEARTBEAT, false),
            )
        } catch (e: VaultLockedException) {
            // §8.9: a locked App Lock vault defers the pass — WorkManager's backoff retries it
            // once the vault is open; the activity kick and periodic cadence also cover it.
            Log.i(TAG, "sync deferred: vault locked")
            return Result.retry()
        }
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
    if (rollout.pushRegistrationEnabled() && !registrar.register()) {
        Log.w("MyExodusPartnerSync", "push installation not (re)registered this run")
    }
    val report = coordinator.syncOnce(wakeMessageId, forceHeartbeat)
    // Unpair progress (peer shred pulled, journal row advanced, ack confirmed) repaints too:
    // deletion passes report pulledCount=0 by design (the wake path returns before the
    // watermark query), yet they are exactly the passes the Partner page must react to
    // (owner-observed stale page after a partnership ended, 2026-10-01).
    // Owner report 2026-11-12: a staged-mailbox delivery repainted nothing until the subpage
    // was left and re-entered — that transport counts in recipientEventsPulled, not the legacy
    // chain's pulledCount. Both repaint.
    if (report.pulledCount > 0 || report.recipientEventsPulled > 0 || report.unpairAdvanced) {
        PartnerSyncSignals.pulledEvents.tryEmit(Unit)
    }
    // ADR #216: surface a cold signing window so the foreground retries the pass once the
    // window is warm. Owner ruling 2026-11-05: automatic delivery never prompts.
    if (report.signingBlocked) PartnerSyncSignals.signingNeeded.tryEmit(Unit)
    PartnerSyncSignals.signingDeferred.value = report.signingBlocked
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

/**
 * One immediate pass after a local rule mutation (owner report 2026-10-31): the rule journal
 * row exists the moment the write commits, but only a sync pass turns it into a partner
 * event — the periodic cadence would otherwise delay the partner's alert by up to 15 minutes.
 * EXPEDITED since owner ruling 2026-11-09 (partner alerts land within seconds): without it a
 * backgrounded app's pass waits for a scheduler slot and is deferred outright in Doze, so
 * even a fully signed row could sit unsent. KEEP, so a pass already running is never
 * duplicated (the row is still picked up by it). The network constraint still applies --
 * expedited work is prioritized, not constraint-free.
 */
fun enqueuePartnerSyncNow(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        PARTNER_SYNC_NOW_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        partnerSyncNowRequest(),
    )
}

/** Top-level internal so a JVM test can pin the expedited flag that the seconds-level
 *  delivery contract depends on. */
internal fun partnerSyncNowRequest(expedited: Boolean = canExpediteWork()): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<PartnerSyncWorker>()
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .apply {
            if (expedited) {
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
        }
        .build()

internal const val PARTNER_SYNC_NOW_WORK_NAME = "partner-sync-now"

/** The wake input key — shared with [PartnerMessagingService] so the producer and consumer of
 *  the wake payload can never drift apart. */
internal const val PARTNER_WAKE_MESSAGE_ID = "messageId"

/** Set only by a liveness-probe push: the server has not accepted a beat for 45 minutes, so
 *  bypass the local 15-minute floor and answer even if this device sent a failed beat
 *  recently. */
internal const val PARTNER_FORCE_HEARTBEAT = "forceHeartbeat"
