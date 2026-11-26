package com.example.myexodus.ui.partner

import com.example.myexodus.accountability.serialization.ActivityStateAfter
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.MonitoringCapability
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleSchedule
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.data.db.entity.PartnerActivityAlert
import com.example.myexodus.data.db.entity.PartnerProtectionGap
import com.example.myexodus.data.db.entity.PartnerRuleChange
import com.example.myexodus.data.db.entity.PartnerSharingAlert
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.recovery.RecoveryMirror
import com.example.myexodus.ui.PillTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

/** Pins the dashboard's pure mapping (plan §7 item 12 / FR3 / §9 rows 457-458). */
class PartnerPresentationTest {

    private val now = 1_700_000_000_000L

    @Test
    fun freshness_neverWhenNoWatermark() {
        assertEquals(SyncFreshness.NEVER, syncFreshness(null, now))
    }

    @Test
    fun freshness_buckets() {
        assertEquals(SyncFreshness.FRESH, syncFreshness(now - 30 * 60_000L, now))
        assertEquals(SyncFreshness.AGING, syncFreshness(now - 3 * 3_600_000L, now))
        assertEquals(SyncFreshness.STALE, syncFreshness(now - 25 * 3_600_000L, now))
    }

    @Test
    fun freshness_clockSkewedFutureTimestamp_readsFreshNeverStale() {
        assertEquals(SyncFreshness.FRESH, syncFreshness(now + 3_600_000L, now))
    }

    @Test
    fun relativeTime_buckets() {
        assertEquals(RelativeTime.JustNow, relativeTime(now, now - 30_000L))
        assertEquals(RelativeTime.Minutes(5), relativeTime(now, now - 5 * 60_000L))
        assertEquals(RelativeTime.Hours(3), relativeTime(now, now - 3 * 3_600_000L))
        assertEquals(RelativeTime.Days(2), relativeTime(now, now - 2 * 86_400_000L))
    }

    @Test
    fun relativeTime_olderThanAWeek_keepsTheRealTimestamp() {
        val old = now - 8 * 86_400_000L
        assertEquals(RelativeTime.Older(old), relativeTime(now, old))
    }

    @Test
    fun chainStatus_maps_everyStoredRepairState() {
        assertEquals(PartnerChainStatus.HEALTHY, chainStatusFor(ChainHead.REPAIR_NONE, forked = false))
        assertEquals(PartnerChainStatus.CATCHING_UP, chainStatusFor(ChainHead.REPAIR_PENDING, forked = false))
        assertEquals(PartnerChainStatus.CATCHING_UP, chainStatusFor(ChainHead.REPAIR_RECOVERY_EXHAUSTED, forked = false))
        assertEquals(PartnerChainStatus.INCOMPLETE, chainStatusFor(ChainHead.REPAIR_CHAIN_INCOMPLETE, forked = false))
        assertEquals(PartnerChainStatus.UNVERIFIED, chainStatusFor(ChainHead.REPAIR_TAMPER_SUSPECTED, forked = false))
        assertEquals(PartnerChainStatus.UNVERIFIED, chainStatusFor(ChainHead.REPAIR_NONE, forked = true))
    }

    @Test
    fun activityText_parsesBothEventKinds() {
        assertEquals(PartnerAlert.AppActivity("com.x", true, 42L), parseActivityText("override:com.x", 42L))
        assertEquals(PartnerAlert.AppActivity("com.x", false, 42L), parseActivityText("blocked:com.x", 42L))
    }

    /** Owner report 2026-11-02: the feed must name the app even when the viewer does not have
     *  it installed — the attempt payload's sender label rides through; old payloads stay null. */
    @Test
    fun alertOf_carriesTheSenderAppLabel_andOldPayloadsStayNull() {
        val withLabel = alertOf(
            EventPayload(text = "blocked:com.ichi2.anki", appLabel = "AnkiDroid"),
            atEpochMillis = 42L,
        ) as PartnerAlert.AppActivity
        assertEquals("AnkiDroid", withLabel.appLabel)

        val old = alertOf(EventPayload(text = "blocked:com.ichi2.anki"), atEpochMillis = 42L)
            as PartnerAlert.AppActivity
        assertNull(old.appLabel)
    }

