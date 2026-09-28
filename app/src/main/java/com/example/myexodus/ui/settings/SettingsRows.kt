package com.example.myexodus.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The two row shapes Settings is built from (owner sign-off 2026-09-22, porting the reference's
 * flat index): a row that goes somewhere carries a chevron, a row that toggles carries the
 * switch. Every row leads with an icon — that leading glyph is what makes a flat list scannable
 * without section headers, which is the one thing the reference layout needs to work.
 */
internal enum class StatusTone { GOOD, ATTENTION, INFO }

@Composable
internal fun StatusDot(tone: StatusTone, modifier: Modifier = Modifier) {
    val accent = when (tone) {
        StatusTone.GOOD -> MaterialTheme.colorScheme.primary
        StatusTone.ATTENTION -> MaterialTheme.colorScheme.error
        StatusTone.INFO -> MaterialTheme.colorScheme.outline
    }
    Box(modifier.size(8.dp).background(accent, CircleShape))
}

/** Divider *between* rows, inset past the row's own start padding. */
@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** A row that opens something. The row itself is the target, so the chevron is decorative. */
@Composable
internal fun IndexRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tone: StatusTone? = null,
) {
    SettingsRowShell(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = modifier.clickable(onClick = onClick),
        leading = tone?.let { t -> { StatusDot(t) } },
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        },
    )
}

/** A row that toggles in place. */
@Composable
internal fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    warning: String? = null,
) {
    SettingsRowShell(
        icon = icon,
        title = title,
        subtitle = subtitle,
        warning = warning,
        modifier = modifier,
        trailing = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
                // The switch is a sibling of its title, not its child, so its own label is all
                // TalkBack has to go on — a column of bare "On/Off" says nothing (WCAG 2.4.6,
                // findings #25/#26).
                modifier = Modifier.semantics { contentDescription = title },
            )
        },
    )
}

/**
 * Icon, up to three stacked text lines, and whatever the row's trailing slot holds. [leading]
 * rides inside the subtitle line only — a status dot belongs beside the status word, not beside
 * the row's title.
 */
@Composable
private fun SettingsRowShell(
    icon: ImageVector,
    title: String,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    warning: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    leading?.invoke()
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (warning != null) {
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        trailing()
    }
}
