package com.example.myexodus.ui.recovery

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myexodus.R
import com.example.myexodus.blocking.AttemptRecorder
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
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.NavTile
import com.example.myexodus.ui.SecureSurface
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

private const val VIEW_HISTORY = "history"
private const val VIEW_CRISIS = "crisis"
private const val VIEW_TECHNIQUES = "techniques"
private const val VIEW_PLAN = "plan"

/**
 * Recovery tab — the app-side surface of the shipped friction session (V1 Req 13) plus the
 * recovery-resource content (v3 W1/W2: suggested techniques, the user's coaching plan, the
 * pacer, the urge log, crisis contacts). The in-the-moment friction flow (start, countdown,
 * override) still lives in BlockerActivity; this screen shows the same persisted session
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
    attempts: AttemptRecorder,
    planRepository: RecoveryPlanRepository,
    sessions: RecoverySessionRepository,
    prefs: RecoveryPrefs,
    entryHint: String?,
    onRouteHome: () -> Unit,
    modifier: Modifier = Modifier,
    onEntryConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var view by rememberSaveable { mutableStateOf<String?>(null) }
    // System back leaves a spoke the way its BackRow does — including a live technique
    // session, whose dispose path records the abandonment. At the hub the shell takes over.
    BackHandler(enabled = view != null) { view = null }
    var liveSession by remember { mutableStateOf<Pair<RecoveryState, RestrictionRule>?>(null) }
    var remainingSeconds by remember { mutableLongStateOf(0L) }
    var frozenAtSeconds by remember { mutableStateOf<Long?>(null) }
    var overrideAvailable by remember { mutableStateOf(true) }
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

    // Repository work on IO: the store open is a synchronous SQLCipher call that can block
    // seconds behind another opener's lock; on Main that is an ANR (device-caught 2026-09-19
    // on the resume path — this screen's callers are all Main-scoped).
    suspend fun reload() = withContext(Dispatchers.IO) {
        val session = recoveryStore.current()
        val rule = session?.let { s ->
            repository.getActiveRules().firstOrNull { it.ruleId == s.ruleId }
        }
        val enforced = liveSessionRule(rule, System.currentTimeMillis())
        liveSession = if (session != null && enforced != null) session to enforced else null
        overrideAvailable = recoveryStore.overrideEligible()
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

    LaunchedEffect(liveSession) {
        val (session, _) = liveSession ?: return@LaunchedEffect
        val tracker = RecoveryCountdownTracker()
        while (isActive) {
            val result = tracker.tick(
                session,
                RecoveryTimer.sameBootSession(session.bootSessionId, SystemClock.elapsedRealtime()),
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
            )
            when (result) {
                is RecoveryCountdown.Counting -> {
                    remainingSeconds = result.remainingSeconds
                    frozenAtSeconds = null
                    if (result.remainingSeconds == 0L) {
                        unlockRule(repository, recoveryStore, session.ruleId)
                        reload()
                        return@LaunchedEffect
                    }
                }
                is RecoveryCountdown.Frozen -> frozenAtSeconds = result.displayedRemainingSeconds
            }
            delay(1000)
        }
    }

    // FLAG_SECURE (W6/INV-11): the recovery tab is a privacy surface for as long as it is
    // composed; an unrecognized restored id (e.g. a removed technique) falls through to home.
    SecureSurface()
    val technique = view?.let { RecoveryResourceCatalog.byId(it) }

    when {
        view == VIEW_HISTORY -> HistoryView(
            sessions = sessions,
            prefs = prefs,
            onBack = { view = null },
            modifier = modifier,
        )
        view == VIEW_CRISIS -> Column(modifier) {
            BackRow(label = stringResource(R.string.recovery_crisis_back), onBack = { view = null })
            CrisisScreen()
        }
        view == VIEW_TECHNIQUES -> TechniquesView(
            inRiskWindow = inRiskWindow,
            onOpenTechnique = { view = it.id },
            onBack = { view = null },
            modifier = modifier,
        )
        view == VIEW_PLAN -> PlanView(
            items = planItems,
            activeWindowId = activeWindowId,
            onBack = { view = null },
            modifier = modifier,
        )
        technique != null -> {
            // The intervention flow itself is never gated or timed (v2 INV-1; W6 exceptions).
            TechniqueSession(
                technique = technique,
                planItems = planItems,
                prefs = prefs,
                entry = visitEntry,
                sessions = sessions,
                onRouteHome = onRouteHome,
                onBack = { view = null },
                modifier = modifier,
            )
        }
        else -> HomeView(
            liveSession = liveSession,
            remainingSeconds = remainingSeconds,
            frozenAtSeconds = frozenAtSeconds,
            overrideAvailable = overrideAvailable,
            inRiskWindow = inRiskWindow,
            onOverride = {
                val rule = liveSession?.second
                if (rule != null) {
                    scope.launch(Dispatchers.IO) {
                        // Same override path as BlockerActivity: always allowed, cooldown-bounded
                        // (recorded only when eligible) and logged to the attempt trail.
                        if (recoveryStore.overrideEligible()) recoveryStore.recordOverride()
                        attempts.recordOverride(rule.ruleId, rule.blockedPackage)
                        unlockRule(repository, recoveryStore, rule.ruleId)
                        reload()
                    }
                }
            },
            onOpenTechnique = { view = it.id },
            onOpenTechniques = { view = VIEW_TECHNIQUES },
            onOpenPlan = { view = VIEW_PLAN },
            onOpenHistory = { view = VIEW_HISTORY },
            onOpenCrisis = { view = VIEW_CRISIS },
            logOnly = { intensity ->
                scope.launch(Dispatchers.IO) {
                    sessions.logOnly(
                        intensity = intensity,
                        entry = visitEntry,
                        zoneId = ZoneId.systemDefault().id,
                        nowEpochMs = System.currentTimeMillis(),
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
    LaunchedEffect(technique.id) {
        sessionId = sessions.start(
            technique = technique.id,
            intensityStart = null,
            entry = entry,
            zoneId = ZoneId.systemDefault().id,
            nowEpochMs = System.currentTimeMillis(),
        )
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
            onSubmit = { intensity ->
                val id = sessionId
                scope.launch(Dispatchers.IO) {
                    // Awaited before navigating away: this scope dies with the composition, so
                    // the completed row (with its intensity) must land first. The dispose-time
                    // abandon then loses to the repository's first-terminal-state-wins guard.
                    if (id != null) {
                        sessions.finish(id, RecoverySession.STATE_COMPLETED, intensity, System.currentTimeMillis())
                    }
                    logging = false
                    onBack()
                    onRouteHome()
                }
            },
        )
    }
}

/**
 * The Recovery hub. The dense page was split (owner ruling 2026-09-22): reference material
 * (the full catalog, the coaching plan, history) moved behind icon+label tiles, while the
 * three things an urge needs immediately stayed — the live state, the log action, and the
 * crisis path, which is never gated behind a tap.
 */
