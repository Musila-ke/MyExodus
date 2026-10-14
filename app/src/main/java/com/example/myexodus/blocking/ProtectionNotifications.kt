package com.example.myexodus.blocking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myexodus.MainActivity
import com.example.myexodus.R
import com.example.myexodus.notifications.NotificationAccess
import com.example.myexodus.ui.formatDuration

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
    const val NOTIF_OUTAGE = 1003

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.protection_channel_status_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.protection_channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    fun ongoing(context: Context): Notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(context.getString(R.string.protection_ongoing_title))
        .setContentText(context.getString(R.string.protection_ongoing_text))
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
            // §8 Step 6: the cause is deliberately not named — with both paths down it is
            // usually more than one thing, and the app's Settings protection rows name each
            // mechanism (and its fix) exactly. Never a false "app blocking is off" claim
            // while Path A is healthy.
            ProtectionDegradation.DEGRADED_NO_DETECTION ->
                DegradedCopy(
                    context.getString(R.string.protection_paused_title),
                    context.getString(R.string.protection_paused_text),
                    context.getString(R.string.protection_paused_action),
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            ProtectionDegradation.APP_STOPPED ->
                DegradedCopy(
                    context.getString(R.string.protection_stopped_title),
                    // Same honest-loss copy as the Home banner (regression finding #1):
                    // "resume blocking" was false while Path A is live after a stop.
                    context.getString(R.string.protection_stopped_text),
                    context.getString(R.string.protection_stopped_action),
                    actionPendingIntent(context, ProtectionActionReceiver.ACTION_REENABLE),
                )
            ProtectionDegradation.NOTIFICATIONS_OFF ->
                DegradedCopy(
                    context.getString(R.string.protection_hidden_title),
                    context.getString(R.string.protection_hidden_text),
                    context.getString(R.string.protection_hidden_action),
                    PendingIntent.getActivity(
                        context,
                        0,
                        context.packageManager.getLaunchIntentForPackage(context.packageName),
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
            // The monitor re-posts this on every refresh so it can never be silently lost
            // (package update / swipe); alert only when the alert is new, not on re-asserts.
            .setOnlyAlertOnce(true)
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
    // Suppressed: the gate is NotificationAccess.canPost (the only way in), which lint can't
    // trace through a helper.
    @SuppressLint("MissingPermission")
    fun updateDegraded(context: Context, degradation: ProtectionDegradation?) {
        val manager = NotificationManagerCompat.from(context)
        if (degradation == null) {
            manager.cancel(NOTIF_DEGRADED)
            return
        }
        if (!NotificationAccess.canPost(context)) return
        manager.notify(NOTIF_DEGRADED, degraded(context, degradation))
    }

    /**
     * D1's outage notification (§8.7): the measured interruption stated as a fact — how long
     * tracking went unobserved, never why (AD-13). "Fix" opens Settings and consumes the offer;
     * "Not now" records the decline. Nothing is cancelled by opening the app: the offer stands
     * until it is answered, declined, or ages out of the freshness window.
     */
    fun outage(context: Context, largestWindowMs: Long): Notification {
        val openSettings = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val text = context.getString(
            R.string.outage_notification_text,
            formatDuration(largestWindowMs),
        )
        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.outage_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, openSettings,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            // "Fix" is an activity PendingIntent, not a broadcast: the request itself is fired
            // from the Settings row the user lands on, so nothing here has to start an activity
            // from the background (the API-29+ launch restriction).
            .addAction(
                0, context.getString(R.string.outage_action_fix),
                PendingIntent.getActivity(
                    context, 1, openSettings,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0, context.getString(R.string.outage_action_later),
                actionPendingIntent(context, ProtectionActionReceiver.ACTION_OUTAGE_DECLINED),
            )
            .build()
    }

    /** Posts or clears the outage notification; no-op silently when notifications are denied. */
    // Suppressed for the same reason as updateDegraded: the gate is NotificationAccess.canPost.
    @SuppressLint("MissingPermission")
    fun updateOutage(context: Context, largestWindowMs: Long?) {
        val manager = NotificationManagerCompat.from(context)
        if (largestWindowMs == null) {
            manager.cancel(NOTIF_OUTAGE)
            return
        }
        if (!NotificationAccess.canPost(context)) return
        manager.notify(NOTIF_OUTAGE, outage(context, largestWindowMs))
    }
}
