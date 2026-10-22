package com.example.myexodus.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The widget view's arithmetic. The milestone line names a future day, so an off-by-one here is a
 * wrong claim rendered on the user's home screen, not a cosmetic slip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetContentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun milestoneCopy_namesTheRungAsAMark_andTheDistanceSeparately() {
        // The rung is the completed-day threshold (7 days free), never the ordinal display day,
        // which is one higher (#128). Two bare day-quantities side by side read as a
        // contradiction (owner-flagged 2026-10-17), so the form is "distance to mark".
        assertEquals("6d to 7-day mark", milestoneText(context, 7, 6))
        assertEquals("6 days to a 7-day mark", milestoneSentence(context, 7, 6))
        assertEquals(
            "Next milestone: 7-day mark — 6 days to go.",
            context.resources.getQuantityString(R.plurals.home_streak_caption_next, 6, 7, 6),
        )
        // Plural grammar at the boundary (lint PluralsCandidate, 2026-10-22).
        assertEquals(
            "Next milestone: 7-day mark — 1 day to go.",
            context.resources.getQuantityString(R.plurals.home_streak_caption_next, 1, 7, 1),
        )
    }

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
}
