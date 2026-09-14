package com.example.myexodus.blocking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.myexodus.R

/**
 * Notification surface for app-block protection (blocking-core plan §8.2/§9): the FGS
 * ongoing status (LOW) and the "protection degraded" alert (HIGH) with its explicit
 * re-enable / fix actions. POST_NOTIFICATIONS-denied is modeled as a distinct degradation
 * (plan §9) — the in-app banner is the only degraded indicator then.
 */
object ProtectionNotifications {

    const val CHANNEL_STATUS = "protection_status"
    const val CHANNEL_ALERTS = "protection_alerts"
    const val NOTIF_FGS = 1001
    const val NOTIF_DEGRADED = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "MyExodus protection", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "MyExodus protection alerts", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun ongoing(context: Context): Notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Protection active")
        .setContentText("Restricted apps are blocked as soon as they open.")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private data class DegradedCopy(
        val title: String,
        val text: String,
        val actionLabel: String,
        val action: PendingIntent,
    )

    fun degraded(context: Context, degradation: ProtectionDegradation): Notification {
        val copy = when (degradation) {
            ProtectionDegradation.USAGE_ACCESS_REVOKED ->
                DegradedCopy(
                    "App blocking is off",
                    "MyExodus can't see when restricted apps open. Grant usage access to restore blocking.",
                    "Fix",
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            ProtectionDegradation.APP_STOPPED ->
                DegradedCopy(
                    "Protection was stopped",
                    "MyExodus was stopped from the Task Manager. Re-enable protection to resume blocking.",
                    "Re-enable",
                    actionPendingIntent(context, ProtectionActionReceiver.ACTION_REENABLE),
                )
            ProtectionDegradation.NOTIFICATIONS_OFF ->
                DegradedCopy(
                    "Protection status hidden",
                    "Notifications are off for MyExodus — open the app to see protection status.",
                    "Open app",
                    PendingIntent.getActivity(
                        context,
                        0,
                        context.packageManager.getLaunchIntentForPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            ProtectionDegradation.OVERLAY_REVOKED ->
                DegradedCopy(
                    "Blocking overlay is off",
                    "MyExodus can't display its blocking overlay. Allow 'display over other apps' to restore full blocking.",
                    "Fix",
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri(),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
        }
        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, copy.actionLabel, copy.action)
            .build()
    }

    private fun actionPendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, ProtectionActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Posts or clears the degraded alert; no-op silently when notifications are denied. */
    fun updateDegraded(context: Context, degradation: ProtectionDegradation?) {
        val manager = NotificationManagerCompat.from(context)
        if (degradation == null) {
            manager.cancel(NOTIF_DEGRADED)
            return
        }
        // POST_NOTIFICATIONS exists only on 33+; pre-33 that check reports DENIED for an
        // unknown permission, so the real gate there is areNotificationsEnabled (same
        // reasoning as ForegroundBlockerService.checkNotificationsPermission).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        manager.notify(NOTIF_DEGRADED, degraded(context, degradation))
    }
}
