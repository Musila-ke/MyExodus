package com.example.myexodus.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.blocking.ProtectionDegradation
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.ui.RuleRowState
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberWindowWidth
import com.example.myexodus.ui.ruleRowState
import com.example.myexodus.ui.scheduleSummary
import com.example.myexodus.ui.streaks.MilestoneSplashCard
import com.example.myexodus.ui.streaks.daysUntil
import com.example.myexodus.ui.streaks.habitDays
import com.example.myexodus.ui.streaks.nextMilestone
import com.example.myexodus.ui.streaks.splashMilestones
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow

/**
 * V1 home dashboard — the restricted user's view (Req 9/11/12/15): protection status at a
 * glance, the days-free hero, and one live row per block the user has set up. Block state is
 * computed with the engine's own schedule evaluation — the dashboard never re-derives
 * window/timezone semantics. The hero and milestone splash cards render the repository's
 * [HabitState] read-only (plan §8 Step 5); values with no data source stay honest (no
 * invented numbers).
 */
@Composable
fun HomeScreen(
    store: AppRulesStore,
    monitor: EnhancedModeHealthMonitor,
    degradation: ProtectionDegradation?,
    authority: ProtectionAuthority?,
    onReenable: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStreaks: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenRule: (String) -> Unit,
    habit: HabitState?,
    onAcknowledgeMilestone: (List<Int>) -> Unit,
    onSwitchHabit: () -> Unit,
    streakResolved: Boolean,
    modifier: Modifier = Modifier,
    encryptionPending: Boolean = false,
) {
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    val serviceEnabled by monitor.enabled.collectAsStateWithLifecycle()
    val profile by store.userProfileDao().observeActive().collectAsStateWithLifecycle(initialValue = null)
    val profileId = profile?.userId
    val rules by remember(profileId) {
        profileId?.let { store.restrictionRuleDao().observeByProfile(it) } ?: emptyFlow()
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

    val streakDays = habit?.let { habitDays(it.currentStreakElapsedMs) }
    val nextUp = habit?.let { nextMilestone(it) }
    val splashDays = habit?.let { splashMilestones(it) }?.map { it.milestoneDay }.orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // §8 Step 5: the splash card renders at the top of Home (REQ-12's guaranteed
        // delivery path, independent of notifications) — ONE combined card for every
        // unacknowledged milestone (product-owner call 2026-09-14, resolving plan §16 OQ).
        if (splashDays.isNotEmpty()) {
            item(key = "splash") {
                MilestoneSplashCard(
                    milestoneDays = splashDays,
                    onAcknowledge = { onAcknowledgeMilestone(splashDays) },
                )
            }
        }
        item(key = "status") {
            StatusHeader(
                serviceEnabled = serviceEnabled,
                degradation = degradation,
                authority = authority,
                onOpenSettings = onOpenSettings,
            )
        }
        degradation?.let { current ->
            item(key = "degradation") { DegradationBanner(current, onReenable, onOpenSettings) }
        }
        if (encryptionPending) {
            item(key = "encryption-pending") { EncryptionPendingNotice() }
        }
        item(key = "streak") {
            StreakHeroCard(
                days = streakDays,
                habitLabel = habit?.targetLabel,
                nextMilestoneDay = nextUp?.milestoneDay,
                daysToNext = habit?.let { state -> nextUp?.let { daysUntil(state, it.milestoneDay) } },
                streakResolved = streakResolved,
                onSwitchHabit = onSwitchHabit,
                onOpenStreaks = onOpenStreaks,
            )
        }
        item(key = "blocks") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BlocksHeader(count = rules.size, attempts = countsByTarget.values.sum())
                BlocksCard(
                    rules = rules,
                    countsByTarget = countsByTarget,
                    nowEpochMillis = nowMillis,
                    // Same-instant coherence with the banner: when nothing can detect apps,
                    // a schedule-derived "Blocked now" would contradict "blocks won't start".
                    protectionPaused = authority == ProtectionAuthority.DEGRADED_NO_DETECTION ||
                        degradation == ProtectionDegradation.DEGRADED_NO_DETECTION,
                    onOpenRules = onOpenRules,
                    onOpenRule = onOpenRule,
                )
            }
        }
    }
}

private enum class ChipState { PROTECTING, LIMITED, OFF }

