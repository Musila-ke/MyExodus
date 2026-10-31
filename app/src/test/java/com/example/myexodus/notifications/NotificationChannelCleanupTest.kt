package com.example.myexodus.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.domainusage.AndroidDomainUsageHost
import com.example.myexodus.streaks.StreakNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner report 2026-10-31: system settings listed retired channel ids beside their versioned
 * replacements, because each owner deleted its legacy id only on the new id's first post.
 * `MyExodusApp` now runs every ensure at startup; this pins the sweep and the create-only rule
 * (re-ensuring must never rewrite an importance the user lowered).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationChannelCleanupTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun startupEnsure_retiresEveryLegacyChannel_andCreatesTheCurrentOnesHigh() {
        listOf("streak_milestones", "domain_usage_status", "enhanced_mode_health").forEach { id ->
            manager.createNotificationChannel(
                NotificationChannel(id, "retired", NotificationManager.IMPORTANCE_LOW),
            )
        }

        StreakNotifications.ensureMilestonesChannel(context)
        AndroidDomainUsageHost.ensureStatusChannel(context)
        EnhancedModeHealthMonitor.ensureChannel(context)

        listOf("streak_milestones", "domain_usage_status", "enhanced_mode_health").forEach { id ->
            assertNull("the retired $id must not survive startup", manager.getNotificationChannel(id))
        }
        listOf("streak_milestones_v2", "domain_usage_status_v1", "enhanced_mode_health_v1")
            .forEach { id ->
                assertEquals(
                    "$id must exist at HIGH so its alert banners",
                    NotificationManager.IMPORTANCE_HIGH,
                    manager.getNotificationChannel(id)!!.importance,
                )
            }
    }

    @Test
    fun ensure_isCreateOnly_neverRaisesOrRewritesAnExistingChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                "domain_usage_status_v1",
                "user-lowered",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        AndroidDomainUsageHost.ensureStatusChannel(context)

        assertEquals(
            "re-ensuring must never rewrite the user's chosen importance",
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel("domain_usage_status_v1")!!.importance,
        )
    }
}
