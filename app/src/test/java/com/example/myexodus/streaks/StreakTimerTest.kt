package com.example.myexodus.streaks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §12 RecoveryTimer evaluation matrix. The engine is a pure function driven with literal
 * readings, which subsumes the prescribed ShadowSystemClock simulation — no Robolectric or
 * shadow clock is needed to exercise same-session vs reboot transitions.
 */
class StreakTimerTest {

    private val legitT = StreakTimer.APP_RELEASE_EPOCH_MS + 5 * StreakTimer.DAY_MS

    @Test
    fun sameBootSession_accumulatesByMonotonicDelta_notWallDelta() {
        val anchor = anchor(tWall = legitT, rBoot = 1_000, bootCount = 7, current = 5_000)
        // Wall clock is 999s "ahead" of the anchor while elapsed advanced only 60s: the
        // monotonic delta is authoritative, the wall value is merely re-observed.
        val readings = TimeReadings(tWallMillis = legitT + 999_000, rBootMillis = 61_000, bootCount = 7)

        val result = StreakTimer.evaluate(anchor, readings) as StreakEvaluation.Advance
        assertEquals(65_000L, result.currentStreakElapsedMs)
        assertEquals(readings.tWallMillis, result.tWallLastObserved)
        assertEquals(readings.rBootMillis, result.rBootLastObserved)
        assertEquals(7L, result.bootCountLastObserved)
        assertEquals(null, result.anomaly)
    }

    @Test
    fun backwardWallClockInSession_logsInformational_butStillAccumulates() {
        val anchor = anchor(tWall = legitT, rBoot = 1_000, bootCount = 7, current = 5_000)
        val readings = TimeReadings(tWallMillis = legitT - 60_000, rBootMillis = 31_000, bootCount = 7)

        val result = StreakTimer.evaluate(anchor, readings) as StreakEvaluation.Advance
        assertEquals(35_000L, result.currentStreakElapsedMs)
        val anomaly = requireNotNull(result.anomaly)
        assertEquals(StreakAnomalyType.BACKWARD_CLOCK_IN_SESSION, anomaly.type)
        assertEquals(AnomalyResolution.ACCEPTED_MONOTONIC, anomaly.resolution)
        assertEquals(-60_000L, anomaly.deltaMs)
    }

    @Test
    fun rebootDetected_accumulatesByWallDelta_andLogsTheTransition() {
        val anchor = anchor(tWall = legitT, rBoot = 50_000, bootCount = 7, current = 5_000)
        val readings = TimeReadings(
            tWallMillis = legitT + StreakTimer.DAY_MS,
            rBootMillis = 500, // elapsed-reset: fresh boot
            bootCount = 8,
        )

        val result = StreakTimer.evaluate(anchor, readings) as StreakEvaluation.Advance
        assertEquals(5_000L + StreakTimer.DAY_MS, result.currentStreakElapsedMs)
        assertEquals(8L, result.bootCountLastObserved)
        val anomaly = requireNotNull(result.anomaly)
        assertEquals(StreakAnomalyType.REBOOT_WALL_DELTA, anomaly.type)
        assertEquals(AnomalyResolution.ACCEPTED_WALL_DELTA, anomaly.resolution)
        assertEquals(StreakTimer.DAY_MS, anomaly.deltaMs)
    }

    @Test
    fun backwardClockAfterReboot_isRejected_andNothingIsPersistable() {
        val anchor = anchor(tWall = legitT, rBoot = 50_000, bootCount = 7, current = 5_000)
        val readings = TimeReadings(tWallMillis = legitT - 3_600_000, rBootMillis = 500, bootCount = 8)

        val result = StreakTimer.evaluate(anchor, readings)
        assertTrue(result is StreakEvaluation.RejectedBackwardClock)
        val anomaly = (result as StreakEvaluation.RejectedBackwardClock).anomaly
        assertEquals(StreakAnomalyType.BACKWARD_CLOCK_AFTER_REBOOT, anomaly.type)
        assertEquals(AnomalyResolution.REJECTED_NO_ANCHOR_UPDATE, anomaly.resolution)
        assertEquals(-3_600_000L, anomaly.deltaMs)
    }

