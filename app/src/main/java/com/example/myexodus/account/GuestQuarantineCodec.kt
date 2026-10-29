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
 * Account plan §8.4: the wire form of one quarantine record, stored only inside the encrypted
 * file. Versioned and strict on decode — an unknown or malformed payload is "unreadable", never
 * a partial import (§6.3.6: no silently dropped entities).
 *
 * Attempt delivery bookkeeping (`event_sequence`) is deliberately not carried: the guest pairs
 * it referred to are terminated by the same transition (plan §6.2.9), so an imported row starts
 * with no audience and can never be re-delivered.
 */
internal object GuestQuarantineCodec {

    const val VERSION = 1

    fun encode(record: GuestQuarantineRecord): String {
        val json = JSONObject()
            .put("version", VERSION)
            .put("transitionId", record.transitionId)
            .put("guestUid", record.guestUid)
            .put("importOperationId", record.importOperationId)
            .put("intent", record.intent.name)
            .put("stagedAtMs", record.stagedAtMs)
            .putOpt("targetUid", record.targetUid)
            .putOpt("importedAtMs", record.importedAtMs)
            .putOpt("summary", record.summary?.let(::encodeSummary))

        val s = record.snapshot
        json.put("habits", JSONArray().apply { s.habits.forEach { put(encodeHabit(it)) } })
        json.put("streaks", JSONArray().apply { s.streaks.forEach { put(encodeStreak(it)) } })
        json.put("milestones", JSONArray().apply { s.milestones.forEach { put(encodeMilestone(it)) } })
        json.put("attempts", JSONArray().apply { s.attempts.forEach { put(encodeAttempt(it)) } })
        json.put("sessions", JSONArray().apply { s.sessions.forEach { put(encodeSession(it)) } })
        json.put("coaching", JSONArray().apply { s.coaching.forEach { put(encodeCoaching(it)) } })
        json.put("riskWindows", JSONArray().apply { s.riskWindows.forEach { put(encodeWindow(it)) } })
        json.putOpt("policy", s.policy?.let(::encodePolicy))
        return json.toString()
    }

    /** @throws org.json.JSONException on any structural mismatch. */
    fun decode(text: String): GuestQuarantineRecord {
        val json = JSONObject(text)
        require(json.getInt("version") == VERSION) { "unsupported quarantine version" }
        return GuestQuarantineRecord(
            transitionId = json.getString("transitionId"),
            guestUid = json.getString("guestUid"),
            importOperationId = json.getString("importOperationId"),
            intent = TransitionIntent.valueOf(json.getString("intent")),
            stagedAtMs = json.getLong("stagedAtMs"),
            targetUid = json.optNullableString("targetUid"),
            importedAtMs = json.optNullableLong("importedAtMs"),
            summary = json.optJSONObject("summary")?.let(::decodeSummary),
            snapshot = GuestSnapshot(
                habits = json.getJSONArray("habits").map(::decodeHabit),
                streaks = json.getJSONArray("streaks").map(::decodeStreak),
                milestones = json.getJSONArray("milestones").map(::decodeMilestone),
                attempts = json.getJSONArray("attempts").map(::decodeAttempt),
                sessions = json.getJSONArray("sessions").map(::decodeSession),
                coaching = json.getJSONArray("coaching").map(::decodeCoaching),
                riskWindows = json.getJSONArray("riskWindows").map(::decodeWindow),
                policy = json.optJSONObject("policy")?.let(::decodePolicy),
            ),
        )
    }

    private fun encodeSummary(s: ImportSummary) = JSONObject()
        .put("inserted", s.inserted)
        .put("updated", s.updated)
        .put("deduplicated", s.deduplicated)
        .put("conflicts", s.conflicts)

    private fun decodeSummary(j: JSONObject) = ImportSummary(
        inserted = j.getInt("inserted"),
        updated = j.getInt("updated"),
        deduplicated = j.getInt("deduplicated"),
        conflicts = j.getInt("conflicts"),
    )

    private fun encodeHabit(h: HabitProgress) = JSONObject()
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

