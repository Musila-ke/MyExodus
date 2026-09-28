package com.example.myexodus.blocking.usage

import com.example.myexodus.blocking.ProcessExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Repair of the accounting lost to a process death (enforcement plan v3 §5 D1/D3). Pure JVM:
 * every clock is an argument, so the floor, the no-cap ruling, the midnight clip and the event
 * pairing are pinned without a device or a UsageStatsManager.
 */
class UsageGapRepairTest {

    private val midnight = 1_000_000_000_000L

    // --- latestExitAfter -------------------------------------------------------------------

    @Test
    fun latestExitAfter_ignoresExitsBeforeTheWatermark() {
        val exits = listOf(ProcessExit(REASON_OTHER, midnight - 5_000), ProcessExit(REASON_CRASH, midnight))

        assertNull(UsageGapRepair.latestExitAfter(exits, watermarkEpochMillis = midnight))
    }

    @Test
    fun latestExitAfter_picksTheMostRecentAcrossReasons() {
        val exits = listOf(
            ProcessExit(REASON_CRASH, midnight + 1_000),
            ProcessExit(REASON_OTHER, midnight + 9_000),
            ProcessExit(REASON_ANR, midnight + 4_000),
        )

        assertEquals(midnight + 9_000, UsageGapRepair.latestExitAfter(exits, midnight)?.timestampEpochMillis)
    }

    // --- repairWindow ----------------------------------------------------------------------

    @Test
    fun repairWindow_spansDeathToProcessStart() {
        val window = UsageGapRepair.repairWindow(
            exitEpochMillis = midnight + 60_000,
            processStartEpochMillis = midnight + 600_000,
            localMidnightEpochMillis = midnight,
        )

        assertEquals(midnight + 60_000, window!!.first)
        assertEquals(midnight + 600_000, window.last + 1)
    }

    @Test
    fun repairWindow_startsAtTheLastObservationSoAFreezePrefixIsIncluded() {
        // X6532-measured class (2026-09-28): freezer SIGSTOP last row 06:00:17, watchdog ANR
        // kill 06:02:41, restart 06:03:13 — the death-anchored window left the ~156 s frozen
        // prefix permanently uncounted. v3 §5 D3's span is (last_credited_wall_ms, now].
        val window = UsageGapRepair.repairWindow(
            exitEpochMillis = midnight + 200_000,
            processStartEpochMillis = midnight + 320_000,
            localMidnightEpochMillis = midnight,
            observedUntilEpochMillis = midnight + 60_000,
        )

        assertEquals(midnight + 60_000, window!!.first)
        assertEquals(midnight + 320_000, window.last + 1)
    }

    @Test
    fun repairWindow_neverStartsAfterTheDeathWhenTheObservationIsNewer() {
        val window = UsageGapRepair.repairWindow(
            exitEpochMillis = midnight + 200_000,
            processStartEpochMillis = midnight + 320_000,
            localMidnightEpochMillis = midnight,
            observedUntilEpochMillis = midnight + 300_000,
        )

        assertEquals(midnight + 200_000, window!!.first)
    }

    @Test
    fun repairWindow_stillClipsAnObservationFromYesterdayToMidnight() {
        val window = UsageGapRepair.repairWindow(
            exitEpochMillis = midnight + 30_000,
            processStartEpochMillis = midnight + 200_000,
            localMidnightEpochMillis = midnight,
            observedUntilEpochMillis = midnight - 60_000,
        )

        assertEquals(midnight, window!!.first)
    }

    @Test
    fun repairWindow_floorMeasuresTheExtendedSpan() {
        // A short death window is worth repairing once the observed prefix pushes the span
        // past the floor; the overlap check keeps that credit off already-credited time.
        assertNotNull(
            UsageGapRepair.repairWindow(
                exitEpochMillis = midnight + 15_000,
                processStartEpochMillis = midnight + 25_000,
                localMidnightEpochMillis = midnight,
                observedUntilEpochMillis = midnight,
            ),
        )
        // Still below the floor = ordinary transition jitter, observation included.
        assertNull(
            UsageGapRepair.repairWindow(
                exitEpochMillis = midnight + 15_000,
                processStartEpochMillis = midnight + 19_999,
                localMidnightEpochMillis = midnight,
                observedUntilEpochMillis = midnight,
            ),
        )
    }

    @Test
    fun repairWindow_rejectsASpanShorterThanTheFloor() {
        assertNull(
            UsageGapRepair.repairWindow(
                exitEpochMillis = midnight + 60_000,
                processStartEpochMillis = midnight + 60_000 + UsageGapRepair.MIN_GAP_MS - 1,
                localMidnightEpochMillis = midnight,
            ),
        )
    }

    @Test
    fun repairWindow_creditsADeadWindowFarBeyondSixHours() {
        // v3 §5 D3, verbatim: "No cap. A cap would re-introduce under-counting, which is the
        // defect class this delta exists to remove." The shipped 6 h cap refused exactly the
        // long OEM-freezer dead windows; the midnight clip is what keeps the credit on today.
        val window = UsageGapRepair.repairWindow(
            exitEpochMillis = midnight - 20 * 3_600_000L,
            processStartEpochMillis = midnight + 8 * 3_600_000L,
            localMidnightEpochMillis = midnight,
        )

        assertEquals(midnight, window!!.first)
        assertEquals(midnight + 8 * 3_600_000L, window.last + 1)
    }

