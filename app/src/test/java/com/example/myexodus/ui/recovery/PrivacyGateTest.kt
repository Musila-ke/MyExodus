package com.example.myexodus.ui.recovery

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6's 60 s memory window as a pure function of two clock readings — the plan asked for a
 * fake-clock test of the gate timing, and this is that clock, without a device: the window is
 * memory-only, so "still open?" must be decided entirely from `elapsedRealtime` readings and
 * a process death (both readings zero) must lock.
 */
class PrivacyGateTest {

    @After
    fun tearDown() = PrivacyGate.reset()

    @Test
    fun window_isClosedBeforeAnyUnlock() {
        PrivacyGate.reset()

        assertFalse(PrivacyGate.isUnlocked(nowElapsedMs = 1_000_000L))
    }

    @Test
    fun freshUnlock_opensTheWindowImmediately() {
        PrivacyGate.markUnlocked(nowElapsedMs = 1_000_000L)

        assertTrue(PrivacyGate.isUnlocked(nowElapsedMs = 1_000_000L))
    }

    @Test
    fun window_expiresAfterSixtySeconds_exclusiveBound() {
        PrivacyGate.markUnlocked(nowElapsedMs = 1_000_000L)

        assertTrue(
            "one unlock covers every gated surface for the full 60 s",
            PrivacyGate.isUnlocked(nowElapsedMs = 1_000_000L + PrivacyGate.WINDOW_MS),
        )
        assertFalse(
            "past 60 s the next gated surface prompts again",
            PrivacyGate.isUnlocked(nowElapsedMs = 1_000_000L + PrivacyGate.WINDOW_MS + 1),
        )
    }

    @Test
    fun aClockReadingThatPredatesTheUnlock_readsAsLocked() {
        PrivacyGate.markUnlocked(nowElapsedMs = 1_000_000L)

        assertFalse(
            "a reboot zeroes elapsedRealtime — the negative span must not count as open",
            PrivacyGate.isUnlocked(nowElapsedMs = 5L),
        )
    }

    @Test
    fun reset_relocksImmediately() {
        PrivacyGate.markUnlocked(nowElapsedMs = 1_000_000L)

        PrivacyGate.reset()

        assertFalse(PrivacyGate.isUnlocked(nowElapsedMs = 1_000_000L))
    }
}