    @Test
    fun alertOf_mapsEverySharingStoppedType_andKeepsTheStreakLabel() {
        assertEquals(
            PartnerAlert.SharingChanged(
                SharingChange.AVAILABILITY_STOPPED, 42L,
            ),
            alertOf(EventPayload(type = EventPayload.TYPE_AVAILABILITY_SHARING_STOPPED, text = ""), 42L),
        )
        assertEquals(
            PartnerAlert.SharingChanged(SharingChange.CHECKINS_STOPPED, 42L),
            alertOf(EventPayload(type = EventPayload.TYPE_CHECKINS_SHARING_STOPPED, text = ""), 42L),
        )
        val state = ActivityStateAfter(
            daysFree = 3, relapseCount = 0, milestonesReached = emptyList(),
            coachingProgress = 0, eventOccurredAtMs = 42L, habitLabel = "No porn",
        )
        assertEquals(
            PartnerAlert.SharingChanged(SharingChange.STREAK_STOPPED, 42L, "No porn"),
            alertOf(
                EventPayload(
                    type = EventPayload.TYPE_STREAK_SHARING_STOPPED,
                    text = "",
                    stateAfter = state,
                ),
                42L,
            ),
        )
        assertEquals(
            PartnerAlert.SharingChanged(SharingChange.STREAK_STOPPED, 42L, null),
            alertOf(
                EventPayload(
                    type = EventPayload.TYPE_STREAK_SHARING_STOPPED,
                    text = "",
                    stateAfter = state.copy(habitLabel = " "),
                ),
                42L,
            ),
        )
    }

    @Test
    fun sharingAlert_mapsTheStoredRow_andDropsAnUnknownKind() {
        val row = PartnerSharingAlert(
            pairId = "pair", eventId = "evt",
            kind = EventPayload.TYPE_CHECKINS_SHARING_STOPPED, label = null,
            occurredAtMs = now, expiresAtMs = now,
        )
        assertEquals(
            PartnerAlert.SharingChanged(SharingChange.CHECKINS_STOPPED, now, null),
            sharingAlert(row),
        )
        assertNull(sharingAlert(row.copy(kind = "something_else")))
    }

    /** Owner ruling 2026-11-15 (#246): the sign-out pause is a feed row like every revoke. */
    @Test
    fun accountabilityPaused_mapsBothTransports() {
        assertEquals(
            PartnerAlert.SharingChanged(SharingChange.ACCOUNTABILITY_PAUSED, 42L),
            alertOf(EventPayload(type = EventPayload.TYPE_ACCOUNTABILITY_PAUSED, text = ""), 42L),
        )
        assertEquals(
            SharingChange.ACCOUNTABILITY_PAUSED,
            sharingAlert(
                PartnerSharingAlert(
                    pairId = "pair", eventId = "evt",
                    kind = EventPayload.TYPE_ACCOUNTABILITY_PAUSED, label = null,
                    occurredAtMs = now, expiresAtMs = now,
                ),
            )?.let { (it as PartnerAlert.SharingChanged).change },
        )
    }

    /** The derived paused state: newest alert at or before the pause means still paused; any
     *  later alert clears it; with nothing newer, the pause stands. */
    @Test
    fun stillPausedSince_onlyUntilSomeLaterAlertArrives() {
        assertEquals(null, stillPausedSince(null, null))
        assertEquals(100L, stillPausedSince(100L, null))
        assertEquals(100L, stillPausedSince(100L, 100L))
        assertEquals(null, stillPausedSince(100L, 200L))
    }