@Composable
private fun StatusHeader(
    serviceEnabled: Boolean,
    degradation: ProtectionDegradation?,
    authority: ProtectionAuthority?,
    onOpenSettings: () -> Unit,
) {
    // One signal authority with the banner below (finding #13): whenever a degradation banner
    // is showing, the chip must not claim "Protecting". Everything below the pre-first-refresh
    // window (authority == null) reads the same tri-state the monitor publishes.
    val chip = when {
        degradation != null -> ChipState.OFF
        authority == ProtectionAuthority.FULL -> ChipState.PROTECTING
        authority == ProtectionAuthority.DEGRADED_POLLING_ONLY -> ChipState.LIMITED
        authority == ProtectionAuthority.DEGRADED_NO_DETECTION -> ChipState.OFF
        else -> if (serviceEnabled) ChipState.PROTECTING else ChipState.OFF
    }
    val style = when (chip) {
        ChipState.PROTECTING -> ChipStyle(
            "Protecting",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primary,
        )
        ChipState.LIMITED -> ChipStyle(
            "Limited",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        ChipState.OFF -> ChipStyle(
            "Blocker off",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.error,
        )
    }
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
            color = style.container,
            contentColor = style.content,
            shape = CircleShape,
            // The chip is the entry point to the fix: every degraded state has its own row
            // and remedy on Settings.
            modifier = Modifier.clickable(role = Role.Button, onClick = onOpenSettings),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).background(style.dot, CircleShape))
                Text(
                    style.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private data class ChipStyle(
    val label: String,
    val container: Color,
    val content: Color,
    val dot: Color,
)

@Composable
private fun StreakHeroCard(
    days: Int?,
    habitLabel: String?,
    nextMilestoneDay: Int?,
    daysToNext: Int?,
    streakResolved: Boolean,
    onSwitchHabit: () -> Unit,
    onOpenStreaks: () -> Unit,
) {
    // days == null means the repository has no habit yet; until its first refresh resolves,
    // that's indistinguishable from "still loading" — never claim the habit is gone early.
    val loading = days == null && !streakResolved
    val caption = when {
        loading -> "Reading your streak…"
        days == null -> "No habit tracked yet. A streak starts counting the day you set one up."
        nextMilestoneDay != null && daysToNext != null ->
            "Next milestone: Day $nextMilestoneDay — $daysToNext to go."
        else -> "Milestone ladder complete — extraordinary."
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            // Multi-habit: the label row opens the shared picker sheet (same affordance as the
            // Streaks summary card). No habit yet → nothing to switch to, plain label.
            if (habitLabel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = onSwitchHabit),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Days free from $habitLabel",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = "Switch habit",
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Text(
                    "Days free",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The numeral slot reserves its exact height in every state (no layout jump):
            // loading shows a skeleton bar, no-habit leaves the slot empty — never a stray
            // em-dash glyph that reads as glitched content.
            Box(Modifier.height(72.dp), contentAlignment = Alignment.BottomStart) {
                when {
                    loading -> Box(
                        Modifier
                            .padding(bottom = 14.dp)
                            .width(96.dp)
                            .height(44.dp)
                            .background(
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                                MaterialTheme.shapes.small,
                            ),
                    )
                    days != null -> Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            days.toString(),
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (days == 1) "day" else "days",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(caption, style = MaterialTheme.typography.bodyMedium)
            if (days == null && !loading) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenStreaks) { Text("Set up a habit") }
            }
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
    protectionPaused: Boolean,
    onOpenRules: () -> Unit,
    onOpenRule: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (rules.isEmpty()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("No blocks yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Blocks you create under Rules show up here with their live status.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenRules) { Text("Add a block") }
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
                        protectionPaused = protectionPaused,
                        onOpenRule = onOpenRule,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockRow(
    rule: RestrictionRule,
    attempts: Int,
    nowEpochMillis: Long,
    protectionPaused: Boolean,
    onOpenRule: (String) -> Unit,
) {
    val label = rememberAppLabel(rule.blockedPackage)
    val res = LocalResources.current
    val state = remember(rule, nowEpochMillis, res, protectionPaused) {
        if (protectionPaused) {
            RuleRowState(res.getString(R.string.state_not_running), active = false)
        } else {
            ruleRowState(rule, nowEpochMillis, res)
        }
    }
    val support = buildString {
        append(scheduleSummary(rule, res))
        if (attempts > 0) {
            append(" · ")
            append(attempts)
            append(if (attempts == 1) " attempt" else " attempts")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A row that affords tapping must route somewhere: straight to this rule's editor
            // under Rules (finding #19).
            .clickable(role = Role.Button, onClick = { onOpenRule(rule.ruleId) })
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                // Schedule + attempts is the informative line; one line truncated it
                // mid-word ("Always on · 2 atte…") — finding #20.
                maxLines = 2,
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

/** Non-blocking notice: the store is running on the plaintext database while the one-time
 *  encryption conversion retries on a later launch (plan §8 Step 0 — never blocks app use). */
@Composable
private fun EncryptionPendingNotice() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Data protection upgrade pending", style = MaterialTheme.typography.titleMedium)
            Text(
                "MyExodus is applying an extra layer of encryption to your local data. " +
                    "Everything works normally — this finishes automatically on a future launch.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DegradationBanner(
    degradation: ProtectionDegradation,
    onReenable: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val (message, actionLabel, action) = when (degradation) {
        // §8 Step 6: cause not named — with both paths down it is usually more than one
        // thing; the Settings protection rows name each mechanism and carry its fix.
        ProtectionDegradation.DEGRADED_NO_DETECTION -> Triple(
            stringResource(R.string.protection_paused_text),
            stringResource(R.string.protection_paused_action),
            onOpenSettings,
        )
        // The old copy claimed blocking was off while Path A (a11y) is typically still
        // live after a Task-Manager stop — can't claim that, and can't claim "everything
        // is fine": the poll path (time limits + fallback detection) stays off until
        // re-enable. Name only the always-true loss (regression finding #1, 2026-09-16).
        ProtectionDegradation.APP_STOPPED -> Triple(
            "MyExodus was stopped from the Task Manager. Time limits stay paused until you re-enable.",
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
