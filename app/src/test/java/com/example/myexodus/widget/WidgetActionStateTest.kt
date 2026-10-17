package com.example.myexodus.widget

import android.app.Application
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.R
import com.example.myexodus.widget.WidgetActionViewModel.RelapseOutcome
import com.example.myexodus.widget.WidgetActionViewModel.UrgeOutcome
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
 * stands between the repository call and what the user reads — a state landing on the wrong copy
 * (success closing silently) is the class of bug these pin.
 *
 * The vocabulary guard exists because the first X6532 leg (2026-10-16) caught a discreet surface
 * printing recovery vocabulary the card had just refused to show. The widget v2.1 correction
 * (2026-10-16) extends the guard to "urge": the icon-only controls put no words on the launcher,
 * and the discreet surface must not either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WidgetActionStateTest {

    private val resources: Resources =
        ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun outcomeCopy_coversTheWholeDisclosureMatrix() {
        // Interactive states owe no message: the hint below the ring is the copy then.
        UrgeOutcome.entries.forEach { outcome ->
            listOf(true, false).forEach { discreet ->
                if (outcome == UrgeOutcome.None || outcome == UrgeOutcome.InFlight) {
                    assertNull(urgeOutcomeRes(outcome, discreet))
                } else {
                    assertTrue("$outcome (discreet=$discreet) has no copy", urgeOutcomeRes(outcome, discreet) != null)
                }
            }
        }
        // The saved state's disclosure pair, pinned exactly so a rewire is a test failure.
        assertEquals(R.string.widget_urge_saved_discreet, urgeOutcomeRes(UrgeOutcome.Saved, true))
        assertEquals(R.string.widget_urge_saved, urgeOutcomeRes(UrgeOutcome.Saved, false))
    }

    @Test
    fun relapseCopy_coversTheWholeDisclosureMatrix() {
        // Interactive states owe no message: the hint below the ring is the copy then.
        RelapseOutcome.entries.forEach { outcome ->
            listOf(true, false).forEach { discreet ->
                if (outcome == RelapseOutcome.None || outcome == RelapseOutcome.InFlight) {
                    assertNull(relapseOutcomeRes(outcome, discreet))
                } else {
                    assertTrue(
                        "$outcome (discreet=$discreet) has no copy",
                        relapseOutcomeRes(outcome, discreet) != null,
                    )
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
            assertEquals(
                R.string.widget_relapse_already_logged,
                relapseOutcomeRes(RelapseOutcome.AlreadyLogged, it),
            )
        }
    }

    @Test
    fun discreetCopy_neverCarriesRecoveryVocabulary() {
        // The outcome is not the only surface a hold opens: the titles/bodies, content
        // descriptions and the reset pair are the same disclosure contract, and every discreet
        // string must hold it.
        val discreetStrings = listOf(
            R.string.widget_urge_saved_discreet,
            R.string.widget_hold_urge_title_discreet,
            R.string.widget_hold_urge_body_discreet,
            R.string.widget_hold_reset_title,
            R.string.widget_hold_reset_body,
            R.string.widget_relapse_logged_discreet,
            R.string.widget_relapse_gone_discreet,
            R.string.widget_relapse_failed_discreet,
            R.string.widget_action_urge_discreet,
            R.string.widget_action_reset,
        )
        discreetStrings.forEach { res ->
            val text = resources.getString(res).lowercase()
            listOf("relapse", "streak", "urge", "recovery").forEach { word ->
                assertFalse("discreet copy ($res) leaks \"$word\": \"$text\"", text.contains(word))
            }
        }
    }

    @Test
    fun onlyTheSavedState_autoFinishes() {
        assertTrue(urgeAutoFinishes(UrgeOutcome.Saved))
        assertTrue(!urgeAutoFinishes(UrgeOutcome.InFlight))
        assertTrue(!urgeAutoFinishes(UrgeOutcome.None))
    }

    @Test
    fun everyAutoFinishingState_carriesItsCopy() {
        // A state that closes the surface without a message would self-dismiss silently.
        UrgeOutcome.entries.forEach { outcome ->
            if (urgeAutoFinishes(outcome)) {
                listOf(true, false).forEach { discreet ->
                    assertTrue(
                        "$outcome (discreet=$discreet) auto-finishes but has no copy",
                        urgeOutcomeRes(outcome, discreet) != null,
                    )
                }
            }
        }
    }

    @Test
    fun urgeHoldRing_isArmedForExactlyTheIdleState() {
        // InFlight is a write in progress; Saved is terminal. Only the armed idle state accepts
        // a gesture.
        assertEquals(
            setOf(UrgeOutcome.None),
            UrgeOutcome.entries.filter { urgeHoldArmed(it) }.toSet(),
        )
    }

    @Test
    fun relapseOnly_theSuccessStatesAutoFinish() {
        assertTrue(relapseAutoFinishes(RelapseOutcome.Logged))
        assertTrue(relapseAutoFinishes(RelapseOutcome.AlreadyLogged))
        // Terminal problems stay open: their copy must be read before the surface leaves.
        assertFalse(relapseAutoFinishes(RelapseOutcome.Gone))
        assertFalse(relapseAutoFinishes(RelapseOutcome.Failed))
        assertFalse(relapseAutoFinishes(RelapseOutcome.InFlight))
        assertFalse(relapseAutoFinishes(RelapseOutcome.None))
    }

    @Test
    fun relapseEveryAutoFinishingState_carriesItsCopy() {
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
    fun relapseHoldRing_isArmedForExactlyTheInteractiveOutcomes() {
        // Failed stays armed: the write never happened and re-holding is the retry — the state
        // shipped with a disabled ring there once, a dead end the surface's own hint pointed at.
        assertEquals(
            setOf(RelapseOutcome.None, RelapseOutcome.Failed),
            RelapseOutcome.entries.filter { relapseHoldArmed(it) }.toSet(),
        )
    }
}
