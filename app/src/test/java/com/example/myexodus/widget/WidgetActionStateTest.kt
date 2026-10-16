package com.example.myexodus.widget

import android.app.Application
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.R
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
    fun discreetCopy_neverCarriesRecoveryVocabulary() {
        // The outcome is not the only surface a hold opens: the titles/bodies are the same
        // disclosure contract, and every discreet string must hold it.
        val discreetStrings = listOf(
            R.string.widget_urge_saved_discreet,
            R.string.widget_hold_urge_title_discreet,
            R.string.widget_hold_urge_body_discreet,
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
    fun holdRing_isArmedForExactlyTheIdleState() {
        // InFlight is a write in progress; Saved is terminal. Only the armed idle state accepts
        // a gesture.
        assertEquals(
            setOf(UrgeOutcome.None),
            UrgeOutcome.entries.filter { urgeHoldArmed(it) }.toSet(),
        )
    }
}
