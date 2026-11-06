package com.example.myexodus.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * Plan §8.5: explicit non-exported status callback, correlated to the owned attempt and
 * session. A pending user action is stored for a resumed activity or user tap; the receiver
 * never launches UI from the background.
 */
internal class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val attemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID) ?: return
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val store = UpdateStateStore(context)
        val attempt = store.activeAttempt()
        if (attempt == null ||
            attempt.attemptId != attemptId ||
            attempt.sessionId != sessionId
        ) {
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val verificationReason = intent.getIntExtra(EXTRA_DEVELOPER_VERIFICATION_REASON, -1)
        val now = System.currentTimeMillis()
        val updated = when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                val installed = installedVersionCode(context)
                if (installed >= attempt.versionCode) {
                    attempt.copy(
                        state = AttemptState.SUCCEEDED,
                        committed = true,
                        error = null,
                        updatedAtMs = now,
                    )
                } else {
                    attempt.copy(
                        state = AttemptState.UPDATE_STATUS_UNKNOWN,
                        committed = true,
                        error = AttemptError.UPDATE_STATUS_UNKNOWN,
                        updatedAtMs = now,
                    )
                }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val prompt = IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_INTENT,
                    Intent::class.java,
                )
                attempt.copy(
                    state = AttemptState.AWAITING_USER_CONFIRMATION,
                    committed = true,
                    userActionIntentUri = prompt?.toUri(Intent.URI_INTENT_SCHEME),
                    updatedAtMs = now,
                )
            }
            else -> {
                val failure = if (verificationReason >= 0) {
                    AttemptError.INSTALL_VERIFICATION_BLOCKED
                } else {
                    failureFor(status)
                }
                attempt.copy(
                    state = if (failure == AttemptError.UPDATE_STATUS_UNKNOWN) {
                        AttemptState.UPDATE_STATUS_UNKNOWN
                    } else {
                        AttemptState.FAILED
                    },
                    error = failure,
                    updatedAtMs = now,
                )
            }
        }
        store.saveAttempt(updated)
        UpdateDiagnostics(context).record(
            code = "install_status",
            policySequence = attempt.policySequence,
            versionCode = attempt.versionCode,
            installerStatus = if (verificationReason >= 0) {
                "$status;verification=$verificationReason"
            } else {
                status.toString()
            },
        )
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.example.myexodus.update.INSTALL_STATUS"
        const val EXTRA_ATTEMPT_ID = "attempt_id"
        const val EXTRA_SESSION_ID = "session_id"

        // PackageInstaller.EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON (API 36.1), inlined as a
        // literal so reading it on older releases is a harmless default miss.
        private const val EXTRA_DEVELOPER_VERIFICATION_REASON =
            "android.content.pm.extra.DEVELOPER_VERIFICATION_FAILURE_REASON"

        internal fun failureFor(status: Int): AttemptError = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> AttemptError.INSTALL_FAILED
            PackageInstaller.STATUS_FAILURE_BLOCKED -> AttemptError.INSTALL_BLOCKED
            PackageInstaller.STATUS_FAILURE_CONFLICT -> AttemptError.INSTALL_CONFLICT
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> AttemptError.RELEASE_INCOMPATIBLE
            PackageInstaller.STATUS_FAILURE_INVALID -> AttemptError.INSTALL_FAILED
            PackageInstaller.STATUS_FAILURE_STORAGE -> AttemptError.INSUFFICIENT_STORAGE
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> AttemptError.INSTALL_TIMEOUT
            else -> AttemptError.UPDATE_STATUS_UNKNOWN
        }
    }
}
