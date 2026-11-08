package com.example.myexodus.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.blocking.ProtectionDegradation
import com.example.myexodus.blocking.usage.BatteryPromptRoute
import com.example.myexodus.settings.IntegrityStatus
import com.example.myexodus.settings.ProtectionChecks
import com.example.myexodus.settings.accessibilitySettingsIntent
import com.example.myexodus.settings.appInfoSettingsIntent
import com.example.myexodus.settings.batteryOptimizationSettingsIntent
import com.example.myexodus.settings.oemRestrictionSettingsIntent
import com.example.myexodus.settings.overlaySettingsIntent
import com.example.myexodus.settings.usageAccessSettingsIntent
import com.example.myexodus.ui.BackRow

/**
 * The permission and enforcement rows (plan §2/§8.6/§9), split off the Settings index by owner
 * sign-off 2026-09-22: these six are the only rows carrying a live status word *and* an action,
 * and six status/action pairs buried an index of destinations. The Android and OEM rows stay
 * separate on purpose — different mechanisms, different remedies, and no API reports OEM state,
 * so that row says so instead of inventing a status. Every action opens a platform screen; none
 * of them grants anything by itself.
 */
@Composable
internal fun ProtectionScreen(
    checks: ProtectionChecks,
    serviceEnabled: Boolean,
    /** §8 Step 6: the same published state Home's banner reads. The rows above can all read
     *  granted while protection is still paused (an explicit stop, Enhanced Mode off, or
     *  notifications denied), so the page states it instead of implying all is well. */
    degradation: ProtectionDegradation?,
    onReenable: () -> Unit,
    onRecheck: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** §8.7/D1: non-null while the outage prompt may be offered — the battery row then
     *  carries the interruption education and, for DIRECT_REQUEST, fires the exemption ask. */
    batteryPrompt: BatteryPromptRoute? = null,
    onRequestExemption: () -> Unit = {},
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize()) {
        BackRow(label = stringResource(R.string.settings_protection_header), onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (degradation) {
                ProtectionDegradation.APP_STOPPED -> PausedBanner(
                    title = stringResource(R.string.protection_stopped_title),
                    body = stringResource(R.string.protection_stopped_text),
                    actionLabel = stringResource(R.string.protection_stopped_action),
                    onAction = onReenable,
                )
                ProtectionDegradation.DEGRADED_NO_DETECTION -> PausedBanner(
                    title = stringResource(R.string.protection_paused_title),
                    body = stringResource(R.string.protection_paused_page_text),
                    actionLabel = null,
                    onAction = null,
                )
                ProtectionDegradation.NOTIFICATIONS_OFF -> PausedBanner(
                    title = stringResource(R.string.protection_notif_off_banner_title),
                    body = stringResource(R.string.protection_notif_off_banner_text),
                    actionLabel = stringResource(R.string.protection_notif_off_banner_action),
                    onAction = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                )
                null -> Unit
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
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

                    RowDivider()
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

                    RowDivider()
                    // D1's Settings surface: the plain row becomes the offer only while a
                    // corroborated interruption is live (batteryPrompt != null) — the direct
                    // exemption ask exists nowhere else in the app.
                    val directAsk = batteryPrompt == BatteryPromptRoute.DIRECT_REQUEST
                    StatusRow(
                        title = stringResource(R.string.settings_battery_title),
                        status = stringResource(
                            if (checks.batteryExempt) R.string.settings_battery_ok else R.string.settings_battery_off,
                        ),
                        supporting = stringResource(
                            if (batteryPrompt != null) R.string.settings_battery_prompt
                            else R.string.settings_battery_hint,
                        ),
                        tone = if (checks.batteryExempt) StatusTone.GOOD else StatusTone.ATTENTION,
                        actionLabel = stringResource(
                            if (checks.batteryExempt) R.string.settings_action_open else R.string.settings_action_fix,
                        ),
                        onAction = {
                            if (directAsk) onRequestExemption()
                            else context.startActivity(batteryOptimizationSettingsIntent())
                        },
                    )

                    RowDivider()
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

                    RowDivider()
                    StatusRow(
                        title = stringResource(R.string.settings_overlay_title),
                        status = stringResource(
                            if (checks.overlayGranted) R.string.settings_overlay_ok else R.string.settings_overlay_off,
                        ),
                        tone = if (checks.overlayGranted) StatusTone.GOOD else StatusTone.ATTENTION,
                        actionLabel = stringResource(
                            if (checks.overlayGranted) R.string.settings_action_open else R.string.settings_action_fix,
                        ),
                        onAction = { context.startActivity(overlaySettingsIntent(context)) },
                    )

                    RowDivider()
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
                        // #14: the recheck is a recovery action — the checks re-run on every
                        // Settings resume, so the button exists only while the row reports a
                        // non-verified state.
                        actionLabel = if (integrity == IntegrityStatus.VERIFIED) {
                            null
                        } else {
                            stringResource(R.string.settings_integrity_recheck)
                        },
                        onAction = onRecheck,
                    )
                }
            }

            // Belongs beside the row it explains: the accessibility service is the one Android
            // will refuse to enable until the user allows restricted settings for this app.
            Text(
                stringResource(R.string.enhanced_mode_restricted_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Home's paused copy, stated here too: a granted permission list alone never proves blocks can
 *  run. Tertiary container is the app's existing callout shape (pairing ceremony). */
@Composable
private fun PausedBanner(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    modifier = Modifier.semantics { contentDescription = "$actionLabel, $title" },
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    title: String,
    status: String,
    tone: StatusTone,
    /** #14: null hides the action — a healthy row offers no manual re-run. */
    actionLabel: String?,
    onAction: () -> Unit,
    supporting: String? = null,
) {
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
                StatusDot(tone)
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
        if (actionLabel != null) {
            TextButton(
                onClick = onAction,
                // The button is a sibling of the title, not its child, so its own label is all
                // TalkBack has to go on — six rows of "Open"/"Fix" announce identically with no
                // subject (WCAG 2.4.6; finding #26, the Rules switches' same defect in #25).
                modifier = Modifier.semantics { contentDescription = "$actionLabel, $title" },
            ) {
                Text(actionLabel)
            }
        }
    }
}
