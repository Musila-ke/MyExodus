package com.example.myexodus.blocking

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.2 user-stop detection (blocking-core plan §8.2, error row "App stopped via the Android
 * 13+ Task Manager 'Stop' action"). Descriptions are the AOSP 14 ActivityManagerService
 * strings and the X6532 exit-dump shapes (2026-09-28 and 2026-10-15) — the detector keys on
 * them because the compileSdk 37 stubs drop `getSubReason()`/`SUBREASON_*`.
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
        // hides an enforcement loss. (The 2026-10-15 measurement — a false positive in a storm
        // era voided ~2.4 h — is why an unknown shape must still pass the correlation checks,
        // but a lone row without error context keeps the fail-safe direction: counted.)
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
        // doesn't match verbatim stays counted unless its row is error-correlated (ADR #156).
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

    // --- 2026-10-15 X6532 ANR-storm false positive (ADR #156): a kill that escapes the app's
    // own ANR/crash, or lands amid a restart storm, is not a deliberate pause. The device
    // shapes below are verbatim from the exit dump (/tmp/exitinfo_now.txt). ---

    private val anr = ApplicationExitInfo.REASON_ANR
    private val lowMemory = ApplicationExitInfo.REASON_LOW_MEMORY

    @Test
    fun forceStopEightSecondsAfterAnr_notDetected() {
        // Device rows: ANR death 18:42:11.724, "from pid" kill 18:42:19.661 — the row the old
        // detector counted, pinning the flag through a re-enable attempt.
        assertFalse(
            detected(
                exit(reason = anr, at = 100_000L),
                exit(at = 108_000L, description = "stop com.example.myexodus due to from pid 24261"),
            ),
        )
    }

    @Test
    fun forceStopAfterNativeCrash_notDetected() {
        assertFalse(
            detected(
                exit(reason = ApplicationExitInfo.REASON_CRASH_NATIVE, at = 100_000L),
                exit(at = 160_000L, description = "stop com.example.myexodus due to from pid 4242"),
            ),
        )
    }

    @Test
    fun errorOutsideTheFiveMinuteWindow_doesNotExempt() {
        // 301 s before the kill is not its context.
        assertTrue(
            detected(
                exit(reason = anr, at = 100_000L),
                exit(at = 401_000L, description = "stop com.example.myexodus due to from pid 4242"),
            ),
        )
    }

    @Test
    fun errorExactlyAtTheAdjacencyEdge_exempts() {
        // The window is [ts-5min, ts): the boundary second is inside it.
        assertFalse(
            detected(
                exit(reason = anr, at = 400_000L),
                exit(at = 700_000L, description = "stop com.example.myexodus due to from pid 4242"),
            ),
        )
    }

    @Test
    fun errorAfterTheCandidate_doesNotExempt() {
        // The kill preceded the failure — it cannot have been an escape from it.
        assertTrue(
            detected(
                exit(at = 100_000L, description = "stop com.example.myexodus due to from pid 4242"),
                exit(reason = anr, at = 160_000L),
            ),
        )
    }

    @Test
    fun killInTheRestartStorm_notDetected() {
        // The 18:41–18:43 dump window verbatim: finished-inst death, ANR, from-pid kill, ANR,
        // start-instr death, from-pid kill — six rows inside ~3 min of each candidate.
        assertFalse(
            detected(
                exit(at = 200_000L, description = "stop com.example.myexodus due to finished inst"),
                exit(reason = anr, at = 267_000L),
                exit(at = 275_000L, description = "stop com.example.myexodus due to from pid 24261"),
                exit(reason = anr, at = 318_000L),
                exit(at = 333_000L, description = "stop com.example.myexodus due to start instr"),
                exit(at = 366_000L, description = "stop com.example.myexodus due to from pid 25267"),
            ),
        )
    }

    @Test
    fun stormAlone_threeExitsWithoutErrorRows_exempts() {
        // Even with no error row adjacent, three deaths in ±10 min is the failure signature —
        // deliberate pauses produce isolated rows, not clusters.
        assertFalse(
            detected(
                exit(reason = lowMemory, at = 200_000L),
                exit(at = 230_000L, description = "stop com.example.myexodus due to from pid 4242"),
                exit(at = 290_000L, description = "stop com.example.myexodus due to from pid 4243"),
            ),
        )
    }

    @Test
    fun twoExitsOnly_notAStorm() {
        // Below the floor the fail-safe stands: a kill plus one routine death still counts.
        assertTrue(
            detected(
                exit(reason = lowMemory, at = 200_000L),
                exit(at = 230_000L, description = "stop com.example.myexodus due to from pid 4242"),
            ),
        )
    }

    @Test
    fun stormWindowEdges_areInclusive() {
        // Exits exactly 10 min on either side of the candidate are inside the window.
        assertFalse(
            detected(
                exit(reason = lowMemory, at = 100_000L),
                exit(at = 700_000L, description = "stop com.example.myexodus due to from pid 4242"),
                exit(reason = lowMemory, at = 1_300_000L),
            ),
        )
    }

    @Test
    fun stormWindowOneMillisecondOutside_detected() {
        assertTrue(
            detected(
                exit(reason = lowMemory, at = 99_999L),
                exit(at = 700_000L, description = "stop com.example.myexodus due to from pid 4242"),
                exit(reason = lowMemory, at = 1_300_001L),
            ),
        )
    }

    @Test
    fun isolatedRealStop_farFromAnyNoise_stillDetected() {
        // The genuine pause: one marker-free row, the only exit anywhere near it.
        assertTrue(
            detected(
                exit(at = 60_000L, description = "stop com.example.myexodus due to finished inst"),
                exit(at = 900_000L, description = "fully stop com.example.myexodus/10411 by user request"),
            ),
        )
    }

    @Test
    fun candidates_areMarkerFreeAndAboveWatermark() {
        // The list the FGS logs when every candidate was error-correlated (ADR #156).
        val exits = listOf(
            exit(at = 500L, description = "remove task"),
            exit(at = 2_000L, description = "stop com.example.myexodus due to start instr"),
            exit(at = 3_000L, description = "remove task"),
        )
        assertEquals(
            listOf(3_000L),
            UserStoppedDetector.userStopCandidates(exits, watermark).map { it.timestampEpochMillis },
        )
    }

    // --- latestUserStop (ADR #160's pause clip) --------------------------------------------

    @Test
    fun latestUserStop_picksTheNewestGenuineStop() {
        val exits = listOf(
            exit(at = 2_000L, description = "remove task"),
            exit(at = 900_000L, description = "fully stop com.example.myexodus/10411 by user request"),
            exit(at = 60_000L, description = "remove task"),
        )

        assertEquals(
            900_000L,
            UserStoppedDetector.latestUserStop(exits, watermark)?.timestampEpochMillis,
        )
    }

    @Test
    fun latestUserStop_skipsErrorCorrelatedCandidates() {
        // An ANR-era kill 8 s after the error row is exempt (ADR #156) — the clip must pass
        // over it and land on the earlier genuine stop, never treat it as a pause boundary.
        val exits = listOf(
            exit(at = 100_000L, description = "remove task"),
            exit(reason = ApplicationExitInfo.REASON_ANR, at = 850_000L),
            exit(at = 858_000L, description = "stop com.example.myexodus due to from pid 4242"),
        )

        assertEquals(
            100_000L,
            UserStoppedDetector.latestUserStop(exits, watermark)?.timestampEpochMillis,
        )
    }

    @Test
    fun latestUserStop_nullWhenOnlyErrorCorrelatedOrPreWatermark() {
        // The flagged-era shape: every would-be stop is a storm escape.
        val exits = listOf(
            exit(at = 500L, description = "remove task"),
            exit(reason = ApplicationExitInfo.REASON_CRASH, at = 850_000L),
            exit(at = 858_000L, description = "remove task"),
        )

        assertNull(UserStoppedDetector.latestUserStop(exits, watermark))
    }

    @Test
    fun latestUserStop_agreesWithWasUserStopped() {
        // The two judgments share one predicate set; a disagreement would let the detector
        // flag a pause the credit clip does not honor, or vice versa.
        val cases = listOf(
            listOf(exit(at = 900_000L, description = "remove task")),
            listOf(
                exit(reason = ApplicationExitInfo.REASON_ANR, at = 850_000L),
                exit(at = 858_000L, description = "remove task"),
            ),
            listOf(exit(at = 500L, description = "remove task")),
        )
        cases.forEach { exits ->
            assertEquals(
                UserStoppedDetector.wasUserStopped(exits, watermark),
                UserStoppedDetector.latestUserStop(exits, watermark) != null,
            )
        }
    }
}
