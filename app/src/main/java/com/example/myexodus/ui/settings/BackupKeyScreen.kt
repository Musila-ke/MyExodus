package com.example.myexodus.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.R
import com.example.myexodus.account.AuthProvider
import com.example.myexodus.account.AuthState
import com.example.myexodus.backup.BackupKeyFailure
import com.example.myexodus.backup.BackupKeyManager.Unlock
import com.example.myexodus.backup.BackupProgress
import com.example.myexodus.backup.BackupUploadFailure
import com.example.myexodus.backup.PassphrasePolicy
import com.example.myexodus.ui.BackRow
import com.example.myexodus.ui.SecureSurface

/**
 * Release 2 (account plan §6.4/§6.5/§8.5): the backup-key pane. Three screens in one: first
 * setup, the one-time recovery-key reveal, and the management actions. The pane is a
 * [SecureSurface] throughout — the reveal must not be screenshotted.
 */
@Composable
internal fun BackupKeyScreen(
    viewModel: BackupKeyViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val active = auth as? AuthState.PersistentActive
    val verificationRequired = active != null &&
        AuthProvider.PASSWORD in active.providers && !active.emailVerified

    SecureSurface()

    Column(modifier = modifier.fillMaxSize()) {
        BackRow(label = stringResource(R.string.backup_key_title), onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val current = operation) {
                is BackupKeyViewModel.Operation.Reveal -> RecoveryReveal(
                    words = current.words,
                    setup = current.setup,
                    onDone = viewModel::acknowledgeReveal,
                )
                else -> {
                    if (operation is BackupKeyViewModel.Operation.Working) {
                        WorkingCard(stringResource(R.string.backup_key_working))
                        TextButton(
                            onClick = viewModel::cancel,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.backup_key_cancel))
                        }
                    }
                    if (operation is BackupKeyViewModel.Operation.Failed) {
                        OperationError(
                            failure = (operation as BackupKeyViewModel.Operation.Failed).failure,
                            onDismiss = viewModel::dismissFailure,
                            onRetry = viewModel::refresh,
                        )
                    }
                    when (val current = status) {
                        BackupKeyViewModel.Status.Loading ->
                            WorkingCard(stringResource(R.string.backup_key_loading))
                        is BackupKeyViewModel.Status.Unavailable -> StatusError(
                            failure = current.failure,
                            onRetry = viewModel::refresh,
                        )
                        BackupKeyViewModel.Status.NotSetUp ->
                            if (verificationRequired && active != null) {
                                VerificationRequired(email = active.email.orEmpty())
                            } else {
                                SetupFlow(viewModel)
                            }
                        is BackupKeyViewModel.Status.Configured ->
                            if (verificationRequired) {
                                StatusCard(stringResource(R.string.backup_key_verify_short))
                            } else {
                                ManageFlow(
                                    viewModel = viewModel,
                                    operation = operation,
                                    backup = backup,
                                )
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationRequired(email: String) {
    InfoCard(
        title = stringResource(R.string.backup_key_verify_title),
        body = stringResource(R.string.backup_key_verify_body, email),
    )
}

private enum class PassphraseMode { MANUAL, GENERATED }

/** §8.5: either a user-chosen passphrase that clears the policy or six generated words. */
@Composable
private fun SetupFlow(viewModel: BackupKeyViewModel) {
    var mode by rememberSaveable { mutableStateOf(PassphraseMode.MANUAL) }
    // Secrets live only in remember: a rotation or process death must drop them, never
    // restore them from saved instance state.
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var generated by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }

    InfoCard(
        title = stringResource(R.string.backup_key_setup_title),
        body = stringResource(R.string.backup_key_setup_body),
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == PassphraseMode.MANUAL,
                    onClick = { mode = PassphraseMode.MANUAL; error = null },
                    label = { Text(stringResource(R.string.backup_key_mode_manual)) },
                )
                FilterChip(
                    selected = mode == PassphraseMode.GENERATED,
                    onClick = { mode = PassphraseMode.GENERATED; error = null },
                    label = { Text(stringResource(R.string.backup_key_mode_generated)) },
                )
            }

            when (mode) {
                PassphraseMode.MANUAL -> {
                    PasswordField(
                        value = passphrase,
                        onValueChange = { passphrase = it; error = null },
                        label = R.string.backup_key_passphrase_label,
                        errorRes = error,
                    )
                    PasswordField(
                        value = confirm,
                        onValueChange = { confirm = it; error = null },
                        label = R.string.backup_key_passphrase_confirm,
                        errorRes = null,
                    )
                    Text(
                        stringResource(R.string.backup_key_policy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PassphraseMode.GENERATED -> {
                    val words = generated
                    Text(
                        stringResource(R.string.backup_key_generated_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (words != null) {
                        WordList(words.split(' ').filter { it.isNotEmpty() })
                    }
                    OutlinedButton(onClick = { generated = viewModel.generatePassphrase() }) {
                        Text(stringResource(R.string.backup_key_generate))
                    }
                }
            }

            Button(
                onClick = {
                    if (mode == PassphraseMode.MANUAL) {
                        if (passphrase != confirm) {
                            error = R.string.backup_key_passphrase_mismatch
                            return@Button
                        }
                        val check = viewModel.checkPassphrase(passphrase, generated = false)
                        if (check != PassphrasePolicy.Result.Accepted) {
                            error = policyMessage(check)
                            return@Button
                        }
                        viewModel.setUp(passphrase, generated = false)
                    } else {
                        generated?.let { viewModel.setUp(it, generated = true) }
                    }
                    passphrase = ""
                    confirm = ""
                    generated = null
                },
                enabled = mode == PassphraseMode.MANUAL || generated != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backup_key_create)) }
        }
    }

    Text(
        stringResource(R.string.backup_key_create_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private enum class ManageMode { NONE, BACKUP, CHANGE_PASSPHRASE, ROTATE_RECOVERY }

private enum class UnlockMode { PASSPHRASE, RECOVERY }

@Composable
private fun ManageFlow(
    viewModel: BackupKeyViewModel,
    operation: BackupKeyViewModel.Operation,
    backup: BackupKeyViewModel.Backup,
) {
    var mode by rememberSaveable { mutableStateOf(ManageMode.NONE) }
    // Collapse a finished sub-flow once its operation returns to idle (§6.5 re-unlock included).
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(operation) {
        when (operation) {
            is BackupKeyViewModel.Operation.Working -> submitted = true
            BackupKeyViewModel.Operation.Idle -> if (submitted) {
                submitted = false
                mode = ManageMode.NONE
            }
            is BackupKeyViewModel.Operation.Reveal -> {
                submitted = false
                mode = ManageMode.NONE
            }
            is BackupKeyViewModel.Operation.Failed -> submitted = false
        }
    }

    InfoCard(
        title = stringResource(R.string.backup_key_configured_title),
        body = stringResource(R.string.backup_key_configured_body),
    )

    BackupStatusCard(backup = backup, viewModel = viewModel)

    val busy = backup is BackupKeyViewModel.Backup.Running ||
        backup is BackupKeyViewModel.Backup.Replace
    if (!busy) {
        when (mode) {
            ManageMode.NONE -> Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { mode = ManageMode.BACKUP },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.backup_now_button)) }
                    OutlinedButton(
                        onClick = { mode = ManageMode.CHANGE_PASSPHRASE },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.backup_key_change)) }
                    OutlinedButton(
                        onClick = { mode = ManageMode.ROTATE_RECOVERY },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.backup_key_rotate)) }
                }
            }

            ManageMode.BACKUP -> BackupForm(
                viewModel = viewModel,
                onCancel = { mode = ManageMode.NONE },
            )

            ManageMode.CHANGE_PASSPHRASE -> ChangePassphraseForm(
                viewModel = viewModel,
                onCancel = { mode = ManageMode.NONE },
            )

            ManageMode.ROTATE_RECOVERY -> RotateRecoveryForm(
                viewModel = viewModel,
                onCancel = { mode = ManageMode.NONE },
            )
        }
    }
}

