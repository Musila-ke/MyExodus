package com.example.myexodus.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.outlined.Handshake as HandshakeOutlined
import androidx.compose.material.icons.outlined.Home as HomeOutlined
import androidx.compose.material.icons.outlined.LocalFireDepartment as FlameOutlined
import androidx.compose.material.icons.outlined.SelfImprovement as LotusOutlined
import androidx.compose.material.icons.outlined.Settings as SettingsOutlined
import androidx.compose.material.icons.outlined.Shield as ShieldOutlined
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.blocking.AttemptRecorder
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.blocking.ProtectionDegradation
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.blocking.usage.AccountingOutageSource
import com.example.myexodus.blocking.usage.PlatformUsageReader
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.accountability.sos.NudgeEnvelope
import com.example.myexodus.accountability.sos.NudgeRepository
import com.example.myexodus.accountability.sync.MessageSendOutcome
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.domainusage.DomainUsageEngine
import com.example.myexodus.domainusage.PublicSuffixList
import com.example.myexodus.recovery.RecoveryPlanRepository
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.StrictModeManager
import com.example.myexodus.settings.WidgetSettings
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.ui.home.HomeScreen
import com.example.myexodus.ui.home.NudgeInboxViewModel
import com.example.myexodus.ui.home.SosViewModel
import com.example.myexodus.ui.partner.PairingCeremonyDeps
import com.example.myexodus.ui.partner.PartnerDashboardSource
import com.example.myexodus.ui.partner.PartnerScreen
import com.example.myexodus.ui.partner.PartnerSelection
import com.example.myexodus.ui.partner.UnpairOutcome
import com.example.myexodus.ui.recovery.RecoveryPrefs
import com.example.myexodus.ui.recovery.RecoveryScreen
import com.example.myexodus.ui.rules.RulesScreen
import com.example.myexodus.ui.settings.NudgeSettingsViewModel
import com.example.myexodus.ui.settings.PANE_SHARING
import com.example.myexodus.ui.settings.SettingsScreen
import com.example.myexodus.ui.streaks.HabitPickerSheet
import com.example.myexodus.ui.streaks.StreaksScreen
import com.example.myexodus.ui.streaks.StreaksViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Tabs = listOf(
    NavTab(NavKeys.HOME, R.string.home_title, Icons.Outlined.HomeOutlined, Icons.Default.Home),
    NavTab(NavKeys.RULES, R.string.rules_title, Icons.Outlined.ShieldOutlined, Icons.Default.Shield),
    NavTab(
        NavKeys.RECOVERY,
        R.string.recovery_title,
        Icons.Outlined.LotusOutlined,
        Icons.Default.SelfImprovement,
    ),
    NavTab(
        NavKeys.STREAKS,
        R.string.streaks_title,
        Icons.Outlined.FlameOutlined,
        Icons.Default.LocalFireDepartment,
    ),
    // §7 item 12: the partner pillar's dashboard — one tab, not a sub-navigation.
    NavTab(NavKeys.PARTNER, R.string.partner_title, Icons.Outlined.HandshakeOutlined, Icons.Default.Handshake),
    NavTab(NavKeys.SETTINGS, R.string.settings_title, Icons.Outlined.SettingsOutlined, Icons.Default.Settings),
)

/** Content column cap for wide screens (Material: 840dp body max). */
private val ContentMaxWidth = 840.dp

