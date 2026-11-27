package com.example.myexodus.ui.settings

import com.example.myexodus.update.DownloadSnapshot

/** Null while the transfer has no known total; the UI must not render that as a 0%. */
internal fun downloadPercent(progress: DownloadSnapshot?): Int? {
    val running = progress as? DownloadSnapshot.Running ?: return null
    if (running.totalBytes <= 0) return null
    return ((running.downloadedBytes * 100) / running.totalBytes).toInt().coerceIn(0, 100)
}

/** Flags a download whose byte count has not moved for [stallMs]; never fails the attempt (§9). */
internal class DownloadStallTracker(private val stallMs: Long) {
    private var attemptId: String? = null
    private var lastBytes = -1L
    private var unchangedSinceMs = 0L

    fun observe(attemptId: String?, downloadedBytes: Long?, nowMs: Long): Boolean {
        if (attemptId == null || downloadedBytes == null) {
            reset()
            return false
        }
        if (attemptId != this.attemptId) {
            this.attemptId = attemptId
            lastBytes = downloadedBytes
            unchangedSinceMs = nowMs
            return false
        }
        if (downloadedBytes != lastBytes) {
            lastBytes = downloadedBytes
            unchangedSinceMs = nowMs
            return false
        }
        return nowMs - unchangedSinceMs >= stallMs
    }

    fun reset() {
        attemptId = null
        lastBytes = -1L
        unchangedSinceMs = 0L
    }
}
