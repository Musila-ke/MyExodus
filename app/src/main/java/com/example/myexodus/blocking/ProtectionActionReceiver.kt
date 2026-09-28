package com.example.myexodus.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myexodus.blocking.usage.AccountingDiagnosticsState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles the notification actions (blocking-core plan §8.2): explicit re-enable after a
 * Task-Manager Stop, and, for the §8.7 outage notification, the "Not now" decline.
 */
@AndroidEntryPoint
class ProtectionActionReceiver : BroadcastReceiver() {

    @Inject lateinit var status: ProtectionStatusStore
    @Inject lateinit var statusMonitor: ProtectionStatusMonitor
    @Inject lateinit var diagnostics: AccountingDiagnosticsState

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REENABLE -> {
                status.reenable()
                // Recompute + publish (§8 Step 6): clearing APP_STOPPED can immediately
                // reveal another reason protection is degraded, or none at all.
                statusMonitor.refresh()
            }
            ACTION_OUTAGE_DECLINED -> {
                // The decline cooldown (§8.7): the offer stops until the user's week has
                // passed, and the notification clears with the eligibility it was made from.
                diagnostics.markDeclined(System.currentTimeMillis())
                ProtectionNotifications.updateOutage(context, null)
            }
        }
    }

    companion object {
        const val ACTION_REENABLE = "com.example.myexodus.action.REENABLE_PROTECTION"
        const val ACTION_OUTAGE_DECLINED = "com.example.myexodus.action.OUTAGE_DECLINED"
    }
}
