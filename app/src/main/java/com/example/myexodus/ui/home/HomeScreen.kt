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
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.accountability.sos.NudgeInboxRepository
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.blocking.ProtectionDegradation
import com.example.myexodus.blocking.usage.AccountingOutage
import com.example.myexodus.blocking.usage.AccountingOutageSource
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.ui.AppMark
import com.example.myexodus.ui.RuleRowState
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.formatDuration
import com.example.myexodus.ui.formatHeaderDate
import com.example.myexodus.ui.preloadAppLabels
import com.example.myexodus.ui.relativeTimeText
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberWindowWidth
import com.example.myexodus.ui.ruleRowState
import com.example.myexodus.ui.rules.RULES_CARD_MAX_HEIGHT
import com.example.myexodus.ui.scheduleSummary
import com.example.myexodus.ui.streaks.FreeSinceLine
import com.example.myexodus.ui.streaks.HabitLabelRow
import com.example.myexodus.ui.streaks.MilestoneSplashCard
import com.example.myexodus.ui.streaks.daysFree
import com.example.myexodus.ui.streaks.daysUntil
import com.example.myexodus.ui.streaks.nextMilestone
import com.example.myexodus.ui.streaks.splashMilestones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

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
    outageSource: AccountingOutageSource,
    onReenable: () -> Unit,
    onOpenSettings: () -> Unit,
    onFixOutage: () -> Unit = onOpenSettings,
    onOpenStreaks: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenRule: (String) -> Unit,
    habit: HabitState?,
    onAcknowledgeMilestone: (List<Int>) -> Unit,
    onSwitchHabit: () -> Unit,
    streakResolved: Boolean,
    onRetryStreak: () -> Unit = {},
    streakLoadFailed: Boolean = false,
    modifier: Modifier = Modifier,
    encryptionPending: Boolean = false,
    /** #12: pairId -> shown partner name for the pinned card's sender line. A failed read
     *  degrades to no names — the card falls back to its generic title, never a wrong name.
     *  Hoisted to the shell 2026-10-04: the pinned card and the Reach out FAB share one
     *  roster read per Home entry. */
    partnerNames: Map<String, String> = emptyMap(),
    /** SOS §8.14: the check-ins-off banner inputs — notification state and its fix path. */
    partnerNotificationsEnabled: Boolean = true,
    partnerNotificationsRequestable: Boolean = false,
    onFixPartnerNotifications: () -> Unit = onOpenSettings,
    /** SOS §8.16 (Step 9): the recipient card — newest pending reach-out, or null. Both of
     *  its actions ("I'm here" and X) acknowledge through the one callback (§6.9). */
    nudgeCard: NudgeInboxRepository.NudgeCard? = null,
    onNudgeAcknowledge: () -> Unit = {},
) {
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    val serviceEnabled by monitor.enabled.collectAsStateWithLifecycle()
    val profile by store.userProfileDao().observeActive().collectAsStateWithLifecycle(initialValue = null)
    val profileId = profile?.userId
    // null until the first emission: a cold start must not flash "No blocks yet" at a user
    // who has blocks (streak card's own loading rule, applied to the list).
    val rules by remember(profileId) {
        profileId?.let { store.restrictionRuleDao().observeByProfile(it) } ?: emptyFlow()
    }.collectAsStateWithLifecycle(initialValue = null)
    // ADR #153: the user-perceivable "fully loaded" moment — streak + block list resolved, not
    // just the first frame (TTID). reportFullyDrawn → AMS logs "Fully drawn ... +Nms", the
    // TTFD number the startup-perf recipe reads.
    ReportDrawnWhen { streakResolved && rules != null }
    // null until the first emission, like [rules]: the stat tiles show their skeleton rather
    // than a "0 blocked" that a moment later jumps to the real count.
    val attemptCounts by store.attemptLogDao().observeBlockedCounts()
        .collectAsStateWithLifecycle(initialValue = null)
    val countsByTarget = remember(attemptCounts) {
        attemptCounts?.associate { it.target to it.count }.orEmpty()
    }
    // Task #13: resolve rule labels off the main thread as the list lands — the 4000-rule
    // ceiling ANR'd on per-row PackageManager calls during composition. Rows render the raw
    // identifier until their label arrives in the shared cache.
    val labelContext = LocalContext.current
    LaunchedEffect(rules) {
        val targets = rules.orEmpty().map { it.blockedPackage }
        if (targets.isNotEmpty()) preloadAppLabels(labelContext, targets)
    }
    // Req 15 / #12: every partner's newest pinned message, newest first, as a Flow — a message
    // delivered by a background sync pass must reach a card that is already on screen, and the
    // card pages across senders. Names arrive from the shell (one roster read per Home entry).
    val pinnedRows by store.partnerActivityDao().observePinnedMessages()
        .collectAsStateWithLifecycle(initialValue = null)
    var pinnedIndex by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(pinnedRows?.size) {
        if (pinnedIndex > (pinnedRows?.size ?: 1) - 1) pinnedIndex = 0
    }

    // §8.7/D1's Home surface: the measured-interruption report, read off Main (the read can
    // open the SQLCipher store — the same rule the shell's own reads follow). One-shot on
    // entry: an interruption is always followed by a foreground entry, so nothing needs to
    // poll for it. #13: Dismiss persists the acknowledgement (so no surface re-raises this
    // evidence) and clears the card in place.
    var outage by remember { mutableStateOf<AccountingOutage?>(null) }
    LaunchedEffect(Unit) {
        outage = withContext(Dispatchers.IO) { outageSource.current() }
    }
    val scope = rememberCoroutineScope()

    // Live block state flips at schedule boundaries; a slow tick keeps the labels honest
    // while the screen stays open. Nothing else on this screen is time-derived.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    val streakDays = habit?.let { daysFree(it.currentStreakElapsedMs) }
    val nextUp = habit?.let { nextMilestone(it) }
    val splashDays = habit?.let { splashMilestones(it) }?.map { it.milestoneDay }.orEmpty()
    // SOS §8.14 (V1 Req 35): paired + notifications off.
    val checkInsAtRisk = partnerNames.isNotEmpty() && !partnerNotificationsEnabled

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // SOS §8.16 (Step 9): the partner's open reach-out is the one Home item with an
        // active ask, so it leads the column — above the milestone splash — until
        // acknowledged.
        nudgeCard?.let { current ->
            item(key = "nudge") {
                NudgeCardView(
                    card = current,
                    nowMillis = nowMillis,
                    onAcknowledge = onNudgeAcknowledge,
                )
            }
        }
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
                nowMillis = nowMillis,
                onOpenSettings = onOpenSettings,
            )
        }
        // SOS §8.14 (V1 Req 35): the check-ins banner below speaks for the same root cause
        // and the same fix as an NOTIFICATIONS_OFF degradation, so exactly one of the two
        // renders (never two stacked).
        degradation?.let { current ->
            if (!(current == ProtectionDegradation.NOTIFICATIONS_OFF && checkInsAtRisk)) {
                item(key = "degradation") { DegradationBanner(current, onReenable, onOpenSettings) }
            }
        }
        if (checkInsAtRisk) {
            item(key = "partner-checkins-off") {
                PartnerCheckInsNotificationsBanner(
                    requestable = partnerNotificationsRequestable,
                    onFix = onFixPartnerNotifications,
                )
            }
        }
        // D1: the outage report sits beside the degradation banner — both are statements about
        // protection, and the outage is the one the user can act on from here.
        outage?.let { current ->
            item(key = "outage") {
                OutageBanner(
                    outage = current,
                    onFix = onFixOutage,
                    onDismiss = {
                        scope.launch {
                            withContext(Dispatchers.IO) { outageSource.dismiss() }
                            outage = null
                        }
                    },
                )
            }
        }
        if (encryptionPending) {
            item(key = "encryption-pending") { EncryptionPendingNotice() }
        }
        // Req 15 arrival surface (#12): the newest pinned message, named for its sender and
        // paged across partners when several have sent one. Absent entirely when nothing has
        // arrived — an empty card would be noise, not encouragement.
        val pinned = pinnedRows.orEmpty()
        if (pinned.isNotEmpty()) {
            val pinnedShown = pinnedIndex.coerceIn(0, pinned.lastIndex)
            val current = pinned[pinnedShown]
            item(key = "pinned") {
                PinnedMessageCard(
                    text = current.pinnedMessageText.orEmpty(),
                    senderName = partnerNames[current.pairId],
                    sentAtMillis = current.pinnedMessageAtMs,
                    position = if (pinned.size > 1) pinnedShown + 1 to pinned.size else null,
                    nowMillis = nowMillis,
                    onPrev = { pinnedIndex = pinnedShown - 1 },
                    onNext = { pinnedIndex = pinnedShown + 1 },
                )
            }
        }
        // SOS §8.15 rev (owner mandate 2026-10-04): Reach out is now a shell-level FAB with a
        // confirmation dialog — it left this column; the flag/pair gate lives in AppShell.
        item(key = "streak") {
            StreakHeroCard(
                days = streakDays,
                habitLabel = habit?.targetLabel,
                freeSinceEpochMillis = habit?.recoveryStartAt,
                nextMilestoneDay = nextUp?.milestoneDay,
                daysToNext = habit?.let { state -> nextUp?.let { daysUntil(state, it.milestoneDay) } },
                streakResolved = streakResolved,
                onSwitchHabit = onSwitchHabit,
                onOpenStreaks = onOpenStreaks,
            )
        }
        item(key = "stats") {
            StatTrio(
                bestStreakDays = habit?.let { daysFree(it.allTimeMaxElapsedMs) },
                streakResolved = streakResolved,
                blocks = rules?.size,
                blocked = attemptCounts?.sumOf { it.count },
            )
        }
        // A permanent section, not a data-dependent guest: loading, empty, error, and success
        // each render their own card (product call 2026-09-29 — it used to pop in only after
        // every other read had landed, which read as a missing-then-late section).
        item(key = "milestones") {
            MilestoneSectionCard(
                habit = habit,
                streakResolved = streakResolved,
                loadFailed = streakLoadFailed,
                onRetry = onRetryStreak,
                onOpenStreaks = onOpenStreaks,
            )
        }
        item(key = "blocks") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.home_blocks_section),
                    style = MaterialTheme.typography.titleMedium,
                )
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
    nowMillis: Long,
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
            stringResource(R.string.protection_state_protecting),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primary,
        )
        ChipState.LIMITED -> ChipStyle(
            stringResource(R.string.protection_state_limited),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        ChipState.OFF -> ChipStyle(
            stringResource(R.string.protection_state_off),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.error,
        )
    }
    // The header from the reference: date line + state pill on one row, then a large greeting
    // as the page's anchor. Only the pill claims anything about protection, so a degradation
    // banner below can never contradict the title.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                remember(nowMillis) { formatHeaderDate(nowMillis) },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The pill is the entry point to the fix, so it never loses the row to a long
                // date (localized month names run well past "September").
                modifier = Modifier.weight(1f, fill = false),
            )
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
        Text(
            stringResource(greetingResId(hourOfDay(nowMillis))),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

/** Time-of-day only — a greeting is the one header element that can never be wrong about
 *  protection. Late hours read "Good evening" rather than "Good night": the latter is a
 *  farewell in English, not a greeting. */
internal fun greetingResId(hourOfDay: Int): Int = when (hourOfDay) {
    in 5..11 -> R.string.home_greeting_morning
    in 12..17 -> R.string.home_greeting_afternoon
    else -> R.string.home_greeting_evening
}

internal fun hourOfDay(epochMillis: Long): Int =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).hour

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
    freeSinceEpochMillis: Long?,
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
            // Streaks summary card). No habit yet → nothing to switch to, plain label. The row
            // itself is shared with Streaks (ADR #85) — these two cards render the same habit,
            // and when each owned its own copy only this one capped the label.
            HabitLabelRow(
                text = if (habitLabel != null) {
                    stringResource(R.string.streaks_days_free_from, habitLabel)
                } else {
                    stringResource(R.string.streaks_days_free)
                },
                onSwitch = if (habitLabel != null) onSwitchHabit else null,
            )
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
            if (freeSinceEpochMillis != null) {
                Spacer(Modifier.height(6.dp))
                FreeSinceLine(freeSinceEpochMillis)
            }
            Spacer(Modifier.height(4.dp))
            Text(caption, style = MaterialTheme.typography.bodyMedium)
            if (days == null && !loading) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenStreaks) { Text(stringResource(R.string.home_streak_cta)) }
            }
        }
    }
}

