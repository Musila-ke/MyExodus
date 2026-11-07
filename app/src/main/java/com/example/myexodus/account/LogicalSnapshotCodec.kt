package com.example.myexodus.account

import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RiskWindow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Account plan §2.1 item 11 / identity doc G5: the explicit logical-entity allowlist, shared by
 * the encrypted guest quarantine and the backup payload. Attempt delivery bookkeeping
 * (`event_sequence`) is deliberately not carried: a quarantined guest's pairs are terminated by
 * the transition and a backup never carries pairing state, so a restored row starts with no
 * audience and can never be re-delivered.
 */
internal object LogicalSnapshotCodec {

    fun habit(h: HabitProgress) = JSONObject()
        .put("progressId", h.progressId)
        .put("profileId", h.profileId)
        .put("targetIdentifier", h.targetIdentifier)
        .put("currentCleanDaysStreak", h.currentCleanDaysStreak)
        .put("totalRelapses", h.totalRelapses)
        .put("accessAttemptsCount", h.accessAttemptsCount)
        .put("habitDurationYears", h.habitDurationYears)
        .put("onboardingSeverityLevel", h.onboardingSeverityLevel)
        .putOpt("lastRelapseTimestamp", h.lastRelapseTimestamp)
        .putOpt("assignedPartnershipId", h.assignedPartnershipId)
        .put("allTimeMaxStreakElapsedMs", h.allTimeMaxStreakElapsedMs)
        .put("isSelected", h.isSelected)

    fun habit(j: JSONObject) = HabitProgress(
        progressId = j.getString("progressId"),
        profileId = j.getString("profileId"),
        targetIdentifier = j.getString("targetIdentifier"),
        currentCleanDaysStreak = j.getInt("currentCleanDaysStreak"),
        totalRelapses = j.getInt("totalRelapses"),
        accessAttemptsCount = j.getInt("accessAttemptsCount"),
        habitDurationYears = j.getInt("habitDurationYears"),
        onboardingSeverityLevel = j.getString("onboardingSeverityLevel"),
        lastRelapseTimestamp = j.optNullableLong("lastRelapseTimestamp"),
        assignedPartnershipId = j.optNullableString("assignedPartnershipId"),
        allTimeMaxStreakElapsedMs = j.getLong("allTimeMaxStreakElapsedMs"),
        isSelected = j.getBoolean("isSelected"),
    )

    fun streak(s: RecoveryStreak) = JSONObject()
        .put("streakId", s.streakId)
        .put("habitId", s.habitId)
        .put("recoveryStartAt", s.recoveryStartAt)
        .putOpt("relapsedAt", s.relapsedAt)
        .putOpt("relapseBootCount", s.relapseBootCount)
        .putOpt("relapseElapsedRealtimeMs", s.relapseElapsedRealtimeMs)
        .put("tWallLastObserved", s.tWallLastObserved)
        .put("rBootLastObserved", s.rBootLastObserved)
        .put("bootCountLastObserved", s.bootCountLastObserved)
        .put("currentStreakElapsedMs", s.currentStreakElapsedMs)
        .put("rowVersion", s.rowVersion)

    fun streak(j: JSONObject) = RecoveryStreak(
        streakId = j.getString("streakId"),
        habitId = j.getString("habitId"),
        recoveryStartAt = j.getLong("recoveryStartAt"),
        relapsedAt = j.optNullableLong("relapsedAt"),
        relapseBootCount = j.optNullableLong("relapseBootCount"),
        relapseElapsedRealtimeMs = j.optNullableLong("relapseElapsedRealtimeMs"),
        tWallLastObserved = j.getLong("tWallLastObserved"),
        rBootLastObserved = j.getLong("rBootLastObserved"),
        bootCountLastObserved = j.getLong("bootCountLastObserved"),
        currentStreakElapsedMs = j.getLong("currentStreakElapsedMs"),
        rowVersion = j.getLong("rowVersion"),
    )

    fun milestone(m: MilestoneEvent) = JSONObject()
        .put("streakId", m.streakId)
        .put("milestoneDay", m.milestoneDay)
        .putOpt("achievedAt", m.achievedAt)
        .putOpt("notificationSentAt", m.notificationSentAt)
        .putOpt("acknowledgedAt", m.acknowledgedAt)

