package com.example.myexodus.accountability.sos

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myexodus.R
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.EnhancedModeTestEntryPoint
import com.example.myexodus.notifications.NotificationAccess
import com.example.myexodus.settings.HomeSettings
import com.example.myexodus.ui.home.HomeScreen
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SOS plan §12.3, Home-surface bullets (device leg) — the card and the check-ins-off banner,
 * rendered by the real [HomeScreen]:
 *
 *  - Card appears from Firestore without relying on FCM payload content (UI half: the card is
 *    a pure function of its fetched data — no push input — rendered here with the listener's
 *    record shape; the data half — listener → card against the live backend — is the harness
 *    leg in [NudgeInboxDeviceInstrumentedTest]).
 *  - `I'm here` / `X` acknowledge (UI half: both actions invoke the repository's one ack path;
 *    the batch-over-all-fetched semantics is pinned by NudgeInboxRepositoryTest and the data
 *    half by the harness leg).
 *  - Notification-disabled banner appears (the banner's input is the boolean MainActivity
 *    computes from `NotificationAccess.canPost`; the input→banner render path is pinned here,
 *    and the canPost ↔ OS-state mapping is pinned by NotificationAccessTest (JVM)).
 *  - Channel-mute state does not show the app-level notification-off banner — this bullet
 *    feeds the LIVE `NotificationAccess.canPost` read (the on-device positive control that the
 *    wire from the real system state into the banner exists) while a fresh probe channel is
 *    muted; the probe is deleted in the same test (see [muteFreshProbeChannel] for why the
 *    probe is not the real `partner_nudges` channel).
 *
 * Why the app-level-off state is fed, not driven: the device-side toggle is
 * OP_POST_NOTIFICATION, and skins differ on whether a test harness may write it. Stock Android
 * accepts `appops set … deny`; the X6532/XOS build silently resets that write to default-allow
 * (verified on-device: rc=0, then `appops get` shows no override, while a control op such as
 * VIBRATE writes fine). Asserting through that drive would pass on stock and be undrivable on
 * XOS — so the portable contract is pinned per level instead: the render path here on every
 * skin, the system-read mapping in the JVM test.
 */
