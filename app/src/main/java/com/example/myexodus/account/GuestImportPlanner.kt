package com.example.myexodus.account

import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RiskWindow
import java.util.Locale
import java.util.UUID

enum class ImportConflictTable { STREAKS, MILESTONES, COACHING, RISK_WINDOWS }

enum class ImportConflictReason {
    /** A guest row references a row the snapshot does not contain (§6.3.2 schema/FK check). */
    BROKEN_REFERENCE,
    COACHING_LIMIT,
    WINDOW_LIMIT,
}

data class ImportConflict(
    val table: ImportConflictTable,
    val reason: ImportConflictReason,
    val count: Int,
)

/** Everything one import transaction writes; empty when [canApply] is false (§6.3.6). */
data class ImportPlan(
    val summary: ImportSummary,
    val conflicts: List<ImportConflict>,
    val habitsToInsert: List<HabitProgress>,
    val habitsToUpdate: List<HabitProgress>,
    val streaksToInsert: List<RecoveryStreak>,
    val milestonesToInsert: List<MilestoneEvent>,
    val milestonesToUpdate: List<MilestoneEvent>,
    val attemptsToInsert: List<AttemptLog>,
    val sessionsToInsert: List<RecoverySession>,
    val coachingToInsert: List<CoachingStep>,
    val riskWindowsToInsert: List<RiskWindow>,
    val policy: RecoveryPolicy?,
) {
    val canApply: Boolean get() = conflicts.isEmpty()
}

/**
 * Account plan §6.3: the additive, natural-key planner. Pure — it reads no database and writes
 * nothing, so the same function produces the pre-commit dry run and the set applied inside the
 * import transaction. Applying it twice is a no-op because every natural key includes the row's
 * canonical identity (habit name, streak start, attempt timestamp+target+outcome, session
 * content, coaching text, window bounds), which is exactly what makes a crashed import safe to
 * retry (plan §8.4 "retry must not duplicate").
 */