    @Test
    fun repairWindow_clipsToLocalMidnightSoCreditLandsOnTodaysRow() {
        val window = UsageGapRepair.repairWindow(
            exitEpochMillis = midnight - 3_600_000,
            processStartEpochMillis = midnight + 600_000,
            localMidnightEpochMillis = midnight,
        )

        assertEquals(midnight, window!!.first)
    }

    @Test
    fun repairWindow_emptyWhenTheDeathPredatesMidnightAndStartIsBeforeIt() {
        assertNull(
            UsageGapRepair.repairWindow(
                exitEpochMillis = midnight - 3_600_000,
                processStartEpochMillis = midnight - 1_000,
                localMidnightEpochMillis = midnight,
            ),
        )
    }

    // --- foregroundMsWithin ----------------------------------------------------------------

    @Test
    fun foregroundMsWithin_sumsCompletedSessions() {
        val marks = listOf(
            UsageMark(midnight, resumed = true),
            UsageMark(midnight + 120_000, resumed = false),
        )

        assertEquals(120_000L, UsageGapRepair.foregroundMsWithin(marks, midnight, midnight + 600_000))
    }

    @Test
    fun foregroundMsWithin_clipsToTheWindowOnBothEnds() {
        val marks = listOf(
            UsageMark(midnight - 300_000, resumed = true),
            UsageMark(midnight + 900_000, resumed = false),
        )

        assertEquals(600_000L, UsageGapRepair.foregroundMsWithin(marks, midnight, midnight + 600_000))
    }

    @Test
    fun foregroundMsWithin_runsAnUnclosedSessionToTheWindowEnd() {
        val marks = listOf(UsageMark(midnight + 60_000, resumed = true))

        assertEquals(540_000L, UsageGapRepair.foregroundMsWithin(marks, midnight, midnight + 600_000))
    }

    @Test
    fun foregroundMsWithin_keepsTheFirstResumeAcrossSamePackageActivitySwitches() {
        // B_RESUMED arrives before A_PAUSED on an in-app activity switch; re-anchoring on the
        // second resume would drop the switch interval.
        val marks = listOf(
            UsageMark(midnight, resumed = true),
            UsageMark(midnight + 60_000, resumed = true),
            UsageMark(midnight + 90_000, resumed = false),
        )

        assertEquals(90_000L, UsageGapRepair.foregroundMsWithin(marks, midnight, midnight + 600_000))
    }

    @Test
    fun foregroundMsWithin_ignoresAPauseWithNoOpenSession() {
        val marks = listOf(UsageMark(midnight + 60_000, resumed = false))

        assertEquals(0L, UsageGapRepair.foregroundMsWithin(marks, midnight, midnight + 600_000))
    }

    @Test
    fun foregroundMsWithin_sortsUnorderedMarksBeforePairing() {
        val marks = listOf(
            UsageMark(midnight + 120_000, resumed = false),
            UsageMark(midnight, resumed = true),
        )

        assertEquals(120_000L, UsageGapRepair.foregroundMsWithin(marks, midnight, midnight + 600_000))
    }

    // --- uncoveredMs (§8.9 overlap avoidance) ----------------------------------------------

    @Test
    fun uncoveredMs_noCoverage_isTheWholeWindow() {
        assertEquals(600_000L, UsageGapRepair.uncoveredMs(emptyList(), midnight, midnight + 600_000))
    }

    @Test
    fun uncoveredMs_fullyCovered_isZero() {
        assertEquals(
            0L,
            UsageGapRepair.uncoveredMs(
                listOf(midnight - 1_000 until midnight + 700_000),
                midnight,
                midnight + 600_000,
            ),
        )
    }

    @Test
    fun uncoveredMs_partialCoverage_returnsTheRemainder() {
        // Covered [100_000, 300_000) of a [0, 600_000) window → 400_000 uncovered.
        assertEquals(
            400_000L,
            UsageGapRepair.uncoveredMs(listOf(midnight + 100_000 until midnight + 300_000), midnight, midnight + 600_000),
        )
    }

    @Test
    fun uncoveredMs_overlappingRanges_measureTheUnionOnce() {
        assertEquals(
            400_000L,
            UsageGapRepair.uncoveredMs(
                listOf(
                    midnight + 100_000 until midnight + 300_000,
                    midnight + 200_000 until midnight + 300_000,
                ),
                midnight,
                midnight + 600_000,
            ),
        )
    }

    @Test
    fun uncoveredMs_adjacentRanges_doNotDoubleCountTheSeam() {
        assertEquals(
            400_000L,
            UsageGapRepair.uncoveredMs(
                listOf(midnight until midnight + 100_000, midnight + 100_000 until midnight + 200_000),
                midnight,
                midnight + 600_000,
            ),
        )
    }

    @Test
    fun uncoveredMs_rangesOutsideTheWindow_areClipped() {
        // Two ranges hanging off both ends of the window together cover it entirely.
        assertEquals(
            0L,
            UsageGapRepair.uncoveredMs(
                listOf(midnight - 500_000 until midnight + 300_000, midnight + 100_000 until midnight + 900_000),
                midnight + 50_000,
                midnight + 550_000,
            ),
        )
    }

    @Test
    fun uncoveredMs_zeroLengthRanges_coverNothing() {
        // A MIGRATION_CARRYOVER row (elapsed_start == elapsed_end) owns no interval.
        assertEquals(
            600_000L,
            UsageGapRepair.uncoveredMs(listOf(midnight until midnight), midnight, midnight + 600_000),
        )
        assertEquals(0L, UsageGapRepair.uncoveredMs(emptyList(), midnight, midnight))
    }

    private companion object {
        const val REASON_OTHER = 13
        const val REASON_CRASH = 4
        const val REASON_ANR = 6
    }
}
