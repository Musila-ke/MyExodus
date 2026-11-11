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
import com.example.myexodus.accountability.serialization.MonitoringCapability
import com.example.myexodus.accountability.serialization.PlanWire
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.example.myexodus.notifications.NotificationAccess
import com.example.myexodus.notifications.NotificationTapTarget
import com.example.myexodus.ui.appLabel
import com.example.myexodus.ui.partner.PartnerAlert
import com.example.myexodus.ui.partner.VIEW_ACTIVITY
import com.example.myexodus.ui.partner.VIEW_CHECKINS
import com.example.myexodus.ui.partner.VIEW_REQUESTS
import com.example.myexodus.ui.partner.VIEW_RULES
import com.example.myexodus.ui.partner.parseActivityText
import com.example.myexodus.ui.partner.shortKeyId
import com.example.myexodus.ui.recovery.outcomeLabel
import com.example.myexodus.ui.recovery.techniqueTitle
import java.text.DateFormat
import java.util.Date

/**
 * Partner-event notification surface (AD-11/NFR2): rendered locally only AFTER the mailbox
 * envelope was verified and appended. Every post names the partner it concerns (owner ruling
 * 2026-09-29 — a device can hold several pairs, so "your partner" is not an answer): the
 * unlocked tray copy carries the pairing label, while the lockscreen public version keeps the
 * generic copy (FR-13's redaction contract). Every post is keyed by (tag, id) with the tag
 * scoped to its category AND pair, so one partner's entry can never silently replace another's
 * pending item — not even when their id hashes collide across categories (owner warning
 * 2026-10-23; see [partnerTag]). Channels are
 * created lazily at post time (creation is idempotent); denied POST_NOTIFICATIONS skips
 * silently (Step 16 owns the permission flow + banner).
 */
object PartnerNotifications {

    /** Plan Step 9 / task #29: one of the three immutable channels. `_v1` (not the old
     *  `partner_alerts` id) because an app can silently LOWER its own channel's importance
     *  and never raise it — a fresh id takes HIGH on every skin, and the old id is deleted at
     *  startup so the settings screen never lists both. */
    const val CHANNEL_ACCOUNTABILITY_EVENTS = "partner_accountability_events_v1"
    internal const val CHANNEL_PARTNER_ALERTS_LEGACY = "partner_alerts"
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

    /** Plan Step 8 (§8.12): the server's six-hour availability notice channel. The id matches
     *  `CHANNEL_AVAILABILITY` in functions/dms-processor.js — a rename here renames what the
     *  worker sends to. HIGH because the notice is user-visible and time-sensitive (§8.12);
     *  the payload itself is fixed generic copy. */
    const val CHANNEL_AVAILABILITY_ALERTS = "availability_alerts_v1"

    /** Retired 2026-10-28 (owner ruling): the server no longer asks the user to check in.
     *  The liveness probe is data-only, and this tray-visible nudge is deleted at startup. */
    internal const val CHANNEL_CHECKIN_REMINDERS_LEGACY = "checkin_reminders_v1"

    /** Legacy per-pair id (§19.5 multi-partner): base + pairId hash. Kept because posting
     *  keys are now (tag, id) — the pair-and-category tag below is what makes entries unique.
     *  The bare id alone collides: the bases are consecutive, so a pair whose hash is 1-3
     *  higher than another's produced the same id for a different category (owner warning
     *  2026-10-23). Different tags keep such entries apart. */
    internal fun idFor(base: Int, pairId: String): Int = base + pairId.hashCode()

    /** Collision-free post identity: the NotificationManager key is (tag, id), so scoping the
     *  tag by category AND pair makes two entries equal only when they are the same category
     *  for the same pair — the one case that must replace in place. */
    internal fun partnerTag(category: String, pairId: String): String =
        "partner:$category:$pairId"

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

    /** Plan Step 6/Step 9: created at startup so the health reading and the first alert agree
     *  on the channel's existence before any event arrives. Create-only — re-creating an
     *  existing channel can silently rewrite its importance (device-observed 2026-10-04). */
    fun ensureAccountabilityEventsChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ACCOUNTABILITY_EVENTS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ACCOUNTABILITY_EVENTS,
                    context.getString(R.string.partner_channel_activity_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        // Retired id: no device should keep a second, dentable partner-activity channel in
        // system settings beside the immutable one.
        manager.deleteNotificationChannel(CHANNEL_PARTNER_ALERTS_LEGACY)
    }

