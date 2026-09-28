package com.example.myexodus.ui.streaks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myexodus.R

/**
 * REQ-12 milestone splash card — the guaranteed delivery path (independent of notification
 * permission or alarm delivery; §8 Step 5 renders it at the top of Home). ONE combined card
 * carries every unacknowledged milestone (product-owner call 2026-09-14, resolving plan §16
 * Open Question line 276); dismissing acknowledges them all via
 * [com.example.myexodus.streaks.HabitRepository.acknowledgeMilestone].
 */
@Composable
fun MilestoneSplashCard(milestoneDays: List<Int>, onAcknowledge: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Column {
                    Text(
                        milestoneCardHeadline(milestoneDays),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        milestoneCardLabel(milestoneDays),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(milestoneCardBody(milestoneDays), style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = onAcknowledge,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.streaks_done))
            }
        }
    }
}
