package com.example.myexodus.ui.partner

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myexodus.R
import com.example.myexodus.accountability.monitoring.PartnerConnection
import com.example.myexodus.accountability.monitoring.PartnerConnectionBucket
import com.example.myexodus.accountability.monitoring.PartnerStatus
import com.example.myexodus.accountability.plan.PartnerPlanRepository
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.MonitoringCapability
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.state.UnpairPhase
import com.example.myexodus.accountability.state.UnpairState
import com.example.myexodus.accountability.sync.MessageSendOutcome
import com.example.myexodus.accountability.sync.PartnerAlertHealth
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.ui.AppMark
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.PillTone
import com.example.myexodus.ui.StatusPill
import com.example.myexodus.ui.settings.IndexRow
import com.example.myexodus.ui.settings.RowDivider
import com.example.myexodus.ui.settings.StatusTone
import com.example.myexodus.accountability.serialization.MonitoringState
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.insights.shareOf
import com.example.myexodus.ui.insights.shortDayLabel
import com.example.myexodus.ui.recovery.outcomeLabel
import com.example.myexodus.ui.recovery.techniqueTitle
import com.example.myexodus.ui.relativeTimeText
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberWindowWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * How a user-initiated unpair ended. A cancelled biometric prompt is not a failure: the
 * user chose to stop, nothing was journaled, and the pair is untouched — so the screen must
 * say nothing rather than tell them something went wrong.
 */
enum class UnpairOutcome { UNPAIRED, CANCELLED, FAILED }

/**
 * Subpage ids for the paired state's split (ADR #78). The other pair states — unpaired,
 * wipe-detected, ended — already render a single card, and splitting those would create the
 * one-control page the owner's ruling forbids, so they get no subpages at all.
 */
internal const val VIEW_REQUESTS = "requests"
internal const val VIEW_ACTIVITY = "activity"
/** Owner directive 2026-11-01: the partner's device signals (monitoring + connection
 *  check-ins) get their own named destination instead of two equal-weight hub cards.
 *  Internal since 2026-11-10: the permission-lost notification lands here. */
internal const val VIEW_CHECKINS = "checkins"
/** Owner ask 2026-10-21: partner sharing moved out of Settings and onto this tab — the
 *  consent surface belongs beside the partners it names. */
private const val VIEW_SHARING = "sharing"
private const val VIEW_PLANS = "plans"
/** Owner mandate 2026-10-31: received rule/batch lifecycle rows as an organized page, not
 *  just the feed preview. */
internal const val VIEW_RULES = "rules"
private const val VIEW_PAIRING = "pairing"

/** A partner-selection deep link returns to the hub — unless the same tap also named a plan
 *  (the plan surface owns that landing) or a subpage (owner ask 2026-11-02: the tap must land
 *  where the event is listed, not one tap short on the hub). */
internal fun pairDeepLinkView(requestedPlanId: String?, requestedViewId: String? = null): String? =
    when {
        requestedPlanId != null -> VIEW_PLANS
        requestedViewId != null -> requestedViewId
        else -> null
    }

/** §19.4 item 4: the ceremony's two entry modes, as `view` values alongside the subpages. */
private const val VIEW_CEREMONY_INVITE = "ceremony-invite"
private const val VIEW_CEREMONY_JOIN = "ceremony-join"

/**
 * The three emphases a partner card can carry. Before this existed every card on the tab was
 * the same `surfaceContainerLow` block, so the partner's identity, a transient sync warning and
 * the way to end the partnership all read at one weight — the page had no hierarchy to skim.
 * Escalation is reserved: [CALM] is the default, [NOTICE] is "this changed", [ALERT] is "this
 * can't be trusted".
 */
private enum class CardTone { CALM, NOTICE, ALERT }

@Composable
private fun CardTone.container(): Color = when (this) {
    CardTone.CALM -> MaterialTheme.colorScheme.surfaceContainerLow
    CardTone.NOTICE -> MaterialTheme.colorScheme.secondaryContainer
    CardTone.ALERT -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun CardTone.onContainer(): Color = when (this) {
    CardTone.CALM -> MaterialTheme.colorScheme.onSurface
    CardTone.NOTICE -> MaterialTheme.colorScheme.onSecondaryContainer
    CardTone.ALERT -> MaterialTheme.colorScheme.onErrorContainer
}

/** The one card shape on this tab, so every card is the same object with a different tone. */
@Composable
private fun PartnerSurface(
    tone: CardTone = CardTone.CALM,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = tone.container(),
        contentColor = tone.onContainer(),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/**
 * One labelled stack of index rows (owner directive 2026-11-09). The hub's destinations used
 * to be a two-column tile grid; grouped rows carry the same live datum at a fraction of the
 * height and read as an order instead of a dashboard. The row chrome (bare icon, chevron,
 * divider inset) is the one Settings already uses, so both tabs read as one app.
 */
@Composable
private fun HubGroup(titleRes: Int, rows: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = rows)
        }
    }
}

/**
 * The chain verdict as one quiet line under the identity. The glyph and the words change with
 * the state, so the verdict never depends on colour alone — the tint only reinforces it. A
 * healthy chain still draws (`VerifiedUser`): the line's job is to say "checked and fine",
 * which is a different statement from the warning cards' absence. Owner directive 2026-11-09:
 * the old 40dp tinted disc put a coloured badge in front of the person's name and made the
 * verification state look like decoration; the line reads as a sentence instead.
 */
