package com.example.myexodus.blocking

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.advancedprotection.AdvancedProtectionManager
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.example.myexodus.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Enhanced Mode health check (plan §8 Step 10): detects when the blocker accessibility service
 * has been disabled and alerts the user. Detection is eventual by design — the WorkManager
 * periodic tick (~15 min, covers a dead process) and the AccessibilityStateChangeListener (a
 * live process) both feed [refresh]. Alerts once per enabled→disabled transition, never
 * re-enables the service (not possible without user action; §9).
 */
@Singleton
class EnhancedModeHealthMonitor @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    // Lazy: constructed with the Hilt graph at process start, which can be pre-unlock
    // (LOCKED_BOOT_COMPLETED, plan §8.5) — CE SharedPreferences throw until first unlock.
    private val prefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _enabled = MutableStateFlow(false)
    private val _advancedProtection = MutableStateFlow(false)
    private val handler = Handler(Looper.getMainLooper())
    private val alertPending = AtomicBoolean(false)

    /** True while this service is in the platform's enabled-accessibility list. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** True when AAPM is active (API 36+): the platform revokes the accessibility grant and
     *  refuses a re-grant, so the Protection pane explains why instead of offering a dead Fix. */
    val advancedProtection: StateFlow<Boolean> = _advancedProtection.asStateFlow()

    init {
        // Records the initial state so the first real transition is detected, not compared
        // against the prefs default (which would silently miss an already-enable→disable turn).
        refresh()
    }

    /**
     * Re-reads platform state (service enabled + AAPM, plan §8 Step 12); alerts once on the
     * enabled→disabled transition. Called from the worker tick, the state-change listener,
     * onServiceConnected, and the app UI on entry.
     */
    fun refresh() {
        // Pre-unlock process start (LOCKED_BOOT_COMPLETED, plan §8.5): CE SharedPreferences
        // throw until first unlock. Nothing to detect pre-unlock — the baseline is recorded
        // on the first post-unlock refresh (state-change listener, worker tick, app entry).
        val prefs = try {
            this.prefs
        } catch (e: IllegalStateException) {
            return
        }
        val now = isServiceEnabled()
        val was = prefs.getBoolean(KEY_WAS_ENABLED, false)
        if (now != was) {
            if (now) {
                prefs.edit { putBoolean(KEY_WAS_ENABLED, true) }
            } else {
                scheduleDisabledAlert()
            }
        }
        // Unconditional on enabled, so a re-enable the process never observed live
        // (Settings toggle while dead) still clears the alert — it otherwise sat in the
        // shade claiming "blocker is off" next to the running "Protection active" FGS
        // notification (caught 2026-09-13).
        if (now) cancelDisabled()
        _enabled.value = now
        _advancedProtection.value = isAdvancedProtectionEnabled()
    }

    /**
     * "Enabled" = listed by the platform OR alive in this process. The client-side enabled list
     * flaps on OEM builds — device-caught on XOS/Transsion 2026-09-19: it read empty while the
     * service was bound and delivering window events. A live instance in this process is ground
     * truth for "the service is up"; a real disable always unbinds it, so the enabled→disabled
     * transition above is unaffected and a flaky read can no longer fire its alert.
     */
    fun isServiceEnabled(): Boolean = BlockerAccessibilityService.connected.get() || appContext
        .getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo?.serviceInfo?.name == BlockerAccessibilityService::class.java.name }

    private fun isAdvancedProtectionEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            return appContext.getSystemService(AdvancedProtectionManager::class.java)
                ?.isAdvancedProtectionEnabled() ?: false
        }
        return false
    }

    private fun cancelDisabled() {
        appContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /**
     * Delayed re-check before alerting: an APK update briefly unbinds the service while the new
     * process starts (device-caught 2026-10-29 — false alert 4.6 s before it reconnected), which
     * read as a genuine disable. The transition is only committed to prefs when the re-check
     * still finds it off, so a process death during the window re-detects on the next start.
     */
    private fun scheduleDisabledAlert() {
        if (!alertPending.compareAndSet(false, true)) return
        handler.postDelayed({
            alertPending.set(false)
            if (isServiceEnabled()) return@postDelayed
            prefs.edit { putBoolean(KEY_WAS_ENABLED, false) }
            notifyDisabled()
        }, DISABLED_ALERT_DELAY_MILLIS)
    }

    private fun notifyDisabled() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        ensureChannel(appContext)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(appContext.getString(R.string.enhanced_mode_health_disabled_title))
            .setContentText(appContext.getString(R.string.enhanced_mode_health_disabled_text))
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    internal companion object {
        const val PREFS_NAME = "enhanced_mode_health"
        const val KEY_WAS_ENABLED = "was_enabled"
        // Versioned: importance cannot be raised on an existing channel (owner report
        // 2026-10-29 — this alert stayed in the tray).
        const val CHANNEL_ID = "enhanced_mode_health_v1"
        const val CHANNEL_ID_LEGACY = "enhanced_mode_health"
        // 1004: distinct from FGS (1001), degraded (1002), and outage (1003) — a shared id
        // replaces the other alert in the shade (finding #9, 2026-10-16: this carried 1003,
        // which the later outage surface also took; a swallowed transition alert never
        // re-posts because refresh() only notifies on a fresh enabled→disabled turn).
        // Guarded by ProtectionNotificationIdsTest.
        const val NOTIFICATION_ID = 1004
        // Longer than any observed update/reboot re-bind (4.6 s on X6532); the fallback keeps
        // blocking either way, so the alert is not time-critical.
        internal const val DISABLED_ALERT_DELAY_MILLIS = 15_000L

        /** Created at startup so system settings never lists the retired
         *  `enhanced_mode_health` id beside this one (owner report 2026-10-31: duplicate
         *  channel entries). Create-only. */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.enhanced_mode_health_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            manager.deleteNotificationChannel(CHANNEL_ID_LEGACY)
        }
    }
}
