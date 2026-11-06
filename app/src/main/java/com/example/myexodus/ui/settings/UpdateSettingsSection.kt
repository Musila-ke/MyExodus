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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myexodus.BuildConfig
import com.example.myexodus.R
import com.example.myexodus.update.UpdateFailure

@Composable
internal fun UpdateSettingsSection(update: UpdateViewModel) {
    val state by update.state.collectAsStateWithLifecycle()
    var dialogOpen by remember { mutableStateOf(false) }
    val subtitle = when (state.phase) {
        UpdatePhase.NOT_CONFIGURED -> stringResource(R.string.settings_update_subtitle_not_configured)
        UpdatePhase.CHECKING -> stringResource(R.string.settings_update_subtitle_checking)
        UpdatePhase.NO_RELEASE -> stringResource(R.string.settings_update_subtitle_no_release)
        UpdatePhase.AVAILABLE -> stringResource(
            R.string.settings_update_subtitle_available,
            state.availableVersionName.orEmpty(),
        )
        UpdatePhase.DOWNLOADING -> stringResource(
            R.string.settings_update_subtitle_downloading,
            state.progressPercent ?: 0,
        )
        UpdatePhase.INSTALLING -> stringResource(R.string.settings_update_subtitle_installing)
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
            UpdatePhase.AVAILABLE -> StatusTone.INFO
            UpdatePhase.FAILED -> StatusTone.ATTENTION
            else -> null
        },
        onClick = {
            dialogOpen = true
            if (state.phase == UpdatePhase.IDLE) update.checkForUpdates()
        },
    )
    if (dialogOpen) {
        UpdateDialog(update, state, onDismiss = { dialogOpen = false })
    }
}

@Composable
private fun UpdateDialog(update: UpdateViewModel, state: UpdateUiState, onDismiss: () -> Unit) {
    val title = if (state.phase == UpdatePhase.AVAILABLE && state.availableVersionName != null) {
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
                    UpdatePhase.NOT_CONFIGURED -> Text(stringResource(R.string.settings_update_body_not_configured))
                    UpdatePhase.CHECKING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.settings_update_checking))
                    }
                    UpdatePhase.UP_TO_DATE -> Text(
                        stringResource(R.string.settings_update_up_to_date, BuildConfig.VERSION_NAME),
                    )
                    UpdatePhase.NO_RELEASE -> Text(stringResource(R.string.settings_update_no_release))
                    UpdatePhase.AVAILABLE -> {
                        Text(
                            stringResource(
                                R.string.settings_update_available,
                                state.availableVersionName.orEmpty(),
                            ),
                        )
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
                        Text(stringResource(R.string.settings_update_downloading))
                        LinearProgressIndicator(
                            progress = { (state.progressPercent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    UpdatePhase.INSTALLING -> Text(stringResource(R.string.settings_update_installing))
                    UpdatePhase.FAILED -> Text(failureText(state.failure))
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.IDLE -> TextButton(onClick = { update.checkForUpdates() }) {
                    Text(stringResource(R.string.settings_update_check_now))
                }
                UpdatePhase.UP_TO_DATE, UpdatePhase.NO_RELEASE -> TextButton(
                    onClick = { update.checkForUpdates() },
                ) {
                    Text(stringResource(R.string.settings_update_check_again))
                }
                UpdatePhase.AVAILABLE -> TextButton(onClick = {
                    if (state.canInstall) update.downloadAndInstall() else update.openInstallSettings()
                }) {
                    Text(
                        stringResource(
                            if (state.canInstall) {
                                R.string.settings_update_download_install
                            } else {
                                R.string.settings_update_allow_installs
                            },
                        ),
                    )
                }
                UpdatePhase.FAILED -> TextButton(onClick = { update.retry() }) {
                    Text(stringResource(R.string.settings_update_try_again))
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (state.phase != UpdatePhase.INSTALLING) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_update_close))
                }
            }
        },
    )
}

@Composable
private fun failureText(failure: UpdateFailure?): String = stringResource(
    when (failure) {
        UpdateFailure.OFFLINE -> R.string.settings_update_failure_offline
        UpdateFailure.NO_RELEASE -> R.string.settings_update_no_release
        UpdateFailure.SERVER -> R.string.settings_update_failure_server
        UpdateFailure.INVALID_MANIFEST -> R.string.settings_update_failure_invalid_manifest
        UpdateFailure.DOWNLOAD -> R.string.settings_update_failure_download
        UpdateFailure.INTEGRITY -> R.string.settings_update_failure_integrity
        UpdateFailure.INVALID_APK -> R.string.settings_update_failure_invalid_apk
        UpdateFailure.WRONG_PACKAGE -> R.string.settings_update_failure_wrong_package
        UpdateFailure.VERSION_MISMATCH, UpdateFailure.DOWNGRADE ->
            R.string.settings_update_failure_version
        UpdateFailure.SIGNER_MISMATCH -> R.string.settings_update_failure_signer
        UpdateFailure.INSTALL, null -> R.string.settings_update_failure_install
        UpdateFailure.PERMISSION -> R.string.settings_update_failure_permission
        UpdateFailure.STORAGE -> R.string.settings_update_failure_storage
    },
)

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
