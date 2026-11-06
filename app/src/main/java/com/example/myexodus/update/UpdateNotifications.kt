package com.example.myexodus.update

import android.annotation.SuppressLint
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

/**
 * Plan §8.7 + 2026-11-06 revision: neutral update posts. Neither version carries personal
 * activity, partner context, or account state.
 */
internal object UpdateNotifications {
    private const val CHANNEL_ID = "update_availability"
    private const val ID_AVAILABLE = 4100
    private const val ID_READY = 4101

    fun notifyAvailable(context: Context, versionName: String) {
        post(
            context = context,
            id = ID_AVAILABLE,
            title = context.getString(R.string.update_notification_available_title),
            text = context.getString(R.string.update_notification_available_body, versionName),
        )
    }

    fun notifyReady(context: Context, versionName: String) {
        post(
            context = context,
            id = ID_READY,
            title = context.getString(R.string.update_notification_ready_title),
            text = context.getString(R.string.update_notification_ready_body, versionName),
        )
    }

    // Suppressed: the gate is NotificationAccess.canPost (the only way in), which lint can't
    // trace through a helper.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, title: String, text: String) {
        if (!NotificationAccess.canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .putExtra(MainActivity.EXTRA_OPEN_UPDATE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .build(),
            )
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
