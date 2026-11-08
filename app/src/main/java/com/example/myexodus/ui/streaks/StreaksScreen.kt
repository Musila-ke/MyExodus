package com.example.myexodus.ui.streaks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.streaks.HabitSummary
import com.example.myexodus.streaks.WithdrawalSeverity
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.NavTile
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.insights.InsightsScreen
import com.example.myexodus.ui.insights.InsightsViewModel
import com.example.myexodus.ui.recovery.HistoryView
import com.example.myexodus.ui.rememberWindowWidth

private const val VIEW_TIMELINE = "timeline"
private const val VIEW_MANAGE = "manage"
private const val VIEW_INSIGHTS = "insights"
private const val VIEW_HISTORY = "history"

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
 * History lives here (owner ruling 2026-11-07) rather than on Recovery: it is retrospective,
 * and since every urge row carries its habit it reads as the streak's own record.
 *
 * `state == null` renders setup: the only ways to be null are "no habit yet" or a transient
 * resolve at first composition — the repository's refresh completes in the same launch and
 * re-emits, and a non-null state is never replaced by null without a resolved no-habit read.
 */
@Composable
fun StreaksScreen(
    state: HabitState?,
    addingHabit: Boolean,
    setupError: StreaksViewModel.SetupError?,
    onboardingInFlight: Boolean,
    relapseInFlight: Boolean,
    relapseFeedback: StreaksViewModel.RelapseFeedback?,
    manageFeedback: StreaksViewModel.ManageFeedback?,
    manageInFlight: Boolean,
    /** Owner task #22: the Insights spoke loads on open, never with the hub. */
    insights: InsightsViewModel.State,
    onRefreshInsights: () -> Unit,
    /** The habit-linked log (owner ruling 2026-11-07): reads on open, scoped to the
     *  displayed habit with an All habits option. */
    sessions: RecoverySessionRepository,
    habits: List<HabitSummary>,
    onRenameHabit: (habitId: String, label: String) -> Unit,
    onDeleteHabit: (habitId: String) -> Unit,
    onClearManageFeedback: () -> Unit,
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
        if (view != null) {
            if (view == VIEW_MANAGE) onClearManageFeedback()
            view = null
        } else {
            onCancelAddHabit()
        }
    }
    // The spokes render only with a habit in hand — expressing that as a value keeps the
    // precondition in the type rather than asserting it at the call, and a habit that disappears
    // while a spoke is open falls back to the hub instead of stranding a BackRow over nothing.
    val timelineState = state?.takeIf { view == VIEW_TIMELINE }
    val manageState = state?.takeIf { view == VIEW_MANAGE }
    LaunchedEffect(view) {
        if (view == VIEW_INSIGHTS) onRefreshInsights()
    }
    // A successful delete ends the Manage spoke: either another habit took the deleted one's
    // place, or the page itself has fallen back to setup (state == null).
    LaunchedEffect(manageFeedback) {
        if (manageFeedback == StreaksViewModel.ManageFeedback.Deleted) {
            view = null
            onClearManageFeedback()
        }
    }
    // History hosts its own LazyColumn (paging plus a pinned filter row), so it must not sit
    // inside this hub's vertical scroll — two scrollables measuring each other in the same
    // axis is a crash, not a layout. Every other spoke is plain content and stays inside.
    if (view == VIEW_HISTORY) {
        HistoryView(
            sessions = sessions,
            habits = habits,
            displayedHabitId = state?.habitId,
            onBack = { view = null },
            modifier = modifier,
        )
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (view == VIEW_INSIGHTS) {
            BackRow(label = stringResource(R.string.insights_title), onBack = { view = null })
            InsightsScreen(state = insights, habit = state, onRetry = onRefreshInsights)
        } else if (timelineState != null) {
            BackRow(label = stringResource(R.string.streaks_what_to_expect), onBack = { view = null })
            WithdrawalTimelineCard(timelineState)
        } else if (manageState != null) {
            BackRow(
                label = stringResource(R.string.streaks_manage_title),
                onBack = {
                    view = null
                    onClearManageFeedback()
                },
            )
            ManageStreakView(
                state = manageState,
                feedback = manageFeedback,
                inFlight = manageInFlight,
                onRename = { onRenameHabit(manageState.habitId, it) },
                onDelete = { onDeleteHabit(manageState.habitId) },
                onClearFeedback = onClearManageFeedback,
            )
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
                    error = setupError?.let { stringResource(setupErrorMessage(it)) },
                    submitting = onboardingInFlight,
                    onOnboard = onOnboard,
                    onCancel = if (addingHabit) onCancelAddHabit else null,
                    firstHabit = state == null,
                )
                // Rows from before a habit existed (techniques run from Recovery) still need a
                // way in; with no habit the page opens on All habits.
                if (state == null) HistoryTile(onClick = { view = VIEW_HISTORY })
            } else {
                // Owner ruling 2026-09-23 (ADR #85): the milestone ladder lives in the hero as a
                // rung strip — as its own card it restated the hero's caption and pushed this
                // screen's one action past the fold.
                // The relapse action rides in the hero (owner call 2026-11-08): it ends the
                // count it sits under, so it no longer sits below the toolkit at page bottom.
                StreakSummaryCard(
                    state = state,
                    onSwitch = onOpenHabitPicker,
                    relapseInFlight = relapseInFlight,
                    relapseNote = relapseFeedback?.let { relapseNoteText(it) },
                    onRelapse = onRelapse,
                )
                // Owner call 2026-11-08: one grouped toolkit instead of four centered tiles.
                // Reference material, management and the record read as a single list, each
                // row stating its scope before the tap (owner task #22).
                StreaksToolkitCard(
                    onOpenTimeline = { view = VIEW_TIMELINE },
                    onOpenManage = {
                        onClearManageFeedback()
                        view = VIEW_MANAGE
                    },
                    onOpenInsights = { view = VIEW_INSIGHTS },
                    onOpenHistory = { view = VIEW_HISTORY },
                )
            }
        }
    }
}

