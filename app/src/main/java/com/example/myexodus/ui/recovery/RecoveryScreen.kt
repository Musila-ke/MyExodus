package com.example.myexodus.ui.recovery

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myexodus.R
import com.example.myexodus.blocking.BlockDecisionEngine
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.recovery.RecoveryCountdown
import com.example.myexodus.blocking.recovery.RecoveryCountdownTracker
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.blocking.recovery.RecoveryTimer
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryState
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.recovery.RecoveryPlanRepository
import com.example.myexodus.recovery.RecoveryResourceCatalog
import com.example.myexodus.recovery.RecoverySessionRepository
import com.example.myexodus.recovery.RecoveryTechnique
import com.example.myexodus.recovery.UrgeCatalog
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.SecureSurface
import com.example.myexodus.ui.StatusPill
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberWindowWidth
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exposed for the post-lapse sheet's "Get help now" (plan §E2.12). */
internal const val RECOVERY_VIEW_CRISIS = "crisis"
private const val VIEW_TECHNIQUES = "techniques"
private const val VIEW_PLAN = "plan"
private const val VIEW_PLAN_EDIT = "plan_edit"
private const val VIEW_PREFS = "prefs"
private const val VIEW_RISK_WINDOWS = "risk_windows"
private const val VIEW_FRICTION_TIMER = "friction_timer"

/** Assist entry points outside this tab (block screen, Home's action dial, widget) request it. */
internal const val RECOVERY_VIEW_ASSIST = "assist"

/**
 * Recovery tab — the app-side surface of the shipped friction session (V1 Req 13) plus the
 * recovery-resource content (v3 W1/W2: suggested techniques, the user's coaching plan, the
 * pacer, the urge log, crisis contacts). The in-the-moment friction flow (start, countdown)
 * still lives in BlockerActivity; this screen shows the same persisted session
 * wherever the user is and resumes the countdown from the stored deadlines (RecoveryTimer /
 * RecoveryCountdownTracker — same engine, no UI-side math).
 *
 * A friction session is shown only while its rule still blocks: liveness is decided by the
 * engine's own evaluation ([BlockDecisionEngine.blocksNow]), so a paused, expired, deleted, or
 * window-ended rule reads as idle — never a countdown for a block that no longer exists.
 *
 * Everything on this surface renders from local state (INV-1): a failed write never blocks the
 * intervention, and the partner mirror is best-effort by design.
 */