/** §6.4 items 11-13: progress, the one explicit replacement confirmation, and the outcome. */
@Composable
private fun BackupStatusCard(
    backup: BackupKeyViewModel.Backup,
    viewModel: BackupKeyViewModel,
) {
    when (backup) {
        BackupKeyViewModel.Backup.Idle -> Unit

        is BackupKeyViewModel.Backup.Running -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    backupProgressText(backup.progress),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = viewModel::cancelBackup,
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.backup_now_cancel)) }
            }
        }

        is BackupKeyViewModel.Backup.Succeeded -> Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.backup_now_done_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.backup_now_done_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = viewModel::dismissBackup,
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.account_dismiss)) }
            }
        }

        is BackupKeyViewModel.Backup.Failed -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(backupUploadFailureRes(backup.failure)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (backup.retryable) {
                        TextButton(onClick = viewModel::retryBackup) {
                            Text(stringResource(R.string.backup_now_retry))
                        }
                    }
                    TextButton(onClick = viewModel::dismissBackup) {
                        Text(stringResource(R.string.account_dismiss))
                    }
                }
            }
        }

        is BackupKeyViewModel.Backup.Replace -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.backup_now_replace_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.backup_now_replace_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::replaceCloudBackup) {
                        Text(stringResource(R.string.backup_now_replace_confirm))
                    }
                    TextButton(onClick = viewModel::dismissBackup) {
                        Text(stringResource(R.string.backup_now_replace_keep))
                    }
                }
            }
        }
    }
}

