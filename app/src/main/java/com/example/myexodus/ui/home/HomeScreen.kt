package com.example.myexodus.ui.home

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.example.myexodus.settings.HomeSettings
import com.example.myexodus.settings.accessibilitySettingsIntent
import com.example.myexodus.settings.batteryOptimizationSettingsIntent
import com.example.myexodus.settings.isBatteryExempt
import com.example.myexodus.settings.overlaySettingsIntent
import com.example.myexodus.settings.usageAccessSettingsIntent
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.ui.AppMark
import com.example.myexodus.ui.RuleRowState
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.formatDuration
import com.example.myexodus.ui.formatHeaderDate
import com.example.myexodus.ui.preloadAppLabels
import com.example.myexodus.ui.relativeTimeText
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberInstalledAppPackages
import com.example.myexodus.ui.rememberWindowWidth
import com.example.myexodus.ui.ruleRowState
import com.example.myexodus.ui.rules.RULES_CARD_MAX_HEIGHT
import com.example.myexodus.ui.rules.hasLiveWebsiteRule
import com.example.myexodus.ui.scheduleSummary
import com.example.myexodus.ui.streaks.FreeSinceLine
import com.example.myexodus.ui.streaks.HabitLabelRow
import com.example.myexodus.ui.streaks.MilestoneReachedDialog
import com.example.myexodus.ui.streaks.daysFree
import com.example.myexodus.ui.streaks.daysUntil
import com.example.myexodus.ui.streaks.nextMilestone
import com.example.myexodus.ui.streaks.splashMilestones
import com.example.myexodus.ui.theme.DisplayFamily
import com.example.myexodus.ui.visibleRules
import com.example.myexodus.vpn.VpnLifecycle
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
    /** Owner request 2026-10-17: the "Your blocks" privacy toggle's persisted state. */
    homeSettings: HomeSettings,
    monitor: EnhancedModeHealthMonitor,
    degradation: ProtectionDegradation?,
    authority: ProtectionAuthority?,
    outageSource: AccountingOutageSource,
    onReenable: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
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
     *  X acknowledges through the one callback (§6.9); the card offers no reply (owner
     *  ruling 2026-11-01 — it only names who needs the check-in). */
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
    // Owner ruling 2026-11-01: an uninstalled app's block is hidden from the list and the
    // counts, but stored and still active — it returns with the app.
    val installedPackages = rememberInstalledAppPackages()
    val shownRules = rules?.let { visibleRules(it, installedPackages) }
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
    LaunchedEffect(shownRules) {
        val targets = shownRules.orEmpty().map { it.blockedPackage }
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

    // Owner correction 2026-10-21: accessibility is not the only blocking-essential grant.
    // Usage access, display-over-apps and the website tunnel are re-read with the 30 s tick,
    // so a grant revoked (or restored) in system settings lands on this screen by itself.
    var readiness by remember { mutableStateOf<BlockingReadiness?>(null) }
    // Owner report 2026-11-09: Settings read the battery row red while Home still showed
    // PROTECTING with no row. Battery is a reliability gap, not a blocking prerequisite
    // (BlockingReadiness), so it never moves the chip — it joins the attention group instead.
    var batteryExempt by remember { mutableStateOf<Boolean?>(null) }
    // Owner ease-of-use rule 2026-10-23: a grant toggled in system settings must land the
    // moment the user comes back, not on the next 30 s tick — the user has just acted and is
    // looking for the result. ON_RESUME re-runs the same read with fresh captured state.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    LaunchedEffect(nowMillis, rules, resumeTick) {
        readiness = withContext(Dispatchers.IO) {
            readBlockingReadiness(labelContext, hasLiveWebsiteRule(rules.orEmpty(), nowMillis))
        }
        batteryExempt = withContext(Dispatchers.IO) { isBatteryExempt(labelContext) }
    }

    // Owner correction 2026-10-21: "Limited" without a cause made the user tap through to
    // Settings to learn what was down. The explainer waits out the accessibility-rebind window
    // after a process start (XOS measures 20–40 s) so a transient rebind never flashes a card;
    // a genuinely down service gets the same cause + Re-enable grammar as the other rows.
    // Owner report 2026-10-23: after an update/force-stop the enabled list was empty, so no
    // rebind could ever complete — the wait showed "Limited" with no cause for a full minute.
    // An absent list entry, or a cleared accessibility master switch, is definitive and skips
    // the wait (see pollingOnlySettled).
    var pollingOnlyPersistent by remember { mutableStateOf(false) }
    LaunchedEffect(authority, degradation, readiness?.accessibilityEnabledByPlatform) {
        val pollingOnly =
            authority == ProtectionAuthority.DEGRADED_POLLING_ONLY && degradation == null
        val waitMs = POLLING_ONLY_EXPLAINER_DELAY_MS -
            (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
        val settled = pollingOnlySettled(
            authority = authority,
            degradation = degradation,
            accessibilityEnabledByPlatform = readiness?.accessibilityEnabledByPlatform,
            rebindWindowElapsed = waitMs <= 0,
        )
        pollingOnlyPersistent = settled
        if (pollingOnly && !settled) {
            delay(waitMs)
            pollingOnlyPersistent = true
        }
    }
    // The consent dialog must be launched for result: a plain start makes Android's dialog
    // report a null caller and finish without rendering (device-found 2026-10-17, RulesScreen).
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        VpnLifecycle.ensure(labelContext.applicationContext, hasRules = true)
        scope.launch {
            readiness = withContext(Dispatchers.IO) {
                readBlockingReadiness(labelContext, hasLiveWebsiteRule(rules.orEmpty(), nowMillis))
            }
        }
    }
    val requestWebsiteTunnel: () -> Unit = {
        VpnLifecycle.consentIntent(labelContext)?.let(vpnConsentLauncher::launch)
            ?: VpnLifecycle.ensure(labelContext.applicationContext, hasRules = true)
    }
    // LIMITED can now mean any of four gaps; the chip opens the gap that is actually down.
    // Polling-only names accessibility as its defining gap; otherwise the missing grant is
    // checked in consequence order, with accessibility last as the remaining cause.
    val limitedFix: () -> Unit = when {
        authority == ProtectionAuthority.DEGRADED_POLLING_ONLY -> {
            { labelContext.startActivity(accessibilitySettingsIntent()) }
        }
        readiness?.usageAccess == false -> {
            { labelContext.startActivity(usageAccessSettingsIntent()) }
        }
        readiness?.overlayGranted == false -> {
            { labelContext.startActivity(overlaySettingsIntent(labelContext)) }
        }
        readiness?.websiteConsentMissing == true -> requestWebsiteTunnel
        else -> { { labelContext.startActivity(accessibilitySettingsIntent()) } }
    }

    val streakDays = habit?.let { daysFree(it.currentStreakElapsedMs) }
    val nextUp = habit?.let { nextMilestone(it) }
    val splashDays = habit?.let { splashMilestones(it) }?.map { it.milestoneDay }.orEmpty()
    // SOS §8.14 (V1 Req 35): paired + notifications off.
    val checkInsAtRisk = partnerNames.isNotEmpty() && !partnerNotificationsEnabled
    // Owner request 2026-10-17: hidden across launches, because the whole point is that a
    // glance at the phone does not disclose the blocked apps and sites.
    var blocksHidden by remember { mutableStateOf(homeSettings.blocksHidden) }

    val listState = rememberLazyListState()
    RevealPrependedCard(cardPresent = nudgeCard != null, listState = listState)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // Bottom clears the shell's always-present Reach out FAB (56dp + its 16dp inset and
        // margin): the last card must never end up underneath it (device-caught 2026-11-01).
        contentPadding = PaddingValues(
            start = sidePadding,
            top = 16.dp,
            end = sidePadding,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // SOS §8.16 (Step 9): the partner's open reach-out is the one Home item with an
        // active ask, so it leads the column — above the milestone splash — until
        // acknowledged.
        nudgeCard?.let { current ->
            item(key = "nudge") {
                NudgeCardView(
                    card = current,
                    partnerName = partnerNames[current.pairId],
                    nowMillis = nowMillis,
                    onAcknowledge = onNudgeAcknowledge,
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
                onLimitedFix = limitedFix,
                readiness = readiness,
            )
        }
        // Owner directive 2026-10-21: the attention alerts are one calm group, not three
        // stacked full-width banners. Each alert only appears when it has something to say,
        // and the group disappears entirely for a healthy install.
        item(key = "attention") {
            AttentionSection(
                degradation = degradation,
                authority = authority,
                pollingOnlyPersistent = pollingOnlyPersistent,
                readiness = readiness,
                batteryNeedsFix = batteryExempt == false,
                checkInsAtRisk = checkInsAtRisk,
                partnerNotificationsRequestable = partnerNotificationsRequestable,
                outage = outage,
                onFixWebsite = requestWebsiteTunnel,
                onReenable = onReenable,
                onOpenSettings = onOpenSettings,
                onFixPartnerNotifications = onFixPartnerNotifications,
                onFixOutage = onFixOutage,
                onDismissOutage = {
                    scope.launch {
                        withContext(Dispatchers.IO) { outageSource.dismiss() }
                        outage = null
                    }
                },
            )
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
                bestDays = habit?.let { daysFree(it.allTimeMaxElapsedMs) },
                habitLabel = habit?.targetLabel,
                freeSinceEpochMillis = habit?.recoveryStartAt,
                nextMilestoneDay = nextUp?.milestoneDay,
                daysToNext = habit?.let { state -> nextUp?.let { daysUntil(state, it.milestoneDay) } },
                streakResolved = streakResolved,
                onSwitchHabit = onSwitchHabit,
                onOpenStreaks = onOpenStreaks,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.home_blocks_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            blocksHidden = !blocksHidden
                            homeSettings.blocksHidden = blocksHidden
                        },
                    ) {
                        Icon(
                            imageVector = if (blocksHidden) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = stringResource(
                                if (blocksHidden) R.string.home_blocks_show else R.string.home_blocks_hide,
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (blocksHidden) {
                    HiddenBlocksCard()
                } else {
                    // Owner UX report 2026-10-22: these two counts used to sit beside "Best
                    // streak" as one trio, so three different scopes read as one. Block facts
                    // now live inside the section that owns them, and the scope line says so
                    // before the values are read. Zero blocks renders the empty card alone —
                    // a 0/0 tile row above "No blocks yet" would be noise, and history is
                    // still on Insights.
                    if (!shownRules.isNullOrEmpty()) {
                        Text(
                            stringResource(R.string.home_blocks_scope),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        BlockStatsRow(
                            blocks = shownRules.size,
                            blocked = attemptCounts?.sumOf { it.count },
                        )
                    }
                    BlocksCard(
                        rules = shownRules,
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
    // REQ-12's guaranteed delivery path, now a modal (owner directive 2026-10-21): the
    // milestone takes the screen for one dismiss-and-continue beat instead of occupying a
    // permanent slot above the day hero. Same acknowledgement write either way.
    if (splashDays.isNotEmpty() && habit != null) {
        MilestoneReachedDialog(
            milestoneDays = splashDays,
            achievedDays = habit.milestones
                .filter { it.achievedAt != null }
                .map { it.milestoneDay }
                .toSet(),
            habitLabel = habit.targetLabel,
            onAcknowledge = { onAcknowledgeMilestone(splashDays) },
        )
    }
}

private enum class ChipState { PROTECTING, LIMITED, OFF }

/** Longest observed accessibility rebind after a process start (XOS measures 20–40 s); the
 *  Limited explainer waits this long before treating polling-only as worth surfacing. */
private const val POLLING_ONLY_EXPLAINER_DELAY_MS = 60_000L

@Composable
private fun StatusHeader(
    serviceEnabled: Boolean,
    degradation: ProtectionDegradation?,
    authority: ProtectionAuthority?,
    readiness: BlockingReadiness?,
    nowMillis: Long,
    onOpenSettings: () -> Unit,
    /** Owner correction 2026-10-21: a limited pill must lead to the grant that is actually
     *  down, not the Settings index and not a fixed permission. */
    onLimitedFix: () -> Unit,
) {
    // One signal authority with the banner below (finding #13): whenever a degradation banner
    // is showing, the chip must not claim "Protecting". Everything below the pre-first-refresh
    // window (authority == null) reads the same tri-state the monitor publishes. FULL only
    // means the detection path; a missing blocking-essential grant still reads LIMITED
    // (owner correction 2026-10-21).
    val chip = when {
        degradation != null -> ChipState.OFF
        limitedBeyondAuthority(authority, readiness) -> ChipState.LIMITED
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
                // The chip is the entry point to the fix. LIMITED opens the one grant that
                // is verified down; the other degraded states can have several causes and
                // land on Settings, whose Protection row carries each mechanism's own remedy.
                modifier = Modifier.clickable(role = Role.Button) {
                    if (chip == ChipState.LIMITED) onLimitedFix() else onOpenSettings()
                },
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
    bestDays: Int?,
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
        loading -> stringResource(R.string.home_streak_caption_loading)
        days == null -> stringResource(R.string.home_streak_caption_none)
        nextMilestoneDay != null && daysToNext != null ->
            pluralStringResource(R.plurals.home_streak_caption_next, daysToNext, nextMilestoneDay, daysToNext)
        else -> stringResource(R.string.home_streak_caption_complete)
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
            // loading shows a skeleton bar; with no habit the slot previews "Day 1" — the value
            // the first day will read — instead of leaving a blank gap above the caption
            // (owner directive 2026-11-07). Never a stray em-dash glyph that reads as glitched.
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
                            pluralStringResource(R.plurals.home_streak_days_unit, days),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                    else -> Text(
                        stringResource(R.string.home_streak_empty_hero, 1),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                    )
                }
            }
            if (freeSinceEpochMillis != null) {
                Spacer(Modifier.height(6.dp))
                FreeSinceLine(freeSinceEpochMillis)
            }
            Spacer(Modifier.height(4.dp))
            Text(caption, style = MaterialTheme.typography.bodyMedium)
            // Habit-scoped like the run above it, and hidden when it equals the run — the same
            // rule the Streaks card uses, so Home never states the same number twice.
            if (bestDays != null && days != null && bestDays > days) {
                Spacer(Modifier.height(4.dp))
                Text(
                    pluralStringResource(R.plurals.streaks_best_run, bestDays, bestDays),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            if (days == null && !loading) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenStreaks) { Text(stringResource(R.string.home_streak_cta)) }
            }
        }
    }
}

/**
 * The two rule-scoped counts, rendered inside the "Your blocks" section so their owner is the
 * section itself. Values only — the reference's percentage chips need history this app does
 * not keep. A null count is a source that has not emitted yet: skeleton, never a zero flash.
 */
@Composable
private fun BlockStatsRow(blocks: Int, blocked: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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

/** The hidden state keeps the section's footprint and states why it is empty; the header's eye
 *  is the way back. */
@Composable
private fun HiddenBlocksCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.home_blocks_hidden),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            append(res.getQuantityString(R.plurals.home_block_row_attempts, attempts, attempts))
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

/**
 * Req 15: the partner's pinned message, rendered verbatim (partner-authored text is content,
 * never a template). #12: the header names the sender when the roster resolves one — the
 * generic title covers the moments before names load and a label-less pairing — and with
 * several senders the arrows page newest-to-oldest. The age line is the message's own send
 * time — same honesty rule the dashboard follows, a stale message must not read as if it
 * just arrived.
 *
 * Redesigned 2026-11-09 (owner: the previous bubble-in-a-beige-box read as the ugliest card
 * on Home). The note is a parchment artifact painted from the scheme's tertiary container, a
 * teal monogram disc naming the sender, and the words set in Fraunces as an editorial quote —
 * no nested bubble. The first pass inverted the card against the page; the owner ruled the
 * ink block did not complement Desert Dawn, so the card now sits inside the palette. ADR
 * #255's grammar still holds (masthead, authored words, footer pager), and the age keeps its
 * honesty rule.
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
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        // The monogram repeats the name beside it; screen readers skip it.
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    val initial = senderName?.trim()?.firstOrNull()?.uppercaseChar()?.toString()
                    if (initial != null) {
                        Text(
                            initial,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.Chat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        if (senderName != null) {
                            stringResource(R.string.home_pinned_from, senderName)
                        } else {
                            stringResource(R.string.home_pinned_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    sentAtMillis?.let {
                        Text(
                            stringResource(R.string.home_pinned_sent, relativeTimeText(nowMillis, it)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
                Text(
                    "“",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFamily),
            )
            if (position != null) {
                val (index, count) = position
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onPrev, enabled = index > 1) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.home_pinned_prev),
                        )
                    }
                    Text(
                        stringResource(R.string.home_pinned_position, index, count),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    )
                    IconButton(onClick = onNext, enabled = index < count) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.home_pinned_next),
                        )
                    }
                }
            }
        }
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
/** One row of the Home attention group. */
private data class AttentionItem(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val dismissLabel: String? = null,
    val onDismiss: (() -> Unit)? = null,
    val resolved: Boolean = false,
)

/**
 * Owner directive 2026-10-21: the protection alerts share one quiet surface and one visual
 * grammar instead of stacking full-width banners, each with its own loud button. Rows stay
 * ordered by consequence, every row keeps its own cause + action (the treatment findings
 * #4/#13 locked in), and the section renders nothing at all on a healthy install — these
 * alerts are occasional, not furniture.
 *
 * SOS §8.14: the partner check-ins row speaks for the same root cause and the same fix as a
 * NOTIFICATIONS_OFF degradation, so exactly one of the two renders (never two stacked).
 */
@Composable
private fun AttentionSection(
    degradation: ProtectionDegradation?,
    authority: ProtectionAuthority?,
    pollingOnlyPersistent: Boolean,
    readiness: BlockingReadiness?,
    batteryNeedsFix: Boolean,
    checkInsAtRisk: Boolean,
    partnerNotificationsRequestable: Boolean,
    outage: AccountingOutage?,
    onFixWebsite: () -> Unit,
    onReenable: () -> Unit,
    onOpenSettings: () -> Unit,
    onFixPartnerNotifications: () -> Unit,
    onFixOutage: () -> Unit,
    onDismissOutage: () -> Unit,
) {
    val context = LocalContext.current
    val items = buildList {
        degradation?.let { current ->
            if (!(current == ProtectionDegradation.NOTIFICATIONS_OFF && checkInsAtRisk)) {
                add(
                    when (current) {
                        // §8 Step 6: cause not named — with both paths down it is usually
                        // more than one thing; Settings names each mechanism and its fix.
                        ProtectionDegradation.DEGRADED_NO_DETECTION -> AttentionItem(
                            icon = Icons.Outlined.Shield,
                            title = stringResource(R.string.protection_paused_title),
                            body = stringResource(R.string.protection_paused_card_text),
                            actionLabel = stringResource(R.string.protection_paused_action),
                            onAction = onOpenSettings,
                        )
                        // Name only the always-true loss (regression finding #1, 2026-09-16);
                        // the stop's cause is never named.
                        ProtectionDegradation.APP_STOPPED -> AttentionItem(
                            icon = Icons.Outlined.Shield,
                            title = stringResource(R.string.protection_stopped_title),
                            body = stringResource(R.string.protection_stopped_text),
                            actionLabel = stringResource(R.string.protection_stopped_action),
                            onAction = onReenable,
                        )
                        ProtectionDegradation.NOTIFICATIONS_OFF -> AttentionItem(
                            icon = Icons.Outlined.NotificationsOff,
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
                    },
                )
            }
        }
        if (explainPollingOnly(authority, degradation, pollingOnlyPersistent)) {
            add(
                AttentionItem(
                    icon = Icons.Outlined.Shield,
                    title = stringResource(R.string.protection_limited_title),
                    body = stringResource(R.string.protection_limited_card_text),
                    actionLabel = stringResource(R.string.protection_stopped_action),
                    onAction = { context.startActivity(accessibilitySettingsIntent()) },
                ),
            )
        }
        // Owner correction 2026-10-21: accessibility is one blocking-essential grant, not the
        // only one. Each missing grant gets its own cause + direct fix; on a healthy install
        // these add nothing.
        if (showOverlayFix(authority, readiness)) {
            add(
                AttentionItem(
                    icon = Icons.Outlined.Block,
                    title = stringResource(R.string.protection_overlay_off_title),
                    body = stringResource(R.string.protection_overlay_off_text),
                    actionLabel = stringResource(R.string.protection_limited_action),
                    onAction = { context.startActivity(overlaySettingsIntent(context)) },
                ),
            )
        }
        if (showUsageFix(authority, readiness)) {
            add(
                AttentionItem(
                    icon = Icons.Outlined.Visibility,
                    title = stringResource(R.string.protection_usage_off_title),
                    body = stringResource(R.string.protection_usage_off_text),
                    actionLabel = stringResource(R.string.protection_limited_action),
                    onAction = { context.startActivity(usageAccessSettingsIntent()) },
                ),
            )
        }
        if (readiness?.websiteConsentMissing == true) {
            add(
                AttentionItem(
                    icon = Icons.Filled.Language,
                    title = stringResource(R.string.protection_website_off_title),
                    body = stringResource(R.string.protection_website_off_text),
                    actionLabel = stringResource(R.string.protection_limited_action),
                    onAction = onFixWebsite,
                ),
            )
        }
        // Owner report 2026-11-09: Settings showed this red while Home read PROTECTING. The
        // action opens the battery-optimization list, where the user still makes the change;
        // the direct exemption request stays outage-prompt-only (§8.7/D1).
        if (batteryNeedsFix) {
            add(
                AttentionItem(
                    icon = Icons.Outlined.BatteryAlert,
                    title = stringResource(R.string.home_battery_title),
                    body = stringResource(R.string.home_battery_body),
                    actionLabel = stringResource(R.string.settings_action_fix),
                    onAction = { context.startActivity(batteryOptimizationSettingsIntent()) },
                ),
            )
        }
        if (checkInsAtRisk) {
            add(
                AttentionItem(
                    icon = Icons.Outlined.NotificationsOff,
                    title = stringResource(R.string.partner_notifications_off_title),
                    body = stringResource(R.string.partner_notifications_checkins_body),
                    actionLabel = stringResource(
                        if (partnerNotificationsRequestable) {
                            R.string.partner_notifications_enable
                        } else {
                            R.string.partner_notifications_open_settings
                        },
                    ),
                    onAction = onFixPartnerNotifications,
                ),
            )
        }
        outage?.let { current ->
            if (current.trackingResumed) {
                // AD-13: the setting, not a cause — the app never claims what interrupted
                // tracking, only that the user's fix is in effect and can run again.
                add(
                    AttentionItem(
                        icon = Icons.Outlined.CheckCircle,
                        title = stringResource(R.string.home_outage_resolved_title),
                        body = stringResource(R.string.home_outage_resolved_body),
                        dismissLabel = stringResource(R.string.outage_action_dismiss),
                        onDismiss = onDismissOutage,
                        resolved = true,
                    ),
                )
            } else {
                add(
                    AttentionItem(
                        icon = Icons.Outlined.Schedule,
                        title = stringResource(R.string.home_outage_title),
                        body = pluralStringResource(
                            R.plurals.home_outage_body,
                            current.summary.count,
                            current.summary.count,
                            formatDuration(current.summary.largestWindowMs),
                        ),
                        actionLabel = current.promptRoute
                            ?.let { stringResource(R.string.outage_action_fix) },
                        onAction = onFixOutage.takeIf { current.promptRoute != null },
                        dismissLabel = stringResource(R.string.outage_action_dismiss),
                        onDismiss = onDismissOutage,
                    ),
                )
            }
        }
    }
    if (items.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                stringResource(R.string.home_attention_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 2.dp),
            )
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                AttentionRow(item)
            }
        }
    }
}

/**
 * Whether the Limited chip gets its cause-and-fix row. Polling-only is a supported running
 * state (ProtectionStatusMonitor explains why it raises no alert), and the accessibility
 * rebind after a process start is brief — so the row waits until the state has outlived that
 * window. Any visible degradation outranks it: that state has its own row and reads OFF.
 */
internal fun explainPollingOnly(
    authority: ProtectionAuthority?,
    degradation: ProtectionDegradation?,
    persistent: Boolean,
): Boolean = persistent && degradation == null &&
    authority == ProtectionAuthority.DEGRADED_POLLING_ONLY

/**
 * Whether polling-only has stopped being explainable as a rebind. The platform state
 * ([com.example.myexodus.settings.isBlockerAccessibilityEnabled]: enabled-list entry plus the
 * non-cleared master switch) is only used here, as the negative signal: when the platform does
 * not say enabled, the service cannot bind, so waiting can only hide the cause (owner report
 * 2026-10-23 — a full minute of "Limited" with nothing to act on). When it does say enabled,
 * the rebind window applies.
 */
internal fun pollingOnlySettled(
    authority: ProtectionAuthority?,
    degradation: ProtectionDegradation?,
    accessibilityEnabledByPlatform: Boolean?,
    rebindWindowElapsed: Boolean,
): Boolean = authority == ProtectionAuthority.DEGRADED_POLLING_ONLY &&
    degradation == null &&
    (accessibilityEnabledByPlatform == false || rebindWindowElapsed)

@Composable
private fun AttentionRow(item: AttentionItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = if (item.resolved) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            Text(
                item.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.actionLabel != null || item.dismissLabel != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.actionLabel != null && item.onAction != null) {
                        TextButton(
                            onClick = item.onAction,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(item.actionLabel, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (item.dismissLabel != null && item.onDismiss != null) {
                        TextButton(
                            onClick = item.onDismiss,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                item.dismissLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A pending reach-out is prepended at index 0. The keyed list keeps the row the user was
 * looking at pinned, so a card that arrives while Home is open would sit above the viewport
 * and never be seen (device-found on the two-AVD rig, 2026-11-01). Reveal it once the
 * prepend has re-anchored; the card's active ask leads Home until acknowledged.
 */
@Composable
internal fun RevealPrependedCard(cardPresent: Boolean, listState: LazyListState) {
    LaunchedEffect(cardPresent) {
        if (!cardPresent) return@LaunchedEffect
        withFrameNanos { }
        if (listState.firstVisibleItemIndex > 0) listState.animateScrollToItem(0)
    }
}

/**
 * SOS §8.16 (V1 Req 35, Step 9): the recipient's card for the newest unacknowledged
 * reach-out. Owner ruling 2026-11-01: the reach-out is a one-way signal, not an exchange —
 * it names who needs the check-in so the partner can reach them by call or message, and
 * offers no answer. X dismisses it (§6.9's batch covers every fetched pending nudge; the
 * tray entry is cancelled after it commits) and the card clears through the listener. The
 * sender's message renders when one was written (v2 envelope); a v1 nudge has none and the
 * card shows only the title — it never implies a message was written.
 *
 * Redesigned 2026-11-09 (owner: the boxed-icon masthead and nested bubble read as slop).
 * It now shares [PinnedMessageCard]'s editorial family: a monogram disc naming the sender,
 * the ask and its honest send time, and the partner's words set directly on the surface in
 * the display face — no nested bubble, no glyph tile. The teal container keeps it the
 * strongest surface on Home while the parchment card stays visually its own event.
 */
@Composable
private fun NudgeCardView(
    card: NudgeInboxRepository.NudgeCard,
    partnerName: String?,
    nowMillis: Long,
    onAcknowledge: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        // The monogram repeats the name beside it; screen readers skip it.
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    val initial = partnerName?.trim()?.firstOrNull()?.uppercaseChar()?.toString()
                    if (initial != null) {
                        Text(
                            initial,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Handshake,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        partnerName?.let { stringResource(R.string.sos_card_title_named, it) }
                            ?: stringResource(R.string.sos_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(
                            R.string.sos_card_sent,
                            relativeTimeText(nowMillis, card.createdAtMs),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                IconButton(onClick = onAcknowledge) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.sos_card_dismiss),
                    )
                }
            }
            card.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFamily),
                )
            }
        }
    }
}
