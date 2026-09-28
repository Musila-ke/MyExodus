package com.example.myexodus.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.data.db.dao.SessionCount
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.recovery.RecoverySessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DAY_MS = 24L * 60L * 60L * 1000L

/** Range chips. W/M/All rather than the reference's D/W/M/Y, spelled out rather than
 *  initialled: a single day of personal urge logs is typically 0–3 rows, so a daily bucket is
 *  noise, and a year bucket is empty for nearly every user of a V1 install. */
internal enum class HistoryRange(val windowMs: Long) {
    WEEK(7L * DAY_MS),
    MONTH(30L * DAY_MS),
    ALL(0L),
}

/**
 * Terminal counts for one window, in the three buckets the page can actually back.
 *
 * Both the switch and the third bucket are corrections (owner 2026-09-22). The switch is gone
 * because "Completed vs ended early" was the schema talking: neither word is one a user would
 * ask with, and "Ended early" existed only to merge Stopped with Interrupted. The buckets
 * distinguish what changed for the user, not what changed in the row:
 *
 * - [loggedOnly] — a bare log, no technique attempted. Born COMPLETED with technique `none`
 *   (`RecoverySessionRepository.logOnly`), so a summary keyed on state alone counted "tapped
 *   Log an urge and went home" as a finished session while the list one row below called the
 *   same row "Logged, no technique".
 * - [completed] — a technique was practised, to the end.
 * - [notFinished] — Stopped *or* Interrupted. Merged deliberately: the row still says which, and
 *   at summary level the user's question is only whether it ran to the end. [STARTED] rows are
 *   excluded from all three — a session still running has no outcome yet, and counting it either
 *   way would be a claim about the future.
 */
internal data class HistoryCounts(
    val completed: Int = 0,
    val notFinished: Int = 0,
    val loggedOnly: Int = 0,
) {
    val total: Int get() = completed + notFinished + loggedOnly
}

internal fun summarize(counts: List<SessionCount>): HistoryCounts {
    var completed = 0
    var notFinished = 0
    var loggedOnly = 0
    counts.forEach { row ->
        val n = row.count.toInt()
        when {
            row.technique == RecoverySession.TECHNIQUE_NONE -> loggedOnly += n
            row.state == RecoverySession.STATE_COMPLETED -> completed += n
            row.state == RecoverySession.STATE_ABANDONED ||
                row.state == RecoverySession.STATE_INTERRUPTED -> notFinished += n
        }
    }
    return HistoryCounts(completed = completed, notFinished = notFinished, loggedOnly = loggedOnly)
}

/** Start of the window, or 0 for all time. Pure so the boundary is testable without a clock. */
internal fun windowStartMs(range: HistoryRange, nowMs: Long): Long =
    if (range.windowMs == 0L) 0L else nowMs - range.windowMs

/**
 * The history summary, laid out on the reference's `13.10.55` hero card: eyebrow over the
 * figure, the range as full-width equal segments *inside* the card, and the breakdown under it.
 * The reference's chart below the chips is refused — there is no per-day history to plot, the
 * same refusal ADR #76 and ADR #79 made for Home and Streaks. Its three buckets are the honest
 * substitute, and reuse Home's stat-trio shape so the app has one way of showing a set of counts.
 *
 * Deliberately scopes **only itself** — the log below stays the full chronological record. The
 * reference's Income/Spending switch filters its whole view, but those are disjoint categories
 * of money; these are readings of one log, and hiding a relapse from the record is the wrong
 * affordance in a recovery app. List filtering is a small add if it is ever wanted.
 */
@Composable
internal fun HistorySummaryCard(
    sessions: RecoverySessionRepository,
    modifier: Modifier = Modifier,
) {
    var range by rememberSaveable { mutableStateOf(HistoryRange.WEEK) }
    var counts by remember { mutableStateOf<HistoryCounts?>(null) }

    LaunchedEffect(range) {
        counts = withContext(Dispatchers.IO) {
            summarize(sessions.countsByStateSince(windowStartMs(range, System.currentTimeMillis())))
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val loaded = counts
            when {
                loaded == null -> Unit
                loaded.total == 0 -> Text(
                    stringResource(R.string.recovery_history_summary_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        stringResource(R.string.recovery_history_summary_eyebrow),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        loaded.total.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // The figure block above is absent on an empty window, but the scope control is not:
            // "no urges in this period" is itself a claim about the period.
            RangeChips(selected = range, onSelect = { range = it })
            // Every bucket on every render, zeros included: a label that appears only once it has
            // a count cannot be compared across periods.
            if (loaded != null && loaded.total > 0) {
                Row(Modifier.fillMaxWidth()) {
                    Bucket(R.string.recovery_history_completed, loaded.completed)
                    Bucket(R.string.recovery_history_bucket_not_finished, loaded.notFinished)
                    Bucket(R.string.recovery_history_bucket_logged_only, loaded.loggedOnly)
                }
            }
        }
    }
}

@Composable
private fun RowScope.Bucket(labelRes: Int, value: Int) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RangeChips(selected: HistoryRange, onSelect: (HistoryRange) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HistoryRange.entries.forEach { range ->
            SegmentPill(
                label = stringResource(
                    when (range) {
                        HistoryRange.WEEK -> R.string.recovery_history_range_week
                        HistoryRange.MONTH -> R.string.recovery_history_range_month
                        HistoryRange.ALL -> R.string.recovery_history_range_all
                    },
                ),
                active = range == selected,
                onClick = { onSelect(range) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SegmentPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .clip(shape)
            .then(
                if (active) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
