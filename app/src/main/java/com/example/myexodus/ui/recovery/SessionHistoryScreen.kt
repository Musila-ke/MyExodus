package com.example.myexodus.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.recovery.RecoveryResourceCatalog
import com.example.myexodus.recovery.RecoverySessionRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 50

/**
 * Session/urge history (v3 W7, FR-9 analog): newest first, paged, behind the privacy gate.
 * Local-only by construction — the rows live in the encrypted store and are never mirrored
 * beyond the coarse `recovery:<technique>:<outcome>` event (INV-13).
 */
@Composable
fun HistoryView(
    sessions: RecoverySessionRepository,
    prefs: RecoveryPrefs,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.recovery_back)) }
            Text(
                stringResource(R.string.recovery_history_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
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

@Composable
private fun HistoryRow(row: RecoverySession) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
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
                    stringResource(outcomeLabel(row.state)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatTimestamp(row.startedAtEpochMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val details = buildList {
                if (row.intensityEnd != null) {
                    add("Intensity ${row.intensityEnd}/10")
                } else if (row.intensityStart != null) {
                    add("Intensity ${row.intensityStart}/10")
                }
                if (row.durationSec != null && row.durationSec > 0) {
                    add("${row.durationSec / 60}m ${row.durationSec % 60}s")
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

/** Shared with the partner dashboard's received-activity rows (W5). */
internal fun outcomeLabel(state: String): Int = when (state) {
    RecoverySession.STATE_COMPLETED -> R.string.recovery_history_completed
    RecoverySession.STATE_ABANDONED -> R.string.recovery_history_abandoned
    else -> R.string.recovery_history_interrupted
}

/** The device's own zone at read time: the row stores the zone it started in, the list reads in
 *  the one the user is in now (travel does not rewrite history). */
private fun formatTimestamp(epochMs: Long): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

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
