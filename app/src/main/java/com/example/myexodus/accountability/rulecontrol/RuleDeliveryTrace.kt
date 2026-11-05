package com.example.myexodus.accountability.rulecontrol

import android.util.Log
import com.example.myexodus.BuildConfig
import com.example.myexodus.util.deleteOrWarn
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * DEBUG-only receive-path breadcrumb (owner report 2026-10-31: the sender's mailbox was
 * acked 6 s after a rule edit and the partner still saw nothing, with logcat already gone).
 * One line per RULE_CONTROL envelope that reached the decrypt/commit hook — arrival, the
 * local store outcome, and the notification attempt — so the missing step is readable days
 * later:
 *
 *   adb shell run-as com.example.myexodus cat files/rule-delivery.log
 *
 * Release builds never write: [enabled] defaults to [BuildConfig.DEBUG], so the append is
 * dead code there. Same best-effort contract as the streak anomaly log — an I/O failure logs
 * to logcat and drops the line; evidence must never break delivery. Single rotation bounds
 * a pathological writer.
 */
class RuleDeliveryTrace(
    private val file: File,
    private val enabled: Boolean = BuildConfig.DEBUG,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 256 * 1024,
) {
    @Synchronized
    fun record(pairId: String, eventId: String, detail: String) {
        if (!enabled) return
        try {
            if (file.length() > maxBytes) rotate()
            file.parentFile?.mkdirs()
            val stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(now()))
            file.appendText("$stamp pair=$pairId event=$eventId $detail\n")
        } catch (e: Exception) {
            Log.w(TAG, "rule-delivery trace append failed (evidence line dropped)", e)
        }
    }

    private fun rotate() {
        val old = File(file.parentFile, file.name + ".1")
        old.deleteOrWarn(TAG)
        if (!file.renameTo(old)) file.deleteOrWarn(TAG)
    }

    private companion object {
        const val TAG = "RuleDeliveryTrace"
    }
}
