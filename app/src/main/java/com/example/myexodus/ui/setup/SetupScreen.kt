package com.example.myexodus.ui.setup

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
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
    ) {
        notificationsRequested = true
        // Re-read the whole gate, not the permission result alone: with POST_NOTIFICATIONS
        // already granted, the launcher returns "granted" without showing anything, and the
        // app-level toggle can still be off (finding #59).
        notificationsOk = notificationsGranted(context)
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandLockup(height = 40.dp)
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.setup_step_counter, index + 1, SetupSteps.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SetupProgress(index = index)
            // Centred as a poster when it fits, scrollable when it doesn't — the actions stay
            // pinned to the bottom either way.
            BoxWithConstraints(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(stepIcon(step)),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(26.dp))
                    EditorialKicker(stringResource(R.string.setup_title))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(titleRes),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(bodyRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    granted?.let {
                        Spacer(Modifier.height(18.dp))
                        StepStatus(granted = it)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (index > 0) {
                    TextButton(onClick = { index-- }) { Text(stringResource(R.string.setup_back)) }
                }
                Spacer(Modifier.weight(1f))
                if (step != SetupStep.DISCLOSURE && granted != true) {
                    TextButton(onClick = ::advance) { Text(stringResource(R.string.setup_skip)) }
                }
                Button(onClick = primary.second, modifier = Modifier.height(52.dp)) {
                    Text(primary.first)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Segmented progress: one bar per permission, so the shape of the journey is visible at a
 *  glance instead of a percentage fill. The current stop is gold; passed stops are teal. */
@Composable
private fun SetupProgress(index: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(SetupSteps.size) { bar ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(
                        when {
                            bar == index -> MaterialTheme.colorScheme.tertiary
                            bar < index -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun StepStatus(granted: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (granted) {
            Icon(
                painter = painterResource(R.drawable.onb_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.setup_status_granted),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.outline, CircleShape),
            )
            Text(
                stringResource(R.string.setup_status_missing),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun stepIcon(step: SetupStep): Int = when (step) {
    SetupStep.USAGE -> R.drawable.onb_usage
    SetupStep.DISCLOSURE -> R.drawable.onb_disclosure
    SetupStep.ACCESSIBILITY -> R.drawable.onb_accessibility
    SetupStep.NOTIFICATIONS -> R.drawable.onb_notifications
    SetupStep.OVERLAY -> R.drawable.onb_overlay
}
