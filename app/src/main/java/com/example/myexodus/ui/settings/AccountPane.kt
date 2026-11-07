package com.example.myexodus.ui.settings

import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.account.AuthEvent
import com.example.myexodus.account.AuthFailure
import com.example.myexodus.account.AuthProvider
import com.example.myexodus.account.AuthState
import com.example.myexodus.account.CloudAccess
import com.example.myexodus.account.DeletionFailure
import com.example.myexodus.account.DeletionPhrases
import com.example.myexodus.account.DeletionState
import com.example.myexodus.account.GoogleCredentialOutcome
import com.example.myexodus.account.LocalRemovalInfo
import com.example.myexodus.account.GuestImportState
import com.example.myexodus.account.GuestTransitionState
import com.example.myexodus.account.ImportFailure
import com.example.myexodus.account.RecoveryFailure
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
    onOpenBackupKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transition by viewModel.transition.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val recovery by viewModel.recovery.collectAsStateWithLifecycle()
    val cloudAccess by viewModel.cloudAccess.collectAsStateWithLifecycle()
    val deletion by viewModel.deletionState.collectAsStateWithLifecycle()
    val removal by viewModel.removal.collectAsStateWithLifecycle()
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
    var showDeleteQuarantine by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    var showDeleteGuest by remember { mutableStateOf(false) }
    var showRemoveLocal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // The app-level collector covers a closed pane; this covers a pane opened after the
        // transition completed while the collector's refresh found nothing bound yet.
        viewModel.refreshImport()
    }
    LaunchedEffect(state) {
        // A sign-in that completes while the pane is open can surface a retained scope.
        viewModel.refreshRecovery()
        // §6.9.3: the outbox/backup disclosure belongs to whichever identity is showing.
        viewModel.refreshRemovalInfo()
    }
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

    fun runGoogleDelete(phrase: String, deleteDeviceData: Boolean) {
        scope.launch {
            when (val result = viewModel.requestGoogleCredential(context)) {
                is GoogleCredentialOutcome.Success ->
                    viewModel.deleteAccountWithGoogle(phrase, result.idToken, deleteDeviceData)
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

            DeletionCard(
                state = deletion,
                onSignOut = { viewModel.finishDeletionSignOut(false) },
                onSignOutDiscarding = { viewModel.finishDeletionSignOut(true) },
                onDiscardAndRetry = viewModel::discardAndRemoveLocalData,
                onDismiss = viewModel::dismissDeletion,
            )

            TransitionCard(
                state = transition,
                signedIn = state is AuthState.PersistentActive,
                onRetry = viewModel::retryTransition,
                onCancelSwitch = viewModel::cancelTransition,
                onDismiss = viewModel::dismissTransition,
            )

            ImportCard(
                state = importState,
                onPreview = viewModel::previewImport,
                onConfirm = viewModel::confirmImport,
                onDelete = { showDeleteQuarantine = true },
                onRetry = viewModel::retryImport,
                onDismiss = viewModel::dismissImport,
                onOpenBackupKey = onOpenBackupKey,
                onMergeLocally = viewModel::mergeImportLocally,
                onRestoreAndMerge = {
                    viewModel.preserveLocalThenRestore(onOpenBackupKey)
                },
            )

            RecoveryCard(state = recovery, onImport = viewModel::stageRecoveryImport)

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
                    onOpenBackupKey = onOpenBackupKey,
                    onDeleteAccount = { showDeleteAccount = true },
                    onRemoveLocal = { showRemoveLocal = true },
                )

                AuthState.SignedOut,
                is AuthState.GuestActive,
                -> GuestAccount(
                    hasGuestUid = isGuest,
                    prepared = transition is GuestTransitionState.Prepared,
                    googleAvailable = viewModel.googleAvailable,
                    viewModel = viewModel,
                    retainedLocal = state is AuthState.SignedOut && removal?.retainedScope == true,
                    onDeleteGuest = { showDeleteGuest = true },
                    onRemoveLocal = { showRemoveLocal = true },
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

    if (showDeleteQuarantine) {
        AlertDialog(
            onDismissRequest = { showDeleteQuarantine = false },
            title = { Text(stringResource(R.string.account_import_delete_title)) },
            text = { Text(stringResource(R.string.account_import_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteQuarantine = false
                        viewModel.deleteQuarantine()
                    },
                ) {
                    Text(
                        stringResource(R.string.account_import_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteQuarantine = false }) {
                    Text(stringResource(R.string.account_import_cancel))
                }
            },
        )
    }

    if (showDeleteAccount) {
        DeleteAccountDialog(
            providers = (state as? AuthState.PersistentActive)?.providers.orEmpty(),
            googleAvailable = viewModel.googleAvailable,
            onDismiss = { showDeleteAccount = false },
            onPassword = { phrase, password, deleteDeviceData ->
                showDeleteAccount = false
                viewModel.deleteAccount(phrase, password, deleteDeviceData)
            },
            onGoogle = { phrase, deleteDeviceData ->
                showDeleteAccount = false
                runGoogleDelete(phrase, deleteDeviceData)
            },
        )
    }

    if (showDeleteGuest) {
        GuestDeleteDialog(
            onDismiss = { showDeleteGuest = false },
            onDelete = { phrase ->
                showDeleteGuest = false
                viewModel.deleteGuestData(phrase)
            },
        )
    }

    if (showRemoveLocal) {
        val retained = state is AuthState.SignedOut
        RemoveLocalDialog(
            info = removal,
            retained = retained,
            onDismiss = { showRemoveLocal = false },
            onConfirm = {
                showRemoveLocal = false
                if (retained) viewModel.removeRetainedLocalData() else viewModel.removeLocalData()
            },
        )
    }
}

/** §6.8.1: the typed phrase plus an actual provider reauthentication before the request. */
@Composable
private fun DeleteAccountDialog(
    providers: Set<AuthProvider>,
    googleAvailable: Boolean,
    onDismiss: () -> Unit,
    onPassword: (String, String, Boolean) -> Unit,
    onGoogle: (String, Boolean) -> Unit,
) {
    var phrase by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var deleteDeviceData by rememberSaveable { mutableStateOf(false) }
    val phraseOk = phrase == DeletionPhrases.ACCOUNT
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_delete_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.account_delete_dialog_body))
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text(stringResource(R.string.account_delete_phrase_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val deviceDataLabel = stringResource(R.string.account_delete_device_data_label)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = deleteDeviceData,
                        onCheckedChange = { deleteDeviceData = it },
                        modifier = Modifier.semantics { contentDescription = deviceDataLabel },
                    )
                    Column {
                        Text(deviceDataLabel)
                        Text(
                            stringResource(R.string.account_delete_device_data_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (AuthProvider.PASSWORD in providers) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.account_password_label)) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector =
                                        if (passwordVisible) Icons.Outlined.VisibilityOff
                                        else Icons.Outlined.Visibility,
                                    contentDescription = stringResource(
                                        if (passwordVisible) R.string.account_password_hide
                                        else R.string.account_password_show,
                                    ),
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation =
                            if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { onPassword(phrase, password, deleteDeviceData) },
                        enabled = phraseOk && password.isNotEmpty(),
                    ) {
                        Text(
                            stringResource(R.string.account_delete_confirm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (googleAvailable) {
                    TextButton(
                        onClick = { onGoogle(phrase, deleteDeviceData) },
                        enabled = phraseOk,
                    ) { Text(stringResource(R.string.account_delete_google)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_reauth_cancel)) }
        },
    )
}

/** §6.8.9: guests confirm twice before their anonymous data is deleted. */
@Composable
private fun GuestDeleteDialog(onDismiss: () -> Unit, onDelete: (String) -> Unit) {
    var phrase by remember { mutableStateOf("") }
    var secondStep by remember { mutableStateOf(false) }
    val phraseOk = phrase == DeletionPhrases.GUEST
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (secondStep) R.string.account_guest_delete_last_title
                    else R.string.account_guest_delete_dialog_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        if (secondStep) R.string.account_guest_delete_last_body
                        else R.string.account_guest_delete_dialog_body,
                    ),
                )
                if (!secondStep) {
                    OutlinedTextField(
                        value = phrase,
                        onValueChange = { phrase = it },
                        label = { Text(stringResource(R.string.account_guest_delete_phrase_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (secondStep) onDelete(phrase) else secondStep = true
                },
                enabled = secondStep || phraseOk,
            ) {
                Text(
                    stringResource(
                        if (secondStep) R.string.account_guest_delete_confirm
                        else R.string.account_guest_delete_continue,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { if (secondStep) secondStep = false else onDismiss() }) {
                Text(
                    stringResource(
                        if (secondStep) R.string.account_guest_delete_back
                        else R.string.account_reauth_cancel,
                    ),
                )
            }
        },
    )
}

/** §6.9.3: names what is on this device before it is erased, including what cannot come back. */
@Composable
private fun RemoveLocalDialog(
    info: LocalRemovalInfo?,
    retained: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_local_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.account_local_dialog_body))
                when (info?.pendingEvents) {
                    true -> Text(stringResource(R.string.account_local_pending_yes))
                    false -> Text(stringResource(R.string.account_local_pending_no))
                    null -> Text(stringResource(R.string.account_local_pending_unknown))
                }
                val revision = info?.backupRevision
                when {
                    info == null || !info.backupChecked ->
                        Text(stringResource(R.string.account_local_backup_unknown))
                    revision != null ->
                        Text(stringResource(R.string.account_local_backup_revision, revision))
                    else -> Text(stringResource(R.string.account_local_backup_none))
                }
                if (retained) Text(stringResource(R.string.account_local_retained_note))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (retained) R.string.account_local_retained_confirm
                        else R.string.account_local_confirm,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_reauth_cancel)) }
        },
    )
}

