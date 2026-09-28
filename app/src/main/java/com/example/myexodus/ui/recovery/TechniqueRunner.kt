package com.example.myexodus.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.recovery.RecoveryResourceCatalog
import com.example.myexodus.recovery.RecoveryTechnique
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The in-technique experience (v2 FR-4): the pacer, the five SOBER screens, the 10-minute delay,
 * and the values card. Purely presentational — the caller owns the session row ([RecoverySession]
 * lifecycle) and decides what "done" and "stop" mean (urge log vs. quiet abandon).
 */
@Composable
fun TechniqueRunner(
    technique: RecoveryTechnique,
    planItems: List<CoachingStep>,
    hapticEnabled: Boolean,
    onHapticChange: (Boolean) -> Unit,
    onCompleted: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (technique.id) {
        RecoveryResourceCatalog.URGE_SURFING -> PacedBreathing(
            technique = technique,
            hapticEnabled = hapticEnabled,
            onHapticChange = onHapticChange,
            onCompleted = onCompleted,
            onStop = onStop,
            modifier = modifier,
        )
        RecoveryResourceCatalog.DELAY_TIMER -> DelayTimer(
            technique = technique,
            ifThen = planItems,
            onCompleted = onCompleted,
            onStop = onStop,
            modifier = modifier,
        )
        RecoveryResourceCatalog.SOBER -> SoberWalkthrough(
            technique = technique,
            ifThen = planItems.filter { it.kind == CoachingStep.KIND_IF_THEN },
            onCompleted = onCompleted,
            onStop = onStop,
            modifier = modifier,
        )
        RecoveryResourceCatalog.VALUES_CARD -> ValuesCard(
            values = planItems.filter { it.kind == CoachingStep.KIND_VALUE },
            onCompleted = onCompleted,
            onStop = onStop,
            modifier = modifier,
        )
    }
}

@Composable
private fun PacedBreathing(
    technique: RecoveryTechnique,
    hapticEnabled: Boolean,
    onHapticChange: (Boolean) -> Unit,
    onCompleted: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val choices = technique.durationChoicesMinutes.ifEmpty { listOf(5) }
    var minutes by remember { mutableIntStateOf(technique.defaultDurationMinutes ?: choices.first()) }
    var running by remember { mutableStateOf(false) }
    var roundsDone by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!running && !roundsDone) {
            Text(
                stringResource(R.string.recovery_pacer_choose_duration),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { choice ->
                    FilterChip(
                        selected = minutes == choice,
                        onClick = { minutes = choice },
                        label = { Text(stringResource(R.string.recovery_minutes, choice)) },
                    )
                }
            }
            Button(onClick = { running = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.recovery_pacer_start))
            }
        } else {
            BreathingPacer(
                // Catalog-owned pacing, asserted rather than defaulted: a silent `?: 4` here
                // would let the ring and the copy disagree with the technique's own numbers.
                breathInSeconds = requireNotNull(technique.breathInSeconds) {
                    "${technique.id} is a paced technique without catalog breath numbers"
                },
                breathOutSeconds = requireNotNull(technique.breathOutSeconds) {
                    "${technique.id} is a paced technique without catalog breath numbers"
                },
                promptRotationSeconds = technique.promptRotationSeconds,
                durationSeconds = minutes * 60,
                hapticEnabled = hapticEnabled,
                onHapticChange = onHapticChange,
                running = running,
                onFinished = {
                    running = false
                    roundsDone = true
                },
            )
            if (running) {
                OutlinedButton(onClick = { running = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.recovery_pacer_pause))
                }
            } else {
                // Reaching 0 stops the clock; the user confirms the session ended where they are.
                Text(
                    stringResource(R.string.recovery_pacer_finished),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (minutes < choices.maxOrNull().orZero()) {
                    TextButton(onClick = { roundsDone = false; minutes = choices.first { it > minutes } }) {
                        Text(stringResource(R.string.recovery_pacer_extend))
                    }
                }
            }
        }
        RunnerActions(onCompleted = onCompleted, onStop = onStop)
    }
}

private fun Int?.orZero(): Int = this ?: 0

