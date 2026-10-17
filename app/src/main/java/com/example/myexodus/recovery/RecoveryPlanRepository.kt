package com.example.myexodus.recovery

import androidx.room3.withWriteTransaction
import com.example.myexodus.accountability.activity.PartnerActivityJournal
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.RiskWindow
import java.time.LocalDateTime
import java.util.UUID

/**
 * The user-authored recovery plan: willpower windows (FR-7) and the coaching items shown
 * during interception (Req 20 steps + FR-6 If-Then/Values). Local DB only — offline by
 * construction (Req 20 edge case), never mirrored as text (INV-13).
 *
 * Coaching edits additionally journal a partner-visible `COACHING_STEP_UPDATED` (partner-activity
 * plan Appendix A.3) inside the same transaction; the event carries the item *count* only, never
 * the text. Reorder ([moveItem]) is deliberately not an event — order is invisible in the
 * partner aggregate, so emitting would be pure notification noise.
 */
class RecoveryPlanRepository(private val storeFactory: AppRulesStoreFactory) {

    /** The windows, creating the single default (20:00–06:00, all days) on first read so a
     *  fresh install has the Req 19 mapping without a seeding migration. */
    suspend fun windows(): List<RiskWindow> {
        val store = storeFactory.open() ?: return emptyList()
        val dao = store.riskWindowDao()
        // Seed inside the write transaction: three surfaces read the plan (the recovery root's
        // activeWindow plus two subpages), and on a fresh install their first reads can overlap —
        // a check-then-insert outside the transaction gives an empty table two defaults.
        store.withWriteTransaction<Unit> {
            if (dao.count() == 0L) dao.insert(RiskWindow.DEFAULT)
        }
        return dao.all()
    }

    suspend fun specs(): List<RiskWindowSpec> = windows().map { it.toSpec() }

    /** False when the ≤3 cap (FR-7) is already reached — the UI disables the action too. */
    suspend fun addWindow(startMinute: Int, endMinute: Int, daysMask: Int): Boolean {
        val store = storeFactory.open() ?: return false
        val dao = store.riskWindowDao()
        // Cap check inside the write transaction: the editor launches each save from its own
        // coroutine, so a check-then-insert outside it lets two saves both pass at the boundary.
        var added = false
        store.withWriteTransaction<Unit> {
            if (dao.count() < RiskWindow.MAX_WINDOWS) {
                dao.insert(RiskWindow(startMinute = startMinute, endMinute = endMinute, daysMask = daysMask))
                added = true
            }
        }
        return added
    }

    suspend fun updateWindow(window: RiskWindow) {
        storeFactory.open()?.riskWindowDao()?.update(window)
    }

    suspend fun deleteWindow(window: RiskWindow) {
        storeFactory.open()?.riskWindowDao()?.delete(window)
    }

    /** The window active at [now], evaluated fresh — never a cached verdict (v2 M1.4). */
    suspend fun activeWindow(now: LocalDateTime): RiskWindowSpec? =
        RiskWindowEvaluator.activeWindow(now, specs())

    /**
     * The profile's coaching items, every kind (W1 renders them inline; W3's editor groups
     * them by kind). Ordered by the item's own step_order — the order the user arranged.
     */
    suspend fun items(profileId: String): List<CoachingStep> =
        storeFactory.open()?.coachingStepDao()?.getForProfile(profileId) ?: emptyList()

    /**
     * Adds a coaching item. Rejects blank text and a full kind (≤5 per kind, FR-6); text is
     * trimmed and capped at [CoachingStep.MAX_TEXT_LENGTH] at this boundary because it is
     * user input. [windowId] blank means "any time" (OPEN-4).
     */
    suspend fun addItem(
        profileId: String,
        kind: String,
        text: String,
        windowId: String = "",
    ): Boolean {
        val clean = text.trim().take(CoachingStep.MAX_TEXT_LENGTH)
        if (clean.isEmpty()) return false
        val store = storeFactory.open() ?: return false
        val dao = store.coachingStepDao()
        // Cap and next order are read inside the write transaction: the editor launches each
        // save from its own coroutine, so a pre-transaction read lets two rapid saves both pass
        // the cap or mint the same step_order.
        var added = false
        store.withWriteTransaction<Unit> {
            if (dao.countForKind(profileId, kind) < CoachingStep.MAX_PER_KIND) {
                dao.insert(
                    CoachingStep(
                        stepId = UUID.randomUUID().toString(),
                        profileId = profileId,
                        stepOrder = dao.maxOrderForKind(profileId, kind) + 1,
                        actionText = clean,
                        associatedTriggerWindow = windowId,
                        kind = kind,
                    ),
                )
                PartnerActivityJournal.emitForProfile(
                    store, profileId, EventPayload.TYPE_COACHING_STEP_UPDATED, System.currentTimeMillis(),
                )
                added = true
            }
        }
        return added
    }

    suspend fun updateItem(step: CoachingStep, text: String, windowId: String) {
        val clean = text.trim().take(CoachingStep.MAX_TEXT_LENGTH)
        if (clean.isEmpty()) return
        val store = storeFactory.open() ?: return
        store.withWriteTransaction<Unit> {
            store.coachingStepDao().update(step.copy(actionText = clean, associatedTriggerWindow = windowId))
            PartnerActivityJournal.emitForProfile(
                store, step.profileId, EventPayload.TYPE_COACHING_STEP_UPDATED, System.currentTimeMillis(),
            )
        }
    }

    suspend fun deleteItem(step: CoachingStep) {
        val store = storeFactory.open() ?: return
        store.withWriteTransaction<Unit> {
            store.coachingStepDao().delete(step)
            PartnerActivityJournal.emitForProfile(
                store, step.profileId, EventPayload.TYPE_COACHING_STEP_UPDATED, System.currentTimeMillis(),
            )
        }
    }

    /** Reorders within the item's own kind ([-1] up, [+1] down); ends are no-ops. */
    suspend fun moveItem(step: CoachingStep, delta: Int) {
        val store = storeFactory.open() ?: return
        val dao = store.coachingStepDao()
        // Read and swap inside the write transaction, and take the moving row's CURRENT order
        // from the sibling list: a second tap carries the entity captured before the first swap
        // committed, so trusting its step_order wrote a duplicated order (the target received
        // the stale value) instead of performing the next move.
        store.withWriteTransaction<Unit> {
            val siblings = dao.getForProfile(step.profileId).filter { it.kind == step.kind }
            val index = siblings.indexOfFirst { it.stepId == step.stepId }
            if (index < 0) return@withWriteTransaction
            val target = siblings.getOrNull(index + delta) ?: return@withWriteTransaction
            val current = siblings[index]
            dao.setOrder(current.stepId, target.stepOrder)
            dao.setOrder(target.stepId, current.stepOrder)
        }
    }
}
