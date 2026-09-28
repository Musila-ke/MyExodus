package com.example.myexodus.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.recovery.RecoveryResourceCatalog
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.SecureSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 50

/**
 * Session/urge history (v3 W7, FR-9 analog): newest first, paged, behind the privacy gate.
 * Local-only by construction — the rows live in the encrypted store and are never mirrored
 * beyond the coarse `recovery:<technique>:<outcome>` event (INV-13).
 *
 * Declares its own [SecureSurface] (INV-11/M2.8) rather than relying on the Recovery tab's
 * being composed above it: the flag must belong to the surface, not to the fact that today
 * the only route here happens to keep the hub mounted.
 */
@Composable
fun HistoryView(
    sessions: RecoverySessionRepository,
    prefs: RecoveryPrefs,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureSurface()
    GatedContent(prefs = prefs, titleRes = R.string.recovery_gate_title_history) {
        HistoryList(sessions = sessions, onBack = onBack, modifier = modifier)
    }
}

@Composable
private fun HistoryList(
    sessions: RecoverySessionRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<RecoverySession>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    var exhausted by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun loadMore() = withContext(Dispatchers.IO) {
        val page = sessions.page(PAGE_SIZE, offset)
        rows = rows + page
        offset += page.size
        exhausted = page.size < PAGE_SIZE
        loaded = true
    }

    LaunchedEffect(Unit) { loadMore() }

    Column(modifier = modifier.fillMaxSize()) {
        BackRow(label = stringResource(R.string.recovery_history_title), onBack = onBack)
        HistorySummaryCard(
            sessions = sessions,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.recovery_history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        stringResource(R.string.recovery_history_log_header),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(rows, key = { it.id }) { row ->
                    HistoryRow(row)
                    HorizontalDivider()
                }
                if (!exhausted) {
                    item {
                        TextButton(
                            onClick = { scope.launch(Dispatchers.IO) { loadMore() } },
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        ) {
                            Text(stringResource(R.string.recovery_history_more))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One log row, laid out on the reference's `11.56.04` task rows: a rounded accent bar down the
 * left, the title with its day stamp trailing, and one muted line carrying everything else.
 * The bar is the row's only judgement — teal for a run that finished or a bare log, toneless
 * for one that stopped early; the app marks what was practised, not what was not.
 */
@Composable
private fun HistoryRow(row: RecoverySession) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (row.hasAccent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            if (row.technique == RecoverySession.TECHNIQUE_NONE) {
                                R.string.recovery_history_logged_only
                            } else {
                                techniqueTitle(row.technique)
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        historyTimestampLabel(
                            epochMs = row.startedAtEpochMs,
                            nowMs = System.currentTimeMillis(),
                            zone = ZoneId.systemDefault(),
                            today = stringResource(R.string.recovery_history_today),
                            yesterday = stringResource(R.string.recovery_history_yesterday),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val details = buildList {
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
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Teal for a finished run or a bare log; a run that stopped early carries no accent. */
private val RecoverySession.hasAccent: Boolean
    get() = technique == RecoverySession.TECHNIQUE_NONE ||
        state == RecoverySession.STATE_COMPLETED

/** Shared with the partner dashboard's received-activity rows (W5). */
internal fun outcomeLabel(state: String): Int = when (state) {
    RecoverySession.STATE_COMPLETED -> R.string.recovery_history_completed
    RecoverySession.STATE_ABANDONED -> R.string.recovery_history_abandoned
    else -> R.string.recovery_history_interrupted
}

/**
 * The trailing day+time stamp of a history row: "Today · 3:42 PM", "Yesterday · 3:42 PM", else
 * "Sep 27 · 3:42 PM" / "Sep 27, 2025 · 3:42 PM" once the year differs from now's. The day is
 * spoken only while it is still current — an absolute date reads as noise on today's row.
 *
 * Pure, with the clock, zone and day words passed in: the boundary at midnight is testable on
 * the JVM, and the copy stays in resources. The device's own zone at read time is the one used:
 * the row stores the zone it started in, the list reads in the one the user is in now (travel
 * does not rewrite history).
 */
internal fun historyTimestampLabel(
    epochMs: Long,
    nowMs: Long,
    zone: ZoneId,
    today: String,
    yesterday: String,
    locale: Locale = Locale.getDefault(),
): String {
    val then = Instant.ofEpochMilli(epochMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(then)
    val day = when {
        then.toLocalDate() == now.toLocalDate() -> today
        then.toLocalDate() == now.toLocalDate().minusDays(1) -> yesterday
        then.year == now.year -> DateTimeFormatter.ofPattern("MMM d", locale).format(then)
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(then)
    }
    return "$day · $time"
}

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