private sealed interface PendingSwitch {
    data class Email(val email: String, val password: String) : PendingSwitch
    data object Google : PendingSwitch
}

/**
 * The one auth surface: create/sign-in switch, email + password (+ confirmation), and Google
 * when it is compiled in. Shared by Settings → Account and first-run onboarding so validation
 * and copy can never drift between the two entry points.
 */
@Composable
internal fun AccountAuthForm(
    hasGuestUid: Boolean,
    prepared: Boolean,
    googleAvailable: Boolean,
    viewModel: AccountViewModel,
    onSignIn: (String, String) -> Unit,
    onGoogle: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var signInMode by rememberSaveable { mutableStateOf(false) }
    var emailInvalid by remember { mutableStateOf(false) }
    var passwordErrorRes by remember { mutableIntStateOf(0) }
    var confirmMismatch by remember { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmVisible by rememberSaveable { mutableStateOf(false) }

    fun submit() {
        // Owner directive 2026-11-01: fields are typed-once-wrong-twice risks — trim the email,
        // reject a spaces-only password, and require the confirmation to match on create.
        val cleanEmail = email.trim()
        emailInvalid = !Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()
        passwordErrorRes = when {
            password.isEmpty() || password.length < MIN_PASSWORD_LENGTH ->
                R.string.account_password_short
            password.isBlank() -> R.string.account_password_blank
            else -> 0
        }
        confirmMismatch = !signInMode && confirmPassword != password
        if (emailInvalid || passwordErrorRes != 0 || confirmMismatch) return
        if (signInMode) onSignIn(cleanEmail, password) else viewModel.createAccount(cleanEmail, password)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector =
                            if (signInMode) Icons.Outlined.Login else Icons.Outlined.PersonAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            if (signInMode) R.string.account_auth_sign_in_title
                            else R.string.account_auth_create_title,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            if (signInMode) R.string.account_auth_sign_in_body
                            else R.string.account_auth_create_body,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // The house mode-switch grammar (FilterChip pairs, as in ScheduleFields), so the
            // two ways into an account are visible choices instead of a link below the fold.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = signInMode,
                    onClick = {
                        signInMode = true
                        emailInvalid = false
                        passwordErrorRes = 0
                        confirmMismatch = false
                    },
                    label = { Text(stringResource(R.string.account_sign_in)) },
                )
                FilterChip(
                    selected = !signInMode,
                    onClick = {
                        signInMode = false
                        emailInvalid = false
                        passwordErrorRes = 0
                        confirmMismatch = false
                    },
                    label = { Text(stringResource(R.string.account_create)) },
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; emailInvalid = false },
                label = { Text(stringResource(R.string.account_email_label)) },
                leadingIcon = { Icon(Icons.Outlined.MailOutline, contentDescription = null) },
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
                onValueChange = { password = it; passwordErrorRes = 0 },
                label = { Text(stringResource(R.string.account_password_label)) },
                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector =
                                if (passwordVisible) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (passwordVisible) R.string.account_password_hide
                                else R.string.account_password_show,
                            ),
                        )
                    }
                },
                singleLine = true,
                isError = passwordErrorRes != 0,
                supportingText = if (passwordErrorRes != 0) {
                    { Text(stringResource(passwordErrorRes)) }
                } else {
                    null
                },
                visualTransformation =
                    if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (signInMode) ImeAction.Done else ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!signInMode) {
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; confirmMismatch = false },
                    label = { Text(stringResource(R.string.account_password_confirm_label)) },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { confirmVisible = !confirmVisible }) {
                            Icon(
                                imageVector =
                                    if (confirmVisible) Icons.Outlined.VisibilityOff
                                    else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (confirmVisible) R.string.account_password_hide
                                    else R.string.account_password_show,
                                ),
                            )
                        }
                    },
                    singleLine = true,
                    isError = confirmMismatch,
                    supportingText = if (confirmMismatch) {
                        { Text(stringResource(R.string.account_password_mismatch)) }
                    } else {
                        null
                    },
                    visualTransformation =
                        if (confirmVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = ::submit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (signInMode) R.string.account_sign_in else R.string.account_create,
                    ),
                )
            }
            if (signInMode) {
                TextButton(
                    onClick = {
                        if (Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                            viewModel.sendPasswordReset(email.trim())
                        } else {
                            emailInvalid = true
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.account_forgot)) }
            }
            // Owner directive 2026-11-01: an alternative sign-in route belongs inside the one
            // auth surface, not floating between two cards as it used to.
            if (googleAvailable) {
                HorizontalDivider()
                OutlinedButton(
                    onClick = onGoogle,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.account_google)) }
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
private fun GuestAccount(
    hasGuestUid: Boolean,
    prepared: Boolean,
    googleAvailable: Boolean,
    viewModel: AccountViewModel,
    retainedLocal: Boolean,
    onDeleteGuest: () -> Unit,
    onRemoveLocal: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onGoogle: () -> Unit,
) {
    AccountCard(
        icon = Icons.Outlined.PersonOutline,
        title = stringResource(R.string.account_guest_intro_title),
        body = stringResource(R.string.account_guest_intro_body),
    )

    AccountAuthForm(
        hasGuestUid = hasGuestUid,
        prepared = prepared,
        googleAvailable = googleAvailable,
        viewModel = viewModel,
        onSignIn = onSignIn,
        onGoogle = onGoogle,
    )

    if (retainedLocal) {
        AccountCard(
            icon = Icons.Outlined.Folder,
            title = stringResource(R.string.account_local_title),
            body = stringResource(R.string.account_local_retained_body),
        ) {
            OutlinedButton(onClick = onRemoveLocal) {
                Text(stringResource(R.string.account_local_action))
            }
        }
    }

    if (hasGuestUid) {
        AccountCard(
            icon = Icons.Outlined.DeleteForever,
            title = stringResource(R.string.account_guest_delete_title),
            body = stringResource(R.string.account_guest_delete_body),
            destructive = true,
        ) {
            OutlinedButton(onClick = onDeleteGuest) {
                Text(
                    stringResource(R.string.account_guest_delete_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PersistentAccount(
    state: AuthState.PersistentActive,
    viewModel: AccountViewModel,
    onOpenBackupKey: () -> Unit,
    onDeleteAccount: () -> Unit,
    onRemoveLocal: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    state.email ?: stringResource(R.string.account_google_account),
                    style = MaterialTheme.typography.titleMedium,
                    // The email is the identity the page exists to show; one line cut it to
                    // "kevinmainigi4…" next to the verified pill (device-caught 2026-11-01).
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        if (AuthProvider.GOOGLE in state.providers &&
                            AuthProvider.PASSWORD !in state.providers
                        ) {
                            R.string.account_provider_google
                        } else {
                            R.string.account_provider_password
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    }

    if (AuthProvider.PASSWORD in state.providers && !state.emailVerified) {
        AccountCard(
            icon = Icons.Outlined.MarkEmailRead,
            title = stringResource(R.string.account_verify_title),
            body = stringResource(R.string.account_verify_body, state.email.orEmpty()),
        ) {
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

    // §6.4: encrypted backup belongs to the account that owns it, so its entry point lives here.
    AccountCard(
        icon = Icons.Outlined.Key,
        title = stringResource(R.string.backup_key_title),
        body = stringResource(R.string.backup_key_account_body),
    ) {
        OutlinedButton(onClick = onOpenBackupKey) {
            Text(stringResource(R.string.backup_key_account_open))
        }
    }

    AccountCard(
        icon = Icons.AutoMirrored.Outlined.Logout,
        title = stringResource(R.string.account_sign_out),
        body = stringResource(R.string.account_sign_out_body),
    ) {
        OutlinedButton(onClick = viewModel::signOut) {
            Text(stringResource(R.string.account_sign_out))
        }
    }

    // §6.9.3: local removal never touches the account, its backups or its partner connections.
    AccountCard(
        icon = Icons.Outlined.Folder,
        title = stringResource(R.string.account_local_title),
        body = stringResource(R.string.account_local_body),
    ) {
        OutlinedButton(onClick = onRemoveLocal) {
            Text(stringResource(R.string.account_local_action))
        }
    }

    // §6.8.1: the irreversible action, last and set apart, with its consequences named first.
    AccountCard(
        icon = Icons.Outlined.DeleteForever,
        title = stringResource(R.string.account_delete_title),
        body = stringResource(R.string.account_delete_body),
        destructive = true,
    ) {
        OutlinedButton(onClick = onDeleteAccount) {
            Text(
                stringResource(R.string.account_delete_action),
                color = MaterialTheme.colorScheme.error,
            )
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
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
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
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector =
                                    if (passwordVisible) Icons.Outlined.VisibilityOff
                                    else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.account_password_hide
                                    else R.string.account_password_show,
                                ),
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation =
                        if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
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
internal fun WorkingCard(text: String) {
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
 * The pane's one card grammar: an icon disc naming the subject, the title and body, then the
 * actions. Owner directive 2026-11-01 — the pane was a stack of identical plain-text cards,
 * so nothing told one block from another before reading it.
 */
@Composable
private fun AccountCard(
    icon: ImageVector,
    title: String,
    body: String,
    destructive: Boolean = false,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (destructive) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            actions()
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

/**
 * Account plan §8.4: the post-switch import. The counts are a dry run; nothing is written
 * until Confirm, and the seven-day replay window is disclosed before the operation.
 */
@Composable
private fun ImportCard(
    state: GuestImportState,
    onPreview: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenBackupKey: () -> Unit,
    onMergeLocally: () -> Unit,
    onRestoreAndMerge: () -> Unit,
) {
    val context = LocalContext.current
    fun day(ms: Long): String =
        android.text.format.DateFormat.getMediumDateFormat(context)
            .format(java.util.Date(ms))

    when (state) {
        GuestImportState.Idle -> Unit
        GuestImportState.Importing ->
            WorkingCard(stringResource(R.string.account_import_working))

        is GuestImportState.Confirming -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.account_import_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.account_import_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        R.string.account_import_counts,
                        state.summary.inserted,
                        state.summary.deduplicated,
                        state.summary.updated,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.conflicts.isNotEmpty()) {
                    Text(
                        pluralStringResource(
                            R.plurals.account_import_conflicts,
                            state.conflicts.sumOf { it.count },
                            state.conflicts.sumOf { it.count },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.account_import_retention, day(state.purgeAtMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.portablePreferencesAvailable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            onPreview(!state.portablePreferences)
                        },
                    ) {
                        Checkbox(
                            checked = state.portablePreferences,
                            onCheckedChange = null,
                        )
                        Text(
                            stringResource(R.string.account_import_portable),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirm, enabled = state.conflicts.isEmpty()) {
                        Text(stringResource(R.string.account_import_confirm))
                    }
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(R.string.account_import_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        is GuestImportState.PendingRestore -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.account_import_restore_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.account_import_restore_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.account_import_retention, day(state.purgeAtMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenBackupKey) {
                        Text(stringResource(R.string.account_import_restore_action))
                    }
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.account_import_check_again))
                    }
                }
            }
        }

        is GuestImportState.BaseConflict -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.account_import_conflict_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.account_import_conflict_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.account_import_retention, day(state.purgeAtMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onRestoreAndMerge) {
                    Text(stringResource(R.string.account_import_conflict_restore))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onMergeLocally) {
                        Text(stringResource(R.string.account_import_conflict_merge))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.account_dismiss))
                    }
                }
                Text(
                    stringResource(R.string.account_import_conflict_upload_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is GuestImportState.Imported -> Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.account_import_done_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    pluralStringResource(
                        R.plurals.account_import_done_body,
                        state.summary.inserted,
                        state.summary.inserted,
                        state.summary.deduplicated,
                        day(state.purgeAtMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.account_import_delete))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.account_dismiss))
                    }
                }
            }
        }

        is GuestImportState.Failed -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(importFailureRes(state.failure)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // §9: an unreadable copy is preserved, not retried and never deleted here.
                    if (state.failure != ImportFailure.UNREADABLE) {
                        Button(onClick = onRetry) {
                            Text(stringResource(R.string.account_import_retry))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.account_dismiss))
                    }
                }
            }
        }
    }
}

