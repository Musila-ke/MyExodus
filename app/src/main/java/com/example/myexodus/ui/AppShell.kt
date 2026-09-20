package com.example.myexodus.ui

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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.blocking.AttemptRecorder
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.blocking.ProtectionDegradation
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.blocking.usage.UsageAccountingEngine
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.recovery.RecoveryPlanRepository
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.StrictModeManager
import com.example.myexodus.settings.WidgetSettings
import com.example.myexodus.telemetry.CrashReporting
import com.example.myexodus.ui.home.HomeScreen
import com.example.myexodus.ui.partner.PartnerDashboardSource
import com.example.myexodus.ui.partner.PartnerScreen
import com.example.myexodus.ui.partner.UnpairOutcome
import com.example.myexodus.ui.recovery.RecoveryPrefs
import com.example.myexodus.ui.recovery.RecoveryScreen
import com.example.myexodus.ui.rules.RulesScreen
import com.example.myexodus.ui.settings.SettingsScreen
import com.example.myexodus.ui.streaks.HabitPickerSheet
import com.example.myexodus.ui.streaks.StreaksScreen
import com.example.myexodus.ui.streaks.StreaksViewModel

private val Tabs = listOf(
    "Home" to Icons.Default.Home,
    "Rules" to Icons.Default.Shield,
    "Recovery" to Icons.Default.SelfImprovement,
    "Streaks" to Icons.Default.LocalFireDepartment,
    // §7 item 12: the partner pillar's dashboard — one tab, not a sub-navigation.
    "Partner" to Icons.Default.Handshake,
    "Settings" to Icons.Default.Settings,
)

/** Content column cap for wide screens (Material: 840dp body max). */
private val ContentMaxWidth = 840.dp

@Composable
fun AppShell(
    store: AppRulesStore?,
    repository: RulesRepository,
    usageAccountingEngine: UsageAccountingEngine,
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
    partnerNotificationsEnabled: Boolean,
    partnerNotificationsRequestable: Boolean,
    onFixNotifications: () -> Unit,
    onPartnerCheckNow: suspend () -> Boolean,
    onRespondToApproval: suspend (requestEventId: String, approve: Boolean) -> Boolean = { _, _ -> false },
    onUnpair: suspend (pairId: String) -> UnpairOutcome = { UnpairOutcome.FAILED },
    onDismissUnpair: suspend (pairId: String) -> Unit = {},
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
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var openRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    val expanded = rememberWindowWidth() == WindowWidth.EXPANDED

    // Plan §8 Step 5: the streak state is collected once here and consumed read-only by the
    // Home hero, the splash cards, and the Streaks tab — never re-derived per screen.
    val habit by streaks.state.collectAsStateWithLifecycle()
    val habits by streaks.habits.collectAsStateWithLifecycle()
    val streakResolved by streaks.resolved.collectAsStateWithLifecycle()
    val addingHabit by streaks.addingHabit.collectAsStateWithLifecycle()
    val setupError by streaks.setupError.collectAsStateWithLifecycle()
    val relapseInFlight by streaks.relapseInFlight.collectAsStateWithLifecycle()
    val relapseNote by streaks.relapseNote.collectAsStateWithLifecycle()

    // Interception Escape Hatch lands here: tab labels are the deep-link keys.
    LaunchedEffect(requestedTab) {
        val index = Tabs.indexOfFirst { it.first.equals(requestedTab, ignoreCase = true) }
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
                NavigationBar {
                    Tabs.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, maxLines = 1, softWrap = false) },
                            // Six destinations: labels on selection only (the platform's
                            // LabelVisibilityMode.AUTO behavior for 4+), else they crowd.
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (expanded) {
                NavigationRail {
                    Tabs.forEachIndexed { index, (label, icon) ->
                        NavigationRailItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, maxLines = 1, softWrap = false) },
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val contentModifier = Modifier.widthIn(max = ContentMaxWidth)
                when {
                    store == null -> ComingSoonScreen("MyExodus", modifier = contentModifier)
                    tab == 0 -> HomeScreen(
                        store = store,
                        monitor = monitor,
                        degradation = degradation,
                        authority = authority,
                        onReenable = onReenable,
                        // §8 Step 6: the degraded banner's fix path is the Settings tab,
                        // where each mechanism's row names its own fix.
                        onOpenSettings = { tab = Tabs.indexOfFirst { it.first == "Settings" } },
                        onOpenStreaks = { tab = 3 },
                        onOpenRules = { tab = 1 },
                        // Finding #19: a Home block row opens that rule's editor directly.
                        onOpenRule = { ruleId -> openRuleId = ruleId; tab = 1 },
                        habit = habit,
                        onAcknowledgeMilestone = streaks::acknowledgeMilestones,
                        onSwitchHabit = { pickerOpen = true },
                        streakResolved = streakResolved,
                        encryptionPending = encryptionPending,
                        modifier = contentModifier,
                    )
                    tab == 1 -> RulesScreen(
                        store = store,
                        repository = repository,
                        usageAccountingEngine = usageAccountingEngine,
                        openRuleId = openRuleId,
                        onOpenRuleConsumed = { openRuleId = null },
                        modifier = contentModifier,
                    )
                    tab == 2 -> RecoveryScreen(
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
                    tab == 3 -> StreaksScreen(
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
                    tab == 4 -> PartnerScreen(
                        source = partnerSource,
                        notificationsEnabled = partnerNotificationsEnabled,
                        notificationsRequestable = partnerNotificationsRequestable,
                        onFixNotifications = onFixNotifications,
                        onCheckNow = onPartnerCheckNow,
                        onRespondToApproval = onRespondToApproval,
                        onUnpair = onUnpair,
                        onDismissUnpair = onDismissUnpair,
                        modifier = contentModifier,
                    )
                    tab == 5 -> SettingsScreen(
                        monitor = monitor,
                        strictMode = strictMode,
                        settings = settings,
                        crashReporting = crashReporting,
                        widgetSettings = widgetSettings,
                        onWidgetSettingsChanged = onWidgetSettingsChanged,
                        planRepository = planRepository,
                        rulesRepository = repository,
                        recoveryPrefs = prefs,
                        modifier = contentModifier,
                    )
                    else -> ComingSoonScreen(Tabs[tab].first, modifier = contentModifier)
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
