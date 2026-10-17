package com.example.myexodus.ui.recovery

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.RiskWindow
import com.example.myexodus.recovery.RecoveryPlanRepository
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.DayChips
import com.example.myexodus.ui.TimeField
import com.example.myexodus.ui.daysLabel
import com.example.myexodus.ui.daysMaskToString
import com.example.myexodus.ui.daysStringToMask
import com.example.myexodus.ui.formatTime
import com.example.myexodus.ui.SecureSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Willpower / risk windows (v3 W4, Req 19): at most three local-time windows, each a range plus
 * a day mask. The list is the same shape the evaluator reads, so an edit shows up in the next
 * evaluation with no resync. One window always remains — "never at risk" is expressed by
 * switching every window off, not by having none (the repository re-seeds a default otherwise).
 */
@Composable
fun RiskWindowsScreen(
    planRepository: RecoveryPlanRepository,
    prefs: RecoveryPrefs,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureSurface()
    GatedContent(prefs = prefs, titleRes = R.string.recovery_gate_title_windows) {
        RiskWindowsBody(planRepository = planRepository, onBack = onBack, modifier = modifier)
    }
}

@Composable
private fun RiskWindowsBody(
    planRepository: RecoveryPlanRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var windows by remember { mutableStateOf<List<RiskWindow>?>(null) }
    var editing by remember { mutableStateOf<RiskWindow?>(null) }
    var adding by remember { mutableStateOf(false) }

    suspend fun reload() = withContext(Dispatchers.IO) {
        windows = planRepository.windows()
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = modifier.fillMaxSize()) {
        BackRow(
            label = stringResource(R.string.recovery_windows_title),
            onBack = onBack,
            trailing = {
                IconButton(
                    onClick = { adding = true },
                    enabled = (windows?.size ?: 0) < RiskWindow.MAX_WINDOWS,
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.recovery_windows_add))
                }
            },
        )
        val rows = windows
        if (rows == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.recovery_windows_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rows.forEach { window ->
                WindowRow(
                    window = window,
                    canDelete = rows.size > 1,
                    onToggle = { enabled ->
                        scope.launch(Dispatchers.IO) { planRepository.updateWindow(window.copy(enabled = enabled)); reload() }
                    },
                    onEdit = { editing = window },
                    onDelete = { scope.launch(Dispatchers.IO) { planRepository.deleteWindow(window); reload() } },
                )
            }
        }
    }

    // A new window starts from the default's shape (20:00–06:00, every day), id 0 = unsaved.
    val dialogWindow = if (adding) RiskWindow.DEFAULT else editing
    if (dialogWindow != null) {
        val isNew = adding
        WindowDialog(
            window = dialogWindow,
            onDismiss = { adding = false; editing = null },
            onSave = { start, end, days ->
                scope.launch(Dispatchers.IO) {
                    if (isNew) {
                        planRepository.addWindow(start, end, daysStringToMask(days))
                    } else {
                        planRepository.updateWindow(
                            dialogWindow.copy(startMinute = start, endMinute = end, daysMask = daysStringToMask(days)),
                        )
                    }
                    adding = false
                    editing = null
                    reload()
                }
            },
        )
    }
}

@Composable
private fun WindowRow(
    window: RiskWindow,
    canDelete: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val res = LocalResources.current
    val schedule = windowScheduleText(window, res)
    val toggleLabel = stringResource(R.string.recovery_windows_toggle)
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        onClick = onEdit,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(schedule, style = MaterialTheme.typography.bodyLarge)
                if (!window.enabled) {
                    Text(
                        stringResource(R.string.recovery_windows_off),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = window.enabled,
                onCheckedChange = onToggle,
                // The switch is a sibling of the schedule text, so without this its accessible
                // name is a bare "On/Off" with no window attached (findings #25/#26's class).
                modifier = Modifier.semantics { contentDescription = "$toggleLabel — $schedule" },
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.recovery_plan_item_actions, schedule),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.rules_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        enabled = canDelete,
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun WindowDialog(
    window: RiskWindow,
    onDismiss: () -> Unit,
    onSave: (startMinute: Int, endMinute: Int, days: String) -> Unit,
) {
    var start by remember { mutableIntStateOf(window.startMinute) }
    var end by remember { mutableIntStateOf(window.endMinute) }
    var days by remember { mutableStateOf(daysMaskToString(window.daysMask)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (window.id == 0L) R.string.recovery_windows_add_title else R.string.recovery_windows_edit_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TimeField(
                        label = stringResource(R.string.editor_from),
                        minutes = start.toLong(),
                        onChange = { start = it.toInt() },
                        modifier = Modifier.weight(1f),
                    )
                    TimeField(
                        label = stringResource(R.string.editor_to),
                        minutes = end.toLong(),
                        onChange = { end = it.toInt() },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Strictly `start > end` is the overnight case; `start == end` is the empty
                // half-open interval ([start, end)), which the evaluator never activates.
                if (end < start) {
                    Text(
                        stringResource(R.string.recovery_windows_overnight),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DayChips(days = days, onChange = { days = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(start, end, days) }) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) }
        },
    )
}

/** The row label both this screen and the coaching editor render for a window. */
internal fun windowScheduleText(window: RiskWindow, res: Resources): String =
    "${formatTime(window.startMinute.toLong())}–${formatTime(window.endMinute.toLong())} · " +
        (daysLabel(daysMaskToString(window.daysMask), res) ?: "")
