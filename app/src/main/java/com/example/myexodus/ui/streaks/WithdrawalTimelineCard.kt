package com.example.myexodus.ui.streaks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.ui.PillTone
import com.example.myexodus.ui.StatusPill

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
            // No card title: this renders only under the spoke's own `BackRow`, which already
            // names it "What to expect" (ADR #85). The subtitle is not a restatement — it says
            // which curve this is, and whether the live streak is still inside it.
            Text(
                stringResource(
                    if (currentWeek == null) R.string.streaks_timeline_subtitle_past
                    else R.string.streaks_timeline_subtitle,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Bar length is the forecast's own intensity, not how far through the curve the
            // streak is (owner audit 2026-11-08: the progress-indicator read misled).
            Text(
                stringResource(R.string.streaks_timeline_bar_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            weeks.forEach { week ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(weekLabel(week), style = MaterialTheme.typography.labelLarge)
                        if (week.weekNumber == currentWeek) {
                            // The live week is this card's one status, so it wears the same chip
                            // as every other status in the app rather than a fourth treatment.
                            StatusPill(
                                text = stringResource(R.string.streaks_timeline_here),
                                tone = PillTone.POSITIVE,
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(weekFill(week, weeks))
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
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
