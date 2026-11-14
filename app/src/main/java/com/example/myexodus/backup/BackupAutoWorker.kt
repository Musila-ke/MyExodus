package com.example.myexodus.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.myexodus.data.db.VaultLockedException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * ADR #310: the daily automatic-backup trigger. The pass decides from content and state; this
 * worker only maps its outcome onto WorkManager's retry policy.
 */
@HiltWorker
class BackupAutoWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backup: AutomaticBackup,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val outcome = backup.run()
        if (outcome is AutomaticBackup.Outcome.Failed && outcome.retryable) {
            Result.retry()
        } else {
            Result.success()
        }
    } catch (e: VaultLockedException) {
        // Same deferral as daily maintenance: a later attempt once the App Lock unlock ran.
        Result.retry()
    }
}

/** KEEP: the first enqueue pins the schedule and later process starts leave it alone. */
fun enqueueAutomaticBackup(context: Context) {
    val request = PeriodicWorkRequestBuilder<BackupAutoWorker>(24, TimeUnit.HOURS)
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        AUTOMATIC_BACKUP_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}

/** internal (not private): the instrumented worker test re-enqueues this same work. */
internal const val AUTOMATIC_BACKUP_WORK_NAME = "backup-auto"
