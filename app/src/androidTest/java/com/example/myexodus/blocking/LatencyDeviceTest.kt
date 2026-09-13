package com.example.myexodus.blocking

import android.util.Log
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enhanced Mode §12 latency test: app-switch-to-restricted-app cycles on the physical device;
 * p50/p95/p99 of the three staged durations (plan §8 Step 6 / Decision 11) asserted against
 * the §2 targets (detection-to-overlay p99 < 1000ms, frame presentation p99 < 100ms).
 * The singleton [LatencyProfiler] samples 100% in debuggable builds and keeps the last 500.
 */
class LatencyDeviceTest : EnhancedModeDeviceSuite() {

    @Test
    fun latency_blockCycles_meetTargets() = runTest {
        resetData()
        seedProfile()
        seedRule(victimPackage, "APP")

        val cycles = 10
        repeat(cycles) { i ->
            launchVictim()
            waitForOverlay(true, timeoutMs = 8_000)
            launchAllowedApp()
            waitForOverlay(false, timeoutMs = 5_000)
            Log.i(TAG, "cycle ${i + 1}/$cycles complete")
        }

        val (detection, submission, presentation) = entryPoint.latencyProfiler().percentilesMillis()
        Log.i(
            TAG,
            "latency ms detection(p50/p95/p99)=${detection.contentToString()} " +
                "submission=${submission.contentToString()} presentation=${presentation.contentToString()}",
        )

        assertTrue(
            "detection p99 ${detection[2]}ms exceeds the <1000ms §2 target",
            detection[2] in 0 until 1_000,
        )
        assertTrue(
            "presentation p99 ${presentation[2]}ms exceeds the <100ms §2 target",
            presentation[2] in 0 until 100,
        )
    }

    private companion object {
        const val TAG = "MyExodusLatencyTest"
    }
}
