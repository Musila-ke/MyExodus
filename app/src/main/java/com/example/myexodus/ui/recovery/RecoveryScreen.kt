package com.example.myexodus.ui.recovery

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.example.myexodus.data.db.entity.RecoveryState
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberWindowWidth
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Recovery tab — the app-side surface of the shipped recovery session (V1 doc Req 13). The
 * in-the-moment flow (start, countdown, override, Home escape hatch) lives in BlockerActivity;
 * this screen shows the same persisted session wherever the user is: re-entering here after
 * leaving the block screen, or after process death, resumes the countdown from the stored
 * deadlines (RecoveryTimer/RecoveryCountdownTracker — same engine, no UI-side math).
 *
 * A session is shown only while its rule still blocks: liveness is decided by the engine's own
 * evaluation ([BlockDecisionEngine.blocksNow]), so a paused, expired, deleted, or
 * window-ended rule reads as idle — never a countdown for a block that no longer exists.
 */
@Composable
fun RecoveryScreen(
    repository: RulesRepository,
    recoveryStore: RecoveryStateStore,
    attempts: AttemptRecorder,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var liveSession by remember { mutableStateOf<Pair<RecoveryState, RestrictionRule>?>(null) }
    var remainingSeconds by remember { mutableLongStateOf(0L) }
    var frozenAtSeconds by remember { mutableStateOf<Long?>(null) }
    var overrideAvailable by remember { mutableStateOf(true) }

    suspend fun reload() {
        val session = recoveryStore.current()
        val rule = session?.let { s ->
            repository.getActiveRules().firstOrNull { it.ruleId == s.ruleId }
        }
        val enforced = liveSessionRule(rule, System.currentTimeMillis())
        liveSession = if (session != null && enforced != null) session to enforced else null
        overrideAvailable = recoveryStore.overrideEligible()
    }

    LaunchedEffect(Unit) { reload() }

    // The rule behind the session can be paused, edited, or deleted on other screens; re-read
    // on every resume so the tab never shows a session that no longer blocks anything.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { reload() }
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

    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    val active = liveSession
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

        if (active == null) {
            IdleCard()
        } else {
            val rule = active.second
            ActiveTimerCard(
                blockedTarget = rule.blockedPackage,
                remainingSeconds = remainingSeconds,
                frozenAtSeconds = frozenAtSeconds,
            )
            OverrideCard(
                overrideAvailable = overrideAvailable,
                onOverride = {
                    scope.launch {
                        // Same override path as BlockerActivity: always allowed, cooldown-bounded
                        // (recorded only when eligible) and logged to the attempt trail.
                        if (recoveryStore.overrideEligible()) recoveryStore.recordOverride()
                        attempts.recordOverride(rule.ruleId, rule.blockedPackage)
                        unlockRule(repository, recoveryStore, rule.ruleId)
                        reload()
                    }
                },
            )
            Text(
                stringResource(R.string.recovery_leave_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
) {
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
                color = MaterialTheme.colorScheme.tertiary,
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOverride,
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
