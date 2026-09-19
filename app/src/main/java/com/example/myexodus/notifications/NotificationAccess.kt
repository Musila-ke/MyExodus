package com.example.myexodus.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * The single post-gate for every notification surface (streaks, protection, partner).
 * POST_NOTIFICATIONS exists only on 33+; pre-33 a raw checkSelfPermission reports DENIED for
 * an unknown permission (a false "denied" on every pre-33 device), so the real gate there is
 * areNotificationsEnabled — same reasoning as EnforcementForegroundService. Denied is silent:
 * every surface has its own guaranteed in-app path.
 */
object NotificationAccess {

    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
}
