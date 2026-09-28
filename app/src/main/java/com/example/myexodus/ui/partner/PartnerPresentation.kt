package com.example.myexodus.ui.partner

import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.recovery.RecoveryMirror
import java.time.Instant
import java.time.ZoneId

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
    ) : PartnerAlert

    data class Recovery(
        val technique: String,
        val outcome: String,
        override val atEpochMillis: Long,
    ) : PartnerAlert
}

/**
 * The activity texts the app writes: "blocked:<pkg>" / "override:<pkg>" (§6) and
 * "recovery:<technique>:<outcome>" (W5, [RecoveryMirror]). Any other payload — a repair's
 * "sync_request", a future event type — is not partner activity and returns null rather than
 * being presented as something it is not.
 */
fun parseActivityText(text: String, atEpochMillis: Long): PartnerAlert? = when {
    text.startsWith("override:") -> PartnerAlert.AppActivity(text.removePrefix("override:"), true, atEpochMillis)
    text.startsWith("blocked:") -> PartnerAlert.AppActivity(text.removePrefix("blocked:"), false, atEpochMillis)
    else -> RecoveryMirror.parse(text)?.let { (technique, outcome) ->
        PartnerAlert.Recovery(technique, outcome, atEpochMillis)
    }
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
    /** The peer's own wall-clock of its last shared transition; null = none received yet. */
    val lastEventOccurredAtEpochMillis: Long?,
    /** Req 15 arrival surface: the newest message this pair has sent us, if any. */
    val pinnedMessageText: String? = null,
    val pinnedMessageAtEpochMillis: Long? = null,
)

/** Dashboard summary over the decoded received events, newest first. */
data class AlertSummary(val todayCount: Int, val weekCount: Int, val recent: List<PartnerAlert>)

fun summarizeAlerts(parsedNewestFirst: List<PartnerAlert>, startOfTodayMillis: Long): AlertSummary =
    AlertSummary(
        todayCount = parsedNewestFirst.count { it.atEpochMillis >= startOfTodayMillis },
        weekCount = parsedNewestFirst.size,
        recent = parsedNewestFirst.take(RECENT_LIMIT),
    )

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

private const val RECENT_LIMIT = 5
