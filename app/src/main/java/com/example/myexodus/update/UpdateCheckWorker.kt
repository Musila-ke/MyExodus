package com.example.myexodus.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.myexodus.BuildConfig
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = UpdateStateStore(applicationContext)
        ApkDownloader(updatesDir(applicationContext)).sweep(System.currentTimeMillis())
        val result = UpdateChecker().check(BuildConfig.VERSION_CODE.toLong())
        store.recordCheck(result, System.currentTimeMillis())
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "update_check"

        fun enqueue(context: Context) {
            if (UpdateConfig.MANIFEST_PUBLIC_KEY_BASE64.isBlank()) return
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                UpdateConfig.CHECK_INTERVAL_HOURS,
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

        internal fun updatesDir(context: Context): File = File(context.cacheDir, "updates")
    }
}
