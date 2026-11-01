package com.example.myexodus.ui.partner

import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.data.db.entity.PartnerRuleChange
import com.example.myexodus.recovery.RecoveryMirror
import com.example.myexodus.ui.PillTone
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pure presentation logic for the partner dashboard (plan §7 item 12 / FR3). No Android or
 * crypto dependencies — the screen wires these to real state; the JVM suite pins the mapping.
 */

/** Staleness buckets for the "last synced" line. There is deliberately no LIVE state: the
 *  cadence is best-effort by design (AD-05), so the copy never promises real-time delivery. */
enum class SyncFreshness { NEVER, FRESH, AGING, STALE }

private const val FRESH_WINDOW_MILLIS = 60 * 60 * 1000L
private const val AGING_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

/** A clock-skewed (future) timestamp reads FRESH, never STALE — an over-eager "delayed"
 *  warning would be the dishonest direction. */
fun syncFreshness(lastSyncedAtMillis: Long?, nowMillis: Long): SyncFreshness = when {
    lastSyncedAtMillis == null -> SyncFreshness.NEVER
    nowMillis - lastSyncedAtMillis < FRESH_WINDOW_MILLIS -> SyncFreshness.FRESH
    nowMillis - lastSyncedAtMillis < AGING_WINDOW_MILLIS -> SyncFreshness.AGING
    else -> SyncFreshness.STALE
}

/** Relative-age bucket; the screen owns the localized copy (strings.xml), not this file. */
sealed interface RelativeTime {
    data object JustNow : RelativeTime
    data class Minutes(val value: Long) : RelativeTime
    data class Hours(val value: Long) : RelativeTime
    data class Days(val value: Long) : RelativeTime

    /** Older than a week: show the actual date. */
    data class Older(val epochMillis: Long) : RelativeTime
}

fun relativeTime(nowMillis: Long, thenMillis: Long): RelativeTime {
    val delta = nowMillis - thenMillis
    return when {
        delta < 60_000L -> RelativeTime.JustNow
        delta < 60 * 60_000L -> RelativeTime.Minutes(delta / 60_000L)
        delta < 24 * 60 * 60_000L -> RelativeTime.Hours(delta / (60 * 60_000L))
        delta < 7 * 24 * 60 * 60_000L -> RelativeTime.Days(delta / (24 * 60 * 60_000L))
        else -> RelativeTime.Older(thenMillis)
    }
}

/**
 * The partner's received chain mapped from its repair state (§9 rows 457-458). INCOMPLETE is
 * the neutral "some history could not be synced" state; UNVERIFIED (TAMPER_SUSPECTED / a
 * forked head) is visually distinct and blocks trust — the two are never conflated.
 */
enum class PartnerChainStatus { HEALTHY, CATCHING_UP, INCOMPLETE, UNVERIFIED }

fun chainStatusFor(repairState: String, forked: Boolean): PartnerChainStatus = when {
    forked || repairState == ChainHead.REPAIR_TAMPER_SUSPECTED -> PartnerChainStatus.UNVERIFIED
    repairState in ChainHead.ACTIVE_REPAIR_STATES -> PartnerChainStatus.CATCHING_UP
    repairState == ChainHead.REPAIR_CHAIN_INCOMPLETE -> PartnerChainStatus.INCOMPLETE
    else -> PartnerChainStatus.HEALTHY
}

/** One received activity event, decoded. Sealed so a new activity kind cannot silently render
 *  with another kind's copy: [AppActivity] carries the blocked package, [Recovery] the coarse
 *  technique id + outcome the mirror ships (W5) — never notes or intensity. */
sealed interface PartnerAlert {
    val atEpochMillis: Long

    data class AppActivity(
        val target: String,
        val isOverride: Boolean,
        override val atEpochMillis: Long,
        /** The named batch the rule belongs to; null for a standalone rule (v2 attempt text
         *  carries it after a `|`). */
        val batchLabel: String? = null,
    ) : PartnerAlert

    data class Recovery(
        val technique: String,
        val outcome: String,
        override val atEpochMillis: Long,
    ) : PartnerAlert

