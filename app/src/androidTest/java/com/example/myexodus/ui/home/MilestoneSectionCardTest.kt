package com.example.myexodus.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.streaks.StreakTimer
import com.example.myexodus.streaks.WithdrawalCurve
import com.example.myexodus.streaks.WithdrawalSeverity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home's milestone section in all four states, rendered for real on the device (product call
 * 2026-09-29). The section takes plain inputs rather than a store, which is the seam that lets
 * every state be exercised — the error state included, which cannot be reached by seeding data.
 * Each state asserts both what it must show and what it must not, so no two states can silently
 * collapse into one.
 */
@RunWith(AndroidJUnit4::class)
class MilestoneSectionCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun str(resId: Int, vararg args: Any): String = compose.activity.getString(resId, *args)

    private fun render(habit: HabitState?, resolved: Boolean, failed: Boolean) {
        compose.setContent {
            MilestoneSectionCard(
                habit = habit,
                streakResolved = resolved,
                loadFailed = failed,
                onRetry = {},
                onOpenStreaks = {},
            )
        }
    }

    @Test
    fun loading_showsTheCardsChrome_andNoStateCopy() {
        render(habit = null, resolved = false, failed = false)

        compose.onNodeWithText(str(R.string.home_grid_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.home_grid_legend, "7, 30, 90")).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.home_grid_empty_body)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.home_grid_error_body)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.home_streak_cta)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.home_grid_retry)).assertDoesNotExist()
    }

    @Test
    fun empty_showsTheLadderExplanation_andTheWayToStart() {
        render(habit = null, resolved = true, failed = false)

        compose.onNodeWithText(str(R.string.home_grid_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.home_grid_empty_body)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.home_streak_cta)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.home_grid_legend, "7, 30, 90")).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.home_grid_error_body)).assertDoesNotExist()
    }

    @Test
    fun success_showsTheGrid_dayChip_andLegend() {
        render(habit = habitAt(days = 5), resolved = true, failed = false)

        compose.onNodeWithText(str(R.string.home_grid_title)).assertIsDisplayed()
        // 5 completed days render as ordinal Day 6 (ADR #128) — never "Day 0 of 90".
        compose.onNodeWithText(str(R.string.home_grid_day_of, 6, 90)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.home_grid_legend, "7, 30, 90")).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.home_grid_empty_body)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.home_grid_error_body)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.home_grid_retry)).assertDoesNotExist()
    }

    @Test
    fun error_showsTheFailure_andTheRetry_withNoStaleCopy() {
        render(habit = null, resolved = false, failed = true)

        compose.onNodeWithText(str(R.string.home_grid_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.home_grid_error_body)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.home_grid_retry)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.home_grid_legend, "7, 30, 90")).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.home_streak_cta)).assertDoesNotExist()
    }

    /**
     * A failed refresh over a previously loaded habit is still the error state: the grid in
     * memory is stale, and the retry is the only honest action offered.
     */
    @Test
    fun error_overAHeldHabit_neverRendersTheStaleGridAsFresh() {
        render(habit = habitAt(days = 5), resolved = true, failed = true)

        compose.onNodeWithText(str(R.string.home_grid_error_body)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.home_grid_day_of, 6, 90)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.home_grid_legend, "7, 30, 90")).assertDoesNotExist()
    }

    private fun habitAt(days: Int) = HabitState(
        habitId = "habit-1",
        streakId = "streak-1",
        targetLabel = "Instagram",
        recoveryStartAt = 0L,
        currentStreakElapsedMs = days * StreakTimer.DAY_MS,
        allTimeMaxElapsedMs = days * StreakTimer.DAY_MS,
        currentDay = days + 1,
        habitDurationYears = 2,
        severity = WithdrawalSeverity.MODERATE,
        withdrawalWeeks = WithdrawalCurve.weeks(2, WithdrawalSeverity.MODERATE),
        milestones = MilestoneEvent.MILESTONE_DAYS.map { MilestoneEvent("streak-1", it) },
    )
}
