package com.example.myexodus.ui.partner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.accountability.plan.PlanTaskDraft
import com.example.myexodus.data.db.entity.PartnerPlanTask
import com.example.myexodus.ui.BackRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One editable line; [taskId] null = a line added in this session. */
private data class TaskRow(val taskId: String?, val text: String)

/**
 * The plan writer (ADR #193): title + a checklist, capped by the entity's own limits at input
 * so an over-long value never fails at the repository boundary. [onSave] returns the plan id,
 * or null when the store refused the write; the caller owns navigation and the saved notice.
 * A failed save keeps every draft on screen — the box that says "try again" only works if the
 * work is still there to retry.
 */
@Composable
internal fun PartnerPlanEditor(
    heading: String,
    initialTitle: String,
    initialTasks: List<Pair<String, String>>,
    localOnly: Boolean,
    onCancel: () -> Unit,
    onSave: suspend (title: String, drafts: List<PlanTaskDraft>) -> String?,
    onSaved: (planId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initialTitle) }
    var rows by remember {
        mutableStateOf(
            initialTasks.map { TaskRow(it.first, it.second) }
                .ifEmpty { listOf(TaskRow(null, "")) },
        )
    }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // 0 = no validation problem; otherwise the string resource explaining it.
    var validation by remember { mutableIntStateOf(0) }

    fun save() {
        if (saving) return
        val cleanTitle = title.trim()
        val drafts = rows.mapNotNull { row ->
            row.text.trim().takeIf { it.isNotEmpty() }?.let { PlanTaskDraft(row.taskId, it) }
        }
        if (cleanTitle.isEmpty()) {
            validation = R.string.partner_plans_need_title
            return
        }
        if (drafts.isEmpty()) {
            validation = R.string.partner_plans_need_task
            return
        }
        validation = 0
        failed = false
        saving = true
        scope.launch {
            val savedId = withContext(Dispatchers.IO) {
                runCatching { onSave(cleanTitle, drafts) }.getOrNull()
            }
            saving = false
            if (savedId != null) onSaved(savedId) else failed = true
        }
    }

    Column(modifier.fillMaxSize()) {
        BackRow(label = heading, onBack = onCancel)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (localOnly) {
                Text(
                    stringResource(R.string.partner_plans_local),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = title,
                onValueChange = {
                    if (it.length <= PartnerPlanTask.MAX_TITLE_LENGTH) title = it
                },
                label = { Text(stringResource(R.string.partner_plans_title_field)) },
                placeholder = { Text(stringResource(R.string.partner_plans_title_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            rows.forEachIndexed { index, row ->
                OutlinedTextField(
                    value = row.text,
                    onValueChange = { value ->
                        if (value.length <= PartnerPlanTask.MAX_TEXT_LENGTH) {
                            rows = rows.toMutableList().also { it[index] = row.copy(text = value) }
                        }
                    },
                    label = { Text(stringResource(R.string.partner_plans_task_field)) },
                    placeholder = { Text(stringResource(R.string.partner_plans_task_hint)) },
                    maxLines = 3,
                    trailingIcon = {
                        IconButton(
                            onClick = { rows = rows.filterIndexed { i, _ -> i != index } },
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(
                                    R.string.partner_plans_remove_task_desc,
                                    row.text.ifBlank {
                                        stringResource(R.string.partner_plans_task_field)
                                    },
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(
                onClick = { rows = rows + TaskRow(null, "") },
                enabled = rows.size < PartnerPlanTask.MAX_TASKS,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.partner_plans_add_task))
            }
            if (validation != 0) {
                Text(
                    stringResource(validation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (failed) {
                Text(
                    stringResource(R.string.partner_plans_save_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = ::save,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (saving) R.string.partner_plans_saving else R.string.partner_plans_save,
                    ),
                )
            }
        }
    }
}
