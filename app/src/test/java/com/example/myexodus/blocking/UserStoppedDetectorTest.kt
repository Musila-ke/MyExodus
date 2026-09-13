package com.example.myexodus.blocking

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §8.2 Task-Manager "Stop" detection (blocking-core plan §8.2, error row "App stopped
 *  via the Android 13+ Task Manager 'Stop' action"). */
class UserStoppedDetectorTest {

    @Test
    fun userRequestedExitAfterWatermark_detected() {
        assertTrue(
            UserStoppedDetector.wasUserStopped(
                listOf(ProcessExit(ApplicationExitInfo.REASON_USER_REQUESTED, 2_000L)),
                watermarkEpochMillis = 1_000L,
            ),
        )
    }

    @Test
    fun userRequestedExitBeforeWatermark_notDetected() {
        // Exits older than the last known-good state happened before we knew it was healthy.
        assertFalse(
            UserStoppedDetector.wasUserStopped(
                listOf(ProcessExit(ApplicationExitInfo.REASON_USER_REQUESTED, 500L)),
                watermarkEpochMillis = 1_000L,
            ),
        )
    }

    @Test
    fun crashOrAnrAfterWatermark_notDetected() {
        // A crash is a routine restart, not an enforcement-loss event.
        assertFalse(
            UserStoppedDetector.wasUserStopped(
                listOf(
                    ProcessExit(ApplicationExitInfo.REASON_CRASH, 2_000L),
                    ProcessExit(ApplicationExitInfo.REASON_ANR, 2_500L),
                ),
                watermarkEpochMillis = 1_000L,
            ),
        )
    }

    @Test
    fun emptyHistory_notDetected() {
        assertFalse(UserStoppedDetector.wasUserStopped(emptyList(), watermarkEpochMillis = 1_000L))
    }
}
