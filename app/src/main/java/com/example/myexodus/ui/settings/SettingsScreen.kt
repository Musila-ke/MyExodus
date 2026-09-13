package com.example.myexodus.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.settings.IntegrityStatus
import com.example.myexodus.settings.ProtectionChecks
import com.example.myexodus.settings.StrictModeManager
import com.example.myexodus.settings.accessibilitySettingsIntent
import com.example.myexodus.settings.appInfoSettingsIntent
import com.example.myexodus.settings.batteryOptimizationSettingsIntent
import com.example.myexodus.settings.exactAlarmSettingsIntent
import com.example.myexodus.settings.oemRestrictionSettingsIntent
import com.example.myexodus.settings.overlaySettingsIntent
import com.example.myexodus.settings.readProtectionChecks
import com.example.myexodus.settings.usageAccessSettingsIntent
import com.example.myexodus.ui.EnhancedModeScreen
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.rememberWindowWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V1 Settings (plan §2/§8.6/§9): one row per mechanism — accessibility, usage access, exact
 * alarms, Android battery optimization, OEM background restrictions, overlay capability, and
 * keystore-backed enforcement integrity. The Android and OEM rows stay separate on purpose:
 * they are different mechanisms with different remedies, and no API reports OEM state, so that
 * row says so instead of inventing a status. Every action opens the platform screen; none of
 * them grants anything by itself.
 */
@Composable
fun SettingsScreen(
    monitor: EnhancedModeHealthMonitor,
    strictMode: StrictModeManager,
    settings: EnhancedModeSettings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serviceEnabled by monitor.enabled.collectAsStateWithLifecycle()
    var checks by remember { mutableStateOf<ProtectionChecks?>(null) }

    // Permissions change in system Settings while this screen is backgrounded; re-read on
    // every resume so a granted permission is never shown as missing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                monitor.refresh()
                scope.launch { checks = withContext(Dispatchers.IO) { readProtectionChecks(context) } }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    val loaded = checks
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (loaded != null) {
            ProtectionSection(
                checks = loaded,
                serviceEnabled = serviceEnabled,
                onRecheck = {
                    monitor.refresh()
                    scope.launch { checks = withContext(Dispatchers.IO) { readProtectionChecks(context) } }
                },
            )
        }

        EnhancedModeScreen(monitor, strictMode, settings)
    }
}

