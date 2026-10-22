package com.example.myexodus.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The three weights a pill may carry. [QUIET] is a fact, [POSITIVE] is a settled good outcome,
 * [NEGATIVE] is the one that needs the reader's eye — the same escalation ladder the partner
 * cards use, at chip scale.
 */
enum class PillTone { QUIET, POSITIVE, NEGATIVE }

/**
 * A short status the reader can take in before the sentence it belongs to: tint **and** the word,
 * never tint alone (the rule the chain verdict mark and the effort chips both follow).
 *
 * Shared rather than duplicated because two feature packages scan lists for exactly this — a
 * request's outcome on the partner tab, a technique's effort on Recovery — and a status that
 * looked different in the two places would read as two different facts.
 */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, tone: PillTone = PillTone.QUIET) {
    Surface(
        color = when (tone) {
            PillTone.QUIET -> MaterialTheme.colorScheme.surfaceContainerHighest
            PillTone.POSITIVE -> MaterialTheme.colorScheme.secondaryContainer
            PillTone.NEGATIVE -> MaterialTheme.colorScheme.errorContainer
        },
        contentColor = when (tone) {
            PillTone.QUIET -> MaterialTheme.colorScheme.onSurfaceVariant
            PillTone.POSITIVE -> MaterialTheme.colorScheme.onSecondaryContainer
            PillTone.NEGATIVE -> MaterialTheme.colorScheme.onErrorContainer
        },
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
