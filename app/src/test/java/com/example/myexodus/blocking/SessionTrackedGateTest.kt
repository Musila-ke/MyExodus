package com.example.myexodus.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Path A's full session-open gate (finding #8, 2026-10-16): a usage session may only open when
 * the schedule doesn't wall the app, no limit wall is up, the rule is tracked, and — the
 * protection-paused promise — `requiresExplicitReEnable` is clear. "Time limits stay paused
 * until you re-enable" (protection_stopped_text) binds this path exactly as it binds the poll
 * path (plan v2 line 246, device-caught 2026-09-16); without this gate Path A credited paused
 * time into the budget while the paused card promised otherwise. Pure decision logic.
 */
class SessionTrackedGateTest {

    @Test
    fun pauseRequired_rejectsEveryOtherwiseOpenCell() {
        val bools = listOf(false, true)
        for (scheduleBlocked in bools) {
            for (limitWalled in bools) {
                for (ruleTracked in bools) {
                    assertFalse(
                        "pause must override scheduleBlocked=$scheduleBlocked " +
                            "limitWalled=$limitWalled ruleTracked=$ruleTracked",
                        BlockerAccessibilityService.sessionTracked(
                            scheduleBlocked, limitWalled, ruleTracked,
                        ) { true },
                    )
                }
            }
        }
    }

    @Test
    fun openOnlyWhen_allThreeGatesPass() {
        assertTrue(
            BlockerAccessibilityService.sessionTracked(false, false, true) { false },
        )
    }

    @Test
    fun eachSingleFailingGate_rejects() {
        assertFalse(BlockerAccessibilityService.sessionTracked(true, false, true) { false })
        assertFalse(BlockerAccessibilityService.sessionTracked(false, true, true) { false })
        assertFalse(BlockerAccessibilityService.sessionTracked(false, false, false) { false })
    }

    @Test
    fun rejectedGate_neverTouchesTheStatusStore() {
        // The flag is read through a lambda so an already-rejected package never reads the
        // status store (pre-unlock store reads must stay unreachable off the a11y path).
        var reads = 0
        BlockerAccessibilityService.sessionTracked(true, false, true) { reads++; true }
        assertEquals(0, reads)
        BlockerAccessibilityService.sessionTracked(false, false, true) { reads++; false }
        assertEquals(1, reads)
    }
}