@Composable
private fun backupProgressText(progress: BackupProgress): String = when (progress) {
    BackupProgress.Preparing -> stringResource(R.string.backup_now_preparing)
    is BackupProgress.Uploading -> {
        val percent = if (progress.total > 0) {
            (progress.transferred * 100 / progress.total).toInt()
        } else {
            0
        }
        stringResource(R.string.backup_now_uploading, percent.coerceIn(0, 100))
    }
    BackupProgress.Committing -> stringResource(R.string.backup_now_committing)
}

/** §6.4 item 5: the secret unlocks exactly this one snapshot and is cleared on submit. */
@Composable
private fun BackupForm(
    viewModel: BackupKeyViewModel,
    onCancel: () -> Unit,
) {
    var unlockMode by rememberSaveable { mutableStateOf(UnlockMode.PASSPHRASE) }
    var secret by remember { mutableStateOf("") }

    FormCard(title = stringResource(R.string.backup_now_button)) {
        Text(
            stringResource(R.string.backup_now_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = unlockMode == UnlockMode.PASSPHRASE,
                onClick = { unlockMode = UnlockMode.PASSPHRASE; secret = "" },
                label = { Text(stringResource(R.string.backup_key_unlock_passphrase)) },
            )
            FilterChip(
                selected = unlockMode == UnlockMode.RECOVERY,
                onClick = { unlockMode = UnlockMode.RECOVERY; secret = "" },
                label = { Text(stringResource(R.string.backup_key_unlock_recovery)) },
            )
        }
        if (unlockMode == UnlockMode.PASSPHRASE) {
            PasswordField(
                value = secret,
                onValueChange = { secret = it },
                label = R.string.backup_key_current_passphrase,
                errorRes = null,
            )
        } else {
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(stringResource(R.string.backup_key_recovery_words)) },
                supportingText = { Text(stringResource(R.string.backup_key_recovery_words_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            stringResource(R.string.backup_now_unlock_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = secret.isNotBlank(),
                onClick = {
                    val unlock = if (unlockMode == UnlockMode.PASSPHRASE) {
                        Unlock.Passphrase(secret)
                    } else {
                        Unlock.RecoveryWords(secret)
                    }
                    viewModel.backUpNow(unlock)
                    secret = ""
                },
            ) { Text(stringResource(R.string.backup_now_button)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.backup_key_cancel)) }
        }
    }
}

/** §6.5 item 1: the old passphrase, or the recovery key when the passphrase was forgotten. */
@Composable
private fun ChangePassphraseForm(
    viewModel: BackupKeyViewModel,
    onCancel: () -> Unit,
) {
    var unlockMode by rememberSaveable { mutableStateOf(UnlockMode.PASSPHRASE) }
    var current by remember { mutableStateOf("") }
    var newPassphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var generated by remember { mutableStateOf<String?>(null) }
    var generatedMode by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    FormCard(title = stringResource(R.string.backup_key_change)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = unlockMode == UnlockMode.PASSPHRASE,
                onClick = { unlockMode = UnlockMode.PASSPHRASE },
                label = { Text(stringResource(R.string.backup_key_unlock_passphrase)) },
            )
            FilterChip(
                selected = unlockMode == UnlockMode.RECOVERY,
                onClick = { unlockMode = UnlockMode.RECOVERY },
                label = { Text(stringResource(R.string.backup_key_unlock_recovery)) },
            )
        }
        if (unlockMode == UnlockMode.PASSPHRASE) {
            PasswordField(
                value = current,
                onValueChange = { current = it; error = null },
                label = R.string.backup_key_current_passphrase,
                errorRes = null,
            )
        } else {
            OutlinedTextField(
                value = current,
                onValueChange = { current = it; error = null },
                label = { Text(stringResource(R.string.backup_key_recovery_words)) },
                supportingText = { Text(stringResource(R.string.backup_key_recovery_words_hint)) },
                minLines = 2,
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !generatedMode,
                onClick = { generatedMode = false },
                label = { Text(stringResource(R.string.backup_key_mode_manual)) },
            )
            FilterChip(
                selected = generatedMode,
                onClick = { generatedMode = true },
                label = { Text(stringResource(R.string.backup_key_mode_generated)) },
            )
        }
        if (generatedMode) {
            val words = generated
            if (words != null) WordList(words.split(' ').filter { it.isNotEmpty() })
            OutlinedButton(onClick = { generated = viewModel.generatePassphrase() }) {
                Text(stringResource(R.string.backup_key_generate))
            }
        } else {
            PasswordField(
                value = newPassphrase,
                onValueChange = { newPassphrase = it; error = null },
                label = R.string.backup_key_new_passphrase,
                errorRes = error,
            )
            PasswordField(
                value = confirm,
                onValueChange = { confirm = it; error = null },
                label = R.string.backup_key_passphrase_confirm,
                errorRes = null,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !generatedMode || generated != null,
                onClick = {
                    val unlock = if (unlockMode == UnlockMode.PASSPHRASE) {
                        Unlock.Passphrase(current)
                    } else {
                        Unlock.RecoveryWords(current)
                    }
                    if (generatedMode) {
                        generated?.let { viewModel.changePassphrase(unlock, it, generated = true) }
                    } else {
                        if (newPassphrase != confirm) {
                            error = R.string.backup_key_passphrase_mismatch
                            return@Button
                        }
                        val check = viewModel.checkPassphrase(newPassphrase, generated = false)
                        if (check != PassphrasePolicy.Result.Accepted) {
                            error = policyMessage(check)
                            return@Button
                        }
                        viewModel.changePassphrase(unlock, newPassphrase, generated = false)
                    }
                    current = ""
                    newPassphrase = ""
                    confirm = ""
                    generated = null
                },
            ) { Text(stringResource(R.string.backup_key_change)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.backup_key_cancel)) }
        }
    }
}

