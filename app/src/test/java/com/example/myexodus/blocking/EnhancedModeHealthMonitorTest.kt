package com.example.myexodus.blocking

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnhancedModeHealthMonitorTest {

    private lateinit var context: Context
    private lateinit var accessibilityManager: AccessibilityManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    }

    private fun setEnabledServices(vararg names: String) {
        shadowOf(accessibilityManager).setEnabledAccessibilityServiceList(
            names.map { TestServiceInfo.accessibilityService(context.packageName, it) },
        )
    }

    @After
    fun tearDown() {
        // The in-process liveness flag is a process-global; never leak it across tests.
        BlockerAccessibilityService.connected.set(false)
    }

    private fun notificationCount() =
        shadowOf(context.getSystemService(NotificationManager::class.java)).size()

    /** The disabled alert is posted only after the delayed re-check fires. */
    private fun fireDisabledAlertRecheck() {
        shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(EnhancedModeHealthMonitor.DISABLED_ALERT_DELAY_MILLIS + 1))
    }

    @Test
    fun enabledService_isDetected() {
        setEnabledServices(BlockerAccessibilityService::class.java.name)
        assertTrue(EnhancedModeHealthMonitor(context).enabled.value)
    }

    @Test
    fun otherServices_doNotCount() {
        setEnabledServices("com.other.app.OtherAccessibilityService")
        assertFalse(EnhancedModeHealthMonitor(context).enabled.value)
    }

    @Test
    fun disableTransition_alertsOnce() {
        setEnabledServices(BlockerAccessibilityService::class.java.name)
        val monitor = EnhancedModeHealthMonitor(context)
        assertEquals(0, notificationCount())

        setEnabledServices()
        monitor.refresh()
        assertFalse(monitor.enabled.value)
        assertEquals(0, notificationCount())

        fireDisabledAlertRecheck()
        assertEquals(1, notificationCount())

        // No re-alert on subsequent checks while still disabled.
        monitor.refresh()
        fireDisabledAlertRecheck()
        assertEquals(1, notificationCount())
    }

    @Test
    fun transientUnbind_reconnectBeforeRecheck_doesNotAlert() {
        // APK update on X6532 2026-10-29: the service was unbound for ~4.6 s while the new
        // process started; that must not count as a disable.
        setEnabledServices(BlockerAccessibilityService::class.java.name)
        val monitor = EnhancedModeHealthMonitor(context)

        setEnabledServices()
        monitor.refresh()

        setEnabledServices(BlockerAccessibilityService::class.java.name)
        monitor.refresh()
        fireDisabledAlertRecheck()
        assertEquals(0, notificationCount())
    }

    @Test
    fun reEnable_cancelsTheStaleAlert() {
        setEnabledServices(BlockerAccessibilityService::class.java.name)
        val monitor = EnhancedModeHealthMonitor(context)
        setEnabledServices()
        monitor.refresh()
        fireDisabledAlertRecheck()
        assertEquals(1, notificationCount())

        // The alert claims "blocker is off" — once it is back on, it must leave the shade.
        setEnabledServices(BlockerAccessibilityService::class.java.name)
        monitor.refresh()
        assertTrue(monitor.enabled.value)
        assertEquals(0, notificationCount())
    }

    @Test
    fun neverEnabled_firstCheck_doesNotNotify() {
        setEnabledServices()
        val monitor = EnhancedModeHealthMonitor(context)
        monitor.refresh()
        assertEquals(0, notificationCount())
    }

    @Test
    fun advancedProtection_isFalseBelowApi36() {
        // API 34 runtime: AdvancedProtectionManager doesn't exist; must be false, not a crash.
        val monitor = EnhancedModeHealthMonitor(context)
        assertFalse(monitor.advancedProtection.value)
        monitor.refresh()
        assertFalse(monitor.advancedProtection.value)
    }

    @Test
    fun liveInstance_countsAsEnabled_evenWhilePlatformListReadsEmpty() {
        // XOS/Transsion 2026-09-19: the client-side list read empty while the service was
        // bound and delivering events; that must not render as "blocker off".
        setEnabledServices()
        BlockerAccessibilityService.connected.set(true)
        val monitor = EnhancedModeHealthMonitor(context)
        assertTrue(monitor.enabled.value)
        assertEquals(0, notificationCount())
    }

    @Test
    fun flakyListDrop_doesNotAlert_butGenuineDisableDoes() {
        setEnabledServices(BlockerAccessibilityService::class.java.name)
        val monitor = EnhancedModeHealthMonitor(context)
        assertEquals(0, notificationCount())

        // Flaky read: the list drops the service while the instance stays alive — not a disable.
        setEnabledServices()
        BlockerAccessibilityService.connected.set(true)
        monitor.refresh()
        assertTrue(monitor.enabled.value)
        assertEquals(0, notificationCount())

        // The genuine disable: the instance dies.
        BlockerAccessibilityService.connected.set(false)
        monitor.refresh()
        assertFalse(monitor.enabled.value)
        assertEquals(0, notificationCount())
        fireDisabledAlertRecheck()
        assertEquals(1, notificationCount())
    }
}