class GuestImportPlanner(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    fun plan(
        guest: GuestSnapshot,
        target: TargetSnapshot,
        includePortablePreferences: Boolean,
    ): ImportPlan {
        val conflicts = validate(guest).toMutableList()
        var deduplicated = 0

        // Risk windows first: a coaching step references one by id, and ids are remapped.
        val targetWindows = target.riskWindows
        val takenWindowIds = targetWindows.map { it.id }.toMutableSet()
        var nextWindowId = (takenWindowIds.maxOrNull() ?: 0L) + 1
        val windowIdByKey = targetWindows.associate { windowKey(it) to it.id }.toMutableMap()
        val windowIdMap = mutableMapOf<Long, Long>()
        val windowsToInsert = mutableListOf<RiskWindow>()
        val guestWindows = guest.riskWindows.sortedWith(
            compareBy({ it.startMinute }, { it.endMinute }, { it.daysMask }),
        )
        for (window in guestWindows) {
            val known = windowIdByKey[windowKey(window)]
            if (known != null) {
                windowIdMap[window.id] = known
                deduplicated++
                continue
            }
            if (targetWindows.size + windowsToInsert.size >= RiskWindow.MAX_WINDOWS) {
                conflicts += ImportConflict(
                    ImportConflictTable.RISK_WINDOWS,
                    ImportConflictReason.WINDOW_LIMIT,
                    1,
                )
                continue
            }
            val id = if (window.id != 0L && window.id !in takenWindowIds) {
                window.id
            } else {
                nextWindowId++
            }
            takenWindowIds += id
            nextWindowId = maxOf(nextWindowId, id + 1)
            windowIdByKey[windowKey(window)] = id
            windowIdMap[window.id] = id
            windowsToInsert += window.copy(id = id)
        }

        val targetCoaching = target.coaching
        val coachingKeys = targetCoaching.map { coachingKey(it) }.toMutableSet()
        val perKind = targetCoaching.groupingBy { it.kind }.eachCount().toMutableMap()
        val nextOrder = mutableMapOf<String, Int>()
        val takenStepIds = targetCoaching.map { it.stepId }.toMutableSet()
        val coachingToInsert = mutableListOf<CoachingStep>()
        val guestCoaching = guest.coaching.sortedWith(
            compareBy({ it.kind }, { it.stepOrder }, { it.actionText }),
        )
        for (step in guestCoaching) {
            if (!coachingKeys.add(coachingKey(step))) {
                deduplicated++
                continue
            }
            val count = perKind[step.kind] ?: 0
            if (count >= CoachingStep.MAX_PER_KIND) {
                conflicts += ImportConflict(
                    ImportConflictTable.COACHING,
                    ImportConflictReason.COACHING_LIMIT,
                    1,
                )
                continue
            }
            val order = nextOrder.getOrPut(step.kind) {
                (targetCoaching.filter { it.kind == step.kind }.maxOfOrNull { it.stepOrder } ?: -1) + 1
            }
            val id = if (step.stepId !in takenStepIds) step.stepId else newId()
            takenStepIds += id
            perKind[step.kind] = count + 1
            nextOrder[step.kind] = order + 1
            val window = step.associatedTriggerWindow.toLongOrNull()
                ?.let { windowIdMap[it] }?.toString().orEmpty()
            coachingToInsert += step.copy(
                stepId = id,
                profileId = target.profileId,
                stepOrder = order,
                associatedTriggerWindow = window,
            )
        }

        // Habits: natural key = trimmed, case-folded label (no unique index exists in the
        // schema, so the key is the domain rule §6.3.3 asks to derive from it).
        val guestHabitKey = guest.habits.associate { it.progressId to habitKey(it) }
        val targetHabitByKey = target.habits
            .filter { it.profileId == target.profileId }
            .groupBy { habitKey(it) }
            .mapValues { it.value.minBy { habit -> habit.progressId } }
        val newHabitIdByKey = mutableMapOf<String, String>()
        val habitMap = mutableMapOf<String, String>()
        for (habit in guest.habits.sortedBy { it.progressId }) {
            val key = guestHabitKey.getValue(habit.progressId)
            val existing = targetHabitByKey[key]
            habitMap[habit.progressId] = if (existing != null) {
                deduplicated++
                existing.progressId
            } else {
                newHabitIdByKey.getOrPut(key) { newId() }
            }
        }

        val takenStreakIds = target.streaks.map { it.streakId }.toMutableSet()
        val insertedStreakIds = mutableSetOf<String>()
        val guestStreaksByHabit = guest.streaks.groupBy { it.habitId }
        val guestMilestonesByStreak = guest.milestones.groupBy { it.streakId }
        val streaksToInsert = mutableListOf<RecoveryStreak>()
        val milestonesToInsert = mutableListOf<MilestoneEvent>()
        val absorbedMilestones = mutableMapOf<String, MutableList<MilestoneEvent>>()
        val activeByTargetHabit = target.streaks
            .filter { it.relapsedAt == null }
            .associateBy { it.habitId }
            .toMutableMap()
        val streakByHabitAndStart = target.streaks
            .associateBy { it.habitId to it.recoveryStartAt }
            .toMutableMap()
        val importedEnded = mutableMapOf<String, MutableList<RecoveryStreak>>()
        val importedActive = mutableMapOf<String, RecoveryStreak>()
        val absorbedActiveElapsed = mutableMapOf<String, Long>()

        for (habit in guest.habits.sortedWith(compareBy({ it.progressId }))) {
            val targetHabitId = habitMap.getValue(habit.progressId)
            val streaks = guestStreaksByHabit[habit.progressId].orEmpty()
                .sortedBy { it.recoveryStartAt }
            for (streak in streaks) {
                val known = streakByHabitAndStart[targetHabitId to streak.recoveryStartAt]
                if (known != null) {
                    val rows = guestMilestonesByStreak[streak.streakId].orEmpty()
                    if (known.streakId in insertedStreakIds) {
                        rows.forEach { mergeIntoPending(milestonesToInsert, known.streakId, it) }
                    } else {
                        absorbedMilestones.getOrPut(known.streakId) { mutableListOf() } += rows
                    }
                    deduplicated++
                    continue
                }
                if (streak.relapsedAt == null) {
                    val running = activeByTargetHabit[targetHabitId]
                    if (running != null) {
                        // One active streak per habit: the target's keeps running, the guest's
                        // milestone facts are preserved on it, and its elapsed time still counts
                        // toward the habit's all-time record below.
                        absorbedMilestones.getOrPut(running.streakId) { mutableListOf() } +=
                            guestMilestonesByStreak[streak.streakId].orEmpty()
                        absorbedActiveElapsed.merge(
                            targetHabitId,
                            streak.currentStreakElapsedMs,
                            ::maxOf,
                        )
                        deduplicated++
                        continue
                    }
                }
                val id = if (streak.streakId !in takenStreakIds) streak.streakId else newId()
                takenStreakIds += id
                val copy = streak.copy(streakId = id, habitId = targetHabitId)
                streaksToInsert += copy
                insertedStreakIds += id
                streakByHabitAndStart[targetHabitId to streak.recoveryStartAt] = copy
                if (streak.relapsedAt == null) {
                    activeByTargetHabit[targetHabitId] = copy
                    importedActive[targetHabitId] = copy
                } else {
                    importedEnded.getOrPut(targetHabitId) { mutableListOf() } += copy
                }
                guestMilestonesByStreak[streak.streakId].orEmpty().forEach { row ->
                    milestonesToInsert += row.copy(streakId = id)
                }
            }
        }

        val targetMilestoneByKey = target.milestones
            .associateBy { it.streakId to it.milestoneDay }
            .toMutableMap()
        val milestonesToUpdate = mutableListOf<MilestoneEvent>()
        for ((streakId, rows) in absorbedMilestones) {
            for (row in rows) {
                val key = streakId to row.milestoneDay
                val current = targetMilestoneByKey[key]
                if (current == null) {
                    if (milestonesToInsert.none { it.streakId == streakId && it.milestoneDay == row.milestoneDay }) {
                        milestonesToInsert += row.copy(streakId = streakId)
                    }
                } else {
                    val merged = current.copy(
                        achievedAt = current.achievedAt ?: row.achievedAt,
                        notificationSentAt = current.notificationSentAt ?: row.notificationSentAt,
                        acknowledgedAt = current.acknowledgedAt ?: row.acknowledgedAt,
                    )
                    if (merged != current) {
                        targetMilestoneByKey[key] = merged
                        val index = milestonesToUpdate.indexOfFirst {
                            it.streakId == streakId && it.milestoneDay == row.milestoneDay
                        }
                        if (index >= 0) milestonesToUpdate[index] = merged else milestonesToUpdate += merged
                    }
                }
            }
        }

        // §6.3.4: derived habit values come from the canonical streak rows that now exist,
        // additively — a counter is never lowered and a replay adds nothing.
        val dayMs = 86_400_000L
        fun durationOf(s: RecoveryStreak): Long =
            s.relapsedAt?.minus(s.recoveryStartAt) ?: s.currentStreakElapsedMs

        // The profile's selected habit drives every screen; inserting the guest's habit
        // unselected into an account with no selection would make the import invisible. The
        // target's existing selection is never overridden.
        val targetHasSelection = target.habits.any { it.profileId == target.profileId && it.isSelected }
        val autoSelectKey = if (targetHasSelection) {
            null
        } else {
            guest.habits.filter { it.isSelected }
                .mapNotNull { guestHabitKey[it.progressId] }
                .firstOrNull { newHabitIdByKey.containsKey(it) }
                ?: newHabitIdByKey.keys.minOrNull()
        }

        val habitsToInsert = mutableListOf<HabitProgress>()
        for ((key, id) in newHabitIdByKey) {
            val source = guest.habits
                .filter { guestHabitKey[it.progressId] == key }
                .minBy { it.progressId }
            val ended = importedEnded[id].orEmpty()
            val active = importedActive[id]
            val canonicalMax = (ended + listOfNotNull(active)).maxOfOrNull(::durationOf) ?: 0
            habitsToInsert += HabitProgress(
                progressId = id,
                profileId = target.profileId,
                targetIdentifier = source.targetIdentifier.trim(),
                currentCleanDaysStreak = active?.let { (it.currentStreakElapsedMs / dayMs).toInt() } ?: 0,
                totalRelapses = ended.size,
                accessAttemptsCount = 0,
                habitDurationYears = source.habitDurationYears,
                onboardingSeverityLevel = source.onboardingSeverityLevel,
                lastRelapseTimestamp = ended.mapNotNull { it.relapsedAt }.maxOrNull(),
                // Partner state is never carried: guest pairs are terminated by the transition.
                assignedPartnershipId = null,
                allTimeMaxStreakElapsedMs = maxOf(source.allTimeMaxStreakElapsedMs, canonicalMax),
                isSelected = key == autoSelectKey,
            )
        }
        val habitsToUpdate = mutableListOf<HabitProgress>()
        for (habit in target.habits.filter { it.profileId == target.profileId }) {
            val ended = importedEnded[habit.progressId].orEmpty()
            val active = importedActive[habit.progressId]
            val absorbedMax = absorbedActiveElapsed[habit.progressId] ?: 0L
            if (ended.isEmpty() && active == null && absorbedMax == 0L) continue
            val canonicalMax = maxOf(
                (ended + listOfNotNull(active)).maxOfOrNull(::durationOf) ?: 0,
                absorbedMax,
            )
            val guestHabitMax = guest.habits
                .filter { habitMap[it.progressId] == habit.progressId }
                .maxOfOrNull { it.allTimeMaxStreakElapsedMs } ?: 0
            val lastRelapse = maxOf(
                habit.lastRelapseTimestamp ?: Long.MIN_VALUE,
                ended.mapNotNull { it.relapsedAt }.maxOrNull() ?: Long.MIN_VALUE,
            )
            val updated = habit.copy(
                currentCleanDaysStreak = active
                    ?.let { (it.currentStreakElapsedMs / dayMs).toInt() }
                    ?: habit.currentCleanDaysStreak,
                totalRelapses = habit.totalRelapses + ended.size,
                lastRelapseTimestamp = if (lastRelapse == Long.MIN_VALUE) {
                    habit.lastRelapseTimestamp
                } else {
                    lastRelapse
                },
                allTimeMaxStreakElapsedMs = maxOf(
                    habit.allTimeMaxStreakElapsedMs,
                    guestHabitMax,
                    canonicalMax,
                ),
            )
            if (updated != habit) habitsToUpdate += updated
        }

        val attemptKeys = target.attempts.map(::attemptKey).toMutableSet()
        val attemptsToInsert = mutableListOf<AttemptLog>()
        for (attempt in guest.attempts.sortedWith(
            compareBy({ it.occurredAtEpochMillis }, { it.ruleId }, { it.blockedTarget }),
        )) {
            if (!attemptKeys.add(attemptKey(attempt))) {
                deduplicated++
                continue
            }
            attemptsToInsert += attempt.copy(id = 0, eventSequence = null)
        }

        val sessionIds = target.sessions.map { it.id }.toMutableSet()
        val sessionContents = target.sessions.map(::sessionContent).toMutableSet()
        val sessionsToInsert = mutableListOf<RecoverySession>()
        for (session in guest.sessions.sortedWith(
            compareBy({ it.startedAtEpochMs }, { it.id }),
        )) {
            if (!sessionContents.add(sessionContent(session))) {
                deduplicated++
                continue
            }
            val id = if (session.id !in sessionIds) session.id else newId()
            sessionIds += id
            sessionsToInsert += session.copy(id = id, eventSequence = null)
        }

        // §6.3.5: target singleton preferences stay by default; the allowlisted portable
        // preferences (recovery friction/cooldown) are imported only on explicit opt-in.
        val policy = if (includePortablePreferences) guest.policy?.takeIf { it != target.policy } else null

        val inserted = habitsToInsert.size + streaksToInsert.size + milestonesToInsert.size +
            attemptsToInsert.size + sessionsToInsert.size + coachingToInsert.size +
            windowsToInsert.size + if (policy != null) 1 else 0
        return ImportPlan(
            summary = ImportSummary(
                inserted = inserted,
                updated = habitsToUpdate.size + milestonesToUpdate.size,
                deduplicated = deduplicated,
                conflicts = conflicts.sumOf { it.count },
            ),
            conflicts = conflicts,
            habitsToInsert = habitsToInsert,
            habitsToUpdate = habitsToUpdate,
            streaksToInsert = streaksToInsert,
            milestonesToInsert = milestonesToInsert,
            milestonesToUpdate = milestonesToUpdate,
            attemptsToInsert = attemptsToInsert,
            sessionsToInsert = sessionsToInsert,
            coachingToInsert = coachingToInsert,
            riskWindowsToInsert = windowsToInsert,
            policy = policy,
        )
    }

    /** §6.3.2: every reference must resolve inside the snapshot, or nothing is activated. */
    private fun validate(guest: GuestSnapshot): List<ImportConflict> {
        val conflicts = mutableListOf<ImportConflict>()
        val habitIds = guest.habits.map { it.progressId }.toSet()
        val orphanStreaks = guest.streaks.count { it.habitId !in habitIds }
        if (orphanStreaks > 0) {
            conflicts += ImportConflict(
                ImportConflictTable.STREAKS,
                ImportConflictReason.BROKEN_REFERENCE,
                orphanStreaks,
            )
        }
        val streakIds = guest.streaks.map { it.streakId }.toSet()
        val orphanMilestones = guest.milestones.count { it.streakId !in streakIds }
        if (orphanMilestones > 0) {
            conflicts += ImportConflict(
                ImportConflictTable.MILESTONES,
                ImportConflictReason.BROKEN_REFERENCE,
                orphanMilestones,
            )
        }
        val windowIds = guest.riskWindows.map { it.id.toString() }.toSet()
        val orphanSteps = guest.coaching.count {
            it.associatedTriggerWindow.isNotBlank() && it.associatedTriggerWindow !in windowIds
        }
        if (orphanSteps > 0) {
            conflicts += ImportConflict(
                ImportConflictTable.COACHING,
                ImportConflictReason.BROKEN_REFERENCE,
                orphanSteps,
            )
        }
        return conflicts
    }

    private fun mergeIntoPending(
        pending: MutableList<MilestoneEvent>,
        streakId: String,
        row: MilestoneEvent,
    ) {
        val index = pending.indexOfFirst {
            it.streakId == streakId && it.milestoneDay == row.milestoneDay
        }
        if (index < 0) {
            pending += row.copy(streakId = streakId)
            return
        }
        val current = pending[index]
        pending[index] = current.copy(
            achievedAt = current.achievedAt ?: row.achievedAt,
            notificationSentAt = current.notificationSentAt ?: row.notificationSentAt,
            acknowledgedAt = current.acknowledgedAt ?: row.acknowledgedAt,
        )
    }

    private fun habitKey(habit: HabitProgress): String =
        habit.targetIdentifier.trim().lowercase(Locale.ROOT)

    private fun coachingKey(step: CoachingStep): String =
        step.kind + " " + step.actionText.trim().lowercase(Locale.ROOT)

    private fun windowKey(window: RiskWindow): Triple<Int, Int, Int> =
        Triple(window.startMinute, window.endMinute, window.daysMask)

    private fun attemptKey(attempt: AttemptLog): AttemptKey = AttemptKey(
        occurredAtEpochMillis = attempt.occurredAtEpochMillis,
        ruleId = attempt.ruleId,
        blockedTarget = attempt.blockedTarget,
        outcome = attempt.outcome,
    )

    private fun sessionContent(session: RecoverySession): SessionContent = SessionContent(
        technique = session.technique,
        intensityStart = session.intensityStart,
        intensityEnd = session.intensityEnd,
        state = session.state,
        startedAtEpochMs = session.startedAtEpochMs,
        endedAtEpochMs = session.endedAtEpochMs,
        durationSec = session.durationSec,
        zoneId = session.zoneId,
        entry = session.entry,
        falseAlarm = session.falseAlarm,
    )

    private data class AttemptKey(
        val occurredAtEpochMillis: Long,
        val ruleId: String,
        val blockedTarget: String,
        val outcome: String,
    )

    /** The same urge episode may exist under a different id (plan §8.4 "journal entries with
     *  different IDs but equal content"); delivery bookkeeping is not content. */
    private data class SessionContent(
        val technique: String,
        val intensityStart: Int?,
        val intensityEnd: Int?,
        val state: String,
        val startedAtEpochMs: Long,
        val endedAtEpochMs: Long?,
        val durationSec: Int?,
        val zoneId: String,
        val entry: String,
        val falseAlarm: Boolean,
    )
}
