package com.example.myexodus.blocking

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.telemetry.CrashReporting
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Path A liveness derivation (regression: frozen "Limited" chip, XOS/Transsion 2026-09-19).
 * `refresh` used to AND the in-process service instance with the platform's client-side
 * enabled list; that list flaps on OEM builds — it read empty while the service was bound and
 * delivering window events — freezing the Home chip on a false "Limited". Liveness is now the
 * in-process instance alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtectionStatusMonitorAuthorityTest {

    private lateinit var context: Context
    private lateinit var monitor: ProtectionStatusMonitor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EnhancedModeSettings(context).enhancedModeEnabled = true
        monitor = ProtectionStatusMonitor(
            appContext = context,
            settings = EnhancedModeSettings(context),
            store = ProtectionStatusStore(context),
            crashReporting = CrashReporting(context),
        )
    }

    @After
    fun tearDown() {
        BlockerAccessibilityService.connected.set(false)
    }

    private fun setPlatformEnabledList(containsBlocker: Boolean) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val services = if (containsBlocker) {
            listOf(
                TestServiceInfo.accessibilityService(
                    context.packageName,
                    BlockerAccessibilityService::class.java.name,
                ),
            )
        } else {
            emptyList()
        }
        shadowOf(manager).setEnabledAccessibilityServiceList(services)
    }

    @Test
    fun liveInstance_isFull_evenWhilePlatformListReadsEmpty() {
        // The device-caught shape: no entry in the client-side list, yet the service object is
        // alive in this process and delivering events (dumpsys: Enabled + Bound).
        setPlatformEnabledList(containsBlocker = false)
        BlockerAccessibilityService.connected.set(true)
        assertEquals(ProtectionAuthority.FULL, monitor.refresh())
    }

    @Test
    fun platformListAlone_isNotFull_whenNoInstanceIsAlive() {
        // The inverse direction stays exact: a listed-but-dead service is not Path A — a
        // disable/crash unbinds and clears the in-process flag.
        setPlatformEnabledList(containsBlocker = true)
        BlockerAccessibilityService.connected.set(false)
        assertNotEquals(ProtectionAuthority.FULL, monitor.refresh())
    }
}
