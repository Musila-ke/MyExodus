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

    // --- 2026-10-14 adversarial additions (stress batch): the boundary and the shapes the
    // re-fire investigation exercised. Each pins an intentional behavior, not a guess. ---

    @Test
    fun exitExactlyAtWatermark_notDetected() {
        // The comparison is strict >: an event at the watermark instant was already seen by the
        // scan that wrote that watermark. Flipping the operator would re-fire every event once.
        assertFalse(detected(exit(at = watermark, description = "remove task")))
    }

    @Test
    fun exitOneMillisecondAfterWatermark_detected() {
        assertTrue(detected(exit(at = watermark + 1, description = "remove task")))
    }

    @Test
    fun embeddedBackgroundKill_notDetected() {
        // AMS embeds the kill reason in the stop sentence; the markers must match mid-string.
        assertFalse(detected(exit(description = "stop com.example.myexodus due to kill background")))
        assertFalse(detected(exit(description = "stop com.example.myexodus due to kill all background")))
    }

    @Test
    fun differentlyCasedMarker_detected() {
        // Marker matching is case-sensitive and the policy is fail-safe: an OEM text that
        // doesn't match verbatim stays counted (a false positive costs a re-enable prompt).
        assertTrue(detected(exit(description = "stop com.example.myexodus due to Kill Background")))
    }

    @Test
    fun instrumentationAfterWatermark_doesNotMaskOlderRealStopBelowWatermark() {
        // Row-level gating: each row is judged alone. A fresh instrumentation row must not
        // "carry" an older real stop, and a real stop below the watermark stays retired.
        assertFalse(
            detected(
                exit(at = 500L, description = "fully stop com.example.myexodus/10411 by user request"),
                exit(at = 2_000L, description = "stop com.example.myexodus due to start instr"),
            ),
        )
    }

    @Test
    fun multipleUserRequestedExits_onlyNewerCounts() {
        // The strongest adversarial batch: one real stop below the watermark + one above —
        // detection must key on the newer row, never the count.
        assertTrue(
            detected(
                exit(at = 250L, description = "remove task"),
                exit(at = 4_000L, description = "stop com.example.myexodus due to from pid 1804"),
            ),
        )
    }
}
