package com.example.myexodus.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ADR #321: the transfer DownloadManager could not perform while the protection VPN is up.
 * Runs as a long-running worker (foreground where the OS allows it), resumes from the bytes
 * already on disk, and drives the existing verify path on completion.
 */
@HiltWorker
class UpdateTransferWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: UpdateCoordinator,
) : CoroutineWorker(appContext, params) {
    private val controller = UpdateDownloadController(appContext)
    private val diagnostics = UpdateDiagnostics(appContext)
    private val transferClient = UpdateTransferClient()
    private var foregrounded = false
    private var lastNotifyAtMs = 0L

    override suspend fun doWork(): Result {
        val attemptId = inputData.getString(EXTRA_ATTEMPT_ID) ?: return Result.success()
        val attempt = UpdateStateStore(applicationContext).activeAttempt()
            ?.takeIf { it.attemptId == attemptId }
            ?: return Result.success()
        if (attempt.state != AttemptState.DOWNLOADING &&
            attempt.state != AttemptState.DOWNLOAD_STARTING
        ) {
            return Result.success()
        }
        val destination = attempt.destination ?: controller.destinationPath(attemptId)
            ?: return fail(attempt, AttemptError.DOWNLOAD_DESTINATION_UNAVAILABLE)
        val file = File(destination)
        if (controller.isComplete(attempt)) return finish(attempt)
        if (!networkAllows(attempt)) return Result.retry()
        enterForeground(attempt, file)
        diagnostics.record(
            code = "transfer_started",
            policySequence = attempt.policySequence,
            versionCode = attempt.versionCode,
            bytes = file.length(),
        )
        return when (val outcome = withContext(Dispatchers.IO) { transfer(attempt, file) }) {
            TransferOutcome.Complete -> finish(attempt)
            is TransferOutcome.Retry -> {
                diagnostics.record(
                    code = "transfer_retry",
                    policySequence = attempt.policySequence,
                    versionCode = attempt.versionCode,
                    bytes = file.length(),
                )
                Result.retry()
            }
            is TransferOutcome.Terminal -> fail(attempt, outcome.failure)
        }
    }

    private suspend fun finish(attempt: UpdateAttempt): Result {
        val updated = coordinator.refreshDownload()
        if (updated?.state == AttemptState.READY_TO_INSTALL) {
            UpdateNotifications.notifyReady(applicationContext, updated.versionName)
        }
        return Result.success()
    }

    private suspend fun fail(attempt: UpdateAttempt, failure: AttemptError): Result {
        coordinator.markTransferFailed(attempt.attemptId, failure)
        return Result.failure()
    }

    private fun networkAllows(attempt: UpdateAttempt): Boolean {
        val network = controller.networkState()
        return network.connected &&
            !network.roaming &&
            (!network.metered || attempt.meteredConsent)
    }

    // API 31+ refuses a foreground service started from the background; the transfer then
    // runs as an ordinary job and resumes from disk if the system stops it.
    private suspend fun enterForeground(attempt: UpdateAttempt, file: File) {
        val info = foregroundInfo(attempt.versionName, file.length(), attempt.apkSizeBytes)
        foregrounded = try {
            setForeground(info)
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun foregroundInfo(versionName: String, downloaded: Long, total: Long): ForegroundInfo {
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else null
        val notification = UpdateNotifications.transfer(applicationContext, versionName, percent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                UpdateNotifications.TRANSFER_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(UpdateNotifications.TRANSFER_ID, notification)
        }
    }

    private fun publishProgress(attempt: UpdateAttempt, downloaded: Long) {
        if (!foregrounded) return
        val now = System.currentTimeMillis()
        if (now - lastNotifyAtMs < PROGRESS_INTERVAL_MS) return
        lastNotifyAtMs = now
        runCatching {
            setForegroundAsync(foregroundInfo(attempt.versionName, downloaded, attempt.apkSizeBytes))
        }
    }

    private fun transfer(attempt: UpdateAttempt, file: File): TransferOutcome =
        transferClient.transfer(attempt.apkUrl, file, attempt.apkSizeBytes) {
            publishProgress(attempt, it)
        }

    companion object {
        const val EXTRA_ATTEMPT_ID = "attempt_id"

        private const val PROGRESS_INTERVAL_MS = 2_000L
    }
}
