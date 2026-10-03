package com.example.myexodus.ui.streaks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.ui.formatDayMonth

/**
 * The hero's label row, shared by the Streaks summary card and Home's streak card — the two
 * render the same label off the same habit, and before they were shared the copies drifted:
 * Home capped the label to one line and Streaks did not, so a long habit name wrapped to four
 * lines on the Streaks tab alone (audit findings #27/#28, ADR #85). Capping lives here now, so
 * it cannot drift again. [onSwitch] null means there is nothing to switch to: plain label, no
 * affordance promising a sheet that would not open.
 */
@Composable
internal fun HabitLabelRow(text: String, onSwitch: (() -> Unit)?) {
    val style = MaterialTheme.typography.labelLarge
    val color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    if (onSwitch == null) {
        Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onSwitch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = stringResource(R.string.streaks_switch_habit),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The stop date as a statement of fact, never a second counter: "Free since Oct 1". The
 * verified day count above it starts at setup — the picked date is remembered and shown, but
 * credits nothing (anti-minting keeps the partner-visible number evidence-backed; owner
 * decision 2026-10-03). After a logged relapse it reads the relapse date, which is exactly
 * when the current free period began. Shared by both streak cards so the two cannot drift.
 */
@Composable
internal fun FreeSinceLine(recoveryStartAtEpochMillis: Long) {
    Text(
        stringResource(R.string.streaks_free_since, formatDayMonth(recoveryStartAtEpochMillis)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
    )
}

/**
 * Live streak headline: days free (primary), the three milestones as a rung strip, best run
 * (secondary). The label row doubles as the multi-habit switcher ([onSwitch] opens the picker
 * sheet) — the sheet is also where "Add habit" lives, so the control shows whenever it's wired,
 * not only at 2+ habits.
 *
 * Owner ruling 2026-09-23 (ADR #85): the rungs replaced a 293px `MilestoneLadderCard` whose
 * first row restated this card's own caption verbatim ("Next milestone: Day 7 — 4 days to go").
 * Transposing the ladder into the hero deletes the duplicate statement and the page's weight
 * with it: at 360dp the whole tab now fits one screen, which is what puts the relapse action
 * back above the fold (finding #27).
 */
@Composable
internal fun StreakSummaryCard(state: HabitState, onSwitch: (() -> Unit)? = null) {
    val days = daysFree(state.currentStreakElapsedMs)
    val best = daysFree(state.allTimeMaxElapsedMs)
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            HabitLabelRow(
                stringResource(R.string.streaks_days_free_from, state.targetLabel),
                onSwitch,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$days",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(
                        if (days == 1) R.string.streaks_day_singular else R.string.streaks_day_plural,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            FreeSinceLine(state.recoveryStartAt)
            Spacer(Modifier.size(12.dp))
            MilestoneRungStrip(state)
            if (best > days) {
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.streaks_best_run, best),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/**
 * The 7/30/90 ladder as three equal rungs inside the hero, day-ordered. Reached rungs carry a
 * filled check; the next unachieved rung is tinted gold — the same gold the numeral uses, and
 * the same thing "Next milestone" said before, stated once instead of twice. The distance is
 * always words, never a bare glyph, so the state never rests on colour alone.
 */
@Composable
private fun MilestoneRungStrip(state: HabitState) {
    val next = nextMilestone(state)
    val muted = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MilestoneEvent.MILESTONE_DAYS.forEach { day ->
            val achieved = state.milestones.firstOrNull { it.milestoneDay == day }?.achievedAt != null
            val isNext = next?.milestoneDay == day
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = if (achieved) Icons.Filled.CheckCircle else Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = if (achieved) MaterialTheme.colorScheme.tertiary else muted,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.streaks_rung_day, day),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (achieved || isNext) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        muted
                    },
                    maxLines = 1,
                )
                Text(
                    if (achieved) {
                        stringResource(R.string.streaks_rung_reached)
                    } else {
                        stringResource(R.string.streaks_rung_days_away, daysUntil(state, day))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isNext) MaterialTheme.colorScheme.tertiary else muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Relapse logging: confirmation dialog first (the button ends the current streak — a
 * deliberate extra step, not the plan's debounce, which is the in-flight flag), then a
 * supportive non-punitive note (design research: "you lost your streak" framing drives
 * churn; the record surviving the reset is the honest counterweight).
 */
@Composable
internal fun RelapseCard(
    currentDays: Int,
    inFlight: Boolean,
    note: String?,
    onRelapse: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.streaks_relapse_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.streaks_relapse_confirm_body,
                        currentDays,
                        stringResource(
                            if (currentDays == 1) {
                                R.string.streaks_day_singular
                            } else {
                                R.string.streaks_day_plural
                            },
                        ),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onRelapse()
                }) { Text(stringResource(R.string.streaks_relapse_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.streaks_cancel))
                }
            },
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.streaks_relapse_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.streaks_relapse_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedButton(
                onClick = { confirming = true },
                enabled = !inFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (inFlight) R.string.streaks_relapse_in_flight
                        else R.string.streaks_relapse_action,
                    ),
                )
            }
        }
    }
}
