package com.example.myexodus.recovery

/**
 * Deterministic, explainable action selection (plan §F3). Pure Kotlin: inputs in, one
 * recommendation out. No Android import, no I/O, no clock, no random. The caller supplies
 * the late-night flag and the risk-window answer; the engine never interprets time as a mood.
 */
object UrgeSelectionEngine {

    fun select(input: UrgeSelectionInput): UrgeRecommendation {
        if (input.immediateDanger) {
            val safety = requireNotNull(UrgeCatalog.byId(UrgeCatalog.SAFETY_CHECK))
            return UrgeRecommendation(safety, emptyList(), UrgeReason.SAFETY)
        }

        val priority = UrgeCatalog.priorityFor(input.category, input.lateNight, input.inTransaction)
        val priorityIndex = priority.withIndex().associate { (index, id) -> id to index }
        val orderedIds = (priority + UrgeCatalog.all.map { it.id }).distinct()
        val lowEffortFirst = input.lateNight || input.inRiskWindow

        val candidates = orderedIds
            .mapNotNull(UrgeCatalog::byId)
            .filter { action ->
                action.behaviors.isEmpty() || (input.category != null && input.category in action.behaviors)
            }
            .filter { it.id !in input.dislikedActions && it.id !in input.excludedActions }
            .filter { input.canMove != false || !it.requiresMovement }
            .filter { input.hasPartner || !it.requiresPartner }
            .filter { !input.quietPreferred || !it.requiresAudio }

        val ranked = candidates.sortedWith(
            compareBy<UrgeAction> { if (it.id in input.helpfulActions) 0 else 1 }
                .thenBy { if (input.lastModality != null && it.modality == input.lastModality) 1 else 0 }
                .thenBy { priorityIndex[it.id] ?: Int.MAX_VALUE }
                .thenBy { if (lowEffortFirst) it.effort.ordinal else 0 },
        )
        // A category answer is the specific signal for this moment, so the designed lead for
        // that behavior outranks a stored preference. The preference still takes the first
        // alternate, and leads on its own when no category was named (plan §F3 step 5).
        val categoryLead = if (input.category != null) {
            candidates.minByOrNull { priorityIndex[it.id] ?: Int.MAX_VALUE }
        } else {
            null
        }
        val ordered = if (categoryLead != null) {
            listOf(categoryLead) + ranked.filter { it.id != categoryLead.id }
        } else {
            ranked
        }

        // Log-only is always possible, so a fully filtered catalog still has one honest answer.
        val primary = ordered.firstOrNull()
            ?: requireNotNull(UrgeCatalog.byId(UrgeCatalog.LOG_ONLY))
        return UrgeRecommendation(
            primary = primary,
            // The whole ranked remainder: the UI decides how much to show at once, but the
            // catalogue is never silently truncated (owner direction 2026-11-07).
            alternatives = ordered.drop(1),
            reason = reason(input, primary),
        )
    }

    private fun reason(input: UrgeSelectionInput, primary: UrgeAction): UrgeReason = when {
        input.category == UrgeCategory.GAMBLING && input.inTransaction -> UrgeReason.GAMBLING_TRANSACTION
        primary.id in input.helpfulActions -> UrgeReason.HELPFUL
        input.lateNight -> UrgeReason.LATE_NIGHT
        input.inRiskWindow -> UrgeReason.RISK_WINDOW
        input.category != null -> UrgeReason.CATEGORY
        else -> UrgeReason.GENERIC
    }
}
