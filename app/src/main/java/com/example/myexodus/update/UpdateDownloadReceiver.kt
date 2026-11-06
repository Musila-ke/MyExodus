package com.example.myexodus.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * 2026-11-06 revision: completes a background transfer. DownloadManager's broadcast is the
 * only wake-up; verification runs in an expedited worker so the receiver window stays cheap.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return
        val attempt = UpdateStateStore(context).activeAttempt() ?: return
        if (attempt.downloadId != downloadId) return
        if (attempt.state != AttemptState.DOWNLOADING &&
            attempt.state != AttemptState.DOWNLOAD_STARTING
        ) {
            return
        }
        val request = OneTimeWorkRequestBuilder<UpdateVerifyWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UpdateVerifyWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
