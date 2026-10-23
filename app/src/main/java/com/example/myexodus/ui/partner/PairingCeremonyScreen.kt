package com.example.myexodus.ui.partner

import android.content.ClipData
import androidx.activity.compose.BackHandler
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import com.example.myexodus.R
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.enrollment.PairingCeremonyTelemetry
import com.example.myexodus.accountability.enrollment.PairingCode
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.enrollment.PairingToken
import com.example.myexodus.accountability.enrollment.PartnerLabel
import com.example.myexodus.accountability.sync.PartnerAlertHealth
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.WindowWidth
import com.example.myexodus.ui.rememberWindowWidth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.Signature
import java.util.concurrent.atomic.AtomicBoolean

/** §19.4 item 4: which half of the ceremony this screen runs. */
enum class CeremonyMode { INVITE, JOIN }

/**
 * Everything the ceremony needs from the Activity. The protocol is called directly (the shipped
 * screen convention — [PartnerScreen] takes its source the same way); the three seams cover what
 * only the Activity can do: resolve the local uid (anonymous sign-in), read/write the device's
 * self-chosen name, and raise the biometric prompt (main-thread contract, NFR3/NFR4).
 */
data class PairingCeremonyDeps(
    val flow: PairingFlow,
    val myUid: suspend () -> String?,
    /** The stored self-chosen name, or null when the owner never chose one. */
    val loadName: suspend () -> String?,
    /** Persists the raw field text; the wiring site owns sanitizing and blank handling. */
    val saveName: suspend (String) -> Unit,
    val sign: suspend (title: String) -> Signature?,
    /** §14 bullet 1: one outcome event per terminal, on the Crashlytics rail. */
    val telemetry: PairingCeremonyTelemetry,
    /** §19.5: the pair just went live — persist it as the active selection so the hub comes
     *  up on the partner the user just paired with, not a stale earlier pick (defect found
     *  two-device 2026-10-04: pairing never wrote the selection). */
    val onPaired: (pairId: String) -> Unit,
)

/**
 * §19.4 item 4 (the plan's headline deliverable): the production pairing ceremony, entered from
 * [PartnerScreen]. Built from the owner's reference folder — the invite card's display-number
 * hierarchy, the OTP box row for code entry, the overline + hero-digits composition for the SAS
 * check, the milestone card for the terminal state — rendered exclusively in Desert Dawn tokens.
 *
 * The ceremony is a human-speed state machine over the shipped [PairingFlow]: the SAS check
 * gates every signature (both roles — the two-phase `prepareJoin`/`finishJoin` split exists for
 * exactly this), and a mismatch cancels the token instead of proceeding. All network work runs
 * off the main thread; the screen never renders a raw protocol failure — the diagnostic reason
 * goes to logcat, the user reads mapped copy.
 */
