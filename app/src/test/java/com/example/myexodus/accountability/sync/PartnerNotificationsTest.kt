package com.example.myexodus.accountability.sync

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.MainActivity
import com.example.myexodus.R
import com.example.myexodus.accountability.serialization.ActivityStateAfter
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.PlanBody
import com.example.myexodus.accountability.serialization.PlanWire
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.example.myexodus.ui.partner.VIEW_ACTIVITY
import com.example.myexodus.ui.partner.VIEW_REQUESTS
import com.example.myexodus.ui.partner.VIEW_RULES
import java.text.DateFormat
import java.util.Date
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
 * notification is attempted while denied) and the channel spec: Step 9's immutable
 * `partner_accountability_events_v1` HIGH (old `partner_alerts` retired), Step 8's
 * `availability_alerts_v1` HIGH, Step 16's `approval_requests` HIGH, `sync_status` LOW +
 * debug-only. `checkin_reminders_v1` is retired: check-in is silent (data-only probe,
 * ADR #223) and upgrades delete the dead channel at startup. The banner itself is a
 * composition; the device leg covers it — this pins the posting gate and channel shape.
 * Owner ruling 2026-09-29: every post names the partner it concerns and is keyed per pair
 * (§19.5), while the lockscreen public copy stays generic (FR-13) — all pinned here.
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
        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME, payload(EventPayload.TYPE_STREAK_UPDATED),
        )
        assertTrue(manager.activeNotifications.isEmpty())
        assertNull(manager.getNotificationChannel(PartnerNotifications.CHANNEL_ACCOUNTABILITY_EVENTS))
    }

    @Test
    fun granted_partnerAlertsChannelIsHighImportance() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME, payload(EventPayload.TYPE_STREAK_UPDATED),
        )
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_ACCOUNTABILITY_EVENTS)!!.importance,
        )
        assertEquals(1, manager.activeNotifications.size)
    }

    /** Plan Step 6: the test alert must prove the same display path as a real alert (the
     *  partner-activity channel, HIGH) while its own tag keeps it from replacing one. */
    @Test
    fun testAlert_ridesThePartnerAlertsChannel_withoutReplacingARealAlert() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        PartnerNotifications.notifyTestAlert(context)

        val posted = manager.activeNotifications.single()
        assertEquals(PartnerNotifications.NOTIF_PARTNER_TEST, posted.id)
        assertEquals(PartnerNotifications.TEST_NOTIFICATION_TAG, posted.tag)
        assertEquals(PartnerNotifications.CHANNEL_ACCOUNTABILITY_EVENTS, posted.notification.channelId)
    }

    @Test
    fun granted_approvalRequestsChannelIsHighImportance() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PartnerNotifications.notifyApprovalRequest(context, PAIR_ID, PARTNER_NAME)
        PartnerNotifications.notifyApprovalDecision(context, PAIR_ID, PARTNER_NAME)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_APPROVAL_REQUESTS)!!.importance,
        )
        assertEquals(2, manager.activeNotifications.size)
        // Owner ask 2026-11-02: an approval tap lands on the Requests queue that holds it.
        val requestTap = shadowOf(
            manager.activeNotifications.first {
                it.id == PartnerNotifications.idFor(PartnerNotifications.NOTIF_APPROVAL_REQUEST, PAIR_ID)
            }.notification.contentIntent,
        ).savedIntent
        assertEquals(
            VIEW_REQUESTS,
            requestTap.getStringExtra(MainActivity.EXTRA_OPEN_PARTNER_VIEW),
        )
    }

    /**
     * Owner ruling 2026-10-31: every partner event must alert on screen. A second event for
     * the same pair still replaces the one tray entry (fixed tag/id) but may not carry
     * FLAG_ONLY_ALERT_ONCE — that flag was the observed reason a new message updated the
     * shade silently. Approval items keep their own alert-every-time contract.
     */
    @Test
    fun partnerEvent_alertsOnEveryPost_andReplacesTheTrayEntry() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME, payload(EventPayload.TYPE_STREAK_UPDATED),
        )
        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME, payload(EventPayload.TYPE_RELAPSE_RECORDED),
        )
        PartnerNotifications.notifyApprovalRequest(context, PAIR_ID, PARTNER_NAME)

        val partnerEvent = manager.activeNotifications.first {
            it.id == PartnerNotifications.idFor(PartnerNotifications.NOTIF_PARTNER_EVENT, PAIR_ID)
        }
        assertEquals(
            "a new partner event must alert, never update the shade silently",
            0,
            partnerEvent.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE,
        )
        assertEquals(
            "one partner still owns exactly one activity tray entry",
            1,
            manager.activeNotifications.count {
                it.id == PartnerNotifications.idFor(PartnerNotifications.NOTIF_PARTNER_EVENT, PAIR_ID)
            },
        )
        val approval = manager.activeNotifications.first {
            it.id == PartnerNotifications.idFor(PartnerNotifications.NOTIF_APPROVAL_REQUEST, PAIR_ID)
        }
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

        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME, payload(EventPayload.TYPE_STREAK_UPDATED),
        )

        val notification = manager.activeNotifications.single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(
            "the unlocked tray copy names the partner",
            context.getString(R.string.partner_event_title_named, PARTNER_NAME),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        val publicVersion = notification.publicVersion
        assertTrue("lockscreen renders the generic public copy", publicVersion != null)
        assertEquals(
            "the lockscreen copy stays generic (FR-13)",
            context.getString(R.string.partner_event_title),
            publicVersion!!.extras.getString(Notification.EXTRA_TITLE),
        )
    }

    /** #11 (owner feedback 2026-10-01): a motivational message names the sender and shows the
     *  message itself in the unlocked tray — the lockscreen public version stays generic
     *  (FR-13), body included. */
    @Test
    fun motivationalMessage_showsTheBodyUnlocked_lockscreenStaysGeneric() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME,
            payload(EventPayload.TYPE_MOTIVATIONAL_MESSAGE, text = "Thinking of you today."),
        )

        val notification = manager.activeNotifications.single().notification
        assertEquals(
            context.getString(R.string.partner_message_title_named, PARTNER_NAME),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.partner_message_quoted, "Thinking of you today."),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        val publicVersion = notification.publicVersion
        assertTrue("lockscreen renders the generic public copy", publicVersion != null)
        assertEquals(
            context.getString(R.string.partner_event_title),
            publicVersion!!.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            "the message body never reaches the lockscreen (FR-13)",
            context.getString(R.string.partner_event_text),
            publicVersion.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    /** A blank body (never our composer's output) must not render as an empty quotation —
     *  the post degrades to the generic named copy. */
    @Test
    fun blankMotivationalBody_degradesToTheGenericCopy() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME,
            payload(EventPayload.TYPE_MOTIVATIONAL_MESSAGE, text = "   "),
        )

        val notification = manager.activeNotifications.single().notification
        assertEquals(
            context.getString(R.string.partner_event_title_named, PARTNER_NAME),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.partner_event_text),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    /** §19.5 multi-partner (owner ruling 2026-09-29): N pairs' items coexist in the tray, each
     *  named for its own partner and keyed by its own id — one partner's entry can never
     *  silently replace another's. */
    @Test
    fun twoPairs_coexist_eachNamedForItsOwnPartner() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        PartnerNotifications.notifyApprovalRequest(context, "pair-alpha", "Mama")
        PartnerNotifications.notifyApprovalRequest(context, "pair-beta", "Ada")

        val posted = manager.activeNotifications
        assertEquals(2, posted.size)
        assertEquals(
            setOf(
                PartnerNotifications.idFor(PartnerNotifications.NOTIF_APPROVAL_REQUEST, "pair-alpha"),
                PartnerNotifications.idFor(PartnerNotifications.NOTIF_APPROVAL_REQUEST, "pair-beta"),
            ),
            posted.map { it.id }.toSet(),
        )
        assertEquals(
            setOf(
                context.getString(R.string.approval_request_title_named, "Mama"),
                context.getString(R.string.approval_request_title_named, "Ada"),
            ),
            posted.map { it.notification.extras.getString(Notification.EXTRA_TITLE) }.toSet(),
        )
    }

    /** Owner warning 2026-10-23 ("we don't want collisions"): ids are `base + pairId.hashCode()`
     *  over consecutive bases, so a pair whose hash is one higher lands exactly on the NEXT
     *  category's id for another pair. "BB" (hash 2112) and "Ab" (hash 2113) are the smallest
     *  such pair: request(B) and decision(A) both compute 4015. The (tag, id) key keeps both
     *  entries alive — with the old id-only key the decision silently replaced the request. */
    @Test
    fun idCollisionAcrossPairsAndCategories_entriesStillCoexist() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val pairA = "BB"
        val pairB = "Ab"
        val requestId = PartnerNotifications.idFor(PartnerNotifications.NOTIF_APPROVAL_REQUEST, pairB)
        val decisionId = PartnerNotifications.idFor(PartnerNotifications.NOTIF_APPROVAL_DECISION, pairA)
        assertEquals("the rigged pair must collide on id", requestId, decisionId)

        PartnerNotifications.notifyApprovalRequest(context, pairB, "Ada")
        PartnerNotifications.notifyApprovalDecision(context, pairA, "Mama")

        val posted = manager.activeNotifications
        assertEquals(2, posted.size)
        assertEquals(
            setOf(
                PartnerNotifications.partnerTag("request", pairB),
                PartnerNotifications.partnerTag("decision", pairA),
            ),
            posted.map { it.tag }.toSet(),
        )
        assertEquals(
            setOf(
                context.getString(R.string.approval_request_title_named, "Ada"),
                context.getString(R.string.approval_decision_title_named, "Mama"),
            ),
            posted.map { it.notification.extras.getString(Notification.EXTRA_TITLE) }.toSet(),
        )
    }

    /** Owner warning 2026-10-23: PendingIntent identity ignores extras and every post here
     *  shared one request code, so a later partner-event post under FLAG_UPDATE_CURRENT
     *  rewrote the reach-out notification's tap — its nudge routing extras vanished. The data
     *  URI now scopes each tag to its own PendingIntent; an event post must not touch the
     *  nudge tap (nor inherit its routing). */
    @Test
    fun nudgeTapExtras_surviveALaterPartnerEventPost() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nudgeId = "123e4567-e89b-42d3-a456-426614174000"

        PartnerNotifications.notifyPartnerNudge(context, nudgeId)
        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME, payload(EventPayload.TYPE_STREAK_UPDATED),
        )

        val posted = manager.activeNotifications
        assertEquals(2, posted.size)
        val nudgeTap = shadowOf(
            posted.first { it.tag == PartnerNudgePush.TAG }.notification.contentIntent,
        ).savedIntent
        assertEquals(PartnerNudgePush.TYPE_PARTNER_NUDGE, nudgeTap.getStringExtra(PartnerNudgePush.TYPE_KEY))
        assertEquals(nudgeId, nudgeTap.getStringExtra(PartnerNudgePush.NUDGE_ID_KEY))
        val eventTap = shadowOf(
            posted.first { it.tag == PartnerNotifications.partnerTag("event", PAIR_ID) }
                .notification.contentIntent,
        ).savedIntent
        assertNull(
            "the partner-event tap must never inherit nudge routing",
            eventTap.getStringExtra(PartnerNudgePush.TYPE_KEY),
        )
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

    /** SOS §8.14: the check-ins channel is created at application startup — IMPORTANCE_HIGH
     *  (owner ruling 2026-10-04: a reach-out must surface as a heads-up banner, the plan's
     *  DEFAULT was device-verified as tray-only), VISIBILITY_PRIVATE for the lockscreen,
     *  vibration enabled on first creation. */
    @Test
    fun ensurePartnerNudgesChannel_hasThePlanShape() {
        PartnerNotifications.ensurePartnerNudgesChannel(context)
        val channel = manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)!!
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertTrue("vibration enabled on first creation", channel.shouldVibrate())
        assertEquals(
            context.getString(R.string.partner_channel_nudges_name),
            channel.name.toString(),
        )
    }

    /** Plan Step 9: the accountability-events channel is created at startup (not lazily on the
     *  first post), HIGH, and the retired `partner_alerts` id is deleted in the same call so
     *  system settings never lists two partner-activity channels. */
    @Test
    fun ensureAccountabilityEventsChannel_isHigh_andRetiresTheLegacyId() {
        manager.createNotificationChannel(
            NotificationChannel(
                PartnerNotifications.CHANNEL_PARTNER_ALERTS_LEGACY, "legacy",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        PartnerNotifications.ensureAccountabilityEventsChannel(context)

        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_ACCOUNTABILITY_EVENTS)!!.importance,
        )
        assertNull(manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_ALERTS_LEGACY))
        assertEquals(
            context.getString(R.string.partner_channel_activity_name),
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_ACCOUNTABILITY_EVENTS)!!.name.toString(),
        )
    }

    /** Owner ruling 2026-10-28: check-in is automatic. The old tray-visible reminder channel
     *  is retired — a device upgrading keeps no dead channel in system settings. */
    @Test
    fun deleteLegacyCheckinChannel_removesTheRetiredChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                PartnerNotifications.CHANNEL_CHECKIN_REMINDERS_LEGACY, "legacy",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        PartnerNotifications.deleteLegacyCheckinChannel(context)

        assertNull(manager.getNotificationChannel(PartnerNotifications.CHANNEL_CHECKIN_REMINDERS_LEGACY))
    }

    /** The `_v3` id migration's other half: a dented pre-v3 channel cannot be raised in
     *  place, so its id is retired by deletion at startup — without this, the settings
     *  screen would list duplicate check-in channels. */
    @Test
    fun ensurePartnerNudgesChannel_retiresTheLegacyChannelIds() {
        manager.createNotificationChannel(
            NotificationChannel(
                PartnerNotifications.CHANNEL_PARTNER_NUDGES_LEGACY, "legacy",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PartnerNotifications.CHANNEL_PARTNER_NUDGES_V2, "v2",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        PartnerNotifications.ensurePartnerNudgesChannel(context)

        assertNull(manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES_LEGACY))
        assertNull(manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES_V2))
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)!!.importance,
        )
    }

    /** Device-observed 2026-10-04 (both rig devices): the nudge post's channel-create call
     *  rewrote the LIVE v2 channel's importance HIGH→DEFAULT (XOS honors app importance
     *  updates on non-user-locked channels), so every later post was tray-only — the exact
     *  reported defect. The post path must create a channel only when missing, never rewrite
     *  an existing one's importance. */
    @Test
    fun nudgePost_neverDemotesTheExistingChannel() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PartnerNotifications.ensurePartnerNudgesChannel(context)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)!!.importance,
        )

        PartnerNotifications.notifyPartnerNudge(context, "123e4567-e89b-42d3-a456-426614174000")

        assertEquals(
            "a nudge post must not rewrite the channel's importance",
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)!!.importance,
        )
    }

    /** SOS §8.17/FR-19/FR-20: the foreground nudge posts immediately on the check-ins
     *  channel under the fixed FR-19 tag + id (one replaceable tray entry), with the plan's
     *  generic copy only — no partner name, no ciphertext, nothing from the push side. */
    @Test
    fun partnerNudge_postsGenericCopyUnderTheFixedTag() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nudgeId = "123e4567-e89b-42d3-a456-426614174000"

        PartnerNotifications.notifyPartnerNudge(context, nudgeId)

        val posted = manager.activeNotifications.single()
        assertEquals(PartnerNudgePush.TAG, posted.tag)
        assertEquals(PartnerNotifications.NOTIF_PARTNER_NUDGE, posted.id)
        assertEquals(PartnerNotifications.CHANNEL_PARTNER_NUDGES, posted.notification.channelId)
        assertEquals(
            context.getString(R.string.partner_nudge_title),
            posted.notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.partner_nudge_text),
            posted.notification.extras.getString(Notification.EXTRA_TEXT),
        )
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.notification.visibility)
    }

    /** D16 (owner ruling 2026-10-23): the unlocked receipt names the sender from this phone's
     *  own pairing label; the lockscreen public version stays generic (FR-13), title and body. */
    @Test
    fun partnerNudge_namesTheSender_unlocked_lockscreenStaysGeneric() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nudgeId = "123e4567-e89b-42d3-a456-426614174000"

        PartnerNotifications.notifyPartnerNudge(context, nudgeId, PARTNER_NAME)

        val notification = manager.activeNotifications.single().notification
        assertEquals(
            context.getString(R.string.partner_nudge_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.partner_nudge_text_named, PARTNER_NAME),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        val publicVersion = notification.publicVersion
        assertTrue("lockscreen renders the generic public copy", publicVersion != null)
        assertEquals(
            context.getString(R.string.partner_nudge_title),
            publicVersion!!.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            "the partner's name never reaches the lockscreen (FR-13)",
            context.getString(R.string.partner_nudge_text),
            publicVersion.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    /** §8.18 parity: the foreground post's tap carries the same launcher extras the
     *  background system path delivers, so MainActivity's routing is identical. */
    @Test
    fun partnerNudge_tapCarriesTheLauncherExtras() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nudgeId = "123e4567-e89b-42d3-a456-426614174000"

        PartnerNotifications.notifyPartnerNudge(context, nudgeId)

        val tap = shadowOf(manager.activeNotifications.single().notification.contentIntent).savedIntent
        assertEquals(PartnerNudgePush.TYPE_PARTNER_NUDGE, tap.getStringExtra(PartnerNudgePush.TYPE_KEY))
        assertEquals(nudgeId, tap.getStringExtra(PartnerNudgePush.NUDGE_ID_KEY))
    }

    /** Denied permission skips the post entirely — the FCM callback must not crash (plan
     *  §12 line 523's denied-permission path). */
    @Test
    fun partnerNudge_deniedPermissionIsASilentNoOp() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PartnerNotifications.notifyPartnerNudge(context, "123e4567-e89b-42d3-a456-426614174000")
        assertTrue(manager.activeNotifications.isEmpty())
    }

    /** Owner mandate 2026-10-04: a relapse alert states which streak and when it happened —
     *  unlocked copy only; the lockscreen public version stays fully generic (FR-13). */
    @Test
    fun relapse_namesTheStreakDaysAndTime_unlocked_lockscreenStaysGeneric() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val state = ActivityStateAfter(
            daysFree = 0, relapseCount = 3, milestonesReached = emptyList(), coachingProgress = 0,
            eventOccurredAtMs = OCCURRED_AT, habitLabel = "Gambling", endedDays = 5,
        )

        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME,
            payload(EventPayload.TYPE_RELAPSE_RECORDED, stateAfter = state),
        )

        val notification = manager.activeNotifications.single().notification
        assertEquals(
            context.getString(R.string.partner_relapse_title_named, PARTNER_NAME),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(
                R.string.partner_event_time_suffix,
                "Gambling · " + context.getString(R.string.partner_relapse_ended_days, 5),
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(OCCURRED_AT)),
            ),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        val publicVersion = notification.publicVersion
        assertTrue("lockscreen renders the generic public copy", publicVersion != null)
        assertEquals(
            context.getString(R.string.partner_event_title),
            publicVersion!!.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            "the streak name and day-count never reach the lockscreen (FR-13)",
            context.getString(R.string.partner_event_text),
            publicVersion.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    /** Wire-v1 degradation (ADR #134): a body without the v2 fields keeps the generic named
     *  copy — never a title promising a streak name the body cannot show. */
    @Test
    fun relapse_withoutV2Fields_degradesToTheGenericCopy() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val state = ActivityStateAfter(
            daysFree = 0, relapseCount = 3, milestonesReached = emptyList(), coachingProgress = 0,
            eventOccurredAtMs = OCCURRED_AT,
        )

        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME,
            payload(EventPayload.TYPE_RELAPSE_RECORDED, stateAfter = state),
        )

        val notification = manager.activeNotifications.single().notification
        assertEquals(
            context.getString(R.string.partner_event_title_named, PARTNER_NAME),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.partner_event_text),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    /** #16: the partner ended the partnership and this device's shared data was shredded —
     *  the responder is told who, when, and that the erase happened (owner-observed defect:
     *  the roster silently dropped with nothing on the other device). This test is the only
     *  coverage of the ended-notification's copy contract; the caller is the deletion
     *  composition in DI. */
    @Test
    fun partnershipEnded_namesWhoAndWhen_unlocked_lockscreenStaysGeneric() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        PartnerNotifications.notifyPartnershipEnded(context, PAIR_ID, PARTNER_NAME, OCCURRED_AT)

        val posted = manager.activeNotifications.single()
        assertEquals(
            PartnerNotifications.idFor(PartnerNotifications.NOTIF_PARTNERSHIP_ENDED, PAIR_ID),
            posted.id,
        )
        val notification = posted.notification
        assertEquals(
            context.getString(R.string.partner_ended_title_named, PARTNER_NAME),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(
                R.string.partner_ended_text_named,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(OCCURRED_AT)),
            ),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        val publicVersion = notification.publicVersion
        assertTrue("lockscreen renders the generic public copy", publicVersion != null)
        assertEquals(
            context.getString(R.string.partner_ended_title),
            publicVersion!!.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            "who and when never reach the lockscreen (FR-13)",
            context.getString(R.string.partner_ended_text),
            publicVersion.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    /** A deletion request without a server timestamp (hand-written document, missing field)
     *  still posts the named copy — with the no-time body, never an empty date. */
    @Test
    fun partnershipEnded_withoutATimestamp_usesTheNoTimeCopy() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        PartnerNotifications.notifyPartnershipEnded(context, PAIR_ID, PARTNER_NAME, endedAtEpochMillis = null)

        assertEquals(
            context.getString(R.string.partner_ended_text_named_no_time),
            manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    /** A milestone alert names the streak and the day reached (owner mandate 2026-10-04). */
    @Test
    fun milestone_namesTheStreakAndDay() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val state = ActivityStateAfter(
            daysFree = 30, relapseCount = 1, milestonesReached = listOf(30), coachingProgress = 0,
            eventOccurredAtMs = OCCURRED_AT, habitLabel = "Alcohol", milestoneDay = 30,
        )

        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME,
            payload(EventPayload.TYPE_MILESTONE_REACHED, stateAfter = state),
        )

        val notification = manager.activeNotifications.single().notification
        assertEquals(
            context.getString(R.string.partner_milestone_title_named, PARTNER_NAME),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        // A partner event lands on that partner's activity, whichever roster entry is selected.
        val tap = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity.TAB_PARTNER, tap.getStringExtra(MainActivity.EXTRA_START_TAB))
        assertEquals(PAIR_ID, tap.getStringExtra(MainActivity.EXTRA_OPEN_PARTNER))
        assertEquals(VIEW_ACTIVITY, tap.getStringExtra(MainActivity.EXTRA_OPEN_PARTNER_VIEW))
        assertNull(tap.getStringExtra(MainActivity.EXTRA_OPEN_PLAN))
        assertEquals(
            context.getString(
                R.string.partner_event_time_suffix,
                "Alcohol · " + context.resources.getQuantityString(
                    R.plurals.partner_milestone_reached_days, 30, 30,
                ),
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(OCCURRED_AT)),
            ),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    /** ADR #193 §6: a new or edited plan is the one plan event worth an alert. It names the
     *  plan's own title, and unlike an activity burst it alerts again on an edit
     *  (`onlyAlertOnce = false`) while the lockscreen keeps the generic public copy. */
    @Test
    fun planPublished_namesThePlanTitle_andReAlertsOnEdit() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val plan = PlanBody(
            planId = "plan-1",
            title = "Evening wind-down",
            revision = 2,
            updatedAtMs = OCCURRED_AT,
        )

        PartnerNotifications.notifyPartnerEvent(
            context, PAIR_ID, PARTNER_NAME,
            payload(PlanWire.TYPE_PLAN_PUBLISHED, text = "", plan = plan),
        )

        val notification = manager.activeNotifications.single().notification
        assertEquals(
            context.getString(R.string.partner_plan_title_named, PARTNER_NAME),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.partner_plan_body_named, "Evening wind-down"),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        assertEquals(
            "a plan edit must alert again",
            0,
            notification.flags and Notification.FLAG_ONLY_ALERT_ONCE,
        )
        assertEquals(
            context.getString(R.string.partner_event_title),
            notification.publicVersion!!.extras.getString(Notification.EXTRA_TITLE),
        )
        // Owner report 2026-10-31: the tap opened Home, where the plan is unreachable. It must
        // land on the Partner tab with the named plan already open.
        val tap = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity.TAB_PARTNER, tap.getStringExtra(MainActivity.EXTRA_START_TAB))
        assertEquals(plan.planId, tap.getStringExtra(MainActivity.EXTRA_OPEN_PLAN))
    }

    /** §8.4 owner report 2026-10-31: assigning a partner to a rule produced a feed row and no
     *  alert — the event never reached the notify path. Every verb must post the named copy,
     *  and the tap must land on the Partner tab where the change is listed. */
    @Test
    fun ruleChanged_everyVerbPostsNamedCopy_andOpensThePartnerTab() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val expected = mapOf(
            RuleChange.CREATED to R.string.partner_rule_event_created,
            RuleChange.ASSIGNED to R.string.partner_rule_event_assigned,
            RuleChange.UNASSIGNED to R.string.partner_rule_event_unassigned,
            RuleChange.UPDATED to R.string.partner_rule_event_updated,
            RuleChange.DISABLED to R.string.partner_rule_event_disabled,
            RuleChange.DELETED to R.string.partner_rule_event_deleted,
        )
        for ((change, body) in expected) {
            PartnerNotifications.notifyRuleChanged(context, PAIR_ID, PARTNER_NAME, change, null)

            val notification = manager.activeNotifications.last().notification
            assertEquals(
                context.getString(R.string.partner_rule_event_title_named, PARTNER_NAME),
                notification.extras.getString(Notification.EXTRA_TITLE),
            )
            assertEquals(context.getString(body), notification.extras.getString(Notification.EXTRA_TEXT))
            val tap = shadowOf(notification.contentIntent).savedIntent
            assertEquals(MainActivity.TAB_PARTNER, tap.getStringExtra(MainActivity.EXTRA_START_TAB))
            assertNull(tap.getStringExtra(MainActivity.EXTRA_OPEN_PLAN))
            // Owner report 2026-10-31: the tap must select the pair the post concerns.
            assertEquals(PAIR_ID, tap.getStringExtra(MainActivity.EXTRA_OPEN_PARTNER))
            // Owner ask 2026-11-02: and land on Rules & blocks, where the change is listed.
            assertEquals(VIEW_RULES, tap.getStringExtra(MainActivity.EXTRA_OPEN_PARTNER_VIEW))
            // One replace-in-place entry per pair: the six posts never stack.
            assertEquals(1, manager.activeNotifications.size)
        }
    }

    /** Owner report 2026-10-31 ("what have I been added to?"): the body names the app, and a
     *  member of a named batch names the batch; a batch-level row states its size. */
    @Test
    fun ruleChanged_namedIdentityAndBatchContext() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        PartnerNotifications.notifyRuleChanged(
            context, PAIR_ID, PARTNER_NAME, RuleChange.CREATED,
            RuleIdentity(label = "Instagram", targetType = RuleTargetType.APP, batchLabel = "Social media"),
        )
        assertEquals(
            context.getString(R.string.partner_rule_event_added_in_batch, "Instagram", "Social media"),
            manager.activeNotifications.last().notification.extras.getString(Notification.EXTRA_TEXT),
        )

        PartnerNotifications.notifyRuleChanged(
            context, PAIR_ID, PARTNER_NAME, RuleChange.CREATED,
            RuleIdentity(label = "Social media", targetType = RuleTargetType.BATCH, itemCount = 4),
        )
        assertEquals(
            context.resources.getQuantityString(
                R.plurals.partner_rule_event_added_batched, 4, "Social media", 4,
            ),
            manager.activeNotifications.last().notification.extras.getString(Notification.EXTRA_TEXT),
        )

        PartnerNotifications.notifyRuleChanged(
            context, PAIR_ID, PARTNER_NAME, RuleChange.EXPIRED,
            RuleIdentity(label = "TikTok", targetType = RuleTargetType.APP),
        )
        assertEquals(
            context.getString(R.string.partner_rule_event_expired_labeled, "TikTok"),
            manager.activeNotifications.last().notification.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    private fun payload(
        type: String,
        text: String = "streak:7",
        stateAfter: ActivityStateAfter? = null,
        plan: PlanBody? = null,
    ) = EventPayload(type = type, text = text, stateAfter = stateAfter, plan = plan)

    private companion object {
        const val PAIR_ID = "pair-alpha"
        const val PARTNER_NAME = "Mama"
        const val OCCURRED_AT = 1_758_000_000_000L
    }
}
