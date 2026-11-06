package com.example.myexodus.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.BuildConfig
import com.example.myexodus.R
import com.example.myexodus.update.AttemptError
import java.util.Locale

@Composable
internal fun UpdateSettingsSection(update: UpdateViewModel, onOpenProtection: () -> Unit) {
    val state by update.state.collectAsStateWithLifecycle()
    var dialogOpen by remember { mutableStateOf(false) }
    // A notification tap asks this dialog to open; the token makes the request one-shot.
    LaunchedEffect(state.openDialogRequest) {
        if (state.openDialogRequest > 0) {
            dialogOpen = true
            update.consumeOpenDialog()
        }
    }
    val subtitle = when (state.phase) {
        UpdatePhase.NOT_CONFIGURED -> stringResource(R.string.settings_update_subtitle_not_configured)
        UpdatePhase.CHECKING -> stringResource(R.string.settings_update_subtitle_checking)
        UpdatePhase.HALTED -> stringResource(R.string.settings_update_subtitle_halted)
        UpdatePhase.NOT_IN_ROLLOUT -> stringResource(R.string.settings_update_subtitle_not_in_rollout)
        UpdatePhase.CHECK_STALE -> stringResource(R.string.settings_update_subtitle_check_stale)
        UpdatePhase.AVAILABLE -> stringResource(
            R.string.settings_update_subtitle_available,
            state.availableVersionName.orEmpty(),
        )
        UpdatePhase.DOWNLOADING -> stringResource(
            R.string.settings_update_subtitle_downloading,
            state.progressPercent ?: 0,
        )
        UpdatePhase.PAUSED -> stringResource(R.string.settings_update_subtitle_paused)
        UpdatePhase.VERIFYING -> stringResource(R.string.settings_update_subtitle_verifying)
        UpdatePhase.READY_TO_INSTALL -> stringResource(R.string.settings_update_subtitle_ready)
        UpdatePhase.NEEDS_PERMISSION -> stringResource(R.string.settings_update_subtitle_needs_permission)
        UpdatePhase.STAGING -> stringResource(R.string.settings_update_subtitle_staging)
        UpdatePhase.INSTALLING -> stringResource(R.string.settings_update_subtitle_installing)
        UpdatePhase.AWAITING_CONFIRMATION -> stringResource(R.string.settings_update_subtitle_awaiting)
        UpdatePhase.STATUS_UNKNOWN -> stringResource(R.string.settings_update_subtitle_status_unknown)
        UpdatePhase.FAILED -> stringResource(R.string.settings_update_subtitle_failed)
        UpdatePhase.UP_TO_DATE -> if (state.lastCheckedAtMs > 0) {
            stringResource(
                R.string.settings_update_subtitle_up_to_date,
                DateUtils.getRelativeTimeSpanString(state.lastCheckedAtMs).toString(),
            )
        } else {
            stringResource(R.string.settings_update_subtitle_up_to_date_short)
        }
        UpdatePhase.IDLE -> stringResource(R.string.settings_update_subtitle_idle)
    }
    IndexRow(
        icon = Icons.Outlined.SystemUpdate,
        title = stringResource(R.string.settings_update_title),
        subtitle = subtitle,
        tone = when (state.phase) {
            UpdatePhase.AVAILABLE, UpdatePhase.READY_TO_INSTALL -> StatusTone.INFO
            UpdatePhase.FAILED, UpdatePhase.NEEDS_PERMISSION,
            UpdatePhase.STATUS_UNKNOWN -> StatusTone.ATTENTION
            else -> null
        },
        onClick = {
            dialogOpen = true
            if (state.phase == UpdatePhase.IDLE) update.checkForUpdates()
        },
    )
    if (dialogOpen) {
        UpdateDialog(
            update = update,
            state = state,
            onOpenProtection = {
                onOpenProtection()
                dialogOpen = false
            },
            onDismiss = { dialogOpen = false },
        )
    }
}

