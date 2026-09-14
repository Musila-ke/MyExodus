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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myexodus.streaks.HabitState

/**
 * Live streak headline: days free (primary), best run (secondary), next milestone. The label
 * row doubles as the multi-habit switcher ([onSwitch] opens the picker sheet) — the sheet is
 * also where "Add habit" lives, so the control shows whenever it's wired, not only at 2+ habits.
 */
@Composable
internal fun StreakSummaryCard(state: HabitState, onSwitch: (() -> Unit)? = null) {
    val days = habitDays(state.currentStreakElapsedMs)
    val best = habitDays(state.allTimeMaxElapsedMs)
    val next = nextMilestone(state)
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (onSwitch != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = onSwitch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Days free from ${state.targetLabel}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = "Switch habit",
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Text(
                    "Days free from ${state.targetLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
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
                    if (days == 1) "day" else "days",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(
                if (next != null) {
                    "Next milestone: Day ${next.milestoneDay} — " +
                        "${daysUntil(state, next.milestoneDay)} days to go."
                } else {
                    "Milestone ladder complete — 7, 30 and 90 all reached."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (best > days) {
                Text(
                    "Best run: $best days — that record stays with you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** The 7/30/90 ladder for the active streak: reached date or countdown per row. */
@Composable
internal fun MilestoneLadderCard(state: HabitState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Milestones", style = MaterialTheme.typography.titleMedium)
            state.milestones.forEach { milestone ->
                val achieved = milestone.achievedAt != null
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (achieved) Icons.Filled.CheckCircle else Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = if (achieved) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "Day ${milestone.milestoneDay}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (achieved) {
                            "Reached"
                        } else {
                            "${daysUntil(state, milestone.milestoneDay)} days away"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            title = { Text("Log a relapse?") },
            text = {
                Text(
                    "Your current streak of $currentDays " +
                        (if (currentDays == 1) "day" else "days") +
                        " ends and a new one starts at Day 0. Your best run stays on record.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onRelapse()
                }) { Text("Log it") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Had a slip?", style = MaterialTheme.typography.titleMedium)
            Text(
                "Logging a relapse ends the current count and starts a new one at Day 0. " +
                    "Your best run stays on record — progress you've made isn't erased.",
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
                Text(if (inFlight) "Logging…" else "Log a relapse")
            }
        }
    }
}
