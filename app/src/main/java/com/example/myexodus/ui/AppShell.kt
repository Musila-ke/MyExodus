package com.example.myexodus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.myexodus.accountability.plan.PartnerPlanRepository
import com.example.myexodus.accountability.sos.NudgeEnvelope
import com.example.myexodus.accountability.sos.NudgeRepository
import com.example.myexodus.accountability.sync.MessageSendOutcome
import com.example.myexodus.accountability.monitoring.PartnerStatus
import com.example.myexodus.accountability.sync.PartnerAlertHealth
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.domainusage.DomainUsageEngine
import com.example.myexodus.domainusage.PublicSuffixList
import com.example.myexodus.recovery.RecoveryPlanRepository
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.HomeSettings
import com.example.myexodus.settings.StrictModeManager
import com.example.myexodus.settings.ThemeMode
import com.example.myexodus.settings.WidgetSettings
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.ui.home.HomeScreen
import com.example.myexodus.ui.home.NudgeInboxViewModel
import com.example.myexodus.ui.home.SosViewModel
import com.example.myexodus.ui.partner.PairingCeremonyDeps
import com.example.myexodus.ui.partner.PartnerDashboardSource
import com.example.myexodus.ui.partner.ReachOutTarget
import com.example.myexodus.ui.partner.PartnerScreen
import com.example.myexodus.ui.partner.RuleSharingItem
import com.example.myexodus.ui.partner.PartnerSelection
import com.example.myexodus.ui.partner.UnpairOutcome
import com.example.myexodus.ui.recovery.RECOVERY_VIEW_ASSIST
import com.example.myexodus.ui.recovery.RECOVERY_VIEW_CRISIS
import com.example.myexodus.ui.recovery.RecoveryPrefs
import com.example.myexodus.ui.recovery.RecoveryScreen
import com.example.myexodus.ui.recovery.UrgeLogSheet
import com.example.myexodus.ui.rules.RulesScreen
import com.example.myexodus.ui.settings.AccountViewModel
import com.example.myexodus.ui.settings.BackupKeyViewModel
import com.example.myexodus.ui.settings.NudgeSettingsViewModel
import com.example.myexodus.ui.settings.SettingsScreen
import com.example.myexodus.ui.settings.UpdateAvailablePrompt
import com.example.myexodus.ui.settings.UpdateNoticeDialog
import com.example.myexodus.ui.settings.UpdateViewModel
import com.example.myexodus.ui.insights.InsightsViewModel
import com.example.myexodus.ui.streaks.HabitPickerSheet
import com.example.myexodus.ui.streaks.StreaksScreen
import com.example.myexodus.ui.streaks.StreaksViewModel
import com.example.myexodus.ui.streaks.daysFree
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    /** Owner request 2026-10-17: Home's "Your blocks" privacy toggle state. */
    homeSettings: HomeSettings,
    widgetSettings: WidgetSettings,
    onWidgetSettingsChanged: () -> Unit,
    /** Settings' appearance row; the live mode the activity's theme root is painting. */
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    degradation: ProtectionDegradation?,
    authority: ProtectionAuthority? = null,
    onReenable: () -> Unit,
    streaks: StreaksViewModel,
    /** Owner task #22: the Insights spoke's on-demand read model. */
    insights: InsightsViewModel,
    /** SOS §8.15 (Step 8): Home's Reach out sender state machine. */
    sos: SosViewModel,
    /** SOS §8.16 (Step 9): the recipient inbox — listener follows the Home surface. */
    nudgeInbox: NudgeInboxViewModel,
    /** SOS §8.19 (Step 11): the recipient block switch behind the Settings row. */
    nudgeSettings: NudgeSettingsViewModel,
    /** Account plan §8.3: the optional-account pane. */
    account: AccountViewModel,
    /** Account plan §7 Release 2 step 12: the ABK key-bundle pane. */
    backupKey: BackupKeyViewModel,
    /** ADR #171: the update row and the one-time post-update notice. */
    update: UpdateViewModel,
    partnerSource: PartnerDashboardSource,
    /** Plan Step 8 (§8.10): the partner's server-derived connection reading, coarse buckets
     *  only. The client bounds callable traffic; null = no honest answer right now. */
    onLoadPartnerStatus: suspend (pairId: String) -> PartnerStatus? = { null },
    /** §19.4 item 4: the production ceremony's seams; §19.5: the persisted user pick. */
    partnerSelection: PartnerSelection,
    ceremonyDeps: PairingCeremonyDeps,
    partnerNotificationsEnabled: Boolean,
    partnerNotificationsRequestable: Boolean,
    /** Plan Step 6: permission + channel + push-provider health, in one actionable reading. */
    partnerAlertHealth: PartnerAlertHealth.State,
    onFixNotifications: () -> Unit,
    onPartnerCheckNow: suspend () -> Boolean,
    /** ADR #294: a share change kicks the silent delivery pass — warm sends in seconds,
     *  cold defers; this path never raises the device-key sheet. */
    onPartnerDeliverSilently: suspend () -> Unit = {},
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
    /** ADR #193: the plan a notification named — consumed by the Partner tab's Plans surface. */
    requestedPlan: String? = null,
    onPlanConsumed: () -> Unit = {},
    /** Owner report 2026-10-31: the exact partner a notification named — the Partner tab
     *  selects it before painting, so the tap never shows a different roster entry. */
    requestedPartner: String? = null,
    onPartnerConsumed: () -> Unit = {},
    /** Owner ask 2026-11-02: the subpage a partner notification concerns (activity, rules,
     *  requests) — the tap lands on it instead of the hub. */
    requestedPartnerView: String? = null,
    onPartnerViewConsumed: () -> Unit = {},
    /** Phase 4: a deep link (block screen, widget) opens this Recovery spoke on arrival. */
    requestedRecoveryView: String? = null,
    onRecoveryViewConsumed: () -> Unit = {},
    planRepository: RecoveryPlanRepository,
    /** Settings → Coaching plan: the editor resolves the active profile through this. */
    rulesRepository: RulesRepository,
    /** Owner mandate 2026-10-04: the Settings sharing pane's per-streak consent writes. */
    habitRepository: HabitRepository,
    /** ADR #193: partner plans, reached from the Partner hub. */
    partnerPlanRepository: PartnerPlanRepository,
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
    // Phase 4: the Recovery spoke to open next — set by the block screen, the widget, or the
    // Home action dial, consumed by the Recovery tab's own back stack.
    var recoveryView by rememberSaveable { mutableStateOf<String?>(null) }
    // The FAB is an in-app Home entry; the row it lands must say so. Cleared once the
    // Recovery tab has captured it, same contract as the intent-supplied entry.
    var fabEntry by rememberSaveable { mutableStateOf<String?>(null) }
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
    val onboardingInFlight by streaks.onboardingInFlight.collectAsStateWithLifecycle()
    val relapseInFlight by streaks.relapseInFlight.collectAsStateWithLifecycle()
    val relapseFeedback by streaks.relapseFeedback.collectAsStateWithLifecycle()
    val manageFeedback by streaks.manageFeedback.collectAsStateWithLifecycle()
    val manageInFlight by streaks.manageInFlight.collectAsStateWithLifecycle()
    val pairSharing by streaks.pairSharing.collectAsStateWithLifecycle()
    val insightsState by insights.state.collectAsStateWithLifecycle()

    // SOS §8.15 (Step 8): the Reach out control's sender state.
    val sosState by sos.state.collectAsStateWithLifecycle()

    // FAB rev (owner mandate 2026-10-04): one roster read per Home entry feeds the pinned
    // card's sender names and the FAB's FR-1 active-pair gate. Pairs only change on the
    // Partner tab, so each Home entry is the freshness boundary.
    var partnerNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Owner mandate 2026-10-04 (Q4) + owner ruling 2026-10-23: the Reach-out recipient list —
    // the §19.5 target first (the pre-picker default), every other live partner after it. The
    // dialog picks exactly one; nothing fans out.
    var sosTargets by remember { mutableStateOf<List<ReachOutTarget>>(emptyList()) }
    LaunchedEffect(contentTab) {
        // Streaks reads the same roster (owner ruling 2026-11-09) for its setup form's
        // "Share with" choice; Home's reach-out dialog uses it too.
        if (contentTab == 0 || contentTab == 3) {
            partnerNames = withContext(Dispatchers.IO) {
                runCatching { partnerSource.partnerDisplayNames() }.getOrDefault(emptyMap())
            }
            sosTargets = withContext(Dispatchers.IO) {
                runCatching { partnerSource.reachOutTargets(partnerSelection.selectedPairId) }
                    .getOrDefault(emptyList())
            }
        }
    }
    var sosConfirmOpen by remember { mutableStateOf(false) }
    // Owner directive 2026-10-21: one expandable action button on Home replaces the single
    // Reach out FAB — reach out, log a relapse, and log an urge behind one quiet control
    // instead of a row of FABs.
    var actionsOpen by rememberSaveable { mutableStateOf(false) }
    var relapseConfirmOpen by remember { mutableStateOf(false) }
    // Owner directive 2026-11-07: the log shortcuts always show. With no habit neither can
    // write anything, so each explains what is missing and offers habit setup instead of
    // opening a confirmation/sheet whose write has nothing to attach to. The urge path was
    // the one the owner actually hit: it used to open its sheet and save with no habit.
    var noHabitPrompt by remember { mutableStateOf<NoHabitPrompt?>(null) }
    // Same principle for the third shortcut (owner directive 2026-11-07): Reach out is
    // always present; with no live pair it explains that and routes to the Partner tab
    // instead of silently disappearing from the dial.
    var reachOutNoPartnerOpen by remember { mutableStateOf(false) }
    var urgeSheetOpen by rememberSaveable { mutableStateOf(false) }
    // Plan §E2.12: the post-lapse support sheet. "Shown" is per logged relapse: the feedback
    // resets to null at the start of every logRelapse, so a second lapse re-arms the sheet,
    // while a rotation (or any recomposition) does not re-show one already dismissed.
    var lapseSheetOpen by rememberSaveable { mutableStateOf(false) }
    var lapseSheetShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(relapseFeedback) {
        when (relapseFeedback) {
            is StreaksViewModel.RelapseFeedback.Support -> if (!lapseSheetShown) {
                lapseSheetShown = true
                lapseSheetOpen = true
            }
            null -> lapseSheetShown = false
            else -> Unit
        }
    }
    // The Home shortcut writes the row itself — a quick action that only switched tabs left
    // users believing an urge was logged when the sheet had never been opened (owner report
    // 2026-10-22). The sheet and the write path are the Recovery screen's, not a copy.
    val homeScope = rememberCoroutineScope()
    BackHandler(enabled = actionsOpen) { actionsOpen = false }
    LaunchedEffect(contentTab) { if (contentTab != 0) actionsOpen = false }
    val snackbarHostState = remember { SnackbarHostState() }
    val sosMessage = sosStateMessage(sosState)
    LaunchedEffect(sosState) {
        sosMessage?.let { snackbarHostState.showSnackbar(it) }
    }
    // §4.7 App Standby explanation (plan §7 Step 7): at most one non-intrusive notice per
    // constrained transition — the client enforces that. It names where to look and never
    // claims a setting is required or that Unrestricted guarantees delivery.
    val standbyNotice = stringResource(R.string.partner_standby_notice)
    LaunchedEffect(Unit) {
        PartnerSyncSignals.standbyNotice.collect {
            snackbarHostState.showSnackbar(standbyNotice)
        }
    }
    // ADR #294 (reverses #292 item 2): a share change asks for the delivery pass but never
    // for authentication — the share is the action, delivery is its silent side effect.
    LaunchedEffect(Unit) {
        streaks.sharingDeliveryRequested.collect { onPartnerDeliverSilently() }
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

    LaunchedEffect(requestedRecoveryView) {
        val requested = requestedRecoveryView ?: return@LaunchedEffect
        recoveryView = requested
        onRecoveryViewConsumed()
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
        // SOS §8.15 rev (owner mandate 2026-10-04): Reach out as a FAB. Owner mandate
        // 2026-10-15: always present — the partner_sos_enabled Remote Config gate is removed
        // (the server-side kill switch is the control); a live pair and Home visibility stay.
        floatingActionButton = {
            if (contentTab == 0) {
                HomeActionsFab(
                    expanded = actionsOpen,
                    onToggle = { actionsOpen = !actionsOpen },
                    // Owner add-on 2026-11-07: the in-the-moment flow is one tap from Home. The
                    // request survives the tab switch because the Recovery tab consumes it on
                    // its next composition.
                    onUrgeAssist = {
                        actionsOpen = false
                        fabEntry = RecoverySession.ENTRY_HOME
                        recoveryView = RECOVERY_VIEW_ASSIST
                        tab = Tabs.indexOfFirst { it.key == NavKeys.RECOVERY }
                    },
                    onReachOut = {
                        actionsOpen = false
                        if (partnerNames.isNotEmpty()) {
                            sosConfirmOpen = true
                        } else {
                            reachOutNoPartnerOpen = true
                        }
                    },
                    onLogRelapse = {
                        actionsOpen = false
                        if (habit != null) relapseConfirmOpen = true else noHabitPrompt = NoHabitPrompt.RELAPSE
                    },
                    onLogUrge = {
                        actionsOpen = false
                        if (habit != null) urgeSheetOpen = true else noHabitPrompt = NoHabitPrompt.URGE
                    },
                )
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
                        homeSettings = homeSettings,
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
                        // Per-rule partner assignment (plan §2.1 req 1): the rule editor's
                        // assignee list is the live pair roster — the same read the SOS
                        // recipient dialog uses.
                        partnerSource = partnerSource,
                        openRuleId = openRuleId,
                        onOpenRuleConsumed = { openRuleId = null },
                        modifier = contentModifier,
                    )
                    contentTab == 2 -> RecoveryScreen(
                        repository = repository,
                        recoveryStore = recoveryStore,
                        planRepository = planRepository,
                        sessions = sessions,
                        prefs = prefs,
                        entryHint = recoveryEntry ?: fabEntry,
                        onEntryConsumed = {
                            fabEntry = null
                            onRecoveryEntryConsumed()
                        },
                        onRouteHome = onRouteHome,
                        modifier = contentModifier,
                        habitId = habit?.habitId,
                        habitLabel = habit?.targetLabel,
                        partnerName = partnerNames.values.firstOrNull(),
                        // The same confirmation the Home FAB and the widget use: one recipient,
                        // one editable message, nothing sends without that dialog.
                        onReachOut = {
                            if (sosTargets.isNotEmpty()) sosConfirmOpen = true
                            else reachOutNoPartnerOpen = true
                        },
                        partnerMessageSent = sosState is SosViewModel.SosSendState.Sent,
                        partnerMessageStatus = sosMessage,
                        requestedView = recoveryView,
                        onViewConsumed = { recoveryView = null },
                    )
                    contentTab == 3 -> StreaksScreen(
                        state = habit,
                        addingHabit = addingHabit,
                        setupError = setupError,
                        onboardingInFlight = onboardingInFlight,
                        relapseInFlight = relapseInFlight,
                        relapseFeedback = relapseFeedback,
                        manageFeedback = manageFeedback,
                        manageInFlight = manageInFlight,
                        insights = insightsState,
                        onRefreshInsights = insights::refresh,
                        sessions = sessions,
                        habits = habits,
                        onRenameHabit = streaks::renameHabit,
                        onDeleteHabit = streaks::deleteHabit,
                        onClearManageFeedback = streaks::clearManageFeedback,
                        onOnboard = streaks::onboard,
                        partners = sosTargets,
                        pairSharing = pairSharing,
                        onRefreshSharing = streaks::refreshPairSharing,
                        onSetHabitSharing = streaks::setHabitSharing,
                        onOpenHabitPicker = { pickerOpen = true },
                        onCancelAddHabit = streaks::cancelAddHabit,
                        onRelapse = streaks::logRelapse,
                        modifier = contentModifier,
                    )
                    contentTab == 4 -> PartnerScreen(
                        source = partnerSource,
                        habitRepository = habitRepository,
                        // Owner ruling 2026-11-09: assigning a partner in an editor is the
                        // share. This reads the rule store itself, so the item is listed in
                        // What you share with no separate grant step.
                        loadRuleSharing = {
                            runCatching {
                                repository.getActiveRules()
                                    .filter { it.assignedPairId != null }
                                    .groupBy { it.assignedPairId!! }
                                    .mapValues { (_, assigned) ->
                                        assigned.filter { it.batchId == null }
                                            .map { RuleSharingItem(it.ruleId, it.blockedPackage, batch = false) } +
                                            assigned.filter { it.batchId != null }
                                                .distinctBy { it.batchId }
                                                .map {
                                                    RuleSharingItem(
                                                        id = it.batchId!!,
                                                        label = it.batchName?.takeIf(String::isNotBlank)
                                                            ?: it.blockedPackage,
                                                        batch = true,
                                                    )
                                                }
                                    }
                            }.getOrDefault(emptyMap())
                        },
                        disconnectRule = { item ->
                            runCatching {
                                if (item.batch) {
                                    repository.removeBatchPartner(item.id)
                                } else {
                                    repository.unassignRule(item.id)
                                }
                                true
                            }.getOrDefault(false)
                        },
                        planRepository = partnerPlanRepository,
                        selection = partnerSelection,
                        ceremony = ceremonyDeps,
                        notificationsEnabled = partnerNotificationsEnabled,
                        notificationsRequestable = partnerNotificationsRequestable,
                        alertHealth = partnerAlertHealth,
                        onFixNotifications = onFixNotifications,
                        onCheckNow = onPartnerCheckNow,
                        onRespondToApproval = onRespondToApproval,
                        onUnpair = onUnpair,
                        onDismissUnpair = onDismissUnpair,
                        onRenameUser = onRenameUser,
                        onSendMessage = onSendMessage,
                        onLoadPartnerStatus = onLoadPartnerStatus,
                        requestedPlanId = requestedPlan,
                        onPlanConsumed = onPlanConsumed,
                        requestedPairId = requestedPartner,
                        onPairConsumed = onPartnerConsumed,
                        requestedViewId = requestedPartnerView,
                        onViewConsumed = onPartnerViewConsumed,
                        modifier = contentModifier,
                    )
                    contentTab == 5 -> SettingsScreen(
                        monitor = monitor,
                        strictMode = strictMode,
                        settings = settings,
                        crashReporting = crashReporting,
                        widgetSettings = widgetSettings,
                        onWidgetSettingsChanged = onWidgetSettingsChanged,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        accountingOutageSource = accountingOutage,
                        account = account,
                        backupKey = backupKey,
                        update = update,
                        // §8 Step 6: the Protection pane and index state the same published
                        // degradation Home's banner does.
                        degradation = degradation,
                        onReenable = onReenable,
                        // SOS §8.19 (Step 11): the block switch's state + refresh/write paths.
                        partnerBlock = partnerBlock,
                        onPartnerBlockRefresh = nudgeSettings::refresh,
                        onPartnerBlockChange = nudgeSettings::setBlocked,
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
            targets = sosTargets,
            initialPairId = sosTargets.firstOrNull()?.pairId,
            onSend = { msg, pairId -> sosConfirmOpen = false; sos.onTap(msg, pairId) },
            onDismiss = { sosConfirmOpen = false },
        )
    }

    // Home's quick action: the same confirmation and copy the Streaks card uses (the widget's
    // hold action shares it too). Confirming records the relapse and lands on Streaks, where
    // the support line renders — the Home sheet never claims success on its own.
    if (relapseConfirmOpen) {
        val days = habit?.let { daysFree(it.currentStreakElapsedMs) } ?: 1
        AlertDialog(
            onDismissRequest = { relapseConfirmOpen = false },
            title = { Text(stringResource(R.string.streaks_relapse_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.streaks_relapse_confirm_body,
                        habit?.targetLabel ?: stringResource(R.string.streaks_title),
                        days,
                        stringResource(
                            if (days == 1) R.string.streaks_day_singular else R.string.streaks_day_plural,
                        ),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        relapseConfirmOpen = false
                        streaks.logRelapse()
                        tab = 3
                    },
                ) { Text(stringResource(R.string.streaks_relapse_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { relapseConfirmOpen = false }) {
                    Text(stringResource(R.string.streaks_cancel))
                }
            },
        )
    }

    // Plan §E2.12: support after a lapse, never a lecture. The block and the streak reset are
    // already done; this sheet only offers the three next steps.
    if (lapseSheetOpen) {
        AlertDialog(
            onDismissRequest = { lapseSheetOpen = false },
            title = { Text(stringResource(R.string.recovery_lapse_title)) },
            text = { Text(stringResource(R.string.recovery_lapse_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        lapseSheetOpen = false
                        if (sosTargets.isNotEmpty()) sosConfirmOpen = true
                        else reachOutNoPartnerOpen = true
                    },
                ) { Text(stringResource(R.string.sos_reach_out)) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            lapseSheetOpen = false
                            tab = Tabs.indexOfFirst { it.key == NavKeys.RECOVERY }
                            recoveryView = RECOVERY_VIEW_CRISIS
                        },
                    ) { Text(stringResource(R.string.recovery_crisis_title)) }
                    TextButton(onClick = { lapseSheetOpen = false }) {
                        Text(stringResource(R.string.recovery_lapse_not_now))
                    }
                }
            },
        )
    }

    // A log shortcut with no habit: name what is missing and hand the user the way to start
    // one. The body says which write had nowhere to go; the exit is the same for both.
    noHabitPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { noHabitPrompt = null },
            title = { Text(stringResource(R.string.home_relapse_no_habit_title)) },
            text = {
                Text(
                    stringResource(
                        when (prompt) {
                            NoHabitPrompt.RELAPSE -> R.string.home_relapse_no_habit_body
                            NoHabitPrompt.URGE -> R.string.home_urge_no_habit_body
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        noHabitPrompt = null
                        streaks.beginAddHabit()
                        tab = 3
                    },
                ) { Text(stringResource(R.string.home_relapse_no_habit_action)) }
            },
            dismissButton = {
                TextButton(onClick = { noHabitPrompt = null }) {
                    Text(stringResource(R.string.streaks_cancel))
                }
            },
        )
    }

    // The third shortcut's prerequisite: no live pair means no recipient. Send the user to
    // the Partner tab, where an unpaired account shows the pairing entry.
    if (reachOutNoPartnerOpen) {
        AlertDialog(
            onDismissRequest = { reachOutNoPartnerOpen = false },
            title = { Text(stringResource(R.string.home_reach_out_no_partner_title)) },
            text = { Text(stringResource(R.string.home_reach_out_no_partner_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        reachOutNoPartnerOpen = false
                        tab = Tabs.indexOfFirst { it.key == NavKeys.PARTNER }
                    },
                ) { Text(stringResource(R.string.home_reach_out_no_partner_action)) }
            },
            dismissButton = {
                TextButton(onClick = { reachOutNoPartnerOpen = false }) {
                    Text(stringResource(R.string.streaks_cancel))
                }
            },
        )
    }

    // First launch after an in-place update: say so once. The notes come from the signed
    // manifest that was installed, when it is still available.
    UpdateNoticeDialog(update)

    // Plan revision 2026-11-06: one prompt per offered version, on app open only. Review
    // lands on the Settings update dialog so the download stays a deliberate choice.
    val updateState by update.state.collectAsStateWithLifecycle()
    UpdateAvailablePrompt(
        versionName = updateState.availablePrompt?.versionName,
        onReview = {
            update.dismissAvailablePrompt()
            tab = Tabs.indexOfFirst { it.key == NavKeys.SETTINGS }
            update.requestOpenDialog()
        },
        onDismiss = update::dismissAvailablePrompt,
    )

    // Home's urge quick action: the Recovery tab's sheet, saved through the same repository
    // path (terminal-born `none` row, never mirrored) and confirmed by a snackbar. It is
    // rule-less but habit-scoped, so the sheet names the streak it writes to.
    // Resolved in composition (stringResource), not through the context in the callback:
    // a context read is not configuration-aware and would keep a stale string across a
    // locale change (lint LocalContextGetResourceValueCall, ADR #208 gate).
    val urgeSavedMessage = stringResource(R.string.home_urge_saved)
    if (urgeSheetOpen) {
        UrgeLogSheet(
            onDismiss = { urgeSheetOpen = false },
            habitLabel = habit?.targetLabel,
            onSubmit = { intensity ->
                urgeSheetOpen = false
                homeScope.launch(Dispatchers.IO) {
                    sessions.logOnly(
                        intensity = intensity,
                        entry = RecoverySession.ENTRY_HOME,
                        zoneId = ZoneId.systemDefault().id,
                        nowEpochMs = System.currentTimeMillis(),
                        habitId = habit?.habitId,
                    )
                    snackbarHostState.showSnackbar(urgeSavedMessage)
                }
            },
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
 * send will reach (§19.5 target first; falls back to the generic title when no name resolves),
 * and carries the message — prefilled with the default, fully editable, capped at the
 * envelope's char bound; a cleared field sends the v1 no-text nudge.
 *
 * Owner ruling 2026-10-23 (per-subject routing): with more than one live partner the dialog
 * also asks who gets it — one explicit recipient per send, never a fan-out. The default is
 * the §19.5 target the single-partner flow always used, so that flow is unchanged. Extracted
 * from AppShell so the §12.3 device leg can render it in isolation (NudgeDialogInstrumentedTest).
 */
@Composable
internal fun SosConfirmDialog(
    targets: List<ReachOutTarget>,
    initialPairId: String?,
    onSend: (String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultMessage = stringResource(R.string.sos_default_message)
    var message by remember { mutableStateOf(defaultMessage) }
    var selectedPairId by remember(targets, initialPairId) {
        mutableStateOf(initialPairId ?: targets.firstOrNull()?.pairId)
    }
    val selectedName = targets.firstOrNull { it.pairId == selectedPairId }?.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                selectedName?.let { stringResource(R.string.sos_confirm_title_named, it) }
                    ?: stringResource(R.string.sos_confirm_title),
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.sos_reach_out_body))
                if (targets.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.sos_target_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Owner directive 2026-11-01: a roster of people, not a stack of chips —
                        // initials, one selected row, bounded height so many partners stay tidy.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            targets.forEach { target ->
                                RecipientRow(
                                    name = target.name,
                                    selected = target.pairId == selectedPairId,
                                    onClick = { selectedPairId = target.pairId },
                                )
                            }
                        }
                    }
                }
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
            TextButton(
                onClick = {
                    onSend(message.trim().takeIf { it.isNotEmpty() }, selectedPairId)
                },
            ) {
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

/** One recipient in the reach-out dialog's roster. */
@Composable
private fun RecipientRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor =
            if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RadioButton(selected = selected, onClick = null)
        }
    }
}