@Composable
fun PairingCeremonyScreen(
    mode: CeremonyMode,
    deps: PairingCeremonyDeps,
    onExit: () -> Unit,
    /** SOS §8.14 (Step 7): the pairing-success ask's inputs — the pair just became active,
     *  so this is the plan's moment to request notification permission. */
    notificationsEnabled: Boolean,
    notificationsRequestable: Boolean,
    onFixNotifications: () -> Unit,
    /** Plan Step 6: the onboarding check is not only the API-33 permission — a muted
     *  partner-activity channel or a missing push provider also breaks alerts. */
    modifier: Modifier = Modifier,
    alertHealth: PartnerAlertHealth.State = PartnerAlertHealth.State.OK,
    onSendTestAlert: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // §14 bullet 1 says ONE event per attempt terminal. Taps land faster than recomposition:
    // a double-tap on Cancel (or back-row while a cancel coroutine is in flight) would report
    // twice, inflating a rate the plan reads as a security signal. Reset per attempt start.
    val reported = remember { AtomicBoolean(false) }
    var step by remember { mutableStateOf<CeremonyStep>(CeremonyStep.Entry) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeError by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    // Resolved in composition (configuration-aware) and captured by the local funs below.
    val signTitle = stringResource(R.string.partner_ceremony_sign_title)

    LaunchedEffect(Unit) {
        val stored = runCatching { deps.loadName() }.getOrNull()
        if (stored != null) name = stored
    }

    fun reportOnce(outcome: PairingCeremonyTelemetry.Outcome) {
        if (reported.compareAndSet(false, true)) deps.telemetry.outcome(outcome)
    }

    fun fail(
        title: Int,
        body: Int,
        outcome: PairingCeremonyTelemetry.Outcome = PairingCeremonyTelemetry.Outcome.FAILED,
    ) {
        reportOnce(outcome)
        step = CeremonyStep.Failed(title, body)
    }

    /** Signed-pairing finish, shared by both roles: one biometric prompt, then the flow call. */
    fun signAndFinish() {
        if (working) return
        val current = pending ?: return
        // Finding #55 (the finding #17 class, RuleEditor): the flag is written on Main before
        // the dispatch. Written inside the IO coroutine, the tap window before dispatch let a
        // second "They match" launch a second prompt and a racing submit — the losing submit
        // then rendered "already used" over a pairing that had just succeeded.
        working = true
        scope.launch(Dispatchers.IO) {
            val uid = deps.myUid()
            if (uid == null) {
                working = false
                fail(R.string.partner_ceremony_offline_title, R.string.partner_ceremony_offline_body)
                return@launch
            }
            val signature = runCatching { deps.sign(signTitle) }.getOrNull()
            if (signature == null) {
                // Cancelled prompt: nothing happened, the SAS check still stands — stay put.
                working = false
                return@launch
            }
            val label = PartnerLabel.sanitize(name)
            val outcome = runCatchingCancellable {
                when (current) {
                    is Pending.Initiator -> deps.flow.submitInitiatorSignature(
                        current.await, current.token, uid, signature,
                    )
                    is Pending.Partner -> deps.flow.finishJoin(
                        current.preparation, uid, signature, label,
                    )
                }
            }.onFailure { Log.w(TAG, "ceremony submit failed", it) }.getOrNull()
            working = false
            when (outcome) {
                is PairingFlow.Outcome.Paired -> {
                    reportOnce(PairingCeremonyTelemetry.Outcome.PAIRED)
                    deps.onPaired(outcome.pairId)
                    step = CeremonyStep.Paired(
                        pairId = outcome.pairId,
                        partnerLabel = when (current) {
                            is Pending.Initiator -> current.await.partnerLabel
                            is Pending.Partner -> current.preparation.initiatorLabel
                        },
                        partnerKeyId = when (current) {
                            is Pending.Initiator ->
                                EnrollmentTranscript.senderKeyId(current.await.partnerSigningPublicKey.encoded)
                            is Pending.Partner ->
                                EnrollmentTranscript.senderKeyId(current.preparation.initiatorSigningPublicKey.encoded)
                        },
                    )
                }
                is PairingFlow.Outcome.TokenExpired -> fail(
                    R.string.partner_ceremony_expired_title,
                    R.string.partner_ceremony_expired_body,
                    PairingCeremonyTelemetry.Outcome.EXPIRED,
                )
                is PairingFlow.Outcome.TokenAlreadyUsed -> fail(
                    R.string.partner_ceremony_used_title,
                    R.string.partner_ceremony_used_body,
                    PairingCeremonyTelemetry.Outcome.TOKEN_USED,
                )
                // The raw reason is written for a developer reading logcat (it can name key
                // parsing and registration internals) — the user reads mapped copy. A null
                // outcome is an exception from the submit call itself (transport), the one
                // case where the connectivity copy is true.
                is PairingFlow.Outcome.Rejected, null -> {
                    val reason = (outcome as? PairingFlow.Outcome.Rejected)?.reason
                    Log.w(TAG, "pairing rejected: $reason")
                    val (title, body) = pairingFailureCopy(reason)
                    fail(title, body)
                }
            }
            pending = null
        }
    }

    fun startInvite() {
        if (working) return
        working = true
        reported.set(false)
        scope.launch(Dispatchers.IO) {
            runCatching { deps.saveName(name) }.onFailure { Log.w(TAG, "saving name failed", it) }
            val uid = deps.myUid()
            val attempt = uid?.let {
                runCatching { deps.flow.initiate(it, PartnerLabel.sanitize(name)) }
                    // Without this the protocol's failure reason is discarded (the missing-key
                    // stall of 2026-09-28 surfaced as copy for a network problem).
                    .onFailure { e -> Log.w(TAG, "invite failed", e) }
            }
            working = false
            val initiation = attempt?.getOrNull()
            if (initiation == null) {
                // #22 class, create side: a server denial (rules / App Check) is not a
                // connectivity problem — only a transport-shaped throw or a missing session
                // (uid == null) gets the offline copy.
                val (title, body) = pairingFailureCopy(attempt?.exceptionOrNull().serverRejectionReason())
                fail(title, body)
                return@launch
            }
            step = CeremonyStep.ShowingCode(
                token = initiation.token,
                code = PairingCode.encode(initiation.token),
            )
        }
    }

    fun startJoin() {
        if (working) return
        // The code can be checked without the network (ADR #97) — a mistyped character is a
        // field error here, never a round-trip that reads back as a pairing failure.
        if (PairingCode.parse(code) is PairingCode.ParseResult.Invalid) {
            codeError = true
            return
        }
        working = true
        reported.set(false)
        scope.launch(Dispatchers.IO) {
            runCatching { deps.saveName(name) }.onFailure { Log.w(TAG, "saving name failed", it) }
            val uid = deps.myUid()
            val attempt = uid?.let {
                runCatching { deps.flow.prepareJoinWithCode(PairingCode.normalize(code), it) }
                    .onFailure { e -> Log.w(TAG, "join prepare failed", e) }
            }
            working = false
            val prepared = attempt?.getOrNull()
            if (prepared == null) {
                // Same #22 class as the invite side: a thrown server denial keeps the neutral
                // copy; only transport-shaped throws and a missing session read as offline.
                val (title, body) = pairingFailureCopy(attempt?.exceptionOrNull().serverRejectionReason())
                fail(title, body)
                return@launch
            }
            when (prepared) {
                is PairingFlow.JoinPreparationResult.Ready -> {
                    pending = Pending.Partner(prepared.preparation)
                    step = CeremonyStep.Compare(prepared.preparation.sas)
                }
                is PairingFlow.JoinPreparationResult.Failed -> when (prepared.outcome) {
                    is PairingFlow.Outcome.TokenExpired -> fail(
                        R.string.partner_ceremony_expired_title,
                        R.string.partner_ceremony_expired_body,
                        PairingCeremonyTelemetry.Outcome.EXPIRED,
                    )
                    is PairingFlow.Outcome.TokenAlreadyUsed -> fail(
                        R.string.partner_ceremony_used_title,
                        R.string.partner_ceremony_used_body,
                        PairingCeremonyTelemetry.Outcome.TOKEN_USED,
                    )
                    else -> {
                        // Verdicts from the prepare phase: unreadable token, failed partner
                        // verification, or a read error — mapped, with the raw reason in logcat.
                        val reason = (prepared.outcome as? PairingFlow.Outcome.Rejected)?.reason
                        Log.w(TAG, "join prepare rejected: $reason")
                        val (title, body) = pairingFailureCopy(reason)
                        fail(title, body)
                    }
                }
            }
        }
    }

    /** The SAS check failed on the human side: kill the token so neither device proceeds. */
    fun rejectMismatch() {
        val current = pending
        scope.launch(Dispatchers.IO) {
            when (current) {
                is Pending.Initiator -> runCatching { deps.flow.cancel(current.token) }
                    .onFailure { Log.w(TAG, "cancel after mismatch failed", it) }
                is Pending.Partner -> runCatching { deps.flow.cancel(current.preparation.token) }
                    .onFailure { Log.w(TAG, "cancel after mismatch failed", it) }
                null -> Unit
            }
            pending = null
            fail(
                R.string.partner_ceremony_mismatch_title,
                R.string.partner_ceremony_mismatch_body,
                PairingCeremonyTelemetry.Outcome.SAS_MISMATCH,
            )
        }
    }

    /** The one exit path, shared by the BackRow and the system back button. */
    fun exitCeremony() {
        // Leaving mid-invitation kills the code: a token nobody is waiting on must not
        // stay joinable. During the SAS check the token is likewise abandoned. The cancel
        // rides the flow's own scope — this screen leaves composition right below, and a
        // launch on the remembered scope dies with it before reaching the network.
        val token = (step as? CeremonyStep.ShowingCode)?.token
            ?: (pending as? Pending.Initiator)?.token
            ?: (pending as? Pending.Partner)?.preparation?.token
        // A live token means an attempt ends here — once. Backing out of a terminal
        // (pending already null) has nothing left to report.
        if (token != null) {
            reportOnce(PairingCeremonyTelemetry.Outcome.CANCELLED)
            deps.flow.cancelInBackground(token)
        }
        onExit()
    }

    BackHandler { exitCeremony() }

    val sidePadding = if (rememberWindowWidth() == WindowWidth.COMPACT) 16.dp else 24.dp
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = sidePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackRow(
            label = stringResource(
                when (step) {
                    is CeremonyStep.Failed -> R.string.partner_ceremony_back_title_failed
                    is CeremonyStep.Compare -> R.string.partner_ceremony_back_title_compare
                    // Owner report 2026-10-23: after success the header still read "Invite a
                    // partner" above a card saying "You're paired". The row names the screen;
                    // once paired, that name is the outcome, not the entry point.
                    is CeremonyStep.Paired -> R.string.partner_ceremony_back_title_paired
                    else -> if (mode == CeremonyMode.INVITE) {
                        R.string.partner_ceremony_title_invite
                    } else {
                        R.string.partner_ceremony_title_join
                    }
                },
            ),
            onBack = ::exitCeremony,
        )

        when (val current = step) {
            CeremonyStep.Entry -> if (mode == CeremonyMode.INVITE) {
                InviteEntry(
                    name = name,
                    onName = { name = it },
                    working = working,
                    onStart = ::startInvite,
                )
            } else {
                JoinEntry(
                    name = name,
                    onName = { name = it },
                    code = code,
                    codeError = codeError,
                    onCode = {
                        code = it
                        codeError = false
                    },
                    working = working,
                    onStart = ::startJoin,
                )
            }

            is CeremonyStep.ShowingCode -> ShowingCode(
                code = current.code,
                expiresAtEpochMillis = current.token.expiresAtEpochMillis,
                copied = copied,
                onCopy = { copied = true },
                onCancel = {
                    scope.launch(Dispatchers.IO) {
                        reportOnce(PairingCeremonyTelemetry.Outcome.CANCELLED)
                        runCatching { deps.flow.cancel(current.token) }
                            .onFailure { Log.w(TAG, "cancel invitation failed", it) }
                        step = CeremonyStep.Entry
                        copied = false
                    }
                },
            )

            is CeremonyStep.Compare -> CompareSas(
                sas = current.sas,
                mode = mode,
                working = working,
                onMatch = ::signAndFinish,
                onMismatch = ::rejectMismatch,
            )

            is CeremonyStep.Paired -> PairedTerminal(
                partnerLabel = current.partnerLabel,
                partnerKeyId = current.partnerKeyId,
                notificationsEnabled = notificationsEnabled,
                notificationsRequestable = notificationsRequestable,
                onFixNotifications = onFixNotifications,
                alertHealth = alertHealth,
                onSendTestAlert = onSendTestAlert,
                onDone = onExit,
            )

            is CeremonyStep.Failed -> FailedTerminal(
                title = stringResource(current.titleRes),
                body = stringResource(current.bodyRes),
                onRetry = {
                    pending = null
                    codeError = false
                    step = CeremonyStep.Entry
                },
                onClose = onExit,
            )
        }
    }

    // The invite's second phase: wait for the partner while the code is on screen. Cancelled
    // automatically when the step changes (a cancel or a completed wait).
    val showing = step as? CeremonyStep.ShowingCode
    if (showing != null) {
        LaunchedEffect(showing.token) {
            val await = runCatchingCancellable {
                deps.flow.awaitPartner(showing.token, PairingToken.TTL_MILLIS)
            }.onFailure { Log.w(TAG, "awaitPartner failed", it) }.getOrNull()
            if (await == null) {
                // Timeout, or the partner's material failed verification (the flow logs which).
                // Either way nothing was signed and the token is dead server-side.
                fail(
                    R.string.partner_ceremony_expired_title,
                    R.string.partner_ceremony_expired_body,
                    PairingCeremonyTelemetry.Outcome.EXPIRED,
                )
            } else {
                pending = Pending.Initiator(await, showing.token)
                step = CeremonyStep.Compare(await.sas)
            }
        }
    }
}