    private fun decodeHabit(j: JSONObject) = HabitProgress(
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

    private fun encodeStreak(s: RecoveryStreak) = JSONObject()
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

    private fun decodeStreak(j: JSONObject) = RecoveryStreak(
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

    private fun encodeMilestone(m: MilestoneEvent) = JSONObject()
        .put("streakId", m.streakId)
        .put("milestoneDay", m.milestoneDay)
        .putOpt("achievedAt", m.achievedAt)
        .putOpt("notificationSentAt", m.notificationSentAt)
        .putOpt("acknowledgedAt", m.acknowledgedAt)

    private fun decodeMilestone(j: JSONObject) = MilestoneEvent(
        streakId = j.getString("streakId"),
        milestoneDay = j.getInt("milestoneDay"),
        achievedAt = j.optNullableLong("achievedAt"),
        notificationSentAt = j.optNullableLong("notificationSentAt"),
        acknowledgedAt = j.optNullableLong("acknowledgedAt"),
    )

    private fun encodeAttempt(a: AttemptLog) = JSONObject()
        .put("occurredAtEpochMillis", a.occurredAtEpochMillis)
        .put("ruleId", a.ruleId)
        .put("blockedTarget", a.blockedTarget)
        .put("outcome", a.outcome)

    private fun decodeAttempt(j: JSONObject) = AttemptLog(
        occurredAtEpochMillis = j.getLong("occurredAtEpochMillis"),
        ruleId = j.getString("ruleId"),
        blockedTarget = j.getString("blockedTarget"),
        outcome = j.getString("outcome"),
    )

    private fun encodeSession(s: RecoverySession) = JSONObject()
        .put("id", s.id)
        .put("technique", s.technique)
        .putOpt("intensityStart", s.intensityStart)
        .putOpt("intensityEnd", s.intensityEnd)
        .put("state", s.state)
        .put("startedAtEpochMs", s.startedAtEpochMs)
        .putOpt("endedAtEpochMs", s.endedAtEpochMs)
        .putOpt("durationSec", s.durationSec)
        .put("zoneId", s.zoneId)
        .put("entry", s.entry)
        .put("falseAlarm", s.falseAlarm)

    private fun decodeSession(j: JSONObject) = RecoverySession(
        id = j.getString("id"),
        technique = j.getString("technique"),
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

    private fun encodeCoaching(c: CoachingStep) = JSONObject()
        .put("stepId", c.stepId)
        .put("profileId", c.profileId)
        .put("stepOrder", c.stepOrder)
        .put("actionText", c.actionText)
        .put("associatedTriggerWindow", c.associatedTriggerWindow)
        .put("kind", c.kind)

    private fun decodeCoaching(j: JSONObject) = CoachingStep(
        stepId = j.getString("stepId"),
        profileId = j.getString("profileId"),
        stepOrder = j.getInt("stepOrder"),
        actionText = j.getString("actionText"),
        associatedTriggerWindow = j.getString("associatedTriggerWindow"),
        kind = j.getString("kind"),
    )

    private fun encodeWindow(w: RiskWindow) = JSONObject()
        .put("id", w.id)
        .put("startMinute", w.startMinute)
        .put("endMinute", w.endMinute)
        .put("daysMask", w.daysMask)
        .put("enabled", w.enabled)

    private fun decodeWindow(j: JSONObject) = RiskWindow(
        id = j.getLong("id"),
        startMinute = j.getInt("startMinute"),
        endMinute = j.getInt("endMinute"),
        daysMask = j.getInt("daysMask"),
        enabled = j.getBoolean("enabled"),
    )

    private fun encodePolicy(p: RecoveryPolicy) = JSONObject()
        .put("defaultFrictionSeconds", p.defaultFrictionSeconds)
        .put("overrideCooldownSeconds", p.overrideCooldownSeconds)

    private fun decodePolicy(j: JSONObject) = RecoveryPolicy(
        defaultFrictionSeconds = j.getLong("defaultFrictionSeconds"),
        overrideCooldownSeconds = j.getLong("overrideCooldownSeconds"),
    )

    private fun JSONObject.putOpt(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key)) null else getLong(key)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (isNull(key)) null else getInt(key)

    private fun <T> JSONArray.map(decode: (JSONObject) -> T): List<T> =
        (0 until length()).map { decode(getJSONObject(it)) }
}
