package com.example.myexodus.accountability.sync

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myexodus.BuildConfig
import com.example.myexodus.MainActivity
import com.example.myexodus.R
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.ActivityStateAfter
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.notifications.NotificationAccess
import com.example.myexodus.ui.partner.shortKeyId
import java.text.DateFormat
import java.util.Date

/**
 * Partner-event notification surface (AD-11/NFR2): rendered locally only AFTER the mailbox
 * envelope was verified and appended. Every post names the partner it concerns (owner ruling
 * 2026-09-29 — a device can hold several pairs, so "your partner" is not an answer): the
 * unlocked tray copy carries the pairing label, while the lockscreen public version keeps the
 * generic copy (FR-13's redaction contract). Each pair posts under its own notification id,
 * so one partner's entry can never silently replace another's pending item. Channels are
 * created lazily at post time (creation is idempotent); denied POST_NOTIFICATIONS skips
 * silently (Step 16 owns the permission flow + banner).
 */
object PartnerNotifications {

    const val CHANNEL_PARTNER_ALERTS = "partner_alerts"
    const val NOTIF_PARTNER_EVENT = 2001

    /** §8 Step 12 disable-approval pair — own channel so a user can silence decisions
     *  separately from activity alerts (plan §8 Step 16's channel list). */
    const val CHANNEL_APPROVAL_REQUESTS = "approval_requests"
    const val NOTIF_APPROVAL_REQUEST = 2002
    const val NOTIF_APPROVAL_DECISION = 2003

    /** #16: the responder's announcement that the partner ended the partnership. */
    const val NOTIF_PARTNERSHIP_ENDED = 2004

    /** Partner SOS (V1 Req 35, plan §8.2/§8 Step 7): the "Reach Out" nudge channel. Its
     *  presence + importance are two thirds of the §8.12 pushEligible formula; the channel
     *  itself is created by the Step 7 notification flow. */
    /** `_v3`: the app can silently LOWER its own channel's importance but never raise it
     *  (AOSP; device-confirmed both directions 2026-10-04). The pre-fix post path carried
     *  DEFAULT and silently dented `partner_nudges_v2` 4→3 on every foreground nudge receipt,
     *  and no in-place re-assert can undo that — so the id bumps again and v2 joins the
     *  startup cleanup list. */
    const val CHANNEL_PARTNER_NUDGES = "partner_nudges_v3"
    const val CHANNEL_PARTNER_NUDGES_LEGACY = "partner_nudges"
    const val CHANNEL_PARTNER_NUDGES_V2 = "partner_nudges_v2"
    const val NOTIF_PARTNER_NUDGE = 2005

    /** Plan §8 Step 16's third channel: LOW importance, "used only for internal/debug
     *  visibility, not shown to the user in production builds" — so the channel is created
     *  only in debug builds. No production surface posts to it by design. */
    const val CHANNEL_SYNC_STATUS = "sync_status"

    /** Per-pair notification id (§19.5 multi-partner): base + pairId hash, so N partners'
     *  entries coexist in the tray. A hash collision only degrades to the old
     *  replace-in-place behavior — never a lost alert for a different pair's item. */
    internal fun idFor(base: Int, pairId: String): Int = base + pairId.hashCode()

    fun ensureSyncStatusChannel(context: Context) {
        if (!BuildConfig.DEBUG) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC_STATUS,
                context.getString(R.string.partner_channel_sync_status_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /** SOS §8.14 (V1 Req 35, plan Step 7): the check-ins channel, created at application
     *  startup so the §8.12 pushEligible snapshot can report true before the first nudge.
     *  IMPORTANCE_HIGH (deviation from the plan's DEFAULT, owner ruling 2026-10-04): a
     *  reach-out must surface as a heads-up banner, and only importance >= HIGH produces
     *  one. Android cannot raise an existing channel's importance in place — and, since an
     *  app can silently LOWER its own channel (see the `_v3` const note), a dented channel
     *  is retired by id version: the current id takes HIGH on every skin, and every prior
     *  id is deleted at startup to avoid duplicate settings entries. Private on the
     *  lockscreen, vibration on first creation. */
    fun ensurePartnerNudgesChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PARTNER_NUDGES,
                context.getString(R.string.partner_channel_nudges_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                enableVibration(true)
            },
        )
        manager.deleteNotificationChannel(CHANNEL_PARTNER_NUDGES_LEGACY)
        manager.deleteNotificationChannel(CHANNEL_PARTNER_NUDGES_V2)
    }