@Composable
private fun ChainVerdictLine(status: PartnerChainStatus) {
    val (icon, text) = when (status) {
        PartnerChainStatus.HEALTHY ->
            Icons.Outlined.VerifiedUser to stringResource(R.string.partner_chain_verified_mark)
        PartnerChainStatus.CATCHING_UP ->
            Icons.Outlined.Sync to stringResource(R.string.partner_chain_catching_up_title)
        PartnerChainStatus.INCOMPLETE ->
            Icons.Outlined.History to stringResource(R.string.partner_chain_incomplete_title)
        PartnerChainStatus.UNVERIFIED ->
            Icons.Outlined.GppBad to stringResource(R.string.partner_chain_unverified_title)
    }
    val tint = if (status == PartnerChainStatus.UNVERIFIED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/**
 * Owner report 2026-11-12: "6 hours ago" froze while the page stayed open. Partner surfaces
 * read the screen's 30 s tick through this instead of the wall clock, so labels age in place.
 */
internal val LocalPartnerNowMillis = compositionLocalOf { System.currentTimeMillis() }

/**
 * Partner tab (plan §7 items 12-13 / V1 Req 7): last-known partner state from verified local
 * events, with an explicit "last synced" line — cache-first per §11 (the load is a local
 * Room read, no network on paint). The screen owns no persistence and never overstates
 * delivery: the sync cadence is best-effort (AD-05), and the §9 chain states are shown
 * exactly as neutral (INCOMPLETE) or trust-blocking (UNVERIFIED), never blended.
 *
 * Step 13: pending disable-approval requests render with the §6 hash-chain verification badge
 * before the Approve/Reject buttons; an UNVERIFIED chain disables responding outright.
 */
@Composable
fun PartnerScreen(
    source: PartnerDashboardSource,
    /** Owner ask 2026-10-04: the sharing-matrix read behind the "nothing shared yet" card. */
    habitRepository: HabitRepository,
    /** ADR #193: partner plans — the shared checklist surface reached from the hub. */
    planRepository: PartnerPlanRepository,
    notificationsEnabled: Boolean,
    onFixNotifications: () -> Unit,
    onCheckNow: suspend () -> Boolean,
    modifier: Modifier = Modifier,
    /** Owner ruling 2026-11-09: assigned blocks are listed on "What you share" automatically. */
    loadRuleSharing: suspend () -> Map<String, List<RuleSharingItem>> = { emptyMap() },
    /** Revokes one item there; the unassign journals, so the partner is notified. */
    disconnectRule: suspend (RuleSharingItem) -> Boolean = { true },
    /** §8 Step 16: true while the system dialog can still be raised (33+, not yet asked);
     *  false means the only fix path left is the settings screen. */
    notificationsRequestable: Boolean = false,
    /** Plan Step 6: what actually blocks a partner alert on this device — permission, the
     *  partner-activity channel's own switch, or a missing push provider. */
    alertHealth: PartnerAlertHealth.State = PartnerAlertHealth.State.OK,
    onRespondToApproval: suspend (requestEventId: String, approve: Boolean) -> Boolean = { _, _ -> false },
    /** Step 14: biometric-gated unpair initiation. CANCELLED (refused prompt) leaves the pair
     *  intact — the local shred is unconditional once the request is acted on, so a refused
     *  prompt must abort before anything is journaled. */
    onUnpair: suspend (pairId: String) -> UnpairOutcome = { UnpairOutcome.FAILED },
    /** Step 14: deletes the dismissed terminal card's journal row (guarded in the journal). */
    onDismissUnpair: suspend (pairId: String) -> Unit = {},
    /** §19.5 requirement 1: the persisted supported-user pick. Null (the harness path) keeps
     *  the selection session-local only. */
    selection: PartnerSelection? = null,
    /** §19.4 item 4: the ceremony's seams. Null hides every pairing entry point — the
     *  harness/preview path, where no production ceremony can be raised. */
    ceremony: PairingCeremonyDeps? = null,
    /** Owner report 2026-09-28: legacy pairs have no exchanged label, so the roster falls back
     *  to a key-id fragment — useless for telling people apart. The partner captions a row
     *  locally; the label never leaves this phone. */
    onRenameUser: suspend (pairId: String, label: String?) -> Unit = { _, _ -> },
    /** Req 15: journals a motivational message for this profile, addressed to [pairId] — the
     *  partner the composer names (#96) — and kicks a sync pass so it leaves the phone now.
     *  The outcome is honest about what actually happened (owner UX ruling 2026-10-01):
     *  [MessageSendOutcome.SENT] = written to the partner's mailbox; [MessageSendOutcome.QUEUED]
     *  = saved, leaves on the retry cadence; [MessageSendOutcome.FAILED] = nothing written. */
    onSendMessage: suspend (pairId: String, text: String) -> MessageSendOutcome =
        { _, _ -> MessageSendOutcome.FAILED },
    /** Plan Step 8: the server-derived connection reading (§8.10), coarse buckets only. Null =
     *  no honest answer right now — the card then claims nothing. */
    onLoadPartnerStatus: suspend (pairId: String) -> PartnerStatus? = { null },
    /** ADR #193 deep link: the plan a notification named — the Plans subpage opens it. */
    requestedPlanId: String? = null,
    onPlanConsumed: () -> Unit = {},
    /** Owner report 2026-10-31: a notification names ONE partner; its tap must land on that
     *  partner's dashboard even when a different roster entry is currently selected. */
    requestedPairId: String? = null,
    onPairConsumed: () -> Unit = {},
    /** Owner ask 2026-11-02: the subpage a notification concerns — activity, rules, requests.
     *  A plan extra wins over this one; both ride the same tap. */
    requestedViewId: String? = null,
    onViewConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // A relative label must age while the page stays open (owner report 2026-11-12); the tick
    // repaints only the composables that read the clock — nothing re-reads Room for it.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    var state by remember { mutableStateOf<PartnerDashboardUi?>(null) }
    var view by rememberSaveable { mutableStateOf<String?>(null) }
    // Hoisted from PartnerPlansScreen so a notification deep link can open the plan before the
    // subpage composes; cleared whenever the subpage is left so a later visit starts at the list.
    var openPlanId by rememberSaveable { mutableStateOf<String?>(null) }
    // System back leaves a subpage the way its BackRow does. The ceremony registers its own
    // handler later in composition — it must cancel the live token, so it wins while open.
    BackHandler(enabled = view != null) {
        view = null
        openPlanId = null
    }
    LaunchedEffect(requestedPlanId) {
        val planId = requestedPlanId ?: return@LaunchedEffect
        view = VIEW_PLANS
        openPlanId = planId
        onPlanConsumed()
    }
    var selectedPairId by rememberSaveable { mutableStateOf(selection?.selectedPairId) }
    var checking by remember { mutableStateOf(false) }
    var checkFailed by remember { mutableStateOf(false) }
    var checkOk by remember { mutableStateOf(false) }
    // Owner-approved A (2026-11-09): rows queued behind a cold signing window say so here,
    // instead of the page looking healthy while the partner's phone hears nothing.
    val signingDeferred by PartnerSyncSignals.signingDeferred.collectAsState()
    var respondingTo by remember { mutableStateOf<String?>(null) }
    var respondFailed by remember { mutableStateOf(false) }
    var confirmingUnpair by remember { mutableStateOf(false) }
    var unpairing by remember { mutableStateOf(false) }
    var unpairFailed by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PartnerUserUi?>(null) }
    var switching by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var messageSending by remember { mutableStateOf(false) }
    var messageOutcome by remember { mutableStateOf<MessageSendOutcome?>(null) }
    // Owner ask 2026-10-04: true = paired but nothing is being shared with the active partner.
    // Null = not paired, or the store refused a read — no honest claim, so no card.
    var sharesNothing by remember { mutableStateOf<Boolean?>(null) }
    // ADR #193: the hub tile's live datum; null = no honest count (store refused, or still
    // loading), which renders as the tile's description rather than a fabricated zero.
    var planHub by remember { mutableStateOf<PlanHubStatus?>(null) }
    var planHubRefresh by remember { mutableIntStateOf(0) }
    // Plan Step 8 (§8.13): the partner's server-derived connection signal, kept apart from the
    // E2EE monitoring report and from sync freshness. Null = no honest reading.
    var partnerStatus by remember { mutableStateOf<PartnerStatus?>(null) }

    // Finding #57: roster switches start overlapping reloads (every roster row fires one), and
    // IO-pool completions can arrive out of order. Only the newest reload may publish, or a
    // fast A -> B switch could leave A's dashboard under B's selection.
    val reloadEpoch = remember { AtomicInteger(0) }
    suspend fun reload() {
        val epoch = reloadEpoch.incrementAndGet()
        val loaded = runCatching { source.load(selectedPairId = selectedPairId, nowMillis = System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "dashboard load failed", it) }
            .getOrElse {
                // A failed reload keeps the last known state only while it describes the same
                // subject; under a different selection it would be partner A's dashboard shown
                // as partner B's (finding #57's mis-attribution, on the failure path).
                if (keepsUnderSelection(state, selectedPairId)) state ?: PartnerDashboardUi.Loading
                else PartnerDashboardUi.Loading
            }
        if (epoch != reloadEpoch.get()) return
        state = loaded
        // The source falls back to the newest pairing when the stored pick is gone (unpaired,
        // or a fresh install's first pair): persist the resolved id, so the next cold start
        // shows this user instead of re-deciding.
        (state as? PartnerDashboardUi.Paired)?.let { paired ->
            if (paired.pairId != selectedPairId) {
                selectedPairId = paired.pairId
                selection?.selectedPairId = paired.pairId
            }
        }
        // Owner ask 2026-10-04 ("you have added a partner... but not shared anything"): computed
        // inside reload() because this is the one refresh path every repaint routes through —
        // pulls, resume, pair switches. A failure reads as "unknown", never as a false claim.
        val shares = (state as? PartnerDashboardUi.Paired)?.let { paired ->
            runCatching { habitRepository.sharesNothingWith(paired.pairId) }.getOrNull()
        }
        // Plan Step 8: every repaint path (pull, resume, switch, check-now) refreshes the
        // connection reading through the client's one-call-a-minute bound, so a burst of
        // reloads can never become a callable burst. A failure clears it rather than repeating
        // a stale claim from before the silence.
        val status = (state as? PartnerDashboardUi.Paired)?.let { paired ->
            runCatching { onLoadPartnerStatus(paired.pairId) }
                .onFailure { Log.w(TAG, "partner status read failed", it) }
                .getOrNull()
        }
        // Finding #57 covers these two reads as well: they belong to the pair this reload
        // loaded, so a reload that lost the race to a newer one must publish neither — writing
        // A's "nothing shared" or connection reading under B's selection is the same stale
        // dashboard the state guard above closes.
        if (epoch != reloadEpoch.get()) return
        sharesNothing = shares
        partnerStatus = status
    }

    /** §19.5: bring another supported user's dashboard forward — persisted, then reloaded.
     *  The subpage stays open (#96): switching who you are looking at must not also navigate
     *  you away — the reload re-scopes every stream this page renders to the new pair. */
    fun selectUser(pairId: String) {
        selectedPairId = pairId
        selection?.selectedPairId = pairId
        scope.launch(Dispatchers.IO) { reload() }
    }

    // Finding #56 (the #17/#55 class): every in-flight flag below is written on Main BEFORE
    // the dispatch, with an entry guard. Written inside the IO coroutine, the queue time before
    // dispatch left a second tap window — for the composer that meant the same message
    // journaled and sent twice, and for approve/reject/unpair a duplicated state-changing write.
    val runCheckNow: () -> Unit = {
        if (!checking) {
            checking = true
            checkFailed = false
            checkOk = false
            scope.launch(Dispatchers.IO) {
                val ok = runCatching { onCheckNow() }.getOrDefault(false)
                checking = false
                checkFailed = !ok
                // A successful check with nothing new leaves the "last synced" watermark
                // unchanged, so without a receipt the button looks like it did nothing.
                checkOk = ok
                reload()
            }
        }
    }

    fun respondTo(requestEventId: String, approve: Boolean) {
        if (respondingTo != null) return
        respondingTo = requestEventId
        respondFailed = false
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { onRespondToApproval(requestEventId, approve) }
                .onFailure { Log.w(TAG, "approval response threw", it) }
                .getOrDefault(false)
            respondingTo = null
            respondFailed = !ok
            reload()
        }
    }

    fun runUnpair(pairId: String) {
        if (unpairing) return
        unpairing = true
        unpairFailed = false
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching { onUnpair(pairId) }
                .onFailure { Log.w(TAG, "unpair initiation threw", it) }
                .getOrDefault(UnpairOutcome.FAILED)
            unpairing = false
            unpairFailed = outcome == UnpairOutcome.FAILED
            reload()
        }
    }

    // Keyed on [view] as well: the Sharing subpage owns its own saves and there is no resume
    // between it and this screen, so returning here must re-read — otherwise the "nothing
    // shared" card and the composer's ADR #135 gate keep the toggles' pre-visit answer.
    LaunchedEffect(view) { reload() }

    // A notification names one partner; opening it must show THAT partner, not whichever
    // roster entry was selected last (owner report 2026-10-31).
    LaunchedEffect(requestedPairId) {
        val pairId = requestedPairId ?: return@LaunchedEffect
        selectedPairId = pairId
        selection?.selectedPairId = pairId
        // The plan notification carries BOTH extras; its plan surface owns the landing, so
        // this effect must not reset the view the plan effect claims (whichever runs first).
        view = pairDeepLinkView(requestedPlanId, requestedViewId)
        reload()
        onPairConsumed()
        onViewConsumed()
    }

    // Owner UX ruling 2026-10-01: a background pass (FCM wake, periodic, check-now elsewhere)
    // that pulled partner events repaints this page in place. The dashboard is a one-shot
    // read, so without this tick an arrival waits for a resume — the exact "nothing moves
    // until I press something" annoyance the ruling retires.
    LaunchedEffect(Unit) {
        PartnerSyncSignals.pulledEvents.collect {
            reload()
            planHubRefresh++
        }
    }

    // The partner's events arrive via background syncs; re-read on every resume so the tab
    // is never a stale frame (the local read is cheap — §11's cache-first constraint).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch(Dispatchers.IO) { reload() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // §19.4 item 4: the ceremony owns the screen while it runs (it renders its own back row),
    // so the dashboard below is neither composed nor reloaded under it. Exiting reloads: a
    // completed pairing must show up in the hub the moment the user lands back on it.
    val deps = ceremony
    if (deps != null && (view == VIEW_CEREMONY_INVITE || view == VIEW_CEREMONY_JOIN)) {
        PairingCeremonyScreen(
            mode = if (view == VIEW_CEREMONY_INVITE) CeremonyMode.INVITE else CeremonyMode.JOIN,
            deps = deps,
            notificationsEnabled = notificationsEnabled,
            notificationsRequestable = notificationsRequestable,
            onFixNotifications = onFixNotifications,
            alertHealth = alertHealth,
            onExit = {
                view = null
                scope.launch(Dispatchers.IO) { reload() }
            },
            modifier = modifier,
        )
        return
    }

    /** §19.4 item 4: one way into the ceremony — null on the harness path, where no production
     *  ceremony exists and the "pair again" cards render without their actions. */
    val ceremonyEntry: ((CeremonyMode) -> Unit)? = if (deps != null) {
        { mode -> view = if (mode == CeremonyMode.INVITE) VIEW_CEREMONY_INVITE else VIEW_CEREMONY_JOIN }
    } else {
        null
    }

    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    val paired = state as? PartnerDashboardUi.Paired
    // ADR #193: the Plans tile's datum. Read on the hub only — the subpage reads its own rows,
    // and a count behind an open subpage is never seen.
    LaunchedEffect(paired?.pairId, view, planHubRefresh) {
        if (view != null) return@LaunchedEffect
        planHub = withContext(Dispatchers.IO) {
            runCatching {
                val plans = planRepository.plans() ?: return@runCatching null
                if (plans.isEmpty()) {
                    PlanHubStatus(hasPlans = false, todo = null)
                } else {
                    val incoming = plans.filter { !it.authored && it.pairId != null }
                    val ids = incoming.mapTo(HashSet()) { it.planId }
                    PlanHubStatus(
                        hasPlans = true,
                        todo = if (incoming.isEmpty()) {
                            null
                        } else {
                            planRepository.allTasks().count { it.planId in ids && !it.done }
                        },
                    )
                }
            }.getOrNull()
        }
    }
    // ADR #193: the plans surface owns the whole screen, the same shape as SharingScreen.
    if (view == VIEW_PLANS && paired != null) {
        CompositionLocalProvider(LocalPartnerNowMillis provides nowMillis) {
            PartnerPlansScreen(
                planRepository = planRepository,
                loadPartners = { source.pairRoster() },
                openPlanId = openPlanId,
                onOpenPlan = { openPlanId = it },
                onBack = {
                    view = null
                    openPlanId = null
                },
                modifier = modifier,
            )
        }
        return
    }
    // The consent surface owns the whole screen (its own BackRow walks overview → partner →
    // switches). Rendering it inside this screen's scroller would nest two vertical scrolls.
    if (view == VIEW_SHARING && paired != null) {
        SharingScreen(
            habitRepository = habitRepository,
            loadPartnerNames = { source.partnerDisplayNames() },
            onBack = { view = null },
            modifier = modifier,
            loadRuleSharing = loadRuleSharing,
            disconnectRule = disconnectRule,
        )
        return
    }
    // Owner directive 2026-11-09 (devices, 2026-11-09): one scroll state served the hub and
    // every subpage, so opening a page while the hub was scrolled down dropped the viewer
    // mid-page with the back row off-screen. Each page change starts at its top.
    val scroller = rememberScrollState()
    LaunchedEffect(view) { scroller.scrollTo(0) }
    CompositionLocalProvider(LocalPartnerNowMillis provides nowMillis) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroller)
            .padding(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // A subpage owns the screen: the back row names it, and repeating the page title above
        // content it does not describe would spend 48dp to say nothing.
        if (view == null || paired == null) {
            Column {
                Text(stringResource(R.string.partner_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.partner_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // §9 row: permission denied is a persistent state, never a silent one (acceptance
            // 2026 line 631). §8 Step 16: while the dialog can still be raised the action
            // requests it; once denied the fix path is the system settings screen, not an
            // in-app nag. Plan Step 6 widens this to the two failures a permission check
            // cannot see: the partner-activity channel muted on its own, and no push provider.
            when (alertHealth) {
                // Owner question 2026-11-01 ("is the test alert needed?"): it is — it proves
                // the display path — but on a healthy device it does not deserve a hub row.
                // The action lives on the Pairing page; the failure states below keep it when
                // it is the diagnostic that matters.
                PartnerAlertHealth.State.OK -> Unit

                PartnerAlertHealth.State.NOTIFICATIONS_OFF -> AlertHealthBanner(
                    title = stringResource(R.string.partner_notifications_off_title),
                    body = stringResource(R.string.partner_notifications_off_body),
                    action = stringResource(
                        if (notificationsRequestable) {
                            R.string.partner_notifications_enable
                        } else {
                            R.string.partner_notifications_open_settings
                        },
                    ),
                    onAction = onFixNotifications,
                )

                PartnerAlertHealth.State.CHANNEL_OFF -> AlertHealthBanner(
                    title = stringResource(R.string.partner_alerts_channel_off_title),
                    body = stringResource(R.string.partner_alerts_channel_off_body),
                    action = stringResource(R.string.partner_alerts_channel_off_action),
                    onAction = onFixNotifications,
                )

                PartnerAlertHealth.State.PROVIDER_UNSUPPORTED -> AlertHealthBanner(
                    title = stringResource(R.string.partner_alerts_provider_title),
                    body = stringResource(R.string.partner_alerts_provider_body),
                    action = null,
                    onAction = {},
                )
            }
        }

        when (val current = state) {
            null, PartnerDashboardUi.Loading -> Text(
                stringResource(R.string.partner_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PartnerDashboardUi.Unpaired -> PairingGoneCard(
                titleRes = R.string.partner_unpaired_title,
                bodyRes = R.string.partner_unpaired_body,
                onRePair = ceremonyEntry,
            )

            // §8 Step 17 (AD-22): says plainly what was lost and the one route forward —
            // re-enrollment (the ceremony, since §19.4 item 4). No recovery: the old pairing
            // cannot be restored, only replaced.
            PartnerDashboardUi.WipeDetected -> PairingGoneCard(
                titleRes = R.string.partner_wipe_title,
                bodyRes = R.string.partner_wipe_body,
                onRePair = ceremonyEntry,
            )

            is PartnerDashboardUi.Ended -> EndedCard(
                state = current,
                checking = checking,
                checkFailed = checkFailed,
                dismissing = dismissing,
                onRePair = ceremonyEntry,
                onCheckNow = runCheckNow,
                onDismiss = {
                    if (!dismissing) {
                        dismissing = true
                        scope.launch(Dispatchers.IO) {
                            runCatching { onDismissUnpair(current.pairId) }
                            dismissing = false
                            reload()
                        }
                    }
                },
            )

            is PartnerDashboardUi.Paired -> if (view != null) {
                BackRow(
                    label = stringResource(
                        when (view) {
                            VIEW_REQUESTS -> R.string.partner_tile_requests
                            VIEW_ACTIVITY -> R.string.partner_tile_activity
                            VIEW_CHECKINS -> R.string.partner_tile_checkins
                            VIEW_RULES -> R.string.partner_tile_rules
                            else -> R.string.partner_tile_pairing
                        },
                    ),
                    onBack = { view = null },
                )
                // #96 (owner ruling): a subpage acts on one partner, so it says which one, and
                // lets the viewer switch right there. Owner ruling 2026-09-29 (live review): the
                // Requests queue is the exception — a person line above a decision list read as
                // a control to fiddle with instead of a queue to clear, so it is gone there; the
                // hub's roster is one tap back.
                // Owner directive 2026-11-09: one quiet person line, not a tinted band — the
                // band plus the hub hero made every subpage open with the same "Viewing X" twice.
                if (view != VIEW_REQUESTS) {
                    PartnerContextLine(
                        label = current.partnerLabel ?: shortKeyId(current.partnerKeyId),
                        canSwitch = current.users.size > 1,
                        onSwitch = { switching = true },
                    )
                }
                when (view) {
                    VIEW_REQUESTS -> RequestsView(
                        state = current,
                        respondingTo = respondingTo,
                        respondFailed = respondFailed,
                        onRespond = ::respondTo,
                    )
                    VIEW_ACTIVITY -> {
                        // Owner ruling 2026-10-31 ("each page has its own responsibility"): this
                        // page shows the partner's activity. The composer stays an action, and
                        // since ADR #285 it never asks the viewer to share before sending — a
                        // supervisor who shares nothing is the message's whole audience.
                        //
                        // Owner directive 2026-11-01: the shared aggregate leads this page — the
                        // hub tile answers "how are they doing?", and this is where that answer
                        // gets its detail instead of stretching the hub.
                        // Owner directive 2026-11-09: with nothing shared there is no
                        // summary — the activity card below already says why the page is
                        // empty, and a second "nothing shared" sentence above it repeated
                        // the same absence in two cards.
                        current.activity?.takeIf(::showsPartnerSummary)
                            ?.let { PartnerSummaryCard(it, current.chainStatus) }
                        ActivityChartCard(current.alerts)
                        // Owner directive 2026-11-09: the answer (summary + picture + list)
                        // comes before the composer. The send action used to sit between the
                        // chart and the list, splitting one story in half.
                        ActivityCard(current.alerts, current.activity)
                        ComposeMessageCard(
                            onCompose = {
                                // A dismissal while a send was in flight must not surface its
                                // stale receipt on the next open.
                                messageOutcome = null
                                composing = true
                            },
                        )
                        // Owner ask 2026-10-01: the writer sees their own last message here —
                        // the mirror of the hub's "Their message to you".
                        current.sentMessageText?.let { SentMessageCard(it) }
                    }
                    VIEW_CHECKINS -> CheckinsView(state = current, status = partnerStatus)
                    VIEW_RULES -> RulesView(current)
                    else -> PairingView(
                        state = current,
                        checking = checking,
                        checkFailed = checkFailed,
                        checkOk = checkOk,
                        signingDeferred = signingDeferred,
                        onCheckNow = runCheckNow,
                        unpairing = unpairing,
                        unpairFailed = unpairFailed,
                        onStartUnpair = { confirmingUnpair = true },
                        // §19.5: pairing again is how the roster grows; hidden on the harness
                        // path where no production ceremony exists.
                        onAddUser = ceremonyEntry,
                    )
                }
            } else {
                // Lean hub (ADR #78, signed off 2026-09-22): the hub answers "does anything need
                // me?" — the chain verdict, anything time-boxed, and a sync that has something to
                // say. Verdict-first order is deliberate: a chain problem is the one thing the
                // viewer may have to act on.
                //
                // ADR #84 corrected what that answer looked like in the good case. Identity was
                // filed as reference material one tile away, which left the tab named "Partner"
                // never naming the partner, and left a healthy pair rendering as blank space below
                // the tiles. The identity line is now the hub's first row, and each tile carries
                // the state its subpage would otherwise spend a tap to say.
                val pending = current.approvals.filter {
                    it.role == ApprovalRequest.ROLE_RESPONDER && it.status == ApprovalRequest.STATUS_REQUESTED
                }
                // Owner directive 2026-11-01: identity and switcher are one card. Tapping the
                // person you are viewing opens "People you support" — switch, rename, or add
                // someone — so the roster no longer spends its own card on the hub.
                PartnerIdentityCard(current, onOpenSwitcher = { switching = true })
                // Owner ask 2026-10-21 ("everything is screaming for my attention"): every
                // time-boxed card now shares one labelled group, so the hub reads as identity,
                // then "Needs you", then the partner's state — instead of six equal-weight
                // blocks competing. An empty group renders nothing, not a heading.
                // Owner directive 2026-11-09: "nothing shared" is something the viewer may have
                // to act on, so it joins this group instead of floating below the tiles.
                val needsYou = current.pairingUnreachable ||
                    current.chainStatus != PartnerChainStatus.HEALTHY ||
                    pending.isNotEmpty() ||
                    sharesNothing == true ||
                    checkFailed ||
                    signingDeferred ||
                    syncNeedsAttention(current)
                if (needsYou) {
                    Text(
                        stringResource(R.string.partner_needs_you),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // OPD #30: the pairing's document denies this device, so nothing has synced
                    // and nothing can until a new pairing exists. Verdict first — a pairing that
                    // cannot sync makes the summary below a stale claim.
                    if (current.pairingUnreachable) {
                        PairingGoneCard(
                            titleRes = R.string.partner_unreachable_title,
                            bodyRes = R.string.partner_unreachable_body,
                            onRePair = ceremonyEntry,
                        )
                    }
                    ChainStatusCard(current.chainStatus)
                    if (pending.isNotEmpty()) {
                        ApprovalRequestCard(
                            requests = pending,
                            chainStatus = current.chainStatus,
                            respondingTo = respondingTo,
                            respondFailed = respondFailed,
                            onRespond = ::respondTo,
                        )
                    }
                    // AD-05 staleness is never hidden: the sync card's full detail lives on the
                    // Pairing page, but it returns to the hub whenever it has something the user
                    // may have to act on. A healthy sync stays quiet here.
                    if (checkFailed || signingDeferred || syncNeedsAttention(current)) {
                        SyncCard(
                            state = current,
                            checking = checking,
                            checkFailed = checkFailed,
                            checkOk = checkOk,
                            signingDeferred = signingDeferred,
                            onCheckNow = runCheckNow,
                            tone = CardTone.NOTICE,
                            prominentAction = false,
                        )
                    }
                    // Owner ask 2026-10-04: a pair with every switch off looks identical to an
                    // active one, and the partner silently receives nothing. Said once, with the
                    // one route to fixing it.
                    if (sharesNothing == true) {
                        NothingSharedCard(
                            partnerName = current.partnerLabel ?: shortKeyId(current.partnerKeyId),
                            onOpenSharing = { view = VIEW_SHARING },
                        )
                    }
                }
                // Owner directive 2026-11-09: destinations are grouped index rows, not a grid
                // of tiles. The tile grid spent a third of the hub's height repeating its own
                // labels, wrapped "None shared with you yet" mid-phrase and left Requests
                // floating alone; grouped rows read top-down in the order the viewer acts —
                // their progress, then rules and plans, then the management surfaces.
                HubGroup(R.string.partner_group_progress) {
                    IndexRow(
                        icon = Icons.Outlined.Timeline,
                        title = stringResource(R.string.partner_tile_activity),
                        // Days free is the number the user expects this row to answer
                        // (owner report 2026-10-31: "activity is always 0" — the old tile
                        // counted only this week's events, so a shared streak read as zero).
                        // ADR #285: a snapshot-less message writes only the pin, and its
                        // placeholder row reads days_free 0 — that is "nothing shared", never
                        // a zero-day streak. The page's own card applies the same rule.
                        subtitle = when (val activity = current.activity) {
                            null -> stringResource(R.string.partner_tile_activity_none)
                            else -> {
                                // Owner report 2026-11-09: the row said "1 day free" without
                                // naming the streak it counts. With per-streak rows present,
                                // name the newest one; pre-v3 senders keep the aggregate form.
                                val newest = activity.streaks
                                    .maxByOrNull { it.lastEventOccurredAtEpochMillis }
                                when {
                                    newest != null -> pluralStringResource(
                                        R.plurals.partner_tile_activity_days_named,
                                        newest.daysFree,
                                        newest.label.ifBlank {
                                            stringResource(R.string.partner_summary_streak_unnamed)
                                        },
                                        newest.daysFree,
                                    )
                                    activity.lastEventOccurredAtEpochMillis == null ->
                                        stringResource(R.string.partner_tile_activity_none)
                                    else -> pluralStringResource(
                                        R.plurals.partner_tile_activity_days,
                                        activity.daysFree,
                                        activity.daysFree,
                                    )
                                }
                            }
                        },
                        onClick = { view = VIEW_ACTIVITY },
                    )
                    RowDivider()
                    IndexRow(
                        icon = Icons.Outlined.VerifiedUser,
                        title = stringResource(R.string.partner_tile_checkins),
                        subtitle = partnerStatus?.let { stringResource(connectionStateTextRes(it)) },
                        tone = partnerStatus?.let(::connectionStateTone),
                        onClick = { view = VIEW_CHECKINS },
                    )
                }
                HubGroup(R.string.partner_group_setup) {
                    IndexRow(
                        icon = Icons.Outlined.GppBad,
                        title = stringResource(R.string.partner_tile_rules),
                        subtitle = if (current.ruleHistory.isEmpty()) {
                            stringResource(R.string.partner_tile_rules_none)
                        } else {
                            pluralStringResource(
                                R.plurals.partner_rules_count,
                                current.ruleHistory.size,
                                current.ruleHistory.size,
                            )
                        },
                        onClick = { view = VIEW_RULES },
                    )
                    RowDivider()
                    IndexRow(
                        icon = Icons.Outlined.Layers,
                        title = stringResource(R.string.partner_tile_plans),
                        subtitle = when (val hub = planHub) {
                            null -> stringResource(R.string.partner_plans_intro)
                            else -> when {
                                !hub.hasPlans -> stringResource(R.string.partner_tile_plans_none)
                                hub.todo == null -> stringResource(R.string.partner_plans_intro)
                                hub.todo == 0 -> stringResource(R.string.partner_tile_plans_all_done)
                                else -> stringResource(R.string.partner_tile_plans_todo, hub.todo)
                            }
                        },
                        onClick = { view = VIEW_PLANS },
                    )
                }
                HubGroup(R.string.partner_group_manage) {
                    // Requests earns a row only while there is something to read: with no
                    // request ever filed, an empty inbox page is a dead end (owner question
                    // 2026-11-01).
                    if (current.approvals.isNotEmpty()) {
                        IndexRow(
                            icon = Icons.Outlined.Inbox,
                            title = stringResource(R.string.partner_tile_requests),
                            subtitle = if (pending.isEmpty()) {
                                stringResource(R.string.partner_tile_requests_none)
                            } else {
                                stringResource(R.string.partner_tile_requests_waiting, pending.size)
                            },
                            onClick = { view = VIEW_REQUESTS },
                        )
                        RowDivider()
                    }
                    IndexRow(
                        icon = Icons.Outlined.Visibility,
                        title = stringResource(R.string.partner_tile_sharing),
                        subtitle = if (sharesNothing == true) {
                            stringResource(R.string.partner_tile_sharing_none)
                        } else {
                            stringResource(R.string.partner_tile_sharing_body)
                        },
                        onClick = { view = VIEW_SHARING },
                    )
                    RowDivider()
                    IndexRow(
                        icon = Icons.Outlined.Handshake,
                        title = stringResource(R.string.partner_tile_pairing),
                        subtitle = if (current.lastSyncedAtEpochMillis == null) {
                            stringResource(R.string.partner_tile_never_synced)
                        } else {
                            stringResource(
                                R.string.partner_tile_synced,
                                relativeTimeText(
                                    LocalPartnerNowMillis.current,
                                    current.lastSyncedAtEpochMillis,
                                ),
                            )
                        },
                        onClick = { view = VIEW_PAIRING },
                    )
                }
            }
        }
    }
    }

    // Hoisted out of the paired branch: the unpair dialog is offered from the Pairing page and
    // must survive a state change while it is open (the pair ends as soon as it is confirmed).
    if (confirmingUnpair) {
        val pairId = paired?.pairId
        AlertDialog(
            onDismissRequest = { confirmingUnpair = false },
            title = { Text(stringResource(R.string.partner_unpair_dialog_title)) },
            text = { Text(stringResource(R.string.partner_unpair_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingUnpair = false
                        if (pairId != null) runUnpair(pairId)
                    },
                ) {
                    Text(
                        stringResource(R.string.partner_unpair_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnpair = false }) {
                    Text(stringResource(R.string.partner_unpair_dialog_cancel))
                }
            },
        )
    }

    // #96: the roster switcher, offered from every subpage's context bar. Same hoisting reason
    // as the other dialogs — the selection must survive the reload that swaps the dashboard.
    if (switching) {
        val users = paired?.users.orEmpty()
        AlertDialog(
            onDismissRequest = { switching = false },
            title = { Text(stringResource(R.string.partner_users_title)) },
            text = {
                // Owner ask 2026-10-21: a long roster must scroll inside the dialog instead of
                // pushing its buttons off-screen.
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    users.forEach { user ->
                        PartnerUserRow(
                            user = user,
                            selected = user.pairId == paired?.pairId,
                            onSelect = {
                                switching = false
                                selectUser(user.pairId)
                            },
                            trailing = {
                                IconButton(
                                    onClick = {
                                        switching = false
                                        renaming = user
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Edit,
                                        contentDescription = stringResource(
                                            R.string.partner_users_rename_action,
                                            user.label ?: shortKeyId(user.keyId),
                                        ),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        )
                    }
                    // Owner directive 2026-11-01: "People you support" is where someone is
                    // added too — the sheet is the roster, so it carries the roster's growth.
                    if (ceremonyEntry != null) {
                        HorizontalDivider()
                        TextButton(
                            onClick = {
                                switching = false
                                view = VIEW_PAIRING
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Outlined.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                stringResource(R.string.partner_pair_add_user_title),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { switching = false }) {
                    Text(stringResource(R.string.partner_switch_close))
                }
            },
        )
    }

    // Req 15 composer. Hoisted for the same reason as the other dialogs: the send is a suspend
    // write, and the dashboard reload that follows it must not close the sheet mid-flight.
    if (composing) {
        // #96: the message names its recipient, and goes to the pair it names — the selected
        // partner at the moment Send is tapped. Hoisted so the SENT toast names the same one.
        val toName = paired?.let { it.partnerLabel ?: shortKeyId(it.partnerKeyId) }.orEmpty()
        val sentToast = stringResource(R.string.partner_toast_sent, toName)
        ComposeMessageDialog(
            sending = messageSending,
            outcome = messageOutcome,
            toName = toName,
            onSend = { text ->
                val target = paired?.pairId
                if (target == null) {
                    messageOutcome = MessageSendOutcome.FAILED
                } else if (!messageSending) {
                    messageSending = true
                    messageOutcome = null
                    scope.launch(Dispatchers.IO) {
                        val outcome = runCatching { onSendMessage(target, text) }
                            .onFailure { Log.w(TAG, "message send threw", it) }
                            .getOrDefault(MessageSendOutcome.FAILED)
                        messageSending = false
                        messageOutcome = outcome
                        // SENT closes the sheet — the mailbox write is the confirmation —
                        // plus the transient toast (owner ask 2026-10-01): with the sheet
                        // gone, the toast is the one visible receipt that the message left.
                        // QUEUED keeps the sheet open with the honest "saved" note (the retry
                        // cadence owns it; a red failure would be a lie), so it must not
                        // close here. A reload follows either way: a SENT kick advanced
                        // this pair's freshness state.
                        if (outcome == MessageSendOutcome.SENT) {
                            composing = false
                            // Device finding 2026-10-01: this coroutine runs on
                            // Dispatchers.IO, and API 30+ constructs the toast on the
                            // calling thread's Looper — which IO threads don't have, so a
                            // bare Toast.makeText here crashes the app. The composition's
                            // main scope shows it instead. The send itself already
                            // succeeded at this point; the crash was the receipt, not
                            // the delivery.
                            scope.launch {
                                Toast.makeText(context, sentToast, Toast.LENGTH_SHORT).show()
                            }
                            reload()
                        }
                    }
                }
            },
            onDismiss = {
                composing = false
                messageOutcome = null
            },
        )
    }

    // Same hoisting reason as the unpair dialog: renaming a row must survive a reload that
    // swaps the user list out from under the open dialog.
    renaming?.let { user ->
        var name by remember(user.pairId) { mutableStateOf(user.label.orEmpty()) }
        // #15: the save is a real write, so it gets the same triad the other actions have —
        // "Saving…" while in flight, the dialog stays open (and keeps the typed text) with an
        // inline failure if the write throws, and the row's updated label is the success
        // confirmation. The old fire-and-forget closed the dialog before the write happened,
        // so a failed rename was indistinguishable from a successful one.
        var saving by remember(user.pairId) { mutableStateOf(false) }
        var failed by remember(user.pairId) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) renaming = null },
            title = { Text(stringResource(R.string.partner_users_rename_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.partner_users_rename_label)) },
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.partner_users_rename_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (failed) {
                        Text(
                            stringResource(R.string.partner_users_rename_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!saving) {
                            saving = true
                            failed = false
                            scope.launch(Dispatchers.IO) {
                                val ok = runCatching { onRenameUser(user.pairId, name) }
                                    .onFailure { Log.w(TAG, "rename failed", it) }
                                    .isSuccess
                                saving = false
                                failed = !ok
                                if (ok) renaming = null
                                reload()
                            }
                        }
                    },
                    enabled = !saving,
                ) {
                    Text(
                        stringResource(
                            if (saving) R.string.partner_users_rename_saving
                            else R.string.partner_users_rename_save,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }, enabled = !saving) {
                    Text(stringResource(R.string.partner_users_rename_cancel))
                }
            },
        )
    }
}

/**
 * §7 Step 11 / §8.11 delta: what the partner has chosen to share — days free as the dominant
 * number, then milestones, relapses and when the last shared event arrived. Read entirely from
 * the local Room projection, so it paints offline and never waits on Firestore.
 *
 * Two honesty rules are structural here, not copy:
 *  - a null [PartnerActivityUi] renders "nothing shared yet", never zeros — the absence of
 *    events is not evidence the partner is at day 0;
 *  - the activity line and the sync line are separate statements, and a degraded chain says so,
 *    because "no events for three days" and "we haven't synced for three days" are different
 *    facts (§8.11: never use one as the other).
 */
/**
 * Owner directive 2026-11-01: the partner's device signals share one destination — the
 * monitoring report their phone publishes and whether it is checking in. Each card keeps its
 * own honesty rules; with neither reading available the page says nothing has arrived yet
 * rather than rendering as blank space.
 */
@Composable
private fun CheckinsView(state: PartnerDashboardUi.Paired, status: PartnerStatus?) {
    // Owner directive 2026-11-09: the page leads with what it answers. The old page dropped
    // two status cards on blank space, and nothing said how "protection" and "checking in"
    // relate — or that silence proves neither.
    Text(
        stringResource(R.string.partner_checkins_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.monitoring == null && status == null) {
        InfoCard(
            title = stringResource(R.string.partner_checkins_empty_title),
            body = stringResource(R.string.partner_checkins_empty_body),
        )
    }
    // Owner directive 2026-11-10: when the report says Limited, the newest recorded
    // protection gap explains why — shown only in that state, so a recovered grant never
    // leaves a stale gap line under a healthy card.
    val latestGap = state.ruleHistory
        .filterIsInstance<PartnerAlert.PermissionLost>()
        .maxByOrNull { it.atEpochMillis }
    state.monitoring?.let { monitoring ->
        PartnerMonitoringCard(
            monitoring = monitoring,
            gap = if (monitoring.state == MonitoringState.DEGRADED) latestGap else null,
        )
    }
    status?.let { PartnerConnectionCard(it) }
}

/**
 * §7 Step 7 / §8.13: the partner's device-monitoring state, decrypted from their last E2EE
 * snapshot. Three separate signals stay separate — this card is the monitoring half; sync
 * freshness and the chain verdict have their own cards, and none of them renders as a
 * combined "healthy" badge. A report older than the recorder's cadence can never keep reading
 * ACTIVE: it becomes UNKNOWN ([PartnerMonitoringUi.stale]).
 */
@Composable
private fun PartnerMonitoringCard(monitoring: PartnerMonitoringUi, gap: PartnerAlert.PermissionLost?) {
    PartnerSurface(spacing = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.partner_monitoring_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusPill(
                text = stringResource(
                    when (monitoring.state) {
                        MonitoringState.ACTIVE -> R.string.partner_monitoring_state_active
                        MonitoringState.DEGRADED -> R.string.partner_monitoring_state_limited
                        MonitoringState.UNAVAILABLE -> R.string.partner_monitoring_state_off
                        MonitoringState.UNKNOWN -> R.string.partner_monitoring_state_unknown
                    },
                ),
                // Owner directive 2026-11-10: a Limited report is a real risk state, not a
                // quiet footnote — it carries the same urgency as Off.
                tone = when (monitoring.state) {
                    MonitoringState.ACTIVE -> PillTone.POSITIVE
                    MonitoringState.DEGRADED -> PillTone.NEGATIVE
                    MonitoringState.UNAVAILABLE -> PillTone.NEGATIVE
                    else -> PillTone.QUIET
                },
            )
        }
        Text(
            stringResource(
                when (monitoring.state) {
                    MonitoringState.ACTIVE -> R.string.partner_monitoring_body_active
                    MonitoringState.DEGRADED -> R.string.partner_monitoring_body_limited
                    MonitoringState.UNAVAILABLE -> R.string.partner_monitoring_body_off
                    MonitoringState.UNKNOWN -> R.string.partner_monitoring_body_unknown
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (gap != null) {
            Text(
                stringResource(
                    R.string.partner_permission_lost_line,
                    stringResource(permissionEffectRes(gap.capability)),
                    gapRuleNames(gap.ruleNames) ?: stringResource(R.string.partner_alert_rule),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                relativeTimeText(LocalPartnerNowMillis.current, gap.atEpochMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            stringResource(
                if (monitoring.stale) {
                    R.string.partner_monitoring_report_stale
                } else {
                    R.string.partner_monitoring_report_fresh
                },
                relativeTimeText(
                    LocalPartnerNowMillis.current,
                    monitoring.lastReportAtEpochMillis,
                ),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (monitoring.updatesDelayed) {
            Text(
                stringResource(R.string.partner_monitoring_delayed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The hub row and the check-ins card read one mapping, so the two cannot disagree. */
@StringRes
private fun connectionStateTextRes(status: PartnerStatus): Int =
    if (!status.consent) {
        R.string.partner_connection_state_not_shared
    } else {
        when (status.connection) {
            PartnerConnection.CHECKING_IN -> R.string.partner_connection_state_checking_in
            PartnerConnection.LATE -> R.string.partner_connection_state_late
            PartnerConnection.NOT_CHECKING_IN -> R.string.partner_connection_state_not_checking_in
            PartnerConnection.NO_DATA -> R.string.partner_connection_state_no_data
        }
    }

/** Owner directive 2026-11-10: a missed check-in window is worth attention, not a quiet dot —
 *  the timing warning stays in the card body (doze, WorkManager deferral), but the state
 *  itself no longer reads as "all fine". */
private fun connectionStateTone(status: PartnerStatus): StatusTone = when {
    !status.consent -> StatusTone.INFO
    status.connection == PartnerConnection.CHECKING_IN -> StatusTone.GOOD
    status.connection == PartnerConnection.LATE -> StatusTone.ATTENTION
    status.connection == PartnerConnection.NOT_CHECKING_IN -> StatusTone.ATTENTION
    else -> StatusTone.INFO
}

/**
 * Plan Step 8 / §4/§8.13: the partner's server-derived connection signal. Coarse buckets only —
 * the screen never shows a check-in timestamp. `consent == false` is its own state ("not
 * shared"), not "not checking in": nothing is shared, so nothing can be concluded. LATE carries
 * attention weight (owner directive 2026-11-10); the body still says it can be normal.
 */
@Composable
private fun PartnerConnectionCard(status: PartnerStatus) {
    PartnerSurface(spacing = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.partner_connection_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusPill(
                text = stringResource(connectionStateTextRes(status)),
                tone = if (!status.consent) {
                    PillTone.QUIET
                } else {
                    when (status.connection) {
                        PartnerConnection.CHECKING_IN -> PillTone.POSITIVE
                        PartnerConnection.LATE -> PillTone.NEGATIVE
                        PartnerConnection.NOT_CHECKING_IN -> PillTone.NEGATIVE
                        PartnerConnection.NO_DATA -> PillTone.QUIET
                    }
                },
            )
        }
        Text(
            stringResource(
                if (!status.consent) {
                    R.string.partner_connection_body_not_shared
                } else {
                    when (status.connection) {
                        PartnerConnection.CHECKING_IN ->
                            R.string.partner_connection_body_checking_in
                        PartnerConnection.LATE -> R.string.partner_connection_body_late
                        PartnerConnection.NOT_CHECKING_IN ->
                            R.string.partner_connection_body_not_checking_in
                        PartnerConnection.NO_DATA ->
                            R.string.partner_connection_body_no_data
                    }
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A server answer can only carry a real bucket alongside a real connection;
        // NO_DATA in either place means there is nothing to time, so no bucket line is
        // claimed.
        val bucketRes = when (status.bucket) {
            PartnerConnectionBucket.WITHIN_1H -> R.string.partner_connection_bucket_within_hour
            PartnerConnectionBucket.HOURS_1_TO_6 -> R.string.partner_connection_bucket_hours
            PartnerConnectionBucket.OVER_6H -> R.string.partner_connection_bucket_over_six
            PartnerConnectionBucket.NO_DATA -> null
        }
        if (status.consent && status.connection != PartnerConnection.NO_DATA &&
            bucketRes != null
        ) {
            Text(
                stringResource(bucketRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // §18 DoD: whenever the partner is looking at anything but a fresh check-in,
        // say what silence is not. Heartbeat absence is never evidence of removal,
        // tamper, or violation.
        if (status.consent && status.connection != PartnerConnection.CHECKING_IN) {
            Text(
                stringResource(R.string.partner_connection_silence_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PartnerSummaryCard(activity: PartnerActivityUi, chainStatus: PartnerChainStatus) {
    PartnerSurface(spacing = 12.dp) {
        // ADR #285: a snapshot-less message writes only the pin. No shared state means no
        // numbers — zeros would be a claim about the partner the absent events do not support.
        val hasSharedState = activity.lastEventOccurredAtEpochMillis != null
        // Owner directive 2026-11-09: every number below belongs to the streak the selector
        // names — several shared streaks get a switcher, one gets its name, and pre-v3
        // senders fall back to the pair-level aggregate exactly as before.
        val streaks = activity.streaks
        var selectedHabitId by rememberSaveable { mutableStateOf<String?>(null) }
        val selected = streaks.firstOrNull { it.habitId == selectedHabitId }
            ?: streaks.maxByOrNull { it.lastEventOccurredAtEpochMillis }
        if (hasSharedState) {
            when {
                streaks.size > 1 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    streaks.forEach { streak ->
                        FilterChip(
                            selected = streak.habitId == selected?.habitId,
                            onClick = { selectedHabitId = streak.habitId },
                            label = {
                                Text(
                                    streak.label.ifBlank {
                                        stringResource(R.string.partner_summary_streak_unnamed)
                                    },
                                )
                            },
                        )
                    }
                }
                selected != null -> Text(
                    stringResource(
                        R.string.partner_summary_streak_viewing,
                        selected.label.ifBlank {
                            stringResource(R.string.partner_summary_streak_unnamed)
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Owner directive 2026-11-09: no card title — the page header already says "How
            // they're doing", and the repeated line pushed the hero number below the fold.
            // Days free is the one number the tab exists to answer, so it takes the reference's
            // hero slot — and its freshness note rides under the label rather than in a third
            // line, which is what made the old card read as a wall of equal-weight text.
            val daysFree = selected?.daysFree ?: activity.daysFree
            HeroStatRow(
                label = stringResource(R.string.partner_summary_days),
                caption = (selected?.lastEventOccurredAtEpochMillis
                    ?: activity.lastEventOccurredAtEpochMillis)?.let { at ->
                    stringResource(
                        R.string.partner_summary_last_event,
                        relativeTimeText(LocalPartnerNowMillis.current, at),
                    )
                },
                value = daysFree,
                unit = pluralStringResource(
                    R.plurals.partner_summary_days_unit,
                    daysFree,
                    daysFree,
                ),
            )
            // Equal thirds: intrinsic widths squeezed the third label below the width of its
            // longest word, which broke "Coaching items" mid-word on the 720px device.
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                StatColumn(
                    stringResource(R.string.partner_summary_relapses),
                    (selected?.relapseCount ?: activity.relapseCount).toString(),
                    modifier = Modifier.weight(1f),
                )
                StatColumn(
                    stringResource(R.string.partner_summary_milestones_label),
                    (selected?.milestonesReached ?: activity.milestonesReached)
                        .joinToString(", ").ifEmpty {
                            stringResource(R.string.partner_summary_milestones_none)
                        },
                    modifier = Modifier.weight(1f),
                )
                StatColumn(
                    stringResource(R.string.partner_summary_coaching_label),
                    activity.coachingProgress.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        activity.pinnedMessageText?.let { message ->
            if (hasSharedState) HorizontalDivider()
            Text(
                stringResource(R.string.partner_summary_pinned_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
        // §8.11 degraded: a gap means these numbers are not the whole story, and saying so is
        // cheaper than letting the user believe a stale card.
        if (hasSharedState && chainStatus != PartnerChainStatus.HEALTHY) {
            Text(
                stringResource(R.string.partner_summary_partial),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Req 15 composer: three suggestions plus free text, one 280-char bound (the same the wire and
 * the receiving projection enforce). The message is journaled locally, then the send call
 * kicks a sync pass — what the dialog reports back is the real outcome ([MessageSendOutcome]):
 * SENT closes, QUEUED keeps the sheet open with the honest "saved" note, FAILED shows inline.
 * Since ADR #285 there is no sharing prerequisite: a message carries only its text, so it is
 * offered to every pair and ships snapshot-less when the sender shares nothing.
 */
@Composable
private fun ComposeMessageDialog(
    sending: Boolean,
    outcome: MessageSendOutcome?,
    /** #96: who this message is addressed to — the selected partner, named verbatim. */
    toName: String,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Non-null only after the write attempt: FAILED renders inline red, QUEUED renders the
    // neutral "saved" note and disables Send (re-tapping would queue a duplicate).
    val queued = outcome == MessageSendOutcome.QUEUED
    var text by remember { mutableStateOf("") }
    var tooLong by remember { mutableStateOf(false) }
    val suggestions = listOf(
        stringResource(R.string.partner_compose_suggestion_1),
        stringResource(R.string.partner_compose_suggestion_2),
        stringResource(R.string.partner_compose_suggestion_3),
    )
    val max = EventPayload.MAX_ACTIVITY_TEXT_LENGTH
    AlertDialog(
        // Never trap the sheet (finding 2026-11-02): the message is journaled before sending,
        // so closing while it is in flight cannot lose it — the retry cadence owns it then.
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.partner_compose_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.partner_compose_to, toName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.partner_compose_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                suggestions.forEach { suggestion ->
                    OutlinedButton(
                        onClick = { text = suggestion; tooLong = false },
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(suggestion, style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        tooLong = it.trim().length > max
                    },
                    label = { Text(stringResource(R.string.partner_compose_hint)) },
                    isError = tooLong,
                    minLines = 2,
                    maxLines = 4,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.partner_compose_characters, text.trim().length, max),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tooLong) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (tooLong) {
                    Text(
                        stringResource(R.string.partner_compose_too_long, max),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (outcome == MessageSendOutcome.FAILED) {
                    Text(
                        stringResource(R.string.partner_compose_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (queued) {
                    Text(
                        stringResource(R.string.partner_compose_queued),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(text) },
                enabled = !sending && !queued && text.isNotBlank() && !tooLong,
            ) {
                Text(
                    stringResource(
                        when {
                            sending -> R.string.partner_compose_sending
                            queued -> R.string.partner_compose_saved
                            else -> R.string.partner_compose_send
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        // While sending this closes the sheet, it does not recall the
                        // journaled message — so it is named Close, not Cancel.
                        if (queued || sending) R.string.partner_compose_close else R.string.partner_compose_cancel,
                    ),
                )
            }
        },
    )
}

/**
 * Owner ask 2026-10-01: the writer's own receipt — the latest message sent to this partner,
 * read back from the journal (label + text, the hub's "Their message to you" grammar).
 */
@Composable
private fun SentMessageCard(text: String) {
    PartnerSurface(spacing = 4.dp) {
        Text(
            stringResource(R.string.partner_activity_sent_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The Activity subpage's leading card: the one action this page owns. The reference's action
 * tile (a mark, the ask, then a full-width primary) — the button spans because on this page
 * there is nothing else to press.
 */
@Composable
private fun ComposeMessageCard(onCompose: () -> Unit) {
    PartnerSurface(spacing = 8.dp) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.partner_compose_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            stringResource(R.string.partner_compose_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onCompose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.partner_compose_action))
        }
    }
}

/**
 * Step 13 responder card (§6 line 240): every pending request renders with the hash-chain
 * verification badge ABOVE the buttons — responding is only allowed while the badge reads
 * verified. UNVERIFIED disables both buttons and says why; the request stays visible (honesty:
 * the ask happened, the answer is deliberately withheld).
 */
@Composable
private fun ApprovalRequestCard(
    requests: List<ApprovalUi>,
    chainStatus: PartnerChainStatus,
    respondingTo: String?,
    respondFailed: Boolean,
    onRespond: (requestEventId: String, approve: Boolean) -> Unit,
) {
    val canRespond = canRespondToApprovals(chainStatus)
    PartnerSurface(spacing = 12.dp) {
        Text(
            stringResource(R.string.partner_approvals_title, requests.size),
            style = MaterialTheme.typography.titleMedium,
        )
        requests.forEachIndexed { index, request ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(request.message, style = MaterialTheme.typography.bodyMedium)
                val nowMillis = LocalPartnerNowMillis.current
                Text(
                    if (request.expiresAtEpochMillis <= nowMillis) {
                        stringResource(R.string.partner_my_requests_expired)
                    } else {
                        stringResource(
                            R.string.partner_approvals_expires,
                            remainingTimeText(nowMillis, request.expiresAtEpochMillis),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The verdict keeps its whole sentence — truncating a trust explanation to fit
                // a pill would trade the reason for the shape. The glyph carries the state
                // instead, so it reads before the sentence does.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        if (canRespond) Icons.Outlined.VerifiedUser else Icons.Outlined.GppBad,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(
                            when {
                                !canRespond -> R.string.partner_approvals_badge_blocked
                                chainStatus == PartnerChainStatus.CATCHING_UP ->
                                    R.string.partner_approvals_badge_ok_catching_up
                                else -> R.string.partner_approvals_badge_ok
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { onRespond(request.requestEventId, true) },
                        enabled = canRespond && respondingTo == null,
                    ) { Text(stringResource(R.string.partner_approvals_approve)) }
                    OutlinedButton(
                        onClick = { onRespond(request.requestEventId, false) },
                        enabled = canRespond && respondingTo == null,
                    ) { Text(stringResource(R.string.partner_approvals_reject)) }
                }
                if (respondingTo == request.requestEventId) {
                    Text(
                        stringResource(R.string.partner_approvals_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (index < requests.lastIndex) HorizontalDivider()
        }
        if (respondFailed) {
            Text(
                stringResource(R.string.partner_approvals_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Requester-side status list. Creation UI is owned by the Strict Mode disable gate (flagged
 *  off until a real partner device exists), so this renders only when rows exist. */
@Composable
private fun MyRequestsCard(requests: List<ApprovalUi>) {
    PartnerSurface {
        Text(
            stringResource(R.string.partner_my_requests_title, requests.size),
            style = MaterialTheme.typography.titleMedium,
        )
        // The outcome is what a requester scans this list for, so it moves out of the caption
        // sentence and into the row's trailing edge — the reference's row geometry, and the
        // reason the five statuses no longer arrive as one grey line of prose.
        requests.forEachIndexed { index, request ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(request.message, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        relativeTimeText(LocalPartnerNowMillis.current, request.createdAtEpochMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(requestStatusText(request.status), tone = requestStatusTone(request.status))
            }
            if (index < requests.lastIndex) HorizontalDivider()
        }
    }
}

/**
 * §8 Step 13 / AD-13b: the terminal card, shown once the pair is gone. The two claims are
 * separate and never blended — the local line is already true when this card can render at
 * all (the keystore key is destroyed before the enrollment disappears, so no early render is
 * possible); the remote line appears only in the state the evidence supports, and the
 * timed-out wait says plainly that erasure could not be confirmed (§17 line 655).
 */
@Composable
private fun EndedCard(
    state: PartnerDashboardUi.Ended,
    checking: Boolean,
    checkFailed: Boolean,
    dismissing: Boolean,
    onRePair: ((CeremonyMode) -> Unit)?,
    onCheckNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isRequester = state.role != UnpairState.ROLE_RESPONDER
    PartnerSurface {
        Text(
            stringResource(R.string.partner_unpair_title),
            style = MaterialTheme.typography.titleMedium,
        )
        if (isRequester) {
            Text(
                stringResource(R.string.partner_unpair_local_done),
                style = MaterialTheme.typography.bodyMedium,
            )
            val (remoteRes, confirmed) = when (state.phase) {
                // Step 10: the server has not acknowledged the exit yet — say that plainly
                // instead of implying the relationship state is settled.
                UnpairPhase.AWAITING_REVOCATION -> R.string.partner_unpair_revocation_pending to false
                UnpairPhase.AWAITING_CONFIRMATION -> R.string.partner_unpair_waiting to false
                UnpairPhase.TERMINAL -> R.string.partner_unpair_confirmed to true
                UnpairPhase.LOCAL_ONLY_TERMINATED -> R.string.partner_unpair_unconfirmed to false
            }
            Text(
                stringResource(remoteRes),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    confirmed -> MaterialTheme.colorScheme.primary
                    state.phase == UnpairPhase.LOCAL_ONLY_TERMINATED ->
                        MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (state.phase == UnpairPhase.AWAITING_CONFIRMATION ||
                state.phase == UnpairPhase.AWAITING_REVOCATION
            ) {
                // #14: the confirmation (and the server acknowledgement) arrives on its own
                // (push + the automatic pass); this is the recovery nudge for a wait that looks
                // stuck, not a primary path — so it stays quiet.
                TextButton(onClick = onCheckNow, enabled = !checking) {
                    Text(
                        stringResource(
                            if (checking) R.string.partner_check_now_running
                            else R.string.partner_check_now,
                        ),
                    )
                }
                if (checkFailed) {
                    Text(
                        stringResource(R.string.partner_check_now_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        } else {
            Text(
                stringResource(R.string.partner_unpair_responder_done),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!state.canDismiss) {
                Text(
                    stringResource(R.string.partner_unpair_responder_acking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.canDismiss) {
            TextButton(onClick = onDismiss, enabled = !dismissing) {
                Text(stringResource(R.string.partner_unpair_dismiss))
            }
        }
        // The ended row's wait (server acknowledgement, or a local-only request's 7-day
        // timeout) is never a reason this device cannot pair with someone else. Without this
        // the card was a dead end for up to 7 days (rig, 2026-10-23).
        if (onRePair != null) {
            PairingEntryActions(
                onInvite = { onRePair(CeremonyMode.INVITE) },
                onJoin = { onRePair(CeremonyMode.JOIN) },
            )
        }
    }
}

/** The unpair entry: last, deliberately low-emphasis, and inert until the confirmation
 *  dialog (then the biometric prompt) commits — nothing is journaled before that. */
@Composable
private fun UnpairCard(
    working: Boolean,
    failed: Boolean,
    onStart: () -> Unit,
) {
    PartnerSurface {
        Text(
            stringResource(R.string.partner_unpair_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.partner_unpair_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onStart,
            enabled = !working,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(
                stringResource(
                    if (working) R.string.partner_unpair_working
                    else R.string.partner_unpair_action,
                ),
            )
        }
        if (failed) {
            Text(
                stringResource(R.string.partner_unpair_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun requestStatusText(status: String): String {
    val res = when (status) {
        ApprovalRequest.STATUS_REQUESTED -> R.string.partner_my_requests_requested
        ApprovalRequest.STATUS_APPROVED -> R.string.partner_my_requests_approved
        ApprovalRequest.STATUS_REJECTED -> R.string.partner_my_requests_rejected
        ApprovalRequest.STATUS_EXPIRED -> R.string.partner_my_requests_expired
        ApprovalRequest.STATUS_CONSUMED -> R.string.partner_my_requests_consumed
        // Unknown statuses cannot be written by this build; render the raw value, never a
        // wrong label.
        else -> null
    }
    return if (res != null) stringResource(res) else status
}

/** "3d" / "20h" / "45m" — short remaining-time bucket for the expiry line. */
private fun remainingTimeText(nowMillis: Long, untilMillis: Long): String {
    val delta = untilMillis - nowMillis
    val hours = delta / (60 * 60 * 1000)
    return when {
        hours < 1 -> "${delta / 60_000}m"
        hours < 48 -> "${hours}h"
        else -> "${hours / 24}d"
    }
}

/**
 * The hub's first row: who this device is paired with, and the chain verdict under the name.
 * The leading mark is deliberately not a portrait — the pairing model has no portrait and,
 * without a §19.5 label, no name either; drawing one would be inventing an identity the app
 * does not have. The label is the partner's own words for themselves, so it is shown verbatim,
 * with the key fragment only as the never-labelled fallback.
 *
 * Owner directive 2026-11-01: the card doubles as the entrance to "People you support", where
 * the viewer switches, renames, or adds someone, without spending a second card on the hub.
 *
 * Owner directive 2026-11-09: the name owns the card at headline weight with no "Viewing"
 * prefix — prefix + single line truncated John Maingi to "John Ma…" on the 720px device, and
 * the trailing "Switch" word read as a setting rather than the person being the way in.
 */
@Composable
private fun PartnerIdentityCard(state: PartnerDashboardUi.Paired, onOpenSwitcher: () -> Unit) {
    Surface(
        onClick = onOpenSwitcher,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    state.partnerLabel ?: shortKeyId(state.partnerKeyId),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.partner_paired_since, pairedOnText(state.pairedAtEpochMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The Needs-you group carries every unhealthy state as its own card; the line
                // here affirms the healthy case so a quiet hub still says "checked and fine".
                if (state.chainStatus == PartnerChainStatus.HEALTHY) {
                    ChainVerdictLine(state.chainStatus)
                }
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Owner ask 2026-10-04: pairing is not sharing. Nothing is shared until a switch is turned on
 * (v16/ADR #135), so a fresh pair can sit for days with the partner seeing nothing and no
 * surface saying why. This card is the one place that says it, and its action lands on the
 * sharing pane — not the Settings index — because the next step is one decision, not browsing.
 */
@Composable
private fun NothingSharedCard(partnerName: String, onOpenSharing: () -> Unit) {
    PartnerSurface {
        Text(
            stringResource(R.string.partner_nothing_shared_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.partner_nothing_shared_body, partnerName),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onOpenSharing) {
            Text(stringResource(R.string.partner_nothing_shared_action))
        }
    }
}

/**
 * Sync detail. The same card serves two jobs, so it takes its emphasis from the caller: the hub
 * shows it only when something needs attention, and there a filled button would make a routine
 * resync the loudest control on a page whose real subject is the partner.
 *
 * #14: "Check now" is a recovery affordance, not a primary path — the pass runs automatically
 * on every app open (plus the periodic worker and push), so [showCheck] hides the button while
 * the state is healthy; callers pass true whenever something needs attention.
 *
 * [showIdentity] (#97 cognitive-load pass): the Pairing page used to stack a separate
 * identity card above this one — two cards, one subject ("who is this and is it healthy?").
 * With the header folded in, the page answers that in one card and the hero.
 */
@Composable
private fun SyncCard(
    state: PartnerDashboardUi.Paired,
    checking: Boolean,
    checkFailed: Boolean,
    checkOk: Boolean,
    onCheckNow: () -> Unit,
    signingDeferred: Boolean = false,
    tone: CardTone = CardTone.CALM,
    prominentAction: Boolean = true,
    showIdentity: Boolean = false,
    showCheck: Boolean = true,
) {
    PartnerSurface(tone = tone) {
        if (showIdentity) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        R.string.partner_paired_with,
                        state.partnerLabel ?: shortKeyId(state.partnerKeyId),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.partner_paired_since,
                        pairedOnText(state.pairedAtEpochMillis),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChainVerdictLine(state.chainStatus)
            }
            // Finding #6: the key-id fragment was a bare mystery string to a first-time user.
            // With no §19.5 label the card explains the code instead of inventing a name.
            if (state.partnerLabel == null) {
                Text(
                    stringResource(R.string.partner_paired_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
        Text(
            if (state.lastSyncedAtEpochMillis == null) {
                stringResource(R.string.partner_never_synced)
            } else {
                stringResource(
                    R.string.partner_last_synced,
                    relativeTimeText(LocalPartnerNowMillis.current, state.lastSyncedAtEpochMillis),
                )
            },
            style = MaterialTheme.typography.titleSmall,
        )
        // An unreachable pairing is not a delayed sync: "check your connection" would send the
        // user after the wrong problem, and the card above already names the real one.
        if (state.freshness == SyncFreshness.STALE && !state.pairingUnreachable) {
            Text(
                stringResource(R.string.partner_sync_delayed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Owner-approved A (2026-11-09): a cold §8.1 signing window defers queued partner
        // events by design (NFR4). The page says the updates exist and what unlocks them, so
        // "sent but the partner heard nothing" is never a silent surprise.
        if (signingDeferred) {
            Text(
                stringResource(R.string.partner_sync_waiting_unlock),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // §8 Step 18: the server refused a recent send — deliberately NOT the
        // "couldn't reach the server" copy: the network is fine, the updates just
        // ride the next sync instead of a push.
        if (state.rateLimitedRecently) {
            Text(
                stringResource(R.string.partner_sync_rate_limited),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.partner_sync_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showCheck) {
            if (prominentAction) {
                Button(onClick = onCheckNow, enabled = !checking) {
                    Text(
                        stringResource(
                            if (checking) R.string.partner_check_now_running else R.string.partner_check_now,
                        ),
                    )
                }
            } else {
                TextButton(onClick = onCheckNow, enabled = !checking) {
                    Text(
                        stringResource(
                            if (checking) R.string.partner_check_now_running else R.string.partner_check_now,
                        ),
                    )
                }
            }
        }
        if (checkFailed) {
            Text(
                stringResource(R.string.partner_check_now_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (checkOk) {
            Text(
                stringResource(R.string.partner_check_now_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ACTIVITY_CHART_HEIGHT = 72.dp
private val ACTIVITY_BAR_SHAPE =
    RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp)

/**
 * Owner directive 2026-11-01: the activity page answers the week as a picture, not only as
 * five rows. One bar per local day on a shared scale, today at the right; values stay printed
 * above each bar and each column merges one spoken sentence, so neither height nor colour is
 * the only encoding (same grammar as the Insights strips).
 */
@Composable
private fun ActivityChartCard(summary: AlertSummary) {
    val counts = summary.dailyCounts
    // Nothing received in the week means the picture would be seven empty bars — the exact
    // all-zero chart the owner reported as "broken". The list below names the empty state.
    if (counts.isEmpty() || summary.recent.isEmpty()) return
    val today = LocalDate.now(ZoneId.systemDefault())
    val lastIndex = counts.lastIndex
    val max = (counts.maxOrNull() ?: 0).toLong()
    PartnerSurface(spacing = 12.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.BarChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.partner_activity_chart_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            stringResource(R.string.partner_activity_chart_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEachIndexed { index, count ->
                val isToday = index == lastIndex
                val label = if (isToday) {
                    stringResource(R.string.partner_activity_today)
                } else {
                    shortDayLabel(today.minusDays((lastIndex - index).toLong()))
                }
                val description =
                    pluralStringResource(R.plurals.partner_activity_chart_bar, count, label, count)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.height(ACTIVITY_CHART_HEIGHT).fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (count > 0) {
                            Box(
                                Modifier
                                    .width(18.dp)
                                    .fillMaxHeight(shareOf(count.toLong(), max))
                                    .clip(ACTIVITY_BAR_SHAPE)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        } else {
                            Box(
                                Modifier
                                    .fillMaxWidth(0.6f)
                                    .height(2.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isToday) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(summary: AlertSummary, activity: PartnerActivityUi?) {
    PartnerSurface {
        Text(
            stringResource(R.string.partner_activity_title),
            style = MaterialTheme.typography.titleMedium,
        )
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            StatColumn(stringResource(R.string.partner_activity_today_stat), summary.todayCount.toString())
            StatColumn(stringResource(R.string.partner_activity_week), summary.weekCount.toString())
        }
        Text(
            stringResource(R.string.partner_activity_counted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (summary.recent.isEmpty()) {
            Text(
                // Two causes, two statements: nothing was ever shared, or sharing is on and
                // the week was quiet. One shared empty sentence conflated them. A snapshot-less
                // message (ADR #285) creates the row without shared state, so the state field
                // — not row presence — is the discriminator.
                stringResource(
                    if (activity?.lastEventOccurredAtEpochMillis == null) {
                        R.string.partner_activity_none_shared
                    } else {
                        R.string.partner_activity_empty
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            HorizontalDivider()
            summary.recent.forEach { alert -> AlertRow(alert) }
        }
    }
}

/**
 * Every rule/batch lifecycle row this device received (owner mandate 2026-10-31): the feed
 * previews the latest five, this page is the organized list behind it. Each row names the
 * rule or batch, the verb, and the batch context, using the wire-v2 identity.
 */
@Composable
private fun RulesView(state: PartnerDashboardUi.Paired) {
    if (state.ruleHistory.isEmpty()) {
        InfoCard(
            title = stringResource(R.string.partner_rules_empty_title),
            body = stringResource(R.string.partner_rules_empty_body),
        )
        return
    }
    // Owner directive 2026-11-09: one undivided card mixed today's changes with month-old
    // ones. Day sections give the list the same "when did this happen" spine the activity
    // feed has, without inventing a second row shape.
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val byDay = state.ruleHistory
        .sortedByDescending { it.atEpochMillis }
        .groupBy { Instant.ofEpochMilli(it.atEpochMillis).atZone(zone).toLocalDate() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                pluralStringResource(
                    R.plurals.partner_rules_count,
                    state.ruleHistory.size,
                    state.ruleHistory.size,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.partner_rules_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        byDay.forEach { (day, alerts) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    ruleDayLabel(day, today),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PartnerSurface {
                    alerts.forEachIndexed { index, alert ->
                        if (index > 0) RowDivider()
                        AlertRow(alert)
                    }
                }
            }
        }
    }
}

@Composable
private fun ruleDayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> stringResource(R.string.partner_day_today)
    today.minusDays(1) -> stringResource(R.string.partner_day_yesterday)
    else -> day.format(DateTimeFormatter.ofPattern("MMM d"))
}

/**
 * The reference's dominant card geometry (ref `204def2b`): the question on the left with its
 * freshness note under it, the answer on the right at display weight, the unit riding the
 * number's baseline — "30 days" is one quantity, which a stacked label cannot say.
 */
@Composable
private fun HeroStatRow(label: String, caption: String?, value: Int, unit: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value.toString(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            unit?.let {
                Text(
                    " $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}

/**
 * A peer count beside the hero (ref `11.54.23`'s stat tiles at card scale). The value leads and
 * the label names it — the same order the hero uses, so the card reads top-down as one scale.
 * A non-numeric value is allowed on purpose: "Milestones met: 7, 30" loses the days if it is
 * flattened to a count, and those days are the whole point of the row.
 */
@Composable
private fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@StringRes
private fun ruleChangeLabel(change: RuleChange): Int = when (change) {
    RuleChange.CREATED -> R.string.partner_rule_created
    RuleChange.ASSIGNED -> R.string.partner_rule_assigned
    RuleChange.UNASSIGNED -> R.string.partner_rule_unassigned
    RuleChange.UPDATED -> R.string.partner_rule_updated
    RuleChange.DISABLED -> R.string.partner_rule_disabled
    RuleChange.DELETED -> R.string.partner_rule_deleted
    RuleChange.EXPIRED -> R.string.partner_rule_expired
}

/** Up to three rule names plus an "and N more" tail — the same cap the notification body uses. */
@Composable
private fun gapRuleNames(ruleNames: List<String>): String? {
    if (ruleNames.isEmpty()) return null
    val shown = ruleNames.take(3).joinToString(", ")
    val extra = ruleNames.size - 3
    return if (extra > 0) stringResource(R.string.partner_permission_lost_more, shown, extra) else shown
}

@StringRes
private fun permissionEffectRes(capability: MonitoringCapability): Int = when (capability) {
    MonitoringCapability.ACCESSIBILITY -> R.string.partner_permission_lost_accessibility
    MonitoringCapability.USAGE_ACCESS -> R.string.partner_permission_lost_usage
    MonitoringCapability.OVERLAY -> R.string.partner_permission_lost_overlay
    MonitoringCapability.WEBSITE_FILTER -> R.string.partner_permission_lost_vpn
}

/**
 * One shared event. The leading mark is the real subject of the row — the app's own icon when
 * this device has it installed, a generic glyph when it doesn't (the partner's phone may block
 * apps this one has never seen). Without it every row was the same shape of text and the list
 * was unskimmable.
 */
@Composable
private fun AlertRow(alert: PartnerAlert) {
    // Non-shaming by design (W5): a recovery row states what was practised, never that the
    // partner "failed" — the outcome labels are the neutral ones the history screen uses.
    // Wire v2 (ADR #134): a relapse/milestone row leads with the streak's own name and
    // states the day-count; "Their streak" covers a v1 body with no name rather than a gap.
    val label = when (alert) {
        is PartnerAlert.AppActivity -> {
            // Local name when this phone has the app; otherwise the name the sender sent —
            // never a bare package id when the partner labelled it (owner report 2026-11-02).
            val local = rememberAppLabel(alert.target)
            if (local != alert.target) local else alert.appLabel ?: local
        }
        is PartnerAlert.Recovery -> stringResource(techniqueTitle(alert.technique))
        is PartnerAlert.Relapse -> alert.habitLabel
            ?: stringResource(R.string.partner_alert_streak_unnamed)
        is PartnerAlert.Milestone -> alert.habitLabel
            ?: stringResource(R.string.partner_alert_streak_unnamed)
        is PartnerAlert.RuleChanged -> alert.identity?.label
            ?: stringResource(R.string.partner_alert_rule)
        is PartnerAlert.PermissionLost -> gapRuleNames(alert.ruleNames)
            ?: stringResource(R.string.partner_alert_rule)
    }
    val kind = when (alert) {
        is PartnerAlert.AppActivity ->
            stringResource(if (alert.isOverride) R.string.partner_alert_override else R.string.partner_alert_blocked) +
                (alert.batchLabel?.let { " · " + stringResource(R.string.partner_rule_in_batch, it) }.orEmpty())
        is PartnerAlert.Recovery -> stringResource(outcomeLabel(alert.outcome.uppercase()))
        is PartnerAlert.Relapse -> stringResource(R.string.partner_alert_relapse) +
            (alert.endedDays?.let { " · " + stringResource(R.string.partner_relapse_ended_days, it) }.orEmpty())
        is PartnerAlert.Milestone -> alert.milestoneDay
            ?.let { pluralStringResource(R.plurals.partner_milestone_reached_days, it, it) }
            ?: stringResource(R.string.partner_alert_milestone_plain)
        is PartnerAlert.RuleChanged -> {
            val verb = stringResource(ruleChangeLabel(alert.change))
            val id = alert.identity
            val count = (id?.itemCount ?: 0L).toInt()
            when {
                id?.targetType == RuleTargetType.BATCH ->
                    verb + " · " + pluralStringResource(R.plurals.partner_rule_item_count, count, count)
                id?.batchLabel != null -> verb + " · " + stringResource(R.string.partner_rule_in_batch, id.batchLabel)
                else -> verb
            }
        }
        is PartnerAlert.PermissionLost -> stringResource(permissionEffectRes(alert.capability))
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (alert) {
            is PartnerAlert.AppActivity -> AppMark(
                packageName = alert.target,
                fallback = Icons.Default.Apps,
                size = 28.dp,
            )
            is PartnerAlert.Recovery -> AppMark(
                packageName = null,
                fallback = Icons.Outlined.SelfImprovement,
                size = 28.dp,
            )
            is PartnerAlert.Relapse -> AppMark(
                packageName = null,
                fallback = Icons.Outlined.RestartAlt,
                size = 28.dp,
            )
            is PartnerAlert.Milestone -> AppMark(
                packageName = null,
                fallback = Icons.Outlined.EmojiEvents,
                size = 28.dp,
            )
            is PartnerAlert.RuleChanged -> AppMark(
                packageName = null,
                fallback = if (alert.identity?.targetType == RuleTargetType.BATCH) {
                    Icons.Outlined.Layers
                } else {
                    Icons.Outlined.Tune
                },
                size = 28.dp,
            )
            is PartnerAlert.PermissionLost -> AppMark(
                packageName = null,
                fallback = Icons.Outlined.GppBad,
                size = 28.dp,
            )
        }
        // Label gets the full width: sharing the line with the state·time tail truncated
        // "Restriction rule" to "Res…" on the 720px test device (owner report 2026-10-31).
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                kind + " · " + relativeTimeText(LocalPartnerNowMillis.current, alert.atEpochMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Every approval ask in one place: the ones awaiting your decision lead (the same card the hub
 * shows while one is time-boxed), then the status of the asks you sent. Both read the same
 * `approvals` list the hub reads, so the two surfaces cannot disagree.
 */
@Composable
private fun RequestsView(
    state: PartnerDashboardUi.Paired,
    respondingTo: String?,
    respondFailed: Boolean,
    onRespond: (requestEventId: String, approve: Boolean) -> Unit,
) {
    val pending = state.approvals.filter {
        it.role == ApprovalRequest.ROLE_RESPONDER && it.status == ApprovalRequest.STATUS_REQUESTED
    }
    val mine = state.approvals.filter { it.role == ApprovalRequest.ROLE_REQUESTER }
    if (pending.isEmpty() && mine.isEmpty()) {
        InfoCard(
            title = stringResource(R.string.partner_requests_empty_title),
            body = stringResource(R.string.partner_requests_empty_body),
        )
        return
    }
    if (pending.isNotEmpty()) {
        ApprovalRequestCard(
            requests = pending,
            chainStatus = state.chainStatus,
            respondingTo = respondingTo,
            respondFailed = respondFailed,
            onRespond = onRespond,
        )
    }
    if (mine.isNotEmpty()) MyRequestsCard(mine)
}

/**
 * Who you are paired with, how to bring another supported user alongside, the sync detail and
 * the way out. Unpair stays last and low-emphasis — never adjacent to the routine cards —
 * which is also why it is not on the hub.
 */
@Composable
private fun PairingView(
    state: PartnerDashboardUi.Paired,
    checking: Boolean,
    checkFailed: Boolean,
    checkOk: Boolean,
    signingDeferred: Boolean,
    onCheckNow: () -> Unit,
    unpairing: Boolean,
    unpairFailed: Boolean,
    onStartUnpair: () -> Unit,
    onAddUser: ((CeremonyMode) -> Unit)?,
) {
    // OPD #30: verdict before mechanism — when the pairing cannot be reached, the sync card's
    // "last synced" is a frozen number and Check now cannot move it, so the cause and the way
    // out are stated first.
    if (state.pairingUnreachable) {
        PairingGoneCard(
            titleRes = R.string.partner_unreachable_title,
            bodyRes = R.string.partner_unreachable_body,
            onRePair = onAddUser,
        )
    }
    SyncCard(
        state = state,
        checking = checking,
        checkFailed = checkFailed,
        checkOk = checkOk,
        signingDeferred = signingDeferred,
        onCheckNow = onCheckNow,
        showIdentity = true,
        // #14: the manual check appears only when the state needs it — routine freshness is
        // the automatic pass's job (foreground, worker, push).
        showCheck = checkFailed || signingDeferred || syncNeedsAttention(state),
    )
    if (onAddUser != null) AddUserCard(onAddUser)
    UnpairCard(working = unpairing, failed = unpairFailed, onStart = onStartUnpair)
}

/**
 * §19.5: the roster grows by pairing again — this device may support several people at once.
 * Both entry modes are offered because either side may be the one holding the phone that
 * starts the ceremony.
 */
@Composable
private fun AddUserCard(onAddUser: (CeremonyMode) -> Unit) {
    // Owner ask 2026-10-21: the optional "another person" path must not be the loudest thing
    // on the Pairing page. Owner directive 2026-11-09: same grouped-row grammar as the hub
    // and Settings; the roster explanation reads as the note under the two actions.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.partner_pair_add_user_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                IndexRow(
                    icon = Icons.Outlined.PersonAdd,
                    title = stringResource(R.string.partner_pair_invite_cta),
                    subtitle = stringResource(R.string.partner_pair_invite_body),
                    onClick = { onAddUser(CeremonyMode.INVITE) },
                )
                RowDivider()
                IndexRow(
                    icon = Icons.Outlined.Handshake,
                    title = stringResource(R.string.partner_pair_join_cta),
                    subtitle = stringResource(R.string.partner_pair_join_body),
                    onClick = { onAddUser(CeremonyMode.JOIN) },
                )
            }
        }
        Text(
            stringResource(R.string.partner_pair_add_user_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One roster row: radio, name, paired-since. Shared by the "People you support" sheet (#96),
 * where the roster switches, renames, or grows.
 */
@Composable
private fun PartnerUserRow(
    user: PartnerUserUi,
    selected: Boolean,
    onSelect: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                user.label ?: shortKeyId(user.keyId),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.partner_users_since,
                    pairedOnText(user.partneredAtEpochMillis),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}

/**
 * #96 (owner ruling): *"as we do them and move on … the partner needs to be able to see or
 * change the partner"*. On the Activity and Pairing subpages — which act on exactly one
 * supported user — the page names which one, and when there is a roster, switching happens
 * right here (a dialog over the page, not a trip back to the hub), keeping the viewer where
 * they were. The Requests queue deliberately does not carry it (owner ruling 2026-09-29).
 *
 * Owner directive 2026-11-09: a quiet person line under the back row, not a tinted band. The
 * band repeated the hub hero's "Viewing <name>" on every page and the two-line stack made the
 * subpages open with their own header twice.
 */
@Composable
private fun PartnerContextLine(label: String, canSwitch: Boolean, onSwitch: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.Outlined.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (canSwitch) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSwitch) {
                Text(stringResource(R.string.partner_context_switch))
            }
        }
    }
}

/**
 * The "this pairing is gone — pair again" card, shared by the unpaired, wipe-detected and
 * unreachable states: the title/body that fits that state, then the ceremony's two entry modes.
 * [onRePair] is null on the harness path, where no production ceremony can be raised — the
 * diagnosis still stands, only the way out is absent.
 */
@Composable
private fun PairingGoneCard(titleRes: Int, bodyRes: Int, onRePair: ((CeremonyMode) -> Unit)?) {
    InfoCard(title = stringResource(titleRes), body = stringResource(bodyRes)) {
        if (onRePair != null) {
            PairingEntryActions(
                onInvite = { onRePair(CeremonyMode.INVITE) },
                onJoin = { onRePair(CeremonyMode.JOIN) },
            )
        }
    }
}

/** The two ways into the ceremony, shared by the unpaired and wipe-detected cards. */
@Composable
private fun PairingEntryActions(onInvite: () -> Unit, onJoin: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Button(onClick = onInvite, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.partner_pair_invite_cta))
        }
        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.partner_pair_join_cta))
        }
    }
}

/**
 * True when the sync state has something the user may have to act on: never synced, stale, or a
 * send the server rate-limited. A healthy sync stays off the hub — its detail, and the manual
 * Check now, live on the Pairing page. An unreachable pairing is deliberately not this case: it
 * has its own card, which names the cause instead of offering the network as a suspect.
 */
/**
 * Whether the last known state answers for [selectedPairId] — used only when a reload failed.
 * States that name a pairing (Paired, Ended) may be kept only under their own selection; with
 * no selection the persisted pick is absent and the screen keeps what it had. Pairing-less
 * states describe this device, not one partner, so they are always keepable.
 */
private fun keepsUnderSelection(state: PartnerDashboardUi?, selectedPairId: String?): Boolean =
    when (state) {
        is PartnerDashboardUi.Paired -> selectedPairId == null || state.pairId == selectedPairId
        is PartnerDashboardUi.Ended -> selectedPairId == null || state.pairId == selectedPairId
        else -> true
    }

private fun syncNeedsAttention(state: PartnerDashboardUi.Paired): Boolean =
    !state.pairingUnreachable && (
        state.lastSyncedAtEpochMillis == null ||
            state.freshness == SyncFreshness.STALE ||
            state.rateLimitedRecently
    )

@Composable
private fun ChainStatusCard(status: PartnerChainStatus) {
    val (titleRes, bodyRes) = when (status) {
        PartnerChainStatus.HEALTHY -> return
        PartnerChainStatus.CATCHING_UP ->
            R.string.partner_chain_catching_up_title to R.string.partner_chain_catching_up_body
        PartnerChainStatus.INCOMPLETE ->
            R.string.partner_chain_incomplete_title to R.string.partner_chain_incomplete_body
        PartnerChainStatus.UNVERIFIED ->
            R.string.partner_chain_unverified_title to R.string.partner_chain_unverified_body
    }
    // UNVERIFIED is the trust-blocking state: error container, visually distinct from the
    // neutral INCOMPLETE card. Neither claims a re-pair happened or is required to happen.
    val unverified = status == PartnerChainStatus.UNVERIFIED
    PartnerSurface(
        tone = if (unverified) CardTone.ALERT else CardTone.NOTICE,
        spacing = 4.dp,
    ) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(bodyRes), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    PartnerSurface(spacing = 4.dp) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        actions()
    }
}

/** "3f9a2c11…" — the partner's attested-key fingerprint, enough to recognize, not to confuse. */
internal fun shortKeyId(keyId: String): String =
    if (keyId.length > 12) keyId.take(12) + "…" else keyId

/** "Sep 19, 2026" — the pairing date, shared by the hub's identity row, the Pairing page and
 *  the plans editor's recipient choice (two pairs can carry the same person's name). */
internal fun pairedOnText(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))

private const val TAG = "MyExodusPartnerTab"

/** Hub-tile datum for plans (ADR #193): [todo] null = nothing on this phone is waiting to be
 *  checked off (no incoming plans), so the tile shows its description instead of a zero. */
private data class PlanHubStatus(val hasPlans: Boolean, val todo: Int?)

/** Plan Step 6 partner-alert banner. Same Surface grammar as every persistent in-app state
 *  (§9 row / acceptance line 631); [action] is null only for the unsupported-provider case,
 *  where no in-app or settings switch can fix the device. */
@Composable
private fun AlertHealthBanner(
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
            if (action != null) {
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