/**
 * What the screen shows. [Compare] carries no protocol object — the signature-gated step keeps
 * its [Pending] payload beside the step so process death drops an unfinished ceremony cleanly
 * (the token then dies with its 10-minute TTL) instead of resurrecting half a protocol.
 */
private sealed interface CeremonyStep {
    data object Entry : CeremonyStep
    data class ShowingCode(val token: PairingToken, val code: String) : CeremonyStep
    data class Compare(val sas: String) : CeremonyStep
    data class Paired(val pairId: String, val partnerLabel: String?, val partnerKeyId: String) : CeremonyStep
    data class Failed(val titleRes: Int, val bodyRes: Int) : CeremonyStep
}

private sealed interface Pending {
    data class Initiator(val await: PairingFlow.InitiatorAwait, val token: PairingToken) : Pending
    data class Partner(val preparation: PairingFlow.JoinPreparation) : Pending
}

@Composable
private fun CeremonyCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun CeremonyIntro(titleRes: Int, bodyRes: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NameField(name: String, onName: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text(stringResource(R.string.partner_ceremony_name_label)) },
        supportingText = { Text(stringResource(R.string.partner_ceremony_name_helper)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InviteEntry(name: String, onName: (String) -> Unit, working: Boolean, onStart: () -> Unit) {
    CeremonyIntro(R.string.partner_ceremony_invite_title, R.string.partner_ceremony_invite_body)
    CeremonyCard {
        NameField(name, onName)
        Button(
            onClick = onStart,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (working) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.partner_ceremony_invite_cta))
        }
    }
}

@Composable
private fun JoinEntry(
    name: String,
    onName: (String) -> Unit,
    code: String,
    codeError: Boolean,
    onCode: (String) -> Unit,
    working: Boolean,
    onStart: () -> Unit,
) {
    CeremonyIntro(R.string.partner_ceremony_join_title, R.string.partner_ceremony_join_body)
    CeremonyCard {
        CodeBoxes(code = code, onCode = onCode, isError = codeError)
        if (codeError) {
            Text(
                stringResource(R.string.partner_ceremony_code_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        NameField(name, onName)
        Button(
            onClick = onStart,
            enabled = !working && code.length == PairingCode.CODE_CHARS,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (working) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.partner_ceremony_join_cta))
        }
    }
}

/**
 * The typed-code box row (reference: OTP segmented entry). A transparent [BasicTextField]
 * underneath owns focus, the IME and accessibility — the boxes are decoration driven by its
 * value, so paste, keyboard type and TalkBack all behave like a normal field. It carries no
 * explicit font size: the field's metrics come from its own modifiers, and a literal here
 * would opt the screen out of the window-size type step (see NoLiteralTypeSizeTest).
 */
@Composable
private fun CodeBoxes(code: String, onCode: (String) -> Unit, isError: Boolean) {
    val focusRequester = remember { FocusRequester() }
    val value = PairingCode.normalize(code)
    val activeIndex = value.length
    val errorColor = MaterialTheme.colorScheme.error
    val boxColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = code,
            onValueChange = { raw -> onCode(PairingCode.normalize(raw).take(PairingCode.CODE_CHARS)) },
            singleLine = true,
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .focusRequester(focusRequester),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable { focusRequester.requestFocus() }
                .clearAndSetSemantics {},
        ) {
            for (i in 0 until PairingCode.CODE_CHARS) {
                if (i == PairingCode.CODE_CHARS - 1) Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .background(boxColor, MaterialTheme.shapes.small)
                        .border(
                            width = if (isError) 2.dp else 1.dp,
                            color = when {
                                isError -> errorColor
                                i == activeIndex -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = MaterialTheme.shapes.small,
                        ),
                ) {
                    Text(
                        value.getOrNull(i)?.toString().orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun ShowingCode(
    code: String,
    expiresAtEpochMillis: Long,
    copied: Boolean,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val shareText = stringResource(R.string.partner_ceremony_share_text, code)
    // The inviter waits alone with no view of the other phone; a live countdown is the only
    // honest signal of how long the partner still has to act (owner report 2026-10-23).
    var minutesLeft by remember(expiresAtEpochMillis) {
        mutableLongStateOf(minutesUntil(expiresAtEpochMillis))
    }
    LaunchedEffect(expiresAtEpochMillis) {
        while (true) {
            minutesLeft = minutesUntil(expiresAtEpochMillis)
            delay(15_000)
        }
    }
    CeremonyCard {
        Text(
            stringResource(R.string.partner_ceremony_code_overline),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp,
        )
        Text(
            code,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (minutesLeft > 0) {
                pluralStringResource(
                    R.plurals.partner_ceremony_code_validity,
                    count = minutesLeft.toInt(),
                    minutesLeft,
                )
            } else {
                stringResource(R.string.partner_ceremony_code_expiring)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    copyToClipboard(context, code)
                    onCopy()
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.partner_ceremony_copy))
            }
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, shareText)
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.partner_ceremony_share))
            }
        }
        if (copied) {
            Text(
                stringResource(R.string.partner_ceremony_copied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.partner_ceremony_waiting),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onCancel) {
            Text(
                stringResource(R.string.partner_ceremony_cancel),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The SAS check — the one moment the humans defeat a machine-in-the-middle. Digits are the
 * hero (reference: overline + display number). The copy follows the real ceremony order, which
 * is asymmetric: the joiner's phone shows the digits first — prepareJoin derives them from the
 * verified initiator material with no write, so the initiator's digits only appear once the
 * joiner's Confirm has released the biometric signature (the token write the initiator polls
 * for). The joiner reads their digits aloud (Confirm / Stop); the initiator's tap is the actual
 * out-of-band comparison against what they heard ("They match" / "They don't match").
 */
@Composable
private fun CompareSas(
    sas: String,
    mode: CeremonyMode,
    working: Boolean,
    onMatch: () -> Unit,
    onMismatch: () -> Unit,
) {
    val joiner = mode == CeremonyMode.JOIN
    CeremonyCard {
        Text(
            stringResource(R.string.partner_ceremony_sas_overline),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            sas,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 10.sp,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(
                if (joiner) R.string.partner_ceremony_sas_body_joiner
                else R.string.partner_ceremony_sas_body_initiator,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onMatch,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (working) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    if (joiner) R.string.partner_ceremony_sas_confirm
                    else R.string.partner_ceremony_sas_match,
                ),
            )
        }
        // The ceremony is asymmetric: after this tap one phone is waiting on a human action on
        // the other. A bare spinner reads as a hang (owner report 2026-10-23).
        if (working) {
            Text(
                stringResource(
                    if (joiner) R.string.partner_ceremony_waiting_partner_match
                    else R.string.partner_ceremony_waiting_activation,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onMismatch,
            enabled = !working,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (joiner) R.string.partner_ceremony_sas_stop
                    else R.string.partner_ceremony_sas_no_match,
                ),
            )
        }
    }
}

@Composable
private fun PairedTerminal(
    partnerLabel: String?,
    partnerKeyId: String,
    notificationsEnabled: Boolean,
    notificationsRequestable: Boolean,
    onFixNotifications: () -> Unit,
    onDone: () -> Unit,
    alertHealth: PartnerAlertHealth.State = PartnerAlertHealth.State.OK,
    onSendTestAlert: () -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Handshake, contentDescription = null, modifier = Modifier.size(26.dp))
                }
            }
            Text(
                stringResource(R.string.partner_ceremony_paired_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.partner_ceremony_paired_with, partnerLabel ?: shortKeyId(partnerKeyId)),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.partner_ceremony_paired_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            // SOS §8.14: the pairing-success ask (API 33+). Declining never blocks pairing —
            // Done stays available in every state.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsEnabled) {
                Text(
                    stringResource(R.string.partner_ceremony_notifications_body),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onFixNotifications, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (notificationsRequestable) R.string.partner_notifications_enable
                            else R.string.partner_notifications_open_settings,
                        ),
                    )
                }
            } else if (alertHealth == PartnerAlertHealth.State.NOTIFICATIONS_OFF) {
                // API < 33 with app notifications disabled: there is no dialog to raise.
                Text(
                    stringResource(R.string.partner_notifications_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onFixNotifications, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.partner_notifications_open_settings))
                }
            } else if (alertHealth == PartnerAlertHealth.State.CHANNEL_OFF) {
                Text(
                    stringResource(R.string.partner_alerts_channel_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onFixNotifications, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.partner_alerts_channel_off_action))
                }
            } else if (alertHealth == PartnerAlertHealth.State.PROVIDER_UNSUPPORTED) {
                Text(
                    stringResource(R.string.partner_alerts_provider_body),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onSendTestAlert, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.partner_alerts_test_action))
                }
            } else {
                // Plan Step 6: an explicitly user-triggered test alert, at the one moment the
                // user has just been told pairing worked.
                OutlinedButton(onClick = onSendTestAlert, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.partner_alerts_test_action))
                }
            }
        }
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.partner_ceremony_done))
    }
}

