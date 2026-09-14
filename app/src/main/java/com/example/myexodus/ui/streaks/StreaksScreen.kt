package com.example.myexodus.ui.streaks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.streaks.WithdrawalSeverity
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.rememberWindowWidth

/**
 * Streaks tab (plan §8 Step 5): live streak state from the repository's `StateFlow` collected
 * by the shell, the REQ-18 withdrawal timeline, the milestone ladder, and relapse logging —
 * or the REQ-18 habit setup while no habit exists (or while adding one). The screen derives
 * nothing itself: days, milestone distances and week positions come from [StreaksPresentation]
 * helpers, and evaluation is never triggered here (that is the ViewModel/lifecycle boundary).
 *
 * Multi-habit: the summary card's label row opens the shell's [HabitPickerSheet]
 * ([onOpenHabitPicker]), and the same [HabitSetupCard] serves first setup and "add another" —
 * `onCancel` only exists in the latter case, where there is something to go back to.
 *
 * `state == null` renders setup: the only ways to be null are "no habit yet" or a transient
 * resolve at first composition — the repository's refresh completes in the same launch and
 * re-emits, and a non-null state is never replaced by null without a resolved no-habit read.
 */
@Composable
fun StreaksScreen(
    state: HabitState?,
    addingHabit: Boolean,
    setupError: String?,
    relapseInFlight: Boolean,
    relapseNote: String?,
    onOnboard: (
        label: String,
        durationYears: Int,
        severity: WithdrawalSeverity,
        recoveryStartAt: Long,
    ) -> Unit,
    onOpenHabitPicker: () -> Unit,
    onCancelAddHabit: () -> Unit,
    onRelapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text("Streaks", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Your days free and what to expect",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state == null || addingHabit) {
            HabitSetupCard(
                error = setupError,
                onOnboard = onOnboard,
                onCancel = if (addingHabit) onCancelAddHabit else null,
            )
        } else {
            StreakSummaryCard(state, onSwitch = onOpenHabitPicker)
            MilestoneLadderCard(state)
            WithdrawalTimelineCard(state)
            RelapseCard(
                currentDays = habitDays(state.currentStreakElapsedMs),
                inFlight = relapseInFlight,
                note = relapseNote,
                onRelapse = onRelapse,
            )
        }
    }
}
