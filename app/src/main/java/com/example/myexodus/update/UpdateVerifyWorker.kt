package com.example.myexodus.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Verifies the finished background transfer, then posts the ready-to-install post. */
@HiltWorker
class UpdateVerifyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: UpdateCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val attempt = coordinator.refreshDownload() ?: return Result.success()
        if (attempt.state == AttemptState.DOWNLOADING ||
            attempt.state == AttemptState.DOWNLOAD_STARTING
        ) {
            return Result.retry()
        }
        if (attempt.state == AttemptState.READY_TO_INSTALL) {
            UpdateNotifications.notifyReady(applicationContext, attempt.versionName)
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "update_verify"
    }
}
