package com.example.myexodus.streaks

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
 */
class StreakAnomalyLog(
    private val file: File,
    private val maxBytes: Long = 512 * 1024,
) {
    @Synchronized
    fun append(anomaly: StreakAnomaly) {
        if (file.length() > maxBytes) rotate()
        file.parentFile?.mkdirs()
        file.appendText(format(anomaly) + "\n")
    }

    /**
     * §14 delivery counter (not an anomaly): logged when a milestone notification is posted,
     * so a reviewer can compare posted counts against splash cards actually seen and detect
     * OEMs suppressing inexact alarms. Timestamp is the observed device wall clock.
     */
    @Synchronized
    fun appendMilestonePosted(milestoneDay: Int, tWallMillis: Long) {
        if (file.length() > maxBytes) rotate()
        file.parentFile?.mkdirs()
        val stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(tWallMillis))
        file.appendText("$stamp type=MILESTONE_NOTIFICATION_POSTED milestoneDay=$milestoneDay\n")
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
}
