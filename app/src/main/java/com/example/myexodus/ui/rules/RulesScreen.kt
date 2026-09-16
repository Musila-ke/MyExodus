package com.example.myexodus.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberWindowWidth
import com.example.myexodus.ui.ruleRowState
import com.example.myexodus.ui.scheduleSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

private const val ROUTE_LIST = "list"
private const val ROUTE_EDIT = "edit"

/**
 * V1 Rules screen (plan v2 §2, executed as UI-spec only): CRUD over the shipped
 * [RestrictionRule] model and engine. Every write goes through [RulesRepository], so the
 * policy version bumps and the enforcement snapshot republishes in the same transaction — a
 * saved change is live immediately. Nothing shows as saved unless the write committed: the
 * pause switch reverts and the banner names the failure (plan §6.1/§9).
 */
@Composable
fun RulesScreen(
    store: AppRulesStore,
    repository: RulesRepository,
    usageAccountingEngine: UsageAccountingEngine,
    modifier: Modifier = Modifier,
) {
    val profile by store.userProfileDao().observeActive().collectAsStateWithLifecycle(initialValue = null)
    val profileId = profile?.userId
    // null = not yet emitted: an empty list here would flash "No blocks yet" while loading.
    val rules by remember(profileId) {
        profileId?.let { store.restrictionRuleDao().observeByProfile(it) } ?: emptyFlow()
    }.collectAsStateWithLifecycle(initialValue = null)

    var route by rememberSaveable { mutableStateOf(ROUTE_LIST) }
    var editRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var editTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var editTargetType by rememberSaveable { mutableStateOf("APP") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Optimistic pause state: shown immediately, dropped once the committed value matches,
    // cleared outright when the write fails (switch snaps back).
    var pendingPause by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    LaunchedEffect(rules, pendingPause) {
        if (pendingPause.isNotEmpty()) {
            pendingPause = pendingPause.filterNot { (id, want) ->
                rules?.firstOrNull { it.ruleId == id }?.isEnabled == want
            }
        }
    }

    // Live pills flip at schedule boundaries; a slow tick keeps them honest while on screen.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    when (route) {
        ROUTE_EDIT -> {
            val editing = editRuleId?.let { id -> rules?.firstOrNull { it.ruleId == id } }
            if (editRuleId != null && editing == null) {
                // Config change / process death before the Flow re-emitted; a rule that was
                // really deleted simply leaves the back action available.
                EditorUnavailable(onBack = { route = ROUTE_LIST }, modifier = modifier)
            } else {
                RuleEditorScreen(
                    initial = editing,
                    targetType = editTargetType,
                    target = editTarget,
                    existingRules = rules.orEmpty(),
                    repository = repository,
                    usageAccountingEngine = usageAccountingEngine,
                    onDone = { route = ROUTE_LIST },
                    modifier = modifier,
                )
            }
        }

        else -> {
            val loaded = rules
            if (loaded == null) {
                Box(modifier.fillMaxSize())
            } else {
                RulesList(
                    rules = loaded,
                    pendingPause = pendingPause,
                    nowMillis = nowMillis,
                    errorMessage = errorMessage,
                    onDismissError = { errorMessage = null },
                    onAddApp = {
                        editRuleId = null
                        editTarget = null
                        editTargetType = "APP"
                        route = ROUTE_EDIT
                    },
                    onAddWebsite = {
                        editRuleId = null
                        editTarget = null
                        editTargetType = "WEBSITE"
                        route = ROUTE_EDIT
                    },
                    onEdit = { rule ->
                        editRuleId = rule.ruleId
                        editTargetType = rule.targetType
                        editTarget = rule.blockedPackage
                        route = ROUTE_EDIT
                    },
                    onTogglePause = { rule, enable ->
                        pendingPause = pendingPause + (rule.ruleId to enable)
                        scope.launch {
                            runCatching { repository.setEnabled(rule, enable) }
                                .onFailure { failure ->
                                    pendingPause = pendingPause - rule.ruleId
                                    errorMessage = notSavedMessage(failure, context)
                                }
                        }
                    },
                    modifier = modifier,
                )
            }
        }
    }
}

