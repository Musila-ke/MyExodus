package com.example.myexodus.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One "• item" line, drawn rather than concatenated: as a single string the bullet glyph
 * wraps into the continuation line's margin, and translators inherit a space that must be
 * preserved. The dot is its own Text so it takes the same type scale as the line — a fixed
 * dp dot drifts off the first line's centre as font scale grows.
 */
@Composable
internal fun BulletLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