    /** Wire v2 (ADR #134): a shared relapse — the streak's own name ([habitLabel], null on
     *  pre-v2 bodies) and the ordinal day-count of the streak it ended ([endedDays], same). */
    data class Relapse(
        val habitLabel: String?,
        val endedDays: Int?,
        override val atEpochMillis: Long,
    ) : PartnerAlert

    /** Wire v2: a milestone the partner reached — the day and the streak's name. */
    data class Milestone(
        val habitLabel: String?,
        val milestoneDay: Int?,
        override val atEpochMillis: Long,
    ) : PartnerAlert

    /** §8.4 RULE_CONTROL: the change verb plus the wire-v2 identity of what changed (a rule
     *  label, or a named batch with its item count). A pre-v2 row carries no identity and
     *  renders the generic copy. */
    data class RuleChanged(
        val change: RuleChange,
        override val atEpochMillis: Long,
        val identity: RuleIdentity? = null,
    ) : PartnerAlert
}

/** A stored RULE_CONTROL row as a feed entry; an unknown verb (a newer sender) is dropped
 *  rather than rendered as a wrong one. */
fun ruleChangeAlert(row: PartnerRuleChange): PartnerAlert? =
    RuleChange.fromWire(row.ruleChange)?.let {
        PartnerAlert.RuleChanged(it, row.occurredAtMinute * 60_000L, row.ruleIdentityOrNull())
    }

/**
 * The activity texts the app writes: "blocked:<pkg>" / "override:<pkg>" (§6) and
 * "recovery:<technique>:<outcome>" (W5, [RecoveryMirror]). Any other payload — a repair's
 * "sync_request", a future event type — is not partner activity and returns null rather than
 * being presented as something it is not.
 */
fun parseActivityText(text: String, atEpochMillis: Long): PartnerAlert? = when {
    text.startsWith("override:") -> appActivity(text.removePrefix("override:"), true, atEpochMillis)
    text.startsWith("blocked:") -> appActivity(text.removePrefix("blocked:"), false, atEpochMillis)
    else -> RecoveryMirror.parse(text)?.let { (technique, outcome) ->
        PartnerAlert.Recovery(technique, outcome, atEpochMillis)
    }
}

/** Attempt text is "<target>" or v2 "<target>|<batch name>" — the batch name is the rule's
 *  own sanitized name, so only the first delimiter splits. */
private fun appActivity(raw: String, isOverride: Boolean, atEpochMillis: Long): PartnerAlert.AppActivity =
    PartnerAlert.AppActivity(
        target = raw.substringBefore('|'),
        isOverride = isOverride,
        atEpochMillis = atEpochMillis,
        batchLabel = raw.substringAfter('|', "").takeIf { it.isNotBlank() },
    )

/**
 * One decoded payload as a feed row. Wire-v2 kinds (ADR #134) render from their `stateAfter`
 * — a relapse tells which streak ended and how long it ran, a milestone which day was reached
 * — and are stamped with the sender's own occurrence time, so a backdated relapse reads as
 * when it happened, not when the mailbox delivered it. Everything else keeps the shipped text
 * parsing; payloads that are not feed material (daily streak snapshots — noise, by design —
 * coaching count changes, sync plumbing) return null.
 */
fun alertOf(payload: EventPayload, atEpochMillis: Long): PartnerAlert? = when (payload.type) {
    EventPayload.TYPE_RELAPSE_RECORDED -> payload.stateAfter?.let {
        PartnerAlert.Relapse(it.habitLabel, it.endedDays, it.eventOccurredAtMs)
    }
    EventPayload.TYPE_MILESTONE_REACHED -> payload.stateAfter?.let {
        PartnerAlert.Milestone(it.habitLabel, it.milestoneDay, it.eventOccurredAtMs)
    }
    else -> parseActivityText(payload.text, atEpochMillis)
}

/**
 * The partner's shared recovery aggregate (plan §4.6 / §8.11 delta), as the dashboard renders
 * it: days free, milestones met, relapse count, and — separately — when the last *event*
 * arrived. Activity recency ([lastEventOccurredAtEpochMillis]) and sync recency
 * (`PartnerDashboardUi.Paired.lastSyncedAtEpochMillis`) are two different facts and are never
 * blended into one line: a partner who simply has not logged anything for three days is not a
 * partner whose sync is broken.
 *
 * Null (the whole object) means no aggregate has ever been received — the card then says so
 * rather than rendering zeros, which would be a claim about the partner's state that the
 * absence of events does not support.
 */