/**
 * ADR #239: data retained under an identity this account never imported. Staging hands the
 * snapshot to the same dry run, atomic apply and seven-day undo as the guest import.
 */
@Composable
private fun RecoveryCard(state: RecoveryUiState, onImport: () -> Unit) {
    if (!state.offered) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.account_recovery_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.account_recovery_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.failure?.let {
                Text(
                    stringResource(
                        when (it) {
                            RecoveryFailure.UNREADABLE -> R.string.account_recovery_error_unreadable
                            RecoveryFailure.STORE_UNAVAILABLE -> R.string.account_import_error_store
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = onImport) {
                Text(stringResource(R.string.account_recovery_action))
            }
        }
    }
}

/** §8.4/§9: what happened and what the user can do; no storage internals. */
internal fun importFailureRes(failure: ImportFailure): Int = when (failure) {
    ImportFailure.UNREADABLE -> R.string.account_import_error_unreadable
    ImportFailure.STORE_UNAVAILABLE -> R.string.account_import_error_store
    ImportFailure.IMPORT_FAILED -> R.string.account_import_error_generic
    ImportFailure.CLOUD_CONFLICT -> R.string.account_import_error_cloud_conflict
    ImportFailure.CLOUD_UNAVAILABLE -> R.string.account_import_error_cloud_unavailable
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

/** §6.8/§6.9.3: the request's outcome; the blocked variant keeps the user moving after §8.7. */
@Composable
private fun DeletionCard(
    state: DeletionState,
    onSignOut: () -> Unit,
    onSignOutDiscarding: () -> Unit,
    onDiscardAndRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        DeletionState.Idle -> Unit
        DeletionState.Working ->
            WorkingCard(stringResource(R.string.account_deletion_working))

        is DeletionState.Accepted -> Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (state.signOutBlocked) R.string.account_deletion_blocked_title
                        else R.string.account_deletion_accepted_title,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        if (state.signOutBlocked) R.string.account_deletion_blocked_body
                        else R.string.account_deletion_accepted_body,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = if (state.signOutBlocked) onSignOutDiscarding else onSignOut,
                ) {
                    Text(
                        stringResource(
                            if (state.signOutBlocked) R.string.account_deletion_sign_out_discard
                            else R.string.account_deletion_sign_out,
                        ),
                    )
                }
            }
        }

        is DeletionState.Failed -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(deletionFailureRes(state.failure)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.failure == DeletionFailure.PENDING_WRITES) {
                        TextButton(onClick = onDiscardAndRetry) {
                            Text(stringResource(R.string.account_pending_writes_discard))
                        }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_dismiss)) }
                }
            }
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
    TransitionFailure.QUARANTINE_FAILED -> R.string.account_transition_error_quarantine
    TransitionFailure.QUARANTINE_UNSAVED -> R.string.account_transition_error_quarantine_bind
    TransitionFailure.ALREADY_PREPARED,
    TransitionFailure.NOT_FOUND,
    TransitionFailure.ANONYMOUS_ONLY,
    TransitionFailure.PERSISTENT_ACCOUNT_REQUIRED,
    TransitionFailure.CONFLICT,
    TransitionFailure.UNKNOWN,
    -> R.string.account_transition_error_generic
}

