package com.example.myexodus.ui.streaks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.streaks.WithdrawalSeverity
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.LinkRow
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.rememberWindowWidth

private const val VIEW_TIMELINE = "timeline"

/**
 * Streaks tab (plan §8 Step 5): live streak state from the repository's `StateFlow` collected
 * by the shell, the hero (days free + the milestone rungs), and relapse logging — or the REQ-18
 * habit setup while no habit exists (or while adding one). The screen derives nothing itself:
 * days, milestone distances and week positions come from [StreaksPresentation] helpers, and
 * evaluation is never triggered here (that is the ViewModel/lifecycle boundary).
 *
 * The REQ-18 withdrawal timeline is reachable through a row rather than rendered inline (owner
 * ruling 2026-09-22, ADR #79): it is reading material whose four bars are a static estimate
 * identical for every user, so it carried the most weight and the least live state. Everything
 * that changes while the user watches — the hero, the ladder's countdowns, the relapse action —
 * stays on the hub.
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
    var view by rememberSaveable { mutableStateOf<String?>(null) }
    // System back mirrors the visible exits: the spoke's BackRow (view = null) or the add
    // form's Cancel. At the hub the shell's handler takes over (tab → Home).
    BackHandler(enabled = view != null || addingHabit) {
        if (view != null) view = null else onCancelAddHabit()
    }
    // The spoke renders only with a habit in hand — expressing that as a value keeps the
    // precondition in the type rather than asserting it at the call, and a habit that disappears
    // while the spoke is open falls back to the hub instead of stranding a BackRow over nothing.
    val timelineState = state?.takeIf { view == VIEW_TIMELINE }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (timelineState != null) {
            BackRow(label = stringResource(R.string.streaks_what_to_expect), onBack = { view = null })
            WithdrawalTimelineCard(timelineState)
        } else {
            Column {
                Text(
                    stringResource(R.string.streaks_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.streaks_subtitle),
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
                // Owner ruling 2026-09-23 (ADR #85): the milestone ladder lives in the hero as a
                // rung strip — as its own card it restated the hero's caption and pushed this
                // screen's one action past the fold.
                StreakSummaryCard(state, onSwitch = onOpenHabitPicker)
                // Owner ruling 2026-09-22 (ADR #79): the four-week card is reading material, not
                // live state, so it sits one tap away. The row says what kind of material it is
                // before the tap — Decision 12's disclaimer still ships on the card itself.
                LinkRow(
                    title = stringResource(R.string.streaks_what_to_expect),
                    body = stringResource(R.string.streaks_timeline_row_body),
                    onClick = { view = VIEW_TIMELINE },
                )
                RelapseCard(
                    currentDays = daysFree(state.currentStreakElapsedMs),
                    inFlight = relapseInFlight,
                    note = relapseNote,
                    onRelapse = onRelapse,
                )
            }
        }
    }
}