    @Test
    fun stuckBootCount_rebootIsNotMistakenForMonotonicAccumulation() {
        // Devices that report a constant/zero BOOT_COUNT: a reboot shows up only as
        // elapsed-realtime going backward. That must take the wall-delta path — reading it
        // as a same-session delta would SUBTRACT time from the accumulator.
        val zeroCase = StreakTimer.evaluate(
            anchor(tWall = legitT, rBoot = 50_000, bootCount = 0, current = 10_000),
            TimeReadings(tWallMillis = legitT + 5_000, rBootMillis = 100, bootCount = 0),
        ) as StreakEvaluation.Advance
        assertEquals(15_000L, zeroCase.currentStreakElapsedMs)
        assertEquals(StreakAnomalyType.REBOOT_WALL_DELTA, zeroCase.anomaly!!.type)

        val stuckCase = StreakTimer.evaluate(
            anchor(tWall = legitT, rBoot = 50_000, bootCount = 7, current = 10_000),
            TimeReadings(tWallMillis = legitT + 5_000, rBootMillis = 100, bootCount = 7),
        ) as StreakEvaluation.Advance
        assertEquals(15_000L, stuckCase.currentStreakElapsedMs)
    }

    @Test
    fun relapseRollback_isInvalidInput() {
        assertTrue(StreakTimer.isRelapseTimestampValid(recoveryStartAt = legitT, tWallAtLogging = legitT))
        assertTrue(
            StreakTimer.isRelapseTimestampValid(recoveryStartAt = legitT, tWallAtLogging = legitT + 1),
        )
        assertFalse(
            StreakTimer.isRelapseTimestampValid(recoveryStartAt = legitT, tWallAtLogging = legitT - 1),
        )
    }

    @Test
    fun firstAnchor_rejectsPreBuildAndFarFuture_andAcceptsLegit() {
        val legit = StreakTimer.firstAnchor(
            TimeReadings(tWallMillis = legitT, rBootMillis = 1_000, bootCount = 3),
        )
        assertEquals(
            FirstAnchor.Anchored(legitT, 1_000, 3),
            legit,
        )

        val preBuild = StreakTimer.firstAnchor(
            TimeReadings(
                tWallMillis = StreakTimer.APP_RELEASE_EPOCH_MS - 2 * StreakTimer.DAY_MS,
                rBootMillis = 1_000,
                bootCount = 3,
            ),
        ) as FirstAnchor.Implausible
        assertEquals(StreakAnomalyType.FIRST_ANCHOR_IMPLAUSIBLE, preBuild.anomaly.type)
        assertEquals(AnomalyResolution.DEFERRED_NO_ANCHOR_WRITE, preBuild.anomaly.resolution)

        val farFuture = StreakTimer.firstAnchor(
            TimeReadings(
                tWallMillis = StreakTimer.APP_RELEASE_EPOCH_MS + 11L * 365 * StreakTimer.DAY_MS,
                rBootMillis = 1_000,
                bootCount = 3,
            ),
        )
        assertTrue(farFuture is FirstAnchor.Implausible)
    }

    @Test
    fun supersededStreak_isDiscardedNotEvaluated() {
        val superseded = anchor(
            tWall = legitT,
            rBoot = 1_000,
            bootCount = 7,
            current = 5_000,
            relapsedAt = legitT + 1_000,
        )
        val result = StreakTimer.evaluate(
            superseded,
            TimeReadings(tWallMillis = legitT + StreakTimer.DAY_MS, rBootMillis = 2_000, bootCount = 7),
        )
        assertEquals(StreakEvaluation.Superseded, result)
    }

    private fun anchor(
        tWall: Long,
        rBoot: Long,
        bootCount: Long,
        current: Long,
        relapsedAt: Long? = null,
    ) = StreakAnchor(
        tWallLastObserved = tWall,
        rBootLastObserved = rBoot,
        bootCountLastObserved = bootCount,
        currentStreakElapsedMs = current,
        rowVersion = 0,
        relapsedAt = relapsedAt,
    )
}
