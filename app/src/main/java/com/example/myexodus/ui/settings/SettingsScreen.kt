package com.example.myexodus.ui.settings

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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.BuildConfig
import com.example.myexodus.R
import com.example.myexodus.account.AuthState
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.blocking.usage.AccountingOutage
import com.example.myexodus.blocking.usage.AccountingOutageSource
import com.example.myexodus.recovery.RecoveryPlanRepository
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.IntegrityStatus
import com.example.myexodus.settings.ProtectionChecks
import com.example.myexodus.settings.StrictModeManager
import com.example.myexodus.settings.WidgetSettings
import com.example.myexodus.settings.readProtectionChecks
import com.example.myexodus.settings.requestBatteryExemption
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.ui.BrandMark
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.recovery.CoachingPlanScreen
import com.example.myexodus.ui.recovery.FrictionTimerScreen
import com.example.myexodus.ui.recovery.RecoveryPrefs
import com.example.myexodus.ui.recovery.RiskWindowsScreen
import com.example.myexodus.ui.rememberWindowWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings as an index (owner sign-off 2026-09-22, porting the reference's flat row list): one
 * row per thing the user can check or change, and the six permission/enforcement rows — the only
 * ones carrying a live status word and an action — behind the Protection row. Everything else is
 * one tap: switches toggle in place, destinations open a pane.
 */
@Composable
fun SettingsScreen(
    monitor: EnhancedModeHealthMonitor,
    strictMode: StrictModeManager,
    settings: EnhancedModeSettings,
    crashReporting: CrashReporting,
    widgetSettings: WidgetSettings,
    onWidgetSettingsChanged: () -> Unit,
    planRepository: RecoveryPlanRepository,
    /** Owner request 2026-11-01: the friction timer pane reads/writes the policy row. */
    recoveryStore: RecoveryStateStore,
    /** The coaching editor resolves the active profile through this (Req 20 / v3 W3). */
    rulesRepository: RulesRepository,
    recoveryPrefs: RecoveryPrefs,
    accountingOutageSource: AccountingOutageSource,
    /** Account plan §8.3: the optional-account pane's driver. */
    account: AccountViewModel,
    /** Account plan §7 Release 2 step 12: the ABK key-bundle pane. */
    backupKey: BackupKeyViewModel,
    /** SOS §8.19 (Step 11): the recipient's block switch — null hides the row (no partner). */
    modifier: Modifier = Modifier,
    partnerBlock: NudgeSettingsViewModel.UiState? = null,
    onPartnerBlockRefresh: () -> Unit = {},
    onPartnerBlockChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serviceEnabled by monitor.enabled.collectAsStateWithLifecycle()
    var checks by remember { mutableStateOf<ProtectionChecks?>(null) }
    // §8.7/D1's Settings surface input: re-read on every resume beside the permission rows,
    // since the exemption the user just granted is exactly what changes this answer.
    var outage by remember { mutableStateOf<AccountingOutage?>(null) }
    // Every destination is a full-screen pane of this tab (the Recovery tab's own pattern):
    // null = the index, otherwise the open pane.
    var pane by remember { mutableStateOf<String?>(null) }
    // System back leaves a pane the way its BackRow does; at the index the shell's own
    // handler takes over (tab → Home).
    BackHandler(enabled = pane != null) { pane = null }

    // Permissions change in system Settings while this screen is backgrounded; re-read on
    // every resume so a granted permission is never shown as missing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                monitor.refresh()
                scope.launch { checks = withContext(Dispatchers.IO) { readProtectionChecks(context) } }
                scope.launch { outage = withContext(Dispatchers.IO) { accountingOutageSource.current() } }
                onPartnerBlockRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // SOS §8.19: tab entry is not a resume (the activity never left), so the switch's state is
    // also re-read here — a pairing finished elsewhere in the app must land before this
    // composition shows the row.
    LaunchedEffect(Unit) { onPartnerBlockRefresh() }

    val loaded = checks
    when (pane) {
        // Finding #53: the pane can be opened before the resume re-read lands (the index
        // renders with no checks), and only this pane renders from `loaded` — without a
        // fallback the tap produced a blank frame.
        PANE_PROTECTION -> if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            ProtectionScreen(
                checks = loaded,
                serviceEnabled = serviceEnabled,
                onRecheck = {
                    monitor.refresh()
                    scope.launch { checks = withContext(Dispatchers.IO) { readProtectionChecks(context) } }
                },
                // D1: the offer, and the one place its request is fired from. Consuming the
                // offer is part of firing it — the eligibility it came from is spent.
                batteryPrompt = outage?.promptRoute,
                onRequestExemption = {
                    accountingOutageSource.markOfferShown()
                    requestBatteryExemption(context)
                },
                onBack = { pane = null },
                modifier = modifier,
            )
        }
        PANE_COACHING_PLAN -> CoachingPlanScreen(
            planRepository = planRepository,
            rulesRepository = rulesRepository,
            prefs = recoveryPrefs,
            onBack = { pane = null },
            modifier = modifier,
        )
        PANE_RISK_WINDOWS -> RiskWindowsScreen(
            planRepository = planRepository,
            prefs = recoveryPrefs,
            onBack = { pane = null },
            modifier = modifier,
        )
        PANE_FRICTION_TIMER -> FrictionTimerScreen(
            recoveryStore = recoveryStore,
            prefs = recoveryPrefs,
            onBack = { pane = null },
            modifier = modifier,
        )
        PANE_ABOUT -> AboutScreen(onBack = { pane = null }, modifier = modifier)
        PANE_ACCOUNT -> AccountPane(
            viewModel = account,
            onBack = { pane = null },
            onOpenBackupKey = { pane = PANE_BACKUP_KEY },
            modifier = modifier,
        )
        PANE_BACKUP_KEY -> BackupKeyScreen(
            viewModel = backupKey,
            onBack = { pane = PANE_ACCOUNT },
            modifier = modifier,
        )
        else -> SettingsIndex(
            modifier = modifier,
            serviceEnabled = serviceEnabled,
            checks = loaded,
            account = account,
            monitor = monitor,
            strictMode = strictMode,
            settings = settings,
            crashReporting = crashReporting,
            widgetSettings = widgetSettings,
            onWidgetSettingsChanged = onWidgetSettingsChanged,
            partnerBlock = partnerBlock,
            onPartnerBlockChange = onPartnerBlockChange,
            onOpenPane = { pane = it },
        )
    }
}

