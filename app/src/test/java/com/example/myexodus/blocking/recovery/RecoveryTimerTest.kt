package com.example.myexodus.blocking.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure dual-clock math (blocking-core plan §8.3). */
class RecoveryTimerTest {

    @Test
    fun remainingSeconds_sameBoot_usesElapsedClock() {
        // Wall clock is 1h off from the deadline, but within the boot session the elapsed
        // clock is authoritative: 100s deadline, 90s elapsed -> 10s.
        val remaining = RecoveryTimer.remainingSeconds(
            deadlineWallClockUtcMillis = 1_800_000_000_000L,
            deadlineElapsedRealtimeThisBoot = 100_000L,
            sameBootSession = true,
            nowWallClockUtcMillis = 1_799_640_000_000L,
            nowElapsedRealtimeMillis = 90_000L,
        )
        assertTrue(remaining == 10L)
    }

    @Test
    fun remainingSeconds_afterReboot_usesWallClock() {
        // Rebooted: the persisted elapsed deadline is meaningless, wall clock is authority.
        val remaining = RecoveryTimer.remainingSeconds(
            deadlineWallClockUtcMillis = 1_800_000_000_000L,
            deadlineElapsedRealtimeThisBoot = 100_000L,
            sameBootSession = false,
            nowWallClockUtcMillis = 1_799_999_960_000L,
            nowElapsedRealtimeMillis = 3_000L,
        )
        assertTrue(remaining == 40L)
    }

    @Test
    fun remainingSeconds_clampsAtZero() {
        assertTrue(
            RecoveryTimer.remainingSeconds(
                1_800_000_000_000L, 100_000L, true, 1_800_000_000_000L, 200_000L,
            ) == 0L,
        )
        assertTrue(
            RecoveryTimer.remainingSeconds(
                1_800_000_000_000L, 100_000L, false, 1_800_000_000_500L, 3_000L,
            ) == 0L,
        )
    }

    @Test
    fun sameBootSession_trueWithinTheSession() {
        assertTrue(RecoveryTimer.sameBootSession(5_000L, 5_000L))
        assertTrue(RecoveryTimer.sameBootSession(5_000L, 42_000L))
    }

    @Test
    fun sameBootSession_falseAfterReboot() {
        // Elapsed-realtime reset at boot: a fresh sample below the stored id means the
        // device rebooted since the recovery session was created.
        assertFalse(RecoveryTimer.sameBootSession(5_000L, 3_000L))
    }

    @Test
    fun detectClockJump_steadyDecrement_isNotAnAnomaly() {
        assertFalse(
            RecoveryTimer.detectClockJump(
                previousRemainingSeconds = 50,
                previousNowWallClockUtcMillis = 1_800_000_000_000L,
                currentRemainingSeconds = 40,
                currentNowWallClockUtcMillis = 1_800_000_010_000L,
                toleranceMillis = 5_000,
            ),
        )
    }

    @Test
    fun detectClockJump_backwardWallRollback_detected() {
        // Same boot: remaining derives from the elapsed clock, so a 2h manual rollback of
        // the wall clock must not change it — diverging from the expected decrement.
        assertTrue(
            RecoveryTimer.detectClockJump(
                previousRemainingSeconds = 50,
                previousNowWallClockUtcMillis = 1_800_000_000_000L,
                currentRemainingSeconds = 49,
                currentNowWallClockUtcMillis = 1_799_992_800_000L, // 2h back
                toleranceMillis = 5_000,
            ),
        )
    }

    @Test
    fun detectClockJump_forwardJump_detected() {
        // Large forward NTP jump with an unchanged elapsed-derived remaining.
        assertTrue(
            RecoveryTimer.detectClockJump(
                previousRemainingSeconds = 50,
                previousNowWallClockUtcMillis = 1_800_000_000_000L,
                currentRemainingSeconds = 49,
                currentNowWallClockUtcMillis = 1_800_003_600_000L, // 1h forward
                toleranceMillis = 5_000,
            ),
        )
    }

    @Test
    fun detectClockJump_deadlineReached_isNeverAnAnomaly() {
        assertFalse(
            RecoveryTimer.detectClockJump(
                previousRemainingSeconds = 50,
                previousNowWallClockUtcMillis = 1_800_000_000_000L,
                currentRemainingSeconds = 0,
                currentNowWallClockUtcMillis = 1_799_992_800_000L,
                toleranceMillis = 5_000,
            ),
        )
    }
}