    /** Plan Step 8: created at startup so the six-hour notice's channel exists before the
     *  first dispatch. Create-only, same device-observed reason as
     *  [ensureAccountabilityEventsChannel] — re-creating an existing channel can silently
     *  rewrite its importance. */
    fun ensureAvailabilityAlertsChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_AVAILABILITY_ALERTS) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AVAILABILITY_ALERTS,
                context.getString(R.string.partner_channel_availability_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /** Owner ruling 2026-10-28: check-in is automatic. The old server nudge told the user to
     *  open the app for normal Doze/OEM freezing; the channel is retired so no device keeps a
     *  dead "check-in reminders" entry in system settings. Partner availability alerts are a
     *  different channel and are untouched. */
    fun deleteLegacyCheckinChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel(CHANNEL_CHECKIN_REMINDERS_LEGACY)
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

    /** Partner-activity alert. The per-pair tag/id collapses one partner's events into a single
     *  tray entry. Every post alerts — owner ruling 2026-10-31: the tray entry was updating
     *  silently for a new message/relapse because the post carried FLAG_ONLY_ALERT_ONCE, and
     *  "it is in the shade but never appeared on screen" is the observed defect. §11.7's
     *  one-alert burst rule is superseded for partner events: each event a partner chose to
     *  share is worth a heads-up, and the single replace-in-place entry still prevents tray
     *  stacking. Unlocked copy names the sender and, for a wire-v2 relapse or milestone,
     *  states which streak and when it happened (owner mandate 2026-10-04); a motivational
     *  message shows the message itself (owner feedback 2026-10-01). The lockscreen public
     *  version stays generic (FR-13) — none of the rich copy reaches it. */
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
            // Owner ruling 2026-11-09: the revoke names the streak that left the partner's
            // view. Always rich — a nameless body is still the honest signal.
            EventPayload.TYPE_STREAK_SHARING_STOPPED ->
                context.getString(R.string.partner_sharing_stopped_title_named, partnerName) to
                    (payload.stateAfter?.habitLabel?.takeIf { it.isNotBlank() }
                        ?.let { context.getString(R.string.partner_sharing_stopped_body_labeled, it) }
                        ?: context.getString(R.string.partner_sharing_stopped_body))
            // Owner ruling 2026-11-10: the check-ins revoke names the silence that follows.
            EventPayload.TYPE_CHECKINS_SHARING_STOPPED ->
                context.getString(R.string.partner_checkins_stopped_title_named, partnerName) to
                    context.getString(R.string.partner_checkins_stopped_body)
            // Owner directive 2026-11-09: the streak-management acts name themselves, so the
            // partner hears "shared / renamed / deleted a streak", not "new activity". Always
            // rich — a pre-v3 sender's label-less body is still the honest signal.
            EventPayload.TYPE_STREAK_SHARED ->
                context.getString(R.string.partner_streak_shared_title_named, partnerName) to
                    (payload.stateAfter?.habitLabel?.takeIf { it.isNotBlank() }
                        ?.let { context.getString(R.string.partner_streak_shared_body_labeled, it) }
                        ?: context.getString(R.string.partner_streak_shared_body))
            EventPayload.TYPE_STREAK_RENAMED ->
                context.getString(R.string.partner_streak_renamed_title_named, partnerName) to
                    (payload.stateAfter?.habitLabel?.takeIf { it.isNotBlank() }
                        ?.let { context.getString(R.string.partner_streak_renamed_body_labeled, it) }
                        ?: context.getString(R.string.partner_streak_renamed_body))
            EventPayload.TYPE_STREAK_DELETED ->
                context.getString(R.string.partner_streak_deleted_title_named, partnerName) to
                    (payload.stateAfter?.habitLabel?.takeIf { it.isNotBlank() }
                        ?.let { context.getString(R.string.partner_streak_deleted_body_labeled, it) }
                        ?: context.getString(R.string.partner_streak_deleted_body))
            // ADR #193: a new or edited plan is the one plan event worth an alert — progress
            // is filtered out before this call.
            PlanWire.TYPE_PLAN_PUBLISHED ->
                context.getString(R.string.partner_plan_title_named, partnerName) to
                    payload.plan?.title?.takeIf { it.isNotBlank() }
                        ?.let { context.getString(R.string.partner_plan_body_named, it) }
            // Owner ruling 2026-11-05: an attempt alert says what happened and to which app,
            // the same named shape the rule alerts use — never "New activity, open the app".
            EventPayload.TYPE_ACTIVITY -> activityCopy(context, partnerName, payload)
            else -> null to null
        }
        val richBody = rich.second
        post(
            context,
            CHANNEL_ACCOUNTABILITY_EVENTS,
            context.getString(R.string.partner_channel_activity_name),
            NotificationManager.IMPORTANCE_HIGH,
            idFor(NOTIF_PARTNER_EVENT, pairId),
            R.string.partner_event_title,
            R.string.partner_event_text,
            // The rich title is only claimed when its details are actually present — a
            // pre-wire-v2 sender's relapse degrades to the generic named copy, never a title
            // that promises a streak name the body cannot show.
            namedTitle = if (richBody != null) {
                rich.first
            } else {
                context.getString(R.string.partner_event_title_named, partnerName)
            },
            namedText = richBody,
            tag = partnerTag("event", pairId),
            // Owner report 2026-10-31: the plan alert opened Home, where the plan is not even
            // reachable — the tap must land on the plan it names.
            openPlanId = payload.plan?.planId
                ?.takeIf { payload.type == PlanWire.TYPE_PLAN_PUBLISHED },
            // Owner ask 2026-11-02: an event is listed on the partner's Activity page — the
            // tap must land there, not stop at the hub. A published plan keeps its own
            // landing (the plan surface wins in pairDeepLinkView).
            openPartnerView = VIEW_ACTIVITY
                .takeIf { payload.type != PlanWire.TYPE_PLAN_PUBLISHED },
            openPairId = pairId,
        )
    }

    /** The attempt/recovery half of [notifyPartnerEvent] (owner ruling 2026-11-05): the same
     *  named, verb-first shape the rule alerts use — title = who + what kind of act, body =
     *  which app (and batch) or which recovery step. Text this phone cannot decode keeps the
     *  generic named copy rather than guessing at it. */
    private fun activityCopy(
        context: Context,
        partnerName: String,
        payload: EventPayload,
    ): Pair<String?, String?> = when (val alert = parseActivityText(payload.text, 0L)) {
        is PartnerAlert.AppActivity -> {
            val local = appLabel(context, alert.target)
            val label = local.takeIf { it != alert.target } ?: payload.appLabel ?: alert.target
            val title = if (alert.isOverride) {
                R.string.partner_activity_override_title_named
            } else {
                R.string.partner_activity_blocked_title_named
            }
            val body = if (alert.batchLabel == null) {
                context.getString(
                    if (alert.isOverride) R.string.partner_activity_override_body
                    else R.string.partner_activity_blocked_body,
                    label,
                )
            } else {
                context.getString(
                    if (alert.isOverride) R.string.partner_activity_override_in_batch
                    else R.string.partner_activity_blocked_in_batch,
                    label,
                    alert.batchLabel,
                )
            }
            context.getString(title, partnerName) to body
        }
        is PartnerAlert.Recovery ->
            context.getString(R.string.partner_activity_recovery_title_named, partnerName) to
                context.getString(
                    R.string.partner_activity_recovery_body,
                    context.getString(techniqueTitle(alert.technique)),
                    context.getString(outcomeLabel(alert.outcome.uppercase())),
                )
        else -> null to null
    }

    /** §8.4 RULE_CONTROL (owner report 2026-10-31): a rule the partner assigned this device to
     *  changed — and the sender's promise is "you'll be told", so the feed row alone was not
     *  delivery. The wire carries the rule's UUID and a closed verb only (CBOR allowlist), so
     *  the copy names the change, never the app. Same per-pair tray entry as other partner
     *  events; the tap lands on the Partner tab where the change is listed. */
    fun notifyRuleChanged(
        context: Context,
        pairId: String,
        partnerName: String,
        change: RuleChange,
        identity: RuleIdentity?,
    ) {
        post(
            context,
            CHANNEL_ACCOUNTABILITY_EVENTS,
            context.getString(R.string.partner_channel_activity_name),
            NotificationManager.IMPORTANCE_HIGH,
            idFor(NOTIF_PARTNER_EVENT, pairId),
            R.string.partner_event_title,
            R.string.partner_event_text,
            namedTitle = context.getString(R.string.partner_rule_event_title_named, partnerName),
            namedText = ruleEventBody(context, change, identity),
            tag = partnerTag("event", pairId),
            // Owner ask 2026-11-02: the change is listed on Rules & blocks — land there.
            openPartnerView = VIEW_RULES,
            openPairId = pairId,
        )
    }

    /** Owner directive 2026-11-10: the other device lost a grant a rule the partner was
     *  assigned to needs. One per-pair entry, same tray key as every other partner event;
     *  the body states each lost capability's real effect and names the affected rules. */
    fun notifyPermissionLost(
        context: Context,
        pairId: String,
        partnerName: String,
        lost: Map<MonitoringCapability, List<String>>,
    ) {
        if (lost.isEmpty()) return
        val body = lost.entries.joinToString("\n") { (capability, rules) ->
            context.getString(
                R.string.partner_permission_lost_line,
                context.getString(capability.effectRes()),
                ruleNames(context, rules),
            )
        }
        post(
            context,
            CHANNEL_ACCOUNTABILITY_EVENTS,
            context.getString(R.string.partner_channel_activity_name),
            NotificationManager.IMPORTANCE_HIGH,
            idFor(NOTIF_PARTNER_EVENT, pairId),
            R.string.partner_event_title,
            R.string.partner_event_text,
            namedTitle = context.getString(R.string.partner_permission_lost_title_named, partnerName),
            namedText = body,
            tag = partnerTag("event", pairId),
            // Owner ruling 2026-11-10: the tap lands on the phone report that now reads Limited;
            // the loss itself stays listed on Rules & blocks and in the feed.
            openPartnerView = VIEW_CHECKINS,
            openPairId = pairId,
        )
    }

    private fun MonitoringCapability.effectRes(): Int = when (this) {
        MonitoringCapability.ACCESSIBILITY -> R.string.partner_permission_lost_accessibility
        MonitoringCapability.USAGE_ACCESS -> R.string.partner_permission_lost_usage
        MonitoringCapability.OVERLAY -> R.string.partner_permission_lost_overlay
        MonitoringCapability.WEBSITE_FILTER -> R.string.partner_permission_lost_vpn
    }

    private fun ruleNames(context: Context, rules: List<String>): String {
        val shown = rules.take(3).joinToString(", ")
        val extra = rules.size - 3
        return if (extra > 0) {
            context.getString(R.string.partner_permission_lost_more, shown, extra)
        } else {
            shown
        }
    }

    /** The body names the rule/batch the wire identified; a pre-v2 sender keeps the generic
     *  copy. A batch row states its item count; a member row names its parent batch. */
    private fun ruleEventBody(context: Context, change: RuleChange, identity: RuleIdentity?): String {
        if (identity == null) {
            return context.getString(
                when (change) {
                    RuleChange.CREATED -> R.string.partner_rule_event_created
                    RuleChange.ASSIGNED -> R.string.partner_rule_event_assigned
                    RuleChange.UNASSIGNED -> R.string.partner_rule_event_unassigned
                    RuleChange.UPDATED -> R.string.partner_rule_event_updated
                    RuleChange.DISABLED -> R.string.partner_rule_event_disabled
                    RuleChange.DELETED -> R.string.partner_rule_event_deleted
                    RuleChange.EXPIRED -> R.string.partner_rule_event_expired
                },
            )
        }
        val label = identity.label
        if (identity.targetType == RuleTargetType.BATCH) {
            val counted = (identity.itemCount ?: 0L).toInt()
            return context.resources.getQuantityString(
                when (change) {
                    RuleChange.CREATED, RuleChange.ASSIGNED -> R.plurals.partner_rule_event_added_batched
                    RuleChange.UPDATED -> R.plurals.partner_rule_event_updated_batched
                    RuleChange.DISABLED -> R.plurals.partner_rule_event_disabled_batched
                    RuleChange.DELETED -> R.plurals.partner_rule_event_deleted_batched
                    RuleChange.UNASSIGNED -> R.plurals.partner_rule_event_unassigned_batched
                    RuleChange.EXPIRED -> R.plurals.partner_rule_event_expired_batched
                },
                counted,
                label,
                counted,
            )
        }
        val batch = identity.batchLabel
        return if (batch == null) {
            context.getString(
                when (change) {
                    RuleChange.CREATED, RuleChange.ASSIGNED -> R.string.partner_rule_event_added_labeled
                    RuleChange.UPDATED -> R.string.partner_rule_event_updated_labeled
                    RuleChange.DISABLED -> R.string.partner_rule_event_disabled_labeled
                    RuleChange.DELETED -> R.string.partner_rule_event_deleted_labeled
                    RuleChange.UNASSIGNED -> R.string.partner_rule_event_unassigned_labeled
                    RuleChange.EXPIRED -> R.string.partner_rule_event_expired_labeled
                },
                label,
            )
        } else {
            context.getString(
                when (change) {
                    RuleChange.CREATED, RuleChange.ASSIGNED -> R.string.partner_rule_event_added_in_batch
                    RuleChange.UPDATED -> R.string.partner_rule_event_updated_in_batch
                    RuleChange.DISABLED -> R.string.partner_rule_event_disabled_in_batch
                    RuleChange.DELETED -> R.string.partner_rule_event_deleted_in_batch
                    RuleChange.UNASSIGNED -> R.string.partner_rule_event_unassigned_in_batch
                    RuleChange.EXPIRED -> R.string.partner_rule_event_expired_in_batch
                },
                label,
                batch,
            )
        }
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
        post(context, CHANNEL_APPROVAL_REQUESTS, context.getString(R.string.partner_channel_requests_name), NotificationManager.IMPORTANCE_HIGH, idFor(NOTIF_APPROVAL_REQUEST, pairId), R.string.approval_request_title, R.string.approval_request_text, namedTitle = context.getString(R.string.approval_request_title_named, partnerName), tag = partnerTag("request", pairId), openPartnerView = VIEW_REQUESTS, openPairId = pairId)

    /** The partner answered our request — informational. */
    fun notifyApprovalDecision(context: Context, pairId: String, partnerName: String) =
        post(context, CHANNEL_APPROVAL_REQUESTS, context.getString(R.string.partner_channel_requests_name), NotificationManager.IMPORTANCE_HIGH, idFor(NOTIF_APPROVAL_DECISION, pairId), R.string.approval_decision_title, R.string.approval_decision_text, namedTitle = context.getString(R.string.approval_decision_title_named, partnerName), tag = partnerTag("decision", pairId), openPartnerView = VIEW_REQUESTS, openPairId = pairId)

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
            CHANNEL_ACCOUNTABILITY_EVENTS,
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
            tag = partnerTag("ended", pairId),
        )
    }

    /** SOS §8.17/FR-20: the FCM receipt posts immediately, the Step 7 channel, and the fixed
     *  FR-19 tag so a follow-up nudge replaces the tray entry (one replaceable entry by
     *  design). D16 (owner ruling 2026-10-23): the unlocked copy names the sender when the
     *  receiving phone can resolve one from its own pairing label; a null name keeps the
     *  generic copy, which is also the lockscreen public version either way (FR-13). The tap
     *  carries the launcher extras so §8.18 routing is identical for every delivery path. HIGH
     *  matches the channel's created importance: this call is also the cold-start lazy-create
     *  path (§8.17), so it must never land below HIGH. */
    fun notifyPartnerNudge(context: Context, nudgeId: String, partnerName: String? = null) =
        post(
            context,
            CHANNEL_PARTNER_NUDGES,
            context.getString(R.string.partner_channel_nudges_name),
            NotificationManager.IMPORTANCE_HIGH,
            NOTIF_PARTNER_NUDGE,
            R.string.partner_nudge_title,
            R.string.partner_nudge_text,
            namedText = partnerName?.let {
                context.getString(R.string.partner_nudge_text_named, it)
            },
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
        tag: String? = null,
        pushNudgeId: String? = null,
        openPlanId: String? = null,
        openPartnerView: String? = null,
        openPairId: String? = null,
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
        // and the day hero live there, so that is where the partner event is visible. A plan
        // event is the exception (owner report 2026-10-31): it lands on the plan itself,
        // because Home does not even reach the Plans surface. The
        // request code is this object's own (extras don't count toward PendingIntent
        // identity, and the shared code 0 would let this post rewrite Protection's
        // Settings-landing extras on the same MainActivity intent). Extras also don't make
        // two intents distinct, so every partner post would otherwise share ONE PendingIntent
        // under FLAG_UPDATE_CURRENT — a partner-event post silently stripped the nudge
        // extras off the reach-out notification's tap (owner warning 2026-10-23). The data
        // URI is part of identity, so each tag (category+pair, nudge, test) gets its own.
        val openAppIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setData(NotificationTapTarget.of(tag ?: "id-$notificationId"))
            .putExtra(
                MainActivity.EXTRA_START_TAB,
                if (openPlanId != null || openPartnerView != null || openPairId != null) {
                    MainActivity.TAB_PARTNER
                } else {
                    MainActivity.TAB_HOME
                },
            )
        if (openPlanId != null) {
            openAppIntent.putExtra(MainActivity.EXTRA_OPEN_PLAN, openPlanId)
        }
        // Owner report 2026-10-31: a tap must land on the pair the post concerns, even when
        // another roster entry is selected — the extra switches PartnerScreen's selection.
        if (openPairId != null) {
            openAppIntent.putExtra(MainActivity.EXTRA_OPEN_PARTNER, openPairId)
        }
        // Owner ask 2026-11-02: the tap lands on the subpage the post concerns.
        if (openPartnerView != null) {
            openAppIntent.putExtra(MainActivity.EXTRA_OPEN_PARTNER_VIEW, openPartnerView)
        }
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
