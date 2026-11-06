package com.example.myexodus.update

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.net.toUri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.telephony.TelephonyManager
import java.io.File
import java.io.InputStream

internal data class NetworkState(
    val connected: Boolean,
    val metered: Boolean,
    val roaming: Boolean,
)

internal sealed interface DownloadEnqueue {
    data class Enqueued(val downloadId: Long) : DownloadEnqueue
    data class Failed(val failure: AttemptError) : DownloadEnqueue
}

internal sealed interface DownloadSnapshot {
    data class Running(val downloadedBytes: Long, val totalBytes: Long) : DownloadSnapshot
    data object Paused : DownloadSnapshot
    data class Failed(val failure: AttemptError) : DownloadSnapshot
    data object Successful : DownloadSnapshot
    data object Missing : DownloadSnapshot
}

/**
 * Plan §8.4: DownloadManager into app-specific external storage, neutral notification,
 * no public Downloads or FileProvider fallback, owned-entry cancellation only.
 */
internal class UpdateDownloadController(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DownloadManager::class.java)

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
        val externalFiles = externalFilesDir() ?: return null
        return File(externalFiles, relativePath(attemptId)).absolutePath
    }

    fun enqueue(
        attemptId: String,
        apkUrl: String,
        meteredConsent: Boolean,
    ): DownloadEnqueue {
        val externalFiles = externalFilesDir()
            ?: return DownloadEnqueue.Failed(AttemptError.DOWNLOAD_DESTINATION_UNAVAILABLE)
        val service = manager
            ?: return DownloadEnqueue.Failed(AttemptError.DOWNLOAD_SERVICE_UNAVAILABLE)
        val directory = File(externalFiles, "updates/$attemptId")
        if (!directory.isDirectory && !directory.mkdirs()) {
            return DownloadEnqueue.Failed(AttemptError.DOWNLOAD_DESTINATION_UNAVAILABLE)
        }
        return try {
            val request = DownloadManager.Request(apkUrl.toUri())
                .setTitle("MyExodus update")
                .setDescription("Downloading an app update")
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalFilesDir(appContext, null, relativePath(attemptId))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverRoaming(false)
                .setAllowedOverMetered(meteredConsent)
            DownloadEnqueue.Enqueued(service.enqueue(request))
        } catch (e: IllegalStateException) {
            DownloadEnqueue.Failed(AttemptError.DOWNLOAD_SERVICE_UNAVAILABLE)
        } catch (e: Exception) {
            DownloadEnqueue.Failed(AttemptError.DOWNLOAD_DESTINATION_UNAVAILABLE)
        }
    }

    fun snapshot(downloadId: Long): DownloadSnapshot {
        val service = manager ?: return DownloadSnapshot.Missing
        val cursor = runCatching {
            service.query(DownloadManager.Query().setFilterById(downloadId))
        }.getOrNull() ?: return DownloadSnapshot.Missing
        cursor.use {
            if (!it.moveToFirst()) return DownloadSnapshot.Missing
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadSnapshot.Successful
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING ->
                    DownloadSnapshot.Running(downloaded, total)
                DownloadManager.STATUS_PAUSED -> DownloadSnapshot.Paused
                DownloadManager.STATUS_FAILED -> {
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    DownloadSnapshot.Failed(failureForReason(reason))
                }
                else -> DownloadSnapshot.Missing
            }
        }
    }

    fun openStream(downloadId: Long): InputStream? {
        val service = manager ?: return null
        val descriptor: ParcelFileDescriptor = service.openDownloadedFile(downloadId) ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    fun cancel(downloadId: Long) {
        runCatching { manager?.remove(downloadId) }
    }

    fun deleteAttemptFiles(attemptId: String) {
        val externalFiles = externalFilesDir() ?: return
        runCatching { File(externalFiles, "updates/$attemptId").deleteRecursively() }
    }

    /** Remove terminal leftovers; only the active attempt's directory survives. */
    fun sweepOtherAttempts(activeAttemptId: String?) {
        val externalFiles = externalFilesDir() ?: return
        val updates = File(externalFiles, "updates")
        updates.listFiles()?.forEach { dir ->
            if (dir.name != activeAttemptId) runCatching { dir.deleteRecursively() }
        }
    }

    private fun externalFilesDir(): File? = appContext.getExternalFilesDir(null)

    private fun failureForReason(reason: Int): AttemptError = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> AttemptError.INSUFFICIENT_STORAGE
        DownloadManager.ERROR_CANNOT_RESUME -> AttemptError.NETWORK_UNAVAILABLE
        DownloadManager.ERROR_TOO_MANY_REDIRECTS,
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
        DownloadManager.ERROR_HTTP_DATA_ERROR,
        DownloadManager.ERROR_UNKNOWN,
        DownloadManager.ERROR_FILE_ERROR,
        DownloadManager.ERROR_FILE_ALREADY_EXISTS,
        -> AttemptError.DOWNLOAD_FAILED
        else -> AttemptError.DOWNLOAD_FAILED
    }
}
