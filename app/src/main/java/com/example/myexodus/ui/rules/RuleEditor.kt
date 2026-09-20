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
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.UrlPattern
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.ui.formatDate
import com.example.myexodus.ui.DayChips
import com.example.myexodus.ui.TimeField
import com.example.myexodus.ui.formatDuration
import com.example.myexodus.ui.formatTime
import com.example.myexodus.ui.toggleDay
import com.example.myexodus.ui.rememberAppLabel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Visible starting window when Always on is switched off — editable, in the user's zone. */
private const val DEFAULT_START_MINUTES = 22 * 60L
private const val DEFAULT_END_MINUTES = 7 * 60L

private const val MINUTE_MS = 60_000L
private const val MILLIS_PER_DAY = 86_400_000L

private const val TAG = "RuleEditor"

/**
 * Create/edit one block. One rule = one target + a combination of three independent mechanisms
 * ("block all day", a schedule window, a daily limit) + one day scope that bounds the whole
 * rule; at least one mechanism is required (user decision 2026-09-20 — the old form defaulted
 * to always-on and forced a window off it, which is what made a plain daily limit unreachable).
 * The schedule fields carry the engine's own semantics — a window is start-inclusive/
 * end-exclusive, and `end <= start` is the engine's overnight form, so equal times are rejected
 * at save (§8.1). Writes go through [RulesRepository]; nothing is reported saved unless the
 * transaction committed, and a failure keeps the editor open with the reason on screen
 * (plan §6.1/§9).
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
    // A new rule starts with nothing selected — picking the mode is the first deliberate
    // choice, never a default the user has to notice and undo.
    var alwaysOn by rememberSaveable { mutableStateOf(initial?.isAlwaysOn ?: false) }
    var windowEnabled by rememberSaveable {
        mutableStateOf(initial?.timeRangeStart != null && initial?.timeRangeEnd != null)
    }
    var limitEnabled by rememberSaveable { mutableStateOf((initial?.dailyLimitMs ?: 0L) > 0L) }
    var limitHours by rememberSaveable {
        mutableStateOf(initial?.dailyLimitMs?.takeIf { it > 0L }?.div(60 * MINUTE_MS)?.toString() ?: "0")
    }
    var limitMinutes by rememberSaveable {
        mutableStateOf(initial?.dailyLimitMs?.takeIf { it > 0L }?.div(MINUTE_MS)?.rem(60)?.toString() ?: "30")
    }
    var startMinutes by rememberSaveable { mutableLongStateOf(initial?.timeRangeStart ?: DEFAULT_START_MINUTES) }
    var endMinutes by rememberSaveable { mutableLongStateOf(initial?.timeRangeEnd ?: DEFAULT_END_MINUTES) }
    var days by rememberSaveable { mutableStateOf(initial?.activeDaysOfWeek ?: "1111111") }
    var timezone by rememberSaveable { mutableStateOf(initial?.timezone ?: deviceZone) }
    var expiresAt by rememberSaveable { mutableLongStateOf(initial?.blockExpiresAt ?: 0L) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // "Add app" has no target yet: open the chooser right away.
    LaunchedEffect(Unit) { if (isApp && targetValue.isBlank()) showPicker = true }

    // The banner sits above the form while Save sits below it: from the bottom of a long form
    // a rejected save is otherwise invisible — the message has to come to the user.
    LaunchedEffect(error) { if (error != null) scrollState.animateScrollTo(0) }

    // Resolved at composition — save() is a click handler, not a composable (lint: resource
    // reads on LocalContext are not configuration-aware).
    val missingTargetMessage = stringResource(R.string.editor_target_missing)
    val invalidTargetMessage = stringResource(R.string.editor_target_invalid)
    val sameTimeMessage = stringResource(R.string.editor_same_time_error)
    val duplicateMessage = stringResource(R.string.editor_target_duplicate)
    val limitInvalidMessage = stringResource(R.string.editor_daily_limit_invalid)
    val limitTooLargeMessage = stringResource(R.string.editor_daily_limit_too_large)
    val noModeMessage = stringResource(R.string.editor_mode_missing)

    fun save() {
        error = null
        val value = targetValue.trim()
        // The limit is app-only (the engine tracks packages); a website rule always stores 0.
        val limitTotalMinutes = parseLimitTotalMinutes(limitHours, limitMinutes)
        val modeError = validateBlockMode(
            alwaysOn = alwaysOn,
            windowEnabled = windowEnabled,
            limitEnabled = isApp && limitEnabled,
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            limitTotalMinutes = limitTotalMinutes,
        )
        val invalid = when {
            isApp && value.isEmpty() -> missingTargetMessage
            !isApp && !isValidWebsiteTarget(value) -> invalidTargetMessage
            modeError == ModeError.NoneSelected -> noModeMessage
            modeError == ModeError.SameTime -> sameTimeMessage
            modeError == ModeError.LimitInvalid -> limitInvalidMessage
            modeError == ModeError.LimitTooLarge -> limitTooLargeMessage
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
        val fields = composeRuleFields(
            alwaysOn = alwaysOn,
            windowEnabled = windowEnabled,
            limitEnabled = isApp && limitEnabled,
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            days = days,
            limitTotalMinutes = limitTotalMinutes,
        )
        scope.launch(Dispatchers.IO) {
            saving = true
            runCatching {
                val base = initial ?: RestrictionRule(
                    ruleId = UUID.randomUUID().toString(),
                    profileId = repository.ensureActiveProfile().userId,
                    blockedPackage = value,
                    targetType = targetType,
                    isAlwaysOn = false,
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
                    dailyLimitMs = fields.dailyLimitMs,
                    isAlwaysOn = fields.isAlwaysOn,
                    timeRangeStart = fields.timeRangeStart,
                    timeRangeEnd = fields.timeRangeEnd,
                    activeDaysOfWeek = fields.activeDaysOfWeek,
                    timezone = timezone,
                    blockExpiresAt = expiresAt,
                )
                if (initial == null) repository.insertRule(rule) else repository.updateRule(rule)
            }.onSuccess {
                // Decision 15: a mid-day limit change must recompute the tracker AFTER the
                // new value is committed (warnings back to NOT_DUE, blockedToday re-derived
                // in both directions). Only edits can have a tracker row; a failure here
                // leaves state the next observation re-derives, so it never blocks the save.
                if (isApp && initial != null && initial.dailyLimitMs != fields.dailyLimitMs &&
                    fields.dailyLimitMs > 0L
                ) {
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
            .verticalScroll(scrollState)
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
                    // A hostname is not prose: sentence capitalization turned "github.com"
                    // into "GitHub.com" on-device (XOS IME, 2026-09-19).
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionCard {
            Text(
                stringResource(R.string.editor_mode_title),
                style = MaterialTheme.typography.titleMedium,
            )

            ModeRow(
                title = stringResource(R.string.editor_always_on),
                hint = stringResource(R.string.editor_always_on_hint),
                checked = alwaysOn,
                enabled = true,
                onCheckedChange = { alwaysOn = it },
            )

            ModeRow(
                title = stringResource(R.string.editor_window_title),
                hint = stringResource(R.string.editor_window_hint),
                checked = windowEnabled,
                // Overridden while "all the time" is on: shown, not editable, values kept.
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

            // Rule 2 (daily quota) is an app-only mechanism — the engine tracks packages, so a
            // website rule is never offered a limit that could not be enforced.
            if (isApp) {
                ModeRow(
                    title = stringResource(R.string.editor_daily_limit_title),
                    hint = stringResource(R.string.editor_daily_limit_hint),
                    checked = limitEnabled,
                    enabled = !alwaysOn,
                    onCheckedChange = { limitEnabled = it },
                )
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
                    // The whole point of h+m fields: no mental math. Show the total — and past a
                    // full day say why it is refused, rather than echoing an inert limit.
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

            // Day scope is rule-level (user decision 2026-09-20): the days here bound every
            // mechanism above — "Block all day" on weekends only is a weekend lockdown.
            DayChips(
                days = days,
                onChange = { days = it },
                labelRes = R.string.editor_active_days,
            )

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
                    scope.launch(Dispatchers.IO) {
                        saving = true
                        runCatching { repository.deleteRule(initial) }
                            .onSuccess { onDone() }
                            .onFailure { error = notSavedMessage(it, context) }
                        saving = false
                    }
                }) {
                    // Destructive confirm wears the error role (finding #18) — it must not
                    // look identical to an affirmative/save action.
                    Text(
                        stringResource(R.string.rules_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.rules_cancel)) }
            },
        )
    }
}

/** One blocking mechanism row: title + hint + switch. Disabled rows keep their checked
 *  state — the value survives while another mechanism overrides it. */
