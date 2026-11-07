package com.example.myexodus.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.recovery.UrgeCatalog
import com.example.myexodus.recovery.UrgeCategory
import com.example.myexodus.streaks.HabitSummary
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.SecureSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 50

/**
 * Session/urge history (v3 W7, FR-9 analog): newest first, paged, behind the privacy gate.
 * Local-only by construction — the rows live in the encrypted store and are never mirrored
 * beyond the coarse `recovery:<technique>:<outcome>` event (INV-13).
 *
 * Lives under the Streaks tab (owner ruling 2026-11-07): it is a retrospective page, and since
 * schema v25 every row carries the habit it was logged under, so the default view is the
 * displayed habit's data with an "All habits" escape for rows from before the link existed.
 *
 * Declares its own [SecureSurface] (INV-11/M2.8) rather than relying on the tab's being
 * composed above it: the flag must belong to the surface, not to the fact that today the only
 * route here happens to keep the hub mounted.
 */
@Composable
fun HistoryView(
    sessions: RecoverySessionRepository,
    prefs: RecoveryPrefs,
    habits: List<HabitSummary>,
    displayedHabitId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureSurface()
    GatedContent(prefs = prefs, titleRes = R.string.recovery_gate_title_history) {
        HistoryList(
            sessions = sessions,
            habits = habits,
            displayedHabitId = displayedHabitId,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@Composable
private fun HistoryList(
    sessions: RecoverySessionRepository,
    habits: List<HabitSummary>,
    displayedHabitId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<RecoverySession>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    var exhausted by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var summaryVersion by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<RecoverySession?>(null) }
    // null = All habits. A page opened from a habit starts scoped to it; the filter is the
    // page's own state, so switching habits on the hub never rewrites a page already open.
    var habitFilter by rememberSaveable(displayedHabitId) { mutableStateOf(displayedHabitId) }
    val habitLabels = remember(habits) { habits.associate { it.habitId to it.label } }
    // Finding #16 (2026-10-16): the pager reads `offset`, fetches, then appends — two taps
    // inside one fetch window both read the same offset and both append, duplicating rows and
    // advancing past records never shown. The button shows no progress, so the second tap is
    // what a real user does. Serialized: the waiting tap then loads the *next* page, which is
    // what a double-tap on "Load more" asks for. A reset (after a delete) takes the same lock,
    // so it can never interleave a page fetch.
    val loadLock = remember { Mutex() }

    suspend fun load(reset: Boolean = false) = loadLock.withLock {
        withContext(Dispatchers.IO) {
            if (reset) {
                rows = emptyList()
                offset = 0
                exhausted = false
                // A filter change is a new query, not a new page: hold the skeleton so the
                // previous habit's rows never render under the new label.
                loaded = false
            }
            val page = sessions.page(PAGE_SIZE, offset, habitId = habitFilter)
            rows = rows + page
            offset += page.size
            exhausted = page.size < PAGE_SIZE
            loaded = true
        }
    }

    LaunchedEffect(habitFilter) { load(reset = true) }

    Column(modifier = modifier.fillMaxSize()) {
        BackRow(label = stringResource(R.string.recovery_history_title), onBack = onBack)
        if (habits.isNotEmpty()) {
            HistoryHabitFilter(
                habits = habits,
                selected = habitFilter,
                onSelect = { habitFilter = it },
            )
        }
        if (!loaded) {
            HistorySkeleton()
        } else {
            val nowMs = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            // Observable read (lint NonObservableLocale): a locale change recomposes the list.
            val locale = LocalConfiguration.current.locales[0]
            val groups = groupHistoryByDay(
                rows = rows,
                nowMs = nowMs,
                zone = zone,
                today = stringResource(R.string.recovery_history_today),
                yesterday = stringResource(R.string.recovery_history_yesterday),
                locale = locale,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(key = "summary") {
                    HistorySummaryCard(
                        sessions = sessions,
                        habitId = habitFilter,
                        refreshKey = summaryVersion,
                    )
                }
                groups.forEach { group ->
                    item(key = "day-${group.rows.first().id}") {
                        HistoryDaySection(
                            group = group,
                            locale = locale,
                            // Under a specific habit the filter already says which one; in
                            // All habits each row has to say it itself.
                            habitLabels = if (habitFilter == null) habitLabels else emptyMap(),
                            onDeleteRequest = { pendingDelete = it },
                        )
                    }
                }
                if (groups.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.recovery_history_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (!exhausted) {
                    item(key = "more") {
                        TextButton(
                            onClick = { scope.launch { load() } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.recovery_history_more))
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.recovery_history_delete_title)) },
            text = { Text(stringResource(R.string.recovery_history_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            withContext(Dispatchers.IO) { sessions.delete(row.id) }
                            summaryVersion++
                            load(reset = true)
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.recovery_history_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.streaks_cancel))
                }
            },
        )
    }
}

/**
 * The page's one scope control, set in the ledger's own type: a tracked label, the current
 * value, and a chevron. It pins under the header so switching habits never needs a scroll
 * back to the top. Rendered only when a habit exists — with none, All habits is the only
 * choice and the row would be a control that cannot change anything.
 */
@Composable
private fun HistoryHabitFilter(
    habits: List<HabitSummary>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val allLabel = stringResource(R.string.recovery_history_all_habits)
    val current = habits.firstOrNull { it.habitId == selected }?.label ?: allLabel
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                stringResource(R.string.recovery_history_habit_filter).uppercase(locale),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(current, style = MaterialTheme.typography.titleSmall)
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(allLabel) },
                onClick = {
                    open = false
                    onSelect(null)
                },
            )
            habits.forEach { habit ->
                DropdownMenuItem(
                    text = { Text(habit.label) },
                    onClick = {
                        open = false
                        onSelect(habit.habitId)
                    },
                )
            }
        }
    }
}