    /** Partner-activity alert. The per-pair id collapses one partner's burst into a single
     *  tray entry; `onlyAlertOnce` additionally keeps that burst to one alert (a
     *  partner-activity pull can append several events at once) — the trade is that further
     *  updates while the user leaves the notification untouched are silent, which is the
     *  non-spammy side of §11.7. Unlocked copy names the sender and, for a wire-v2 relapse
     *  or milestone, states which streak and when it happened (owner mandate 2026-10-04); a
     *  motivational message shows the message itself (owner feedback 2026-10-01). The
     *  lockscreen public version stays generic (FR-13) — none of the rich copy reaches it. */
    fun notifyPartnerEvent(context: Context, pairId: String, partnerName: String, payload: EventPayload) {
        val rich = when (payload.type) {
            EventPayload.TYPE_RELAPSE_RECORDED ->
                context.getString(R.string.partner_relapse_title_named, partnerName) to
                    relapseBody(context, payload.stateAfter)
            EventPayload.TYPE_MILESTONE_REACHED ->
                context.getString(R.string.partner_milestone_title_named, partnerName) to
                    milestoneBody(context, payload.stateAfter)
            EventPayload.TYPE_MOTIVATIONAL_MESSAGE ->
                context.getString(R.string.partner_message_title_named, partnerName) to
                    payload.text.takeIf { it.isNotBlank() }
                        ?.let { context.getString(R.string.partner_message_quoted, it) }
            else -> null to null
        }
        val richBody = rich.second
        post(
            context,
            CHANNEL_PARTNER_ALERTS,
            context.getString(R.string.partner_channel_activity_name),
            NotificationManager.IMPORTANCE_HIGH,
            idFor(NOTIF_PARTNER_EVENT, pairId),
            R.string.partner_event_title,
            R.string.partner_event_text,
            onlyAlertOnce = true,
            // The rich title is only claimed when its details are actually present — a
            // pre-wire-v2 sender's relapse degrades to the generic named copy, never a title
            // that promises a streak name the body cannot show.
            namedTitle = if (richBody != null) {
                rich.first
            } else {
                context.getString(R.string.partner_event_title_named, partnerName)
            },
            namedText = richBody,
        )
    }

    /** Wire v2 (ADR #134): "«streak» · ended a N-day streak · «when»"; null when the sender's
     *  body predates the wire — the caller then keeps the generic copy. */
    private fun relapseBody(context: Context, state: ActivityStateAfter?): String? {
        if (state == null) return null
        val label = state.habitLabel ?: return null
        val days = state.endedDays ?: return null
        val ended = context.getString(R.string.partner_relapse_ended_days, days)
        return withOccurredAt(context, "$label · $ended", state.eventOccurredAtMs)
    }

    private fun milestoneBody(context: Context, state: ActivityStateAfter?): String? {
        if (state == null) return null
        val label = state.habitLabel ?: return null
        val day = state.milestoneDay ?: return null
        val reached = context.resources.getQuantityString(
            R.plurals.partner_milestone_reached_days, day, day,
        )
        return withOccurredAt(context, "$label · $reached", state.eventOccurredAtMs)
    }