/** The retrospective log, one tap from the hub in both states (see [StreaksScreen]). Its
 *  caption states the scope before the tap. */
@Composable
private fun HistoryTile(onClick: () -> Unit) {
    NavTile(
        icon = Icons.Outlined.History,
        label = stringResource(R.string.recovery_history_title),
        status = stringResource(R.string.recovery_history_tile_caption),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The streak's four destinations as one grouped list (owner call 2026-11-08). Rows carry the
 * scope captions the tiles used to, so each door is legible before the tap, and the whole
 * block fits on the hub without the dead space centered tiles cost.
 */
@Composable
private fun StreaksToolkitCard(
    onOpenTimeline: () -> Unit,
    onOpenManage: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            ToolkitRow(
                icon = Icons.Outlined.Timeline,
                label = stringResource(R.string.streaks_what_to_expect),
                caption = null,
                onClick = onOpenTimeline,
            )
            ToolkitDivider()
            ToolkitRow(
                icon = Icons.Outlined.Tune,
                label = stringResource(R.string.streaks_manage_title),
                caption = null,
                onClick = onOpenManage,
            )
            ToolkitDivider()
            ToolkitRow(
                icon = Icons.Outlined.Insights,
                label = stringResource(R.string.insights_title),
                caption = stringResource(R.string.insights_stat_caption),
                onClick = onOpenInsights,
            )
            ToolkitDivider()
            ToolkitRow(
                icon = Icons.Outlined.History,
                label = stringResource(R.string.recovery_history_title),
                caption = stringResource(R.string.recovery_history_tile_caption),
                onClick = onOpenHistory,
            )
        }
    }
}

@Composable
private fun ToolkitRow(
    icon: ImageVector,
    label: String,
    caption: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Inset to the text column, so the group reads as one card, not four stacked rows. */
@Composable
private fun ToolkitDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 50.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * The Manage spoke (owner directive 2026-10-21): rename or delete the displayed streak.
 * Rename changes only the label; delete removes the habit's local streaks, milestones and
 * sharing consent (repository contract). Failures stay inline — what happened, and what to do.
 */
@Composable
private fun ManageStreakView(
    state: HabitState,
    feedback: StreaksViewModel.ManageFeedback?,
    inFlight: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onClearFeedback: () -> Unit,
) {
    var name by rememberSaveable(state.habitId) { mutableStateOf(state.targetLabel) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val error = when (feedback) {
        StreaksViewModel.ManageFeedback.InvalidName -> stringResource(R.string.streaks_manage_error_name)
        StreaksViewModel.ManageFeedback.StoreUnavailable -> stringResource(R.string.streaks_manage_error_store)
        StreaksViewModel.ManageFeedback.NotFound -> stringResource(R.string.streaks_manage_error_gone)
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        if (it.length <= HabitRepository.MAX_LABEL_LENGTH) {
                            name = it
                            onClearFeedback()
                        }
                    },
                    label = { Text(stringResource(R.string.streaks_manage_name_label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (feedback == StreaksViewModel.ManageFeedback.Renamed) {
                    Text(
                        stringResource(R.string.streaks_manage_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = { onRename(name) },
                    enabled = !inFlight && name.isNotBlank() && name.trim() != state.targetLabel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (inFlight) R.string.streaks_manage_saving
                            else R.string.streaks_manage_save,
                        ),
                    )
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.streaks_manage_delete_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.streaks_manage_delete_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmingDelete = true },
                    enabled = !inFlight,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.streaks_manage_delete_action))
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.streaks_manage_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.streaks_manage_delete_confirm_body,
                        state.targetLabel,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                ) {
                    Text(
                        stringResource(R.string.streaks_manage_delete_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.streaks_cancel))
                }
            },
        )
    }
}

private fun setupErrorMessage(error: StreaksViewModel.SetupError): Int = when (error) {
    StreaksViewModel.SetupError.START_IN_FUTURE -> R.string.streaks_setup_error_future
    StreaksViewModel.SetupError.INVALID_DURATION -> R.string.streaks_setup_error_invalid
    StreaksViewModel.SetupError.IMPLAUSIBLE_CLOCK -> R.string.streaks_setup_error_clock
    StreaksViewModel.SetupError.STORE_UNAVAILABLE -> R.string.streaks_setup_error_store
}

/** Copy for the last relapse outcome (finding #20); the support line stays the test-pinned
 *  pure function in StreaksPresentation.kt. */
@Composable
private fun relapseNoteText(feedback: StreaksViewModel.RelapseFeedback): String = when (feedback) {
    is StreaksViewModel.RelapseFeedback.Support ->
        relapseSupportNote(feedback.endedDays, feedback.bestDays)

    StreaksViewModel.RelapseFeedback.RejectedBeforeStart ->
        stringResource(R.string.streaks_relapse_error_before_start)

    StreaksViewModel.RelapseFeedback.StoreUnavailable ->
        stringResource(R.string.streaks_relapse_error_store)
}