/** Which log shortcut needs habit guidance. The body copy differs; the way out is the same. */
private enum class NoHabitPrompt { RELAPSE, URGE }

/**
 * Owner directive 2026-10-21: one expandable action button replaces a row of FABs. Collapsed
 * it is a single accent control; expanded it reveals the three things a Home visit can
 * legitimately act on — reach out, log a relapse, and log an urge. Each shortcut opens its
 * own confirmation/flow; nothing sends from here.
 *
 * All three stay visible without their prerequisites (owner directive 2026-11-07): hiding an
 * action left the dial missing it with no explanation, and the urge shortcut used to write a
 * note with nothing to track. The caller routes each to the setup its missing prerequisite needs.
 */
@Composable
private fun HomeActionsFab(
    expanded: Boolean,
    onToggle: () -> Unit,
    onUrgeAssist: () -> Unit,
    onReachOut: () -> Unit,
    onLogRelapse: () -> Unit,
    onLogUrge: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickAction(
                    icon = { Icon(Icons.Filled.Bolt, contentDescription = null) },
                    label = stringResource(R.string.home_action_right_now),
                    onClick = onUrgeAssist,
                )
                QuickAction(
                    icon = { Icon(painterResource(R.drawable.ic_log_relapse), contentDescription = null) },
                    label = stringResource(R.string.streaks_relapse_action),
                    onClick = onLogRelapse,
                )
                QuickAction(
                    icon = { Icon(Icons.Filled.WavingHand, contentDescription = null) },
                    label = stringResource(R.string.sos_reach_out),
                    onClick = onReachOut,
                )
                QuickAction(
                    icon = { Icon(painterResource(R.drawable.ic_log_urge), contentDescription = null) },
                    label = stringResource(R.string.home_action_log_urge),
                    onClick = onLogUrge,
                )
            }
        }
        FloatingActionButton(onClick = onToggle) {
            Icon(
                imageVector = if (expanded) Icons.Filled.Close else Icons.Filled.Add,
                contentDescription = stringResource(
                    if (expanded) R.string.home_quick_actions_close else R.string.home_quick_actions_open,
                ),
            )
        }
    }
}

