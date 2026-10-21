package com.example.myexodus.ui.streaks

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner directive 2026-10-21: the milestone celebration is a modal (it was an inline card).
 * One dialog covers a single milestone and a stack, and Continue acknowledges through the
 * callback — the same acknowledgement write the card performed.
 */
@RunWith(AndroidJUnit4::class)
class MilestoneReachedDialogTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun str(resId: Int, vararg args: Any): String = compose.activity.getString(resId, *args)

    @Test
    fun singleMilestone_rendersTheCelebration_andContinueAcknowledges() {
        var acknowledged = false
        compose.setContent {
            MilestoneReachedDialog(
                milestoneDays = listOf(7),
                achievedDays = setOf(7),
                habitLabel = "New habit",
                onAcknowledge = { acknowledged = true },
            )
        }

        compose.onNodeWithText(str(R.string.milestone_dialog_eyebrow)).assertIsDisplayed()
        compose.onNodeWithText(milestoneCardHeadline(listOf(7))).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.milestone_dialog_habit, "New habit")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.milestone_dialog_day_short, 7)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.milestone_dialog_day_short, 30)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.milestone_dialog_day_short, 90)).assertIsDisplayed()
        compose.onNodeWithText(milestoneCardBody(listOf(7))).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.milestone_dialog_continue)).performClick()
        assertTrue("Continue must acknowledge the milestone", acknowledged)
    }

    @Test
    fun stackedMilestones_useTheCombinedCopy_andDropTheHabitLine() {
        compose.setContent {
            MilestoneReachedDialog(
                milestoneDays = listOf(7, 30),
                achievedDays = setOf(7, 30),
                habitLabel = null,
                onAcknowledge = {},
            )
        }

        compose.onNodeWithText(milestoneCardHeadline(listOf(7, 30))).assertIsDisplayed()
        compose.onNodeWithText(milestoneCardBody(listOf(7, 30))).assertIsDisplayed()
        compose.onAllNodesWithText("Free from", substring = true).assertCountEquals(0)
    }
}
