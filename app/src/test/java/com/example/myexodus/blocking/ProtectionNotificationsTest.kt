package com.example.myexodus.blocking

import android.app.Application
import android.content.Context
import com.example.myexodus.MainActivity
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Owner warning 2026-10-23 ("we don't want collisions"): every surface's tap used request code
 * 0 on a bare MainActivity intent. PendingIntent identity ignores extras, so the protection
 * alert (Settings) and the VPN/domain-usage alerts (Rules) shared ONE PendingIntent and the
 * latest post rewrote the others' tab extra. Each surface now carries its own data URI —
 * pinned here for the two protection alerts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProtectionNotificationsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun degradedAlert_tapIsItsOwnIdentityAndLandsOnSettings() {
        val degraded = ProtectionNotifications.degraded(
            context, ProtectionDegradation.DEGRADED_NO_DETECTION,
        )
        val action = NotificationCompat.getAction(degraded, 0)!!
        val tap = shadowOf(action.actionIntent).savedIntent

        assertEquals("myexodus://notification/protection-degraded", tap.data.toString())
        assertEquals(MainActivity.TAB_SETTINGS, tap.getStringExtra(MainActivity.EXTRA_START_TAB))
    }

    @Test
    fun degradedAndOutageTaps_neverShareAnIdentity() {
        val degraded = ProtectionNotifications.degraded(
            context, ProtectionDegradation.DEGRADED_NO_DETECTION,
        )
        val outage = ProtectionNotifications.outage(context, 60_000L)
        val degradedIntent = NotificationCompat.getAction(degraded, 0)!!.actionIntent
        val outageIntent = outage.contentIntent
        val outageTap = shadowOf(outageIntent).savedIntent

        assertEquals("myexodus://notification/protection-outage", outageTap.data.toString())
        assertFalse(
            "same request code + same target, so identity must come from the data URI",
            shadowOf(degradedIntent).savedIntent.filterEquals(outageTap),
        )
    }
}
