package com.example.myexodus.ui.recovery

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myexodus.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Pacer ring geometry: the moderate chest-movement range the pacer research settled on. */
private const val SCALE_MIN = 0.72f
private const val SCALE_MAX = 1f

private const val TICK_MS = 100L

/**
 * The 4 s in / 6 s out pacer (v2 FR-4). One clock drives everything — the phase word, the ring
 * scale, the per-phase second, the rotating prompt, and the session countdown — so they cannot
 * drift apart. With "remove animations" on (ANIMATOR_DURATION_SCALE 0) the ring stops scaling
 * and the phase words carry the pacing alone.
 */
@Composable
fun BreathingPacer(
    breathInSeconds: Int,
    breathOutSeconds: Int,
    /** Seconds between prompt changes — the catalog's own field, so pacing cannot drift. */
    promptRotationSeconds: Int,
    durationSeconds: Int,
    hapticEnabled: Boolean,
    onHapticChange: (Boolean) -> Unit,
    running: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cycleSeconds = breathInSeconds + breathOutSeconds
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val animationsRemoved = remember(context) { animationsRemoved(context) }
    val haptics = LocalHapticFeedback.current
    // The switch is only reachable while the session runs, so the tick loop must see a toggle
    // made mid-session (owner report 2026-11-08): the Boolean parameter is captured when the
    // effect launches, rememberUpdatedState keeps the live value visible inside it.
    val hapticOn = rememberUpdatedState(hapticEnabled)
    val resources = LocalResources.current
    val prompts = remember(resources) { resources.getStringArray(R.array.recovery_pacer_prompts) }

    val phaseIndex = (elapsedMs / 1000L % cycleSeconds).toInt()
    val inPhase = phaseIndex < breathInSeconds
    // Counts down inside the phase (4,3,2,1) — the number the user paces against.
    val phaseSecond =
        (if (inPhase) breathInSeconds - phaseIndex else cycleSeconds - phaseIndex)
    val remainingSeconds = (durationSeconds - elapsedMs / 1000L).coerceAtLeast(0L)

    // One tick loop owns the clock; every derived value above is a pure function of it.
    LaunchedEffect(running, durationSeconds, cycleSeconds) {
        if (!running) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime() - elapsedMs
        var lastInhale: Boolean? = null
        while (isActive) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            // Finding #14 (2026-10-16): [remainingSeconds] is a composition-time val, and this
            // effect block holds the copy captured when it launched (the canonical stale-lambda
            // case the Compose docs solve with rememberUpdatedState) — testing it here meant
            // onFinished never fired and a session could never end itself. The state read
            // (elapsedMs, just written above) is live, so the boundary is tested on it.
            if (elapsedMs >= durationSeconds * 1000L) {
                onFinished()
                return@LaunchedEffect
            }
            // The haptic marks the in/out change only. Keying it on the cycle second buzzed
            // once a second (owner report 2026-11-08) — the countdown changes each second,
            // the breath does not.
            val inhaling = (elapsedMs / 1000L % cycleSeconds) < breathInSeconds
            if (lastInhale != null && lastInhale != inhaling && hapticOn.value) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            lastInhale = inhaling
            delay(TICK_MS)
        }
    }

    val scale = remember { Animatable(SCALE_MIN) }
    LaunchedEffect(phaseIndex, running, animationsRemoved) {
        if (!running || animationsRemoved) return@LaunchedEffect
        val target = if (inPhase) SCALE_MAX else SCALE_MIN
        val phaseMs = if (inPhase) breathInSeconds * 1000 else breathOutSeconds * 1000
        scale.animateTo(target, tween(phaseMs, easing = LinearEasing))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.recovery_pacer_remaining, remainingSeconds / 60, remainingSeconds % 60),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .size(220.dp)
                .scale(if (animationsRemoved) SCALE_MIN else scale.value),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(220.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(
                        if (inPhase) R.string.recovery_pacer_breathe_in
                        else R.string.recovery_pacer_breathe_out,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "$phaseSecond",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (prompts.isNotEmpty()) {
            Text(
                prompts[((elapsedMs / 1000L) / promptRotationSeconds % prompts.size).toInt()],
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hapticsLabel = stringResource(R.string.recovery_pacer_haptics)
            Text(hapticsLabel, style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = hapticEnabled,
                onCheckedChange = onHapticChange,
                // The switch is a sibling of its label, not its child: without this its
                // accessible name is a bare "On/Off" (findings #25/#26's class).
                modifier = Modifier.semantics { contentDescription = hapticsLabel },
            )
        }
    }
}

/** Plan v3 W1: honor the system "remove animations" setting (ANIMATOR_DURATION_SCALE 0). */
private fun animationsRemoved(context: Context): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

