package com.example.myexodus.accountability.sos

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.myexodus.MainActivity
import com.example.myexodus.R
import com.example.myexodus.accountability.sync.PartnerMessagingService
import com.example.myexodus.accountability.sync.PartnerNotifications
import com.example.myexodus.accountability.sync.PartnerNudgePush
import com.example.myexodus.notifications.NotificationAccess
import com.google.firebase.messaging.RemoteMessage
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SOS plan §12.3, notification bullets (device leg, emulator rig):
 *
 *  - Channel exists before notification post.
 *  - Foreground FCM results in one local notification.
 *  - Background FCM results in one system notification.
 *  - Fixed tag replaces existing partner notification.
 *  - Notification tap navigates to Home and consumes intent extras.
 *
 * The receive path is driven through the production [PartnerMessagingService.onMessageReceived]
 * with a crafted data-only [RemoteMessage] — the exact entry FCM invokes. The payload is
 * data-only by design (AD-11/§8.17: never a notification block, never content), so foreground
 * and background delivery land in the same callback; the "system notification" of the second
 * bullet is the tray entry this code posts, and the two bullets pin the foreground/background
 * variants separately because their preconditions differ: a foreground receipt happens with the
 * process — and startup's channel creation — already in place, while a background wake can post
 * before app startup ran (the post path must create the channel itself).
 *
 * Preconditions (rig): app installed once (MyExodusApp.onCreate has run — creates the channel);
 * POST_NOTIFICATIONS granted / appops allowed (granted here if missing); for the tap test, setup
 * completed so Home renders. Robolectric (PartnerNotificationsTest / PartnerNudgePushTest)
 * covers the shadow-level copies; this class pins the real NotificationManager, real tray, and
 * the real MainActivity routing.
 */