@RunWith(AndroidJUnit4::class)
class NudgeHomeSurfaceInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private fun str(resId: Int, vararg args: Any): String = compose.activity.getString(resId, *args)

    // --- §12.3 bullets 6-8 (UI half) --------------------------------------------------------

    @Test
    fun card_rendersFromItsFetchedData_andBothActionsAcknowledge() {
        var acks = 0
        render(
            partnerNotificationsEnabled = true,
            card = NudgeInboxRepository.NudgeCard(
                pairId = "pair-1",
                createdAtMs = System.currentTimeMillis() - 60_000,
                pendingNudgeIds = listOf("n1", "n2"),
            ),
            onAcknowledge = { acks++ },
        )

        compose.onNodeWithText(str(R.string.sos_card_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.sos_card_im_here)).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.sos_card_im_here)).performClick()
        compose.waitForIdle()
        assertEquals("'I'm here' must hit the one acknowledgment path", 1, acks)

        compose.onNodeWithContentDescription(str(R.string.sos_card_dismiss)).performClick()
        compose.waitForIdle()
        assertEquals("the X must acknowledge through the same path", 2, acks)
    }

    @Test
    fun card_rendersTheSendersMessage() {
        // §12.3 rev (owner mandate 2026-10-04): the message rides the card when the envelope
        // carried one; the envelope → card.message half is NudgeInboxRepositoryTest (JVM) and
        // the v1 "no message" half is the same repository test — this leg is the render path.
        render(
            partnerNotificationsEnabled = true,
            card = NudgeInboxRepository.NudgeCard(
                pairId = "pair-1",
                createdAtMs = System.currentTimeMillis() - 60_000,
                pendingNudgeIds = listOf("n1"),
                message = "Please call me",
            ),
        )
        compose.onNodeWithText(str(R.string.sos_card_title)).assertIsDisplayed()
        compose.onNodeWithText("Please call me").assertIsDisplayed()
    }

    // --- §12.3 bullets 10-11 ----------------------------------------------------------------

    @Test
    fun notificationsOn_noBanner() {
        // Positive control: the banner is absent while its paired input (the roster) is live
        // in the frame — names are structural now, so the absence assert cannot pass vacuously.
        render(partnerNotificationsEnabled = true)
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.partner_notifications_checkins_body)).assertDoesNotExist()
    }

    @Test
    fun appLevelNotificationsOff_bannerAppears() {
        // The app-level-off input exactly as MainActivity computes it when the OS toggle is off.
        // See the class comment for why this is fed rather than driven from the test, and where
        // the canPost ↔ OS-state mapping is pinned instead.
        render(partnerNotificationsEnabled = false)

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(str(R.string.partner_notifications_checkins_body))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(str(R.string.partner_notifications_checkins_body)).assertIsDisplayed()
    }

    @Test
    fun channelMute_doesNotShowTheBanner() {
        val probe = muteFreshProbeChannel()
        try {
            grantPostNotifications()
            Assume.assumeTrue(
                "a muted channel must not read as app-level off — needs app-level notifications " +
                    "on (a fresh AVD starts with POST_NOTIFICATIONS denied and pm grant refused)",
                NotificationAccess.canPost(context),
            )
            // The banner's other input (a linked partner) is structural now — names pass
            // straight into the frame, so the absence assert below cannot pass vacuously.
            render(partnerNotificationsEnabled = NotificationAccess.canPost(context))
            compose.waitForIdle()

            compose.onNodeWithText(str(R.string.partner_notifications_checkins_body)).assertDoesNotExist()
        } finally {
            manager.deleteNotificationChannel(probe)
        }
    }

    // --- rendering ---------------------------------------------------------------------------

    private fun render(
        partnerNotificationsEnabled: Boolean,
        card: NudgeInboxRepository.NudgeCard? = null,
        onAcknowledge: () -> Unit = {},
    ) {
        val ep = EntryPointAccessors.fromApplication(context, EnhancedModeTestEntryPoint::class.java)
        val store = ep.appRulesStoreFactory().open()
            ?: error("rules store unavailable — launch the app once and re-run")
        compose.setContent {
            HomeScreen(
                store = store,
                homeSettings = HomeSettings(context),
                monitor = EnhancedModeHealthMonitor(context),
                degradation = null,
                authority = null,
                outageSource = ep.accountingOutageSource(),
                onReenable = {},
                onOpenSettings = {},
                onOpenStreaks = {},
                onOpenRules = {},
                onOpenRule = {},
                habit = null,
                onAcknowledgeMilestone = {},
                onSwitchHabit = {},
                streakResolved = false,
                partnerNames = mapOf("pair-1" to "Sam"),
                partnerNotificationsEnabled = partnerNotificationsEnabled,
                onFixPartnerNotifications = {},
                nudgeCard = card,
                onNudgeAcknowledge = onAcknowledge,
            )
        }
    }

    // --- device state (restored in-test) -----------------------------------------------------

    /**
     * Mutes a virgin probe channel and returns its id (caller deletes it in `finally`).
     * Deliberately NOT the real `partner_nudges` channel: Android re-applies a deleted
     * channel's remembered importance on re-creation (observed deterministic on the X6532,
     * 2026-10-04 — delete + recreate-as-NONE reads back DEFAULT), so muting the real channel
     * could neither establish the mute nor be fully restored. The bullet's claim — channel
     * mute must not read as app-level off — is channel-agnostic: `canPost` is app-level, and
     * a fresh id takes the requested IMPORTANCE_NONE on every skin.
     */
    /** Grant-if-missing (never revoke, matching the other tray tests): API 33+ starts a fresh
     *  install with POST_NOTIFICATIONS denied, so the app-level toggle would read off before
     *  this bullet ever reaches the channel-mute input. Output is read to EOF so the grant has
     *  completed before [NotificationAccess.canPost] is read. */
    private fun grantPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            ),
        ).use { it.readBytes() }
    }

    private fun muteFreshProbeChannel(): String {
        val id = "test_mute_probe_${System.currentTimeMillis()}"
        manager.createNotificationChannel(
            NotificationChannel(id, "mute probe", NotificationManager.IMPORTANCE_NONE),
        )
        assertEquals(
            "a virgin channel id must take the requested mute",
            NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(id).importance,
        )
        return id
    }
}