/** §6.8 failure copy: what happened, why, and the action; never server internals. */
internal fun deletionFailureRes(failure: DeletionFailure): Int = when (failure) {
    DeletionFailure.NETWORK -> R.string.account_delete_error_network
    DeletionFailure.SIGN_IN_REQUIRED -> R.string.account_error_not_signed_in
    DeletionFailure.REAUTH_REQUIRED -> R.string.account_delete_error_reauth
    DeletionFailure.REAUTH_FAILED -> R.string.account_delete_error_reauth_failed
    DeletionFailure.EMAIL_NOT_VERIFIED -> R.string.account_delete_error_unverified
    DeletionFailure.PERSISTENT_ACCOUNT_REQUIRED -> R.string.account_delete_error_persistent
    DeletionFailure.ANONYMOUS_ONLY -> R.string.account_delete_error_anonymous
    DeletionFailure.CONFIRMATION_MISMATCH -> R.string.account_delete_error_phrase
    DeletionFailure.INVALID_INPUT -> R.string.account_delete_error_input
    DeletionFailure.DENIED -> R.string.account_delete_error_denied
    DeletionFailure.SECURITY_CHECK_FAILED -> R.string.account_delete_error_security
    DeletionFailure.PENDING_WRITES -> R.string.account_error_pending_writes
    DeletionFailure.UNKNOWN -> R.string.account_error_generic
}
