package com.example.myexodus.ui.setup

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.settings.ProtectionChecks
import com.example.myexodus.settings.accessibilitySettingsIntent
import com.example.myexodus.settings.notificationSettingsIntent
import com.example.myexodus.settings.notificationsGranted
import com.example.myexodus.settings.overlaySettingsIntent
import com.example.myexodus.settings.readProtectionChecks
import com.example.myexodus.settings.usageAccessSettingsIntent
import com.example.myexodus.ui.BrandLockup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SetupStep { USAGE, DISCLOSURE, ACCESSIBILITY, NOTIFICATIONS, OVERLAY }

/** Plan §8 Step 7 order, verbatim: usage access → disclosure → accessibility →
 *  notifications → overlay, then enforcement starts (in `onFinish`'s caller). */
private val SetupSteps = listOf(
    SetupStep.USAGE,
    SetupStep.DISCLOSURE,
    SetupStep.ACCESSIBILITY,
    SetupStep.NOTIFICATIONS,
    SetupStep.OVERLAY,
)

/**
 * First-run permission wizard (plan §8 Step 7). Each step states plainly what breaks if its
 * permission is denied, matching actual [ProtectionStatusMonitor] behavior, and every step
 * can be skipped — the Settings protection rows remain the fix path afterwards. Granting a
 * permission in system Settings is reflected live by the resume re-read; nothing is written
 * to disk here (the caller persists "setup seen" and starts enforcement).
 */
@Composable
fun SetupScreen(
    monitor: EnhancedModeHealthMonitor,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by rememberSaveable { mutableIntStateOf(0) }
    var checks by remember { mutableStateOf<ProtectionChecks?>(null) }
    var notificationsOk by remember { mutableStateOf(notificationsGranted(context)) }
    var notificationsRequested by rememberSaveable { mutableStateOf(false) }
    val serviceEnabled by monitor.enabled.collectAsStateWithLifecycle()

    // Every stop in this wizard leaves the app for a system screen; the grant is only
    // visible on resume, so re-read there (same contract as Settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                monitor.refresh()
                scope.launch {
                    checks = withContext(Dispatchers.IO) { readProtectionChecks(context) }
                    notificationsOk = notificationsGranted(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        checks = withContext(Dispatchers.IO) { readProtectionChecks(context) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsRequested = true
        notificationsOk = granted
    }

    val step = SetupSteps[index]
    val lastStep = index == SetupSteps.lastIndex
    val granted: Boolean? = when (step) {
        SetupStep.USAGE -> checks?.usageAccess
        SetupStep.DISCLOSURE -> null
        SetupStep.ACCESSIBILITY -> serviceEnabled
        SetupStep.NOTIFICATIONS -> notificationsOk
        SetupStep.OVERLAY -> checks?.overlayGranted
    }

    fun advance() {
        if (lastStep) onFinish() else index++
    }

    // System back steps the wizard back while an earlier step exists — the on-screen Back's
    // twin, so the two back affordances never disagree (ADR #91). At step 0 the platform
    // default stands: back leaves the app.
    BackHandler(enabled = index > 0) { index-- }

    val primary: Pair<String, () -> Unit> = when (step) {
        SetupStep.USAGE -> if (granted == true) {
            stringResource(R.string.setup_next) to ::advance
        } else {
            stringResource(R.string.setup_usage_action) to
                { context.startActivity(usageAccessSettingsIntent()) }
        }

        SetupStep.DISCLOSURE -> stringResource(R.string.setup_disclosure_action) to ::advance

        SetupStep.ACCESSIBILITY -> if (granted == true) {
            stringResource(R.string.setup_next) to ::advance
        } else {
            stringResource(R.string.setup_accessibility_action) to
                { context.startActivity(accessibilitySettingsIntent()) }
        }

        SetupStep.NOTIFICATIONS -> when {
            notificationsOk -> stringResource(R.string.setup_next) to ::advance
            // A second runtime request is a silent no-op once the user denied it, so the
            // fallback after that is the app's notification settings.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsRequested ->
                stringResource(R.string.setup_notifications_action) to
                    { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            else -> stringResource(R.string.setup_notifications_action_settings) to
                { context.startActivity(notificationSettingsIntent(context)) }
        }

        SetupStep.OVERLAY -> if (granted == true) {
            stringResource(R.string.setup_done) to onFinish
        } else {
            stringResource(R.string.setup_overlay_action) to
                { context.startActivity(overlaySettingsIntent(context)) }
        }
    }

    val (titleRes, bodyRes) = when (step) {
        SetupStep.USAGE -> R.string.setup_usage_title to R.string.setup_usage_body
        SetupStep.DISCLOSURE -> R.string.setup_disclosure_title to R.string.setup_disclosure_body
        SetupStep.ACCESSIBILITY -> R.string.setup_accessibility_title to R.string.setup_accessibility_body
        SetupStep.NOTIFICATIONS -> R.string.setup_notifications_title to R.string.setup_notifications_body
        SetupStep.OVERLAY -> R.string.setup_overlay_title to R.string.setup_overlay_body
    }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        // Centred when it fits, scrollable when it doesn't (landscape / short screens must
        // never strand the action buttons below the fold).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Brand moment on the first stop only — repeating it on all five permission
                // steps would be chrome on a screen whose job is granting access.
                if (index == 0) {
                    BrandLockup()
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.setup_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        stringResource(R.string.setup_step_of, index + 1, SetupSteps.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { (index + 1) / SetupSteps.size.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(bodyRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        granted?.let { StatusLine(granted = it) }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (index > 0) {
                        TextButton(onClick = { index-- }) { Text(stringResource(R.string.setup_back)) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (step != SetupStep.DISCLOSURE && granted != true) {
                        TextButton(onClick = ::advance) { Text(stringResource(R.string.setup_skip)) }
                    }
                    Button(onClick = primary.second) { Text(primary.first) }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(granted: Boolean) {
    val tint = if (granted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(
                if (granted) R.string.setup_status_granted else R.string.setup_status_missing,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
    }
}
