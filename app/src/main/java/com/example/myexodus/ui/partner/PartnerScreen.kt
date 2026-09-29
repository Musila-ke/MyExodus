package com.example.myexodus.ui.partner

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myexodus.R
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.state.UnpairPhase
import com.example.myexodus.accountability.state.UnpairState
import com.example.myexodus.ui.AppMark
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.NavTile
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.recovery.outcomeLabel
import com.example.myexodus.ui.recovery.techniqueTitle
import com.example.myexodus.ui.relativeTimeText
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberWindowWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
private const val VIEW_REQUESTS = "requests"
private const val VIEW_ACTIVITY = "activity"
private const val VIEW_PAIRING = "pairing"

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
 * The chain verdict as a glanceable mark. The glyph changes with the state and carries the
 * words, so the verdict never depends on colour alone — the tint only reinforces it. A healthy
 * chain still draws (`VerifiedUser`, quiet tone): the mark's job is to say "checked and fine",
 * which is a different statement from the warning cards' absence.
 */
@Composable
private fun ChainVerdictMark(status: PartnerChainStatus) {
    val (icon, description) = when (status) {
        PartnerChainStatus.HEALTHY ->
            Icons.Outlined.VerifiedUser to stringResource(R.string.partner_chain_verified_mark)
        PartnerChainStatus.CATCHING_UP ->
            Icons.Outlined.Sync to stringResource(R.string.partner_chain_catching_up_title)
        PartnerChainStatus.INCOMPLETE ->
            Icons.Outlined.History to stringResource(R.string.partner_chain_incomplete_title)
        PartnerChainStatus.UNVERIFIED ->
            Icons.Outlined.GppBad to stringResource(R.string.partner_chain_unverified_title)
    }
    val tone = when (status) {
        PartnerChainStatus.HEALTHY -> null
        PartnerChainStatus.UNVERIFIED -> CardTone.ALERT
        else -> CardTone.NOTICE
    }
    Surface(
        color = tone?.container() ?: MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = tone?.onContainer() ?: MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
        }
    }
}

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
    notificationsEnabled: Boolean,
    onFixNotifications: () -> Unit,
    onCheckNow: suspend () -> Boolean,
    modifier: Modifier = Modifier,
    /** §8 Step 16: true while the system dialog can still be raised (33+, not yet asked);
     *  false means the only fix path left is the settings screen. */
    notificationsRequestable: Boolean = false,
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
     *  partner the composer names (#96). False = nothing was written (blank/oversized body, or
     *  the store refused) — the dialog stays open and says so. */
    onSendMessage: suspend (pairId: String, text: String) -> Boolean = { _, _ -> false },
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<PartnerDashboardUi?>(null) }
    var view by rememberSaveable { mutableStateOf<String?>(null) }
    // System back leaves a subpage the way its BackRow does. The ceremony registers its own
    // handler later in composition — it must cancel the live token, so it wins while open.
    BackHandler(enabled = view != null) { view = null }
    var selectedPairId by rememberSaveable { mutableStateOf(selection?.selectedPairId) }
    var checking by remember { mutableStateOf(false) }
    var checkFailed by remember { mutableStateOf(false) }
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
    var messageFailed by remember { mutableStateOf(false) }

    suspend fun reload() {
        state = runCatching { source.load(selectedPairId = selectedPairId, nowMillis = System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "dashboard load failed", it) }
            .getOrElse { state ?: PartnerDashboardUi.Loading }
        // The source falls back to the newest pairing when the stored pick is gone (unpaired,
        // or a fresh install's first pair): persist the resolved id, so the next cold start
        // shows this user instead of re-deciding.
        (state as? PartnerDashboardUi.Paired)?.let { paired ->
            if (paired.pairId != selectedPairId) {
                selectedPairId = paired.pairId
                selection?.selectedPairId = paired.pairId
            }
        }
    }

    /** §19.5: bring another supported user's dashboard forward — persisted, then reloaded.
     *  The subpage stays open (#96): switching who you are looking at must not also navigate
     *  you away — the reload re-scopes every stream this page renders to the new pair. */
    fun selectUser(pairId: String) {
        selectedPairId = pairId
        selection?.selectedPairId = pairId
        scope.launch(Dispatchers.IO) { reload() }
    }

    val runCheckNow: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            checking = true
            checkFailed = false
            val ok = runCatching { onCheckNow() }.getOrDefault(false)
            checking = false
            checkFailed = !ok
            reload()
        }
    }

    fun respondTo(requestEventId: String, approve: Boolean) {
        scope.launch(Dispatchers.IO) {
            respondingTo = requestEventId
            respondFailed = false
            val ok = runCatching { onRespondToApproval(requestEventId, approve) }
                .onFailure { Log.w(TAG, "approval response threw", it) }
                .getOrDefault(false)
            respondingTo = null
            respondFailed = !ok
            reload()
        }
    }

    fun runUnpair(pairId: String) {
        scope.launch(Dispatchers.IO) {
            unpairing = true
            unpairFailed = false
            val outcome = runCatching { onUnpair(pairId) }
                .onFailure { Log.w(TAG, "unpair initiation threw", it) }
                .getOrDefault(UnpairOutcome.FAILED)
            unpairing = false
            unpairFailed = outcome == UnpairOutcome.FAILED
            reload()
        }
    }

    LaunchedEffect(Unit) { reload() }

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
            onExit = {
                view = null
                scope.launch(Dispatchers.IO) { reload() }
            },
            modifier = modifier,
        )
        return
    }

    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    val paired = state as? PartnerDashboardUi.Paired
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            // in-app nag.
            if (!notificationsEnabled) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.partner_notifications_off_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.partner_notifications_off_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onFixNotifications) {
                            Text(
                                stringResource(
                                    if (notificationsRequestable) {
                                        R.string.partner_notifications_enable
                                    } else {
                                        R.string.partner_notifications_open_settings
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }

        when (val current = state) {
            null, PartnerDashboardUi.Loading -> Text(
                stringResource(R.string.partner_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PartnerDashboardUi.Unpaired -> InfoCard(
                title = stringResource(R.string.partner_unpaired_title),
                body = stringResource(R.string.partner_unpaired_body),
            ) {
                if (deps != null) {
                    PairingEntryActions(
                        onInvite = { view = VIEW_CEREMONY_INVITE },
                        onJoin = { view = VIEW_CEREMONY_JOIN },
                    )
                }
            }

            // §8 Step 17 (AD-22): says plainly what was lost and the one route forward —
            // re-enrollment (the ceremony, since §19.4 item 4). No recovery: the old pairing
            // cannot be restored, only replaced.
            PartnerDashboardUi.WipeDetected -> InfoCard(
                title = stringResource(R.string.partner_wipe_title),
                body = stringResource(R.string.partner_wipe_body),
            ) {
                if (deps != null) {
                    PairingEntryActions(
                        onInvite = { view = VIEW_CEREMONY_INVITE },
                        onJoin = { view = VIEW_CEREMONY_JOIN },
                    )
                }
            }

            is PartnerDashboardUi.Ended -> EndedCard(
                state = current,
                checking = checking,
                checkFailed = checkFailed,
                dismissing = dismissing,
                onCheckNow = runCheckNow,
                onDismiss = {
                    scope.launch(Dispatchers.IO) {
                        dismissing = true
                        runCatching { onDismissUnpair(current.pairId) }
                        dismissing = false
                        reload()
                    }
                },
            )

            is PartnerDashboardUi.Paired -> if (view != null) {
                BackRow(
                    label = stringResource(
                        when (view) {
                            VIEW_REQUESTS -> R.string.partner_tile_requests
                            VIEW_ACTIVITY -> R.string.partner_tile_activity
                            else -> R.string.partner_tile_pairing
                        },
                    ),
                    onBack = { view = null },
                )
                // #96 (owner ruling): every subpage acts on one partner, so every subpage says
                // which one, and the ones with a roster let the viewer switch right there —
                // without leaving the page they are on.
                PartnerContextBar(current, onSwitch = { switching = true })
                when (view) {
                    VIEW_REQUESTS -> RequestsView(
                        state = current,
                        respondingTo = respondingTo,
                        respondFailed = respondFailed,
                        onRespond = ::respondTo,
                    )
                    VIEW_ACTIVITY -> {
                        ComposeMessageCard(onCompose = { composing = true })
                        ActivityCard(current.alerts)
                    }
                    else -> PairingView(
                        state = current,
                        checking = checking,
                        checkFailed = checkFailed,
                        onCheckNow = runCheckNow,
                        unpairing = unpairing,
                        unpairFailed = unpairFailed,
                        onStartUnpair = { confirmingUnpair = true },
                        // §19.5: pairing again is how the roster grows; hidden on the harness
                        // path where no production ceremony exists.
                        onAddUser = if (deps != null) {
                            { mode ->
                                view = if (mode == CeremonyMode.INVITE) {
                                    VIEW_CEREMONY_INVITE
                                } else {
                                    VIEW_CEREMONY_JOIN
                                }
                            }
                        } else {
                            null
                        },
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
                PartnerIdentityCard(current)
                // §19.5 requirement 1: with more than one supported user the identity card
                // names only one of them, so the roster that can bring the others forward
                // sits directly under it. One user = nothing to switch to = no card.
                if (current.users.size > 1) {
                    PartnerUsersCard(
                        users = current.users,
                        selectedPairId = current.pairId,
                        onSelect = ::selectUser,
                        onRename = { renaming = it },
                    )
                }
                // §7 Step 11 delta: the partner's shared aggregate, directly under who they are
                // — the tab's own question ("how are they doing?") answered before the
                // mechanism cards. Absent until something has actually arrived.
                PartnerSummaryCard(current.activity, current.chainStatus)
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
                // Pairing page, but it returns to the hub whenever it has something the user may
                // have to act on. A healthy sync stays quiet here.
                if (checkFailed || syncNeedsAttention(current)) {
                    SyncCard(
                        state = current,
                        checking = checking,
                        checkFailed = checkFailed,
                        onCheckNow = runCheckNow,
                        tone = CardTone.NOTICE,
                        prominentAction = false,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NavTile(
                        icon = Icons.Outlined.Inbox,
                        label = stringResource(R.string.partner_tile_requests),
                        status = if (pending.isEmpty()) {
                            stringResource(R.string.partner_tile_requests_none)
                        } else {
                            stringResource(R.string.partner_tile_requests_waiting, pending.size)
                        },
                        onClick = { view = VIEW_REQUESTS },
                        modifier = Modifier.weight(1f),
                    )
                    NavTile(
                        icon = Icons.Outlined.Timeline,
                        label = stringResource(R.string.partner_tile_activity),
                        status = stringResource(
                            R.string.partner_tile_activity_week,
                            current.alerts.weekCount,
                        ),
                        onClick = { view = VIEW_ACTIVITY },
                        modifier = Modifier.weight(1f),
                    )
                    NavTile(
                        icon = Icons.Outlined.Handshake,
                        label = stringResource(R.string.partner_tile_pairing),
                        status = if (current.lastSyncedAtEpochMillis == null) {
                            stringResource(R.string.partner_tile_never_synced)
                        } else {
                            stringResource(
                                R.string.partner_tile_synced,
                                relativeTimeText(
                                    System.currentTimeMillis(),
                                    current.lastSyncedAtEpochMillis,
                                ),
                            )
                        },
                        onClick = { view = VIEW_PAIRING },
                        modifier = Modifier.weight(1f),
                    )
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
            title = { Text(stringResource(R.string.partner_switch_title)) },
            text = {
                Column {
                    users.forEach { user ->
                        PartnerUserRow(
                            user = user,
                            selected = user.pairId == paired?.pairId,
                            onSelect = {
                                switching = false
                                selectUser(user.pairId)
                            },
                        )
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
        ComposeMessageDialog(
            sending = messageSending,
            failed = messageFailed,
            // #96: the message names its recipient, and goes to the pair it names — the
            // selected partner at the moment Send is tapped.
            toName = paired?.let { it.partnerLabel ?: shortKeyId(it.partnerKeyId) }.orEmpty(),
            onSend = { text ->
                val target = paired?.pairId
                if (target == null) {
                    messageFailed = true
                } else {
                    scope.launch(Dispatchers.IO) {
                        messageSending = true
                        messageFailed = false
                        val ok = runCatching { onSendMessage(target, text) }
                            .onFailure { Log.w(TAG, "message send threw", it) }
                            .getOrDefault(false)
                        messageSending = false
                        messageFailed = !ok
                        if (ok) composing = false
                    }
                }
            },
            onDismiss = {
                composing = false
                messageFailed = false
            },
        )
    }

    // Same hoisting reason as the unpair dialog: renaming a row must survive a reload that
    // swaps the user list out from under the open dialog.
    renaming?.let { user ->
        var name by remember(user.pairId) { mutableStateOf(user.label.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.partner_users_rename_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.partner_users_rename_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.partner_users_rename_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renaming = null
                        scope.launch(Dispatchers.IO) {
                            runCatching { onRenameUser(user.pairId, name) }
                                .onFailure { Log.w(TAG, "rename failed", it) }
                            reload()
                        }
                    },
                ) {
                    Text(stringResource(R.string.partner_users_rename_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
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
@Composable
private fun PartnerSummaryCard(activity: PartnerActivityUi?, chainStatus: PartnerChainStatus) {
    PartnerSurface(spacing = 12.dp) {
        Text(
            stringResource(R.string.partner_summary_title),
            style = MaterialTheme.typography.titleMedium,
        )
        if (activity == null) {
            Text(
                stringResource(R.string.partner_summary_no_events),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // Days free is the one number the tab exists to answer, so it takes the
                // headline weight the reference cards give their single dominant metric.
                StatBlock(stringResource(R.string.partner_summary_days), activity.daysFree)
                StatBlock(stringResource(R.string.partner_summary_relapses), activity.relapseCount)
            }
            Text(
                stringResource(
                    R.string.partner_summary_milestones,
                    activity.milestonesReached.joinToString(", ").ifEmpty {
                        stringResource(R.string.partner_summary_milestones_none)
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            activity.lastEventOccurredAtEpochMillis?.let { at ->
                Text(
                    stringResource(
                        R.string.partner_summary_last_event,
                        relativeTimeText(System.currentTimeMillis(), at),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        activity?.pinnedMessageText?.let { message ->
            HorizontalDivider()
            Text(
                stringResource(R.string.partner_summary_pinned_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
        // §8.11 degraded: a gap means these numbers are not the whole story, and saying so is
        // cheaper than letting the user believe a stale card.
        if (activity != null && chainStatus != PartnerChainStatus.HEALTHY) {
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
 * the receiving projection enforce). The message is journaled locally and rides the normal
 * sync rails — this dialog never talks to the network, so "sending" means "written, will be
 * delivered by the next sync", which is what the copy says.
 */
@Composable
private fun ComposeMessageDialog(
    sending: Boolean,
    failed: Boolean,
    /** #96: who this message is addressed to — the selected partner, named verbatim. */
    toName: String,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var tooLong by remember { mutableStateOf(false) }
    val suggestions = listOf(
        stringResource(R.string.partner_compose_suggestion_1),
        stringResource(R.string.partner_compose_suggestion_2),
        stringResource(R.string.partner_compose_suggestion_3),
    )
    val max = EventPayload.MAX_ACTIVITY_TEXT_LENGTH
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
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
                if (failed) {
                    Text(
                        stringResource(R.string.partner_compose_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(text) },
                enabled = !sending && text.isNotBlank() && !tooLong,
            ) {
                Text(
                    stringResource(
                        if (sending) R.string.partner_compose_sending else R.string.partner_compose_send,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) {
                Text(stringResource(R.string.partner_compose_cancel))
            }
        },
    )
}

/** The Activity subpage's leading card: the one action this page owns. */
@Composable
private fun ComposeMessageCard(onCompose: () -> Unit) {
    PartnerSurface(spacing = 4.dp) {
        Text(
            stringResource(R.string.partner_compose_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.partner_compose_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onCompose) { Text(stringResource(R.string.partner_compose_action)) }
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
                val nowMillis = System.currentTimeMillis()
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
        requests.forEach { request ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(request.message, style = MaterialTheme.typography.bodyMedium)
                Text(
                    requestStatusText(request.status) + " · " +
                        relativeTimeText(System.currentTimeMillis(), request.createdAtEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            if (state.phase == UnpairPhase.AWAITING_CONFIRMATION) {
                Button(onClick = onCheckNow, enabled = !checking) {
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
 * The hub's first row: who this device is paired with, and the chain verdict as a mark beside
 * it. The mark is deliberately not a portrait — the pairing model has no portrait and, without
 * a §19.5 label, no name either; drawing one would be inventing an identity the app does not
 * have. The label is the partner's own words for themselves, so it is shown verbatim, with the
 * key fragment only as the never-labelled fallback.
 */
@Composable
private fun PartnerIdentityCard(state: PartnerDashboardUi.Paired) {
    PartnerSurface(spacing = 4.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChainVerdictMark(state.chainStatus)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(
                        R.string.partner_paired_with,
                        state.partnerLabel ?: shortKeyId(state.partnerKeyId),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.partner_paired_since, pairedOnText(state.pairedAtEpochMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Sync detail. The same card serves two jobs, so it takes its emphasis from the caller: on the
 * Pairing page it is the mechanism the user came for, with a filled action; on the hub it is
 * there only because something needs attention, and there a filled button would make a routine
 * resync the loudest control on a page whose real subject is the partner.
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
    onCheckNow: () -> Unit,
    tone: CardTone = CardTone.CALM,
    prominentAction: Boolean = true,
    showIdentity: Boolean = false,
) {
    PartnerSurface(tone = tone) {
        if (showIdentity) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChainVerdictMark(state.chainStatus)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            R.string.partner_paired_with,
                            state.partnerLabel ?: shortKeyId(state.partnerKeyId),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
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
                }
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
            stringResource(
                if (state.lastSyncedAtEpochMillis == null) R.string.partner_never_synced
                else R.string.partner_last_synced,
                relativeTimeText(System.currentTimeMillis(), state.lastSyncedAtEpochMillis),
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        if (state.freshness == SyncFreshness.STALE) {
            Text(
                stringResource(R.string.partner_sync_delayed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
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
        if (checkFailed) {
            Text(
                stringResource(R.string.partner_check_now_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ActivityCard(summary: AlertSummary) {
    PartnerSurface {
        Text(
            stringResource(R.string.partner_activity_title, summary.recent.size),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatBlock(stringResource(R.string.partner_activity_today), summary.todayCount)
            StatBlock(stringResource(R.string.partner_activity_week), summary.weekCount)
        }
        if (summary.recent.isEmpty()) {
            Text(
                stringResource(R.string.partner_activity_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            HorizontalDivider()
            summary.recent.forEach { alert -> AlertRow(alert) }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: Int) {
    Column {
        Text(
            value.toString(),
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
    val (label, kindRes) = when (alert) {
        is PartnerAlert.AppActivity -> rememberAppLabel(alert.target) to
            (if (alert.isOverride) R.string.partner_alert_override else R.string.partner_alert_blocked)
        is PartnerAlert.Recovery -> stringResource(techniqueTitle(alert.technique)) to
            outcomeLabel(alert.outcome.uppercase())
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
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(kindRes) + " · " + relativeTimeText(System.currentTimeMillis(), alert.atEpochMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    onCheckNow: () -> Unit,
    unpairing: Boolean,
    unpairFailed: Boolean,
    onStartUnpair: () -> Unit,
    onAddUser: ((CeremonyMode) -> Unit)?,
) {
    SyncCard(
        state = state,
        checking = checking,
        checkFailed = checkFailed,
        onCheckNow = onCheckNow,
        showIdentity = true,
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
    PartnerSurface(spacing = 8.dp) {
        Text(
            stringResource(R.string.partner_pair_add_user_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.partner_pair_add_user_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { onAddUser(CeremonyMode.INVITE) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.partner_pair_invite_cta))
        }
        OutlinedButton(onClick = { onAddUser(CeremonyMode.JOIN) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.partner_pair_join_cta))
        }
    }
}

/**
 * §19.5 requirement 1: every active enrollment as a selectable row, newest first. Rendered only
 * when more than one exists — with a single user the identity card already names them, and a
 * one-row picker would be a control with nothing to choose. Selection is a radio row (not a
 * dropdown) so the alternatives stay visible: this device is deliberately asking the partner to
 * hold several people in view at once, and hiding them behind a tap would contradict that.
 */
@Composable
private fun PartnerUsersCard(
    users: List<PartnerUserUi>,
    selectedPairId: String,
    onSelect: (String) -> Unit,
    onRename: (PartnerUserUi) -> Unit,
) {
    PartnerSurface(spacing = 0.dp) {
        Text(
            stringResource(R.string.partner_users_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        users.forEach { user ->
            PartnerUserRow(
                user = user,
                selected = user.pairId == selectedPairId,
                onSelect = { onSelect(user.pairId) },
                trailing = {
                    IconButton(onClick = { onRename(user) }) {
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
    }
}

/**
 * One roster row: radio, name, paired-since. Shared by the hub's roster card and the subpage
 * switcher dialog (#96), so "which partner is selected" looks the same wherever it is asked.
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
 * change the partner"*. Every partner subpage acts on exactly one supported user, so the page
 * names which one — and when there is a roster, switching happens right here (a dialog over the
 * page, not a trip back to the hub), keeping the viewer where they were.
 */
@Composable
private fun PartnerContextBar(state: PartnerDashboardUi.Paired, onSwitch: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.partner_context_label),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    state.partnerLabel ?: shortKeyId(state.partnerKeyId),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.users.size > 1) {
                TextButton(onClick = onSwitch) {
                    Text(stringResource(R.string.partner_context_switch))
                }
            }
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
 * Check now, live on the Pairing page.
 */
private fun syncNeedsAttention(state: PartnerDashboardUi.Paired): Boolean =
    state.lastSyncedAtEpochMillis == null ||
        state.freshness == SyncFreshness.STALE ||
        state.rateLimitedRecently

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

/** "Sep 19, 2026" — the pairing date, shared by the hub's identity row and the Pairing page. */
private fun pairedOnText(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))

private const val TAG = "MyExodusPartnerTab"
