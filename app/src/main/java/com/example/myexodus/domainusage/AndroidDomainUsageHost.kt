package com.example.myexodus.domainusage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.myexodus.R

/** §8 S7.4/S7.5: [DomainUsageHost] over the app's real services. */
class AndroidDomainUsageHost(private val context: Context) : DomainUsageHost {

    override suspend fun modeOverride(): EngineMode? =
        EngineMode.parseOrNull(DomainUsageSettings.modeOverride(context))

    override suspend fun lastDayKey(): String? = DomainUsageSettings.lastDayKey(context)

    override suspend fun setLastDayKey(dayKey: String) = DomainUsageSettings.setLastDayKey(context, dayKey)

    /**
     * §8 S7.4: dedicated channel, one notification, suppressed without notification permission.
     * INV-7: the copy names the feature, never a site.
     */
    override fun postVpnStoppedNotification() {
        if (notificationsDenied()) return
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.domain_usage_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            nm.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(context.getString(R.string.domain_usage_vpn_stopped_title))
                    .setContentText(context.getString(R.string.domain_usage_vpn_stopped_text))
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: Exception) {
            // A missing notification must never break the stop path.
            return
        }
    }

    override fun registerPrivateDnsMonitor(onStrictChanged: (Boolean) -> Unit): () -> Unit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return {}
        return PrivateDnsMonitor(context, onStrictChanged).start()
    }

    /** Same gate as ProtectionStatusMonitor: the 33+ permission plus the channel-level switch. */
    private fun notificationsDenied(): Boolean {
        val permissionDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        return permissionDenied || !NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private companion object {
        const val CHANNEL_ID = "domain_usage_status"
        const val NOTIFICATION_ID = 2
    }
}
