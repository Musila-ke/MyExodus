package com.example.myexodus.accountability.sos

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.myexodus.MainActivity
import com.example.myexodus.R
import com.example.myexodus.accountability.sync.PartnerMessagingService
import com.example.myexodus.accountability.sync.PartnerNotifications
import com.example.myexodus.accountability.sync.PartnerNudgePush
import com.example.myexodus.notifications.NotificationAccess
import com.example.myexodus.settings.SetupStateStore
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
 * data-only by design (AD-11/§8.17; D16 adds `pair_id` for local naming), so foreground and
 * background delivery land in the same callback; the "system notification" of the second
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

    private val setupState: SetupStateStore get() = SetupStateStore(context)

    private companion object {
        const val SETTLE_ID = 9001
    }

    private var setupSeenBefore = false

    @Before
    fun notificationsAvailable() {
        // The tap bullet asserts Home renders (the class precondition). Set the setup-complete
        // marker rather than depending on ambient device state — a clean AVD otherwise boots
        // the protection wizard and never composes Home. Restored in @After.
        setupSeenBefore = setupState.setupSeen
        setupState.setupSeen = true
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        setAppNotificationsEnabled(true)
        assertTrue("the notification tray did not settle before the test", settleNudgeTray())
        assertTrue("POST_NOTIFICATIONS must be grantable on the rig", NotificationAccess.canPost(context))
    }

    @After
    fun clearTray() {
        settleNudgeTray()
        setupState.setupSeen = setupSeenBefore
    }

    /**
     * Drains the nudge tray and waits until the cancel is *processed*, not merely enqueued.
     *
     * A cancel is only observable while an entry exists. When the tap leg already consumed its
     * entry, the old drain loop exited instantly and the still-queued cancel later evicted the
     * NEXT test's post — the 2026-10-31 full-suite failure at line 167 (cancel submitted at
     * 03.314, next post at 03.389, NotificationService processed the cancel at 03.743). The
     * barrier is a canary posted through the same manager after the cancel: the
     * NotificationService handler runs a process's calls in order, so the canary appearing
     * proves every earlier cancel for this app has been processed.
     *
     * Every attempt uses a fresh tag (and its own title): a late cancel of a previous canary
     * targets THAT key only. The shared fixed key was itself evicting the next settle's canary
     * (NotificationService logged "Cannot find enqueued record" for the key right after it had
     * been seen — the 2026-10-31 class-run settle failures). The canary never counts as a
     * nudge ([nudgeEntries]) and lives on the silent debug-only sync channel.
     */
    private fun settleNudgeTray(): Boolean {
        PartnerNotifications.cancelPartnerNudge(context)
        PartnerNotifications.ensureSyncStatusChannel(context)
        repeat(3) {
            val key = "test:tray-settle-${UUID.randomUUID()}"
            manager.notify(
                key,
                SETTLE_ID,
                NotificationCompat.Builder(context, PartnerNotifications.CHANNEL_SYNC_STATUS)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(key)
                    .build(),
            )
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var seen = false
            while (!seen && SystemClock.elapsedRealtime() < deadline) {
                seen = manager.activeNotifications.any {
                    it.tag == key && it.id == SETTLE_ID &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                            ?.toString() == key
                }
                if (!seen) SystemClock.sleep(50)
            }
            manager.cancel(key, SETTLE_ID)
            if (seen) return true
        }
        return false
    }

    // --- §12.3 bullet 1 ---------------------------------------------------------------------

    @Test
    fun channel_existsBeforeAnyNotificationPost() {
        manager.deleteNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)
        awaitChannelDeleted(PartnerNotifications.CHANNEL_PARTNER_NUDGES)
        assertNull(manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES))

        // The app-startup path (MyExodusApp.onCreate) — not the lazy create inside a post.
        PartnerNotifications.ensurePartnerNudgesChannel(context)

        val channel = manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)
        assertNotNull("the channel must exist before the first nudge", channel)
        assertEquals(context.getString(R.string.partner_channel_nudges_name), channel!!.name)
        // Owner ruling 2026-10-04 (plan deviation, see PartnerNotifications.ensurePartnerNudgesChannel):
        // a reach-out must surface as a heads-up banner — HIGH, not the plan's DEFAULT.
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
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

        val entry = awaitNudgeEntries(1).single()
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
        awaitChannelDeleted(PartnerNotifications.CHANNEL_PARTNER_NUDGES)
        assertNull(manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES))

        messagingService().onMessageReceived(nudgeMessage(UUID.randomUUID().toString()))

        val entry = awaitNudgeEntries(1).single()
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
        assertEquals(1, awaitNudgeEntries(1).size)

        messagingService().onMessageReceived(nudgeMessage(UUID.randomUUID().toString()))

        // FR-19: one replaceable tray entry — a second nudge must not stack a second entry.
        val entry = awaitNudgeEntries(1).single()
        assertEquals(PartnerNotifications.NOTIF_PARTNER_NUDGE, entry.id)
        assertEquals(PartnerNudgePush.TAG, entry.tag)
    }

    // --- §12.3 bullet 5 ---------------------------------------------------------------------

    @Test
    fun notificationTap_navigatesHome_andConsumesIntentExtras() {
        android.util.Log.i("NudgeTapTest", "tap: begin")
        finishAnyMainActivity()
        android.util.Log.i("NudgeTapTest", "tap: previous activity finished")

        messagingService().onMessageReceived(nudgeMessage(UUID.randomUUID().toString()))
        assertTrue("the nudge must reach the tray before the tap drive", awaitNudgeEntries(1).isNotEmpty())
        android.util.Log.i("NudgeTapTest", "tap: posted + tray visible")
        // A synthetic contentIntent.send() is blocked by AOSP background-activity-launch
        // hardening (Lean_API34, 2026-10-31: "Background activity launch blocked …
        // originatingPendingIntent … NOTIFICATION_SERVICE"); only a real user tap carries the
        // NotificationManagerService allowlist. Drive the actual tap through SystemUI.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        unlockedRig(device)
        device.pressHome()
        android.util.Log.i("NudgeTapTest", "tap: home pressed")
        // 45 s, not 10 s: under full-suite load SystemUI logged the shade surface 18 s after
        // openNotification() returned (AVD, 2026-10-31 18:24:41 → 18:24:59), outliving a 10 s wait.
        // Exact text, not textContains: the long-press settings panel shows the channel name
        // "Accountability Check-ins", which contains the notification title and would otherwise
        // be clicked instead (AVD, 2026-10-31 19:53:41 — attempt 2 found the panel's channel row).
        // A busy SystemUI can also stretch the injected tap past the long-press threshold, which
        // is what opens that panel (19:53:09: gesture took 9.9 s, no ActivityTaskManager START);
        // each retry closes it with BACK and reopens a fresh shade.
        val title = context.getString(R.string.partner_nudge_title)
        var activity: MainActivity? = null
        for (attempt in 1..3) {
            // Re-assert an unlocked rig: a deferral observed here was the keyguard coming back
            // between attempts, not a missed tap.
            unlockedRig(device)
            device.pressBack()
            device.openNotification()
            android.util.Log.i("NudgeTapTest", "tap: shade open requested ($attempt)")
            val entry = device.wait(Until.findObject(By.text(title)), 45_000)
            if (entry == null) {
                android.util.Log.i("NudgeTapTest", "tap: nudge not in shade ($attempt)")
                continue
            }
            // Let the row settle so the injection lands on it, not mid-layout.
            SystemClock.sleep(500)
            android.util.Log.i("NudgeTapTest", "tap: found in shade, clicking ($attempt)")
            entry.click()
            android.util.Log.i("NudgeTapTest", "tap: click returned ($attempt)")
            activity = resumedMainActivityWithin(30_000, device)
            if (activity != null) break
            android.util.Log.i("NudgeTapTest", "tap: no activity after the click ($attempt)")
        }
        if (activity == null) {
            fail("MainActivity never reached RESUMED after the notification tap (3 attempts)")
            error("unreachable")
        }
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

    // A valid pair id rides along (D16) but the directly constructed service has no injected
    // enrollment DAO, so these legs pin the generic-fallback copy. The named path is proven
    // on-device through the real Hilt-created service (see the D16 device leg in the mapping).
    private fun nudgeMessage(nudgeId: String) = RemoteMessage.Builder("test")
        .addData(PartnerNudgePush.TYPE_KEY, PartnerNudgePush.TYPE_PARTNER_NUDGE)
        .addData(PartnerNudgePush.NUDGE_ID_KEY, nudgeId)
        .addData(PartnerNudgePush.PAIR_ID_KEY, "alice-uid_bob-uid")
        .build()

    // --- tray + launcher state --------------------------------------------------------------

    private fun nudgeEntries() = manager.activeNotifications.filter {
        it.tag == PartnerNudgePush.TAG || it.id == PartnerNotifications.NOTIF_PARTNER_NUDGE
    }

    /**
     * Posting is asynchronous on the framework side (the enqueue returns before the
     * NotificationService handler runs): an immediate read after [PartnerMessagingService.onMessageReceived]
     * races it, and a test burst — canaries included — can lose that race. Poll for the
     * expected count instead; the assertion after this call keeps the contract.
     */
    private fun awaitNudgeEntries(count: Int) = run {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var entries = nudgeEntries()
        while (entries.size != count && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            entries = nudgeEntries()
        }
        entries
    }

    private fun awaitChannelDeleted(channelId: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (manager.getNotificationChannel(channelId) != null &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(50)
        }
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

    private fun resumedMainActivityWithin(timeoutMs: Long, device: UiDevice): MainActivity? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            resumedMainActivity()?.let { return it }
            // A launch deferred on `afterKeyguardGone` executes the moment the keyguard goes;
            // keep clearing it so the same attempt can observe its own launch.
            if (device.hasObject(By.res("com.android.systemui", "keyguard_security_container"))) {
                shell("wm dismiss-keyguard")
            }
            SystemClock.sleep(200)
        }
        return null
    }

    /**
     * Wake the rig and clear the keyguard. A notification launch on a locked rig defers with
     * "dismissKeyguardThenExecute, afterKeyguardGone: true" and the activity never starts
     * (AVD, 2026-10-31), so the tap drive re-asserts this before every attempt. `wm
     * dismiss-keyguard` alone does not clear the swipe keyguard; swipe up if it is still up.
     */
    private fun unlockedRig(device: UiDevice) {
        device.wakeUp()
        shell("wm dismiss-keyguard")
        val keyguard = By.res("com.android.systemui", "keyguard_security_container")
        if (device.hasObject(keyguard)) {
            android.util.Log.i("NudgeTapTest", "tap: keyguard up, swiping to unlock")
            device.swipe(540, 1900, 540, 500, 20)
            device.wait(Until.gone(keyguard), 5_000)
        }
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
