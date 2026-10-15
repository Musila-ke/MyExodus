package com.example.myexodus.widget

import com.example.myexodus.R
import com.example.myexodus.widget.WidgetActionViewModel.RelapseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold surface's pure decisions, over the whole outcome domain: every state's copy (null =
 * still interactive) and which states close themselves. The mapping is what stands between a
 * repository result and what the user reads — a state landing on the wrong copy (an error shown
 * as success, or a success closing silently) is the class of bug these pin.
 */
class WidgetActionStateTest {

    @Test
    fun outcomeCopy_coversTheWholeDomain() {
        // Interactive states owe no message: the hint below the ring is the copy then.
        assertNull(relapseOutcomeRes(RelapseOutcome.None))
        assertNull(relapseOutcomeRes(RelapseOutcome.InFlight))
        assertEquals(R.string.widget_relapse_logged, relapseOutcomeRes(RelapseOutcome.Logged))
        assertEquals(
            R.string.widget_relapse_already_logged,
            relapseOutcomeRes(RelapseOutcome.AlreadyLogged),
        )
        assertEquals(R.string.widget_relapse_gone, relapseOutcomeRes(RelapseOutcome.Gone))
        assertEquals(R.string.widget_relapse_failed, relapseOutcomeRes(RelapseOutcome.Failed))
    }

    @Test
    fun onlyTheSuccessStates_autoFinish() {
        assertTrue(relapseAutoFinishes(RelapseOutcome.Logged))
        assertTrue(relapseAutoFinishes(RelapseOutcome.AlreadyLogged))
        // Terminal problems stay open: their copy must be read before the surface leaves.
        assertTrue(!relapseAutoFinishes(RelapseOutcome.Gone))
        assertTrue(!relapseAutoFinishes(RelapseOutcome.Failed))
        assertTrue(!relapseAutoFinishes(RelapseOutcome.InFlight))
        assertTrue(!relapseAutoFinishes(RelapseOutcome.None))
    }

    @Test
    fun everyAutoFinishingState_carriesItsCopy() {
        // A state that closes the surface without a message would self-dismiss silently.
        RelapseOutcome.entries.forEach { outcome ->
            if (relapseAutoFinishes(outcome)) {
                assertTrue("$outcome auto-finishes but has no copy", relapseOutcomeRes(outcome) != null)
            }
        }
    }

    @Test
    fun holdRing_isArmedForExactlyTheInteractiveOutcomes() {
        // Failed stays armed: the write never happened and re-holding is the retry — the state
        // shipped with a disabled ring there once, a dead end the surface's own hint pointed at.
        assertEquals(
            setOf(RelapseOutcome.None, RelapseOutcome.Failed),
            RelapseOutcome.entries.filter { relapseHoldArmed(it) }.toSet(),
        )
    }
}