@Composable
private fun FailedTerminal(title: String, body: String, onRetry: () -> Unit, onClose: () -> Unit) {
    CeremonyCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.partner_ceremony_retry))
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.partner_ceremony_close))
        }
    }
}

/**
 * #22: maps a failure reason to user copy. registerPairing verdicts arrive as stable reason
 * codes (PairingRegistration surfaces the HttpsError message verbatim); pairing-preparation
 * rejections carry local English developer reasons. Unknown reasons get the neutral
 * "start fresh" body — the connectivity copy is reserved for the genuinely transport-shaped
 * cases: a null reason (the submit call threw) and read errors while polling.
 */
internal fun pairingFailureCopy(reason: String?): Pair<Int, Int> = when {
    reason == null ->
        R.string.partner_ceremony_offline_title to R.string.partner_ceremony_offline_body
    // #22, create/prepare side: the server actively declined (FirebaseFirestoreException
    // PERMISSION_DENIED — a rules denial or an App Check refusal). Truthful neutral copy:
    // there is no connection to check.
    reason == "SERVER_REJECTED" ->
        R.string.partner_ceremony_failed_title to R.string.partner_ceremony_failed_body
    reason == "PAIRING_ACTIVE" ||
        reason == "an active pairing with different material already exists" ->
        R.string.partner_ceremony_failed_active_title to R.string.partner_ceremony_failed_active_body
    reason == "TRANSCRIPT_MISMATCH" || reason == "sessionId mismatch" ->
        R.string.partner_ceremony_failed_stale_title to R.string.partner_ceremony_failed_stale_body
    reason == "SAME_KEY" ->
        R.string.partner_ceremony_failed_same_device_title to R.string.partner_ceremony_failed_same_device_body
    reason == "SELF_PAIRING" ->
        R.string.partner_ceremony_failed_same_account_title to R.string.partner_ceremony_failed_same_account_body
    reason == "RATE_LIMITED" ->
        R.string.partner_ceremony_failed_rate_title to R.string.partner_ceremony_failed_rate_body
    reason == "PROTOCOL_VERSION_UNSUPPORTED" ->
        R.string.partner_ceremony_failed_version_title to R.string.partner_ceremony_failed_version_body
    reason in IDENTITY_REJECTIONS || reason.startsWith("attestation:") ->
        R.string.partner_ceremony_failed_verify_title to R.string.partner_ceremony_failed_verify_body
    else ->
        R.string.partner_ceremony_failed_title to R.string.partner_ceremony_failed_body
}

