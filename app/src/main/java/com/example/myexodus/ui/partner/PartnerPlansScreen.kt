package com.example.myexodus.ui.partner

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myexodus.R
import com.example.myexodus.accountability.plan.PartnerPlanRepository
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.data.db.entity.PartnerPlan
import com.example.myexodus.data.db.entity.PartnerPlanTask
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.LinkRow
import com.example.myexodus.ui.relativeTimeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [planId] null = a plan the user is about to write; [pairId] null = a local-only plan. */
private data class EditorTarget(val planId: String?, val pairId: String?)

/**
 * Partner plans (ADR #193): the checklist one side writes and the other checks off, plus the
 * user's own local plans. Three levels — list, detail, editor — with the nested BackHandlers
 * SharingScreen uses, so system back walks editor → detail → list → hub.
 *
 * The list is not scoped to the hub's selected partner: a device can hold several pairs, and
 * plans written for any of them belong here. Every row names its counterpart, so a title alone
 * never has to carry that answer.
 */
@Composable
fun PartnerPlansScreen(
    planRepository: PartnerPlanRepository,
    loadPartners: suspend () -> List<ReachOutTarget>,
    onBack: () -> Unit,
    /** Hoisted so a notification tap can open a named plan before this surface composes. */
    openPlanId: String? = null,
    onOpenPlan: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<PartnerPlan>>(emptyList()) }
    var tasksByPlan by remember { mutableStateOf<Map<String, List<PartnerPlanTask>>>(emptyMap()) }
    // The live pairs, newest first — with the pairing date, so two users sharing a label are
    // still distinguishable when choosing who a plan is for.
    var partners by remember { mutableStateOf<List<ReachOutTarget>>(emptyList()) }
    val names = partners.associate { it.pairId to it.name }
    var loaded by remember { mutableStateOf(false) }
    // True = the store refused the read. An empty list is a real answer; this is not.
    var unavailable by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }
    var askingForWhom by remember { mutableStateOf(false) }
    // null = no notice; true = saved locally; false = saved for the partner.
    var savedNotice by remember { mutableStateOf<Boolean?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        val snapshot = withContext(Dispatchers.IO) {
            runCatching {
                val allPlans = planRepository.plans()
                Triple(
                    allPlans,
                    if (allPlans == null) emptyList() else planRepository.allTasks(),
                    runCatching { loadPartners() }.getOrDefault(emptyList()),
                )
            }.getOrNull()
        }
        plans = snapshot?.first.orEmpty()
        tasksByPlan = snapshot?.second.orEmpty().groupBy { it.planId }
        partners = snapshot?.third.orEmpty()
        unavailable = snapshot == null || snapshot.first == null
        loaded = true
    }

    // A verified event from the partner (a new or edited plan, their progress on ours) repaints
    // this surface the same way it repaints the hub.
    LaunchedEffect(Unit) {
        PartnerSyncSignals.pulledEvents.collect {
            reloadKey++
        }
    }

    // Checks reset at local midnight, and the read that normalizes them only runs on reload —
    // a surface left composed across the boundary would keep yesterday's ticks on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = editorTarget != null) { editorTarget = null }
    BackHandler(enabled = editorTarget == null && openPlanId != null) {
        onOpenPlan(null)
        savedNotice = null
    }

    val target = editorTarget
    if (target != null) {
        val editingPlan = target.planId?.let { id -> plans.firstOrNull { it.planId == id } }
        val pairId = editingPlan?.pairId ?: target.pairId
        PartnerPlanEditor(
            heading = stringResource(
                if (target.planId == null) R.string.partner_plans_editor_new
                else R.string.partner_plans_editor_edit,
            ),
            initialTitle = editingPlan?.title.orEmpty(),
            initialTasks = target.planId
                ?.let { tasksByPlan[it] }
                .orEmpty()
                .map { it.taskId to it.text },
            localOnly = pairId == null,
            onCancel = { editorTarget = null },
            onSave = { title, drafts ->
                if (target.planId == null) {
                    planRepository.createPlan(pairId, title, drafts.map { it.text })
                } else {
                    planRepository.updatePlan(target.planId, title, drafts)
                        .takeIf { it }
                        ?.let { target.planId }
                }
            },
            onSaved = { planId ->
                editorTarget = null
                onOpenPlan(planId)
                savedNotice = pairId == null
                reloadKey++
            },
            modifier = modifier,
        )
        return
    }

    val openPlan = openPlanId?.let { id -> plans.firstOrNull { it.planId == id } }
    if (openPlan != null) {
        PlanDetail(
            plan = openPlan,
            tasks = tasksByPlan[openPlan.planId].orEmpty(),
            partnerName = openPlan.pairId?.let { names[it] }
                ?: stringResource(R.string.partner_plans_unknown_partner),
            savedNotice = savedNotice,
            onBack = {
                onOpenPlan(null)
                savedNotice = null
            },
            onEdit = { editorTarget = EditorTarget(openPlan.planId, openPlan.pairId) },
            onToggle = { task, checked ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            planRepository.setTaskDone(openPlan.planId, task.taskId, checked)
                        }.getOrDefault(false)
                    }
                    if (ok) reloadKey++
                }
            },
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        BackRow(
            label = stringResource(R.string.partner_plans_title),
            onBack = onBack,
            trailing = {
                TextButton(onClick = { askingForWhom = true }) {
                    Text(stringResource(R.string.partner_plans_new))
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !loaded -> Box(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                unavailable -> Text(
                    stringResource(R.string.partner_plans_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> {
                    if (plans.isEmpty()) {
                        Text(
                            stringResource(R.string.partner_plans_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        PlanSection(
                            header = stringResource(R.string.partner_plans_from_header),
                            plans = plans.filter { !it.authored && it.pairId != null },
                            tasksByPlan = tasksByPlan,
                            names = names,
                            onOpen = onOpenPlan,
                        )
                        PlanSection(
                            header = stringResource(R.string.partner_plans_given_header),
                            plans = plans.filter { it.authored && it.pairId != null },
                            tasksByPlan = tasksByPlan,
                            names = names,
                            onOpen = onOpenPlan,
                        )
                        PlanSection(
                            header = stringResource(R.string.partner_plans_mine_header),
                            plans = plans.filter { it.pairId == null },
                            tasksByPlan = tasksByPlan,
                            names = names,
                            onOpen = onOpenPlan,
                        )
                    }
                }
            }
        }
    }

    if (askingForWhom) {
        AlertDialog(
            onDismissRequest = { askingForWhom = false },
            title = { Text(stringResource(R.string.partner_plans_for_whom)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    partners.forEach { partner ->
                        TextButton(
                            onClick = {
                                askingForWhom = false
                                editorTarget = EditorTarget(null, partner.pairId)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(partner.name)
                                partner.pairedAtEpochMillis?.let { pairedAt ->
                                    Text(
                                        stringResource(
                                            R.string.partner_users_since,
                                            pairedOnText(pairedAt),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            askingForWhom = false
                            editorTarget = EditorTarget(null, null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.partner_plans_for_self))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { askingForWhom = false }) {
                    Text(stringResource(R.string.partner_plans_cancel))
                }
            },
        )
    }
}

/** One titled group of plan rows; a section with nothing in it spends no vertical space. */
@Composable
private fun PlanSection(
    header: String,
    plans: List<PartnerPlan>,
    tasksByPlan: Map<String, List<PartnerPlanTask>>,
    names: Map<String, String>,
    onOpen: (String) -> Unit,
) {
    if (plans.isEmpty()) return
    Text(
        header,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    plans.forEach { plan ->
        val tasks = tasksByPlan[plan.planId].orEmpty()
        val done = tasks.count { it.done }
        val progress = stringResource(R.string.partner_plans_progress, done, tasks.size)
        val who = when {
            plan.pairId == null -> stringResource(R.string.partner_plans_local)
            plan.authored -> stringResource(
                R.string.partner_plans_with,
                names[plan.pairId] ?: stringResource(R.string.partner_plans_unknown_partner),
            )
            else -> stringResource(
                R.string.partner_plans_from,
                names[plan.pairId] ?: stringResource(R.string.partner_plans_unknown_partner),
            )
        }
        LinkRow(
            title = plan.title,
            body = "$progress · $who",
            onClick = { onOpen(plan.planId) },
        )
    }
}

@Composable
private fun PlanDetail(
    plan: PartnerPlan,
    tasks: List<PartnerPlanTask>,
    partnerName: String,
    savedNotice: Boolean?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onToggle: (PartnerPlanTask, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A shared plan's author only watches progress arrive; a self plan's owner checks locally.
    val canCheck = !plan.authored || plan.pairId == null
    Column(modifier.fillMaxSize()) {
        BackRow(
            label = plan.title,
            onBack = onBack,
            trailing = {
                if (plan.authored) {
                    TextButton(onClick = onEdit) {
                        Text(stringResource(R.string.partner_plans_edit))
                    }
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (savedNotice != null) {
                Text(
                    stringResource(
                        if (savedNotice) R.string.partner_plans_saved_local
                        else R.string.partner_plans_saved,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val done = tasks.count { it.done }
            Text(
                stringResource(R.string.partner_plans_progress, done, tasks.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tasks.isNotEmpty()) {
                Text(
                    stringResource(R.string.partner_plans_daily_reset),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when {
                    plan.pairId == null -> stringResource(R.string.partner_plans_local)
                    plan.authored -> stringResource(
                        R.string.partner_plans_author_hint,
                        partnerName,
                    )
                    else -> stringResource(R.string.partner_plans_recipient_hint, partnerName)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tasks.isEmpty()) {
                Text(
                    stringResource(R.string.partner_plans_empty_checklist),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tasks.forEach { task ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (canCheck) {
                        Checkbox(
                            checked = task.done,
                            onCheckedChange = { checked -> onToggle(task, checked) },
                            modifier = Modifier.semantics { contentDescription = task.text },
                        )
                    } else {
                        Icon(
                            if (task.done) Icons.Outlined.CheckCircle
                            else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (task.done) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        task.text,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (task.done) TextDecoration.LineThrough else null,
                        color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (plan.pairId != null) {
                Text(
                    stringResource(
                        R.string.partner_plans_updated,
                        relativeTimeText(System.currentTimeMillis(), plan.updatedAtMs),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
