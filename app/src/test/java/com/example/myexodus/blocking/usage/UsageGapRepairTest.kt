package com.example.myexodus.blocking.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Repair of accounting lost to an interruption (enforcement plan v3 §5 D1/D3; ADR #160's
 * credit-edge anchor). Pure JVM: every clock is an argument, so the floor, the no-cap ruling,
 * the midnight/boot clips and the event pairing are pinned without a device or a
 * UsageStatsManager.
 */
class UsageGapRepairTest {

    private val midnight = 1_000_000_000_000L

    /** This boot's wall origin — far enough before [midnight] to be inert in most tests. */
    private val bootOrigin = midnight - 10 * 3_600_000L

    // --- uncreditedWindow (ADR #160) --------------------------------------------------------

    @Test
    fun uncreditedWindow_spansLastCreditToProcessStart() {
        val window = UsageGapRepair.uncreditedWindow(
            lastCreditWallMs = midnight + 60_000,
            localMidnightEpochMillis = midnight,
            bootOriginWallMs = bootOrigin,
            resumeWallMs = midnight + 600_000,
        )

        assertEquals(midnight + 60_000, window!!.first)
        assertEquals(midnight + 600_000, window.last + 1)
    }

    @Test
    fun uncreditedWindow_noCreditToday_startsAtMidnight() {
        val window = UsageGapRepair.uncreditedWindow(
            lastCreditWallMs = null,
            localMidnightEpochMillis = midnight,
            bootOriginWallMs = bootOrigin,
            resumeWallMs = midnight + 600_000,
        )

        assertEquals(midnight, window!!.first)
    }

    @Test
    fun uncreditedWindow_creditFromYesterday_clipsToMidnight() {
        // The anchor query bounds itself at midnight, but a stale value must clip anyway —
        // the credit may only ever land on today's row.
        val window = UsageGapRepair.uncreditedWindow(
            lastCreditWallMs = midnight - 60_000,
            localMidnightEpochMillis = midnight,
            bootOriginWallMs = bootOrigin,
            resumeWallMs = midnight + 600_000,
        )

        assertEquals(midnight, window!!.first)
    }

    @Test
    fun uncreditedWindow_preBootCredit_startsAtTheBootOriginSoTheElapsedMapIsValid() {
        // §8.5: elapsed values from another boot are not comparable, so a boot-crossing
        // window's row starts at this frame's zero — the pre-boot share is not claimed.
        val window = UsageGapRepair.uncreditedWindow(
            lastCreditWallMs = midnight,
            localMidnightEpochMillis = midnight,
            bootOriginWallMs = midnight + 300_000,
            resumeWallMs = midnight + 600_000,
        )

        assertEquals(midnight + 300_000, window!!.first)
    }

    @Test
    fun uncreditedWindow_rejectsASpanShorterThanTheFloor() {
        assertNull(
            UsageGapRepair.uncreditedWindow(
                lastCreditWallMs = midnight + 60_000,
                localMidnightEpochMillis = midnight,
                bootOriginWallMs = bootOrigin,
                resumeWallMs =midnight + 60_000 + UsageGapRepair.MIN_GAP_MS - 1,
            ),
        )
        assertNotNull(
            UsageGapRepair.uncreditedWindow(
                lastCreditWallMs = midnight + 60_000,
                localMidnightEpochMillis = midnight,
                bootOriginWallMs = bootOrigin,
                resumeWallMs =midnight + 60_000 + UsageGapRepair.MIN_GAP_MS,
            ),
        )
    }

    @Test
    fun uncreditedWindow_endBeforeStart_isNull() {
        // The pause clip can hand back an end at or before the credit edge.
        assertNull(
            UsageGapRepair.uncreditedWindow(
                lastCreditWallMs = midnight + 600_000,
                localMidnightEpochMillis = midnight,
                bootOriginWallMs = bootOrigin,
                resumeWallMs =midnight + 60_000,
            ),
        )
    }

    @Test
    fun uncreditedWindow_creditsALongSpanFarBeyondSixHours() {
        // v3 §5 D3, verbatim: "No cap. A cap would re-introduce under-counting, which is the
        // defect class this delta exists to remove." The shipped 6 h cap refused exactly the
        // long OEM-freezer dead windows; the midnight clip is what keeps the credit on today.
        val window = UsageGapRepair.uncreditedWindow(
            lastCreditWallMs = midnight - 20 * 3_600_000L,
            localMidnightEpochMillis = midnight,
            bootOriginWallMs = midnight - 30 * 3_600_000L,
            resumeWallMs = midnight + 8 * 3_600_000L,
        )

        assertEquals(midnight, window!!.first)
        assertEquals(midnight + 8 * 3_600_000L, window.last + 1)
    }