/**
 * "The other phone's identity couldn't be verified", from either side: the server's
 * attestation guard codes (functions/attestation.js) or the joiner's local
 * EnrollmentFlow.PartnerVerification reasons.
 */
private val IDENTITY_REJECTIONS = setOf(
    "ATTESTATION_MISSING", "KEY_SOFTWARE", "KEY_LEVEL_UNKNOWN", "CHALLENGE_MISMATCH",
    "ORIGIN_NOT_GENERATED", "PURPOSE_MISSING_SIGN", "KEY_CHAIN_MISMATCH", "CHAIN_UNTRUSTED",
    "transcript signature invalid", "malformed certificate chain",
)

/**
 * A throw from the invite/join prepare calls that the SERVER declined (rules or App Check
 * PERMISSION_DENIED) — as opposed to a transport failure, which keeps the offline copy.
 * Everything else, including null, stays transport-shaped.
 */
private fun Throwable?.serverRejectionReason(): String? =
    if (this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
        "SERVER_REJECTED"
    } else {
        null
    }

private fun copyToClipboard(context: Context, code: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("MyExodus invitation code", code))
}

/**
 * §10: TTL is [PairingToken.TTL_MILLIS]; the countdown shows whole minutes, rounded up so
 * "1 minute" never means "expired".
 */
internal fun minutesUntil(
    expiresAtEpochMillis: Long,
    nowEpochMillis: Long = System.currentTimeMillis(),
): Long {
    val remaining = expiresAtEpochMillis - nowEpochMillis
    return if (remaining <= 0) 0 else (remaining + 59_999L) / 60_000L
}

private const val TAG = "MyExodusPairingCeremony"

/**
 * [runCatching] that does not swallow [CancellationException]. Kotlin's `runCatching` catches
 * `Throwable` — including cancellation — so a coroutine cancelled by a step change (cancelling
 * an invitation flips the step back to Entry, cancelling the wait effect) still resumes and
 * runs its failure branch, overwriting the live step with a spurious terminal. Observed on the
 * X6532 (2026-09-28): tap "Cancel invitation" on a showing code → the screen rendered "The
 * invitation ran out". Any suspend call whose result writes UI state goes through this.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