@Composable
private fun ModeRow(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun LimitNumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(4)) },
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryDateDialog(
    initialEpochMillis: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialEpochMillis.toPickerDayMillis(),
        selectableDates = NotPastDates,
    )
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

/** A past expiry lifts the rule the moment it is saved (engine Rule 3) — never offerable. */
private object NotPastDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        utcTimeMillis >= LocalDate.now().toEpochDay() * MILLIS_PER_DAY
}

/** Stored expiry is an end-of-day instant; the picker works in UTC whole days. */
private fun Long.toPickerDayMillis(): Long? = takeIf { it > 0 }?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

private fun Long.toLocalEndOfDayMillis(): Long =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
        .atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Validation outcome of the block-mode form; the caller maps it to copy. */
internal enum class ModeError { NoneSelected, SameTime, LimitInvalid, LimitTooLarge }

/** A usage day is 24 h long, so a limit at or above this can never fire (user-reported
 *  2026-09-20: "30 hrs a day" was accepted and would have been an inert rule). */
internal const val MINUTES_PER_DAY = 24 * 60

internal fun parseLimitTotalMinutes(hours: String, minutes: String): Long? {
    val h = hours.trim().toLongOrNull() ?: return null
    val m = minutes.trim().toLongOrNull() ?: return null
    val total = h * 60 + m
    return total.takeIf { it > 0 }
}

