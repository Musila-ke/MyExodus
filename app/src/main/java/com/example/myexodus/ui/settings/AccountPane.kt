package com.example.myexodus.ui.settings

import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.account.AuthEvent
import com.example.myexodus.account.AuthFailure
import com.example.myexodus.account.AuthProvider
import com.example.myexodus.account.AuthState
import com.example.myexodus.account.CloudAccess
import com.example.myexodus.account.GoogleCredentialOutcome
import com.example.myexodus.account.GuestTransitionState
import com.example.myexodus.account.TransitionFailure
import com.example.myexodus.account.TransitionIntent
import com.example.myexodus.account.TransitionStep
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.PillTone
import com.example.myexodus.ui.StatusPill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Release 1 (account plan §8.3): optional accounts in the Settings pane grammar. Guest first —
 * the pane never implies an account is required. Sign-in errors are generic (no account
 * existence oracle), a collision gets the plan's neutral guidance, and cancelling Google
 * returns silently to this screen with no fallback flow.
 */
@Composable
internal fun AccountPane(
    viewModel: AccountViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transition by viewModel.transition.collectAsStateWithLifecycle()
    val cloudAccess by viewModel.cloudAccess.collectAsStateWithLifecycle()
    val isGuest = state is AuthState.GuestActive
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Hold the event/failure, resolve its copy in composition — a Context.getString from a
    // coroutine would not follow a configuration change (Compose lint).
    var statusEvent by remember { mutableStateOf<AuthEvent?>(null) }
    var statusFailure by remember { mutableStateOf<AuthFailure?>(null) }
    // A sign-in the disclosure dialog interrupted; resumes once the server confirms the switch.
    var pendingSwitch by remember { mutableStateOf<PendingSwitch?>(null) }
    var showSwitchDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event.messageRes() != null) {
                statusEvent = event
                statusFailure = null
            }
        }
    }
    LaunchedEffect(statusEvent, statusFailure) {
        if (statusEvent != null || statusFailure != null) {
            delay(8_000)
            statusEvent = null
            statusFailure = null
        }
    }

    fun runGoogle(reauth: Boolean, switchAccount: Boolean = false) {
        scope.launch {
            when (val result = viewModel.requestGoogleCredential(context)) {
                is GoogleCredentialOutcome.Success ->
                    if (reauth) viewModel.reauthenticateWithGoogle(result.idToken)
                    else viewModel.continueWithGoogle(result.idToken, switchAccount)
                GoogleCredentialOutcome.Cancelled -> Unit
                is GoogleCredentialOutcome.Unavailable -> {
                    statusEvent = null
                    statusFailure = result.failure
                }
            }
        }
    }

    LaunchedEffect(transition) {
        val pending = pendingSwitch ?: return@LaunchedEffect
        when (transition) {
            is GuestTransitionState.Prepared -> {
                pendingSwitch = null
                when (pending) {
                    is PendingSwitch.Email -> viewModel.signInWithEmail(pending.email, pending.password)
                    PendingSwitch.Google -> runGoogle(reauth = false, switchAccount = true)
                }
            }
            GuestTransitionState.Idle -> pendingSwitch = null
            else -> Unit
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BackRow(label = stringResource(R.string.account_settings_title), onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            (statusEvent?.messageRes() ?: statusFailure?.let { failureRes(it) })?.let {
                StatusCard(stringResource(it))
            }

            if (cloudAccess != CloudAccess.READY) {
                CloudBlockedCard(onRestart = viewModel::restartForCloudRecovery)
            }

            TransitionCard(
                state = transition,
                signedIn = state is AuthState.PersistentActive,
                onRetry = viewModel::retryTransition,
                onCancelSwitch = viewModel::cancelTransition,
                onDismiss = viewModel::dismissTransition,
            )

            when (val current = state) {
                AuthState.Initializing -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(28.dp)) }

                is AuthState.Authenticating -> WorkingCard()

                is AuthState.AuthError -> ErrorCard(
                    failure = current.failure,
                    onDismiss = viewModel::dismissTransient,
                    onDiscardPendingWrites = viewModel::discardPendingWritesAndRetry,
                    onRestart = viewModel::restartForCloudRecovery,
                )

                is AuthState.ReauthRequired -> ReauthCard(
                    state = current,
                    viewModel = viewModel,
                    onGoogle = { runGoogle(reauth = true) },
                )

                is AuthState.PersistentActive -> PersistentAccount(
                    state = current,
                    viewModel = viewModel,
                )

                AuthState.SignedOut,
                is AuthState.GuestActive,
                -> GuestAccount(
                    hasGuestUid = isGuest,
                    prepared = transition is GuestTransitionState.Prepared,
                    googleAvailable = viewModel.googleAvailable,
                    viewModel = viewModel,
                    onSignIn = { email, password ->
                        if (transition is GuestTransitionState.Prepared || !isGuest) {
                            viewModel.signInWithEmail(email, password)
                        } else {
                            pendingSwitch = PendingSwitch.Email(email, password)
                            showSwitchDialog = true
                        }
                    },
                    onGoogle = {
                        if (transition is GuestTransitionState.Prepared || !isGuest) {
                            runGoogle(reauth = false, switchAccount = isGuest)
                        } else {
                            pendingSwitch = PendingSwitch.Google
                            showSwitchDialog = true
                        }
                    },
                )
            }
        }
    }

    if (showSwitchDialog) {
        AlertDialog(
            onDismissRequest = {
                showSwitchDialog = false
                pendingSwitch = null
            },
            title = { Text(stringResource(R.string.account_transition_title)) },
            text = { Text(stringResource(R.string.account_transition_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSwitchDialog = false
                        viewModel.startTransition(TransitionIntent.IMPORT)
                    },
                ) { Text(stringResource(R.string.account_transition_import)) }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            showSwitchDialog = false
                            viewModel.startTransition(TransitionIntent.DISCARD)
                        },
                    ) {
                        Text(
                            stringResource(R.string.account_transition_discard),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(
                        onClick = {
                            showSwitchDialog = false
                            pendingSwitch = null
                        },
                    ) { Text(stringResource(R.string.account_transition_stay_guest)) }
                }
            },
        )
    }
}

