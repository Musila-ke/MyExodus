package com.example.myexodus.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.ProtectionDegradation
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberWindowWidth
import com.example.myexodus.ui.ruleRowState
import com.example.myexodus.ui.scheduleSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow

/**
 * V1 home dashboard — the restricted user's view (Req 9/11/12/15): protection status at a
 * glance, the days-free hero, and one live row per block the user has set up. Block state is
 * computed with the engine's own schedule evaluation — the dashboard never re-derives
 * window/timezone semantics. Values that have no data source yet stay honest (no invented
 * numbers): streak tracking lights up once habit progress exists.
 */
@Composable
fun HomeScreen(
    store: AppRulesStore,
    monitor: EnhancedModeHealthMonitor,
    degradation: ProtectionDegradation?,
    onReenable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    val serviceEnabled by monitor.enabled.collectAsStateWithLifecycle()
    val profile by store.userProfileDao().observeActive().collectAsStateWithLifecycle(initialValue = null)
    val profileId = profile?.userId
    val rules by remember(profileId) {
        profileId?.let { store.restrictionRuleDao().observeByProfile(it) } ?: emptyFlow()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by remember(profileId) {
        profileId?.let { store.habitProgressDao().observeByProfile(it) } ?: emptyFlow()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val attemptCounts by store.attemptLogDao().observeBlockedCounts()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val countsByTarget = remember(attemptCounts) { attemptCounts.associate { it.target to it.count } }

    // Live block state flips at schedule boundaries; a slow tick keeps the labels honest
    // while the screen stays open. Nothing else on this screen is time-derived.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    val bestStreak = progress.maxOfOrNull { it.currentCleanDaysStreak } ?: 0

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "status") { StatusHeader(serviceEnabled) }
        degradation?.let { current ->
            item(key = "degradation") { DegradationBanner(current, onReenable) }
        }
        item(key = "streak") {
            StreakHeroCard(streak = bestStreak, tracked = progress.isNotEmpty())
        }
        item(key = "blocks") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BlocksHeader(count = rules.size, attempts = countsByTarget.values.sum())
                BlocksCard(
                    rules = rules,
                    countsByTarget = countsByTarget,
                    nowEpochMillis = nowMillis,
                )
            }
        }
    }
}

@Composable
private fun StatusHeader(serviceEnabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("MyExodus", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Your protection dashboard",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = if (serviceEnabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
            contentColor = if (serviceEnabled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
            shape = CircleShape,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (serviceEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            CircleShape,
                        ),
                )
                Text(
                    if (serviceEnabled) "Protecting" else "Blocker off",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun StreakHeroCard(streak: Int, tracked: Boolean) {
    val nextMilestone = when {
        streak < 7 -> 7
        streak < 30 -> 30
        streak < 90 -> 90
        else -> null
    }
    val caption = when {
        !tracked -> "No habit tracked yet — streak counting starts when habit setup is complete."
        streak == 0 -> "Set up your first rule to start your streak."
        nextMilestone != null -> "Next milestone: $nextMilestone days — ${nextMilestone - streak} to go."
        else -> "Milestone ladder complete — extraordinary."
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Days free",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$streak",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (streak == 1) "day" else "days",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(caption, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BlocksHeader(count: Int, attempts: Int) {
    val meta = buildString {
        append(count)
        append(if (count == 1) " block" else " blocks")
        if (attempts > 0) {
            append(" · ")
            append(attempts)
            append(if (attempts == 1) " attempt" else " attempts")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Your blocks", style = MaterialTheme.typography.titleMedium)
        if (count > 0) {
            Text(
                meta,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun BlocksCard(
    rules: List<RestrictionRule>,
    countsByTarget: Map<String, Int>,
    nowEpochMillis: Long,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (rules.isEmpty()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("No blocks yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Blocks you create under Rules show up here with their live status.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column {
                rules.forEachIndexed { index, rule ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    BlockRow(
                        rule = rule,
                        attempts = countsByTarget[rule.blockedPackage] ?: 0,
                        nowEpochMillis = nowEpochMillis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockRow(rule: RestrictionRule, attempts: Int, nowEpochMillis: Long) {
    val label = rememberAppLabel(rule.blockedPackage)
    val res = LocalResources.current
    val state = remember(rule, nowEpochMillis, res) { ruleRowState(rule, nowEpochMillis, res) }
    val support = buildString {
        append(scheduleSummary(rule, res))
        if (attempts > 0) {
            append(" · ")
            append(attempts)
            append(if (attempts == 1) " attempt" else " attempts")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = if (rule.targetType == "WEBSITE") Icons.Default.Language else Icons.Default.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                support,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.label?.let { StatePill(it, state.active) }
    }
}

@Composable
private fun StatePill(label: String, active: Boolean) {
    val accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun DegradationBanner(
    degradation: ProtectionDegradation,
    onReenable: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val (message, actionLabel, action) = when (degradation) {
        ProtectionDegradation.USAGE_ACCESS_REVOKED -> Triple(
            "App blocking is off — MyExodus can't see when restricted apps open. Grant usage access in Settings.",
            "Fix",
            { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
        )
        ProtectionDegradation.APP_STOPPED -> Triple(
            "Protection was stopped from the Task Manager. Re-enable it to resume blocking.",
            "Re-enable",
            onReenable,
        )
        ProtectionDegradation.NOTIFICATIONS_OFF -> Triple(
            "Notifications are off for MyExodus — protection status may be hidden. Turn them on in Settings.",
            "Fix",
            {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
        )
        ProtectionDegradation.OVERLAY_REVOKED -> Triple(
            "Blocking overlay is off — MyExodus can't show block screens. Allow 'display over other apps' in Settings.",
            "Fix",
            {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData("package:${context.packageName}".toUri()),
                )
            },
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = action) { Text(actionLabel) }
        }
    }
}
