package com.example.myexodus.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles the degraded-notification actions (blocking-core plan §8.2): explicit re-enable
 * after a Task-Manager Stop, and the usage-access settings deep link.
 */
@AndroidEntryPoint
class ProtectionActionReceiver : BroadcastReceiver() {

    @Inject lateinit var status: ProtectionStatusStore
    @Inject lateinit var statusMonitor: ProtectionStatusMonitor

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REENABLE -> {
                status.reenable()
                // Recompute + publish (§8 Step 6): clearing APP_STOPPED can immediately
                // reveal another reason protection is degraded, or none at all.
                statusMonitor.refresh()
            }
        }
    }

    companion object {
        const val ACTION_REENABLE = "com.example.myexodus.action.REENABLE_PROTECTION"
    }
}
