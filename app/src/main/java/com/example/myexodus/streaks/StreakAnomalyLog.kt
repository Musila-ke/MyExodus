package com.example.myexodus.streaks

import android.util.Log
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Local-file anomaly log (habit-streaks plan §14): timestamp, event type, observed anchor
 * values, and the resolution taken — one line per event, manual-review only, never network.
 * The timestamp is the *observed* device wall clock: offline there is no trusted time source,
 * so an anomaly caused by that same clock is inherently self-reported (a human reviewer reads
 * the delta and boot count together). Append-only with a single rotation so a persistent
 * anomaly (e.g. a clock stuck in the past, logged on every evaluation) cannot grow unbounded.
 *
 * Totality (finding #13, 2026-10-16): both writes swallow I/O failures. This log is a
 * best-effort evidence channel, and every caller appends from inside an evaluation write
 * transaction or between a milestone claim and its post — before this guard an IOException
 * (disk-full class) rolled back the evaluation or consumed a claim without posting. A failed
 * append logs to logcat and drops the evidence line; the DB write in the same transaction
 * remains the canary for a full disk.
 */
class StreakAnomalyLog(
    private val file: File,
    private val maxBytes: Long = 512 * 1024,
) {
    @Synchronized
    fun append(anomaly: StreakAnomaly) {
        appendLine(format(anomaly))
    }

    /**
     * §14 delivery counter (not an anomaly): logged when a milestone notification is posted,
     * so a reviewer can compare posted counts against splash cards actually seen and detect
     * OEMs suppressing inexact alarms. Timestamp is the observed device wall clock.
     */
    @Synchronized
    fun appendMilestonePosted(milestoneDay: Int, tWallMillis: Long) {
        val stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(tWallMillis))
        appendLine("$stamp type=MILESTONE_NOTIFICATION_POSTED milestoneDay=$milestoneDay")
    }

    private fun appendLine(line: String) {
        try {
            if (file.length() > maxBytes) rotate()
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "anomaly-log append failed (evidence line dropped)", e)
        }
    }

    private fun rotate() {
        val old = File(file.parentFile, file.name + ".1")
        old.delete()
        // Rename can fail (locked/exotic FS); truncating loses history but never wedges.
        if (!file.renameTo(old)) file.delete()
    }

    private fun format(a: StreakAnomaly): String = buildString {
        append(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(a.tWallMillis)))
        append(" type=").append(a.type)
        append(" bootCount=").append(a.bootCount)
        append(" tWall=").append(a.tWallMillis)
        append(" rBoot=").append(a.rBootMillis)
        append(" deltaMs=").append(a.deltaMs ?: "-")
        append(" resolution=").append(a.resolution)
    }

    private companion object {
        const val TAG = "StreakAnomalyLog"
    }
}
