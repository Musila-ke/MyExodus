package com.example.myexodus.blocking

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.2 user-stop detection (blocking-core plan §8.2, error row "App stopped via the Android
 * 13+ Task Manager 'Stop' action"). Descriptions are the AOSP 14 ActivityManagerService
 * strings and the X6532 exit-dump shapes (2026-09-28) — the detector keys on them because the
 * compileSdk 37 stubs drop `getSubReason()`/`SUBREASON_*`.
 */
class UserStoppedDetectorTest {

    private val watermark = 1_000L

    private fun exit(
        reason: Int = ApplicationExitInfo.REASON_USER_REQUESTED,
        at: Long = 2_000L,
        description: String = "",
    ) = ProcessExit(reason, at, description)

    private fun detected(vararg exits: ProcessExit) =
        UserStoppedDetector.wasUserStopped(exits.toList(), watermarkEpochMillis = watermark)

    @Test
    fun taskManagerStop_detected() {
        assertTrue(detected(exit(description = "fully stop com.example.myexodus/10411 by user request")))
    }

    @Test
    fun settingsForceStop_detected() {
        // AOSP: forceStopPackageLocked(pkg, uid, "from pid " + callingPid)
        assertTrue(detected(exit(description = "stop com.example.myexodus due to from pid 4242")))
    }

    @Test
    fun recentsSwipeAway_detected() {
        assertTrue(detected(exit(description = "remove task")))
    }

    @Test
    fun exitWithNoDescription_detected() {
        // Unknown shapes stay counted: over-reporting costs a re-enable prompt, under-reporting
        // hides an enforcement loss.
        assertTrue(detected(exit(description = "")))
    }

    @Test
    fun instrumentationStart_notDetected() {
        // Device-caught 2026-09-28: am instrument force-stopped the app and pinned the device
        // in APP_STOPPED — every test run looked like the user stopping protection.
        assertFalse(detected(exit(description = "stop com.example.myexodus due to start instr")))
    }

    @Test
    fun instrumentationFinish_notDetected() {
        assertFalse(detected(exit(description = "stop com.example.myexodus due to finished inst")))
        assertFalse(detected(exit(description = "stop com.example.myexodus due to finished instr")))
    }

    @Test
    fun backgroundKill_notDetected() {
        assertFalse(detected(exit(description = "kill background")))
        assertFalse(detected(exit(description = "kill all background")))
    }

    @Test
    fun exitBeforeWatermark_notDetected() {
        // Exits older than the last known-good state happened before we knew it was healthy.
        assertFalse(
            UserStoppedDetector.wasUserStopped(
                listOf(exit(at = 500L, description = "remove task")),
                watermarkEpochMillis = watermark,
            ),
        )
    }

    @Test
    fun crashOrAnr_notDetected() {
        // A crash is a routine restart, not an enforcement-loss event.
        assertFalse(
            detected(
                exit(reason = ApplicationExitInfo.REASON_CRASH),
                exit(reason = ApplicationExitInfo.REASON_ANR, at = 2_500L),
            ),
        )
    }

    @Test
    fun instrumentationRowAlongsideARealStop_detectsTheRealStop() {
        assertTrue(
            detected(
                exit(at = 1_500L, description = "stop com.example.myexodus due to finished inst"),
                exit(at = 1_800L, description = "fully stop com.example.myexodus/10411 by user request"),
            ),
        )
    }

    @Test
    fun emptyHistory_notDetected() {
        assertFalse(UserStoppedDetector.wasUserStopped(emptyList(), watermarkEpochMillis = watermark))
    }
}
