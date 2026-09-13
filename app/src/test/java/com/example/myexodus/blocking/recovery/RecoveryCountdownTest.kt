package com.example.myexodus.blocking.recovery

import com.example.myexodus.data.db.entity.RecoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Countdown freeze semantics (blocking-core plan §8.3 + §12 recovery tests): process-death
 * recreation reproduces the same remaining within a boot session; a simulated large clock
 * jump triggers the time-anomaly freeze instead of silently altering the countdown.
 */
class RecoveryCountdownTest {

    private val wall = 1_800_000_000_000L
    private val elapsed = 100_000L

    private fun state(bootSessionId: Long = elapsed) = RecoveryState(
        ruleId = "r-1",
        deadlineWallClockUtcMillis = wall + 60_000L,
        deadlineElapsedRealtimeThisBoot = elapsed + 60_000L,
        bootSessionId = bootSessionId,
    )

    @Test
    fun firstTick_establishesBaseline() {
        val countdown = RecoveryCountdownTracker()
        val result = countdown.tick(state(), sameBootSession = true, nowWallClockUtcMillis = wall, nowElapsedRealtimeMillis = elapsed)
        assertEquals(RecoveryCountdown.Counting(60L), result)
    }

    @Test
    fun steadyTicks_decrementOnElapsedClock() {
        val countdown = RecoveryCountdownTracker()
        countdown.tick(state(), true, wall, elapsed)
        assertEquals(RecoveryCountdown.Counting(50L), countdown.tick(state(), true, wall + 10_000L, elapsed + 10_000L))
        assertEquals(RecoveryCountdown.Counting(40L), countdown.tick(state(), true, wall + 20_000L, elapsed + 20_000L))
    }

    @Test
    fun processDeathRecreation_sameRemainingWithinBoot() {
        // §12 (a): two independent holders over the same persisted state + instant agree.
        val persisted = state()
        val t1 = RecoveryCountdownTracker().tick(persisted, true, wall, elapsed + 15_000L)
        val t2 = RecoveryCountdownTracker().tick(persisted, true, wall, elapsed + 15_000L)
        assertEquals(RecoveryCountdown.Counting(45L), t1)
        assertEquals(t1, t2)
    }

    @Test
    fun clockRollback_freezesAtLastKnownGood() {
        // §12 (c): wall clock rolled back 2h mid-countdown (same boot — elapsed unaffected).
        val countdown = RecoveryCountdownTracker()
        assertEquals(RecoveryCountdown.Counting(50L), countdown.tick(state(), true, wall, elapsed + 10_000L))
        val frozen = countdown.tick(state(), true, wall - 7_200_000L, elapsed + 12_000L)
        assertTrue("Expected Frozen, got $frozen", frozen is RecoveryCountdown.Frozen)
        assertEquals(50L, (frozen as RecoveryCountdown.Frozen).displayedRemainingSeconds)
        // The authoritative (elapsed) countdown keeps running: the hatch stays reachable.
        assertEquals(48L, frozen.unlockInSeconds)
        // Subsequent ticks stay frozen at the same last-known-good.
        val stillFrozen = countdown.tick(state(), true, wall - 7_200_000L, elapsed + 14_000L)
        assertTrue("Expected Frozen, got $stillFrozen", stillFrozen is RecoveryCountdown.Frozen)
        assertEquals(50L, (stillFrozen as RecoveryCountdown.Frozen).displayedRemainingSeconds)
    }

    @Test
    fun forwardClockJump_freezesAtLastKnownGood() {
        val countdown = RecoveryCountdownTracker()
        assertEquals(RecoveryCountdown.Counting(50L), countdown.tick(state(), true, wall, elapsed + 10_000L))
        val frozen = countdown.tick(state(), true, wall + 3_600_000L, elapsed + 12_000L)
        assertTrue("Expected Frozen, got $frozen", frozen is RecoveryCountdown.Frozen)
        assertEquals(50L, (frozen as RecoveryCountdown.Frozen).displayedRemainingSeconds)
    }

    @Test
    fun deadlinePassed_returnsZeroNotFrozen() {
        val countdown = RecoveryCountdownTracker()
        countdown.tick(state(), true, wall, elapsed)
        assertEquals(RecoveryCountdown.Counting(0L), countdown.tick(state(), true, wall - 7_200_000L, elapsed + 61_000L))
    }

    @Test
    fun afterReboot_countdownUsesWallClock() {
        // Simulated reboot (§12 (b)): fresh elapsed (3s) is below the stored boot-session id
        // (100s) -> wall clock is authoritative; the countdown restarts from the wall deadline.
        val countdown = RecoveryCountdownTracker()
        val result = countdown.tick(state(), sameBootSession = false, nowWallClockUtcMillis = wall + 30_000L, nowElapsedRealtimeMillis = 3_000L)
        assertEquals(RecoveryCountdown.Counting(30L), result)
    }
}