/**
 * Three real counts, no invented deltas: the reference's percentage chips need history this app
 * does not keep, so the tiles carry values only. Each waits for its own source's first emission
 * (skeleton in the value slot, the same treatment the blocks card uses) instead of flashing a
 * zero. Best streak is absent — not zeroed — while no habit exists to have a best.
 */
@Composable
private fun StatTrio(
    bestStreakDays: Int?,
    streakResolved: Boolean,
    blocks: Int?,
    blocked: Int?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Rendered while unresolved (skeleton) and once resolved with a real best; absent only
        // when the streak is resolved and there is no habit to have a best.
        if (bestStreakDays != null || !streakResolved) {
            StatTile(
                label = stringResource(R.string.home_stat_best_streak),
                value = bestStreakDays,
                icon = Icons.Outlined.EmojiEvents,
                modifier = Modifier.weight(1f),
            )
        }
        StatTile(
            label = stringResource(R.string.home_stat_blocks),
            value = blocks,
            icon = Icons.Outlined.Shield,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.home_stat_blocked),
            value = blocked,
            icon = Icons.Outlined.Block,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: Int?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    // A null value IS the loading state: no source has emitted yet. Callers only pass null
    // while waiting, so a tile can never show a skeleton over a value it already holds.
    val loading = value == null
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            // Fixed slot: the skeleton and the value occupy the same height, so resolving
            // never reflows the row.
            Box(Modifier.height(30.dp), contentAlignment = Alignment.BottomStart) {
                if (loading) {
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(18.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                MaterialTheme.shapes.small,
                            ),
                    )
                } else if (value != null) {
                    Text(
                        value.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            // A two-line slot keeps the trio uniform: "Times blocked" wraps at compact widths
            // (device-caught 2026-10-14 — a single line clipped it mid-word); heightIn so
            // accessibility font scales can still grow the tile instead of cutting the label.
            Box(Modifier.heightIn(min = 32.dp), contentAlignment = Alignment.TopStart) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun BlocksCard(
    rules: List<RestrictionRule>?,
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
        if (rules == null) {
            Box(
                Modifier.padding(16.dp).fillMaxWidth().height(40.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        MaterialTheme.shapes.small,
                    ),
            )
        } else if (rules.isEmpty()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.home_blocks_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.home_blocks_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenRules) { Text(stringResource(R.string.home_blocks_add)) }
            }
        } else {
            // Task #13: lazy, not an eager Column — only visible rows compose, so the list
            // scales to the 4000-rule ceiling without one giant main-thread pass. The height
            // cap is required: this card is an item of Home's outer LazyColumn, so an
            // unbounded inner list would be measured with infinity constraints and crash.
            LazyColumn(modifier = Modifier.heightIn(max = RULES_CARD_MAX_HEIGHT)) {
                itemsIndexed(rules, key = { _, rule -> rule.ruleId }) { index, rule ->
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
        // The real app icon, same mark the Rules rows use (owner 2026-10-04: the two lists
        // read as different designs while this was a generic glyph).
        AppMark(
            packageName = if (rule.targetType == "WEBSITE") null else rule.blockedPackage,
            fallback = if (rule.targetType == "WEBSITE") Icons.Default.Language else Icons.Default.Apps,
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
/**
 * Req 15: the partner's pinned message, rendered verbatim (partner-authored text is content,
 * never a template). #12: the header names the sender when the roster resolves one — the
 * generic title covers the moments before names load and a label-less pairing — and with
 * several senders the arrows page newest-to-oldest. The age line is the message's own send
 * time — same honesty rule the dashboard follows, a stale message must not read as if it
 * just arrived.
 */
@Composable
private fun PinnedMessageCard(
    text: String,
    senderName: String?,
    sentAtMillis: Long?,
    position: Pair<Int, Int>?,
    nowMillis: Long,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (senderName != null) {
                        stringResource(R.string.home_pinned_from, senderName)
                    } else {
                        stringResource(R.string.home_pinned_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (position != null) {
                    val (index, count) = position
                    IconButton(onClick = onPrev, enabled = index > 1) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.home_pinned_prev),
                        )
                    }
                    Text(
                        stringResource(R.string.home_pinned_position, index, count),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    IconButton(onClick = onNext, enabled = index < count) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.home_pinned_next),
                        )
                    }
                }
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
            sentAtMillis?.let { sent ->
                Text(
                    stringResource(R.string.home_pinned_sent, relativeTimeText(nowMillis, sent)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

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
            Text(
                stringResource(R.string.encryption_pending_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.encryption_pending_home_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * D1's Home surface (§8.7): what was measured — how long tracking went unobserved — and never
 * a cause (AD-13). The Fix action exists only while the exemption offer is live
 * ([AccountingOutage.promptRoute] non-null); a report with no offer is still worth showing,
 * because the user's limits really are missing that time. #13: every variant is dismissible —
 * the acknowledgement persists ([AccountingOutageSource.dismiss]) so the report never returns
 * for the same evidence — and [AccountingOutage.trackingResumed] renders the one-time
 * confirmation that the fired fix is in effect instead of the alert.
 */
@Composable
private fun OutageBanner(outage: AccountingOutage, onFix: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (outage.trackingResumed) {
                // AD-13: the setting, not a cause — the app never claims what interrupted
                // tracking, only that the user's fix is in effect and can run again.
                Text(
                    stringResource(R.string.home_outage_resolved_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.home_outage_resolved_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.outage_action_dismiss))
                }
            } else {
                Text(
                    stringResource(R.string.home_outage_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    pluralStringResource(
                        R.plurals.home_outage_body,
                        outage.summary.count,
                        outage.summary.count,
                        formatDuration(outage.summary.largestWindowMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (outage.promptRoute != null) {
                        Button(onClick = onFix) { Text(stringResource(R.string.outage_action_fix)) }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.outage_action_dismiss))
                    }
                }
            }
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
            stringResource(R.string.protection_paused_card_text),
            stringResource(R.string.protection_paused_action),
            onOpenSettings,
        )
        // The old copy claimed blocking was off while Path A (a11y) is typically still
        // live after a Task-Manager stop — can't claim that, and can't claim "everything
        // is fine": the poll path (time limits + fallback detection) stays off until
        // re-enable. Name only the always-true loss (regression finding #1, 2026-09-16);
        // the stop's cause is never named (which shapes it counts — see the string).
        ProtectionDegradation.APP_STOPPED -> Triple(
            stringResource(R.string.protection_stopped_text),
            stringResource(R.string.protection_stopped_action),
            onReenable,
        )
        ProtectionDegradation.NOTIFICATIONS_OFF -> Triple(
            stringResource(R.string.protection_notif_off_banner_text),
            stringResource(R.string.protection_notif_off_banner_action),
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

/** SOS §8.14 (V1 Req 35): a pair is linked and notifications are off — the plan's copy with
 *  the working fix path (the system dialog while it can still be raised, else the app's
 *  notification settings). Same surface grammar as [DegradationBanner]. */
@Composable
private fun PartnerCheckInsNotificationsBanner(requestable: Boolean, onFix: () -> Unit) {
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
            Text(
                stringResource(R.string.partner_notifications_checkins_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onFix) {
                Text(
                    stringResource(
                        if (requestable) R.string.partner_notifications_enable
                        else R.string.partner_notifications_open_settings,
                    ),
                )
            }
        }
    }
}

/**
 * SOS §8.16 (V1 Req 35, Step 9): the recipient's card for the newest unacknowledged
 * reach-out. Both actions acknowledge — §6.9's batch covers every fetched pending nudge and
 * the tray entry is cancelled after it commits (repository); the card then clears through
 * the listener. The sender's message renders when one was written (v2 envelope); a v1 nudge
 * has none and the card simply shows the title — it never implies a message was written.
 */
@Composable
private fun NudgeCardView(
    card: NudgeInboxRepository.NudgeCard,
    nowMillis: Long,
    onAcknowledge: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sos_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onAcknowledge) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.sos_card_dismiss),
                    )
                }
            }
            card.message?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                stringResource(R.string.sos_card_sent, relativeTimeText(nowMillis, card.createdAtMs)),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onAcknowledge) {
                Text(stringResource(R.string.sos_card_im_here))
            }
        }
    }
}