/** One section per local day, unboxed like a ledger page: a tracked day break with a hairline
 *  rule running to the margin, then the day's rows with no card around them. The day is spoken
 *  once, above its rows, instead of repeated in every row's stamp; grouped boxes were the
 *  same rounded shape stacked down the page, which is what made it read machine-made. Rows
 *  keep the log's newest-first order inside the section. */
@Composable
private fun HistoryDaySection(
    group: HistoryDayGroup,
    locale: Locale,
    habitLabels: Map<String, String>,
    onDeleteRequest: (RecoverySession) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                group.label.uppercase(locale),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(2.dp))
        group.rows.forEach { row ->
            HistoryRow(
                row = row,
                locale = locale,
                habitLabel = row.habitId?.let(habitLabels::get),
                onDelete = { onDeleteRequest(row) },
            )
        }
    }
}

/** Loading stands in for the frontispiece and the first day's rows, in the sizes they occupy:
 *  resolving never reflows the page. */
@Composable
private fun HistorySkeleton() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonBar(Modifier.width(104.dp).height(12.dp))
            SkeletonBar(Modifier.width(64.dp).height(44.dp))
            SkeletonBar(Modifier.fillMaxWidth().height(1.dp))
            SkeletonBar(Modifier.width(210.dp).height(14.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SkeletonBar(Modifier.width(88.dp).height(12.dp))
            repeat(3) { SkeletonBar(Modifier.fillMaxWidth().height(34.dp)) }
        }
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}

/**
 * One log row inside its day section: the time in a fixed ledger column, the title with one
 * muted line carrying the rest, and the row's own action menu. No card and no accent bar — the
 * row's judgement lives in its words, and the page's structure is carried by type and rules.
 */
