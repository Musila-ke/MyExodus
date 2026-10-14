package com.example.myexodus.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Stress slice A6 (backlog `project_stress_test_backlog`): the ENTIRE 2×2×6 `decide()` state
 * space pinned as a hand-written truth table — every cell commits to an expected Action, so any
 * future edit to the policy fails loudly on the cell it changed instead of sliding through the
 * scenario tests. Expected values come from the documented contract (KDoc on `decide`), never
 * re-derived from the implementation.
 *
 * Contract restated: START ⇒ live rules ∧ consent ∧ state ∈ {STOPPED, FAILED, REVOKED};
 * STOP ⇒ ¬live rules ∧ state ∈ {STARTING, ACTIVE, DEGRADED}; otherwise NONE.
 */
class VpnLifecycleStressTest {

    private val start = VpnLifecycle.Action.START
    private val stop = VpnLifecycle.Action.STOP
    private val none = VpnLifecycle.Action.NONE

    @Test
    fun exhaustiveMatrix_pinsAllTwentyFourCells() {
        // (hasRules, consented, state) → expected. Hand-written; do not regenerate from the impl.
        val table = mapOf(
            // ---- live rules + consent: start the down states, leave the living alone ----
            Triple(true, true, VpnEngineState.STOPPED) to start,
            Triple(true, true, VpnEngineState.STARTING) to none,
            Triple(true, true, VpnEngineState.ACTIVE) to none,
            Triple(true, true, VpnEngineState.DEGRADED) to none,
            Triple(true, true, VpnEngineState.FAILED) to start,
            Triple(true, true, VpnEngineState.REVOKED) to start,
            // ---- live rules, no consent: never start, never stop a running tunnel ----
            Triple(true, false, VpnEngineState.STOPPED) to none,
            Triple(true, false, VpnEngineState.STARTING) to none,
            Triple(true, false, VpnEngineState.ACTIVE) to none,
            Triple(true, false, VpnEngineState.DEGRADED) to none,
            Triple(true, false, VpnEngineState.FAILED) to none,
            Triple(true, false, VpnEngineState.REVOKED) to none,
            // ---- no rules: stop only what runs, consent irrelevant to stopping ----
            Triple(false, true, VpnEngineState.STOPPED) to none,
            Triple(false, true, VpnEngineState.STARTING) to stop,
            Triple(false, true, VpnEngineState.ACTIVE) to stop,
            Triple(false, true, VpnEngineState.DEGRADED) to stop,
            Triple(false, true, VpnEngineState.FAILED) to none,
            Triple(false, true, VpnEngineState.REVOKED) to none,
            Triple(false, false, VpnEngineState.STOPPED) to none,
            Triple(false, false, VpnEngineState.STARTING) to stop,
            Triple(false, false, VpnEngineState.ACTIVE) to stop,
            Triple(false, false, VpnEngineState.DEGRADED) to stop,
            Triple(false, false, VpnEngineState.FAILED) to none,
            Triple(false, false, VpnEngineState.REVOKED) to none,
        )
        // The table must be total over the enum × boolean product — a new state fails HERE first.
        assertEquals(2 * 2 * VpnEngineState.entries.size, table.size)
        for ((key, expected) in table) {
            val (rules, consented, state) = key
            assertEquals("decide($rules, $consented, $state)", expected, VpnLifecycle.decide(rules, consented, state))
        }
    }

    @Test
    fun crossBucketInvariants_holdOverTheWholeProduct() {
        for (state in VpnEngineState.entries) {
            // START is only ever reachable with BOTH live rules and consent.
            for (rules in listOf(true, false)) {
                for (consented in listOf(true, false)) {
                    val action = VpnLifecycle.decide(rules, consented, state)
                    if (action == start) {
                        assertEquals(true, rules)
                        assertEquals(true, consented)
                    }
                    // STOP must only ever come from the no-rules bucket.
                    if (action == stop) {
                        assertEquals(false, rules)
                    }
                }
            }
            // No-rules never starts from ANY state, with or without consent (4 cells).
            assertNotEquals(start, VpnLifecycle.decide(false, true, state))
            assertNotEquals(start, VpnLifecycle.decide(false, false, state))
        }
    }

    @Test
    fun repeatedCalls_arePure_noStateCarriedBetweenDecisions() {
        // decide() is the policy atom called from onResume, Rules LaunchedEffect and BootReceiver
        // — possibly concurrently. Stress: hammer the same and alternating inputs; the function
        // must never depend on call order (a hidden memo/branch flip would show here).
        val states = VpnEngineState.entries
        var flips = 0
        for (round in 1..50) {
            for (state in states) {
                val a = VpnLifecycle.decide(true, true, state)
                val b = VpnLifecycle.decide(false, true, state)
                val aAgain = VpnLifecycle.decide(true, true, state)
                if (a != aAgain) flips++
                if (a == VpnLifecycle.Action.START && b == VpnLifecycle.Action.START) flips++
            }
        }
        assertEquals(0, flips)
    }
}
