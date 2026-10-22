package com.example.myexodus.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.insights.HabitInsight
import com.example.myexodus.insights.InsightPackage
import com.example.myexodus.insights.InsightTarget
import com.example.myexodus.insights.InsightsSnapshot
import com.example.myexodus.insights.RecoveryInsight
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.ui.formatDuration
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.streaks.daysFree
import com.example.myexodus.ui.streaks.habitDays

/**
 * The Insights spoke (owner task #22): blocking, screen time, urge practice, and every
 * streak's record, all from local journals. Rendered inside the Streaks tab's scroller — no
 * scroll or padding of its own — and loaded by [InsightsViewModel] only while it is open.
 *
 * The page follows the reference grammar the owner chose for dense surfaces: one number pair
 * up top, then one message per chart, ranked rows for detail, and an honest empty/error state
 * per section rather than a fabricated zero. Nothing here is a score.
 */
@Composable
fun InsightsScreen(
    state: InsightsViewModel.State,
    habit: HabitState?,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text(
                stringResource(R.string.insights_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.insights_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            InsightsViewModel.State.Idle,
            InsightsViewModel.State.Loading,
            -> LoadingCard()

            InsightsViewModel.State.Unavailable -> UnavailableCard(onRetry)

            is InsightsViewModel.State.Ready -> ReadyContent(state.snapshot, habit)
        }
    }
}

@Composable
private fun LoadingCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.insights_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnavailableCard(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.insights_unavailable_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.insights_unavailable_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.insights_retry))
            }
        }
    }
}

@Composable
private fun ReadyContent(snapshot: InsightsSnapshot, habit: HabitState?) {
    if (snapshot.isEmpty) {
        SectionCard(title = stringResource(R.string.insights_empty_title)) {
            Text(
                stringResource(R.string.insights_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            label = stringResource(R.string.insights_blocked_label),
            value = snapshot.blockedTotal.toString(),
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.insights_overrides_label),
            value = snapshot.overridesTotal.toString(),
            modifier = Modifier.weight(1f),
        )
    }

    SectionCard(
        title = stringResource(R.string.insights_blocks_title),
        caption = stringResource(R.string.insights_blocks_caption),
    ) {
        if (snapshot.blockedTotal == 0 && snapshot.overridesTotal == 0) {
            EmptyLine(stringResource(R.string.insights_blocks_empty))
        } else {
            AttemptBarStrip(snapshot.days)
            ChartLegend(
                stringResource(R.string.insights_legend_blocked) to MaterialTheme.colorScheme.primary,
                stringResource(R.string.insights_legend_overrides) to MaterialTheme.colorScheme.tertiary,
            )
        }
    }

    SectionCard(
        title = stringResource(R.string.insights_usage_title),
        trailing = formatDuration(snapshot.screenTimeTotalMs),
        caption = stringResource(
            R.string.insights_usage_caption,
            formatDuration(snapshot.screenTimeTodayMs),
        ),
    ) {
        if (snapshot.screenTimeTotalMs == 0L) {
            EmptyLine(stringResource(R.string.insights_usage_empty))
        } else {
            UsageBarStrip(snapshot.days)
        }
    }

    if (snapshot.topPackages.isNotEmpty()) {
        SectionCard(title = stringResource(R.string.insights_time_where_title)) {
            val max = snapshot.topPackages.first().creditedMs
            for (pkg in snapshot.topPackages) {
                PackageRow(pkg, max)
            }
        }
    }

    if (snapshot.topTargets.isNotEmpty()) {
        SectionCard(title = stringResource(R.string.insights_most_blocked_title)) {
            val max = snapshot.topTargets.first().count.toLong()
            for (target in snapshot.topTargets) {
                TargetRow(target, max)
            }
        }
    }

    SectionCard(title = stringResource(R.string.insights_recovery_title)) {
        RecoveryRows(snapshot.recovery)
        if (snapshot.recovery.allTime > 0) {
            Text(
                pluralStringResource(
                    R.plurals.insights_recovery_all_time,
                    snapshot.recovery.allTime.toInt(),
                    snapshot.recovery.allTime.toInt(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SectionCard(title = stringResource(R.string.insights_habits_title)) {
        if (snapshot.habits.isEmpty()) {
            EmptyLine(stringResource(R.string.insights_habits_empty))
        } else {
            for (entry in snapshot.habits) {
                HabitRow(entry, currentDays = habit?.takeIf { it.habitId == entry.habitId })
            }
        }
    }
}

@Composable
private fun PackageRow(pkg: InsightPackage, max: Long) {
    RankedBarRow(
        label = rememberAppLabel(pkg.packageName),
        value = formatDuration(pkg.creditedMs),
        fraction = shareOf(pkg.creditedMs, max),
    )
}

@Composable
private fun TargetRow(target: InsightTarget, max: Long) {
    RankedBarRow(
        label = rememberAppLabel(target.target),
        value = target.count.toString(),
        fraction = shareOf(target.count.toLong(), max),
    )
}

@Composable
private fun RecoveryRows(recovery: RecoveryInsight) {
    if (
        recovery.completedTechniques == 0 &&
        recovery.loggedOnly == 0 &&
        recovery.abandoned == 0 &&
        recovery.interrupted == 0
    ) {
        EmptyLine(stringResource(R.string.insights_recovery_empty))
        return
    }
    RecoveryRow(R.string.insights_recovery_completed, recovery.completedTechniques)
    RecoveryRow(R.string.insights_recovery_logged, recovery.loggedOnly)
    RecoveryRow(R.string.insights_recovery_abandoned, recovery.abandoned)
    RecoveryRow(R.string.insights_recovery_interrupted, recovery.interrupted)
}

@Composable
private fun RecoveryRow(label: Int, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One habit's record. The selected habit also carries its live run, on the same display
 *  scale the hero uses ([daysFree]); a non-selected habit has no live accumulator here. */
@Composable
private fun HabitRow(entry: HabitInsight, currentDays: HabitState?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                entry.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val bestDays = habitDays(entry.bestElapsedMs)
            Text(
                if (bestDays > 0) {
                    pluralStringResource(R.plurals.insights_habit_best, bestDays, bestDays)
                } else {
                    stringResource(R.string.insights_habit_no_best)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val current = currentDays?.let { daysFree(it.currentStreakElapsedMs) }
            Text(
                if (current != null) {
                    pluralStringResource(R.plurals.insights_habit_current, current, current)
                } else {
                    ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                pluralStringResource(
                    R.plurals.insights_habit_relapses,
                    entry.relapses,
                    entry.relapses,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.insights_stat_caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    trailing: String? = null,
    caption: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) {
                    Text(
                        trailing,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
