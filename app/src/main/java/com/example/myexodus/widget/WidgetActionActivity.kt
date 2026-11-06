package com.example.myexodus.widget

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.ui.home.SosViewModel
import com.example.myexodus.ui.sosStateMessage
import com.example.myexodus.ui.theme.MyExodusTheme
import com.example.myexodus.settings.ThemeSettings
import com.example.myexodus.ui.theme.rememberDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The widget's hold-to-confirm surface (widget v2.2, owner rulings 2026-10-15/16/17): the
 * widget's icon controls open this translucent card, and every action — log a relapse, log an
 * urge, reach out — executes only through a full hold of the ring. Release early cancels; the
 * ring filling executes.
 *
 * Why an Activity: no launcher RemoteViews/Glance host delivers touch streams, so the growing
 * ring cannot live on the widget itself (ADR #159; javap-verified against glance-appwidget
 * 1.1.1's action API). The control taps are plain Glance start-activity actions; the accident
 * protection — and the only place touch exists — is here.
 *
 * This surface is also where the action's words live: the widget itself is icon-only (a text
 * pill on the home screen would spell private intent), so naming the action here — after the
 * user deliberately opened it — is the disclosure step, not a leak.
 *
 * The task/lifecycle shape mirrors [com.example.myexodus.blocking.BlockerActivity]: own
 * affinity, out of recents, `exported=false` (it is only ever started through the widget's
 * own PendingIntent, which executes with this app's identity).
 */
@AndroidEntryPoint
class WidgetActionActivity : ComponentActivity() {

    @Inject lateinit var themeSettings: ThemeSettings

    private val viewModel: WidgetActionViewModel by viewModels()

    private var openTarget by mutableStateOf<String?>(null)
    private var openHabitId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!parseTarget(intent)) return
        setContent {
            MyExodusTheme(darkTheme = rememberDarkTheme(themeSettings.mode)) {
                val target = openTarget
                if (target != null) {
                    // Re-opened as a different action (singleTop → onNewIntent): a fresh key
                    // restores the ring to its armed state instead of inheriting the last
                    // session's progress.
                    key(target, openHabitId) {
                        WidgetActionScreen(
                            viewModel = viewModel,
                            target = target,
                            habitId = openHabitId,
                            onDismiss = { finish() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!parseTarget(intent)) return
        viewModel.reset()
    }

    /** False (and finish) for a malformed launch — a relapse control without a bound habitId has
     *  no track to end, and an unknown target has no surface to render. */
    private fun parseTarget(intent: Intent): Boolean {
        val target = intent.getStringExtra(EXTRA_TARGET)
        val habitId = intent.getStringExtra(EXTRA_HABIT_ID)
        val known = target == TARGET_URGE || target == TARGET_RELAPSE || target == TARGET_SOS
        if (!known || (target == TARGET_RELAPSE && habitId == null)) {
            finish()
            return false
        }
        openTarget = target
        openHabitId = habitId
        return true
    }

    companion object {
        const val EXTRA_TARGET = "widget_action_target"
        const val EXTRA_HABIT_ID = "widget_action_habit_id"
        const val TARGET_URGE = "urge"
        const val TARGET_RELAPSE = "relapse"
        const val TARGET_SOS = "sos"
    }
}

/**
 * The control's launch intent, built here so the widget file and this surface share one
 * definition of the contract. NEW_TASK: the tap arrives from the launcher's process; SINGLE_TOP:
 * a second tap reaches [WidgetActionActivity.onNewIntent] instead of stacking a copy. [habitId]
 * binds a relapse to the exact track the tapped control displayed; the other targets ignore it.
 */
internal fun widgetActionIntent(
    context: Context,
    target: String,
    habitId: String? = null,
): Intent =
    Intent(context, WidgetActionActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(WidgetActionActivity.EXTRA_TARGET, target)
        .putExtra(WidgetActionActivity.EXTRA_HABIT_ID, habitId)

/** The success state closes itself once the line has been read; the widget behind the scrim is
 *  the real feedback. Long enough for the sentence, short enough to feel immediate. */
private const val AUTO_CLOSE_MILLIS = 2_000L

/** The whole hold. Sized for a deliberate press — long enough that a pocket tap cannot fire it,
 *  short enough that an intentional hold never feels stalled. */
private const val HOLD_CONFIRM_MILLIS = 1_200

@Composable
private fun WidgetActionScreen(
    viewModel: WidgetActionViewModel,
    target: String,
    habitId: String?,
    onDismiss: () -> Unit,
) {
    val urge by viewModel.urge.collectAsStateWithLifecycle()
    val relapse by viewModel.relapse.collectAsStateWithLifecycle()
    val partner by viewModel.partner.collectAsStateWithLifecycle()
    val sosState by viewModel.sos.state.collectAsStateWithLifecycle()

    val urgeBusy = urge == WidgetActionViewModel.UrgeOutcome.InFlight
    val relapseBusy = relapse == WidgetActionViewModel.RelapseOutcome.InFlight
    val sosBusy = sosState == SosViewModel.SosSendState.Sending
    // Dismissal (scrim tap / Back) is allowed in every state except a write actually racing —
    // a mid-write finish would cancel the view-model scope under the repository call.
    val busy = urgeBusy || relapseBusy || sosBusy

    // The auto-close belongs to the action that produced it: a control tap switching targets
    // (onNewIntent) must not inherit the other action's finishing timer.
    LaunchedEffect(target, urge) {
        if (target == WidgetActionActivity.TARGET_URGE && urgeAutoFinishes(urge)) {
            delay(AUTO_CLOSE_MILLIS)
            onDismiss()
        }
    }
    LaunchedEffect(target, relapse) {
        if (target == WidgetActionActivity.TARGET_RELAPSE && relapseAutoFinishes(relapse)) {
            delay(AUTO_CLOSE_MILLIS)
            onDismiss()
        }
    }
    LaunchedEffect(target, sosState) {
        if (target == WidgetActionActivity.TARGET_SOS &&
            sosState == SosViewModel.SosSendState.Sent
        ) {
            delay(AUTO_CLOSE_MILLIS)
            onDismiss()
        }
    }
    BackHandler(enabled = busy) { /* a write in flight outlives Back */ }

    val context = LocalContext.current
    // What the stored record says about this action's surface, read at open: the disclosure flag
    // for both log actions, and — for a relapse — the label only when the record still binds the
    // habitId this surface will end. A mismatch means the widget render is stale, and naming a
    // track other than the one being ended is exactly the mislabel this guard exists to refuse.
    // Discreet defaults to true until the read lands (FR5: a missing store must never disclose
    // on a guess).
    var surfaceInfo by remember { mutableStateOf(SurfaceInfo(discreet = true, matchedLabel = null)) }
    LaunchedEffect(target, habitId) {
        if (target != WidgetActionActivity.TARGET_URGE &&
            target != WidgetActionActivity.TARGET_RELAPSE
        ) {
            return@LaunchedEffect
        }
        surfaceInfo = withContext(Dispatchers.IO) {
            val snap = runCatching { snapshotFrom(context.widgetSnapshotDataStore.data.first()) }
                .getOrNull()
            SurfaceInfo(
                discreet = snap?.discreet ?: true,
                matchedLabel = snap
                    ?.takeIf {
                        target == WidgetActionActivity.TARGET_RELAPSE &&
                            habitId != null && it.habitId == habitId && !it.discreet
                    }
                    ?.habitLabel,
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { if (!busy) onDismiss() },
        )
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
                .widthIn(max = 340.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (target) {
                    WidgetActionActivity.TARGET_SOS -> SosSurface(
                        viewModel = viewModel,
                        partner = partner,
                        sosState = sosState,
                        onDismiss = onDismiss,
                    )

                    WidgetActionActivity.TARGET_RELAPSE -> RelapseSurface(
                        viewModel = viewModel,
                        habitId = habitId,
                        info = surfaceInfo,
                        outcome = relapse,
                        onDismiss = onDismiss,
                    )

                    else -> UrgeSurface(
                        viewModel = viewModel,
                        discreet = surfaceInfo.discreet,
                        outcome = urge,
                    )
                }
            }
        }
    }
}

/** The stored record's contribution to a log surface. */
private data class SurfaceInfo(val discreet: Boolean, val matchedLabel: String?)

@Composable
private fun RelapseSurface(
    viewModel: WidgetActionViewModel,
    habitId: String?,
    info: SurfaceInfo,
    outcome: WidgetActionViewModel.RelapseOutcome,
    onDismiss: () -> Unit,
) {
    Text(
        text = stringResource(
            if (info.discreet) R.string.widget_hold_reset_title else R.string.widget_hold_relapse_title,
        ),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
    info.matchedLabel?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
    }
    Text(
        text = stringResource(
            if (info.discreet) R.string.widget_hold_reset_body else R.string.widget_hold_relapse_body,
        ),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    HoldRing(
        enabled = relapseHoldArmed(outcome),
        onConfirm = { habitId?.let(viewModel::logRelapse) },
        fill = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (outcome == WidgetActionViewModel.RelapseOutcome.InFlight) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_log_relapse),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.size(40.dp),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    relapseOutcomeRes(outcome, discreet = info.discreet)?.let {
        Text(
            text = stringResource(it),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = when (outcome) {
                WidgetActionViewModel.RelapseOutcome.Logged,
                WidgetActionViewModel.RelapseOutcome.AlreadyLogged,
                -> MaterialTheme.colorScheme.primary

                else -> MaterialTheme.colorScheme.error
            },
        )
        Spacer(Modifier.height(8.dp))
    }
    when (outcome) {
        WidgetActionViewModel.RelapseOutcome.None,
        WidgetActionViewModel.RelapseOutcome.Failed,
        -> HoldHint()

        // The dead end explains itself and offers the only real next step.
        WidgetActionViewModel.RelapseOutcome.Gone -> CloseButton(onDismiss = onDismiss)
        else -> Unit
    }
}

@Composable
private fun UrgeSurface(
    viewModel: WidgetActionViewModel,
    discreet: Boolean,
    outcome: WidgetActionViewModel.UrgeOutcome,
) {
    Text(
        text = stringResource(
            if (discreet) R.string.widget_hold_urge_title_discreet else R.string.widget_hold_urge_title,
        ),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(
            if (discreet) R.string.widget_hold_urge_body_discreet else R.string.widget_hold_urge_body,
        ),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    HoldRing(
        enabled = urgeHoldArmed(outcome),
        onConfirm = viewModel::logUrge,
        fill = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (outcome == WidgetActionViewModel.UrgeOutcome.InFlight) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_log_urge),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.size(40.dp),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    urgeOutcomeRes(outcome, discreet = discreet)?.let {
        Text(
            text = stringResource(it),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
    }
    if (outcome == WidgetActionViewModel.UrgeOutcome.None) HoldHint()
}

@Composable
private fun SosSurface(
    viewModel: WidgetActionViewModel,
    partner: WidgetActionViewModel.PartnerGate,
    sosState: SosViewModel.SosSendState,
    onDismiss: () -> Unit,
) {
    Text(
        text = stringResource(R.string.widget_hold_sos_title),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
    when (partner) {
        WidgetActionViewModel.PartnerGate.Checking -> {
            Text(
                text = stringResource(R.string.widget_hold_checking),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Box(modifier = Modifier.size(124.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        }

        WidgetActionViewModel.PartnerGate.Missing -> {
            Text(
                text = stringResource(R.string.widget_hold_no_partner),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            CloseButton(onDismiss = onDismiss)
        }

        is WidgetActionViewModel.PartnerGate.Ready -> {
            Text(
                text = partner.name?.let {
                    stringResource(R.string.widget_hold_sos_body, it)
                } ?: stringResource(R.string.widget_hold_sos_body_generic),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            val armed = sosState == SosViewModel.SosSendState.Idle ||
                sosState == SosViewModel.SosSendState.CouldNotConfirm
            val defaultMessage = stringResource(R.string.sos_default_message)
            HoldRing(
                enabled = armed,
                onConfirm = { viewModel.logReachOut(defaultMessage) },
                fill = MaterialTheme.colorScheme.primary,
            ) {
                if (sosState == SosViewModel.SosSendState.Sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.ic_widget_reach_out),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            sosStateMessage(sosState)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = if (sosState == SosViewModel.SosSendState.Sent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (armed) HoldHint()
        }
    }
}

@Composable
private fun HoldHint() {
    Text(
        text = stringResource(R.string.widget_hold_hint),
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.widget_hold_close))
    }
}

/**
 * The hold control: the ring encloses the pressed disc and fills linearly over
 * [HOLD_CONFIRM_MILLIS]; release — or a gesture a parent claims (scroll) — before the fill
 * cancels and drains. This is the accident protection (owner spec 2026-10-15): both actions
 * execute only on a completed, deliberate hold.
 *
 * The animation runs on a composition-scoped coroutine, not the gesture coroutine, so the fill
 * is not torn down by pointer-stream cancellation; [LaunchedEffect] on `enabled` is what stops
 * it when the state machine takes over (write in flight), and a new gesture always starts from
 * an empty ring.
 */
@Composable
private fun HoldRing(
    enabled: Boolean,
    onConfirm: () -> Unit,
    fill: Color,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val holdJob = remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            holdJob.value?.cancel()
            holdJob.value = null
            if (progress.value in 0f..1f && progress.value != 0f) {
                progress.animateTo(0f, tween(120))
            }
        } else if (progress.value >= 1f) {
            // Re-armed after a failed attempt: the ring must read empty, not full.
            progress.snapTo(0f)
        }
    }
    DisposableEffect(Unit) { onDispose { holdJob.value?.cancel() } }

    Box(
        modifier = Modifier
            .size(124.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    // snapTo is suspending and awaitEachGesture's scope is restricted — it must
                    // run off-scope. Same coroutine as the fill so the order is strict: the ring
                    // empties, then fills from empty (a still-draining ring from the last release
                    // is cancelled by the snap, never inherited).
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(HOLD_CONFIRM_MILLIS, easing = LinearEasing),
                        )
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConfirm()
                    }
                    holdJob.value = job
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed } || event.changes.all { !it.pressed }) {
                            break
                        }
                    }
                    if (job.isActive) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(120)) }
                    }
                    holdJob.value = null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 5.dp,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(fill),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
