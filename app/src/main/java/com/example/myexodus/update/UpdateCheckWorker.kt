package com.example.myexodus.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Plan §8.2: metadata discovery only; this worker never downloads or installs. */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: UpdateCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!coordinator.configured) return Result.success()
        return when (coordinator.check(manual = false)) {
            CheckOutcome.UPDATED -> {
                coordinator.backgroundCheckFinished(
                    foreground = inputData.getBoolean(KEY_FOREGROUND, false),
                )
                Result.success()
            }
            CheckOutcome.THROTTLED, CheckOutcome.NOT_CONFIGURED -> Result.success()
            CheckOutcome.FAILED -> {
                val error = UpdateStateStore(applicationContext).lastCheckError()
                if (error in TRANSIENT) Result.retry() else Result.success()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "update_check"
        private const val WORK_NAME_FOREGROUND = "update_check_foreground"
        private const val KEY_FOREGROUND = "foreground"

        fun enqueue(context: Context) {
            if (!UpdateConfig.configured) return
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                UpdateConfig.CHECK_INTERVAL_HOURS,
                TimeUnit.HOURS,
                UpdateConfig.CHECK_FLEX_HOURS,
                TimeUnit.HOURS,
            ).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Plan §R01: a foreground entry after 24 hours joins one bounded metadata check. */
        fun enqueueForegroundIfDue(context: Context) {
            if (!UpdateConfig.configured) return
            val store = UpdateStateStore(context)
            val due = System.currentTimeMillis() - store.lastCheckAtMs() >=
                TimeUnit.HOURS.toMillis(UpdateConfig.CHECK_INTERVAL_HOURS)
            if (!due) return
            val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setInputData(workDataOf(KEY_FOREGROUND to true))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_FOREGROUND,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private val TRANSIENT = setOf(
            AttemptError.NETWORK_UNAVAILABLE,
            AttemptError.NETWORK_TIMEOUT,
            AttemptError.HOST_THROTTLED,
            AttemptError.HOST_UNAVAILABLE,
        )
    }
}