    @Test
    fun receivedActivityAlert_mapsTheStoredRelapseAndMilestone_andDropsOtherKinds() {
        val relapse = PartnerActivityAlert(
            pairId = "pair", eventId = "evt-1", kind = EventPayload.TYPE_RELAPSE_RECORDED,
            habitLabel = "No porn", endedDays = 12, milestoneDay = null,
            occurredAtMs = now, expiresAtMs = now,
        )
        val milestone = PartnerActivityAlert(
            pairId = "pair", eventId = "evt-2", kind = EventPayload.TYPE_MILESTONE_REACHED,
            habitLabel = "No porn", endedDays = null, milestoneDay = 7,
            occurredAtMs = now, expiresAtMs = now,
        )
        assertEquals(PartnerAlert.Relapse("No porn", 12, now), receivedActivityAlert(relapse))
        assertEquals(PartnerAlert.Milestone("No porn", 7, now), receivedActivityAlert(milestone))
        assertNull(receivedActivityAlert(relapse.copy(kind = "something_else")))
    }

    @Test
    fun activityText_parsesRecoveryMirrorLines() {
        assertEquals(
            PartnerAlert.Recovery("urge_surfing", "completed", 42L),
            parseActivityText(RecoveryMirror.text("urge_surfing", RecoverySession.STATE_COMPLETED), 42L),
        )
        assertEquals(
            PartnerAlert.Recovery("sober", "abandoned", 42L),
            parseActivityText("recovery:sober:abandoned", 42L),
        )
    }

    @Test
    fun activityText_malformedRecoveryLines_areNull_notPresented() {
        assertNull(parseActivityText("recovery:urge_surfing", 42L))
        assertNull(parseActivityText("recovery::completed", 42L))
        assertNull(parseActivityText("recovery:urge_surfing:pending", 42L))
        assertNull(parseActivityText("recovery:urge_surfing:completed:extra", 42L))
    }

    @Test
    fun activityText_nonActivityPayload_isNull_notPresented() {
        assertNull(parseActivityText("sync_request", 42L))
        assertNull(parseActivityText("", 42L))
        assertNull(parseActivityText("something-else", 42L))
    }

