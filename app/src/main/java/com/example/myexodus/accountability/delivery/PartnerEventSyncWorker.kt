package com.example.myexodus.accountability.delivery

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * §4.1/§4.3 background drain: no foreground UI, so registration is never attempted here —
 * enrollment/rotation needs §8.3.1's foreground acts and is left to the coordinator's
 * foreground pass. The periodic instance is the recovery backstop; the immediate instance
 * carries the capture kick.
 */
@HiltWorker
class PartnerEventSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val drain: PartnerEventDrain,
    private val outbox: ServiceEventOutbox,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // §6.1 step 4: reset the marker when a job starts, so an event committed mid-run
        // arms exactly one queued follow-up behind this worker.
        outbox.clearWake()
        val result = try {
            drain.run(allowRegistration = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "drain failed", e)
            return Result.retry()
        }
        Log.i(TAG, "drain: $result")
        scheduleNextRetry(applicationContext, result.earliestNextAttemptMs)
        return Result.success()
    }

    private companion object {
        const val TAG = "MyExodusPartnerEvents"
    }
}

/**
 * §4.1 immediate capture kick. APPEND_OR_REPLACE, never KEEP alone: an event committed just
 * before a running worker exits still gets its own queued pass, and the persistent wake
 * marker ([ServiceEventOutbox.requestWake]) bounds the chain to one appended job per marker.
 */
fun enqueuePartnerEventSyncNow(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        PARTNER_EVENT_SYNC_WORK_NAME,
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        partnerEventSyncNowRequest(),
    )
}

internal fun partnerEventSyncNowRequest(): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<PartnerEventSyncWorker>()
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        // Owner ruling 2026-11-09: a partner alert must land within seconds of the act, so the
        // capture kick may not wait for an ordinary JobScheduler slot; quota spillover degrades
        // to a normal request, never to a dropped event (the outbox row stays pending).
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

/** §4.1 periodic backstop — best effort, not a guaranteed 15-minute tick. */
fun enqueuePartnerEventSync(context: Context) {
    val request = PeriodicWorkRequestBuilder<PartnerEventSyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PARTNER_EVENT_SYNC_PERIODIC_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
    )
}

/**
 * §4.3/§9 backoff: the drain computed the exact earliest due time, so this is a timed wake
 * rather than a poll. REPLACE keeps one pending retry whose delay always reflects the newest
 * queue state; a retry cancelled here mid-drain re-leases through the lease bound.
 */
internal fun scheduleNextRetry(context: Context, earliestNextAttemptMs: Long?) {
    if (earliestNextAttemptMs == null) return
    val delayMs = (earliestNextAttemptMs - System.currentTimeMillis()).coerceAtLeast(0)
    WorkManager.getInstance(context).enqueueUniqueWork(
        PARTNER_EVENT_SYNC_RETRY_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<PartnerEventSyncWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build(),
    )
}

internal const val PARTNER_EVENT_SYNC_WORK_NAME = "myexodus.partner.sync"
internal const val PARTNER_EVENT_SYNC_PERIODIC_WORK_NAME = "myexodus.partner.sync.periodic"
internal const val PARTNER_EVENT_SYNC_RETRY_WORK_NAME = "myexodus.partner.sync.retry"