@Composable
fun RecoveryScreen(
    repository: RulesRepository,
    recoveryStore: RecoveryStateStore,
    planRepository: RecoveryPlanRepository,
    sessions: RecoverySessionRepository,
    prefs: RecoveryPrefs,
    entryHint: String?,
    onRouteHome: () -> Unit,
    modifier: Modifier = Modifier,
    /** The habit whose streak this surface displays; urge rows logged here carry its id. */
    habitId: String? = null,
    /** Its user-entered label, so a log names the streak it lands in. */
    habitLabel: String? = null,
    onEntryConsumed: () -> Unit = {},
    /** Urge Assist support strip + hub reach-out row: the pair's name and the SOS sender. */
    partnerName: String? = null,
    onReachOut: () -> Unit = {},
    partnerMessageSent: Boolean = false,
    partnerMessageStatus: String? = null,
    /** Set by the block screen, the widget, or Home's action dial to open Urge Assist on arrival. */
    requestedView: String? = null,
    onViewConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var view by rememberSaveable { mutableStateOf<String?>(null) }
    // System back leaves a spoke the way its BackRow does — including a live technique
    // session, whose dispose path records the abandonment. At the hub the shell takes over.
    BackHandler(enabled = view != null) { view = null }
    // One row per rule (owner acceptance test 2026-11-02): every started session keeps its
    // own countdown and unlock — starting another rule's session never replaces it.
    var liveSessions by remember { mutableStateOf<List<LiveSession>>(emptyList()) }
    var planItems by remember { mutableStateOf<List<CoachingStep>>(emptyList()) }
    var activeWindowId by remember { mutableStateOf<String?>(null) }
    var inRiskWindow by remember { mutableStateOf(false) }
    var profileId by remember { mutableStateOf("") }
    // The deep-link that brought the user here (block screen vs. widget) labels every session
    // row this visit creates; captured once, because the extra is consumed by the shell.
    var visitEntry by rememberSaveable { mutableStateOf(RecoverySession.ENTRY_TAB) }

    LaunchedEffect(entryHint) {
        if (entryHint != null) {
            visitEntry = entryHint
            // Consumed after capture: a later manual visit to the tab is ENTRY_TAB, not
            // whichever deep link happened to bring the user here earlier this session.
            onEntryConsumed()
        }
    }

    LaunchedEffect(requestedView) {
        val requested = requestedView ?: return@LaunchedEffect
        view = requested
        onViewConsumed()
    }

    // Repository work on IO: the store open is a synchronous SQLCipher call that can block
    // seconds behind another opener's lock; on Main that is an ANR (device-caught 2026-09-19
    // on the resume path — this screen's callers are all Main-scoped).
    suspend fun reload() = withContext(Dispatchers.IO) {
        val rules = repository.getActiveRules().associateBy { it.ruleId }
        val now = System.currentTimeMillis()
        liveSessions = recoveryStore.sessions()
            .mapNotNull { session ->
                val enforced = liveSessionRule(rules[session.ruleId], now) ?: return@mapNotNull null
                LiveSession(
                    state = session,
                    rule = enforced,
                    remainingSeconds = 0L,
                    frozenAtSeconds = null,
                )
            }
            .sortedBy { it.state.deadlineWallClockUtcMillis }
    }

    suspend fun reloadPlan() = withContext(Dispatchers.IO) {
        val id = runCatching { repository.ensureActiveProfile().userId }.getOrNull() ?: return@withContext
        profileId = id
        planItems = planRepository.items(id)
        val window = planRepository.activeWindow(LocalDateTime.now())
        // A step's trigger link is a string (blank = any time, OPEN-4); the decimal id is the
        // link value the W3 editor writes.
        activeWindowId = window?.id?.toString()
        inRiskWindow = window != null
    }

    LaunchedEffect(Unit) {
        reload()
        reloadPlan()
    }

    // The rule behind the session can be paused, edited, or deleted on other screens; re-read
    // on every resume so the tab never shows a session that no longer blocks anything.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch(Dispatchers.IO) { reload(); reloadPlan() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Ticks every live session independently; only the expired rule is unlocked. Keyed on the
    // session identities so the per-second value updates never restart the effect, while a
    // new or consumed session does.
    val sessionKeys = liveSessions.map { it.state.ruleId to it.state.deadlineWallClockUtcMillis }
    LaunchedEffect(sessionKeys) {
        if (liveSessions.isEmpty()) return@LaunchedEffect
        val trackers = liveSessions.associate { it.state.ruleId to RecoveryCountdownTracker() }
        while (isActive) {
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val expired = mutableListOf<String>()
            // Re-read each tick: a concurrent reload (resume, rule change) may have removed a
            // session, and a stale captured list would resurrect its row every second.
            liveSessions = liveSessions.map { live ->
                val result = trackers.getValue(live.state.ruleId).tick(
                    live.state,
                    RecoveryTimer.sameBootSession(live.state.bootSessionId, nowElapsed),
                    nowWall,
                    nowElapsed,
                )
                when (result) {
                    is RecoveryCountdown.Counting -> {
                        if (result.remainingSeconds == 0L) expired += live.state.ruleId
                        live.copy(remainingSeconds = result.remainingSeconds, frozenAtSeconds = null)
                    }
                    is RecoveryCountdown.Frozen ->
                        live.copy(frozenAtSeconds = result.displayedRemainingSeconds)
                }
            }
            if (expired.isNotEmpty()) {
                expired.forEach { unlockRule(repository, recoveryStore, it) }
                reload()
                return@LaunchedEffect
            }
            delay(1000)
        }
    }

    // FLAG_SECURE (W6/INV-11): the recovery tab is a privacy surface for as long as it is
    // composed; an unrecognized restored id (e.g. a removed technique) falls through to home.
    SecureSurface()
    val technique = view?.let { RecoveryResourceCatalog.byId(it) }

    when {
        view == RECOVERY_VIEW_ASSIST -> UrgeAssistScreen(
            sessions = sessions,
            prefs = prefs,
            planItems = planItems,
            entry = visitEntry,
            habitId = habitId,
            habitLabel = habitLabel,
            inRiskWindow = inRiskWindow,
            partnerName = partnerName,
            onReachOut = onReachOut,
            partnerMessageSent = partnerMessageSent,
            partnerMessageStatus = partnerMessageStatus,
            onRouteHome = onRouteHome,
            onBack = { view = null },
            modifier = modifier,
        )
        view == RECOVERY_VIEW_CRISIS -> Column(modifier) {
            BackRow(label = stringResource(R.string.recovery_crisis_back), onBack = { view = null })
            CrisisScreen(prefs = prefs)
        }
        view == VIEW_TECHNIQUES -> TechniquesView(
            inRiskWindow = inRiskWindow,
            prefs = prefs,
            onOpenTechnique = { view = it.id },
            onBack = { view = null },
            modifier = modifier,
        )
        view == VIEW_PLAN -> PlanView(
            items = planItems,
            activeWindowId = activeWindowId,
            onEdit = { view = VIEW_PLAN_EDIT },
            onOpenPrefs = { view = VIEW_PREFS },
            onBack = { view = null },
            modifier = modifier,
        )
        view == VIEW_PREFS -> RecoveryPreferencesScreen(
            prefs = prefs,
            onBack = { view = VIEW_PLAN },
            modifier = modifier,
        )
        view == VIEW_PLAN_EDIT -> CoachingPlanScreen(
            planRepository = planRepository,
            rulesRepository = repository,
            onBack = { view = VIEW_PLAN },
            modifier = modifier,
        )
        view == VIEW_RISK_WINDOWS -> RiskWindowsScreen(
            planRepository = planRepository,
            onBack = { view = null },
            modifier = modifier,
        )
        view == VIEW_FRICTION_TIMER -> FrictionTimerScreen(
            recoveryStore = recoveryStore,
            onBack = { view = null },
            modifier = modifier,
        )
        technique != null -> {
            // The intervention flow itself is never timed (v2 INV-1).
            TechniqueSession(
                technique = technique,
                planItems = planItems,
                prefs = prefs,
                entry = visitEntry,
                habitId = habitId,
                habitLabel = habitLabel,
                sessions = sessions,
                onRouteHome = onRouteHome,
                onBack = { view = null },
                modifier = modifier,
            )
        }
        else -> HomeView(
            liveSessions = liveSessions,
            onLeaveAlone = onRouteHome,
            onOpenAssist = { view = RECOVERY_VIEW_ASSIST },
            partnerName = partnerName,
            onReachOut = onReachOut,
            onOpenTechniques = { view = VIEW_TECHNIQUES },
            onOpenPlan = { view = VIEW_PLAN },
            onOpenRiskWindows = { view = VIEW_RISK_WINDOWS },
            onOpenFrictionTimer = { view = VIEW_FRICTION_TIMER },
            onOpenCrisis = { view = RECOVERY_VIEW_CRISIS },
            habitLabel = habitLabel,
            logOnly = { intensity ->
                scope.launch(Dispatchers.IO) {
                    sessions.logOnly(
                        intensity = intensity,
                        entry = visitEntry,
                        zoneId = ZoneId.systemDefault().id,
                        nowEpochMs = System.currentTimeMillis(),
                        habitId = habitId,
                    )
                    onRouteHome()
                }
            },
            modifier = modifier,
        )
    }
}