    private fun withOccurredAt(context: Context, body: String, occurredAtMs: Long): String {
        val at = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(occurredAtMs))
        return context.getString(R.string.partner_event_time_suffix, body, at)
    }

    /** A verified disable-approval request arrived — the responder's actionable item.
     *  Plan Step 16 channel spec: `approval_requests` is HIGH importance. */
    fun notifyApprovalRequest(context: Context, pairId: String, partnerName: String) =
        post(context, CHANNEL_APPROVAL_REQUESTS, context.getString(R.string.partner_channel_requests_name), NotificationManager.IMPORTANCE_HIGH, idFor(NOTIF_APPROVAL_REQUEST, pairId), R.string.approval_request_title, R.string.approval_request_text, namedTitle = context.getString(R.string.approval_request_title_named, partnerName))

    /** The partner answered our request — informational. */
    fun notifyApprovalDecision(context: Context, pairId: String, partnerName: String) =
        post(context, CHANNEL_APPROVAL_REQUESTS, context.getString(R.string.partner_channel_requests_name), NotificationManager.IMPORTANCE_HIGH, idFor(NOTIF_APPROVAL_DECISION, pairId), R.string.approval_decision_title, R.string.approval_decision_text, namedTitle = context.getString(R.string.approval_decision_title_named, partnerName))

    /** #16: the partner ended the partnership and this device's shared data was shredded — the
     *  responder is told who, when, and that the erase happened (owner-observed defect: the
     *  roster silently dropped with nothing on the other device). Posted only by the deletion
     *  composition, and only after the shred returned success. */
    fun notifyPartnershipEnded(
        context: Context,
        pairId: String,
        partnerName: String,
        endedAtEpochMillis: Long?,
    ) {
        val endedAt = endedAtEpochMillis?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }
        post(
            context,
            CHANNEL_PARTNER_ALERTS,
            context.getString(R.string.partner_channel_activity_name),
            NotificationManager.IMPORTANCE_HIGH,
            idFor(NOTIF_PARTNERSHIP_ENDED, pairId),
            R.string.partner_ended_title,
            R.string.partner_ended_text,
            namedTitle = context.getString(R.string.partner_ended_title_named, partnerName),
            namedText = if (endedAt != null) {
                context.getString(R.string.partner_ended_text_named, endedAt)
            } else {
                context.getString(R.string.partner_ended_text_named_no_time)
            },
        )
    }

    /** SOS §8.17/FR-20: the foreground FCM receipt posts immediately — generic copy only
     *  (FR-19), the Step 7 channel, and the fixed FR-19 tag so a follow-up nudge replaces the
     *  tray entry (one replaceable entry by design). The tap carries the same launcher extras
     *  the background system path delivers, so §8.18's routing is identical for both. HIGH
     *  matches the channel's created importance: this call is also the background-wake
     *  lazy-create path (§8.17), so it must never land below HIGH. */
    fun notifyPartnerNudge(context: Context, nudgeId: String) =
        post(
            context,
            CHANNEL_PARTNER_NUDGES,
            context.getString(R.string.partner_channel_nudges_name),
            NotificationManager.IMPORTANCE_HIGH,
            NOTIF_PARTNER_NUDGE,
            R.string.partner_nudge_title,
            R.string.partner_nudge_text,
            tag = PartnerNudgePush.TAG,
            pushNudgeId = nudgeId,
        )

    /** SOS §6.9 step 4 (plan §8.16): after the acknowledgment batch commits, the tray entry
     *  for the reach-out is retired. FR-19's fixed tag + id mean at most one such entry
     *  exists (replace-in-place), so this single cancel is the whole contract — and it is
     *  scoped to exactly that tag/id, never the partner-alert entries on other channels. */
    fun cancelPartnerNudge(context: Context) {
        NotificationManagerCompat.from(context).cancel(PartnerNudgePush.TAG, NOTIF_PARTNER_NUDGE)
    }

    // Suppressed: the gate is NotificationAccess.canPost (the only way in), which lint can't
    // trace through a helper.
    @SuppressLint("MissingPermission")
    private fun post(
        context: Context,
        channelId: String,
        channelName: String,
        importance: Int,
        notificationId: Int,
        titleRes: Int,
        textRes: Int,
        namedTitle: String? = null,
        namedText: String? = null,
        onlyAlertOnce: Boolean = false,
        tag: String? = null,
        pushNudgeId: String? = null,
    ) {
        if (!NotificationAccess.canPost(context)) return
        // Create only when missing (device-observed 2026-10-04, both rig devices): re-creating
        // an EXISTING channel with the poster's importance is not a no-op on XOS — a nudge post
        // carrying DEFAULT rewrote partner_nudges_v2 from HIGH(4) to DEFAULT(3) on a
        // non-user-locked channel, so every later post was tray-only, no heads-up banner.
        // Importance belongs to the channel's creation (ensurePartnerNudgesChannel / the
        // first lazy post), never to a later poster's call.
        val channelManager = context.getSystemService(NotificationManager::class.java)
        if (channelManager.getNotificationChannel(channelId) == null) {
            channelManager.createNotificationChannel(NotificationChannel(channelId, channelName, importance))
        }
        // Owner ask 2026-10-01: the tap lands on Home — the pinned message card (messages)
        // and the day hero live there, so that is where the partner event is visible. The
        // request code is this object's own (extras don't count toward PendingIntent
        // identity, and the shared code 0 would let this post rewrite Protection's
        // Settings-landing extras on the same MainActivity intent).
        val openAppIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_HOME)
        if (pushNudgeId != null) {
            openAppIntent
                .putExtra(PartnerNudgePush.TYPE_KEY, PartnerNudgePush.TYPE_PARTNER_NUDGE)
                .putExtra(PartnerNudgePush.NUDGE_ID_KEY, pushNudgeId)
        }
        val openApp = PendingIntent.getActivity(
            context,
            NOTIF_PARTNER_EVENT,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // FR-13: the lockscreen public version stays generic by construction (no alias, event
        // type, count, or coaching/motivational content), so the redaction shows generic copy
        // rather than hiding the notification entirely. The unlocked content may name the
        // partner (owner ruling 2026-09-29) and carry a motivational message's body (owner
        // feedback 2026-10-01) — neither ever reaches the public version, which is built from
        // the generic resources alone.
        val title = namedTitle ?: context.getString(titleRes)
        val text = namedText ?: context.getString(textRes)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(context.getString(titleRes))
                    .setContentText(context.getString(textRes))
                    .build(),
            )
            .setOnlyAlertOnce(onlyAlertOnce)
            .setContentIntent(openApp)
            .build()
        val manager = NotificationManagerCompat.from(context)
        if (tag != null) {
            manager.notify(tag, notificationId, notification)
        } else {
            manager.notify(notificationId, notification)
        }
    }
}

/** The name a partner is shown under in notification copy — the pairing label, else the short
 *  key id (the Partner tab's #63 convention), never a fabricated name. */
fun displayNameOf(enrollment: EnrollmentRecord): String =
    enrollment.partnerLabel ?: shortKeyId(enrollment.partnerSigningKeyId)