    fun milestone(j: JSONObject) = MilestoneEvent(
        streakId = j.getString("streakId"),
        milestoneDay = j.getInt("milestoneDay"),
        achievedAt = j.optNullableLong("achievedAt"),
        notificationSentAt = j.optNullableLong("notificationSentAt"),
        acknowledgedAt = j.optNullableLong("acknowledgedAt"),
    )

    fun attempt(a: AttemptLog) = JSONObject()
        .put("occurredAtEpochMillis", a.occurredAtEpochMillis)
        .put("ruleId", a.ruleId)
        .put("blockedTarget", a.blockedTarget)
        .put("outcome", a.outcome)

    fun attempt(j: JSONObject) = AttemptLog(
        occurredAtEpochMillis = j.getLong("occurredAtEpochMillis"),
        ruleId = j.getString("ruleId"),
        blockedTarget = j.getString("blockedTarget"),
        outcome = j.getString("outcome"),
    )

    fun session(s: RecoverySession) = JSONObject()
        .put("id", s.id)
        .put("technique", s.technique)
        // Schema v25; absent in older backups, which restores as an unlinked row.
        .putOpt("habitId", s.habitId)
        .putOpt("intensityStart", s.intensityStart)
        .putOpt("intensityEnd", s.intensityEnd)
        .put("state", s.state)
        .put("startedAtEpochMs", s.startedAtEpochMs)
        .putOpt("endedAtEpochMs", s.endedAtEpochMs)
        .putOpt("durationSec", s.durationSec)
        .put("zoneId", s.zoneId)
        .put("entry", s.entry)
        .put("falseAlarm", s.falseAlarm)

    fun session(j: JSONObject) = RecoverySession(
        id = j.getString("id"),
        technique = j.getString("technique"),
        habitId = j.optNullableString("habitId"),
        intensityStart = j.optNullableInt("intensityStart"),
        intensityEnd = j.optNullableInt("intensityEnd"),
        state = j.getString("state"),
        startedAtEpochMs = j.getLong("startedAtEpochMs"),
        endedAtEpochMs = j.optNullableLong("endedAtEpochMs"),
        durationSec = j.optNullableInt("durationSec"),
        zoneId = j.getString("zoneId"),
        entry = j.getString("entry"),
        falseAlarm = j.getBoolean("falseAlarm"),
    )

    fun coaching(c: CoachingStep) = JSONObject()
        .put("stepId", c.stepId)
        .put("profileId", c.profileId)
        .put("stepOrder", c.stepOrder)
        .put("actionText", c.actionText)
        .put("associatedTriggerWindow", c.associatedTriggerWindow)
        .put("kind", c.kind)

    fun coaching(j: JSONObject) = CoachingStep(
        stepId = j.getString("stepId"),
        profileId = j.getString("profileId"),
        stepOrder = j.getInt("stepOrder"),
        actionText = j.getString("actionText"),
        associatedTriggerWindow = j.getString("associatedTriggerWindow"),
        kind = j.getString("kind"),
    )

    fun riskWindow(w: RiskWindow) = JSONObject()
        .put("id", w.id)
        .put("startMinute", w.startMinute)
        .put("endMinute", w.endMinute)
        .put("daysMask", w.daysMask)
        .put("enabled", w.enabled)

    fun riskWindow(j: JSONObject) = RiskWindow(
        id = j.getLong("id"),
        startMinute = j.getInt("startMinute"),
        endMinute = j.getInt("endMinute"),
        daysMask = j.getInt("daysMask"),
        enabled = j.getBoolean("enabled"),
    )

    fun policy(p: RecoveryPolicy) = JSONObject()
        .put("defaultFrictionSeconds", p.defaultFrictionSeconds)
        .put("overrideCooldownSeconds", p.overrideCooldownSeconds)

    fun policy(j: JSONObject) = RecoveryPolicy(
        defaultFrictionSeconds = j.getLong("defaultFrictionSeconds"),
        overrideCooldownSeconds = j.getLong("overrideCooldownSeconds"),
    )
}

internal fun JSONObject.putOpt(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

internal fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else getString(key)

internal fun JSONObject.optNullableLong(key: String): Long? =
    if (isNull(key)) null else getLong(key)

internal fun JSONObject.optNullableInt(key: String): Int? =
    if (isNull(key)) null else getInt(key)

internal fun <T> JSONArray.mapObjects(decode: (JSONObject) -> T): List<T> =
    (0 until length()).map { decode(getJSONObject(it)) }