@Composable
fun AppShell(
    store: AppRulesStore,
    repository: RulesRepository,
    usageAccountingEngine: UsageAccountingEngine,
    platformUsage: PlatformUsageReader,
    domainUsageEngine: DomainUsageEngine,
    psl: PublicSuffixList,
    /** §8.7/D1: the measured-interruption read model behind Home's outage banner and the
     *  Protection row's exemption offer. */
    accountingOutage: AccountingOutageSource,
    recoveryStore: RecoveryStateStore,
    attempts: AttemptRecorder,
    monitor: EnhancedModeHealthMonitor,
    strictMode: StrictModeManager,
    settings: EnhancedModeSettings,
    crashReporting: CrashReporting,
    widgetSettings: WidgetSettings,
    onWidgetSettingsChanged: () -> Unit,
    degradation: ProtectionDegradation?,
    authority: ProtectionAuthority? = null,
    onReenable: () -> Unit,
    streaks: StreaksViewModel,
    /** SOS §8.15 (Step 8): Home's Reach out sender state machine. */
    sos: SosViewModel,
    /** SOS §8.16 (Step 9): the recipient inbox — listener follows the Home surface. */
    nudgeInbox: NudgeInboxViewModel,
    /** SOS §8.19 (Step 11): the recipient block switch behind the Settings row. */
    nudgeSettings: NudgeSettingsViewModel,
    partnerSource: PartnerDashboardSource,
    /** §19.4 item 4: the production ceremony's seams; §19.5: the persisted user pick. */
    partnerSelection: PartnerSelection,
    ceremonyDeps: PairingCeremonyDeps,
    partnerNotificationsEnabled: Boolean,
    partnerNotificationsRequestable: Boolean,
    onFixNotifications: () -> Unit,
    onPartnerCheckNow: suspend () -> Boolean,
    onRespondToApproval: suspend (requestEventId: String, approve: Boolean) -> Boolean = { _, _ -> false },
    onUnpair: suspend (pairId: String) -> UnpairOutcome = { UnpairOutcome.FAILED },
    onDismissUnpair: suspend (pairId: String) -> Unit = {},
    onRenameUser: suspend (pairId: String, label: String?) -> Unit = { _, _ -> },
    /** Req 15: journals a motivational message for the selected partner (#96) and kicks a
     *  sync pass; the outcome is what the composer reports back. */
    onSendMessage: suspend (pairId: String, text: String) -> MessageSendOutcome =
        { _, _ -> MessageSendOutcome.FAILED },
    encryptionPending: Boolean = false,
    requestedTab: String? = null,
    onTabConsumed: () -> Unit = {},
    requestedHabit: String? = null,
    onHabitConsumed: () -> Unit = {},
    planRepository: RecoveryPlanRepository,
    /** Owner mandate 2026-10-04: the Settings sharing pane's per-streak consent writes. */
    habitRepository: HabitRepository,
    sessions: RecoverySessionRepository,
    prefs: RecoveryPrefs,
    recoveryEntry: String? = null,
    onRecoveryEntryConsumed: () -> Unit = {},
    onRouteHome: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // A saved index can outlive the tab list (restored state after a tab is removed), and every
    // tab below is enumerated — the clamp keeps that exhaustive `when` from needing a fallback.
    val contentTab = tab.coerceIn(0, Tabs.lastIndex)
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var openRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    // Owner ask 2026-10-04: cross-tab pane entry — the Partner hub's "nothing shared" card
    // lands on Settings with the sharing pane already open (consumed by SettingsScreen).
    var settingsPane by rememberSaveable { mutableStateOf<String?>(null) }
    val expanded = rememberWindowWidth() == WindowWidth.EXPANDED

    // System back at a tab's root returns to the start destination before exiting (Android's
    // bottom-nav convention). Every spoke screen registers its own BackHandler later in
    // composition, so while a spoke is open this one is shadowed and back pops the spoke first.
    BackHandler(enabled = contentTab != 0) { tab = 0 }

    // Plan §8 Step 5: the streak state is collected once here and consumed read-only by the
    // Home hero, the splash cards, and the Streaks tab — never re-derived per screen.
    val habit by streaks.state.collectAsStateWithLifecycle()
    val habits by streaks.habits.collectAsStateWithLifecycle()
    val streakResolved by streaks.resolved.collectAsStateWithLifecycle()
    val streakLoadFailed by streaks.loadFailed.collectAsStateWithLifecycle()
    val addingHabit by streaks.addingHabit.collectAsStateWithLifecycle()
    val setupError by streaks.setupError.collectAsStateWithLifecycle()
    val relapseInFlight by streaks.relapseInFlight.collectAsStateWithLifecycle()
    val relapseNote by streaks.relapseNote.collectAsStateWithLifecycle()

    // SOS §8.15 (Step 8): the Reach out control's client flag and sender state.
    val sosEnabled by sos.enabled.collectAsStateWithLifecycle()
    val sosState by sos.state.collectAsStateWithLifecycle()

    // FAB rev (owner mandate 2026-10-04): one roster read per Home entry feeds the pinned
    // card's sender names and the FAB's FR-1 active-pair gate. Pairs only change on the
    // Partner tab, so each Home entry is the freshness boundary.
    var partnerNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Owner mandate 2026-10-04 (Q4): who the Reach-out will actually go to — the §19.5 target
    // the sender resolves at tap time, read in the same Home-entry pass as the roster above.
    var sosTargetName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(contentTab) {
        if (contentTab == 0) {
            partnerNames = withContext(Dispatchers.IO) {
                runCatching { partnerSource.partnerDisplayNames() }.getOrDefault(emptyMap())
            }
            sosTargetName = withContext(Dispatchers.IO) {
                runCatching { partnerSource.targetPartnerName(partnerSelection.selectedPairId) }.getOrNull()
            }
        }
    }
    var sosConfirmOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(sosState) {
        val message = sosStateMessage(context, sosState) ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
    }

    // SOS §8.16 (Step 9): "attach the listener during Home lifecycle" — the §6.9 query runs
    // only while Home is the visible tab.
    val nudgeCard by nudgeInbox.card.collectAsStateWithLifecycle()
    LaunchedEffect(contentTab) {
        if (contentTab == 0) nudgeInbox.start() else nudgeInbox.stop()
    }

    // SOS §8.19 (Step 11): the block switch's state — null hides the row (no live partner).
    val partnerBlock by nudgeSettings.state.collectAsStateWithLifecycle()

    // Interception Escape Hatch lands here: the intent extra carries a NavKeys constant.
    LaunchedEffect(requestedTab) {
        val index = Tabs.indexOfFirst { it.key.equals(requestedTab, ignoreCase = true) }
        if (index >= 0) {
            tab = index
            onTabConsumed()
        }
    }

    // Milestone notification deep link: select the celebrated habit BEFORE its splash card
    // renders, so the card (and the hero) belong to the habit the notification named.
    LaunchedEffect(requestedHabit) {
        val habitId = requestedHabit ?: return@LaunchedEffect
        streaks.selectHabit(habitId)
        onHabitConsumed()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // SOS §8.15 rev (owner mandate 2026-10-04): Reach out as a FAB, gated like the old
        // card — flag on (FR-1), a live pair, Home visible.
        floatingActionButton = {
            if (contentTab == 0 && sosEnabled && partnerNames.isNotEmpty()) {
                ReachOutFab(state = sosState, onClick = { sosConfirmOpen = true })
            }
        },
        bottomBar = {
            if (!expanded) {
                NotchedNavBar(
                    tabs = Tabs,
                    selectedIndex = contentTab,
                    onSelect = { tab = it },
                )
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (expanded) {
                NavigationRail {
                    Tabs.forEachIndexed { index, navTab ->
                        NavigationRailItem(
                            selected = contentTab == index,
                            onClick = { tab = index },
                            icon = {
                                Icon(
                                    imageVector = if (contentTab == index) navTab.selectedIcon else navTab.icon,
                                    contentDescription = stringResource(navTab.labelRes),
                                )
                            },
                            label = {
                                Text(stringResource(navTab.labelRes), maxLines = 1, softWrap = false)
                            },
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val contentModifier = Modifier.widthIn(max = ContentMaxWidth)
                when {
                    contentTab == 0 -> HomeScreen(
                        store = store,
                        monitor = monitor,
                        degradation = degradation,
                        authority = authority,
                        outageSource = accountingOutage,
                        onReenable = onReenable,
                        // D1's Home banner fix: same landing as the outage notification and
                        // the APP_STOPPED precedent — the Settings tab, whose Protection row
                        // carries the attention tone; the battery row holds the offer itself.
                        onFixOutage = { tab = Tabs.indexOfFirst { it.key == NavKeys.SETTINGS } },
                        // §8 Step 6: the degraded banner's fix path is the Settings tab,
                        // where each mechanism's row names its own fix.
                        onOpenSettings = { tab = Tabs.indexOfFirst { it.key == NavKeys.SETTINGS } },
                        onOpenStreaks = { tab = 3 },
                        onOpenRules = { tab = 1 },
                        // Finding #19: a Home block row opens that rule's editor directly.
                        onOpenRule = { ruleId -> openRuleId = ruleId; tab = 1 },
                        habit = habit,
                        onAcknowledgeMilestone = streaks::acknowledgeMilestones,
                        onSwitchHabit = { pickerOpen = true },
                        streakResolved = streakResolved,
                        onRetryStreak = streaks::refresh,
                        streakLoadFailed = streakLoadFailed,
                        encryptionPending = encryptionPending,
                        // #12: the pinned card's sender line — the same hoisted roster read
                        // that gates the Reach out FAB.
                        partnerNames = partnerNames,
                        // SOS §8.14: the check-ins-off banner shares the Partner tab's
                        // notification state and fix path.
                        partnerNotificationsEnabled = partnerNotificationsEnabled,
                        partnerNotificationsRequestable = partnerNotificationsRequestable,
                        onFixPartnerNotifications = onFixNotifications,
                        // SOS §8.16: the recipient card + its acknowledge path.
                        nudgeCard = nudgeCard,
                        onNudgeAcknowledge = nudgeInbox::acknowledge,
                        modifier = contentModifier,
                    )
                    contentTab == 1 -> RulesScreen(
                        store = store,
                        repository = repository,
                        usageAccountingEngine = usageAccountingEngine,
                        platformUsage = platformUsage,
                        domainUsageEngine = domainUsageEngine,
                        psl = psl,
                        openRuleId = openRuleId,
                        onOpenRuleConsumed = { openRuleId = null },
                        modifier = contentModifier,
                    )
                    contentTab == 2 -> RecoveryScreen(
                        repository = repository,
                        recoveryStore = recoveryStore,
                        attempts = attempts,
                        planRepository = planRepository,
                        sessions = sessions,
                        prefs = prefs,
                        entryHint = recoveryEntry,
                        onEntryConsumed = onRecoveryEntryConsumed,
                        onRouteHome = onRouteHome,
                        modifier = contentModifier,
                    )
                    contentTab == 3 -> StreaksScreen(
                        state = habit,
                        addingHabit = addingHabit,
                        setupError = setupError,
                        relapseInFlight = relapseInFlight,
                        relapseNote = relapseNote,
                        onOnboard = streaks::onboard,
                        onOpenHabitPicker = { pickerOpen = true },
                        onCancelAddHabit = streaks::cancelAddHabit,
                        onRelapse = streaks::logRelapse,
                        modifier = contentModifier,
                    )
                    contentTab == 4 -> PartnerScreen(
                        source = partnerSource,
                        habitRepository = habitRepository,
                        selection = partnerSelection,
                        ceremony = ceremonyDeps,
                        notificationsEnabled = partnerNotificationsEnabled,
                        notificationsRequestable = partnerNotificationsRequestable,
                        onFixNotifications = onFixNotifications,
                        onCheckNow = onPartnerCheckNow,
                        onRespondToApproval = onRespondToApproval,
                        onUnpair = onUnpair,
                        onDismissUnpair = onDismissUnpair,
                        onRenameUser = onRenameUser,
                        onSendMessage = onSendMessage,
                        onOpenSharing = {
                            settingsPane = PANE_SHARING
                            tab = Tabs.indexOfFirst { it.key == NavKeys.SETTINGS }
                        },
                        modifier = contentModifier,
                    )
                    contentTab == 5 -> SettingsScreen(
                        monitor = monitor,
                        strictMode = strictMode,
                        settings = settings,
                        crashReporting = crashReporting,
                        widgetSettings = widgetSettings,
                        onWidgetSettingsChanged = onWidgetSettingsChanged,
                        planRepository = planRepository,
                        rulesRepository = repository,
                        habitRepository = habitRepository,
                        loadPartnerNames = { partnerSource.partnerDisplayNames() },
                        recoveryPrefs = prefs,
                        domainUsageEngine = domainUsageEngine,
                        accountingOutageSource = accountingOutage,
                        // SOS §8.19 (Step 11): the block switch's state + refresh/write paths.
                        partnerBlock = partnerBlock,
                        onPartnerBlockRefresh = nudgeSettings::refresh,
                        onPartnerBlockChange = nudgeSettings::setBlocked,
                        openPane = settingsPane,
                        onOpenPaneConsumed = { settingsPane = null },
                        modifier = contentModifier,
                    )
                }
            }
        }
    }

    // FAB rev (owner mandate 2026-10-04): explicit confirmation before a check-in goes out —
    // this supersedes FR-2's "no confirmation dialog": one extra tap beats an accidental send.
    if (sosConfirmOpen) {
        SosConfirmDialog(
            targetName = sosTargetName,
            onSend = { msg -> sosConfirmOpen = false; sos.onTap(msg) },
            onDismiss = { sosConfirmOpen = false },
        )
    }

    // One shared habit picker for both entry points (Home hero + Streaks summary card);
    // "Add habit" from Home lands on the Streaks tab where the form renders.
    if (pickerOpen) {
        HabitPickerSheet(
            habits = habits,
            onSelect = {
                streaks.selectHabit(it)
                pickerOpen = false
            },
            onAddHabit = {
                streaks.beginAddHabit()
                tab = 3
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * FAB rev (owner mandate 2026-10-04, Q4): the reach-out confirmation. Names the partner the
 * send will reach (§19.5 target; falls back to the generic title when no name resolves), and
 * carries the message — prefilled with the default, fully editable, capped at the envelope's
 * char bound; a cleared field sends the v1 no-text nudge. Extracted from AppShell so the
 * §12.3 device leg can render it in isolation (NudgeDialogInstrumentedTest).
 */
@Composable
internal fun SosConfirmDialog(
    targetName: String?,
    onSend: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultMessage = stringResource(R.string.sos_default_message)
    var message by remember { mutableStateOf(defaultMessage) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                targetName?.let { stringResource(R.string.sos_confirm_title_named, it) }
                    ?: stringResource(R.string.sos_confirm_title),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sos_reach_out_body))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(NudgeEnvelope.MAX_TEXT_CHARS) },
                    label = { Text(stringResource(R.string.sos_message_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.sos_message_counter,
                                message.length,
                                NudgeEnvelope.MAX_TEXT_CHARS,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(message.trim().takeIf { it.isNotEmpty() }) }) {
                Text(stringResource(R.string.sos_confirm_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sos_confirm_cancel))
            }
        },
    )
}

/**
 * SOS §8.15 rev (owner mandate 2026-10-04): the Reach out control is a FAB, not a Home card —
 * one tap raises the confirmation dialog; the sender state machine behind it is unchanged.
 * Dimmed and inert while a result is on screen (the old card's disabled-button rule); a
 * spinner replaces the icon mid-send.
 */
@Composable
private fun ReachOutFab(state: SosViewModel.SosSendState, onClick: () -> Unit) {
    val actionable = state == SosViewModel.SosSendState.Idle ||
        state == SosViewModel.SosSendState.CouldNotConfirm
    val sending = state == SosViewModel.SosSendState.Sending
    ExtendedFloatingActionButton(
        onClick = { if (actionable) onClick() },
        icon = {
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.WavingHand, contentDescription = null)
            }
        },
        text = { Text(stringResource(R.string.sos_reach_out)) },
        modifier = Modifier.alpha(if (actionable || sending) 1f else 0.6f),
    )
}

/** The sender state machine's user-visible results (plan §8.15 copy); null while Idle/Sending. */
private fun sosStateMessage(context: Context, state: SosViewModel.SosSendState): String? =
    when (state) {
        SosViewModel.SosSendState.Sent -> context.getString(R.string.sos_check_in_sent)
        SosViewModel.SosSendState.NotSentOffline -> context.getString(R.string.sos_state_offline)
        SosViewModel.SosSendState.CouldNotConfirm -> context.getString(R.string.sos_state_could_not_confirm)
        SosViewModel.SosSendState.Unavailable -> context.getString(R.string.sos_state_unavailable)
        is SosViewModel.SosSendState.RateLimited -> when (state.reason) {
            NudgeRepository.RateLimitReason.COOLDOWN -> context.getString(
                R.string.sos_rate_limited_cooldown,
                SosViewModel.cooldownMinutes(state.retryAfterSeconds),
            )
            NudgeRepository.RateLimitReason.DAILY_CAP -> context.getString(R.string.sos_rate_limited_daily_cap)
            NudgeRepository.RateLimitReason.UNKNOWN -> context.getString(R.string.sos_rate_limited_unknown)
        }
        else -> null
    }
