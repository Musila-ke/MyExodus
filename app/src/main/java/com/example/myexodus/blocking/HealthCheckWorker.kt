package com.example.myexodus.blocking

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Plan §8 Step 10: OS-minimum (~15 min) periodic health check; eventual detection only.
 *  Also one of §8 Step 6's protection-status refresh triggers (the maintenance job's run). */
@HiltWorker
class HealthCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val monitor: EnhancedModeHealthMonitor,
    private val statusMonitor: ProtectionStatusMonitor,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        monitor.refresh()
        statusMonitor.refresh()
        return Result.success()
    }
}