@RunWith(AndroidJUnit4::class)
class NudgeNotificationInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun notificationsAvailable() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        setAppNotificationsEnabled(true)
        PartnerNotifications.cancelPartnerNudge(context)
        assertTrue("POST_NOTIFICATIONS must be grantable on the rig", NotificationAccess.canPost(context))
    }

    @After
    fun clearTray() {
        PartnerNotifications.cancelPartnerNudge(context)
    }

    // --- §12.3 bullet 1 ---------------------------------------------------------------------

    @Test
    fun channel_existsBeforeAnyNotificationPost() {
        manager.deleteNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)
        assertNull(manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES))

        // The app-startup path (MyExodusApp.onCreate) — not the lazy create inside a post.
        PartnerNotifications.ensurePartnerNudgesChannel(context)

        val channel = manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)
        assertNotNull("the channel must exist before the first nudge", channel)
        assertEquals(context.getString(R.string.partner_channel_nudges_name), channel!!.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        // The channel-level lockscreenVisibility we REQUEST (VISIBILITY_PRIVATE, PartnerNotifications)
        // is pinned by the Robolectric test (PartnerNotificationsTest) — on-device the platform may
        // echo VISIBILITY_NO_OVERRIDE for every channel (X6532/XOS: -1000 for all, incl. WhatsApp's),
        // so asserting the requested value here would pin an OEM echo, not our contract.
        assertEquals("no notification may have been posted to prove the channel", 0, nudgeEntries().size)
    }

    // --- §12.3 bullets 2 + 3 ----------------------------------------------------------------

    @Test
    fun foregroundPush_postsExactlyOneLocalNotification() {
        // Foreground: startup has run, so the channel already exists before the push.
        PartnerNotifications.ensurePartnerNudgesChannel(context)
        assertNotNull(manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES))

        messagingService().onMessageReceived(nudgeMessage(UUID.randomUUID().toString()))

        val entry = nudgeEntries().single()
        assertEquals(PartnerNotifications.NOTIF_PARTNER_NUDGE, entry.id)
        assertEquals(PartnerNudgePush.TAG, entry.tag)
        assertEquals(PartnerNotifications.CHANNEL_PARTNER_NUDGES, entry.notification.channelId)
        assertEquals(
            context.getString(R.string.partner_nudge_title),
            entry.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            context.getString(R.string.partner_nudge_text),
            entry.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertTrue(
            "the entry must retire itself when tapped",
            entry.notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
        // FR-13 redaction, notification level: the posted entry itself is private (the
        // channel-level requested value is Robolectric-pinned; this is the portable device pin).
        assertEquals(Notification.VISIBILITY_PRIVATE, entry.notification.visibility)
    }

    @Test
    fun backgroundPush_postsExactlyOneSystemNotification() {
        // Background wake before app startup created the channel: the post path must create it.
        manager.deleteNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)
        assertNull(manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES))

        messagingService().onMessageReceived(nudgeMessage(UUID.randomUUID().toString()))

        val entry = nudgeEntries().single()
        assertEquals(PartnerNotifications.NOTIF_PARTNER_NUDGE, entry.id)
        assertEquals(PartnerNudgePush.TAG, entry.tag)
        assertNotNull(
            "a push arriving before startup must still land on a channel",
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES),
        )
    }

    @Test
    fun malformedNudgeId_isIgnored_neverPosted() {
        messagingService().onMessageReceived(nudgeMessage("not-a-uuid"))
        assertEquals("an invalid nudge_id must never reach the tray", 0, nudgeEntries().size)
    }

    @Test
    fun wakePush_isNotANudge_neverPosted() {
        val wake = RemoteMessage.Builder("test").addData("messageId", "m-1").build()
        messagingService().onMessageReceived(wake)
        assertEquals("the sync-wake shape must not post anything", 0, nudgeEntries().size)
    }

    // --- §12.3 bullet 4 ---------------------------------------------------------------------

    @Test
    fun fixedTag_replacesTheExistingPartnerNotification() {
        messagingService().onMessageReceived(nudgeMessage(UUID.randomUUID().toString()))
        assertEquals(1, nudgeEntries().size)

        messagingService().onMessageReceived(nudgeMessage(UUID.randomUUID().toString()))

        // FR-19: one replaceable tray entry — a second nudge must not stack a second entry.
        val entry = nudgeEntries().single()
        assertEquals(PartnerNotifications.NOTIF_PARTNER_NUDGE, entry.id)
        assertEquals(PartnerNudgePush.TAG, entry.tag)
    }

    // --- §12.3 bullet 5 ---------------------------------------------------------------------

    @Test
    fun notificationTap_navigatesHome_andConsumesIntentExtras() {
        finishAnyMainActivity()

        messagingService().onMessageReceived(nudgeMessage(UUID.randomUUID().toString()))
        nudgeEntries().single().notification.contentIntent.send()

        val activity = waitForMainActivity()
        assertNull(
            "the launcher entry must consume its type extra",
            activity.intent.getStringExtra(PartnerNudgePush.TYPE_KEY),
        )
        assertNull(
            "the launcher entry must consume its nudge_id extra",
            activity.intent.getStringExtra(PartnerNudgePush.NUDGE_ID_KEY),
        )
        val homeTitle = context.getString(R.string.home_grid_title)
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(homeTitle).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // --- driving ----------------------------------------------------------------------------

    /**
     * A real [PartnerMessagingService] whose base context is attached the way the framework
     * attaches it (`attachBaseContext` is protected API; reflective invoke is the same trick
     * EnhancedModeDeviceSuite uses for trimmed framework surfaces).
     */
    private fun messagingService(): PartnerMessagingService {
        val service = PartnerMessagingService()
        val attach = ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        attach.invoke(service, context)
        return service
    }

    private fun nudgeMessage(nudgeId: String) = RemoteMessage.Builder("test")
        .addData(PartnerNudgePush.TYPE_KEY, PartnerNudgePush.TYPE_PARTNER_NUDGE)
        .addData(PartnerNudgePush.NUDGE_ID_KEY, nudgeId)
        .build()

    // --- tray + launcher state --------------------------------------------------------------

    private fun nudgeEntries() = manager.activeNotifications.filter {
        it.tag == PartnerNudgePush.TAG || it.id == PartnerNotifications.NOTIF_PARTNER_NUDGE
    }

    private fun finishAnyMainActivity() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .forEach { it.finish() }
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline && resumedMainActivity() != null) {
            SystemClock.sleep(100)
        }
    }

    private fun resumedMainActivity(): MainActivity? {
        var found: MainActivity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull()
        }
        return found
    }

    private fun waitForMainActivity(): MainActivity {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            resumedMainActivity()?.let { return it }
            SystemClock.sleep(200)
        }
        fail("MainActivity never reached RESUMED after the notification tap")
        error("unreachable")
    }

    // --- shell ------------------------------------------------------------------------------

    private fun setAppNotificationsEnabled(enabled: Boolean) {
        shell("appops set ${context.packageName} POST_NOTIFICATION ${if (enabled) "allow" else "deny"}")
        val want = enabled
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline && NotificationAccess.canPost(context) != want) {
            SystemClock.sleep(100)
        }
        assertEquals("appops must drive the app-level notification read", want, NotificationAccess.canPost(context))
    }

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
}
