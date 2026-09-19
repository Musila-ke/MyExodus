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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.RiskWindow
import com.example.myexodus.recovery.RecoveryPlanRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Coaching Plan editor (v3 W3, Req 20): the user's own steps, if-then plans, and values —
 * one ordered list per kind, ≤5 items of ≤200 characters each (the entity's own caps). Local
 * DB only and offline by construction; gated because the text is as private as the history
 * (W6/INV-11).
 */
@Composable
fun CoachingPlanScreen(
    planRepository: RecoveryPlanRepository,
    rulesRepository: RulesRepository,
    prefs: RecoveryPrefs,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GatedContent(prefs = prefs, titleRes = R.string.recovery_gate_title_plan) {
        CoachingPlanBody(
            planRepository = planRepository,
            rulesRepository = rulesRepository,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@Composable
private fun CoachingPlanBody(
    planRepository: RecoveryPlanRepository,
    rulesRepository: RulesRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.recovery_back),
                )
            }
            Text(
                stringResource(R.string.recovery_plan_editor_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(kindHeader(kind)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onAdd, enabled = canAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.recovery_plan_add))
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
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)) {
                    Text(item.actionText, style = MaterialTheme.typography.bodyLarge)
                    windowLabel(item, windows)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onEdit(item) }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.recovery_plan_edit))
                        }
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.rules_delete))
                        }
                        IconButton(onClick = { onMove(item, -1) }, enabled = index > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.recovery_plan_move_up))
                        }
                        IconButton(onClick = { onMove(item, 1) }, enabled = index < items.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.recovery_plan_move_down))
                        }
                    }
                }
            }
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
