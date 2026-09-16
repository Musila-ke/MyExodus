package com.example.myexodus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.EnforcementForegroundService
import com.example.myexodus.blocking.cancelDailyMaintenance
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.StrictModeManager
import kotlinx.coroutines.delay

/**
 * Enhanced Mode controls (plan §8 Steps 10-12): the app-side master switch gatekept by Strict
 * Mode's 24h cooldown, AAPM unsupported-config state, and the legitimate Restricted Settings
 * flow. No AAPM-disable instructions anywhere (Decision 7). Permission status lives in the
 * Settings protection rows — this section carries only the controls.
 */
@Composable
fun EnhancedModeScreen(
    monitor: EnhancedModeHealthMonitor,
    strictMode: StrictModeManager,
    settings: EnhancedModeSettings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val advancedProtection by monitor.advancedProtection.collectAsStateWithLifecycle()

    var enhancedOn by remember { mutableStateOf(settings.enhancedModeEnabled) }
    var strictOn by remember { mutableStateOf(strictMode.strictModeEnabled) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        monitor.refresh()
        while (true) {
            delay(60_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    if (advancedProtection) {
        UnsupportedConfigurationScreen(modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Enhanced Mode", style = MaterialTheme.typography.titleLarge)

        Surface(
            tonalElevation = 1.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Enhanced Mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Block restricted apps and websites when you open them.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enhancedOn,
                        enabled = strictMode.canDisable(),
                        onCheckedChange = { enabled ->
                            settings.enhancedModeEnabled = enabled
                            enhancedOn = enabled
                            // The master switch also drives the §8.1 foreground service.
                            if (enabled) {
                                EnforcementForegroundService.start(context)
                            } else {
                                EnforcementForegroundService.stop(context)
                                // §13 rollback: the maintenance job goes with the switch.
                                cancelDailyMaintenance(context)
                            }
                        },
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Strict Mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Lock in-app controls for 24 hours after enabling.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = strictOn,
                        enabled = strictMode.canDisable(),
                        onCheckedChange = { enabled ->
                            strictMode.strictModeEnabled = enabled
                            strictOn = enabled
                        },
                    )
                }

                if (strictOn && strictMode.cooldownActive) {
                    Text(
                        "Strict Mode active — in-app controls unlock in " +
                            formatCooldown(strictMode.cooldownRemainingMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Text(
            "If Android blocks the accessibility service, allow it via App Info → ⋮ → " +
                "“Allow restricted settings”.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnsupportedConfigurationScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Enhanced Mode", style = MaterialTheme.typography.titleLarge)
        Surface(
            tonalElevation = 1.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Enhanced Mode isn't supported with Advanced Protection enabled on this device.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private fun formatCooldown(remainingMillis: Long): String {
    val hours = remainingMillis / 3_600_000
    val minutes = (remainingMillis % 3_600_000) / 60_000
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
