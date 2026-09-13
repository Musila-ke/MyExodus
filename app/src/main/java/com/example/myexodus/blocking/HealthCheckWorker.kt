package com.example.myexodus.blocking

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Plan §8 Step 10: OS-minimum (~15 min) periodic health check; eventual detection only. */
@HiltWorker
class HealthCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val monitor: EnhancedModeHealthMonitor,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        monitor.refresh()
        return Result.success()
    }
}
