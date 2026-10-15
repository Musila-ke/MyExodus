package com.example.myexodus.widget

import android.app.Application
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.R
import com.example.myexodus.widget.WidgetActionViewModel.RelapseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hold surface's pure decisions, over the whole outcome domain: every state's copy (null =
 * still interactive, per disclosure mode) and which states close themselves. The mapping is what
 * stands between a repository result and what the user reads — a state landing on the wrong copy
 * (an error shown as success, or a success closing silently) is the class of bug these pin.
 *
 * The vocabulary guard exists because the first X6532 leg (2026-10-16) caught the discreet
 * surface printing "Relapse logged" after a completed hold, while the same surface's title
 * discreetly said "Reset the count?" — the outcome map simply had no disclosure pair. It does
 * now, and no discreet outcome may ever carry the recovery vocabulary again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WidgetActionStateTest {

    private val resources: Resources =
        ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun outcomeCopy_coversTheWholeDisclosureMatrix() {
        // Interactive states owe no message: the hint below the ring is the copy then.
        RelapseOutcome.entries.forEach { outcome ->
            listOf(true, false).forEach { discreet ->
                if (outcome == RelapseOutcome.None || outcome == RelapseOutcome.InFlight) {
                    assertNull(relapseOutcomeRes(outcome, discreet))
                } else {
                    assertTrue("$outcome (discreet=$discreet) has no copy", relapseOutcomeRes(outcome, discreet) != null)
                }
            }
        }
        // The states with a disclosure pair, pinned exactly so a rewire is a test failure.
        assertEquals(R.string.widget_relapse_logged_discreet, relapseOutcomeRes(RelapseOutcome.Logged, true))
        assertEquals(R.string.widget_relapse_logged, relapseOutcomeRes(RelapseOutcome.Logged, false))
        assertEquals(R.string.widget_relapse_gone_discreet, relapseOutcomeRes(RelapseOutcome.Gone, true))
        assertEquals(R.string.widget_relapse_gone, relapseOutcomeRes(RelapseOutcome.Gone, false))
        assertEquals(R.string.widget_relapse_failed_discreet, relapseOutcomeRes(RelapseOutcome.Failed, true))
        assertEquals(R.string.widget_relapse_failed, relapseOutcomeRes(RelapseOutcome.Failed, false))
        // Vocabulary-free, shared across modes.
        listOf(true, false).forEach {
            assertEquals(R.string.widget_relapse_already_logged, relapseOutcomeRes(RelapseOutcome.AlreadyLogged, it))
        }
    }

    @Test
    fun discreetOutcomes_neverCarryRecoveryVocabulary() {
        RelapseOutcome.entries.forEach { outcome ->
            val res = relapseOutcomeRes(outcome, discreet = true) ?: return@forEach
            val text = resources.getString(res).lowercase()
            listOf("relapse", "streak").forEach { word ->
                assertFalse("discreet copy for $outcome leaks \"$word\": \"$text\"", text.contains(word))
            }
        }
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
                listOf(true, false).forEach { discreet ->
                    assertTrue(
                        "$outcome (discreet=$discreet) auto-finishes but has no copy",
                        relapseOutcomeRes(outcome, discreet) != null,
                    )
                }
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