@Composable
private fun ProtectionSection(
    checks: ProtectionChecks,
    serviceEnabled: Boolean,
    onRecheck: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_protection_header), style = MaterialTheme.typography.titleMedium)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                var first = true
                @Composable
                fun divider() {
                    if (!first) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    first = false
                }

                divider()
                StatusRow(
                    title = stringResource(R.string.settings_accessibility_title),
                    status = stringResource(
                        if (serviceEnabled) R.string.settings_accessibility_ok else R.string.settings_accessibility_off,
                    ),
                    tone = if (serviceEnabled) StatusTone.GOOD else StatusTone.ATTENTION,
                    actionLabel = stringResource(
                        if (serviceEnabled) R.string.settings_action_open else R.string.settings_action_fix,
                    ),
                    onAction = { context.startActivity(accessibilitySettingsIntent()) },
                )

                divider()
                StatusRow(
                    title = stringResource(R.string.settings_usage_title),
                    status = stringResource(
                        if (checks.usageAccess) R.string.settings_usage_ok else R.string.settings_usage_off,
                    ),
                    tone = if (checks.usageAccess) StatusTone.GOOD else StatusTone.ATTENTION,
                    actionLabel = stringResource(
                        if (checks.usageAccess) R.string.settings_action_open else R.string.settings_action_fix,
                    ),
                    onAction = { context.startActivity(usageAccessSettingsIntent()) },
                )

                // Below API 31 the permission does not exist — no row, rather than a row
                // claiming a status Android cannot have.
                if (checks.exactAlarmSupported) {
                    divider()
                    StatusRow(
                        title = stringResource(R.string.settings_exact_alarm_title),
                        status = stringResource(
                            if (checks.exactAlarmGranted) R.string.settings_exact_alarm_ok else R.string.settings_exact_alarm_off,
                        ),
                        tone = if (checks.exactAlarmGranted) StatusTone.GOOD else StatusTone.ATTENTION,
                        actionLabel = stringResource(
                            if (checks.exactAlarmGranted) R.string.settings_action_open else R.string.settings_action_fix,
                        ),
                        onAction = { context.startActivity(exactAlarmSettingsIntent(context)) },
                    )
                }

                divider()
                StatusRow(
                    title = stringResource(R.string.settings_battery_title),
                    status = stringResource(
                        if (checks.batteryExempt) R.string.settings_battery_ok else R.string.settings_battery_off,
                    ),
                    supporting = stringResource(R.string.settings_battery_hint),
                    tone = if (checks.batteryExempt) StatusTone.GOOD else StatusTone.ATTENTION,
                    actionLabel = stringResource(
                        if (checks.batteryExempt) R.string.settings_action_open else R.string.settings_action_fix,
                    ),
                    onAction = { context.startActivity(batteryOptimizationSettingsIntent()) },
                )

                divider()
                StatusRow(
                    title = stringResource(R.string.settings_oem_title),
                    status = stringResource(R.string.settings_oem_status, Build.MANUFACTURER),
                    tone = StatusTone.INFO,
                    actionLabel = stringResource(R.string.settings_action_open),
                    onAction = {
                        val intent = oemRestrictionSettingsIntent(context)
                        // Known OEM screens move between builds; a dead deep link lands on
                        // App info rather than nowhere.
                        runCatching { context.startActivity(intent) }
                            .onFailure { context.startActivity(appInfoSettingsIntent(context)) }
                    },
                )

                divider()
                val overlayOk = checks.overlayGranted || !checks.overlayRequired
                StatusRow(
                    title = stringResource(R.string.settings_overlay_title),
                    status = stringResource(
                        when {
                            checks.overlayGranted -> R.string.settings_overlay_ok
                            !checks.overlayRequired -> R.string.settings_overlay_not_needed
                            else -> R.string.settings_overlay_off
                        },
                    ),
                    tone = if (overlayOk) StatusTone.GOOD else StatusTone.ATTENTION,
                    actionLabel = if (checks.overlayRequired) {
                        stringResource(
                            if (checks.overlayGranted) R.string.settings_action_open else R.string.settings_action_fix,
                        )
                    } else {
                        null
                    },
                    onAction = if (checks.overlayRequired) {
                        { context.startActivity(overlaySettingsIntent(context)) }
                    } else {
                        null
                    },
                )

                divider()
                val integrity = checks.integrity
                StatusRow(
                    title = stringResource(R.string.settings_integrity_title),
                    status = stringResource(
                        when (integrity) {
                            IntegrityStatus.VERIFIED -> R.string.settings_integrity_verified
                            IntegrityStatus.UNVERIFIED -> R.string.settings_integrity_unverified
                            IntegrityStatus.UNAVAILABLE -> R.string.settings_integrity_unavailable
                            IntegrityStatus.FAILED -> R.string.settings_integrity_failed
                        },
                    ),
                    tone = when (integrity) {
                        IntegrityStatus.VERIFIED -> StatusTone.GOOD
                        IntegrityStatus.FAILED -> StatusTone.ATTENTION
                        else -> StatusTone.INFO
                    },
                    actionLabel = stringResource(R.string.settings_integrity_recheck),
                    onAction = onRecheck,
                )
            }
        }
    }
}

private enum class StatusTone { GOOD, ATTENTION, INFO }

@Composable
private fun StatusRow(
    title: String,
    status: String,
    tone: StatusTone,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    supporting: String? = null,
) {
    val accent = when (tone) {
        StatusTone.GOOD -> MaterialTheme.colorScheme.primary
        StatusTone.ATTENTION -> MaterialTheme.colorScheme.error
        StatusTone.INFO -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).background(accent, CircleShape))
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
