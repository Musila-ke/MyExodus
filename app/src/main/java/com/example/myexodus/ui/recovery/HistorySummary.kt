package com.example.myexodus.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myexodus.R
import com.example.myexodus.data.db.dao.SessionCount
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.recovery.RecoverySessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DAY_MS = 24L * 60L * 60L * 1000L

/** Range tabs. W/M/All rather than the reference's D/W/M/Y, spelled out rather than
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
 * The history frontispiece: an unboxed editorial masthead rather than the reference's boxed
 * stat card. The earlier version — eyebrow, big figure, a segmented proportion bar, and three
 * equal swatch-and-count columns — was the stock generated dashboard: three equal columns, a
 * legend that only restated them, and a card box identical to the day cards below, so the page
 * had one shape repeated. Owner call 2026-11-07: it looked machine-made.
 *
 * The replacement keeps the same facts and gives the page a voice:
 * - The figure is the hero, set in Fraunces at display size in ink. Gold stays reserved for
 *   streaks and milestones, so the history count never borrows the streak's colour.
 * - The range is a quiet index-tab row beside the eyebrow, not a row of pills. Active is
 *   carried by colour *and* an underline, and the tabs stay put across ranges.
 * - A single hairline rule separates the figure from the breakdown, which reads as one line
 *   of prose ("Completed 1 · Not finished 0 · Logged only 0") instead of three columns. Every
 *   bucket appears on every render, zeros included: a label that appears only once it has a
 *   count cannot be compared across periods.
 *
 * The reference's chart below the chips is refused — there is no per-day history to plot, the
 * same refusal ADR #76 and ADR #79 made for Home and Streaks.
 *
 * [habitId] follows the page's habit filter (null = All habits) so the figure always counts
 * the same rows the list below shows. The range tabs deliberately scope **only** the summary:
 * hiding a relapse from the chronological record is the wrong affordance in a recovery app,
 * and the habit filter is a scope (whose log this is), not a category switch.
 */
@Composable
internal fun HistorySummaryCard(
    sessions: RecoverySessionRepository,
    modifier: Modifier = Modifier,
    habitId: String? = null,
    refreshKey: Int = 0,
) {
    var range by rememberSaveable { mutableStateOf(HistoryRange.WEEK) }
    var counts by remember { mutableStateOf<HistoryCounts?>(null) }
    // Observable read (lint NonObservableLocale): a locale change recomposes the eyebrow and tabs.
    val locale = LocalConfiguration.current.locales[0]

    LaunchedEffect(range, habitId, refreshKey) {
        counts = withContext(Dispatchers.IO) {
            summarize(
                sessions.countsByStateSince(
                    windowStartMs(range, System.currentTimeMillis()),
                    habitId = habitId,
                ),
            )
        }
    }

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The eyebrow takes the residual width so the tabs keep their intrinsic size even
            // at a large accessibility font scale; the eyebrow wraps instead of squeezing them.
            Text(
                stringResource(R.string.recovery_history_summary_eyebrow).uppercase(locale),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            RangeTabs(selected = range, onSelect = { range = it })
        }
        // The figure keeps its slot in every state: resolving or switching ranges never reflows
        // the page, and an empty period reads "0" — a real answer, not a blank. A minimum, not a
        // fixed height: at a large accessibility font scale the numeral grows instead of clipping.
        Box(Modifier.heightIn(min = 64.dp), contentAlignment = Alignment.BottomStart) {
            val loaded = counts
            if (loaded == null) {
                Box(
                    Modifier
                        .width(64.dp)
                        .height(44.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            MaterialTheme.shapes.small,
                        ),
                )
            } else {
                Text(
                    loaded.total.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        val loaded = counts
        when {
            loaded == null -> Box(
                Modifier
                    .padding(vertical = 3.dp)
                    .width(200.dp)
                    .height(14.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        MaterialTheme.shapes.small,
                    ),
            )
            loaded.total == 0 -> Text(
                stringResource(R.string.recovery_history_summary_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val completed = stringResource(R.string.recovery_history_completed)
                val notFinished = stringResource(R.string.recovery_history_bucket_not_finished)
                val loggedOnly = stringResource(R.string.recovery_history_bucket_logged_only)
                Text(
                    listOf(
                        stringResource(R.string.recovery_history_summary_part, completed, loaded.completed),
                        stringResource(R.string.recovery_history_summary_part, notFinished, loaded.notFinished),
                        stringResource(R.string.recovery_history_summary_part, loggedOnly, loaded.loggedOnly),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Index tabs, not pills: words under a hairline that fills for the active range. Colour plus
 * the underline, so selection never rests on colour alone; `selectableGroup`/`Role.Tab` give
 * TalkBack the "selected" state. Every tab keeps the underline slot, so switching shifts no
 * layout.
 */
@Composable
private fun RangeTabs(selected: HistoryRange, onSelect: (HistoryRange) -> Unit) {
    Row(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HistoryRange.entries.forEach { range ->
            val active = range == selected
            Column(
                // Intrinsic width: the underline below is fillMaxWidth, and without this the
                // column claims the row's whole width per tab — each tab then measures a full
                // screen wide, pushing the other two and the eyebrow off-screen.
                Modifier
                    .width(IntrinsicSize.Max)
                    .clip(MaterialTheme.shapes.small)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(range) })
                    .padding(vertical = 12.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(
                        when (range) {
                            HistoryRange.WEEK -> R.string.recovery_history_range_week
                            HistoryRange.MONTH -> R.string.recovery_history_range_month
                            HistoryRange.ALL -> R.string.recovery_history_range_all
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
    }
}