/** §6.5 item 1: replacing a lost recovery key requires the current passphrase. */
@Composable
private fun RotateRecoveryForm(
    viewModel: BackupKeyViewModel,
    onCancel: () -> Unit,
) {
    var current by remember { mutableStateOf("") }

    FormCard(title = stringResource(R.string.backup_key_rotate)) {
        Text(
            stringResource(R.string.backup_key_rotate_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PasswordField(
            value = current,
            onValueChange = { current = it },
            label = R.string.backup_key_current_passphrase,
            errorRes = null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.rotateRecoveryKey(current)
                    current = ""
                },
            ) { Text(stringResource(R.string.backup_key_rotate)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.backup_key_cancel)) }
        }
    }
}

/**
 * §6.4 item 2: the 24-word recovery key is shown exactly once, behind FLAG_SECURE, with an
 * explicit acknowledgement before it leaves the screen; copying marks the clip sensitive.
 */
@Composable
private fun RecoveryReveal(
    words: List<String>,
    setup: Boolean,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.backup_key_reveal_copy_label)
    var acknowledged by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(
                    if (setup) R.string.backup_key_reveal_title_setup
                    else R.string.backup_key_reveal_title_rotation,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.backup_key_reveal_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                words.chunked(12).forEachIndexed { column, slice ->
                    Column(Modifier.weight(1f)) {
                        slice.forEachIndexed { index, word ->
                            Text(
                                "${column * 12 + index + 1}. $word",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    copySensitive(context, copyLabel, words.joinToString(" "))
                    copied = true
                },
            ) {
                Text(
                    stringResource(
                        if (copied) R.string.backup_key_reveal_copied
                        else R.string.backup_key_reveal_copy,
                    ),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = acknowledged,
                        role = Role.Checkbox,
                        onValueChange = { acknowledged = it },
                    ),
            ) {
                Checkbox(checked = acknowledged, onCheckedChange = null)
                Text(
                    stringResource(R.string.backup_key_reveal_ack),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Button(onClick = onDone, enabled = acknowledged, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.backup_key_reveal_done))
            }
        }
    }
}