@Composable
private fun UpdateDialog(
    update: UpdateViewModel,
    state: UpdateUiState,
    onOpenProtection: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val title = if (state.availableVersionName != null) {
        stringResource(R.string.settings_update_dialog_title_available, state.availableVersionName)
    } else {
        stringResource(R.string.settings_update_dialog_title)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.phase) {
                    UpdatePhase.IDLE -> Text(stringResource(R.string.settings_update_body_idle))
                    UpdatePhase.NOT_CONFIGURED ->
                        Text(stringResource(R.string.settings_update_body_not_configured))
                    UpdatePhase.CHECKING -> BusyLine(stringResource(R.string.settings_update_checking))
                    UpdatePhase.UP_TO_DATE -> Text(
                        stringResource(R.string.settings_update_up_to_date, state.installedVersionName),
                    )
                    UpdatePhase.HALTED -> Text(
                        stringResource(R.string.settings_update_halted, state.installedVersionName),
                    )
                    UpdatePhase.NOT_IN_ROLLOUT -> Text(
                        stringResource(R.string.settings_update_not_in_rollout, state.installedVersionName),
                    )
                    UpdatePhase.CHECK_STALE -> Text(
                        stringResource(R.string.settings_update_check_stale, state.installedVersionName),
                    )
                    UpdatePhase.AVAILABLE -> {
                        val size = state.sizeBytes?.let(::formatBytes)
                        Text(
                            if (size != null) {
                                stringResource(
                                    R.string.settings_update_available,
                                    state.availableVersionName.orEmpty(),
                                    size,
                                )
                            } else {
                                stringResource(
                                    R.string.settings_update_available_no_size,
                                    state.availableVersionName.orEmpty(),
                                )
                            },
                        )
                        if (state.roaming) {
                            Text(stringResource(R.string.settings_update_roaming))
                        } else if (state.metered && size != null) {
                            Text(stringResource(R.string.settings_update_metered, size))
                        }
                        state.notes?.let { notes ->
                            Text(
                                stringResource(R.string.settings_update_whats_new),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                notes,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    UpdatePhase.DOWNLOADING -> {
                        Text(
                            stringResource(
                                R.string.settings_update_downloading,
                                state.progressPercent ?: 0,
                            ),
                        )
                        LinearProgressIndicator(
                            progress = { (state.progressPercent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    UpdatePhase.PAUSED -> Text(stringResource(R.string.settings_update_paused))
                    UpdatePhase.VERIFYING -> BusyLine(stringResource(R.string.settings_update_verifying))
                    UpdatePhase.READY_TO_INSTALL ->
                        Text(stringResource(R.string.settings_update_ready))
                    UpdatePhase.NEEDS_PERMISSION ->
                        Text(stringResource(R.string.settings_update_needs_permission))
                    UpdatePhase.STAGING -> BusyLine(stringResource(R.string.settings_update_staging))
                    UpdatePhase.INSTALLING -> Text(stringResource(R.string.settings_update_installing))
                    UpdatePhase.AWAITING_CONFIRMATION ->
                        Text(stringResource(R.string.settings_update_awaiting))
                    UpdatePhase.STATUS_UNKNOWN ->
                        Text(stringResource(R.string.settings_update_status_unknown))
                    UpdatePhase.FAILED -> Text(
                        failureText(state.error ?: state.checkError),
                    )
                }
                val autoDownloadTitle = stringResource(R.string.settings_update_auto_download_title)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            autoDownloadTitle,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.settings_update_auto_download_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // Sibling of its label, so it needs its own accessible name (NamedSwitchTest).
                    Switch(
                        checked = state.autoDownload,
                        onCheckedChange = update::setAutoDownload,
                        modifier = Modifier.semantics { contentDescription = autoDownloadTitle },
                    )
                }
                if (state.protectionResumeRequired) {
                    Text(stringResource(R.string.settings_update_protection_resume))
                    TextButton(onClick = onOpenProtection) {
                        Text(stringResource(R.string.settings_update_open_protection))
                    }
                }
                if (state.phase in RELEASE_PAGE_PHASES) {
                    TextButton(onClick = { update.openReleasePage(context) }) {
                        Text(stringResource(R.string.settings_update_open_release_page))
                    }
                }
                if (state.phase in CANCELABLE_PHASES) {
                    TextButton(onClick = { update.cancelDownload() }) {
                        Text(stringResource(R.string.settings_update_cancel_download))
                    }
                }
                TextButton(onClick = { update.copyDiagnostics(context) }) {
                    Text(stringResource(R.string.settings_update_copy_diagnostics))
                }
                // Plan §14: installed version, truthful last authenticated fetch, last outcome.
                Text(
                    stringResource(R.string.settings_update_status_installed, state.installedVersionName),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (state.lastCheckedAtMs > 0) {
                        stringResource(
                            R.string.settings_update_status_last_check,
                            DateUtils.getRelativeTimeSpanString(state.lastCheckedAtMs).toString(),
                        )
                    } else {
                        stringResource(R.string.settings_update_status_never_checked)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                when (state.lastOutcome) {
                    LastOutcome.SUCCEEDED -> Text(
                        stringResource(R.string.settings_update_status_last_success),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LastOutcome.FAILED -> Text(
                        stringResource(
                            R.string.settings_update_status_last_failure,
                            failureText(state.lastOutcomeError),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LastOutcome.UNKNOWN -> Text(
                        stringResource(R.string.settings_update_status_last_unknown),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LastOutcome.NONE -> Unit
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.IDLE -> PrimaryAction(
                    label = stringResource(R.string.settings_update_check_now),
                    enabled = !state.busy,
                    onClick = { update.checkForUpdates() },
                )
                UpdatePhase.UP_TO_DATE, UpdatePhase.HALTED, UpdatePhase.NOT_IN_ROLLOUT,
                UpdatePhase.CHECK_STALE -> PrimaryAction(
                    label = stringResource(R.string.settings_update_check_again),
                    enabled = !state.busy && !state.roaming,
                    onClick = { update.retry() },
                )
                UpdatePhase.FAILED -> PrimaryAction(
                    label = stringResource(R.string.settings_update_retry),
                    enabled = !state.busy && !state.roaming,
                    onClick = { update.retry() },
                )
                UpdatePhase.AVAILABLE -> PrimaryAction(
                    label = stringResource(
                        if (state.metered) {
                            R.string.settings_update_download_metered
                        } else {
                            R.string.settings_update_download
                        },
                    ),
                    enabled = !state.busy && !state.roaming,
                    onClick = { update.download(meteredConsent = state.metered) },
                )
                UpdatePhase.DOWNLOADING, UpdatePhase.PAUSED, UpdatePhase.VERIFYING -> PrimaryAction(
                    label = stringResource(R.string.settings_update_cancel_download),
                    enabled = true,
                    onClick = { update.cancelDownload() },
                )
                UpdatePhase.READY_TO_INSTALL -> PrimaryAction(
                    label = stringResource(R.string.settings_update_install),
                    enabled = !state.busy,
                    onClick = { update.install() },
                )
                UpdatePhase.NEEDS_PERMISSION -> PrimaryAction(
                    label = stringResource(
                        if (state.canInstall) {
                            R.string.settings_update_install
                        } else {
                            R.string.settings_update_allow_installs
                        },
                    ),
                    enabled = !state.busy,
                    onClick = { update.install() },
                )
                UpdatePhase.AWAITING_CONFIRMATION -> PrimaryAction(
                    label = stringResource(R.string.settings_update_continue_installation),
                    enabled = true,
                    onClick = { update.continueInstall(context) },
                )
                UpdatePhase.STATUS_UNKNOWN -> PrimaryAction(
                    label = stringResource(R.string.settings_update_check_status),
                    enabled = true,
                    onClick = { update.retry() },
                )
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_update_close))
            }
        },
    )
}

@Composable
private fun PrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(enabled = enabled, onClick = onClick) { Text(label) }
}

@Composable
private fun BusyLine(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(label)
    }
}

@Composable
private fun failureText(error: AttemptError?): String = stringResource(
    when (error) {
        AttemptError.NO_STABLE_POLICY -> R.string.settings_update_failure_no_policy
        AttemptError.NETWORK_UNAVAILABLE, AttemptError.NETWORK_TIMEOUT ->
            R.string.settings_update_failure_offline
        AttemptError.HOST_THROTTLED, AttemptError.HOST_UNAVAILABLE ->
            R.string.settings_update_failure_server
        AttemptError.POLICY_INVALID, AttemptError.POLICY_ROLLBACK, AttemptError.POLICY_CONFLICT ->
            R.string.settings_update_failure_policy
        AttemptError.DOWNLOAD_SERVICE_UNAVAILABLE, AttemptError.DOWNLOAD_DESTINATION_UNAVAILABLE ->
            R.string.settings_update_failure_download_service
        AttemptError.DOWNLOAD_FAILED -> R.string.settings_update_failure_download
        AttemptError.APK_INTEGRITY_FAILURE -> R.string.settings_update_failure_integrity
        AttemptError.RELEASE_INCOMPATIBLE -> R.string.settings_update_failure_release
        AttemptError.INSUFFICIENT_STORAGE -> R.string.settings_update_failure_storage
        AttemptError.NEEDS_INSTALL_PERMISSION -> R.string.settings_update_failure_permission
        AttemptError.INSTALL_UI_BLOCKED -> R.string.settings_update_failure_install_ui
        AttemptError.INSTALL_BLOCKED -> R.string.settings_update_failure_install_blocked
        AttemptError.INSTALL_VERIFICATION_BLOCKED ->
            R.string.settings_update_failure_install_verification
        AttemptError.INSTALL_CONFLICT -> R.string.settings_update_failure_install_conflict
        AttemptError.INSTALL_TIMEOUT -> R.string.settings_update_failure_install_timeout
        AttemptError.WAITING_FOR_POLICY_CONFIRMATION ->
            R.string.settings_update_failure_policy_confirm
        AttemptError.UPDATE_STATUS_UNKNOWN -> R.string.settings_update_failure_status_unknown
        AttemptError.PROTECTION_RESUME_REQUIRED -> R.string.settings_update_failure_protection
        AttemptError.HALTED -> R.string.settings_update_failure_halted
        AttemptError.NOT_IN_ROLLOUT -> R.string.settings_update_failure_not_in_rollout
        AttemptError.INSTALL_FAILED, null -> R.string.settings_update_failure_install
    },
)

/** One prompt per offered version, shown when the app opens (plan revision 2026-11-06). */
@Composable
internal fun UpdateAvailablePrompt(
    versionName: String?,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (versionName == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_update_prompt_title)) },
        text = {
            Text(stringResource(R.string.settings_update_prompt_body, versionName))
        },
        confirmButton = {
            TextButton(onClick = onReview) {
                Text(stringResource(R.string.settings_update_prompt_review))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_update_prompt_later))
            }
        },
    )
}

@Composable
internal fun UpdateNoticeDialog(update: UpdateViewModel) {
    val state by update.state.collectAsStateWithLifecycle()
    val notice = state.notice ?: return
    AlertDialog(
        onDismissRequest = update::dismissNotice,
        title = {
            Text(stringResource(R.string.settings_update_notice_title, BuildConfig.VERSION_NAME))
        },
        text = {
            val notes = notice.notes
            if (notes != null) {
                Text(
                    notes,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
            } else {
                Text(stringResource(R.string.settings_update_notice_body, BuildConfig.VERSION_NAME))
            }
        },
        confirmButton = {
            TextButton(onClick = update::dismissNotice) {
                Text(stringResource(R.string.settings_update_notice_ok))
            }
        },
    )
}

private val RELEASE_PAGE_PHASES = setOf(
    UpdatePhase.UP_TO_DATE,
    UpdatePhase.HALTED,
    UpdatePhase.NOT_IN_ROLLOUT,
    UpdatePhase.CHECK_STALE,
    UpdatePhase.AVAILABLE,
    UpdatePhase.FAILED,
)

private val CANCELABLE_PHASES = setOf(
    UpdatePhase.DOWNLOADING,
    UpdatePhase.PAUSED,
    UpdatePhase.VERIFYING,
    UpdatePhase.READY_TO_INSTALL,
)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 ->
        String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 ->
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L ->
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
