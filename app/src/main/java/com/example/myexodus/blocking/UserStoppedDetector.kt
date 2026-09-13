package com.example.myexodus.blocking

import android.annotation.SuppressLint
import android.app.ApplicationExitInfo

/** A process-exit observation, mapped from ApplicationExitInfo in the service. */
data class ProcessExit(val reason: Int, val timestampEpochMillis: Long)

/**
 * Pure filter for the §8.2 Task-Manager "Stop" check: any REASON_USER_REQUESTED exit after
 * the last known-good watermark means the user stopped the app in the Android 13+ Task
 * Manager — a first-class enforcement-loss event, not a routine restart.
 */
@SuppressLint("InlinedApi") // REASON_USER_REQUESTED is a compile-time int; callers gate on SDK 30+
object UserStoppedDetector {
    fun wasUserStopped(
        exitReasons: List<ProcessExit>,
        watermarkEpochMillis: Long,
    ): Boolean = exitReasons.any {
        it.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
            it.timestampEpochMillis > watermarkEpochMillis
    }
}
