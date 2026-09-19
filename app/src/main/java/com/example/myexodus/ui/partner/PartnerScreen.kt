package com.example.myexodus.ui.partner

import android.util.Log
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.state.UnpairPhase
import com.example.myexodus.accountability.state.UnpairState
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.recovery.outcomeLabel
import com.example.myexodus.ui.recovery.techniqueTitle
import com.example.myexodus.ui.rememberAppLabel
import com.example.myexodus.ui.rememberWindowWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    /** Step 14: biometric-gated unpair initiation. False (cancelled/refused) leaves the pair
     *  intact — the local shred is unconditional once the request is acted on, so a refused
     *  prompt must abort before anything is journaled. */
    onUnpair: suspend (pairId: String) -> Boolean = { _ -> false },
    /** Step 14: deletes the dismissed terminal card's journal row (guarded in the journal). */
    onDismissUnpair: suspend (pairId: String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<PartnerDashboardUi?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkFailed by remember { mutableStateOf(false) }
    var respondingTo by remember { mutableStateOf<String?>(null) }
    var respondFailed by remember { mutableStateOf(false) }
    var confirmingUnpair by remember { mutableStateOf(false) }
    var unpairing by remember { mutableStateOf(false) }
    var unpairFailed by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }

    suspend fun reload() {
        state = runCatching { source.load(System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "dashboard load failed", it) }
            .getOrElse { state ?: PartnerDashboardUi.Loading }
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

    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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

        when (val current = state) {
            null, PartnerDashboardUi.Loading -> Text(
                stringResource(R.string.partner_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PartnerDashboardUi.Unpaired -> InfoCard(
                title = stringResource(R.string.partner_unpaired_title),
                body = stringResource(R.string.partner_unpaired_body),
            )

            // §8 Step 17 (AD-22): says plainly what was lost and the one route forward —
            // re-enrollment. No recovery CTA: none exists to offer.
            PartnerDashboardUi.WipeDetected -> InfoCard(
                title = stringResource(R.string.partner_wipe_title),
                body = stringResource(R.string.partner_wipe_body),
            )

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

            is PartnerDashboardUi.Paired -> {
                // Needs-attention state first (verdict-first hierarchy): a chain problem is
                // the one thing on this screen the viewer may have to act on.
                ChainStatusCard(current.chainStatus)
                val pending = current.approvals.filter {
                    it.role == ApprovalRequest.ROLE_RESPONDER && it.status == ApprovalRequest.STATUS_REQUESTED
                }
                if (pending.isNotEmpty()) {
                    ApprovalRequestCard(
                        requests = pending,
                        chainStatus = current.chainStatus,
                        respondingTo = respondingTo,
                        respondFailed = respondFailed,
                        onRespond = { requestEventId, approve ->
                            scope.launch(Dispatchers.IO) {
                                respondingTo = requestEventId
                                respondFailed = false
                                val ok = runCatching { onRespondToApproval(requestEventId, approve) }
                                    .getOrDefault(false)
                                respondingTo = null
                                respondFailed = !ok
                                reload()
                            }
                        },
                    )
                }
                PartnerCard(current)
                SyncCard(
                    state = current,
                    checking = checking,
                    checkFailed = checkFailed,
                    onCheckNow = runCheckNow,
                )
                ActivityCard(current.alerts)
                val mine = current.approvals.filter { it.role == ApprovalRequest.ROLE_REQUESTER }
                if (mine.isNotEmpty()) MyRequestsCard(mine)
                // Destructive action last, low-emphasis: never adjacent to the routine cards.
                UnpairCard(
                    working = unpairing,
                    failed = unpairFailed,
                    onStart = { confirmingUnpair = true },
                )
                if (confirmingUnpair) {
                    AlertDialog(
                        onDismissRequest = { confirmingUnpair = false },
                        title = { Text(stringResource(R.string.partner_unpair_dialog_title)) },
                        text = { Text(stringResource(R.string.partner_unpair_dialog_body)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmingUnpair = false
                                    scope.launch(Dispatchers.IO) {
                                        unpairing = true
                                        unpairFailed = false
                                        val ok = runCatching { onUnpair(current.pairId) }
                                            .getOrDefault(false)
                                        unpairing = false
                                        unpairFailed = !ok
                                        reload()
                                    }
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
            }
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.partner_approvals_title),
                style = MaterialTheme.typography.titleMedium,
            )
            requests.forEach { request ->
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
                        color = if (canRespond) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
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
                HorizontalDivider()
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
}

/** Requester-side status list. Creation UI is owned by the Strict Mode disable gate (flagged
 *  off until a real partner device exists), so this renders only when rows exist. */
@Composable
private fun MyRequestsCard(requests: List<ApprovalUi>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.partner_my_requests_title),
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
}

/** The unpair entry: last, deliberately low-emphasis, and inert until the confirmation
 *  dialog (then the biometric prompt) commits — nothing is journaled before that. */
@Composable
private fun UnpairCard(
    working: Boolean,
    failed: Boolean,
    onStart: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun PartnerCard(state: PartnerDashboardUi.Paired) {
    val date = remember(state.pairedAtEpochMillis) {
        Instant.ofEpochMilli(state.pairedAtEpochMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.partner_paired_with, shortKeyId(state.partnerKeyId)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.partner_paired_since, date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Finding #6: the key-id fragment was a bare mystery string to a first-time
            // user. No alias exists in the pairing model, so the card explains the code
            // instead of inventing a name.
            Text(
                stringResource(R.string.partner_paired_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncCard(
    state: PartnerDashboardUi.Paired,
    checking: Boolean,
    checkFailed: Boolean,
    onCheckNow: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Button(onClick = onCheckNow, enabled = !checking) {
                Text(
                    stringResource(
                        if (checking) R.string.partner_check_now_running else R.string.partner_check_now,
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
    }
}

@Composable
private fun ActivityCard(summary: AlertSummary) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.partner_activity_title),
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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(kindRes) + " · " + relativeTimeText(System.currentTimeMillis(), alert.atEpochMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
    Surface(
        color = if (unverified) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (unverified) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(bodyRes), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** "3f9a2c11…" — the partner's attested-key fingerprint, enough to recognize, not to confuse. */
private fun shortKeyId(keyId: String): String =
    if (keyId.length > 12) keyId.take(12) + "…" else keyId

private fun relativeTimeText(nowMillis: Long, thenMillis: Long?): String =
    when (val rel = relativeTime(nowMillis, thenMillis ?: nowMillis)) {
        RelativeTime.JustNow -> "just now"
        is RelativeTime.Minutes -> "${rel.value}m ago"
        is RelativeTime.Hours -> "${rel.value}h ago"
        is RelativeTime.Days -> "${rel.value}d ago"
        is RelativeTime.Older -> Instant.ofEpochMilli(rel.epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d"))
    }

private const val TAG = "MyExodusPartnerTab"