/** A shortcut in the Home action dial — a quiet tonal pill, not a second accent. */
@Composable
private fun QuickAction(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        // The label is drawn by ExtendedFAB's text slot but was absent from the exported
        // accessibility node on device (TalkBack announced an unnamed button); name it here
        // explicitly so the action is operable without sight.
        modifier = Modifier.semantics { contentDescription = label },
        icon = icon,
        text = { Text(label) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
}

/** The sender state machine's user-visible results (plan §8.15 copy); null while Idle/Sending.
 *  Internal since widget v2: the widget's hold-to-confirm surface renders the same states, and
 *  the copy has exactly one definition (DRY — §8.15 semantics must not fork per surface). */
@Composable
internal fun sosStateMessage(state: SosViewModel.SosSendState): String? =
    when (state) {
        SosViewModel.SosSendState.Sent -> stringResource(R.string.sos_check_in_sent)
        SosViewModel.SosSendState.NotSentOffline -> stringResource(R.string.sos_state_offline)
        SosViewModel.SosSendState.CouldNotConfirm -> stringResource(R.string.sos_state_could_not_confirm)
        SosViewModel.SosSendState.Unavailable -> stringResource(R.string.sos_state_unavailable)
        is SosViewModel.SosSendState.RateLimited -> when (state.reason) {
            NudgeRepository.RateLimitReason.COOLDOWN -> stringResource(
                R.string.sos_rate_limited_cooldown,
                SosViewModel.cooldownMinutes(state.retryAfterSeconds),
            )
            NudgeRepository.RateLimitReason.DAILY_CAP -> stringResource(R.string.sos_rate_limited_daily_cap)
            NudgeRepository.RateLimitReason.UNKNOWN -> stringResource(R.string.sos_rate_limited_unknown)
        }
        else -> null
    }