@Composable
private fun HomeView(
    liveSession: Pair<RecoveryState, RestrictionRule>?,
    remainingSeconds: Long,
    frozenAtSeconds: Long?,
    overrideAvailable: Boolean,
    inRiskWindow: Boolean,
    onOverride: () -> Unit,
    onOpenTechnique: (RecoveryTechnique) -> Unit,
    onOpenTechniques: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenCrisis: () -> Unit,
    logOnly: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var logging by remember { mutableStateOf(false) }
    val suggested = RecoveryResourceCatalog.recommended(rating = null, inRiskWindow = inRiskWindow)
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.recovery_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (liveSession == null) {
            IdleCard()
        } else {
            val rule = liveSession.second
            ActiveTimerCard(
                blockedTarget = rule.blockedPackage,
                remainingSeconds = remainingSeconds,
                frozenAtSeconds = frozenAtSeconds,
            )
            OverrideCard(overrideAvailable = overrideAvailable, onOverride = onOverride)
            Text(
                stringResource(R.string.recovery_leave_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column {
            Text(
                stringResource(R.string.recovery_suggested_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (inRiskWindow) {
                Text(
                    stringResource(R.string.recovery_risk_window_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // One suggestion leads (v2 M1.3); the rest of the catalog is one tile away.
        TechniqueCard(technique = suggested, onOpen = { onOpenTechnique(suggested) })

        Button(onClick = { logging = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recovery_log_action))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NavTile(
                icon = Icons.Outlined.SelfImprovement,
                label = stringResource(R.string.recovery_tile_techniques),
                onClick = onOpenTechniques,
                modifier = Modifier.weight(1f),
            )
            NavTile(
                icon = Icons.Outlined.Checklist,
                label = stringResource(R.string.recovery_plan_title),
                onClick = onOpenPlan,
                modifier = Modifier.weight(1f),
            )
            NavTile(
                icon = Icons.Outlined.History,
                label = stringResource(R.string.recovery_history_title),
                onClick = onOpenHistory,
                modifier = Modifier.weight(1f),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenCrisis),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.recovery_crisis_link),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.recovery_crisis_link_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (logging) {
        UrgeLogSheet(
            onDismiss = { logging = false },
            onSubmit = { intensity ->
                logging = false
                logOnly(intensity)
            },
        )
    }
}

@Composable
private fun TechniqueCard(technique: RecoveryTechnique, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(techniqueTitle(technique.id)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(effortLabel(technique.effort.name)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(techniqueDescription(technique.id)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** All four techniques — the "choose another" path the hub's single suggestion promises. */
@Composable
private fun TechniquesView(
    inRiskWindow: Boolean,
    onOpenTechnique: (RecoveryTechnique) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        BackRow(label = stringResource(R.string.recovery_tile_techniques), onBack = onBack)
        Column(
            modifier = Modifier.padding(horizontal = sidePadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (inRiskWindow) {
                Text(
                    stringResource(R.string.recovery_risk_window_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RecoveryResourceCatalog.ordered(inRiskWindow).forEach { technique ->
                TechniqueCard(technique = technique, onOpen = { onOpenTechnique(technique) })
            }
        }
    }
}

@Composable
private fun PlanView(
    items: List<CoachingStep>,
    activeWindowId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        BackRow(label = stringResource(R.string.recovery_plan_title), onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = sidePadding, vertical = 8.dp)) {
            PlanItems(items = items, activeWindowId = activeWindowId)
        }
    }
}

/** The user's coaching plan (W1/W3): steps, if-then plans, and value statements. */
@Composable
private fun PlanItems(items: List<CoachingStep>, activeWindowId: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.recovery_plan_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        listOf(
            CoachingStep.KIND_STEP to R.string.recovery_plan_steps,
            CoachingStep.KIND_IF_THEN to R.string.recovery_plan_ifthen,
            CoachingStep.KIND_VALUE to R.string.recovery_plan_values,
        ).forEach { (kind, label) ->
            val kindItems = items.filter { it.kind == kind }
            if (kindItems.isEmpty()) return@forEach
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            kindItems.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BulletLine(item.actionText, modifier = Modifier.weight(1f))
                    // OPEN-4 default: every item shows; one tied to the window that is active now
                    // is marked, so the plan can be read in order and still answer "what now?".
                    if (activeWindowId != null && item.associatedTriggerWindow == activeWindowId) {
                        Text(
                            stringResource(R.string.recovery_plan_now),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
    else -> R.string.recovery_tech_values_card_title
}

internal fun techniqueDescription(id: String): Int = when (id) {
    RecoveryResourceCatalog.URGE_SURFING -> R.string.recovery_tech_urge_surfing_desc
    RecoveryResourceCatalog.DELAY_TIMER -> R.string.recovery_tech_delay_timer_desc
    RecoveryResourceCatalog.SOBER -> R.string.recovery_tech_sober_desc
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
 *  the engine's existing meaning, no new exemption path. Consumes the session (see
 *  [RecoveryStateStore.clear]) so a completed session cannot auto-unlock a later re-arm. */
internal suspend fun unlockRule(
    repository: RulesRepository,
    recoveryStore: RecoveryStateStore,
    ruleId: String,
) = withContext(Dispatchers.IO) {
    val rule = repository.getActiveRules().firstOrNull { it.ruleId == ruleId }
    if (rule != null) {
        repository.updateRule(rule.copy(blockExpiresAt = System.currentTimeMillis() - 1))
    }
    recoveryStore.clear()
}

@Composable
private fun ActiveTimerCard(
    blockedTarget: String,
    remainingSeconds: Long,
    frozenAtSeconds: Long?,
) {
    val appLabel = rememberAppLabel(blockedTarget)
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.recovery_timer_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Text(
                formatCountdown(frozenAtSeconds ?: remainingSeconds),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                // Gold is the streak/milestone accent only (finding #21); an urge timer is
                // neither — the card's own on-container ink is the honest neutral.
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.recovery_blocked_target, appLabel),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (frozenAtSeconds != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.recovery_time_change_frozen),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun OverrideCard(overrideAvailable: Boolean, onOverride: () -> Unit) {
    // Finding #17: the override retires the rule for good (Rule-3 expiry) — an irreversible
    // consequence that was not disclosed before it happened.
    var confirming by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { confirming = true },
                enabled = overrideAvailable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (overrideAvailable) R.string.recovery_override
                        else R.string.recovery_override_unavailable,
                    ),
                )
            }
            Text(
                stringResource(R.string.recovery_overrides_logged),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.recovery_override_confirm_title)) },
            text = { Text(stringResource(R.string.recovery_override_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onOverride()
                    },
                ) {
                    // Destructive confirm wears the error role (same rule as finding #18):
                    // ending the block retires the rule permanently.
                    Text(
                        stringResource(R.string.recovery_override_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.recovery_override_confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun IdleCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.recovery_idle_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.recovery_idle_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatCountdown(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