    // --- uncreditedWindow's stop clip (the pause promise) ----------------------------------

    @Test
    fun uncreditedWindow_stopInsideTheSpan_clipsAtTheStop() {
        // A genuine pause's era starts at the stop: usage before it is claimable, none after.
        val window = UsageGapRepair.uncreditedWindow(
            lastCreditWallMs = midnight + 60_000,
            localMidnightEpochMillis = midnight,
            bootOriginWallMs = bootOrigin,
            resumeWallMs = midnight + 600_000,
            stopWallMs = midnight + 300_000,
        )

        assertEquals(midnight + 60_000, window!!.first)
        assertEquals(midnight + 300_000, window.last + 1)
    }

    @Test
    fun uncreditedWindow_stopOlderThanTheCreditEdge_doesNotShortenTheSpan() {
        // The live shape that made min(end, stop) wrong: a genuine stop at 12:43, the user
        // re-enabled, credits resumed and the credit edge now sits past the stop. The stop is
        // outside the tail — the span must survive, not null out (it did, pre-fix).
        val window = UsageGapRepair.uncreditedWindow(
            lastCreditWallMs = midnight + 500_000,
            localMidnightEpochMillis = midnight,
            bootOriginWallMs = bootOrigin,
            resumeWallMs = midnight + 600_000,
            stopWallMs = midnight + 100_000,
        )

        assertEquals(midnight + 500_000, window!!.first)
        assertEquals(midnight + 600_000, window.last + 1)
    }

    @Test
    fun uncreditedWindow_stopExactlyAtTheSpanStart_claimsNothing() {
        // Stop and credit edge coincide: the whole tail is pause-era. Claiming any of it
        // would credit paused time; the safe direction is nothing.
        assertNull(
            UsageGapRepair.uncreditedWindow(
                lastCreditWallMs = midnight + 300_000,
                localMidnightEpochMillis = midnight,
                bootOriginWallMs = bootOrigin,
                resumeWallMs = midnight + 600_000,
                stopWallMs = midnight + 300_000,
            ),
        )
    }

    @Test
    fun uncreditedWindow_paused_stopOlderThanTheSpanStart_claimsNothing() {
        // Flag set with a stop on record: a paused pipeline cannot have credited past the
        // stop, so a credit edge past it is incoherent — claim nothing rather than trust it.
        assertNull(
            UsageGapRepair.uncreditedWindow(
                lastCreditWallMs = midnight + 500_000,
                localMidnightEpochMillis = midnight,
                bootOriginWallMs = bootOrigin,
                resumeWallMs = midnight + 600_000,
                stopWallMs = midnight + 100_000,
                paused = true,
            ),
        )
    }

    @Test
    fun uncreditedWindow_stopAfterTheProcessStart_boundsAtTheProcessStart() {
        val window = UsageGapRepair.uncreditedWindow(
            lastCreditWallMs = midnight + 60_000,
            localMidnightEpochMillis = midnight,
            bootOriginWallMs = bootOrigin,
            resumeWallMs = midnight + 600_000,
            stopWallMs = midnight + 900_000,
        )

        assertEquals(midnight + 600_000, window!!.last + 1)
    }

    // --- repairWindow (the diagnostics silence path) ---------------------------------------

    @Test
    fun repairWindow_spansTheSilenceToThePassMoment() {
        val window = UsageGapRepair.repairWindow(
            silenceStartEpochMillis = midnight + 60_000,
            observedUntilEpochMillis = midnight + 600_000,
            localMidnightEpochMillis = midnight,
        )

        assertEquals(midnight + 60_000, window!!.first)
        assertEquals(midnight + 600_000, window.last + 1)
    }

    @Test
    fun repairWindow_clipsASilenceFromYesterdayToMidnight() {
        val window = UsageGapRepair.repairWindow(
            silenceStartEpochMillis = midnight - 60_000,
            observedUntilEpochMillis = midnight + 200_000,
            localMidnightEpochMillis = midnight,
        )

        assertEquals(midnight, window!!.first)
    }

    @Test
    fun repairWindow_rejectsASpanShorterThanTheFloor() {
        assertNull(
            UsageGapRepair.repairWindow(
                silenceStartEpochMillis = midnight + 60_000,
                observedUntilEpochMillis = midnight + 60_000 + UsageGapRepair.MIN_GAP_MS - 1,
                localMidnightEpochMillis = midnight,
            ),
        )
    }

    @Test
    fun repairWindow_emptyWhenTheWholeSilencePredatesMidnight() {
        assertNull(
            UsageGapRepair.repairWindow(
                silenceStartEpochMillis = midnight - 3_600_000,
                observedUntilEpochMillis = midnight - 1_000,
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
}