@Composable
private fun OperationError(
    failure: BackupKeyFailure,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(backupFailureRes(failure)), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // §9: a conflict or a missing bundle is only actionable after a reload.
                if (failure == BackupKeyFailure.REVISION_CONFLICT ||
                    failure == BackupKeyFailure.NOT_INITIALIZED
                ) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.backup_key_reload)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_dismiss)) }
            }
        }
    }
}

@Composable
private fun StatusError(failure: BackupKeyFailure, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(backupFailureRes(failure)), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.backup_key_reload)) }
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun FormCard(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    errorRes: Int?,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = errorRes != null,
        supportingText = errorRes?.let { { Text(stringResource(it)) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WordList(words: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        words.forEachIndexed { index, word ->
            Text(
                "${index + 1}. $word",
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

private fun policyMessage(result: PassphrasePolicy.Result): Int = when (result) {
    PassphrasePolicy.Result.TooShort -> R.string.backup_key_passphrase_short
    is PassphrasePolicy.Result.TooWeak -> R.string.backup_key_passphrase_weak
    PassphrasePolicy.Result.Accepted -> R.string.backup_key_passphrase_weak
}

/** §6.4 item 2: the copied mnemonic is marked sensitive so the OS masks the preview. */
private fun copySensitive(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}

/** §9: what happened and what the user can do; never a server string or status code. */
internal fun backupFailureRes(failure: BackupKeyFailure): Int = when (failure) {
    BackupKeyFailure.PERSISTENT_ACCOUNT_REQUIRED -> R.string.backup_key_error_account
    BackupKeyFailure.EMAIL_VERIFICATION_REQUIRED -> R.string.backup_key_verify_short
    BackupKeyFailure.PROFILE_NOT_ACTIVE -> R.string.backup_key_error_profile
    BackupKeyFailure.ACCOUNT_DELETING -> R.string.backup_key_error_deleting
    BackupKeyFailure.ALREADY_INITIALIZED -> R.string.backup_key_error_exists
    BackupKeyFailure.NOT_INITIALIZED -> R.string.backup_key_error_missing
    BackupKeyFailure.REVISION_CONFLICT -> R.string.backup_key_error_conflict
    BackupKeyFailure.BUNDLE_IDENTITY_CHANGED,
    BackupKeyFailure.INVALID_BUNDLE,
    -> R.string.backup_key_error_version
    BackupKeyFailure.ROLLBACK -> R.string.backup_key_error_rollback
    BackupKeyFailure.WRONG_SECRET -> R.string.backup_key_error_unlock
    BackupKeyFailure.PASS_TOO_SHORT -> R.string.backup_key_passphrase_short
    BackupKeyFailure.PASS_TOO_WEAK -> R.string.backup_key_passphrase_weak
    BackupKeyFailure.SIGN_IN_REQUIRED -> R.string.account_error_not_signed_in
    BackupKeyFailure.DENIED -> R.string.backup_key_error_profile
    BackupKeyFailure.NETWORK -> R.string.account_error_network
    BackupKeyFailure.UNKNOWN -> R.string.account_error_generic
}

/** §9: the same rule for snapshot uploads — cause the user can act on, never raw server text. */
internal fun backupUploadFailureRes(failure: BackupUploadFailure): Int = when (failure) {
    BackupUploadFailure.PERSISTENT_ACCOUNT_REQUIRED -> R.string.backup_now_error_account
    BackupUploadFailure.EMAIL_VERIFICATION_REQUIRED -> R.string.backup_now_error_verify
    BackupUploadFailure.PROFILE_NOT_ACTIVE -> R.string.backup_now_error_profile
    BackupUploadFailure.ACCOUNT_DELETING -> R.string.backup_now_error_deleting
    BackupUploadFailure.FEATURE_DISABLED -> R.string.backup_now_error_feature
    BackupUploadFailure.BACKUP_BASE_REQUIRED,
    BackupUploadFailure.REMOTE_BACKUP_AHEAD,
    BackupUploadFailure.BACKUP_CONFLICT,
    -> R.string.backup_now_error_conflict
    BackupUploadFailure.REMOTE_BACKUP_EQUIVOCATION -> R.string.backup_now_error_equivocation
    BackupUploadFailure.REMOTE_BACKUP_ROLLBACK -> R.string.backup_now_error_rollback
    BackupUploadFailure.BACKUP_INVALID_REQUEST -> R.string.backup_now_error_unknown
    BackupUploadFailure.BACKUP_RESERVATION_LIMIT -> R.string.backup_now_error_limit
    BackupUploadFailure.BACKUP_RESERVATION_EXPIRED -> R.string.backup_now_error_expired
    BackupUploadFailure.BACKUP_OBJECT_MISSING -> R.string.backup_now_error_missing_object
    BackupUploadFailure.BACKUP_OBJECT_MISMATCH -> R.string.backup_now_error_mismatch
    BackupUploadFailure.NO_LOCAL_DATA -> R.string.backup_now_error_no_local
    BackupUploadFailure.WRONG_SECRET -> R.string.backup_now_error_unlock
    BackupUploadFailure.SESSION_CHANGED -> R.string.backup_now_error_session
    BackupUploadFailure.STAGING_FAILED -> R.string.backup_now_error_staging
    BackupUploadFailure.LOCAL_STATE_UNAVAILABLE -> R.string.backup_now_error_local
    BackupUploadFailure.PAYLOAD_TOO_LARGE -> R.string.backup_now_error_too_large
    BackupUploadFailure.SIGN_IN_REQUIRED -> R.string.account_error_not_signed_in
    BackupUploadFailure.DENIED -> R.string.backup_now_error_denied
    BackupUploadFailure.NETWORK -> R.string.account_error_network
    BackupUploadFailure.UNKNOWN -> R.string.backup_now_error_unknown
}