private sealed interface PendingSwitch {
    data class Email(val email: String, val password: String) : PendingSwitch
    data object Google : PendingSwitch
}

@Composable
private fun GuestAccount(
    hasGuestUid: Boolean,
    prepared: Boolean,
    googleAvailable: Boolean,
    viewModel: AccountViewModel,
    onSignIn: (String, String) -> Unit,
    onGoogle: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var signInMode by rememberSaveable { mutableStateOf(false) }
    var emailInvalid by remember { mutableStateOf(false) }
    var passwordInvalid by remember { mutableStateOf(false) }

    fun validInput(): Boolean {
        emailInvalid = !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
        passwordInvalid = password.length < MIN_PASSWORD_LENGTH
        return !emailInvalid && !passwordInvalid
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.account_guest_intro_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.account_guest_intro_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (googleAvailable) {
        OutlinedButton(
            onClick = onGoogle,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.account_google)) }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; emailInvalid = false },
                label = { Text(stringResource(R.string.account_email_label)) },
                singleLine = true,
                isError = emailInvalid,
                supportingText = if (emailInvalid) {
                    { Text(stringResource(R.string.account_email_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; passwordInvalid = false },
                label = { Text(stringResource(R.string.account_password_label)) },
                singleLine = true,
                isError = passwordInvalid,
                supportingText = if (passwordInvalid) {
                    { Text(stringResource(R.string.account_password_short)) }
                } else {
                    null
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (!validInput()) return@Button
                    if (signInMode) {
                        onSignIn(email, password)
                    } else {
                        viewModel.createAccount(email, password)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (signInMode) R.string.account_sign_in else R.string.account_create,
                    ),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { signInMode = !signInMode }) {
                    Text(
                        stringResource(
                            if (signInMode) R.string.account_new_here
                            else R.string.account_have_account,
                        ),
                    )
                }
                if (signInMode) {
                    TextButton(
                        onClick = {
                            if (Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                                viewModel.sendPasswordReset(email)
                            } else {
                                emailInvalid = true
                            }
                        },
                    ) { Text(stringResource(R.string.account_forgot)) }
                }
            }
            if (signInMode && hasGuestUid && !prepared) {
                // Plan §8.4: the disclosure (import vs discard, pair effects) comes before the
                // server-side preparation; the button opens it rather than signing in directly.
                Text(
                    stringResource(R.string.account_transition_guest_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PersistentAccount(
    state: AuthState.PersistentActive,
    viewModel: AccountViewModel,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.email ?: stringResource(R.string.account_google_account),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (AuthProvider.PASSWORD in state.providers) {
                    StatusPill(
                        text = stringResource(
                            if (state.emailVerified) R.string.account_verified
                            else R.string.account_not_verified,
                        ),
                        tone = if (state.emailVerified) PillTone.POSITIVE else PillTone.NEGATIVE,
                    )
                }
            }
            Text(
                stringResource(
                    if (AuthProvider.GOOGLE in state.providers && AuthProvider.PASSWORD !in state.providers) {
                        R.string.account_provider_google
                    } else {
                        R.string.account_provider_password
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (AuthProvider.PASSWORD in state.providers && !state.emailVerified) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.account_verify_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.account_verify_body, state.email.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::sendVerificationEmail) {
                        Text(stringResource(R.string.account_verify_resend))
                    }
                    Button(onClick = viewModel::refreshVerification) {
                        Text(stringResource(R.string.account_verify_confirm))
                    }
                }
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.account_sign_out_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = viewModel::signOut) {
                Text(stringResource(R.string.account_sign_out))
            }
        }
    }
}

@Composable
private fun ReauthCard(
    state: AuthState.ReauthRequired,
    viewModel: AccountViewModel,
    onGoogle: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.account_reauth_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.account_reauth_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.email != null) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.account_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { if (password.isNotEmpty()) viewModel.reauthenticateWithEmail(password) },
                    enabled = password.isNotEmpty(),
                ) { Text(stringResource(R.string.account_reauth_confirm)) }
            }
            if (viewModel.googleAvailable) {
                OutlinedButton(onClick = onGoogle) {
                    Text(stringResource(R.string.account_reauth_google))
                }
            }
            TextButton(onClick = viewModel::dismissTransient) {
                Text(stringResource(R.string.account_reauth_cancel))
            }
        }
    }
}

