package com.example.myexodus.streaks

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
import com.example.myexodus.notifications.NotificationTapTarget

/**
 * §6/§8 Step 4 milestone notification surface: the best-effort system notification for a
 * reached milestone — the splash card is the guaranteed delivery path (REQ-12). The channel
 * is created lazily at post time (creation is idempotent), so posting has no dependency on
 * any startup path having run first. POST_NOTIFICATIONS denied skips silently (§9).
 */
object StreakNotifications {

    // A channel's importance is immutable after creation, so the heads-up upgrade needs a new
    // id: the old DEFAULT channel stays behind empty rather than silently keeping this at 3.
    private const val CHANNEL_MILESTONES = "streak_milestones_v2"
    private const val CHANNEL_MILESTONES_LEGACY = "streak_milestones"

    /** Fixed id + per-milestone tag: the NotificationManager key is (tag, id), so two habits
     *  or two milestones can never replace each other's entry. The old bare stable-hash id
     *  could collide — another streak's hash, or another surface's fixed id — and one
     *  milestone would silently swallow the other's tray entry (owner warning 2026-10-23). */
    private const val NOTIF_STREAK_MILESTONE = 3001

    internal fun milestoneTag(streakId: String, milestoneDay: Int): String =
        "streak_milestone:$streakId:$milestoneDay"

    // Suppressed: the gate is NotificationAccess.canPost (the only way in), which lint can't
    // trace through a helper.
    @SuppressLint("MissingPermission")
    fun postMilestone(
        context: Context,
        streakId: String,
        milestoneDay: Int,
        habitLabel: String,
        habitId: String,
    ) {
        if (!NotificationAccess.canPost(context)) return
        context.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MILESTONES,
                    context.getString(R.string.streaks_milestone_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            deleteNotificationChannel(CHANNEL_MILESTONES_LEGACY)
        }
        // Lands on Home with the right habit displayed (multi-habit): the splash card renders
        // at the top of Home for the *displayed* habit, so the tap both switches to this
        // habit's Home and shows its card — never another habit's. Extras don't count toward
        // PendingIntent identity, so the habit rides in the data URI: two habit ids whose
        // hashes happen to match would otherwise share one PendingIntent and the later post
        // would rewrite this tap's habit extra.
        val openHome = PendingIntent.getActivity(
            context,
            habitId.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(NotificationTapTarget.habit(habitId))
                .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_HOME)
                .putExtra(MainActivity.EXTRA_START_HABIT, habitId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            milestoneTag(streakId, milestoneDay),
            NOTIF_STREAK_MILESTONE,
            NotificationCompat.Builder(context, CHANNEL_MILESTONES)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(
                    context.getString(R.string.streaks_milestone_notification_title, milestoneDay, habitLabel),
                )
                .setContentText(
                    context.getString(R.string.streaks_milestone_notification_text, milestoneDay),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openHome)
                .build(),
        )
    }
}
