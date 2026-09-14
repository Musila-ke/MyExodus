package com.example.myexodus.ui.streaks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myexodus.streaks.HabitState

/**
 * REQ-18 timeline: the four weekly buckets as relative-intensity bars (bar length orders the
 * weeks; it is not a score), the live week marked, and per-week expectation copy. Decision 12
 * is load-bearing here — the disclaimer ships with the card everywhere it renders.
 */
@Composable
internal fun WithdrawalTimelineCard(state: HabitState) {
    val weeks = state.withdrawalWeeks
    val currentWeek = currentTimelineWeek(state)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What to expect", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (currentWeek == null) {
                        "Your first month, week by week — you're past the four-week curve now."
                    } else {
                        "Your four-week curve, week by week."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            weeks.forEach { week ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(weekLabel(week), style = MaterialTheme.typography.labelLarge)
                        if (week.weekNumber == currentWeek) {
                            Text(
                                "You are here",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { weekFill(week, weeks) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        weekExpectation(week.weekNumber),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                TIMELINE_DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