@Composable
private fun HistoryRow(
    row: RecoverySession,
    locale: Locale,
    habitLabel: String?,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Plan §E2.11: a technique-less row that named an action is an Urge Assist episode, not a
    // bare log. The action is its title; the category and check-in join the detail line.
    val isAssist = row.technique == RecoverySession.TECHNIQUE_NONE && row.actionId != null
    val label = when {
        isAssist -> stringResource(
            row.actionId?.let { UrgeCatalog.byId(it)?.labelRes } ?: R.string.recovery_history_bucket_assist,
        )
        row.technique == RecoverySession.TECHNIQUE_NONE -> stringResource(R.string.recovery_history_logged_only)
        else -> stringResource(techniqueTitle(row.technique))
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            historyTimeLabel(
                epochMs = row.startedAtEpochMs,
                zone = ZoneId.systemDefault(),
                locale = locale,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(64.dp).padding(top = 13.dp),
        )
        Column(
            modifier = Modifier.weight(1f).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            val details = buildList {
                // The habit leads: under All habits it is the row's context, and a bare log
                // with no details otherwise would not say which streak it belongs to.
                if (habitLabel != null) add(habitLabel)
                if (isAssist) {
                    // Plan §E2.11: outcome, optional category, check-in. The category is inside
                    // the History gate, which is the page's whole content.
                    add(stringResource(outcomeLabel(row.state)))
                    row.category?.let { id ->
                        UrgeCategory.fromId(id)?.let { add(categoryLabel(it)) }
                    }
                    row.checkIn?.let { add(stringResource(checkInLabel(it))) }
                } else {
                    // No outcome for a bare log: its title already says nothing was practised,
                    // and the row is born COMPLETED, so [outcomeLabel] would call it "Completed"
                    // — one row claiming both.
                    if (row.technique != RecoverySession.TECHNIQUE_NONE) {
                        add(stringResource(outcomeLabel(row.state)))
                    }
                    val intensity = row.intensityEnd ?: row.intensityStart
                    if (intensity != null) {
                        add(stringResource(R.string.recovery_history_intensity, intensity))
                    }
                    val duration = row.durationSec
                    if (duration != null && duration > 0) {
                        add(
                            stringResource(
                                R.string.recovery_history_duration,
                                duration / 60,
                                duration % 60,
                            ),
                        )
                    }
                }
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.recovery_plan_item_actions, label),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.recovery_history_delete_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/** The one-tap check-in an assist row stored (plan §E2.11), in the log's plain words. */
private fun checkInLabel(checkIn: String): Int = when (checkIn) {
    RecoverySession.CHECK_IN_BETTER -> R.string.recovery_history_check_better
    RecoverySession.CHECK_IN_SAME -> R.string.recovery_history_check_same
    RecoverySession.CHECK_IN_WORSE -> R.string.recovery_history_check_worse
    else -> R.string.recovery_history_check_skipped
}

/** Shared with the partner dashboard's received-activity rows (W5). */
internal fun outcomeLabel(state: String): Int = when (state) {
    RecoverySession.STATE_COMPLETED -> R.string.recovery_history_completed
    RecoverySession.STATE_ABANDONED -> R.string.recovery_history_abandoned
    else -> R.string.recovery_history_interrupted
}

/**
 * The heading a history row sorts under: "Today", "Yesterday", else "Sep 27" /
 * "Sep 27, 2025" once the year differs from now's. The day is spoken only while it is still
 * current — an absolute date reads as noise on today's rows.
 *
 * Pure, with the clock, zone and day words passed in: the boundary at midnight is testable on
 * the JVM, and the copy stays in resources. The device's own zone at read time is the one used:
 * the row stores the zone it started in, the list reads in the one the user is in now (travel
 * does not rewrite history).
 */
internal fun historyDayLabel(
    epochMs: Long,
    nowMs: Long,
    zone: ZoneId,
    today: String,
    yesterday: String,
    locale: Locale = Locale.getDefault(),
): String {
    val then = Instant.ofEpochMilli(epochMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    return when {
        then.toLocalDate() == now.toLocalDate() -> today
        then.toLocalDate() == now.toLocalDate().minusDays(1) -> yesterday
        then.year == now.year -> DateTimeFormatter.ofPattern("MMM d", locale).format(then)
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(then)
    }
}

/** A row's time inside its day card ("3:42 PM"); the day itself is the card's heading. */
internal fun historyTimeLabel(
    epochMs: Long,
    zone: ZoneId,
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    .withLocale(locale)
    .format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** Rows arrive newest-first; grouping preserves that order both between and inside days. */
internal data class HistoryDayGroup(val label: String, val rows: List<RecoverySession>)

internal fun groupHistoryByDay(
    rows: List<RecoverySession>,
    nowMs: Long,
    zone: ZoneId,
    today: String,
    yesterday: String,
    locale: Locale = Locale.getDefault(),
): List<HistoryDayGroup> = rows
    .groupBy { historyDayLabel(it.startedAtEpochMs, nowMs, zone, today, yesterday, locale) }
    .map { (label, dayRows) -> HistoryDayGroup(label, dayRows) }

/** Exposed for the coaching editor too: the same gate wraps every private surface (W6). */
@Composable
fun GatedContent(
    prefs: RecoveryPrefs,
    titleRes: Int,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val title = stringResource(titleRes)
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(GateUiState.Pending) }

    LaunchedEffect(attempt) {
        state = if (activity == null) {
            // Hosted outside a FragmentActivity (previews/tests): nothing to prompt with.
            GateUiState.Open
        } else {
            when (PrivacyGate.ensureUnlocked(activity, title)) {
                GateResult.Unlocked -> GateUiState.Open
                GateResult.UnlockedWithoutSecureLock ->
                    if (prefs.consumeNoLockBanner()) GateUiState.OpenNoLock else GateUiState.Open
                GateResult.Cancelled -> GateUiState.Locked
            }
        }
    }

    when (state) {
        GateUiState.Pending -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        GateUiState.Locked -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.recovery_gate_locked),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = { state = GateUiState.Pending; attempt++ }) {
                Text(stringResource(R.string.recovery_gate_unlock))
            }
        }
        GateUiState.Open -> content()
        GateUiState.OpenNoLock -> Column(Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.recovery_gate_no_lock_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

private enum class GateUiState { Pending, Locked, Open, OpenNoLock }