data class PartnerActivityUi(
    val daysFree: Int,
    val milestonesReached: List<Int>,
    val relapseCount: Int,
    /** Count of the partner's active coaching items (never their text, INV-13) — the partner
     *  half of Req 20. Zero when they have not shared recovery. */
    val coachingProgress: Int = 0,
    /** The peer's own wall-clock of its last shared transition; null = none received yet. */
    val lastEventOccurredAtEpochMillis: Long?,
    /** Req 15 arrival surface: the newest message this pair has sent us, if any. */
    val pinnedMessageText: String? = null,
    val pinnedMessageAtEpochMillis: Long? = null,
)

/** Dashboard summary over the decoded received events. */
data class AlertSummary(
    val todayCount: Int,
    val weekCount: Int,
    val recent: List<PartnerAlert>,
    /** One bucket per local day, oldest first, ending today — the activity page's chart. */
    val dailyCounts: List<Int> = emptyList(),
)

/**
 * [parsed] arrives in mailbox order; wire-v2 rows carry the sender's own occurrence time,
 * which may differ from arrival (a backdated relapse). Everything time-based here — the
 * today count, the chart buckets and which rows survive [RECENT_LIMIT] — reads that
 * timestamp, so the feed reports when things *happened*, never merely when the mail landed.
 *
 * Day buckets come from [zone]'s calendar days, not fixed 24-hour slices, so a DST shift
 * cannot move an event into a neighbouring bar.
 */
fun summarizeAlerts(
    parsed: List<PartnerAlert>,
    startOfTodayMillis: Long,
    zone: ZoneId,
): AlertSummary {
    val today = Instant.ofEpochMilli(startOfTodayMillis).atZone(zone).toLocalDate()
    val counts = IntArray(WEEK_DAYS)
    parsed.forEach { alert ->
        val day = Instant.ofEpochMilli(alert.atEpochMillis).atZone(zone).toLocalDate()
        val index = WEEK_DAYS - 1 - ChronoUnit.DAYS.between(day, today).toInt()
        if (index in 0 until WEEK_DAYS) counts[index]++
    }
    return AlertSummary(
        todayCount = parsed.count { it.atEpochMillis >= startOfTodayMillis },
        weekCount = parsed.size,
        recent = parsed.sortedByDescending { it.atEpochMillis }.take(RECENT_LIMIT),
        dailyCounts = counts.toList(),
    )
}

/** Start of the local day containing [nowMillis] — the "today" boundary of the summary. */
fun startOfLocalDay(nowMillis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

/** One disable-approval request as the dashboard shows it (Step 13). [role] decides which side
 *  of the pair this device is on: RESPONDER rows are actionable, REQUESTER rows status-only. */
data class ApprovalUi(
    val requestEventId: String,
    val role: String,
    val status: String,
    val message: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

/**
 * §6 line 240: the hash-chain verification badge gates responding. UNVERIFIED (a forked or
 * tamper-suspected chain, §9 row 458) blocks approve/reject outright — a blocked chain mints
 * no responses. Every other state is verified: the request row exists only because its own
 * chain link passed verification at append time; CATCHING_UP is surfaced as a caveat, not a
 * block, since the gap it reflects is elsewhere in the chain, not in this event.
 */
fun canRespondToApprovals(status: PartnerChainStatus): Boolean = status != PartnerChainStatus.UNVERIFIED

/**
 * The one place a request status becomes a [PillTone] — with [requestStatusText] in the screen
 * this keeps the two halves of the pill (word and weight) from ever disagreeing.
 */
fun requestStatusTone(status: String): PillTone = when (status) {
    ApprovalRequest.STATUS_APPROVED -> PillTone.POSITIVE
    ApprovalRequest.STATUS_REJECTED -> PillTone.NEGATIVE
    else -> PillTone.QUIET
}

private const val RECENT_LIMIT = 5
private const val WEEK_DAYS = 7
