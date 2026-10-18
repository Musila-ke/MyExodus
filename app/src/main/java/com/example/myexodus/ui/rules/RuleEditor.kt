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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.UrlPattern
import com.example.myexodus.blocking.usage.PlatformUsageReader
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.domainusage.EngineMode
import com.example.myexodus.domainusage.HostNormalizer
import com.example.myexodus.domainusage.MeteringConfig
import com.example.myexodus.domainusage.PublicSuffixList
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.formatDate
import com.example.myexodus.ui.DayChips
import com.example.myexodus.ui.TimeField
import com.example.myexodus.ui.formatDuration
import com.example.myexodus.ui.formatTime
import com.example.myexodus.ui.toggleDay
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.vpn.VpnLifecycle.WebsiteEngineBlocker
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
    platformUsage: PlatformUsageReader,
    psl: PublicSuffixList,
    /** Non-null while the website tunnel cannot serve this rule (owner report 2026-10-17):
     *  the warning is stated up front and re-asked at save, so a rule can never be created
     *  silently inert. Null for app rules and a healthy/startable tunnel. */
    websiteEngineBlocker: WebsiteEngineBlocker?,
    /** The runtime engine mode (`DomainUsageEngine.mode`). A website daily limit can fire only
     *  under [EngineMode.ENFORCE]; in any other mode the switch is shown disabled with a notice
     *  rather than letting the build create an inert limit silently (audit A-03). App limits use
     *  the accessibility usage accounting and are unaffected. */
    websiteLimitsMode: EngineMode,
    onEnableWebsiteEngine: () -> Unit,
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
    // New rules start with both fields blank — a prefilled "30" was a default the user had to
    // notice and undo (owner 2026-10-04: "2 hrs means typing 00 in minutes"). Editing an
    // existing rule prefills only the non-zero half; blank reads as 0 on save.
    var limitHours by rememberSaveable {
        mutableStateOf(
            initial?.dailyLimitMs?.takeIf { it > 0L }?.div(60 * MINUTE_MS)?.takeIf { it > 0L }
                ?.toString().orEmpty(),
        )
    }
    var limitMinutes by rememberSaveable {
        mutableStateOf(
            initial?.dailyLimitMs?.takeIf { it > 0L }?.div(MINUTE_MS)?.rem(60)?.takeIf { it > 0L }
                ?.toString().orEmpty(),
        )
    }
    var startMinutes by rememberSaveable { mutableLongStateOf(initial?.timeRangeStart ?: DEFAULT_START_MINUTES) }
    var endMinutes by rememberSaveable { mutableLongStateOf(initial?.timeRangeEnd ?: DEFAULT_END_MINUTES) }
    var days by rememberSaveable { mutableStateOf(initial?.activeDaysOfWeek ?: "1111111") }
    var timezone by rememberSaveable { mutableStateOf(initial?.timezone ?: deviceZone) }
    var expiresAt by rememberSaveable { mutableLongStateOf(initial?.blockExpiresAt ?: 0L) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<EditorError?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmEngineOff by rememberSaveable { mutableStateOf(false) }
    // One prompt per editor session: "Save anyway" is a deliberate choice, so a later save
    // must not re-ask — the persistent Rules row remains the standing reminder.
    var enginePromptHandled by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // "Add app" has no target yet: open the chooser right away.
    LaunchedEffect(Unit) { if (isApp && targetValue.isBlank()) showPicker = true }

    // The banner sits above the form while Save sits below it: from the bottom of a long form
    // a rejected save is otherwise invisible — the message has to come to the user. Keyed on
    // the attempt too: a second save with the identical error value (data class equality)
    // would otherwise skip recomposition, leaving the banner off-screen with zero feedback
    // (device-caught 2026-10-04 — the "inert Save button" that was never inert).
    var saveAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(error, saveAttempt) { if (error != null) scrollState.animateScrollTo(0) }

    // Resolved at composition — save() is a click handler, not a composable (lint: resource
    // reads on LocalContext are not configuration-aware).
    val missingTargetMessage = stringResource(R.string.editor_target_missing)
    val invalidTargetMessage = stringResource(R.string.editor_target_invalid)
    val sameTimeMessage = stringResource(R.string.editor_same_time_error)
    val duplicateMessage = stringResource(R.string.editor_target_duplicate)
    val limitInvalidMessage = stringResource(R.string.editor_daily_limit_invalid)
    val limitTooLargeMessage = stringResource(R.string.editor_daily_limit_too_large)
    val noModeMessage = stringResource(R.string.editor_mode_missing)
    val siteLimitInvalidMessage = stringResource(R.string.editor_site_limit_invalid)
    val siteLimitIpMessage = stringResource(R.string.editor_site_limit_ip)
    val siteLimitTooBroadMessage = stringResource(R.string.editor_site_limit_too_broad)
    val siteLimitNeverBlockMessage = stringResource(R.string.editor_site_limit_never_block)
    val siteLimitResolverMessage = stringResource(R.string.editor_site_limit_resolver)
    val siteLimitDuplicateMessage = stringResource(R.string.editor_site_limit_duplicate)
    val siteLimitUseItLabel = stringResource(R.string.editor_site_limit_use_it)
    val siteLimitHasPathTemplate = stringResource(R.string.editor_site_limit_has_path)

    fun save() {
        error = null
        saveAttempt++
        val value = targetValue.trim()
        // A limit is stored only while no other mechanism overrides it — the same condition
        // `composeRuleFields` applies.
        val limitOn = !alwaysOn && limitEnabled
        val limitTotalMinutes = parseLimitTotalMinutes(limitHours, limitMinutes)
        // §8 S9.1/FR-10: a website save that enables a daily limit runs the full chain —
        // normalize → FR-2 uniqueness (S2.8) → clamp — and its error table replaces the generic
        // website check. Without a limit, a website keeps V1 semantics (a path-scoped block is
        // a legitimate blocklist target, and the metering engine never sees the rule).
        var storedTarget = value
        if (!isApp && limitOn) {
            when (val check = siteLimitTarget(value, psl, siteLimitPatternsOf(existingRules, psl, initial?.ruleId))) {
                is SiteLimitCheck.Ok -> storedTarget = check.host
                is SiteLimitCheck.HasPath -> {
                    error = EditorError(
                        message = siteLimitHasPathTemplate.format(check.suggestedHost),
                        useInstead = check.suggestedHost,
                    )
                    return
                }
                SiteLimitCheck.Invalid -> { error = EditorError(siteLimitInvalidMessage); return }
                SiteLimitCheck.IpLiteral -> { error = EditorError(siteLimitIpMessage); return }
                SiteLimitCheck.TooBroad -> { error = EditorError(siteLimitTooBroadMessage); return }
                SiteLimitCheck.NeverBlock -> { error = EditorError(siteLimitNeverBlockMessage); return }
                SiteLimitCheck.EncryptedDnsResolver ->
                    { error = EditorError(siteLimitResolverMessage); return }
                SiteLimitCheck.DuplicatePattern ->
                    { error = EditorError(siteLimitDuplicateMessage); return }
            }
        }
        val modeError = validateBlockMode(
            alwaysOn = alwaysOn,
            windowEnabled = windowEnabled,
            limitEnabled = limitEnabled,
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
            error = EditorError(invalid)
            return
        }
        val duplicate = existingRules.any { rule ->
            rule.ruleId != initial?.ruleId &&
                rule.targetType == targetType &&
                targetKey(rule.blockedPackage, targetType) == targetKey(storedTarget, targetType)
        }
        if (duplicate) {
            error = EditorError(duplicateMessage)
            return
        }
        // Owner report 2026-10-17: a website rule saved while the tunnel is off is inert —
        // nothing blocks and a daily limit never counts. The choice stays with the user, but
        // it is made knowingly: reaching this point means the fields are valid and unique.
        if (!isApp && websiteEngineBlocker != null && !enginePromptHandled) {
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
        // Finding #17 (2026-10-16): set on Main before the launch. `saving` gates the Save
        // button, and setting it inside the IO coroutine left the dispatcher's queue time as a
        // window where a second tap passed the duplicate check again (existingRules can't see a
        // rule that hasn't committed) and inserted a second identical rule — the collision the
        // duplicate check exists to prevent.
        saving = true
        scope.launch(Dispatchers.IO) {
            runCatching {
                val base = initial ?: RestrictionRule(
                    ruleId = UUID.randomUUID().toString(),
                    profileId = repository.ensureActiveProfile().userId,
                    blockedPackage = storedTarget,
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
                    blockedPackage = storedTarget,
                    dailyLimitMs = clampStoredLimitMs(fields.dailyLimitMs),
                    isAlwaysOn = fields.isAlwaysOn,
                    timeRangeStart = fields.timeRangeStart,
                    timeRangeEnd = fields.timeRangeEnd,
                    activeDaysOfWeek = fields.activeDaysOfWeek,
                    timezone = timezone,
                    blockExpiresAt = expiresAt,
                )
                if (initial == null) repository.insertRule(rule) else repository.updateRule(rule)
            }.onSuccess {
                // OPD #28(a) / user ruling 2026-09-27: a limit starts covering the app NOW, so
                // seed the tracker from the platform's own foreground number — usage from
                // before the rule existed or was enabled is invisible to the engine otherwise
                // (device-caught 2026-09-27: Instagram at DW 5h21m, tracker 0ms).
                // Decision 15 ordering: seed first, then the limit-change recompute (warnings
                // back to NOT_DUE, blockedToday re-derived both ways); a new rule has no row
                // for the recompute, which is a no-op there. A failure here leaves state the
                // next observation re-derives, so it never blocks the save. App-only: that
                // tracker (UsageAccountingEngine) accounts packages, not hosts.
                if (isApp && fields.dailyLimitMs > 0L) {
                    runCatching {
                        usageAccountingEngine.reconcileWithPlatform(
                            storedTarget,
                            platformUsage.todayForegroundMs(storedTarget),
                        )
                        if (initial != null && initial.dailyLimitMs != fields.dailyLimitMs) {
                            usageAccountingEngine.onDailyLimitChanged(storedTarget)
                        }
                    }.onFailure { Log.w(TAG, "Platform usage reconcile failed after save", it) }
                }
                onDone()
            }.onFailure { error = EditorError(notSavedMessage(it, context)) }
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
        // The shared subpage header, not a one-off: "the way out is the same shape on every
        // screen" is BackRow's own contract, and this was the only screen breaking it.
        BackRow(
            label = stringResource(
                when {
                    initial != null -> R.string.editor_edit
                    isApp -> R.string.editor_new_app
                    else -> R.string.editor_new_website
                },
            ),
            onBack = onDone,
        )

        error?.let { failure ->
            ErrorBanner(
                message = failure.message,
                onDismiss = { error = null },
                // The HasPath suggestion is one tap away from being the right value: fill the
                // field rather than telling the user to retype it.
                actionLabel = failure.useInstead?.let { siteLimitUseItLabel },
                onAction = failure.useInstead?.let { host ->
                    {
                        targetValue = host
                        error = null
                    }
                },
            )
        }

        // Owner report 2026-10-17: the engine state must be obvious while the rule is being
        // built, not discovered later when nothing fires. No dismiss — it is a live system
        // claim and leaves when the tunnel does.
        if (!isApp && websiteEngineBlocker != null) {
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
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (targetValue.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        // A long app label or domain otherwise runs into "Change" and can push
                        // it off the row.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
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

            // Rule 2 (daily quota). S9: open to website rules too — the metering engine clocks
            // time on the site's connections, so the mechanism it always had is now reachable
            // (before S9 the switch was app-only and a website rule stored 0). A website limit
            // is only live under ENFORCE (audit A-03): other modes show the switch disabled and
            // say so, instead of storing a limit nothing will ever count against.
            val limitMechanismAvailable = websiteLimitMechanismAvailable(isApp, websiteLimitsMode)
            ModeRow(
                title = stringResource(R.string.editor_daily_limit_title),
                hint = stringResource(R.string.editor_daily_limit_hint),
                checked = limitEnabled,
                enabled = !alwaysOn && limitMechanismAvailable,
                onCheckedChange = { limitEnabled = it },
            )
            if (!isApp && !limitMechanismAvailable) {
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
                if (!isApp) {
                    // S9.2: scope and connection-level attribution, stated where the limit is
                    // configured (R-SNI-SCOPE — the disclosure the plan resolves item 24 with).
                    Text(
                        stringResource(R.string.editor_limit_scope_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.editor_limit_connection_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                }) {
                    Text(stringResource(R.string.editor_engine_off_turn_on))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmEngineOff = false
                    enginePromptHandled = true
                    save()
                }) {
                    Text(stringResource(R.string.editor_engine_off_save_anyway))
                }
            },
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
                            .onFailure { error = EditorError(notSavedMessage(it, context)) }
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
        // A gap, not just SpaceBetween: the hint wraps to the column's full width, so its last
        // word otherwise ends flush against the switch.
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            // The switch is a sibling of its title, not its child: without this its accessible
            // name is a bare "On/Off" with no mechanism attached (findings #25/#26's class).
            modifier = Modifier.semantics { contentDescription = title },
        )
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
    // Settings-row grammar, the LinkRow family in single-line form. Design history (owner,
    // 2026-10-04): the bare "Ends · Never" text row read as a static statement and the owner
    // missed it was tappable; the full-width outlined field that replaced it fixed that but
    // read as a heavy form control and was rejected on looks. The chevron does the affordance
    // work here, and clearing lives inside the dialog so the row keeps one calm line.
    Surface(
        onClick = { picking = true },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.editor_ends),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expiresAt > 0) formatDate(expiresAt) else stringResource(R.string.editor_never),
                style = MaterialTheme.typography.bodyMedium,
                color = if (expiresAt > 0) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
    Text(
        stringResource(R.string.editor_ends_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (picking) {
        ExpiryDateDialog(
            initialEpochMillis = expiresAt,
            onConfirm = {
                onChange(it)
                picking = false
            },
            onDismiss = { picking = false },
            onClear = if (expiresAt > 0) {
                { onChange(0L); picking = false }
            } else {
                null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryDateDialog(
    initialEpochMillis: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)? = null,
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
            Row {
                if (onClear != null) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.editor_clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) }
            }
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

/** A save-time failure shown in the editor banner. [useInstead] is the HasPath suggestion the
 *  "Use it" action fills into the target field (S9.1). */
private data class EditorError(val message: String, val useInstead: String? = null)

/** The S9.1 error table for a website save that enables a daily limit, one case per row. */
internal sealed interface SiteLimitCheck {
    data class Ok(val host: String) : SiteLimitCheck
    data class HasPath(val suggestedHost: String) : SiteLimitCheck
    data object Invalid : SiteLimitCheck
    data object IpLiteral : SiteLimitCheck
    data object TooBroad : SiteLimitCheck
    data object NeverBlock : SiteLimitCheck
    data object EncryptedDnsResolver : SiteLimitCheck
    data object DuplicatePattern : SiteLimitCheck
}

/**
 * §8 S9.1/S2.8: the website-limit save chain — normalize, then the FR-2 uniqueness check
 * against [otherLimitPatterns] (exact match; a subdomain of a limited site is a legal,
 * independently limited rule, resolved by longest-pattern-wins at match time).
 */
internal fun siteLimitTarget(
    raw: String,
    psl: PublicSuffixList,
    otherLimitPatterns: Set<String>,
): SiteLimitCheck = when (val result = HostNormalizer.normalize(raw, psl)) {
    is HostNormalizer.Result.Ok ->
        if (result.host in otherLimitPatterns) SiteLimitCheck.DuplicatePattern
        else SiteLimitCheck.Ok(result.host)
    is HostNormalizer.Result.HasPath -> SiteLimitCheck.HasPath(result.suggestedHost)
    HostNormalizer.Result.Invalid -> SiteLimitCheck.Invalid
    HostNormalizer.Result.IpLiteral -> SiteLimitCheck.IpLiteral
    HostNormalizer.Result.TooBroad -> SiteLimitCheck.TooBroad
    HostNormalizer.Result.NeverBlock -> SiteLimitCheck.NeverBlock
    HostNormalizer.Result.EncryptedDnsResolver -> SiteLimitCheck.EncryptedDnsResolver
}

/**
 * §8 S9.1/S2.8: the collision set for the uniqueness check — the normalizeLegacy form of every
 * *other* website rule with a daily limit. Paused rules are kept in on purpose: a rule
 * re-enabled later would collide in `RuleIndex`, whose dedup silently drops one side (S9 log
 * entry records this reading of "another enabled rule").
 */
internal fun siteLimitPatternsOf(
    rules: List<RestrictionRule>,
    psl: PublicSuffixList,
    excludeRuleId: String?,
): Set<String> = rules.asSequence()
    .filter { it.ruleId != excludeRuleId && it.targetType == "WEBSITE" && it.dailyLimitMs > 0L }
    .mapNotNull { (HostNormalizer.normalizeLegacy(it.blockedPackage, psl) as? HostNormalizer.Result.Ok)?.host }
    .toSet()

/**
 * §8 S9.1's clamp before storing (R-LIMIT-BOUNDS). The editor's own validation already keeps
 * the form inside the range, so this never fires from the UI — it exists so the stored value
 * satisfies the invariant on its own. Zero stays zero: clamping must not switch a limit on.
 */
internal fun clampStoredLimitMs(dailyLimitMs: Long): Long =
    if (dailyLimitMs > 0L) {
        dailyLimitMs.coerceIn(MeteringConfig.MIN_DAILY_LIMIT_MS, MeteringConfig.MAX_DAILY_LIMIT_MS)
    } else {
        0L
    }

/** Validation outcome of the block-mode form; the caller maps it to copy. */
internal enum class ModeError { NoneSelected, SameTime, LimitInvalid, LimitTooLarge }

/** A usage day is 24 h long, so a limit at or above this can never fire (user-reported
 *  2026-09-20: "30 hrs a day" was accepted and would have been an inert rule). */
internal const val MINUTES_PER_DAY = 24 * 60

/** A blank hours or minutes field reads as 0 when the other half carries a number (owner
 *  2026-10-04: "what if i just want 2hrs? i have to type 00 in minutes" — bad UX). A fully
 *  blank pair is still "no limit entered", and a non-numeric entry still fails. */
internal fun parseLimitTotalMinutes(hours: String, minutes: String): Long? {
    if (hours.isBlank() && minutes.isBlank()) return null
    val h = hours.trim().ifEmpty { "0" }.toLongOrNull() ?: return null
    val m = minutes.trim().ifEmpty { "0" }.toLongOrNull() ?: return null
    val total = h * 60 + m
    return total.takeIf { it > 0 }
}

/**
 * Audit A-03: a website daily limit fires only while the domain-usage engine enforces; `OFF`
 * and `ACCOUNT` never shape or block. App limits are metered by the accessibility usage
 * accounting and are live in every mode.
 */
internal fun websiteLimitMechanismAvailable(isApp: Boolean, mode: EngineMode): Boolean =
    isApp || mode == EngineMode.ENFORCE

/**
 * The editor's rules: at least one mechanism selected, a selected window must not be zero-length
 * (equal start/end is the engine's overnight form, ambiguous as a new rule), and a selected
 * limit must total between 1 minute and one day. All checks are skipped for mechanisms
 * "all the time" overrides.
 *
 * There is deliberately no "limit longer than the window" rule (audit A-01). The window is an
 * additional *block* period (BlockDecisionEngine Rule 1), not an allowance: usage outside it
 * still accrues, and the quota fires at `usageTodayMs >= dailyLimitMs` on any active day
 * (Rule 2, day-scoped). A 2 h limit beside a 1 h window is therefore meaningful, and rejecting
 * it would block a coherent rule. `MINUTES_PER_DAY` remains the only upper bound.
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
