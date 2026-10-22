package com.example.myexodus.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.insights.InsightDay

private val CHART_HEIGHT = 72.dp
private val BAR_SHAPE = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp)

/**
 * Blocked/override bars for the window. One shared scale (the tallest day), value printed
 * above each bar, stacked segments plus a text legend — colour is never the only encoding,
 * and each column merges its own sentence for TalkBack.
 */
@Composable
internal fun AttemptBarStrip(days: List<InsightDay>, modifier: Modifier = Modifier) {
    val max = maxAttempts(days)
    val blockedColor = MaterialTheme.colorScheme.primary
    val overrideColor = MaterialTheme.colorScheme.tertiary
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (day in days) {
            val dayLabel = shortDayLabel(day.date)
            val total = day.blocked + day.overrides
            val description = if (total == 0) {
                stringResource(R.string.insights_attempt_bar_none, dayLabel)
            } else {
                stringResource(
                    R.string.insights_attempt_bar_description,
                    dayLabel,
                    day.blocked,
                    day.overrides,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    total.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier.height(CHART_HEIGHT).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (total > 0) {
                        Column(
                            modifier = Modifier
                                .width(18.dp)
                                .fillMaxHeight(shareOf(total.toLong(), max.toLong()))
                                .clip(BAR_SHAPE),
                        ) {
                            if (day.overrides > 0) {
                                Box(
                                    Modifier.fillMaxWidth()
                                        .weight(day.overrides.toFloat())
                                        .background(overrideColor),
                                )
                            }
                            if (day.blocked > 0) {
                                Box(
                                    Modifier.fillMaxWidth()
                                        .weight(day.blocked.toFloat())
                                        .background(blockedColor),
                                )
                            }
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth(0.6f)
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    dayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Credited screen time for the window, one bar per day on a shared scale. */
@Composable
internal fun UsageBarStrip(days: List<InsightDay>, modifier: Modifier = Modifier) {
    val max = maxCreditedMs(days)
    val barColor = MaterialTheme.colorScheme.tertiary
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (day in days) {
            val dayLabel = shortDayLabel(day.date)
            val description = stringResource(
                R.string.insights_usage_bar_description,
                dayLabel,
                compactDuration(day.creditedMs),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    compactDuration(day.creditedMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier.height(CHART_HEIGHT).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (day.creditedMs > 0) {
                        Box(
                            Modifier.width(18.dp)
                                .fillMaxHeight(shareOf(day.creditedMs, max))
                                .clip(BAR_SHAPE)
                                .background(barColor),
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth(0.6f)
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    dayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Legend swatch + name for the stacked strip. */
@Composable
internal fun ChartLegend(vararg entries: Pair<String, Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((label, color) in entries) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One ranked row: name, value, and a proportional share bar (not interactive). */
@Composable
internal fun RankedBarRow(label: String, value: String, fraction: Float) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
