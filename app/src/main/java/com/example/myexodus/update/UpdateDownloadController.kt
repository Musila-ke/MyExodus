package com.example.myexodus.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.InputStream

internal data class NetworkState(
    val connected: Boolean,
    val metered: Boolean,
    val roaming: Boolean,
)

internal sealed interface DownloadEnqueue {
    data object Enqueued : DownloadEnqueue
    data class Failed(val failure: AttemptError) : DownloadEnqueue
}

internal sealed interface DownloadSnapshot {
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : DownloadSnapshot
    data class Paused(val downloadedBytes: Long) : DownloadSnapshot
}

/**
 * File-based progress for the in-app transfer. A metered, roaming or offline network reports
 * Paused because [UpdateTransferWorker] stops instead of transferring.
 */
internal fun transferSnapshot(
    network: NetworkState,
    meteredConsent: Boolean,
    downloadedBytes: Long,
    expectedBytes: Long,
): DownloadSnapshot {
    val bytes = downloadedBytes.coerceIn(0L, maxOf(expectedBytes, 0L))
    return if (!network.connected || network.roaming || (network.metered && !meteredConsent)) {
        DownloadSnapshot.Paused(bytes)
    } else {
        DownloadSnapshot.Running(bytes, expectedBytes)
    }
}

/**
 * Plan §8.4 replacement (ADR #321): DownloadManager binds its connectivity check to the
 * requesting app's uid, and the self-excluded protection VPN can never satisfy that check
 * (INV-9), so transfers run in-app and resume from the byte count already on disk.
 */
internal class UpdateDownloadController(context: Context) {
    private val appContext = context.applicationContext

    fun networkState(): NetworkState {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
            ?: return NetworkState(connected = false, metered = false, roaming = false)
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return NetworkState(connected = false, metered = false, roaming = false)
        val roaming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        } else {
            runCatching {
                appContext.getSystemService(TelephonyManager::class.java).isNetworkRoaming
            }.getOrDefault(false)
        }
        return NetworkState(
            connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            roaming = roaming,
        )
    }

    fun relativePath(attemptId: String): String = "updates/$attemptId/release.apk"

    fun destinationPath(attemptId: String): String? {
        val externalFiles = appContext.getExternalFilesDir(null) ?: return null
        return File(externalFiles, relativePath(attemptId)).absolutePath
    }

    /**
     * No WorkManager network constraint: WorkManager schedules through JobScheduler, and a
     * job's network requirement is bound to the requesting uid, which the self-excluded VPN
     * cannot satisfy (ADR #321). The worker checks the network itself and retries.
     */
    fun start(attemptId: String, replace: Boolean = false): DownloadEnqueue {
        val destination = destinationPath(attemptId)
            ?: return DownloadEnqueue.Failed(AttemptError.DOWNLOAD_DESTINATION_UNAVAILABLE)
        val directory = File(destination).parentFile
        if (directory == null ||
            (!directory.isDirectory && !directory.mkdirs())
        ) {
            return DownloadEnqueue.Failed(AttemptError.DOWNLOAD_DESTINATION_UNAVAILABLE)
        }
        val request = OneTimeWorkRequestBuilder<UpdateTransferWorker>()
            .setInputData(workDataOf(UpdateTransferWorker.EXTRA_ATTEMPT_ID to attemptId))
            .addTag(TAG)
            .build()
        return try {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                workName(attemptId),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
            DownloadEnqueue.Enqueued
        } catch (e: Exception) {
            DownloadEnqueue.Failed(AttemptError.DOWNLOAD_SERVICE_UNAVAILABLE)
        }
    }

    fun cancel(attemptId: String) {
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(workName(attemptId)) }
    }

    fun progress(attempt: UpdateAttempt, network: NetworkState): DownloadSnapshot? {
        if (attempt.state != AttemptState.DOWNLOADING &&
            attempt.state != AttemptState.DOWNLOAD_STARTING
        ) {
            return null
        }
        val destination = attempt.destination ?: return null
        val downloaded = runCatching { File(destination).length() }.getOrDefault(0L)
        return transferSnapshot(
            network = network,
            meteredConsent = attempt.meteredConsent,
            downloadedBytes = downloaded,
            expectedBytes = attempt.apkSizeBytes,
        )
    }

    fun isComplete(attempt: UpdateAttempt): Boolean {
        if (attempt.apkSizeBytes <= 0) return false
        val destination = attempt.destination ?: return false
        return runCatching { File(destination).length() == attempt.apkSizeBytes }
            .getOrDefault(false)
    }

    fun openFile(path: String): InputStream? =
        runCatching { File(path).inputStream() }.getOrNull()

    fun deleteAttemptFiles(attemptId: String) {
        val externalFiles = appContext.getExternalFilesDir(null) ?: return
        runCatching { File(externalFiles, "updates/$attemptId").deleteRecursively() }
    }

    /** Remove terminal leftovers; only the active attempt's directory survives. */
    fun sweepOtherAttempts(activeAttemptId: String?) {
        val externalFiles = appContext.getExternalFilesDir(null) ?: return
        val updates = File(externalFiles, "updates")
        updates.listFiles()?.forEach { dir ->
            if (dir.name != activeAttemptId) runCatching { dir.deleteRecursively() }
        }
    }

    private companion object {
        const val TAG = "update_transfer"
        const val WORK_PREFIX = "update_transfer_"

        fun workName(attemptId: String): String = WORK_PREFIX + attemptId
    }
}