@Composable
private fun WorkingCard() = WorkingCard(stringResource(R.string.account_working))

@Composable
private fun WorkingCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Account plan §8.4: the switch's own status, above the identity cards. Pairs are untouched
 * until finalization, so the guest copy only ever promises what the server will do.
 */
@Composable
private fun TransitionCard(
    state: GuestTransitionState,
    signedIn: Boolean,
    onRetry: () -> Unit,
    onCancelSwitch: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        GuestTransitionState.Idle -> Unit
        GuestTransitionState.Preparing ->
            WorkingCard(stringResource(R.string.account_transition_preparing))
        GuestTransitionState.Cancelling ->
            WorkingCard(stringResource(R.string.account_transition_cancelling))
        is GuestTransitionState.Finalizing ->
            WorkingCard(stringResource(R.string.account_transition_finalizing))

        is GuestTransitionState.Prepared -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (signedIn) R.string.account_transition_finish_title
                        else R.string.account_transition_prepared_title,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        if (signedIn) R.string.account_transition_finish_body
                        else R.string.account_transition_prepared_body,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (signedIn) {
                    OutlinedButton(onClick = onRetry) {
                        Text(stringResource(R.string.account_transition_retry))
                    }
                } else {
                    TextButton(onClick = onCancelSwitch) {
                        Text(stringResource(R.string.account_transition_stay_guest))
                    }
                }
            }
        }

        is GuestTransitionState.Completed -> Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.account_transition_completed_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    pluralStringResource(
                        R.plurals.account_transition_completed_pairs,
                        state.terminatedPairs,
                        state.terminatedPairs,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_dismiss)) }
            }
        }

        is GuestTransitionState.Failed -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(transitionFailureRes(state.failure)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.step != TransitionStep.FINALIZE) {
                        TextButton(onClick = onCancelSwitch) {
                            Text(stringResource(R.string.account_transition_stay_guest))
                        }
                    }
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.account_transition_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    failure: AuthFailure,
    onDismiss: () -> Unit,
    onDiscardPendingWrites: () -> Unit,
    onRestart: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(failureRes(failure)), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (failure) {
                    // §8.7: the only way past a write flush that cannot complete except waiting.
                    AuthFailure.PENDING_WRITES -> TextButton(onClick = onDiscardPendingWrites) {
                        Text(stringResource(R.string.account_pending_writes_discard))
                    }
                    // §9: a failed one-time purge is not retryable in-process.
                    AuthFailure.CLOUD_MIGRATION_PENDING -> TextButton(onClick = onRestart) {
                        Text(stringResource(R.string.account_restart))
                    }
                    else -> Unit
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_dismiss)) }
            }
        }
    }
}

