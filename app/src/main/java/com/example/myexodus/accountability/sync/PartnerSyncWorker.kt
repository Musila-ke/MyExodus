package com.example.myexodus.accountability.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** §14 housekeeping (plan §7 Decision 7): 15-minute partner sync — attempt → event reconcile,
 *  outbox drain, mailbox pull, backfill response. Delivery latency is bounded by this cadence
 *  (at-least-once; the attempt log is the local source of truth meanwhile). */
@HiltWorker
class PartnerSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: PartnerSyncCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val report = coordinator.syncOnce()
        Log.i(TAG, "sync: $report")
        return Result.success()
    }

    private companion object {
        const val TAG = "MyExodusPartnerSync"
    }
}
