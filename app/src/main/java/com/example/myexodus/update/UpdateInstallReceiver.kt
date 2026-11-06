package com.example.myexodus.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

internal class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val store = UpdateStateStore(context)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val prompt = IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_INTENT,
                    Intent::class.java,
                )
                if (prompt != null) {
                    prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(prompt) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> store.recordInstallStatus(InstallStatus.SUCCEEDED)
            else -> store.recordInstallStatus(failureFor(status))
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.example.myexodus.update.INSTALL_STATUS"

        internal fun failureFor(status: Int): InstallStatus = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallStatus.FAILED_ABORTED
            PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallStatus.FAILED_BLOCKED
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallStatus.FAILED_CONFLICT
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallStatus.FAILED_INCOMPATIBLE
            PackageInstaller.STATUS_FAILURE_INVALID -> InstallStatus.FAILED_INVALID
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallStatus.FAILED_STORAGE
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> InstallStatus.FAILED_TIMEOUT
            else -> InstallStatus.FAILED_UNKNOWN
        }
    }
}
