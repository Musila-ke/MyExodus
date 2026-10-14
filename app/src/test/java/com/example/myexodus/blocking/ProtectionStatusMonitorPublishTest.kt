package com.example.myexodus.blocking

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.telemetry.CrashReporting
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * publish()'s notification half (device-caught 2026-10-14): the degraded alert is re-asserted
 * on every refresh. It used to sit behind the same change gate as the store write, so a
 * notification cleared out-of-band — a package update (the app's only distribution channel)
 * or a swipe — could never return while enforcement stayed paused: the stored value never
 * changed, and nothing re-posted it. The store write and transition log remain change-gated;
 * only the post is unconditional, silent on re-asserts via ONLY_ALERT_ONCE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtectionStatusMonitorPublishTest {

    private lateinit var context: Context
    private lateinit var store: ProtectionStatusStore
    private lateinit var monitor: ProtectionStatusMonitor

    private val notifManager get() = context.getSystemService(NotificationManager::class.java)
    private fun posted(id: Int) = shadowOf(notifManager).getNotification(id)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        EnhancedModeSettings(context).enhancedModeEnabled = true
        store = ProtectionStatusStore(context)
        monitor = ProtectionStatusMonitor(
            appContext = context,
            settings = EnhancedModeSettings(context),
            store = store,
            crashReporting = CrashReporting(context),
        )
    }

    @After
    fun tearDown() {
        // The in-process liveness flag is a process-global; never leak it across tests.
        BlockerAccessibilityService.connected.set(false)
    }

    @Test
    fun unchangedDegradedState_repostsClearedNotification() {
        store.requiresExplicitReEnable = true
        monitor.refresh()
        assertEquals(ProtectionDegradation.APP_STOPPED, store.degradation)
        assertNotNull(posted(ProtectionNotifications.NOTIF_DEGRADED))

        // The out-of-band clear: state persists in the store, the notification is gone.
        notifManager.cancel(ProtectionNotifications.NOTIF_DEGRADED)
        assertNull(posted(ProtectionNotifications.NOTIF_DEGRADED))

        monitor.refresh()
        assertNotNull(
            "an unchanged degraded state must re-post its alert",
            posted(ProtectionNotifications.NOTIF_DEGRADED),
        )
    }

    @Test
    fun healthyRefresh_postsNothing() {
        monitor.refresh()
        assertNull(store.degradation)
        assertNull(posted(ProtectionNotifications.NOTIF_DEGRADED))
    }

    @Test
    fun degradationFlow_tracksPublishAndClear() {
        // The Home banner collects this flow: a detection landing after the UI's resume read
        // must still reach the screen in-session (device-caught 2026-10-14 — banner only
        // appeared on the next resume/relaunch).
        assertNull(monitor.degradation.value)

        store.requiresExplicitReEnable = true
        monitor.refresh()
        assertEquals(ProtectionDegradation.APP_STOPPED, monitor.degradation.value)

        // Path A alive → clearing the flag leaves no other degraded reason.
        BlockerAccessibilityService.connected.set(true)
        store.reenable()
        monitor.refresh()
        assertNull(monitor.degradation.value)
    }

    @Test
    fun degradedNotification_isOnlyAlertOnce() {
        store.requiresExplicitReEnable = true
        monitor.refresh()

        val notification = posted(ProtectionNotifications.NOTIF_DEGRADED)
        assertNotNull(notification)
        assertEquals(
            "re-asserts must stay silent — no re-alert loop on the 30s refresh cycle",
            Notification.FLAG_ONLY_ALERT_ONCE,
            notification!!.flags and Notification.FLAG_ONLY_ALERT_ONCE,
        )
    }
}
