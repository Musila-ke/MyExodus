package com.example.myexodus.ui.streaks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.MilestoneEvent

/**
 * REQ-12 milestone celebration — a modal, not an inline card (owner directive 2026-10-21):
 * the milestone is an event, so it takes the screen for one dismiss-and-continue beat and then
 * leaves the day hero unobstructed. Still the guaranteed delivery path (independent of
 * notification permission or alarm delivery): Home renders it whenever a milestone is achieved
 * and unacknowledged; dismissing acknowledges every day in [milestoneDays] through
 * [com.example.myexodus.streaks.HabitRepository.acknowledgeMilestone].
 */
@Composable
fun MilestoneReachedDialog(
    milestoneDays: List<Int>,
    achievedDays: Set<Int>,
    habitLabel: String?,
    onAcknowledge: () -> Unit,
) {
    Dialog(
        onDismissRequest = onAcknowledge,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(vertical = 24.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The one celebratory element on the screen: a gold medallion. Gold stays the
                // streak/milestone accent (finding #21); nothing else here competes with it.
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.EmojiEvents,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(42.dp),
                    )
                }
                Text(
                    stringResource(R.string.milestone_dialog_eyebrow),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.6.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    milestoneCardHeadline(milestoneDays),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                if (!habitLabel.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.milestone_dialog_habit, habitLabel),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MilestoneEvent.MILESTONE_DAYS.forEach { day ->
                        val achieved = day in achievedDays
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        if (achieved) {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (achieved) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                } else {
                                    Text(
                                        "$day",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.milestone_dialog_day_short, day),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    milestoneCardBody(milestoneDays),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onAcknowledge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.milestone_dialog_continue))
                }
            }
        }
    }
}
