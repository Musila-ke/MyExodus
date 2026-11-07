package com.example.myexodus.recovery

import com.example.myexodus.data.db.entity.RecoverySession

/**
 * One Urge Assist episode's check-in state (plan §F5). Immutable and pure: the branching rules
 * live here so they are unit-tested without Android, and the screen only renders what this
 * decides. One episode runs from entering Urge Assist to leaving it; the round limits and the
 * exclusions survive action switches inside it.
 *
 * The same action is never offered again in the episode after "worse" — [excludedActions]
 * feeds the engine's exclusion filter, and a materially different action follows "same".
 */
data class UrgeEpisode(
    /** Actions this episode will not offer again. */
    val excludedActions: Set<String> = emptySet(),
    /** "About the same" answers so far; the second one escalates (plan §F5). */
    val sameRounds: Int = 0,
    /** The single "Better" extension is spent. */
    val betterUsed: Boolean = false,
    val lastModality: UrgeModality? = null,
) {

    fun cantDo(actionId: String): UrgeEpisode = copy(excludedActions = excludedActions + actionId)

    fun checkIn(result: UrgeCheckIn, actionId: String, modality: UrgeModality): CheckInTransition =
        when (result) {
            UrgeCheckIn.BETTER -> if (betterUsed) {
                CheckInTransition(this, CheckInOutcome.FINISH)
            } else {
                // One extension is allowed; the action is not excluded, since repeating it is
                // exactly what "keep going" offers.
                CheckInTransition(
                    copy(betterUsed = true, lastModality = modality),
                    CheckInOutcome.EXTEND,
                )
            }
            UrgeCheckIn.SAME -> {
                val rounds = (sameRounds + 1).coerceAtMost(MAX_SAME_ROUNDS)
                val next = copy(
                    sameRounds = rounds,
                    excludedActions = excludedActions + actionId,
                    lastModality = modality,
                )
                if (rounds >= MAX_SAME_ROUNDS) {
                    CheckInTransition(next, CheckInOutcome.ESCALATE)
                } else {
                    CheckInTransition(next, CheckInOutcome.NEW_ACTION)
                }
            }
            UrgeCheckIn.WORSE -> CheckInTransition(
                copy(excludedActions = excludedActions + actionId, lastModality = modality),
                CheckInOutcome.ESCALATE,
            )
            UrgeCheckIn.SKIPPED -> CheckInTransition(this, CheckInOutcome.FINISH)
        }

    companion object {
        const val MAX_SAME_ROUNDS = 2
    }
}

/** The four one-tap check-in results (plan §E2.4); ids match the stored column values. */
enum class UrgeCheckIn(val id: String) {
    BETTER(RecoverySession.CHECK_IN_BETTER),
    SAME(RecoverySession.CHECK_IN_SAME),
    WORSE(RecoverySession.CHECK_IN_WORSE),
    SKIPPED(RecoverySession.CHECK_IN_SKIPPED),
}

enum class CheckInOutcome { EXTEND, NEW_ACTION, ESCALATE, FINISH }

data class CheckInTransition(val episode: UrgeEpisode, val outcome: CheckInOutcome)

/** 22:00 to 05:59 — the late-night window the plan's example names (plan §F7). */
fun isLateNight(hour: Int): Boolean = hour >= 22 || hour < 6