/** A technique session: the row's lifecycle (start → completed/abandoned) plus the runner. */
@Composable
private fun TechniqueSession(
    technique: RecoveryTechnique,
    planItems: List<CoachingStep>,
    prefs: RecoveryPrefs,
    entry: String,
    habitId: String?,
    habitLabel: String?,
    sessions: RecoverySessionRepository,
    onRouteHome: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var sessionId by remember { mutableStateOf<String?>(null) }
    var logging by remember { mutableStateOf(false) }
    var haptic by remember { mutableStateOf(prefs.hapticPacing) }

    // The row is created when the technique is opened (FR-5: STARTED is the moment of choosing),
    // and each technique gets its own row — switching techniques ends the previous one quietly.
    // Finding #60: start() opens the store synchronously, and a LaunchedEffect body runs on Main
    // — the same fresh-open ANR class reload() above documents (device-caught 2026-09-19).
    LaunchedEffect(technique.id) {
        sessionId = withContext(Dispatchers.IO) {
            sessions.start(
                technique = technique.id,
                intensityStart = null,
                entry = entry,
                zoneId = ZoneId.systemDefault().id,
                nowEpochMs = System.currentTimeMillis(),
                habitId = habitId,
            )
        }
    }
    DisposableEffect(technique.id) {
        onDispose {
            // Leaving without the log sheet (back arrow, tab switch) is an abandoned session.
            // The repository's own scope owns the write: this composable's scope is already
            // cancelled by the time a tab switch disposes it.
            sessionId?.let(sessions::abandonQuietly)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BackRow(
            label = stringResource(techniqueTitle(technique.id)),
            onBack = {
                sessionId?.let(sessions::abandonQuietly)
                onBack()
            },
        )
        Text(
            stringResource(techniqueDescription(technique.id)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        TechniqueRunner(
            technique = technique,
            planItems = planItems,
            hapticEnabled = haptic,
            onHapticChange = {
                haptic = it
                prefs.hapticPacing = it
            },
            onCompleted = { logging = true },
            onStop = {
                sessionId?.let(sessions::abandonQuietly)
                onBack()
            },
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
        )
    }

    if (logging) {
        UrgeLogSheet(
            onDismiss = { logging = false },
            habitLabel = habitLabel,
            onSubmit = { intensity ->
                // Closed first: a sheet still on screen during the write takes a second tap
                // and fires the navigation below twice.
                logging = false
                val id = sessionId
                scope.launch(Dispatchers.IO) {
                    // Awaited before navigating away: this scope dies with the composition, so
                    // the completed row (with its intensity) must land first. The dispose-time
                    // abandon then loses to the repository's first-terminal-state-wins guard.
                    if (id != null) {
                        sessions.finish(id, RecoverySession.STATE_COMPLETED, intensity, System.currentTimeMillis())
                    }
                    onBack()
                    onRouteHome()
                }
            },
        )
    }
}

/** One live session as the hub renders it: persisted state + the rule that still blocks. */
private data class LiveSession(
    val state: RecoveryState,
    val rule: RestrictionRule,
    val remainingSeconds: Long,
    val frozenAtSeconds: Long?,
)

/**
 * The Recovery hub, "Field Ledger" (plan §E2.1/§E3). One destination, hairline rows, and a
 * single raised surface for a live block — no icon discs, no card stack. The crisis path keeps
 * its one-tap presence and is never gated.
 */
@Composable
private fun HomeView(
    liveSessions: List<LiveSession>,
    onLeaveAlone: () -> Unit,
    onOpenAssist: () -> Unit,
    partnerName: String?,
    onReachOut: () -> Unit,
    onOpenTechniques: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenRiskWindows: () -> Unit,
    onOpenFrictionTimer: () -> Unit,
    onOpenCrisis: () -> Unit,
    habitLabel: String?,
    logOnly: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var logging by remember { mutableStateOf(false) }
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Zone A — where things stand: identity and the current block state, on plain ground.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePadding)
                .padding(top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (habitLabel != null) {
                        stringResource(R.string.recovery_habit_context, habitLabel)
                    } else {
                        stringResource(R.string.recovery_subtitle)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The live block is the only raised surface on the page.
            if (liveSessions.isEmpty()) {
                IdleLine()
            } else {
                liveSessions.forEach { live ->
                    ActiveSessionBlock(
                        live = live,
                        onChangeWait = onOpenFrictionTimer,
                        onLeaveAlone = onLeaveAlone,
                    )
                }
            }
        }

        // Zone B — act now: the start dial and its doors on the ledger ground; teal is spent
        // on the one dial, and every support door is the size of its own label.
        RightNowCard(
            onStart = onOpenAssist,
            partnerName = partnerName,
            onReachOut = onReachOut,
            onOpenCrisis = onOpenCrisis,
            onLogUrge = { logging = true },
            sidePadding = sidePadding,
        )

        // Zone C — prepare: quiet practice and settings, back on plain ground.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePadding)
                .padding(top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionEyebrow(stringResource(R.string.recovery_prepare_label))
            Hairline()
            // Techniques lead: practice is the door a person in an urge is most likely to want.
            PrepareRow(
                icon = Icons.Outlined.SelfImprovement,
                titleRes = R.string.recovery_tile_techniques,
                descriptionRes = R.string.recovery_prepare_techniques_desc,
                onClick = onOpenTechniques,
            )
            Hairline(alpha = 0.5f)
            PrepareRow(
                icon = Icons.Outlined.Checklist,
                titleRes = R.string.recovery_plan_title,
                descriptionRes = R.string.recovery_prepare_plan_desc,
                onClick = onOpenPlan,
            )
            Hairline(alpha = 0.5f)
            PrepareRow(
                icon = Icons.Outlined.Schedule,
                titleRes = R.string.recovery_windows_title,
                descriptionRes = R.string.recovery_prepare_windows_desc,
                onClick = onOpenRiskWindows,
            )
            Hairline(alpha = 0.5f)
            PrepareRow(
                icon = Icons.Outlined.Timer,
                titleRes = R.string.recovery_timer_label,
                descriptionRes = R.string.recovery_prepare_friction_desc,
                onClick = onOpenFrictionTimer,
            )
        }
    }

    if (logging) {
        UrgeLogSheet(
            onDismiss = { logging = false },
            habitLabel = habitLabel,
            onSubmit = { intensity ->
                logging = false
                logOnly(intensity)
            },
        )
    }
}

/**
 * The moment-of-need block (owner recut 2026-11-08, second pass): the filled card is gone.
 * The section speaks the same Field Ledger grammar as the rest of the page — eyebrow, rule,
 * plain ground — and earns its weight from one object instead of a wash: a teal start dial
 * beside a Fraunces statement. The support doors sit below as compact pills, so every control
 * is the size of its own meaning and the primary action is no longer a full-width bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RightNowCard(
    onStart: () -> Unit,
    partnerName: String?,
    onReachOut: () -> Unit,
    onOpenCrisis: () -> Unit,
    onLogUrge: () -> Unit,
    sidePadding: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sidePadding),
    ) {
        SectionEyebrow(stringResource(R.string.recovery_now_eyebrow))
        Spacer(Modifier.height(4.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Top) {
            SignalDial(
                icon = Icons.Filled.PlayArrow,
                label = stringResource(R.string.recovery_now_start),
                contentDescription = stringResource(R.string.recovery_now_start),
                onClick = onStart,
            )
            Spacer(Modifier.width(18.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.recovery_now_title),
                    // Fraunces at statement size: the page's one editorial beat, on the
                    // adaptive scale (NoLiteralTypeSizeTest guards the window-size step).
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    stringResource(R.string.recovery_now_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (partnerName != null) {
                LifelinePill(
                    icon = Icons.Outlined.WavingHand,
                    label = partnerName,
                    contentDescription = stringResource(
                        R.string.recovery_reach_out_named,
                        partnerName,
                    ),
                    onClick = onReachOut,
                )
            }
            LifelinePill(
                icon = Icons.Outlined.SupportAgent,
                label = stringResource(R.string.recovery_lifeline_urgent),
                contentDescription = stringResource(R.string.recovery_crisis_link),
                onClick = onOpenCrisis,
            )
            LifelinePill(
                painter = painterResource(R.drawable.ic_log_urge),
                label = stringResource(R.string.recovery_log_action),
                contentDescription = stringResource(R.string.recovery_log_action),
                onClick = onLogUrge,
            )
        }
    }
}

/** A support door the size of its own label: pill outline, icon, one line. */
@Composable
private fun LifelinePill(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    painter: Painter? = null,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(1.dp, outline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun IdleLine() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hairline()
        Text(
            stringResource(R.string.recovery_idle_line),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrepareRow(icon: ImageVector, titleRes: Int, descriptionRes: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun SectionEyebrow(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun Hairline(alpha: Float = 0.7f) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha))
}

/** The practice library (plan §E2.6): editorial rows, no icon discs. */
@Composable
private fun TechniquesView(
    inRiskWindow: Boolean,
    prefs: RecoveryPrefs,
    onOpenTechnique: (RecoveryTechnique) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Plan §E2.6: "Not for me" is the user-controlled retry-later mechanism. The disliked set
    // is stored by action id, because that is what the assist engine filters on.
    var disliked by remember { mutableStateOf(prefs.dislikedActions) }
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        BackRow(label = stringResource(R.string.recovery_tile_techniques), onBack = onBack)
        Column(
            modifier = Modifier.padding(horizontal = sidePadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.recovery_techniques_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inRiskWindow) {
                Text(
                    stringResource(R.string.recovery_risk_window_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Hairline()
            val all = RecoveryResourceCatalog.ordered(inRiskWindow)
            val techniques = all.filterNot { techniqueId(it) in disliked }
            techniques.forEachIndexed { index, technique ->
                TechniqueRow(
                    technique = technique,
                    onOpen = { onOpenTechnique(technique) },
                    onNotForMe = {
                        prefs.markDisliked(techniqueId(technique))
                        disliked = prefs.dislikedActions
                    },
                )
                if (index < techniques.lastIndex) Hairline(alpha = 0.5f)
            }
            val hidden = all.filter { techniqueId(it) in disliked }
            if (hidden.isNotEmpty()) {
                Hairline()
                SectionEyebrow(stringResource(R.string.recovery_techniques_hidden))
                hidden.forEach { technique ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(techniqueTitle(technique.id)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                prefs.clearDisliked(techniqueId(technique))
                                disliked = prefs.dislikedActions
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.recovery_show_again))
                        }
                    }
                }
            }
        }
    }
}

/** The assist action that hosts a practice technique; the disliked set stores action ids. */
private fun techniqueId(technique: RecoveryTechnique): String =
    UrgeCatalog.actionForTechnique(technique.id)?.id ?: technique.id

@Composable
private fun TechniqueRow(
    technique: RecoveryTechnique,
    onOpen: () -> Unit,
    onNotForMe: () -> Unit,
) {
    val effort = stringResource(effortLabel(technique.effort.name))
    val minutes = technique.defaultDurationMinutes
    val meta = if (minutes != null) {
        stringResource(R.string.recovery_technique_meta, effort, minutes)
    } else {
        effort
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(techniqueTitle(technique.id)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                meta,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(techniqueDescription(technique.id)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                onClick = onOpen,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(stringResource(R.string.recovery_technique_practice))
            }
            TextButton(
                onClick = onNotForMe,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(stringResource(R.string.recovery_technique_not_for_me))
            }
        }
    }
}

/** The plan read state (plan §E2.7): a ledger; the editor is the next step. */
@Composable
private fun PlanView(
    items: List<CoachingStep>,
    activeWindowId: String?,
    onEdit: () -> Unit,
    onOpenPrefs: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        BackRow(
            label = stringResource(R.string.recovery_plan_title),
            onBack = onBack,
            trailing = {
                TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.recovery_plan_edit))
                }
            },
        )
        Column(modifier = Modifier.padding(horizontal = sidePadding, vertical = 8.dp)) {
            PlanItems(items = items, activeWindowId = activeWindowId, onAdd = onEdit)
            // Plan §E2.7: the preferences live here rather than behind a Settings trip.
            Hairline()
            TextButton(
                onClick = onOpenPrefs,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(stringResource(R.string.recovery_prefs_title))
            }
        }
    }
}

