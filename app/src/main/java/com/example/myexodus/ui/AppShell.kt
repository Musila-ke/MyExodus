package com.example.myexodus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Handshake as HandshakeOutlined
import androidx.compose.material.icons.outlined.Home as HomeOutlined
import androidx.compose.material.icons.outlined.LocalFireDepartment as FlameOutlined
import androidx.compose.material.icons.outlined.SelfImprovement as LotusOutlined
import androidx.compose.material.icons.outlined.Settings as SettingsOutlined
import androidx.compose.material.icons.outlined.Shield as ShieldOutlined
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.myexodus.accountability.sync.MessageSendOutcome
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.domainusage.DomainUsageEngine
import com.example.myexodus.domainusage.PublicSuffixList
import com.example.myexodus.recovery.RecoveryPlanRepository
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.StrictModeManager
import com.example.myexodus.settings.WidgetSettings
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.ui.home.HomeScreen
import com.example.myexodus.ui.partner.PairingCeremonyDeps
import com.example.myexodus.ui.partner.PartnerDashboardSource
import com.example.myexodus.ui.partner.PartnerScreen
import com.example.myexodus.ui.partner.PartnerSelection
import com.example.myexodus.ui.partner.UnpairOutcome
import com.example.myexodus.ui.recovery.RecoveryPrefs
import com.example.myexodus.ui.recovery.RecoveryScreen
import com.example.myexodus.ui.rules.RulesScreen
import com.example.myexodus.ui.settings.SettingsScreen
import com.example.myexodus.ui.streaks.HabitPickerSheet
import com.example.myexodus.ui.streaks.StreaksScreen
import com.example.myexodus.ui.streaks.StreaksViewModel

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
                        // #12: the pinned card's sender line — the same roster read the
                        // Partner tab folds in, kept to names only.
                        loadPartnerNames = partnerSource::partnerDisplayNames,
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
                        recoveryPrefs = prefs,
                        domainUsageEngine = domainUsageEngine,
                        accountingOutageSource = accountingOutage,
                        modifier = contentModifier,
                    )
                }
            }
        }
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
