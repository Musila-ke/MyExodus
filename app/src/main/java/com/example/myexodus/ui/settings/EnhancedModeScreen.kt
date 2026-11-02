package com.example.myexodus.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.EnforcementForegroundService
import com.example.myexodus.blocking.cancelDailyMaintenance
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.StrictModeManager
import kotlinx.coroutines.delay

/**
 * Enhanced Mode's two switches (plan §8 Steps 10-12), now rows of the Settings index rather than
 * a section of their own (owner sign-off 2026-09-22). Both are gatekept by Strict Mode's 24h
 * cooldown, and the unsupported-configuration case (Advanced Protection on the device) turns the
 * switches off with the reason as their subtitle — the old early-return section hid the controls
 * entirely, which left the user unable to see that the feature exists but is unavailable.
 * No AAPM-disable instructions anywhere (Decision 7).
 */
@Composable
internal fun EnhancedModeToggleRow(
    monitor: EnhancedModeHealthMonitor,
    strictMode: StrictModeManager,
    settings: EnhancedModeSettings,
    /** Shared with the Strict row by the parent: a Strict toggle recomposes this row at once,
     *  so the switch greys out immediately instead of up to a minute later on the tick. */
    strictOn: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val advancedProtection by monitor.advancedProtection.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(settings.enhancedModeEnabled) }
    // Same ticker as the Strict row, for the same two reasons: the tick is what forces this
    // row's recomposition so `canDisable()` re-reads the clock and the switch frees itself the
    // moment the cooldown expires, and it keeps the countdown line below accurate meanwhile.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { monitor.refresh() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            tick = System.currentTimeMillis()
        }
    }

    ToggleRow(
        icon = Icons.Outlined.Bolt,
        title = stringResource(R.string.enhanced_mode_title),
        subtitle = stringResource(
            if (advancedProtection) R.string.enhanced_mode_unsupported else R.string.enhanced_mode_body,
        ),
        checked = enabled,
        enabled = !advancedProtection && strictMode.canDisable(),
        // The Strict cooldown locks this switch too; without the line it greyed out with no
        // stated reason on the row (the Strict row above carries its own explanation).
        warning = if (!advancedProtection && strictOn && strictMode.cooldownActive) {
            tick
            stringResource(
                R.string.enhanced_mode_strict_cooldown,
                formatCooldown(strictMode.cooldownRemainingMillis),
            )
        } else {
            null
        },
        onCheckedChange = { on ->
            settings.enhancedModeEnabled = on
            enabled = on
            // The master switch also drives the §8.1 foreground service.
            if (on) {
                EnforcementForegroundService.start(context)
            } else {
                EnforcementForegroundService.stop(context)
                // §13 rollback: the maintenance job goes with the switch.
                cancelDailyMaintenance(context)
            }
        },
        modifier = modifier,
    )
}

@Composable
internal fun StrictModeToggleRow(
    monitor: EnhancedModeHealthMonitor,
    strictMode: StrictModeManager,
    strictOn: Boolean,
    onStrictOnChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val advancedProtection by monitor.advancedProtection.collectAsStateWithLifecycle()
    // Read below only to re-arm the countdown: the tick itself is what forces the recomposition,
    // and `cooldownRemainingMillis` recomputes from the clock on each pass.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            tick = System.currentTimeMillis()
        }
    }

    ToggleRow(
        icon = Icons.Outlined.Lock,
        title = stringResource(R.string.enhanced_mode_strict_title),
        // Under Advanced Protection this switch is disabled like the Enhanced row's — the
        // Enhanced row swaps in the reason, this one did not, so it greyed out unexplained.
        subtitle = stringResource(
            if (advancedProtection) R.string.enhanced_mode_strict_unsupported else R.string.enhanced_mode_strict_body,
        ),
        checked = strictOn,
        enabled = !advancedProtection && strictMode.canDisable(),
        onCheckedChange = { on ->
            strictMode.strictModeEnabled = on
            onStrictOnChange(on)
        },
        warning = if (strictOn && strictMode.cooldownActive) {
            tick
            stringResource(
                R.string.enhanced_mode_strict_cooldown,
                formatCooldown(strictMode.cooldownRemainingMillis),
            )
        } else {
            null
        },
        modifier = modifier,
    )
}

/** h/m rather than a localised duration format: it is a countdown label beside a status word,
 *  not a date, and the two units are the whole vocabulary. */
private fun formatCooldown(remainingMillis: Long): String {
    val hours = remainingMillis / 3_600_000
    val minutes = (remainingMillis % 3_600_000) / 60_000
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