@Composable
private fun DelayTimer(
    technique: RecoveryTechnique,
    ifThen: List<CoachingStep>,
    onCompleted: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val minutes = technique.defaultDurationMinutes ?: 10
    val totalSeconds = minutes * 60
    var startedAtMs by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        if (startedAtMs == 0L) startedAtMs = android.os.SystemClock.elapsedRealtime()
        while (isActive) {
            val elapsed = (android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000
            elapsedSeconds = elapsed.coerceAtMost(totalSeconds.toLong())
            if (elapsedSeconds >= totalSeconds) return@LaunchedEffect
            delay(1000)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.recovery_delay_body, minutes, minutes),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (running) {
            Text(
                stringResource(
                    R.string.recovery_delay_remaining,
                    ((totalSeconds - elapsedSeconds) / 60).toInt(),
                    ((totalSeconds - elapsedSeconds) % 60).toInt(),
                ),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(
                progress = { elapsedSeconds.toFloat() / totalSeconds },
                modifier = Modifier.fillMaxWidth(),
            )
            if (elapsedSeconds >= totalSeconds) {
                Text(
                    stringResource(R.string.recovery_delay_finished),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            Button(onClick = { running = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.recovery_delay_start))
            }
        }
        CoachingItems(items = ifThen, emptyRes = R.string.recovery_delay_no_ifthen)
        RunnerActions(onCompleted = onCompleted, onStop = onStop)
    }
}

@Composable
private fun SoberWalkthrough(
    technique: RecoveryTechnique,
    ifThen: List<CoachingStep>,
    onCompleted: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phases = technique.phases
    if (phases.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The reference's "TRACKING" eyebrow: the rail shows where the user is, this says it —
        // for a glance, for TalkBack, and for the window where the rail runs below the fold.
        Text(
            stringResource(R.string.recovery_sober_progress, index + 1, phases.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.fillMaxWidth()) {
            phases.forEachIndexed { i, p ->
                val state = when {
                    i < index -> StepState.Done
                    i == index -> StepState.Current
                    else -> StepState.Upcoming
                }
                StepNode(
                    number = i + 1,
                    titleRes = soberPhaseTitle(p),
                    state = state,
                    isLast = i == phases.lastIndex,
                ) {
                    if (state == StepState.Current) {
                        Text(
                            stringResource(soberPhaseBody(p)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (p == RecoveryResourceCatalog.SOBER_RESPOND) {
                            CoachingItems(items = ifThen, emptyRes = R.string.recovery_sober_no_ifthen)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { index-- },
                enabled = index > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.recovery_sober_back))
            }
            Button(
                onClick = { if (index == phases.lastIndex) onCompleted() else index++ },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        if (index == phases.lastIndex) R.string.recovery_sober_finish
                        else R.string.recovery_sober_next,
                    ),
                )
            }
        }
        TextButton(onClick = onStop, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.recovery_stop))
        }
    }
}

private fun soberPhaseTitle(phase: String): Int = when (phase) {
    RecoveryResourceCatalog.SOBER_STOP -> R.string.recovery_sober_stop_title
    RecoveryResourceCatalog.SOBER_OBSERVE -> R.string.recovery_sober_observe_title
    RecoveryResourceCatalog.SOBER_BREATHE -> R.string.recovery_sober_breathe_title
    RecoveryResourceCatalog.SOBER_EXPAND -> R.string.recovery_sober_expand_title
    else -> R.string.recovery_sober_respond_title
}

private fun soberPhaseBody(phase: String): Int = when (phase) {
    RecoveryResourceCatalog.SOBER_STOP -> R.string.recovery_sober_stop_body
    RecoveryResourceCatalog.SOBER_OBSERVE -> R.string.recovery_sober_observe_body
    RecoveryResourceCatalog.SOBER_BREATHE -> R.string.recovery_sober_breathe_body
    RecoveryResourceCatalog.SOBER_EXPAND -> R.string.recovery_sober_expand_body
    else -> R.string.recovery_sober_respond_body
}

private enum class StepState { Done, Current, Upcoming }

/**
 * One node of the walkthrough rail (reference `12.01.19`): numbered circle, the connector to
 * the next node, then the phase title — with the current phase's body expanded under its own
 * title instead of in a separate card below. Only the last node is connector-less; the card
 * the body used to sit in is gone because the rail's expanded node already says "you are here".
 */
@Composable
private fun StepNode(
    number: Int,
    titleRes: Int,
    state: StepState,
    isLast: Boolean,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.width(26.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepCircle(number = number, state = state)
            if (!isLast) {
                // The segment leaving a finished node is teal; everything below the current
                // node is toneless — the rail fills in behind the user, it does not preview.
                Box(
                    Modifier
                        .weight(1f)
                        .width(2.dp)
                        .background(
                            if (state == StepState.Done) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (state == StepState.Current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (state == StepState.Upcoming) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            content()
        }
    }
}

/** Decorative to the accessible tree: position is spoken by the eyebrow, and only the current
 *  node carries body text — a screen reader hears the walkthrough without five bare numerals. */
@Composable
private fun StepCircle(number: Int, state: StepState) {
    when (state) {
        StepState.Done -> Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
        StepState.Current -> Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        StepState.Upcoming -> Box(
            modifier = Modifier
                .size(26.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ValuesCard(
    values: List<CoachingStep>,
    onCompleted: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var index by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (values.isEmpty()) {
            Text(
                stringResource(R.string.recovery_values_empty),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            val value = values[index.coerceIn(0, values.lastIndex)]
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.recovery_values_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(value.actionText, style = MaterialTheme.typography.headlineSmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { index-- },
                    enabled = index > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.recovery_sober_back))
                }
                Button(
                    onClick = { index++ },
                    enabled = index < values.lastIndex,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.recovery_values_next))
                }
            }
        }
        RunnerActions(onCompleted = onCompleted, onStop = onStop)
    }
}

/** Plan items rendered inline during a technique (FR-4: if-then and values show while running). */
@Composable
private fun CoachingItems(items: List<CoachingStep>, emptyRes: Int) {
    if (items.isEmpty()) {
        Text(
            stringResource(emptyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            BulletLine(item.actionText)
        }
    }
}

/** Shared end-of-run actions: the log path ("done") and the quiet path ("stop"). */
@Composable
private fun RunnerActions(onCompleted: () -> Unit, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(4.dp))
        Button(onClick = onCompleted, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.recovery_done))
        }
        TextButton(onClick = onStop, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.recovery_stop))
        }
    }
}
