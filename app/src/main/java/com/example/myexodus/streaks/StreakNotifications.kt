package com.example.myexodus.streaks

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.myexodus.MainActivity
import com.example.myexodus.R

/**
 * §6/§8 Step 4 milestone notification surface: the best-effort system notification for a
 * reached milestone — the splash card is the guaranteed delivery path (REQ-12). The channel
 * is created lazily at post time (creation is idempotent), so posting has no dependency on
 * any startup path having run first. POST_NOTIFICATIONS denied skips silently (§9).
 */
object StreakNotifications {

    private const val CHANNEL_MILESTONES = "streak_milestones"

    /** §9: denied is silent — the splash card is the guaranteed path. Callers that claim the
     *  notification slot first (the §14 delivery counter) must check this before claiming.
     *  POST_NOTIFICATIONS exists only on 33+; pre-33 a raw checkSelfPermission reports DENIED
     *  for an unknown permission (a false "denied" on every pre-33 device), so the real gate
     *  there is areNotificationsEnabled — same pattern as ForegroundBlockerService. */
    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    // Suppressed: the gate is canPost (the only way in), which lint can't trace through a helper.
    @SuppressLint("MissingPermission")
    fun postMilestone(
        context: Context,
        streakId: String,
        milestoneDay: Int,
        habitLabel: String,
        habitId: String,
    ) {
        if (!canPost(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_MILESTONES,
                "MyExodus milestones",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        // Lands on Home with the right habit displayed (multi-habit): the splash card renders
        // at the top of Home for the *displayed* habit, so the tap both switches to this
        // habit's Home and shows its card — never another habit's. The request code is
        // per-habit (extras don't count toward PendingIntent identity), so FLAG_UPDATE_CURRENT
        // from a later post for another habit cannot rewrite this notification's extras.
        val openHome = PendingIntent.getActivity(
            context,
            habitId.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_HOME)
                .putExtra(MainActivity.EXTRA_START_HABIT, habitId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            notificationIdFor(streakId, milestoneDay),
            NotificationCompat.Builder(context, CHANNEL_MILESTONES)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Day $milestoneDay free from $habitLabel")
                .setContentText("Your streak hit $milestoneDay days. Open MyExodus to keep it going.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openHome)
                .build(),
        )
    }

    /** Same stable-hash identity as the alarm PendingIntent (§8 Step 4): streaks never collide. */
    private fun notificationIdFor(streakId: String, milestoneDay: Int): Int =
        (streakId.hashCode() * 31 + milestoneDay) and 0x7FFFFFFF
}
