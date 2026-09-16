package com.example.myexodus.ui.rules

import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.UrlPattern
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.ui.formatDate
import com.example.myexodus.ui.formatTime
import com.example.myexodus.ui.rememberAppLabel
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/** Visible starting window when Always on is switched off — editable, in the user's zone. */
private const val DEFAULT_START_MINUTES = 22 * 60L
private const val DEFAULT_END_MINUTES = 7 * 60L

private const val MINUTE_MS = 60_000L

private const val TAG = "RuleEditor"

/**
 * Create/edit one block. One rule = one target + one window (the shipped model); the schedule
 * fields carry the engine's own semantics — a window is start-inclusive/end-exclusive, and
 * `end <= start` is the engine's overnight form, so equal times are rejected at save (§8.1).
 * Writes go through [RulesRepository]; nothing is reported saved unless the transaction
 * committed, and a failure keeps the editor open with the reason on screen (plan §6.1/§9).
 */
@Composable
fun RuleEditorScreen(
    initial: RestrictionRule?,
    targetType: String,
    target: String?,
    existingRules: List<RestrictionRule>,
    repository: RulesRepository,
    usageAccountingEngine: UsageAccountingEngine,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceZone = remember { ZoneId.systemDefault().id }
    val isApp = targetType == "APP"

    var targetValue by rememberSaveable { mutableStateOf(initial?.blockedPackage ?: target.orEmpty()) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var limitEnabled by rememberSaveable { mutableStateOf((initial?.dailyLimitMs ?: 0L) > 0L) }
    var limitText by rememberSaveable {
        mutableStateOf(initial?.dailyLimitMs?.takeIf { it > 0L }?.div(MINUTE_MS)?.toString() ?: "30")
    }
    var isAlwaysOn by rememberSaveable { mutableStateOf(initial?.isAlwaysOn ?: true) }
    var startMinutes by rememberSaveable { mutableLongStateOf(initial?.timeRangeStart ?: DEFAULT_START_MINUTES) }
    var endMinutes by rememberSaveable { mutableLongStateOf(initial?.timeRangeEnd ?: DEFAULT_END_MINUTES) }
    var days by rememberSaveable { mutableStateOf(initial?.activeDaysOfWeek ?: "1111111") }
    var timezone by rememberSaveable { mutableStateOf(initial?.timezone ?: deviceZone) }
    var expiresAt by rememberSaveable { mutableLongStateOf(initial?.blockExpiresAt ?: 0L) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // "Add app" has no target yet: open the chooser right away.
    LaunchedEffect(Unit) { if (isApp && targetValue.isBlank()) showPicker = true }

    // Resolved at composition — save() is a click handler, not a composable (lint: resource
    // reads on LocalContext are not configuration-aware).
    val missingTargetMessage = stringResource(R.string.editor_target_missing)
    val invalidTargetMessage = stringResource(R.string.editor_target_invalid)
    val sameTimeMessage = stringResource(R.string.editor_same_time_error)
    val duplicateMessage = stringResource(R.string.editor_target_duplicate)
    val limitInvalidMessage = stringResource(R.string.editor_daily_limit_invalid)

    fun save() {
        error = null
        val value = targetValue.trim()
        // §9: the UI clamps the daily limit's minimum to 1 minute; an unset limit stores 0
        // (the engine's "untracked for Rule 2" value — schedule blocking is unaffected).
        val limitMinutes = limitText.trim().toLongOrNull()
        val limitInvalid = isApp && limitEnabled && (limitMinutes == null || limitMinutes < 1L)
        val newLimitMs = if (isApp && limitEnabled && !limitInvalid) limitMinutes!! * MINUTE_MS else 0L
        val invalid = when {
            isApp && value.isEmpty() -> missingTargetMessage
            !isApp && !isValidWebsiteTarget(value) -> invalidTargetMessage
            !isAlwaysOn && startMinutes == endMinutes -> sameTimeMessage
            limitInvalid -> limitInvalidMessage
            else -> null
        }
        if (invalid != null) {
            error = invalid
            return
        }
        val duplicate = existingRules.any { rule ->
            rule.ruleId != initial?.ruleId &&
                rule.targetType == targetType &&
                targetKey(rule.blockedPackage, targetType) == targetKey(value, targetType)
        }
        if (duplicate) {
            error = duplicateMessage
            return
        }
        scope.launch {
            saving = true
            runCatching {
                val base = initial ?: RestrictionRule(
                    ruleId = UUID.randomUUID().toString(),
                    profileId = repository.ensureActiveProfile().userId,
                    blockedPackage = value,
                    targetType = targetType,
                    isAlwaysOn = true,
                    dailyLimitMs = 0L,
                    timeRangeStart = null,
                    timeRangeEnd = null,
                    activeDaysOfWeek = "1111111",
                    timezone = deviceZone,
                    blockExpiresAt = 0L,
                    severityLevel = "HIGH",
                )
                val rule = base.copy(
                    blockedPackage = value,
                    dailyLimitMs = newLimitMs,
                    isAlwaysOn = isAlwaysOn,
                    // Always on means no window: the stored rule stays honest, so every row
                    // and the engine read the same thing.
                    timeRangeStart = if (isAlwaysOn) null else startMinutes,
                    timeRangeEnd = if (isAlwaysOn) null else endMinutes,
                    activeDaysOfWeek = if (isAlwaysOn) "1111111" else days,
                    timezone = timezone,
                    blockExpiresAt = expiresAt,
                )
                if (initial == null) repository.insertRule(rule) else repository.updateRule(rule)
            }.onSuccess {
                // Decision 15: a mid-day limit change must recompute the tracker AFTER the
                // new value is committed (warnings back to NOT_DUE, blockedToday re-derived
                // in both directions). Only edits can have a tracker row; a failure here
                // leaves state the next observation re-derives, so it never blocks the save.
                if (isApp && initial != null && initial.dailyLimitMs != newLimitMs && newLimitMs > 0L) {
                    runCatching { usageAccountingEngine.onDailyLimitChanged(value) }
                        .onFailure { Log.w(TAG, "Daily-limit recompute failed after save", it) }
                }
                onDone()
            }.onFailure { error = notSavedMessage(it, context) }
            saving = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.editor_back)) }
            Text(
                stringResource(
                    when {
                        initial != null -> R.string.editor_edit
                        isApp -> R.string.editor_new_app
                        else -> R.string.editor_new_website
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        error?.let { message ->
            ErrorBanner(message = message, onDismiss = { error = null })
        }

        SectionCard {
            Text(
                stringResource(if (isApp) R.string.editor_target_app else R.string.editor_target_website),
                style = MaterialTheme.typography.titleMedium,
            )
            if (isApp) {
                val label = if (targetValue.isBlank()) {
                    stringResource(R.string.editor_choose_app)
                } else {
                    rememberAppLabel(targetValue)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (targetValue.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { showPicker = true }) {
                        Text(stringResource(if (targetValue.isBlank()) R.string.editor_choose_app else R.string.editor_change_app))
                    }
                }
            } else {
                OutlinedTextField(
                    value = targetValue,
                    onValueChange = { targetValue = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.editor_website_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Rule 2 (daily quota) is an app-only mechanism — the engine tracks packages, so a
        // website rule is never offered a limit that could not be enforced.
        if (isApp) {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.editor_daily_limit_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.editor_daily_limit_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = limitEnabled, onCheckedChange = { limitEnabled = it })
                }
                if (limitEnabled) {
                    OutlinedTextField(
                        value = limitText,
                        onValueChange = { limitText = it.filter(Char::isDigit) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.editor_daily_limit_field_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.editor_always_on), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.editor_always_on_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = isAlwaysOn, onCheckedChange = { isAlwaysOn = it })
            }

            if (!isAlwaysOn) {
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
                DayChips(days = days, onChange = { days = it })
            }

            // Expiry applies to every rule (Req 5), not just windowed ones: the recovery
            // unlock expires always-on rules too, so the editor must be able to re-arm them.
            EndsRow(
                expiresAt = expiresAt,
                onChange = { expiresAt = it },
            )

            Text(
                stringResource(R.string.editor_timezone_note, timezone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (timezone != deviceZone) {
                TextButton(onClick = { timezone = deviceZone }) {
                    Text(stringResource(R.string.editor_use_device_zone))
                }
            }
        }

        Button(
            onClick = { save() },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (saving) R.string.editor_saving else R.string.editor_save))
        }

        if (initial != null) {
            TextButton(
                onClick = { confirmDelete = true },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.rules_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            onPick = { packageName ->
                targetValue = packageName
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }

    if (confirmDelete && initial != null) {
        val label = if (isApp) rememberAppLabel(initial.blockedPackage) else initial.blockedPackage
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.rules_delete_title)) },
            text = { Text(stringResource(R.string.rules_delete_body, label)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    error = null
                    scope.launch {
                        saving = true
                        runCatching { repository.deleteRule(initial) }
                            .onSuccess { onDone() }
                            .onFailure { error = notSavedMessage(it, context) }
                        saving = false
                    }
                }) { Text(stringResource(R.string.rules_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.rules_cancel)) }
            },
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun TimeField(
    label: String,
    minutes: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, modifier = modifier) {
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(formatTime(minutes), style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (picking) {
        TimePickerDialog(
            initialMinutes = minutes,
            onConfirm = {
                onChange(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun EndsRow(expiresAt: Long, onChange: (Long) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.editor_ends), style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (expiresAt > 0) {
                TextButton(onClick = { onChange(0L) }) { Text(stringResource(R.string.editor_clear)) }
            }
            TextButton(onClick = { picking = true }) {
                Text(
                    if (expiresAt > 0) formatDate(expiresAt) else stringResource(R.string.editor_never),
                )
            }
        }
    }
    if (picking) {
        ExpiryDateDialog(
            initialEpochMillis = expiresAt,
            onConfirm = {
                onChange(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayChips(days: String, onChange: (String) -> Unit) {
    val presets = listOf(
        R.string.editor_days_every to "1111111",
        R.string.editor_days_weekdays to "1111100",
        R.string.editor_days_weekends to "0000011",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.editor_days), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (labelRes, value) ->
                FilterChip(
                    selected = days == value,
                    onClick = { onChange(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..6).forEach { index ->
                val selected = days.getOrNull(index) == '1'
                FilterChip(
                    selected = selected,
                    // Never let the last day go: a days set with no day never blocks.
                    onClick = { if (selected && days.count { it == '1' } == 1) return@FilterChip else onChange(toggleDay(days, index)) },
                    label = {
                        Text(
                            DayOfWeek.of(index + 1)
                                .getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0]),
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinutes: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = (initialMinutes / 60).toInt(),
        initialMinute = (initialMinutes % 60).toInt(),
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60L + state.minute) }) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) }
        },
        text = { TimePicker(state = state) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryDateDialog(
    initialEpochMillis: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialEpochMillis.toPickerDayMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onConfirm(it.toLocalEndOfDayMillis()) }
                },
            ) { Text(stringResource(R.string.dialog_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) }
        },
    ) { DatePicker(state = state) }
}

/** Stored expiry is an end-of-day instant; the picker works in UTC whole days. */
private fun Long.toPickerDayMillis(): Long? = takeIf { it > 0 }?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

private fun Long.toLocalEndOfDayMillis(): Long =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
        .atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

internal fun toggleDay(days: String, index: Int): String {
    val chars = days.padEnd(7, '0').toCharArray()
    chars[index] = if (chars[index] == '1') '0' else '1'
    return String(chars)
}

/**
 * Website targets are stored as typed; validation only rejects what can never match — the
 * engine's parser (unparseable text, whitespace) or a host with no dot, which no browser
 * address produces.
 */
internal fun isValidWebsiteTarget(raw: String): Boolean {
    if (raw.isEmpty() || raw.any { it.isWhitespace() }) return false
    val structure = UrlPattern.parseStructure(raw) ?: return false
    return structure.hostUnicode.contains('.')
}

/** Case- and form-insensitive identity of a target, for "already blocked" checks. */
internal fun targetKey(raw: String, targetType: String): String {
    val trimmed = raw.trim().lowercase(Locale.ROOT)
    if (targetType == "APP") return trimmed
    val structure = UrlPattern.parseStructure(trimmed) ?: return trimmed
    return structure.hostUnicode + (structure.port?.let { ":$it" } ?: "") + (structure.pathPrefix ?: "")
}