/** Stands whenever this launch is cloud-blocked, even with no auth failure on screen. */
@Composable
private fun CloudBlockedCard(onRestart: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.account_cloud_blocked), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRestart) { Text(stringResource(R.string.account_restart)) }
        }
    }
}

@Composable
private fun StatusCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

private const val MIN_PASSWORD_LENGTH = 6

/** The events that carry user-visible copy; the rest are state changes the pane shows already. */
private fun AuthEvent.messageRes(): Int? = when (this) {
    AuthEvent.EmailVerificationSent -> R.string.account_verification_sent
    AuthEvent.PasswordResetRequested -> R.string.account_reset_sent
    AuthEvent.SignedOut -> R.string.account_signed_out
    AuthEvent.Collision -> R.string.account_collision
    AuthEvent.EmailVerified, AuthEvent.Reauthenticated, is AuthEvent.SignedIn -> null
}

/** Local-only: Firebase enforces its own policy; this is inline feedback, not the authority. */
internal fun failureRes(failure: AuthFailure): Int = when (failure) {
    AuthFailure.INVALID_CREDENTIALS -> R.string.account_error_invalid_credentials
    AuthFailure.CREDENTIAL_ALREADY_REGISTERED -> R.string.account_collision
    AuthFailure.WEAK_PASSWORD -> R.string.account_error_weak_password
    AuthFailure.INVALID_EMAIL -> R.string.account_error_invalid_email
    AuthFailure.EMAIL_NOT_VERIFIED -> R.string.account_error_transition
    AuthFailure.NETWORK -> R.string.account_error_network
    AuthFailure.TOO_MANY_REQUESTS -> R.string.account_error_too_many
    AuthFailure.PROVIDER_UNAVAILABLE -> R.string.account_error_provider_unavailable
    AuthFailure.REQUIRES_RECENT_LOGIN -> R.string.account_error_requires_recent
    AuthFailure.ALREADY_SIGNED_IN -> R.string.account_error_already_signed_in
    AuthFailure.NOT_SIGNED_IN -> R.string.account_error_not_signed_in
    AuthFailure.TRANSITION_REQUIRED -> R.string.account_error_transition
    AuthFailure.PENDING_WRITES -> R.string.account_error_pending_writes
    AuthFailure.CLOUD_MIGRATION_PENDING -> R.string.account_error_cloud_pending
    AuthFailure.TRANSITION_IN_PROGRESS -> R.string.account_error_transition_in_progress
    AuthFailure.SECURITY_CHECK_FAILED -> R.string.account_error_security
    AuthFailure.UNKNOWN -> R.string.account_error_generic
}

/** §8.4 failure copy: what happened and what the user can do about it. */
internal fun transitionFailureRes(failure: TransitionFailure): Int = when (failure) {
    TransitionFailure.NETWORK -> R.string.account_transition_error_network
    TransitionFailure.DENIED,
    TransitionFailure.INVALID_CAPABILITY,
    TransitionFailure.NOT_OWNER,
    TransitionFailure.SECURITY_CHECK_FAILED,
    -> R.string.account_transition_error_verify
    TransitionFailure.SIGN_IN_REQUIRED -> R.string.account_error_not_signed_in
    TransitionFailure.REAUTH_REQUIRED -> R.string.account_error_requires_recent
    TransitionFailure.NOT_ENABLED -> R.string.account_transition_error_unavailable
    TransitionFailure.RATE_LIMITED -> R.string.account_transition_error_rate_limited
    TransitionFailure.CANCELLED,
    TransitionFailure.FINALIZED,
    -> R.string.account_transition_error_cancelled
    TransitionFailure.OWNED_BY_ANOTHER_ACCOUNT -> R.string.account_transition_error_owner
    TransitionFailure.PROFILE_NOT_ACTIVE -> R.string.account_transition_error_profile
    TransitionFailure.ACCOUNT_DELETING -> R.string.account_transition_error_deleting
    TransitionFailure.INTENT_MISMATCH -> R.string.account_transition_error_intent
    TransitionFailure.JOURNAL_UNREADABLE -> R.string.account_transition_error_journal
    TransitionFailure.ALREADY_PREPARED,
    TransitionFailure.NOT_FOUND,
    TransitionFailure.ANONYMOUS_ONLY,
    TransitionFailure.PERSISTENT_ACCOUNT_REQUIRED,
    TransitionFailure.CONFLICT,
    TransitionFailure.UNKNOWN,
    -> R.string.account_transition_error_generic
}
