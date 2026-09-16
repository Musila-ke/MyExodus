package com.example.myexodus.blocking.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Path A's session state machine (enforcement plan v2 §8 Step 4 / §6 steps 2–4, §12). Pure
 * JVM: the caller supplies the elapsed-realtime clock and the boot count, so the
 * reboot-mid-session discard and the incremental-delta contract are pinned without Android.
 */
class UsageSessionMachineTest {

    private val machine = UsageSessionMachine()

    private fun observe(pkg: String, tracked: Boolean, now: Long, boot: Int = 1, displayId: Int = 0) =
        machine.observeForeground(pkg, tracked, displayId, now, boot)

    @Test
    fun trackedForeground_opensSession() {
        val result = observe(APP, tracked = true, now = 1_000)
        assertTrue(result.opened)
        assertNull(result.closedTick)
        assertEquals(APP, machine.activePackage)
    }

    @Test
    fun untrackedForeground_neverOpens() {
        val result = observe(OTHER, tracked = false, now = 1_000)
        assertFalse(result.opened)
        assertNull(machine.activePackage)
    }

    @Test
    fun repeatedSamePackageObservation_keepsSessionAndAnchor() {
        observe(APP, tracked = true, now = 0)
        assertEquals(10_000L, machine.tick(10_000, bootCount = 1)!!.deltaMs)
        // In-app activity hop / dialog: the same tracked package again — no close, no reopen,
        // and the un-ticked delta since the last tick stays inside the session (Decision 4:
        // the ticker, not the next event, drives accounting).
        val reobserved = observe(APP, tracked = true, now = 12_000)
        assertFalse(reobserved.opened)
        assertNull(reobserved.closedTick)
        assertEquals(5_000L, machine.tick(15_000, bootCount = 1)!!.deltaMs)
    }

    @Test
    fun longDwell_creditsTheFullDuration_notAClampedAmount() {
        // §12 Path A dwell regression: v1 clamped credited time (5-minute wall-clock defect).
        // A 40-minute session must credit the full 40 minutes, however the ticks are spaced.
        observe(APP, tracked = true, now = 0)
        val tick = machine.tick(40 * 60_000L, bootCount = 1)!!
        assertEquals(40 * 60_000L, tick.deltaMs)
        // And a sparse-tick continuation still carries every millisecond: 40:00 → 45:30.
        observe(APP, tracked = true, now = 40 * 60_000L)
        assertEquals(5 * 60_000L + 30_000L, machine.tick(45 * 60_000L + 30_000L, bootCount = 1)!!.deltaMs)
    }

    @Test
    fun switchToAnotherTrackedPackage_closesWithFinalTickAndOpens() {
        observe(APP, tracked = true, now = 0)
        machine.tick(10_000, bootCount = 1)
        val switched = observe(OTHER, tracked = true, now = 12_000)
        // The closing session is credited up to the transition — never clamped away (§6 step 4).
        assertEquals(UsageSessionMachine.Tick(APP, 2_000, 0), switched.closedTick)
        assertTrue(switched.opened)
        assertEquals(OTHER, machine.activePackage)
        assertEquals(OTHER, machine.tick(20_000, bootCount = 1)!!.packageName)
    }

    @Test
    fun switchToUntrackedPackage_closesWithoutOpening() {
        observe(APP, tracked = true, now = 0)
        val switched = observe(OTHER, tracked = false, now = 4_000)
        assertEquals(4_000L, switched.closedTick!!.deltaMs)
        assertFalse(switched.opened)
        assertNull(machine.activePackage)
    }

    @Test
    fun rulePausedMidSession_samePackageObservationClosesWithoutReopening() {
        observe(APP, tracked = true, now = 0)
        val paused = observe(APP, tracked = false, now = 3_000)
        assertEquals(3_000L, paused.closedTick!!.deltaMs)
        assertFalse(paused.opened)
        assertNull(machine.activePackage)
    }

    @Test
    fun tickWithNoSession_isNull() {
        assertNull(machine.tick(10_000, bootCount = 1))
    }

    @Test
    fun bootCountMismatch_discardsSessionWithoutCrediting_reopensAnchoredNow() {
        observe(APP, tracked = true, now = 1_000, boot = 7)
        // Reboot mid-session: the first tick after the boot-count change credits nothing —
        // the elapsed clock spans two boots (§9: no negative or wrapped deltas possible).
        assertNull(machine.tick(50_000, bootCount = 8))
        // The session reopened anchored at the mismatch tick; the next delta starts there.
        assertEquals(1_000L, machine.tick(51_000, bootCount = 8)!!.deltaMs)
    }

    @Test
    fun close_returnsFinalDelta_thenSessionIsGone() {
        observe(APP, tracked = true, now = 1_000)
        assertEquals(UsageSessionMachine.Tick(APP, 6_500, 0), machine.close(7_500))
        assertNull(machine.activePackage)
        assertNull(machine.close(8_000))
    }

    @Test
    fun reset_dropsSessionWithoutCrediting() {
        observe(APP, tracked = true, now = 1_000)
        machine.reset()
        assertNull(machine.activePackage)
        assertNull(machine.tick(11_000, bootCount = 1))
        assertNull(machine.close(12_000))
    }

    @Test
    fun tickCarriesTheSessionsDisplay() {
        observe(APP, tracked = true, now = 0, displayId = 3)
        assertEquals(3, machine.tick(10_000, bootCount = 1)!!.displayId)
    }

    private companion object {
        const val APP = "com.instagram.android"
        const val OTHER = "com.tiktok.android"
    }
}
