package com.example.myexodus.widget

import com.example.myexodus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The widget view's arithmetic. The milestone line names a future day, so an off-by-one here is a
 * wrong claim rendered on the user's home screen, not a cosmetic slip.
 */
class WidgetContentTest {

    @Test
    fun nextMilestone_isTheFirstRungAhead() {
        assertEquals(7 to 7, nextMilestone(0))
        assertEquals(7 to 1, nextMilestone(6))
        // Day 7 is reached, not ahead: the line must point at 30, not repeat the current day.
        assertEquals(30 to 23, nextMilestone(7))
        assertEquals(90 to 1, nextMilestone(89))
    }

    @Test
    fun pastTheLastRung_showsNothing() {
        assertNull(nextMilestone(90))
        assertNull(nextMilestone(400))
    }

    @Test
    fun noStreak_hasNoMilestone() {
        assertNull(nextMilestone(null))
    }

    @Test
    fun milestoneFraction_isTheDaysSpentTowardTheRung() {
        assertEquals(0f, milestoneFraction(0, 7)!!, 0.0001f)
        assertEquals(7f / 30f, milestoneFraction(7, 30)!!, 0.0001f)
        assertEquals(89f / 90f, milestoneFraction(89, 90)!!, 0.0001f)
        // Defensive: a count past the rung cannot overfill the bar.
        assertEquals(1f, milestoneFraction(95, 90)!!, 0.0001f)
    }

    @Test
    fun milestoneFraction_needsBothSides() {
        assertNull(milestoneFraction(null, 30))
        assertNull(milestoneFraction(7, null))
        assertNull(milestoneFraction(7, 0))
    }

    /**
     * The relapse pill's label over its whole 2×2 domain (disclosure × row width). Discreet wins
     * at any width — the recovery word must be unreachable while the user has not opted into
     * disclosure (FR5); the short form exists only for the tight row after disclosure.
     */
    @Test
    fun relapseLabel_coversTheDisclosureAndWidthMatrix() {
        assertEquals(R.string.widget_action_reset, relapseLabelRes(discreet = true, compact = false))
        assertEquals(R.string.widget_action_reset, relapseLabelRes(discreet = true, compact = true))
        assertEquals(
            R.string.widget_action_relapse,
            relapseLabelRes(discreet = false, compact = false),
        )
        assertEquals(
            R.string.widget_action_relapse_short,
            relapseLabelRes(discreet = false, compact = true),
        )
    }
}
