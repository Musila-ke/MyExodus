package com.example.myexodus.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.myexodus.blocking.AttemptRecorder
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.ProtectionDegradation
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.StrictModeManager
import com.example.myexodus.ui.home.HomeScreen
import com.example.myexodus.ui.recovery.RecoveryScreen
import com.example.myexodus.ui.rules.RulesScreen
import com.example.myexodus.ui.settings.SettingsScreen

private val Tabs = listOf(
    "Home" to Icons.Default.Home,
    "Rules" to Icons.Default.Shield,
    "Recovery" to Icons.Default.SelfImprovement,
    "Streaks" to Icons.Default.LocalFireDepartment,
    "Settings" to Icons.Default.Settings,
)

/** Content column cap for wide screens (Material: 840dp body max). */
private val ContentMaxWidth = 840.dp

@Composable
fun AppShell(
    store: AppRulesStore?,
    repository: RulesRepository,
    recoveryStore: RecoveryStateStore,
    attempts: AttemptRecorder,
    monitor: EnhancedModeHealthMonitor,
    strictMode: StrictModeManager,
    settings: EnhancedModeSettings,
    degradation: ProtectionDegradation?,
    onReenable: () -> Unit,
    requestedTab: String? = null,
    onTabConsumed: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val expanded = rememberWindowWidth() == WindowWidth.EXPANDED

    // Interception Escape Hatch lands here: tab labels are the deep-link keys.
    LaunchedEffect(requestedTab) {
        val index = Tabs.indexOfFirst { it.first.equals(requestedTab, ignoreCase = true) }
        if (index >= 0) {
            tab = index
            onTabConsumed()
        }
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
                        onReenable = onReenable,
                        modifier = contentModifier,
                    )
                    tab == 1 -> RulesScreen(
                        store = store,
                        repository = repository,
                        modifier = contentModifier,
                    )
                    tab == 2 -> RecoveryScreen(
                        repository = repository,
                        recoveryStore = recoveryStore,
                        attempts = attempts,
                        modifier = contentModifier,
                    )
                    tab == 4 -> SettingsScreen(
                        monitor = monitor,
                        strictMode = strictMode,
                        settings = settings,
                        modifier = contentModifier,
                    )
                    else -> ComingSoonScreen(Tabs[tab].first, modifier = contentModifier)
                }
            }
        }
    }
}
