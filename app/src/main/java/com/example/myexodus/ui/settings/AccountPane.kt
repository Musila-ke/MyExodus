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
import com.example.myexodus.account.GoogleCredentialOutcome
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Hold the event/failure, resolve its copy in composition — a Context.getString from a
    // coroutine would not follow a configuration change (Compose lint).
    var statusEvent by remember { mutableStateOf<AuthEvent?>(null) }
    var statusFailure by remember { mutableStateOf<AuthFailure?>(null) }

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

    fun runGoogle(reauth: Boolean) {
        scope.launch {
            when (val result = viewModel.requestGoogleCredential(context)) {
                is GoogleCredentialOutcome.Success ->
                    if (reauth) viewModel.reauthenticateWithGoogle(result.idToken)
                    else viewModel.continueWithGoogle(result.idToken)
                GoogleCredentialOutcome.Cancelled -> Unit
                is GoogleCredentialOutcome.Unavailable -> {
                    statusEvent = null
                    statusFailure = result.failure
                }
            }
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

            when (val current = state) {
                AuthState.Initializing -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(28.dp)) }

                is AuthState.Authenticating -> WorkingCard()

                is AuthState.AuthError -> ErrorCard(current.failure, onDismiss = viewModel::dismissTransient)

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
                    hasGuestUid = state is AuthState.GuestActive,
                    googleAvailable = viewModel.googleAvailable,
                    viewModel = viewModel,
                    onGoogle = { runGoogle(reauth = false) },
                )
            }
        }
    }
}

@Composable
private fun GuestAccount(
    hasGuestUid: Boolean,
    googleAvailable: Boolean,
    viewModel: AccountViewModel,
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
                        viewModel.signInWithEmail(email, password)
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
            if (signInMode && hasGuestUid) {
                // A guest cannot switch identity directly: the transition flow (plan §6.2)
                // prepares a server-side intent before any pair is terminated.
                Text(
                    stringResource(R.string.account_error_transition),
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
private fun WorkingCard() {
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
            Text(stringResource(R.string.account_working), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorCard(failure: AuthFailure, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(failureRes(failure)), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_dismiss)) }
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
    AuthFailure.SECURITY_CHECK_FAILED -> R.string.account_error_security
    AuthFailure.UNKNOWN -> R.string.account_error_generic
}