/** Maps a write failure to copy that says what happened and what to do (plan §9). */
internal fun notSavedMessage(failure: Throwable, context: android.content.Context): String {
    val detail = when (failure) {
        is com.example.myexodus.data.db.ProtectedPackageException ->
            context.getString(R.string.error_self_block)
        is com.example.myexodus.blocking.RulesetLimitException ->
            failure.message ?: context.getString(R.string.error_limit_reached)
        else -> context.getString(R.string.error_write_failed)
    }
    return context.getString(R.string.editor_not_saved, detail)
}

@Composable
private fun EditorUnavailable(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.editor_back)) }
        Text(
            stringResource(R.string.editor_rule_gone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RulesList(
    rules: List<RestrictionRule>,
    pendingPause: Map<String, Boolean>,
    nowMillis: Long,
    errorMessage: String?,
    onDismissError: () -> Unit,
    onAddApp: () -> Unit,
    onAddWebsite: () -> Unit,
    onEdit: (RestrictionRule) -> Unit,
    onTogglePause: (RestrictionRule, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePadding: Dp = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    val appsHeader = stringResource(R.string.rules_apps_header)
    val websitesHeader = stringResource(R.string.rules_websites_header)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Column {
                Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.rules_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        errorMessage?.let { message ->
            item(key = "error") {
                ErrorBanner(message = message, onDismiss = onDismissError)
            }
        }
        item(key = "add") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AddButton(
                    label = stringResource(R.string.editor_target_app),
                    onClick = onAddApp,
                )
                AddButton(
                    label = stringResource(R.string.editor_target_website),
                    onClick = onAddWebsite,
                )
            }
        }
        if (rules.isEmpty()) {
            item(key = "empty") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.rules_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.rules_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            ruleSection(
                header = appsHeader,
                rules = rules.filter { it.targetType == "APP" },
                pendingPause = pendingPause,
                nowMillis = nowMillis,
                onEdit = onEdit,
                onTogglePause = onTogglePause,
            )
            ruleSection(
                header = websitesHeader,
                rules = rules.filter { it.targetType == "WEBSITE" },
                pendingPause = pendingPause,
                nowMillis = nowMillis,
                onEdit = onEdit,
                onTogglePause = onTogglePause,
            )
        }
    }
}

@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun LazyListScope.ruleSection(
    header: String,
    rules: List<RestrictionRule>,
    pendingPause: Map<String, Boolean>,
    nowMillis: Long,
    onEdit: (RestrictionRule) -> Unit,
    onTogglePause: (RestrictionRule, Boolean) -> Unit,
) {
    if (rules.isEmpty()) return
    item(key = "header-$header") {
        Text(
            header,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    item(key = "card-$header") {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                rules.forEachIndexed { index, rule ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    RuleRow(
                        rule = rule,
                        enabled = pendingPause[rule.ruleId] ?: rule.isEnabled,
                        nowMillis = nowMillis,
                        onEdit = { onEdit(rule) },
                        onTogglePause = { enable -> onTogglePause(rule, enable) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: RestrictionRule,
    enabled: Boolean,
    nowMillis: Long,
    onEdit: () -> Unit,
    onTogglePause: (Boolean) -> Unit,
) {
    val label = rememberAppLabel(rule.blockedPackage)
    val res = LocalResources.current
    val state = remember(rule, enabled, nowMillis, res) { ruleRowState(rule, nowMillis, res) }
    val pauseLabel = stringResource(R.string.rules_pause)
    val resumeLabel = stringResource(R.string.rules_resume)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = if (rule.targetType == "WEBSITE") Icons.Default.Language else Icons.Default.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                scheduleSummary(rule, res),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.label?.let { StatePill(label = it, active = state.active && enabled) }
        Switch(
            checked = enabled,
            onCheckedChange = onTogglePause,
            modifier = Modifier.semantics {
                contentDescription = if (enabled) pauseLabel else resumeLabel
            },
        )
    }
}

@Composable
private fun StatePill(label: String, active: Boolean) {
    val accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) }
        }
    }
}
