package com.example.myexodus.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.ui.streaks.MilestoneWindow
import com.example.myexodus.ui.streaks.daysFree
import com.example.myexodus.ui.streaks.milestoneWindow

/**
 * Home's milestone ladder as a permanent section (product call 2026-09-29): it used to render
 * only once the streak state had loaded, which read as a late pop-in. It now occupies its slot
 * from the first frame and carries four explicit states:
 *
 *  - **loading** — the card's own chrome drawn with skeletons, so the section never shifts
 *    when the real values land;
 *  - **empty** — no habit yet: the un-filled ladder with the rings still marked, plus the way
 *    to start one;
 *  - **error** — the streak read failed (store refused to open, or the read threw); the retry
 *    re-runs the refresh that produced it;
 *  - **success** — one cell per day of the current ladder stretch (days 1..90, then the new
 *    count from 91 toward 180, then 181 toward 365), filled to the current day, with each
 *    rung ringed and gold once the milestone row says it was actually reached.
 *
 * Every value here is the same state the hero renders, so the two can never disagree.
 */
@Composable
internal fun MilestoneSectionCard(
    habit: HabitState?,
    streakResolved: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    onOpenStreaks: () -> Unit,
) {
    when {
        // Outranks the skeleton deliberately: a failed read never resolves, so without this
        // branch the section would sit in loading forever with no way to act on it.
        loadFailed -> MilestoneCardShell {
            Text(
                stringResource(R.string.home_grid_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.home_grid_error_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.home_grid_retry)) }
        }

        // The title lands with the first frame — only the values it will carry are skeletoned,
        // so the section reads as present-and-filling rather than absent.
        !streakResolved -> MilestoneCardShell {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.home_grid_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                SkeletonBar(Modifier.width(72.dp).height(14.dp))
            }
            SkeletonBar(Modifier.fillMaxWidth().height(gridHeight(EmptyMilestoneWindow)))
            SkeletonBar(Modifier.width(180.dp).height(14.dp))
        }

        habit == null -> MilestoneCardShell {
            Text(
                stringResource(R.string.home_grid_title),
                style = MaterialTheme.typography.titleMedium,
            )
            MilestoneGrid(
                days = 0,
                achieved = emptyList(),
                window = EmptyMilestoneWindow,
                modifier = Modifier.fillMaxWidth().height(gridHeight(EmptyMilestoneWindow)),
            )
            Text(
                stringResource(R.string.home_grid_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenStreaks) { Text(stringResource(R.string.home_streak_cta)) }
        }

        else -> MilestoneCardShell {
            MilestoneCardContent(
                // The ordinal display day (Day 1 on a fresh streak — never "Day 0 of 90"):
                // cell fill, header, and the app's other counters share one reading.
                days = daysFree(habit.currentStreakElapsedMs),
                achieved = habit.milestones
                    .mapNotNull { it.achievedAt?.let { _ -> it.milestoneDay } },
            )
        }
    }
}

/** Shared card chrome — identical across the four states so the section never re-skins. */
@Composable
private fun MilestoneCardShell(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun MilestoneCardContent(days: Int, achieved: List<Int>) {
    val window = milestoneWindow(days)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.home_grid_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (days > window.endDay) {
                stringResource(R.string.home_grid_all_days, window.endDay)
            } else {
                stringResource(R.string.home_grid_day_of, days, window.endDay)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
    MilestoneGrid(
        days = days,
        achieved = achieved,
        window = window,
        modifier = Modifier.fillMaxWidth().height(gridHeight(window)),
    )
    Text(
        stringResource(
            if (window.rungs.size == 1) R.string.home_grid_legend_single else R.string.home_grid_legend,
            window.rungs.joinToString(", "),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The loading placeholder: the skeleton and the content occupy the same slots, so resolving
 *  swaps values in place instead of reflowing the card. */
@Composable
private fun SkeletonBar(modifier: Modifier) {
    Box(
        modifier.background(
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            MaterialTheme.shapes.small,
        ),
    )
}

/** Reading order is day order: the stretch's first day opens the top row. The empty and
 *  loading states always draw the first stretch, the only one reachable without data. */
private const val GridColumns = 30
private val GridCellHeight = 12.dp
private val GridGap = 2.dp
private val EmptyMilestoneWindow = milestoneWindow(0)

private fun gridRows(window: MilestoneWindow): Int =
    (window.length + GridColumns - 1) / GridColumns

private fun gridHeight(window: MilestoneWindow): Dp {
    val rows = gridRows(window)
    return GridCellHeight * rows + GridGap * (rows - 1)
}

@Composable
private fun MilestoneGrid(
    days: Int,
    achieved: List<Int>,
    window: MilestoneWindow,
    modifier: Modifier = Modifier,
) {
    val filled = MaterialTheme.colorScheme.primary
    val gold = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val ring = MaterialTheme.colorScheme.outline
    val achievedDays = remember(achieved) { achieved.toSet() }
    val total = gridRows(window) * GridColumns

    Canvas(modifier) {
        val gap = GridGap.toPx()
        val cellHeight = GridCellHeight.toPx()
        val cellWidth = (size.width - gap * (GridColumns - 1)) / GridColumns
        val corner = CornerRadius(cellHeight / 2f)
        repeat(total) { index ->
            // The tail of the last row has no day belonging to this stretch; leave it blank
            // so the grid visibly ends where the stretch does.
            if (index >= window.length) return@repeat
            val day = window.startDay + index
            val topLeft = Offset(
                x = (index % GridColumns) * (cellWidth + gap),
                y = (index / GridColumns) * (cellHeight + gap),
            )
            val cellSize = Size(cellWidth, cellHeight)
            val done = day <= days
            when {
                done && day in achievedDays -> drawRoundRect(gold, topLeft, cellSize, corner)
                done -> drawRoundRect(filled, topLeft, cellSize, corner)
                // A rung still ahead keeps its own mark, so the ladder is readable before
                // the days are filled in.
                MilestoneEvent.MILESTONE_DAYS.contains(day) -> {
                    drawRoundRect(track, topLeft, cellSize, corner)
                    drawRoundRect(
                        color = ring,
                        topLeft = topLeft,
                        size = cellSize,
                        cornerRadius = corner,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
                else -> drawRoundRect(track, topLeft, cellSize, corner)
            }
        }
    }
}