    @Test
    fun summarize_countsFromTheLocalDayBoundary_andCapsRecentAtFive() {
        val startOfToday = now - 3 * 3_600_000L
        val newestFirst = (0 until 7).map {
            PartnerAlert.AppActivity("com.x$it", false, startOfToday - it * 3_600_000L)
        }
        val summary = summarizeAlerts(newestFirst, startOfToday, ZoneOffset.UTC)
        assertEquals(7, summary.weekCount)
        assertEquals(1, summary.todayCount)
        assertEquals(5, summary.recent.size)
        assertEquals("com.x0", (summary.recent.first() as PartnerAlert.AppActivity).target)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 7), summary.dailyCounts)
    }

    @Test
    fun summarize_bucketsByCalendarDay_oldestFirstEndingToday() {
        val startOfToday = now - 3 * 3_600_000L
        val alerts = listOf(
            PartnerAlert.AppActivity("today", false, startOfToday),
            PartnerAlert.AppActivity("yesterday", false, startOfToday - 24 * 3_600_000L),
            PartnerAlert.AppActivity("outOfWindow", false, startOfToday - 9 * 24 * 3_600_000L),
        )
        val summary = summarizeAlerts(alerts, startOfToday, ZoneOffset.UTC)
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1), summary.dailyCounts)
    }

    @Test
    fun ruleChange_rowsUseTheOccurrenceMinute_asTheirTimestamp() {
        val row = PartnerRuleChange(
            pairId = "pair", eventId = "evt", ruleId = "uuid", ruleChange = "DISABLED",
            occurredAtMinute = 28_333_333, receivedAtMs = now, expiresAtMs = now,
        )
        assertEquals(
            PartnerAlert.RuleChanged(RuleChange.DISABLED, 28_333_333 * 60_000L),
            ruleChangeAlert(row),
        )
    }

    /** OPD #48: a receiver row's stored schedules reach the feed mapping unchanged, so the
     *  Rules & Blocks card and the activity feed can render them. */
    @Test
    fun ruleChange_rowsCarryTheirStoredSchedules() {
        val after = RuleSchedule(
            alwaysOn = false, days = "1111100", windowStartMinute = 540, windowEndMinute = 1020,
            dailyLimitMs = 0, expiresAtMs = 0,
        )
        val before = after.copy(alwaysOn = true, days = "1111111", windowStartMinute = null, windowEndMinute = null)
        val row = PartnerRuleChange(
            pairId = "pair", eventId = "evt", ruleId = "uuid", ruleChange = "UPDATED",
            ruleSchedule = after.encode(), rulePreviousSchedule = before.encode(),
            occurredAtMinute = 28_333_333, receivedAtMs = now, expiresAtMs = now,
        )
        assertEquals(
            PartnerAlert.RuleChanged(
                RuleChange.UPDATED, 28_333_333 * 60_000L, null, after, before,
            ),
            ruleChangeAlert(row),
        )
    }

    @Test
    fun ruleChange_unknownVerbIsDropped_notShownAsAnotherChange() {
        val row = PartnerRuleChange(
            pairId = "pair", eventId = "evt", ruleId = "uuid", ruleChange = "PAUSED",
            occurredAtMinute = 1, receivedAtMs = now, expiresAtMs = now,
        )
        assertNull(ruleChangeAlert(row))
    }

    @Test
    fun protectionGap_rowsCarryTheLostGrantAndTheAffectedRuleNames() {
        val row = PartnerProtectionGap(
            pairId = "pair", eventId = "evt", capability = "A11Y",
            ruleNames = PartnerProtectionGap.encodeRuleNames(listOf("Instagram", "YouTube Music")),
            occurredAtMs = now, expiresAtMs = now,
        )
        assertEquals(
            PartnerAlert.PermissionLost(
                MonitoringCapability.ACCESSIBILITY,
                listOf("Instagram", "YouTube Music"),
                now,
            ),
            protectionGapAlert(row),
        )
    }

    @Test
    fun protectionGap_unknownTokenIsDropped_notShownAsAnotherGrant() {
        val row = PartnerProtectionGap(
            pairId = "pair", eventId = "evt", capability = "NOTIFICATIONS",
            ruleNames = "Instagram", occurredAtMs = now, expiresAtMs = now,
        )
        assertNull(protectionGapAlert(row))
    }

    @Test
    fun showsPartnerSummary_onlyWithStateOrAPin() {
        // No projection row at all.
        assertEquals(false, showsPartnerSummary(null))
        // A cleared aggregate (last shared streak stopped/deleted) or a bare placeholder row.
        assertEquals(false, showsPartnerSummary(activity(lastEvent = null)))
        // A snapshot-less message writes only the pin (ADR #285) — the card renders it.
        assertEquals(true, showsPartnerSummary(activity(lastEvent = null, pin = "Keep going")))
        // Received shared state, pin or not.
        assertEquals(true, showsPartnerSummary(activity(lastEvent = now)))
        assertEquals(true, showsPartnerSummary(activity(lastEvent = now, pin = "Keep going")))
    }

    private fun activity(lastEvent: Long?, pin: String? = null) = PartnerActivityUi(
        daysFree = 0,
        milestonesReached = emptyList(),
        relapseCount = 0,
        lastEventOccurredAtEpochMillis = lastEvent,
        pinnedMessageText = pin,
    )

    @Test
    fun startOfLocalDay_isTheZoneBoundary() {
        // 2023-11-14T21:30Z → midnight UTC of 2023-11-14.
        val expected = 1_699_920_000_000L
        assertEquals(expected, startOfLocalDay(1_700_000_000_000L, ZoneOffset.UTC))
    }

    @Test
    fun requestStatusTone_onlyRejectionEscalates_andDecisionOutcomesTint() {
        assertEquals(PillTone.POSITIVE, requestStatusTone(ApprovalRequest.STATUS_APPROVED))
        assertEquals(PillTone.NEGATIVE, requestStatusTone(ApprovalRequest.STATUS_REJECTED))
        // A pending request is a fact, not a problem — and an unknown status must never
        // inherit another status's weight.
        assertEquals(PillTone.QUIET, requestStatusTone(ApprovalRequest.STATUS_REQUESTED))
        assertEquals(PillTone.QUIET, requestStatusTone(ApprovalRequest.STATUS_EXPIRED))
        assertEquals(PillTone.QUIET, requestStatusTone(ApprovalRequest.STATUS_CONSUMED))
        assertEquals(PillTone.QUIET, requestStatusTone("SOMETHING_NEW"))
    }
}