/** The user's coaching plan (W1/W3): steps, if-then plans, and value statements. */
@Composable
internal fun PlanItems(items: List<CoachingStep>, activeWindowId: String?, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.recovery_plan_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onAdd,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(stringResource(R.string.recovery_plan_add))
            }
            return@Column
        }
        listOf(
            CoachingStep.KIND_STEP to R.string.recovery_plan_steps,
            CoachingStep.KIND_IF_THEN to R.string.recovery_plan_ifthen,
            CoachingStep.KIND_VALUE to R.string.recovery_plan_values,
        ).forEach { (kind, label) ->
            val kindItems = items.filter { it.kind == kind }
            if (kindItems.isEmpty()) return@forEach
            SectionEyebrow(stringResource(label))
            Hairline()
            kindItems.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BulletLine(item.actionText, modifier = Modifier.weight(1f))
                    // OPEN-4 default: every item shows; one tied to the window that is active now
                    // is marked, so the plan can be read in order and still answer "what now?".
                    if (activeWindowId != null && item.associatedTriggerWindow == activeWindowId) {
                        StatusPill(stringResource(R.string.recovery_plan_now))
                    }
                }
            }
        }
    }
}

internal fun techniqueTitle(id: String): Int = when (id) {
    RecoveryResourceCatalog.URGE_SURFING -> R.string.recovery_tech_urge_surfing_title
    RecoveryResourceCatalog.DELAY_TIMER -> R.string.recovery_tech_delay_timer_title
    RecoveryResourceCatalog.SOBER -> R.string.recovery_tech_sober_title
    RecoveryResourceCatalog.GROUNDING -> R.string.recovery_tech_grounding_title
    RecoveryResourceCatalog.DEFUSION -> R.string.recovery_tech_defusion_title
    else -> R.string.recovery_tech_values_card_title
}

