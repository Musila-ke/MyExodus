package com.example.myexodus.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.RiskWindow
import com.example.myexodus.recovery.RecoveryPlanRepository
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.SecureSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Coaching Plan editor (v3 W3, Req 20): the user's own steps, if-then plans, and values —
 * one ordered list per kind, ≤5 items of ≤200 characters each (the entity's own caps). Local
 * DB only and offline by construction; direct access (owner ruling 2026-11-08).
 */
@Composable
fun CoachingPlanScreen(
    planRepository: RecoveryPlanRepository,
    rulesRepository: RulesRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureSurface()
    CoachingPlanBody(
        planRepository = planRepository,
        rulesRepository = rulesRepository,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun CoachingPlanBody(
    planRepository: RecoveryPlanRepository,
    rulesRepository: RulesRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var profileId by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<CoachingStep>>(emptyList()) }
    var windows by remember { mutableStateOf<List<RiskWindow>>(emptyList()) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var pendingDelete by remember { mutableStateOf<CoachingStep?>(null) }

    suspend fun reload(id: String) = withContext(Dispatchers.IO) {
        items = planRepository.items(id)
        windows = planRepository.windows()
    }

    LaunchedEffect(Unit) {
        val id = withContext(Dispatchers.IO) {
            runCatching { rulesRepository.ensureActiveProfile().userId }.getOrNull()
        } ?: return@LaunchedEffect
        profileId = id
        reload(id)
    }

    Column(modifier = modifier.fillMaxSize()) {
        BackRow(label = stringResource(R.string.recovery_plan_editor_title), onBack = onBack)
        val id = profileId
        if (id == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.recovery_plan_editor_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KINDS.forEach { kind ->
                KindSection(
                    kind = kind,
                    items = items.filter { it.kind == kind },
                    windows = windows,
                    canAdd = items.count { it.kind == kind } < CoachingStep.MAX_PER_KIND,
                    onAdd = { dialog = EditorDialog(kind = kind, step = null) },
                    onEdit = { dialog = EditorDialog(kind = kind, step = it) },
                    onDelete = { pendingDelete = it },
                    onMove = { step, delta ->
                        scope.launch(Dispatchers.IO) {
                            planRepository.moveItem(step, delta)
                            reload(id)
                        }
                    },
                )
            }
            Text(
                stringResource(R.string.recovery_plan_kind_cap, CoachingStep.MAX_PER_KIND),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    dialog?.let { d ->
        ItemDialog(
            dialog = d,
            windows = windows,
            onDismiss = { dialog = null },
            onSave = { text, windowId ->
                val id = profileId
                if (id != null) {
                    scope.launch(Dispatchers.IO) {
                        val step = d.step
                        if (step == null) {
                            planRepository.addItem(id, d.kind, text, windowId)
                        } else {
                            planRepository.updateItem(step, text, windowId)
                        }
                        dialog = null
                        reload(id)
                    }
                }
            },
        )
    }

    pendingDelete?.let { step ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.recovery_plan_delete_title)) },
            text = { Text(stringResource(R.string.recovery_plan_delete_body, step.actionText)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = profileId
                        pendingDelete = null
                        if (id != null) scope.launch(Dispatchers.IO) { planRepository.deleteItem(step); reload(id) }
                    },
                ) {
                    Text(
                        stringResource(R.string.rules_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.rules_cancel))
                }
            },
        )
    }
}

private val KINDS = listOf(CoachingStep.KIND_STEP, CoachingStep.KIND_IF_THEN, CoachingStep.KIND_VALUE)

private fun kindHeader(kind: String): Int = when (kind) {
    CoachingStep.KIND_STEP -> R.string.recovery_plan_steps
    CoachingStep.KIND_IF_THEN -> R.string.recovery_plan_ifthen
    else -> R.string.recovery_plan_values
}

private fun kindHint(kind: String): Int = when (kind) {
    CoachingStep.KIND_STEP -> R.string.recovery_plan_hint_step
    CoachingStep.KIND_IF_THEN -> R.string.recovery_plan_hint_ifthen
    else -> R.string.recovery_plan_hint_value
}

private data class EditorDialog(val kind: String, val step: CoachingStep?)

@Composable
private fun KindSection(
    kind: String,
    items: List<CoachingStep>,
    windows: List<RiskWindow>,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onEdit: (CoachingStep) -> Unit,
    onDelete: (CoachingStep) -> Unit,
    onMove: (CoachingStep, Int) -> Unit,
) {
    val header = stringResource(kindHeader(kind))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                header,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onAdd, enabled = canAdd) {
                // Three of these announce a bare "Add" with no way to tell which kind they add
                // to — the same missing-subject defect as findings #25/#26 (WCAG 2.4.6).
                Icon(
                    Icons.Default.Add,
                    contentDescription = "${stringResource(R.string.recovery_plan_add)}, $header",
                )
            }
        }
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.recovery_plan_empty_kind),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items.forEachIndexed { index, item ->
            Surface(
                onClick = { onEdit(item) },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(item.actionText, style = MaterialTheme.typography.bodyLarge)
                        windowLabel(item, windows)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    ItemMenu(
                        itemText = item.actionText,
                        canMoveUp = index > 0,
                        canMoveDown = index < items.lastIndex,
                        onMove = { onMove(item, it) },
                        onDelete = { onDelete(item) },
                    )
                }
            }
        }
    }
}

/**
 * Reorder + delete behind one overflow. The row itself is the edit target, so four visible
 * icon buttons per item became one: at five items in each of three kinds the old row put up to
 * **60** targets on the page, each too small to hit reliably and each announcing the same
 * subjectless word as its neighbours.
 */
@Composable
private fun ItemMenu(
    itemText: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.recovery_plan_item_actions, itemText),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recovery_plan_move_up)) },
                enabled = canMoveUp,
                onClick = { open = false; onMove(-1) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recovery_plan_move_down)) },
                enabled = canMoveDown,
                onClick = { open = false; onMove(1) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.rules_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/** The item's window link rendered as its human label, or null when it applies at any time. */
@Composable
private fun windowLabel(item: CoachingStep, windows: List<RiskWindow>): String? {
    val window = windows.firstOrNull { it.id.toString() == item.associatedTriggerWindow } ?: return null
    val res = LocalResources.current
    return remember(window, res) { windowScheduleText(window, res) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemDialog(
    dialog: EditorDialog,
    windows: List<RiskWindow>,
    onDismiss: () -> Unit,
    onSave: (text: String, windowId: String) -> Unit,
) {
    val res = LocalResources.current
    var text by remember { mutableStateOf(dialog.step?.actionText ?: "") }
    var windowId by remember { mutableStateOf(dialog.step?.associatedTriggerWindow ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (dialog.step == null) R.string.recovery_plan_add_title else R.string.recovery_plan_edit_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    // The entity's own cap, applied at input: an over-long value could never
                    // be saved anyway (the repository trims to it).
                    onValueChange = { if (it.length <= CoachingStep.MAX_TEXT_LENGTH) text = it },
                    label = { Text(stringResource(kindHint(dialog.kind))) },
                    supportingText = { Text("${text.length}/${CoachingStep.MAX_TEXT_LENGTH}") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.recovery_plan_window_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = windowId.isEmpty(),
                        onClick = { windowId = "" },
                        label = { Text(stringResource(R.string.recovery_plan_window_any)) },
                    )
                    windows.forEach { window ->
                        FilterChip(
                            selected = windowId == window.id.toString(),
                            onClick = { windowId = window.id.toString() },
                            label = { Text(windowScheduleText(window, res)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSave(text, windowId) },
            ) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) }
        },
    )
}