private const val PANE_PROTECTION = "protection"
private const val PANE_COACHING_PLAN = "coaching_plan"
private const val PANE_RISK_WINDOWS = "risk_windows"
private const val PANE_FRICTION_TIMER = "friction_timer"
private const val PANE_ABOUT = "about"
private const val PANE_ACCOUNT = "account"
private const val PANE_BACKUP_KEY = "backup_key"

@Composable
private fun SettingsIndex(
    serviceEnabled: Boolean,
    checks: ProtectionChecks?,
    account: AccountViewModel,
    monitor: EnhancedModeHealthMonitor,
    strictMode: StrictModeManager,
    settings: EnhancedModeSettings,
    crashReporting: CrashReporting,
    widgetSettings: WidgetSettings,
    onWidgetSettingsChanged: () -> Unit,
    partnerBlock: NudgeSettingsViewModel.UiState?,
    onPartnerBlockChange: (Boolean) -> Unit,
    onOpenPane: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    val context = LocalContext.current
    var discreet by remember { mutableStateOf(widgetSettings.discreetMode) }
    var crashReportsOn by remember { mutableStateOf(crashReporting.enabled) }
    // Owned here so switching Strict on recomposes the Enhanced row in the same frame (its
    // switch must grey out at once, not up to 60 s later on its own tick).
    var strictOn by remember { mutableStateOf(strictMode.strictModeEnabled) }
    // Null until the checks land: the row shows no status rather than claiming one it hasn't read.
    val attention = checks?.let { attentionCount(it, serviceEnabled) }
    // §8.3: the identity row states the current mode without implying an account is required.
    val accountState by account.state.collectAsStateWithLifecycle()
    val accountSubtitle = when (val current = accountState) {
        is AuthState.PersistentActive -> stringResource(
            if (current.email != null && !current.emailVerified) {
                R.string.account_settings_subtitle_unverified
            } else {
                R.string.account_settings_subtitle_signed_in
            },
            current.email ?: stringResource(R.string.account_google_account),
        )
        AuthState.Initializing, is AuthState.Authenticating, is AuthState.AuthError,
        is AuthState.ReauthRequired -> null
        AuthState.SignedOut, is AuthState.GuestActive ->
            stringResource(R.string.account_settings_subtitle_guest)
    }
    val protectionSubtitle = when (attention) {
        null -> null
        0 -> stringResource(R.string.settings_protection_summary_ok)
        else -> pluralStringResource(R.plurals.settings_protection_summary_attention, attention, attention)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BrandMark()
            Column {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                IndexRow(
                    icon = Icons.Outlined.Person,
                    title = stringResource(R.string.account_settings_title),
                    subtitle = accountSubtitle,
                    onClick = { onOpenPane(PANE_ACCOUNT) },
                )

                RowDivider()
                IndexRow(
                    icon = Icons.Outlined.Shield,
                    title = stringResource(R.string.settings_protection_header),
                    subtitle = protectionSubtitle,
                    tone = when (attention) {
                        null -> null
                        0 -> StatusTone.GOOD
                        else -> StatusTone.ATTENTION
                    },
                    onClick = { onOpenPane(PANE_PROTECTION) },
                )

                RowDivider()
                EnhancedModeToggleRow(monitor, strictMode, settings, strictOn)

                RowDivider()
                StrictModeToggleRow(
                    monitor,
                    strictMode,
                    strictOn,
                    onStrictOnChange = { strictOn = it },
                )

                RowDivider()
                ToggleRow(
                    icon = Icons.Outlined.Widgets,
                    title = stringResource(R.string.settings_widget_discreet_title),
                    subtitle = stringResource(R.string.settings_widget_discreet_body),
                    checked = discreet,
                    enabled = true,
                    onCheckedChange = { enabled ->
                        widgetSettings.discreetMode = enabled
                        discreet = enabled
                        onWidgetSettingsChanged()
                    },
                )

                RowDivider()
                IndexRow(
                    icon = Icons.Outlined.Checklist,
                    title = stringResource(R.string.recovery_plan_editor_title),
                    subtitle = stringResource(R.string.recovery_plan_editor_intro),
                    onClick = { onOpenPane(PANE_COACHING_PLAN) },
                )

                RowDivider()
                IndexRow(
                    icon = Icons.Outlined.Schedule,
                    title = stringResource(R.string.settings_recovery_windows_title),
                    subtitle = stringResource(R.string.settings_recovery_windows_body),
                    onClick = { onOpenPane(PANE_RISK_WINDOWS) },
                )

                RowDivider()
                IndexRow(
                    icon = Icons.Outlined.Timer,
                    title = stringResource(R.string.recovery_timer_label),
                    subtitle = stringResource(R.string.settings_friction_timer_body),
                    onClick = { onOpenPane(PANE_FRICTION_TIMER) },
                )

                // SOS §8.19 (Step 11): the recipient's block switch — it configures exactly
                // the pair the inbox listens on (§19.5 rule) and names that partner, and the
                // write is one document: already-delivered notifications are never retracted.
                partnerBlock?.let { block ->
                    RowDivider()
                    ToggleRow(
                        icon = Icons.Outlined.NotificationsOff,
                        title = stringResource(R.string.settings_partner_block_title),
                        subtitle = stringResource(
                            R.string.settings_partner_block_body,
                            block.partnerName,
                        ),
                        checked = block.blocked,
                        enabled = !block.saving,
                        onCheckedChange = onPartnerBlockChange,
                        warning = if (block.saveFailed) {
                            stringResource(R.string.settings_partner_block_failed)
                        } else {
                            null
                        },
                    )
                }

                RowDivider()
                ToggleRow(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_crash_reports_title),
                    subtitle = stringResource(R.string.settings_crash_reports_body),
                    checked = crashReportsOn,
                    enabled = true,
                    onCheckedChange = { enabled ->
                        crashReporting.enabled = enabled
                        crashReportsOn = enabled
                    },
                )

                RowDivider()
                IndexRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.settings_about_title),
                    subtitle = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                    onClick = { onOpenPane(PANE_ABOUT) },
                )
            }
        }
    }
}

/**
 * How many of the Protection rows carry an ATTENTION tone — the index row's own claim, so it has
 * to mirror them exactly: the five that can be wrong, with integrity counted only when it has
 * actually failed (unverified and unavailable are informational, and the OEM row never reports).
 */
internal fun attentionCount(checks: ProtectionChecks, serviceEnabled: Boolean): Int = listOf(
    !serviceEnabled,
    !checks.usageAccess,
    !checks.batteryExempt,
    !checks.overlayGranted,
    checks.integrity == IntegrityStatus.FAILED,
).count { it }