internal fun techniqueDescription(id: String): Int = when (id) {
    RecoveryResourceCatalog.URGE_SURFING -> R.string.recovery_tech_urge_surfing_desc
    RecoveryResourceCatalog.DELAY_TIMER -> R.string.recovery_tech_delay_timer_desc
    RecoveryResourceCatalog.SOBER -> R.string.recovery_tech_sober_desc
    RecoveryResourceCatalog.GROUNDING -> R.string.recovery_tech_grounding_desc
    RecoveryResourceCatalog.DEFUSION -> R.string.recovery_tech_defusion_desc
    else -> R.string.recovery_tech_values_card_desc
}

private fun effortLabel(effort: String): Int = when (effort) {
    "LOW" -> R.string.recovery_effort_low
    "MEDIUM" -> R.string.recovery_effort_medium
    else -> R.string.recovery_effort_high
}

/**
 * The session is live only while its rule still exists and still blocks — the engine's own
 * Rule 1/3 evaluation, the same one enforcement uses (no UI-side schedule re-derivation).
 * Matches the enforcement path exactly: it also decides with no daily-limit usage feed.
 */
internal fun liveSessionRule(rule: RestrictionRule?, nowMillis: Long): RestrictionRule? =
    rule?.takeIf {
        it.isEnabled && BlockDecisionEngine.blocksNow(
            it.isAlwaysOn,
            it.dailyLimitMs,
            it.timeRangeStart,
            it.timeRangeEnd,
            it.activeDaysOfWeek,
            it.timezone,
            it.blockExpiresAt,
            nowMillis,
        )
    }