/**
 * The editor's rules: at least one mechanism selected, a selected window must not be zero-length
 * (equal start/end is the engine's overnight form, ambiguous as a new rule), and a selected
 * limit must total between 1 minute and one day. All checks are skipped for mechanisms
 * "all the time" overrides.
 */
internal fun validateBlockMode(
    alwaysOn: Boolean,
    windowEnabled: Boolean,
    limitEnabled: Boolean,
    startMinutes: Long,
    endMinutes: Long,
    limitTotalMinutes: Long?,
): ModeError? = when {
    !alwaysOn && !windowEnabled && !limitEnabled -> ModeError.NoneSelected
    !alwaysOn && windowEnabled && startMinutes == endMinutes -> ModeError.SameTime
    !alwaysOn && limitEnabled && limitTotalMinutes == null -> ModeError.LimitInvalid
    !alwaysOn && limitEnabled && limitTotalMinutes != null && limitTotalMinutes >= MINUTES_PER_DAY ->
        ModeError.LimitTooLarge
    else -> null
}

/** The stored fields a rule ends up with. "All the time" stores no window and no limit — the
 *  stored rule stays honest (rows and engine read the same thing), while the form keeps the
 *  user's values so flipping the switch back restores them. Days are stored for every rule:
 *  they scope the whole rule, not just a window. */
internal data class RuleFields(
    val isAlwaysOn: Boolean,
    val timeRangeStart: Long?,
    val timeRangeEnd: Long?,
    val activeDaysOfWeek: String,
    val dailyLimitMs: Long,
)

internal fun composeRuleFields(
    alwaysOn: Boolean,
    windowEnabled: Boolean,
    limitEnabled: Boolean,
    startMinutes: Long,
    endMinutes: Long,
    days: String,
    limitTotalMinutes: Long?,
): RuleFields {
    val window = !alwaysOn && windowEnabled
    val limit = !alwaysOn && limitEnabled
    return RuleFields(
        isAlwaysOn = alwaysOn,
        timeRangeStart = if (window) startMinutes else null,
        timeRangeEnd = if (window) endMinutes else null,
        activeDaysOfWeek = days,
        dailyLimitMs = if (limit) (limitTotalMinutes ?: 0L) * MINUTE_MS else 0L,
    )
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
