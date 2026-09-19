package com.example.myexodus.accountability.sync

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myexodus.BuildConfig
import com.example.myexodus.MainActivity
import com.example.myexodus.R
import com.example.myexodus.notifications.NotificationAccess

/**
 * Partner-event notification surface (AD-11/NFR2): rendered locally only AFTER the mailbox
 * envelope was verified and appended — the tray text is deliberately generic (no alias, no
 * event type; detail lives behind opening the app, and the alias arrives with the Step 12
 * dashboard). Channels are created lazily at post time (creation is idempotent); denied
 * POST_NOTIFICATIONS skips silently (Step 16 owns the permission flow + banner).
 */
object PartnerNotifications {

    const val CHANNEL_PARTNER_ALERTS = "partner_alerts"
    const val NOTIF_PARTNER_EVENT = 2001

    /** §8 Step 12 disable-approval pair — own channel so a user can silence decisions
     *  separately from activity alerts (plan §8 Step 16's channel list). */
    const val CHANNEL_APPROVAL_REQUESTS = "approval_requests"
    const val NOTIF_APPROVAL_REQUEST = 2002
    const val NOTIF_APPROVAL_DECISION = 2003

    /** Plan §8 Step 16's third channel: LOW importance, "used only for internal/debug
     *  visibility, not shown to the user in production builds" — so the channel is created
     *  only in debug builds. No production surface posts to it by design. */
    const val CHANNEL_SYNC_STATUS = "sync_status"

    fun ensureSyncStatusChannel(context: Context) {
        if (!BuildConfig.DEBUG) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC_STATUS, "MyExodus sync status", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun notifyPartnerEvent(context: Context) =
        post(context, CHANNEL_PARTNER_ALERTS, "MyExodus partner activity", NotificationManager.IMPORTANCE_HIGH, NOTIF_PARTNER_EVENT, R.string.partner_event_title, R.string.partner_event_text)

    /** A verified disable-approval request arrived — the responder's actionable item.
     *  Plan Step 16 channel spec: `approval_requests` is HIGH importance. */
    fun notifyApprovalRequest(context: Context) =
        post(context, CHANNEL_APPROVAL_REQUESTS, "MyExodus partner requests", NotificationManager.IMPORTANCE_HIGH, NOTIF_APPROVAL_REQUEST, R.string.approval_request_title, R.string.approval_request_text)

    /** The partner answered our request — informational. */
    fun notifyApprovalDecision(context: Context) =
        post(context, CHANNEL_APPROVAL_REQUESTS, "MyExodus partner requests", NotificationManager.IMPORTANCE_HIGH, NOTIF_APPROVAL_DECISION, R.string.approval_decision_title, R.string.approval_decision_text)

    // Suppressed: the gate is NotificationAccess.canPost (the only way in), which lint can't
    // trace through a helper.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, channelId: String, channelName: String, importance: Int, notificationId: Int, titleRes: Int, textRes: Int) {
        if (!NotificationAccess.canPost(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, channelName, importance),
        )
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            notificationId,
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(titleRes))
                .setContentText(context.getString(textRes))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build(),
        )
    }
}