/** Escape semantics shared with BlockerActivity: expire the rule via Rule 3 (blockExpiresAt) —
 *  the engine's existing meaning, no new exemption path. Consumes that rule's session (see
 *  [RecoveryStateStore.clear]) so a completed session cannot auto-unlock a later re-arm;
 *  other rules' sessions keep running. */
internal suspend fun unlockRule(
    repository: RulesRepository,
    recoveryStore: RecoveryStateStore,
    ruleId: String,
) = withContext(Dispatchers.IO) {
    val rule = repository.getActiveRules().firstOrNull { it.ruleId == ruleId }
    if (rule != null) {
        repository.updateRule(rule.copy(blockExpiresAt = System.currentTimeMillis() - 1))
    }
    recoveryStore.clear(ruleId)
}

/**
 * The live friction session: the hub's only raised surface (plan §E2.1). Timer, blocked app,
 * and the two escape links ("Change wait" opens the same editor as Prepare) all live here,
 * so nothing about a running block reads as a separate card. The countdown is the only way
 * the block lifts (owner ruling 2026-11-08: the immediate override removed).
 */
@Composable
private fun ActiveSessionBlock(
    live: LiveSession,
    onChangeWait: () -> Unit,
    onLeaveAlone: () -> Unit,
) {
    val appLabel = rememberAppLabel(live.rule.blockedPackage)
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = ink,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.recovery_timer_label),
                style = MaterialTheme.typography.labelLarge,
                color = ink.copy(alpha = 0.8f),
            )
            Text(
                formatCountdown(live.frozenAtSeconds ?: live.remainingSeconds),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                // Gold is the streak/milestone accent only (finding #21); a timer is neither.
                color = ink,
                maxLines = 1,
            )
            Text(
                stringResource(R.string.recovery_blocked_target, appLabel),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (live.frozenAtSeconds != null) {
                Text(
                    stringResource(R.string.recovery_time_change_frozen),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HorizontalDivider(
                color = ink.copy(alpha = 0.2f),
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onChangeWait,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    // The owner's cited friction point: the wait was reachable only through
                    // Settings. This is the same editor, one tap from the running block.
                    Text(stringResource(R.string.recovery_change_wait))
                }
                TextButton(
                    onClick = onLeaveAlone,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.recovery_leave_alone))
                }
            }
            Text(
                stringResource(R.string.recovery_leave_note),
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.8f),
            )
        }
    }
}

private fun formatCountdown(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
