package com.example.myexodus.blocking.usage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §8.8's maintenance policy: the clock-anomaly test that gates pruning, and the cross-run
 * state carrying its cool-down. The plan's security review names the risk directly —
 * "retention pruning deletes evidence right after a clock jump" — so this truth table is the
 * guard: a forward disparity past the threshold must suspend; a first run, a reboot, or a
 * backwards clock move must not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LedgerMaintenanceTest {

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    // --- §8.8 clock-anomaly detector ---

    @Test
    fun noPreviousFrame_isNeverAJump() {
        assertFalse(LedgerRetention.clockJumpDetected(0L, 0L, day, 5 * day))
        assertFalse(LedgerRetention.clockJumpDetected(day, 0L, 2 * day, 5 * day))
        assertFalse(LedgerRetention.clockJumpDetected(0L, day, 2 * day, 5 * day))
    }

    @Test
    fun normalProgression_isNeverAJump_evenWhenTheRunOverran() {
        // Wall and monotonic both advanced one day; and a run that overran by hours still
        // shows no disparity.
        assertFalse(LedgerRetention.clockJumpDetected(day, 5 * day, 2 * day, 6 * day))
        assertFalse(LedgerRetention.clockJumpDetected(day, 5 * day, 2 * day, 9 * day))
    }

    @Test
    fun disparityExactlyAtTheThreshold_isNotYetAJump_oneMillisecondPast_is() {
        val base = 5 * day
        assertFalse(
            LedgerRetention.clockJumpDetected(day, base, day + base + LedgerRetention.CLOCK_JUMP_THRESHOLD_MS, 2 * base),
        )
        assertTrue(
            LedgerRetention.clockJumpDetected(day, base, day + base + LedgerRetention.CLOCK_JUMP_THRESHOLD_MS + 1, 2 * base),
        )
    }

    @Test
    fun aForwardJump_isDetected() {
        // A day of monotonic time, but the wall clock shows a day and 7 h — a 7 h disparity.
        assertTrue(LedgerRetention.clockJumpDetected(day, 5 * day, 2 * day + 7 * hour, 6 * day))
    }

    @Test
    fun backwardsWallClockNeverReports_becauseItCannotMakeRecentRowsLookOld() {
        assertFalse(LedgerRetention.clockJumpDetected(2 * day, 6 * day, day, 7 * day))
    }

    @Test
    fun monotonicResetAcrossAReboot_isNeverAJump() {
        // Elapsed restarts at a boot: the frames are not comparable, so nothing is asserted
        // even though wall time clearly advanced.
        assertFalse(LedgerRetention.clockJumpDetected(day, 30 * day, 31 * day, 2 * hour))
    }

    @Test
    fun aLongPowerOff_readsAsAJump_deliberately_theSafeDirection() {
        // Elapsed stopped while the phone was off; by construction that is indistinguishable
        // from a jump and takes the same conservative path — one skipped sweep.
        assertTrue(LedgerRetention.clockJumpDetected(day, 5 * day, 2 * day, 5 * day + hour))
    }

    // --- the run decision and its cool-down ---

    private fun state() = LedgerMaintenanceState(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun firstRun_prunes_andRecordsItsFrame() {
        val state = state()

        assertEquals(MaintenanceOutcome.PRUNE, state.beginRun(100 * day, 5 * day))

        assertEquals(100 * day, state.lastWallClockMs)
        assertEquals(5 * day, state.lastElapsedRealtimeMs)
    }

    @Test
    fun aRebootBetweenRuns_stillPrunes() {
        val state = state()
        state.beginRun(100 * day, 5 * day)

        // A week later, but elapsed time restarted hours ago: a reboot, not a clock anomaly.
        assertEquals(MaintenanceOutcome.PRUNE, state.beginRun(107 * day, 3 * hour))
    }

    @Test
    fun aJump_suspends_itsNextRunInsideTheWindow_stillSuspends_andTheBoundaryResumes() {
        val state = state()
        val t0 = 100 * day
        assertEquals(MaintenanceOutcome.PRUNE, state.beginRun(t0, 5 * day))

        // 24 h later, but the wall clock is 7 h ahead of the monotonic one.
        val jumpWall = t0 + day + 7 * hour
        assertEquals(MaintenanceOutcome.SUSPENDED, state.beginRun(jumpWall, 6 * day))

        // 12 h on: no new jump, but inside the cool-down — and the suspended run's own frame
        // is what the next comparison uses.
        assertEquals(MaintenanceOutcome.SUSPENDED, state.beginRun(jumpWall + 12 * hour, 6 * day + 12 * hour))
        assertEquals(jumpWall + 12 * hour, state.lastWallClockMs)

        // Exactly at the cool-down boundary the sweep resumes.
        assertEquals(MaintenanceOutcome.PRUNE, state.beginRun(jumpWall + day, 7 * day))
    }

    @Test
    fun aSecondJumpWhileSuspended_restartsTheCoolDownFromTheNewDetection() {
        val state = state()
        state.beginRun(100 * day, 5 * day)
        val jump1 = 100 * day + day + 7 * hour
        assertEquals(MaintenanceOutcome.SUSPENDED, state.beginRun(jump1, 6 * day))

        // An hour later the wall clock jumps another 7 h: the cool-down restarts from here.
        val jump2 = jump1 + hour + 7 * hour
        assertEquals(MaintenanceOutcome.SUSPENDED, state.beginRun(jump2, 6 * day + hour))
        assertEquals(MaintenanceOutcome.SUSPENDED, state.beginRun(jump2 + day - 1, 7 * day - 1))
        assertEquals(MaintenanceOutcome.PRUNE, state.beginRun(jump2 + day, 7 * day))
    }

    // --- the frequent trigger's frequency guard ---

    @Test
    fun maintenanceDue_freshInstall_isDue_immediately() {
        // No frame recorded (0), so any real wall clock is past the interval by decades.
        assertTrue(state().maintenanceDue(1_700_000_000_000L))
    }

    @Test
    fun maintenanceDue_respectsTheInterval_boundaryInclusive() {
        val state = state()
        state.beginRun(100 * day, 5 * day)

        val interval = LedgerMaintenanceState.MIN_INTERVAL_MS
        assertFalse(state.maintenanceDue(100 * day + interval - 1))
        assertTrue(state.maintenanceDue(100 * day + interval))
    }

    @Test
    fun maintenanceDue_aSuspendedRun_stillPacesTheTrigger() {
        val state = state()
        state.beginRun(100 * day, 5 * day)
        // 7 h of wall disparity: suspended, but it happened — the trigger must not re-run
        // every 30 s trying to get a different answer.
        assertEquals(MaintenanceOutcome.SUSPENDED, state.beginRun(101 * day + 7 * hour, 6 * day))

        assertFalse(state.maintenanceDue(101 * day + 7 * hour + hour))
        assertTrue(state.maintenanceDue(101 * day + 7 * hour + LedgerMaintenanceState.MIN_INTERVAL_MS))
    }
}
