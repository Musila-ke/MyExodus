package com.example.myexodus.ui.rules

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.usage.PlatformUsageReader
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.domainusage.EngineMode
import com.example.myexodus.domainusage.PublicSuffixList
import com.example.myexodus.ui.AppMark
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.DayChips
import com.example.myexodus.ui.TimeField
import com.example.myexodus.ui.formatDuration
import com.example.myexodus.ui.partner.ReachOutTarget
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.vpn.VpnLifecycle.WebsiteEngineBlocker
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val DEFAULT_START_MINUTES = 22 * 60L
private const val DEFAULT_END_MINUTES = 7 * 60L
private const val MINUTE_MS = 60_000L
private const val BATCH_NAME_MAX = 64

private const val TAG = "BatchEditor"

/**
 * Batch creation (owner mandate 2026-10-31): several apps and websites named and blocked
 * together under one partner. Every member shares the batch id/name, the block settings, and
 * the assignment; [RulesRepository.insertBatch] commits them and ONE batch-level journal row
 * in a single transaction, so the partner learns about "Social media" as a whole and can
 * never see half of it. Validation reuses the single-rule editor's own helpers, so a batch
 * member is exactly the rule that editor would have produced.
 */
@Composable
fun BatchEditorScreen(
    existingRules: List<RestrictionRule>,
    partners: List<ReachOutTarget>,
    repository: RulesRepository,
    usageAccountingEngine: UsageAccountingEngine,
    platformUsage: PlatformUsageReader,
    psl: PublicSuffixList,
    websiteEngineBlocker: WebsiteEngineBlocker?,
    websiteLimitsMode: EngineMode,
    onEnableWebsiteEngine: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceZone = remember { ZoneId.systemDefault().id }

    var name by rememberSaveable { mutableStateOf("") }
    var apps by rememberSaveable(
        stateSaver = listSaver(save = { it }, restore = { it }),
    ) { mutableStateOf(emptyList<String>()) }
    var websites by rememberSaveable(
        stateSaver = listSaver(save = { it }, restore = { it }),
    ) { mutableStateOf(emptyList<String>()) }
    var hostDraft by rememberSaveable { mutableStateOf("") }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var presetCategoryId by rememberSaveable { mutableStateOf<String?>(null) }

    // Same defaults and stored shape as a preset rule: a batch means "block these now".
    var alwaysOn by rememberSaveable { mutableStateOf(true) }
    var windowEnabled by rememberSaveable { mutableStateOf(false) }
    var limitEnabled by rememberSaveable { mutableStateOf(false) }
    var limitHours by rememberSaveable { mutableStateOf("") }
    var limitMinutes by rememberSaveable { mutableStateOf("") }
    var startMinutes by rememberSaveable { mutableLongStateOf(DEFAULT_START_MINUTES) }
    var endMinutes by rememberSaveable { mutableLongStateOf(DEFAULT_END_MINUTES) }
    var days by rememberSaveable { mutableStateOf("1111111") }
    var expiresAt by rememberSaveable { mutableLongStateOf(0L) }
    var assignedPairId by rememberSaveable { mutableStateOf<String?>(null) }

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveAttempt by remember { mutableIntStateOf(0) }
    var confirmEngineOff by rememberSaveable { mutableStateOf(false) }
    var enginePromptHandled by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    LaunchedEffect(error, saveAttempt) { if (error != null) scrollState.animateScrollTo(0) }

    val existingAppKeys = remember(existingRules) {
        existingRules.filter { it.targetType == "APP" }
            .map { targetKey(it.blockedPackage, "APP") }.toSet()
    }
    val existingWebsiteKeys = remember(existingRules) {
        existingRules.filter { it.targetType == "WEBSITE" }
            .map { targetKey(it.blockedPackage, "WEBSITE") }.toSet()
    }

    val nameMissingMessage = stringResource(R.string.batch_name_missing)
    val itemsMissingMessage = stringResource(R.string.batch_items_missing)
    val invalidWebsiteMessage = stringResource(R.string.batch_website_invalid)
    val duplicateTemplate = stringResource(R.string.batch_duplicate)
    val itemErrorTemplate = stringResource(R.string.batch_item_error)
    // Hosted here, not read from context inside save(), so a configuration change can't leave
    // stale strings behind (lint LocalContextGetResourceValueCall).
    val limitUnavailableMessage = stringResource(R.string.editor_website_limits_unavailable)
    val modeMissingMessage = stringResource(R.string.editor_mode_missing)
    val sameTimeMessage = stringResource(R.string.editor_same_time_error)
    val limitInvalidMessage = stringResource(R.string.editor_daily_limit_invalid)
    val limitTooLargeMessage = stringResource(R.string.editor_daily_limit_too_large)
    val siteHasPathTemplate = stringResource(R.string.editor_site_limit_has_path)
    val siteInvalidMessage = stringResource(R.string.editor_site_limit_invalid)
    val siteIpMessage = stringResource(R.string.editor_site_limit_ip)
    val siteTooBroadMessage = stringResource(R.string.editor_site_limit_too_broad)
    val siteNeverBlockMessage = stringResource(R.string.editor_site_limit_never_block)
    val siteResolverMessage = stringResource(R.string.editor_site_limit_resolver)
    val siteDuplicateMessage = stringResource(R.string.editor_site_limit_duplicate)
    val limitAvailable = websites.isEmpty() || websiteLimitMechanismAvailable(false, websiteLimitsMode)
    val appLabels = apps.associateWith { rememberAppLabel(it) }

    fun addWebsites(hosts: List<String>) {
        val additions = hosts.map { it.trim() }.filter { host ->
            host.isNotEmpty() &&
                targetKey(host, "WEBSITE") !in existingWebsiteKeys &&
                websites.none { targetKey(it, "WEBSITE") == targetKey(host, "WEBSITE") }
        }
        if (additions.isNotEmpty()) websites = websites + additions
    }

    fun save() {
        error = null
        saveAttempt++
        val batchName = name.trim().take(BATCH_NAME_MAX)
        if (batchName.isEmpty()) {
            error = nameMissingMessage
            return
        }
        if (apps.isEmpty() && websites.isEmpty()) {
            error = itemsMissingMessage
            return
        }
        val limitOn = !alwaysOn && limitEnabled
        if (limitOn && !limitAvailable) {
            error = limitUnavailableMessage
            return
        }
        val limitTotalMinutes = parseLimitTotalMinutes(limitHours, limitMinutes)
        when (val modeError = validateBlockMode(alwaysOn, windowEnabled, limitEnabled, startMinutes, endMinutes, limitTotalMinutes)) {
            ModeError.NoneSelected -> { error = modeMissingMessage; return }
            ModeError.SameTime -> { error = sameTimeMessage; return }
            ModeError.LimitInvalid -> { error = limitInvalidMessage; return }
            ModeError.LimitTooLarge -> { error = limitTooLargeMessage; return }
            null -> Unit
        }

        // Re-checked at save: the rule set can change while this screen is open.
        apps.firstOrNull { targetKey(it, "APP") in existingAppKeys }?.let { packageName ->
            error = duplicateTemplate.format(appLabels[packageName] ?: packageName)
            return
        }
        websites.firstOrNull { targetKey(it, "WEBSITE") in existingWebsiteKeys }?.let { host ->
            error = duplicateTemplate.format(host)
            return
        }
        var storedWebsites = websites
        if (limitOn) {
            val patterns = siteLimitPatternsOf(existingRules, psl, excludeRuleId = null)
            for (host in websites) {
                when (val check = siteLimitTarget(host, psl, patterns)) {
                    is SiteLimitCheck.Ok -> storedWebsites = storedWebsites - host + check.host
                    is SiteLimitCheck.HasPath ->
                        { error = itemErrorTemplate.format(host, siteHasPathTemplate.format(check.suggestedHost)); return }
                    SiteLimitCheck.Invalid ->
                        { error = itemErrorTemplate.format(host, siteInvalidMessage); return }
                    SiteLimitCheck.IpLiteral ->
                        { error = itemErrorTemplate.format(host, siteIpMessage); return }
                    SiteLimitCheck.TooBroad ->
                        { error = itemErrorTemplate.format(host, siteTooBroadMessage); return }
                    SiteLimitCheck.NeverBlock ->
                        { error = itemErrorTemplate.format(host, siteNeverBlockMessage); return }
                    SiteLimitCheck.EncryptedDnsResolver ->
                        { error = itemErrorTemplate.format(host, siteResolverMessage); return }
                    SiteLimitCheck.DuplicatePattern ->
                        { error = itemErrorTemplate.format(host, siteDuplicateMessage); return }
                }
            }
        }

        if (websites.isNotEmpty() && websiteEngineBlocker != null && !enginePromptHandled) {
            confirmEngineOff = true
            return
        }
        val fields = composeRuleFields(
            alwaysOn = alwaysOn,
            windowEnabled = windowEnabled,
            limitEnabled = limitEnabled,
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            days = days,
            limitTotalMinutes = limitTotalMinutes,
        )
        saving = true
        scope.launch(Dispatchers.IO) {
            runCatching {
                val profileId = repository.ensureActiveProfile().userId
                val batchId = UUID.randomUUID().toString()
                val targets = apps.map { it to "APP" } + storedWebsites.map { it to "WEBSITE" }
                val rules = targets.map { (target, type) ->
                    RestrictionRule(
                        ruleId = UUID.randomUUID().toString(),
                        profileId = profileId,
                        blockedPackage = target,
                        targetType = type,
                        isAlwaysOn = fields.isAlwaysOn,
                        dailyLimitMs = clampStoredLimitMs(fields.dailyLimitMs),
                        timeRangeStart = fields.timeRangeStart,
                        timeRangeEnd = fields.timeRangeEnd,
                        activeDaysOfWeek = fields.activeDaysOfWeek,
                        timezone = deviceZone,
                        blockExpiresAt = expiresAt,
                        severityLevel = "HIGH",
                        assignedPairId = assignedPairId,
                        batchId = batchId,
                        batchName = batchName,
                    )
                }
                repository.insertBatch(rules)
                rules.filter { it.targetType == "APP" && it.dailyLimitMs > 0L }.forEach { rule ->
                    runCatching {
                        usageAccountingEngine.reconcileWithPlatform(
                            rule.blockedPackage,
                            platformUsage.todayForegroundMs(rule.blockedPackage),
                        )
                    }.onFailure { Log.w(TAG, "Platform usage reconcile failed after batch save", it) }
                }
            }.onSuccess { onDone() }
                .onFailure { error = notSavedMessage(it, context) }
            saving = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackRow(label = stringResource(R.string.batch_new_title), onBack = onDone)

        error?.let { message ->
            ErrorBanner(message = message, onDismiss = { error = null })
        }

        if (websites.isNotEmpty() && websiteEngineBlocker != null) {
            val consentMissing = websiteEngineBlocker == WebsiteEngineBlocker.NEEDS_CONSENT
            ErrorBanner(
                message = stringResource(
                    if (consentMissing) R.string.editor_engine_off_consent
                    else R.string.editor_engine_off_retry,
                ),
                onDismiss = null,
                actionLabel = stringResource(
                    if (consentMissing) R.string.editor_engine_off_turn_on
                    else R.string.editor_engine_off_try_again,
                ),
                onAction = onEnableWebsiteEngine,
            )
        }

        SectionCard {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(BATCH_NAME_MAX) },
                singleLine = true,
                label = { Text(stringResource(R.string.batch_name_label)) },
                placeholder = { Text(stringResource(R.string.batch_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.batch_items_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    pluralStringResource(
                        R.plurals.batch_item_count, apps.size + websites.size, apps.size + websites.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.batch_apps_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showAppPicker = true }, enabled = !saving) {
                    Text(stringResource(R.string.batch_apps_add))
                }
            }
            if (apps.isEmpty()) {
                EmptyLine(stringResource(R.string.batch_apps_empty))
            } else {
                apps.forEach { packageName ->
                    BatchItemRow(
                        label = rememberAppLabel(packageName),
                        packageName = packageName,
                        onRemove = { apps = apps - packageName },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.batch_websites_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = hostDraft,
                    onValueChange = { hostDraft = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.batch_website_hint)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = hostDraft.isNotBlank() && !saving,
                    onClick = {
                        val host = hostDraft.trim()
                        when {
                            !isValidWebsiteTarget(host) -> error = invalidWebsiteMessage
                            targetKey(host, "WEBSITE") in existingWebsiteKeys ||
                                websites.any { targetKey(it, "WEBSITE") == targetKey(host, "WEBSITE") } ->
                                error = duplicateTemplate.format(host)
                            else -> {
                                websites = websites + host
                                hostDraft = ""
                                error = null
                            }
                        }
                    },
                ) { Text(stringResource(R.string.batch_website_add)) }
            }
            if (websites.isEmpty()) {
                EmptyLine(stringResource(R.string.batch_websites_empty))
            } else {
                websites.forEach { host ->
                    BatchItemRow(
                        label = host,
                        packageName = null,
                        onRemove = { websites = websites - host },
                    )
                }
            }
            CategoryPresetChips(onOpenPresets = { presetCategoryId = it })
        }

        SectionCard {
            Text(
                stringResource(R.string.editor_mode_title),
                style = MaterialTheme.typography.titleMedium,
            )
            ModeRow(
                title = stringResource(R.string.editor_always_on),
                hint = stringResource(R.string.editor_always_on_hint),
                icon = Icons.Outlined.Block,
                checked = alwaysOn,
                enabled = true,
                onCheckedChange = { alwaysOn = it },
            )
            ModeRow(
                title = stringResource(R.string.editor_window_title),
                hint = stringResource(R.string.editor_window_hint),
                icon = Icons.Outlined.Schedule,
                checked = windowEnabled,
                enabled = !alwaysOn,
                onCheckedChange = { windowEnabled = it },
            )
            if (windowEnabled && !alwaysOn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TimeField(
                        label = stringResource(R.string.editor_from),
                        minutes = startMinutes,
                        onChange = { startMinutes = it },
                        modifier = Modifier.weight(1f),
                    )
                    TimeField(
                        label = stringResource(R.string.editor_to),
                        minutes = endMinutes,
                        onChange = { endMinutes = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (endMinutes <= startMinutes) {
                    Text(
                        stringResource(R.string.editor_overnight),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.editor_wall_time_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ModeRow(
                title = stringResource(R.string.editor_daily_limit_title),
                hint = stringResource(R.string.editor_daily_limit_hint),
                icon = Icons.Outlined.HourglassBottom,
                checked = limitEnabled,
                enabled = !alwaysOn && limitAvailable,
                onCheckedChange = { limitEnabled = it },
            )
            if (!limitAvailable) {
                Text(
                    stringResource(R.string.editor_website_limits_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (limitEnabled && !alwaysOn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LimitNumberField(
                        label = stringResource(R.string.editor_limit_hours),
                        value = limitHours,
                        onChange = { limitHours = it },
                        modifier = Modifier.weight(1f),
                    )
                    LimitNumberField(
                        label = stringResource(R.string.editor_limit_minutes),
                        value = limitMinutes,
                        onChange = { limitMinutes = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                parseLimitTotalMinutes(limitHours, limitMinutes)?.let { totalMinutes ->
                    val tooLarge = totalMinutes >= MINUTES_PER_DAY
                    Text(
                        if (tooLarge) {
                            stringResource(R.string.editor_daily_limit_too_large)
                        } else {
                            stringResource(R.string.editor_limit_preview, formatDuration(totalMinutes * MINUTE_MS))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (tooLarge) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        SectionCard {
            Text(
                stringResource(R.string.editor_scope_title),
                style = MaterialTheme.typography.titleMedium,
            )
            DayChips(
                days = days,
                onChange = { days = it },
                labelRes = R.string.editor_active_days,
            )
            EndsRow(expiresAt = expiresAt, onChange = { expiresAt = it })
            Text(
                stringResource(R.string.editor_timezone_note, deviceZone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            Text(
                stringResource(R.string.editor_partner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.batch_partner_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val selectedPartner = partners.firstOrNull { it.pairId == assignedPairId }
            Text(
                selectedPartner?.let { stringResource(R.string.rules_watched_by, it.name) }
                    ?: stringResource(R.string.rules_not_shared),
                style = MaterialTheme.typography.titleSmall,
                color = if (selectedPartner != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (partners.isEmpty()) {
                Text(
                    stringResource(R.string.editor_partner_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    item {
                        PartnerChoiceRow(
                            title = stringResource(R.string.editor_partner_none),
                            hint = stringResource(R.string.editor_partner_none_hint),
                            selected = assignedPairId == null,
                            onSelect = { assignedPairId = null },
                        )
                    }
                    items(partners) { partner ->
                        PartnerChoiceRow(
                            title = partner.name,
                            selected = assignedPairId == partner.pairId,
                            onSelect = { assignedPairId = partner.pairId },
                        )
                    }
                }
            }
        }

        Button(
            onClick = { save() },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (saving) R.string.batch_saving else R.string.batch_save))
        }
    }

    if (showAppPicker) {
        AppMultiPickerDialog(
            exclude = existingAppKeys,
            onConfirm = { picked ->
                apps = (apps + picked.filterNot { targetKey(it, "APP") in existingAppKeys }).distinct()
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false },
        )
    }

    presetCategoryId?.let { categoryId ->
        WebsitePresetSheet(
            initialCategoryId = categoryId,
            existingRules = existingRules,
            saving = false,
            error = null,
            onDismiss = { presetCategoryId = null },
            onConfirm = { sites ->
                addWebsites(sites)
                presetCategoryId = null
            },
            onAddOwnSite = { presetCategoryId = null },
        )
    }

    if (confirmEngineOff) {
        AlertDialog(
            onDismissRequest = { confirmEngineOff = false },
            title = { Text(stringResource(R.string.editor_engine_off_dialog_title)) },
            text = { Text(stringResource(R.string.editor_engine_off_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEngineOff = false
                    enginePromptHandled = true
                    onEnableWebsiteEngine()
                    save()
                }) { Text(stringResource(R.string.editor_engine_off_turn_on)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmEngineOff = false
                    enginePromptHandled = true
                    save()
                }) { Text(stringResource(R.string.editor_engine_off_save_anyway)) }
            },
        )
    }
}

@Composable
private fun BatchItemRow(label: String, packageName: String?, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppMark(
            packageName = packageName,
            fallback = if (packageName == null) Icons.Default.Language else Icons.Default.Apps,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.batch_remove_item, label),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
