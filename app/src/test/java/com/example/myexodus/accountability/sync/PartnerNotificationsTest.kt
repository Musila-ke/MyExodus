package com.example.myexodus.accountability.sync

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Plan §12 line 523 (notification-permission-denied path: banner appears, no crash when a
 * notification is attempted while denied) and §8 Step 16's channel spec: `partner_alerts`
 * HIGH, `approval_requests` HIGH, `sync_status` LOW + debug-only. The banner itself is a
 * composition; the device leg covers it — this pins the posting gate and channel shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerNotificationsTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    @Test
    fun deniedPermission_postIsSilentNoOp() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PartnerNotifications.notifyPartnerEvent(context)
        assertTrue(manager.activeNotifications.isEmpty())
        assertNull(manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_ALERTS))
    }

    @Test
    fun granted_partnerAlertsChannelIsHighImportance() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PartnerNotifications.notifyPartnerEvent(context)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_ALERTS)!!.importance,
        )
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test
    fun granted_approvalRequestsChannelIsHighImportance() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PartnerNotifications.notifyApprovalRequest(context)
        PartnerNotifications.notifyApprovalDecision(context)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_APPROVAL_REQUESTS)!!.importance,
        )
        assertEquals(2, manager.activeNotifications.size)
    }

    /**
     * §11.7 burst coalescing: a pull can append several events at once, and one fixed id already
     * collapses them into a single tray entry — `onlyAlertOnce` keeps that burst to one *alert*
     * on the partner-alert channel. Approval items must still alert every time (they are
     * actionable and never burst), so the flag is per-call, not global.
     */
    @Test
    fun partnerEvent_alertOnceDuringABurst_butApprovalAlwaysAlerts() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        PartnerNotifications.notifyPartnerEvent(context)
        PartnerNotifications.notifyApprovalRequest(context)

        val partnerEvent = manager.activeNotifications.first { it.id == PartnerNotifications.NOTIF_PARTNER_EVENT }
        assertTrue(
            "partner-activity bursts alert once",
            partnerEvent.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
        )
        val approval = manager.activeNotifications.first { it.id == PartnerNotifications.NOTIF_APPROVAL_REQUEST }
        assertEquals(
            "approval requests never suppress the alert",
            0,
            approval.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE,
        )
    }

    /** FR-13 / NFR2: the lockscreen shows a generic copy rather than the app's own text, and the
     *  content is private so the system does not fall back to rendering it in full. */
    @Test
    fun partnerEvent_isPrivateWithAGenericPublicVersion() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        PartnerNotifications.notifyPartnerEvent(context)

        val notification = manager.activeNotifications.single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue("lockscreen renders the generic public copy", notification.publicVersion != null)
    }

    @Test
    fun syncStatusChannel_isLowImportance() {
        // Unit tests run the debug variant (BuildConfig.DEBUG = true), so the debug gate
        // passes here; in release the function returns before any channel is created.
        PartnerNotifications.ensureSyncStatusChannel(context)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_SYNC_STATUS)!!.importance,
        )
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
