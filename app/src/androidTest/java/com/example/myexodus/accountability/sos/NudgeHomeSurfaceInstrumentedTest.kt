package com.example.myexodus.accountability.sos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.R
import com.example.myexodus.accountability.sync.PartnerNotifications
import com.example.myexodus.blocking.EnhancedModeHealthMonitor
import com.example.myexodus.blocking.EnhancedModeTestEntryPoint
import com.example.myexodus.notifications.NotificationAccess
import com.example.myexodus.ui.home.HomeScreen
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 *    wire from the real system state into the banner exists) while the real `partner_nudges`
 *    channel is muted; the mute is restored in the same test.
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

    // --- §12.3 bullets 10-11 ----------------------------------------------------------------

    @Test
    fun notificationsOn_noBanner() {
        var namesLoaded = 0
        render(partnerNotificationsEnabled = true, onNamesLoaded = { namesLoaded++ })
        // Positive control: the banner is absent while its paired input is live in the frame.
        compose.waitUntil(timeoutMillis = 5_000) { namesLoaded > 0 }
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
        mutePartnerNudgesChannel()
        try {
            assertTrue(
                "a muted channel must not read as app-level off",
                NotificationAccess.canPost(context),
            )
            var namesLoaded = 0
            render(
                partnerNotificationsEnabled = NotificationAccess.canPost(context),
                onNamesLoaded = { namesLoaded++ },
            )
            // The banner's other input (a linked partner) must actually be in the frame —
            // otherwise the absence assert below could pass vacuously.
            compose.waitUntil(timeoutMillis = 5_000) { namesLoaded > 0 }
            compose.waitForIdle()

            compose.onNodeWithText(str(R.string.partner_notifications_checkins_body)).assertDoesNotExist()
        } finally {
            restorePartnerNudgesChannel()
        }
    }

    // --- rendering ---------------------------------------------------------------------------

    private fun render(
        partnerNotificationsEnabled: Boolean,
        card: NudgeInboxRepository.NudgeCard? = null,
        onAcknowledge: () -> Unit = {},
        onNamesLoaded: () -> Unit = {},
    ) {
        val ep = EntryPointAccessors.fromApplication(context, EnhancedModeTestEntryPoint::class.java)
        val store = ep.appRulesStoreFactory().open()
            ?: error("rules store unavailable — launch the app once and re-run")
        compose.setContent {
            HomeScreen(
                store = store,
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
                loadPartnerNames = { onNamesLoaded(); mapOf("pair-1" to "Sam") },
                partnerNotificationsEnabled = partnerNotificationsEnabled,
                onFixPartnerNotifications = {},
                nudgeCard = card,
                onNudgeAcknowledge = onAcknowledge,
            )
        }
    }

    // --- device state (restored in-test) -----------------------------------------------------

    private fun mutePartnerNudgesChannel() {
        manager.deleteNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)
        manager.createNotificationChannel(
            NotificationChannel(
                PartnerNotifications.CHANNEL_PARTNER_NUDGES,
                context.getString(R.string.partner_channel_nudges_name),
                NotificationManager.IMPORTANCE_NONE,
            ),
        )
        assertEquals(
            NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES).importance,
        )
    }

    private fun restorePartnerNudgesChannel() {
        manager.deleteNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)
        PartnerNotifications.ensurePartnerNudgesChannel(context)
    }
}
