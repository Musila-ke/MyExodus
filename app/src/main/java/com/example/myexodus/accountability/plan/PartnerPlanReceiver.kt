package com.example.myexodus.accountability.plan

import androidx.room3.withWriteTransaction
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.PlanBody
import com.example.myexodus.accountability.serialization.PlanWire
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.PartnerPlan
import com.example.myexodus.data.db.entity.PartnerPlanTask

/**
 * Receive side of partner plans (ADR #193), called from the mailbox APPENDED hook. The chain
 * event is already verified and appended; this only projects it onto the local plan copy.
 *
 * - `plan_published` applies a newer revision to the recipient's copy. Lines are matched by
 *   task id, so the checks the recipient already made survive an edit; when any survive, the
 *   progress flag is re-raised because the author's view is now at the new revision.
 * - `plan_progress` applies the recipient's checked set to the author's copy — only at the
 *   exact revision the author currently holds. A stale event is ignored by design: the
 *   recipient re-sends after the next publish arrives (see above), so the two copies converge
 *   without any acknowledgement protocol.
 */
class PartnerPlanReceiver(private val storeFactory: AppRulesStoreFactory) {

    /** Never throws is the caller's contract — a store that will not open just drops it. */
    suspend fun applyAppended(pairId: String, payload: EventPayload) {
        val body = payload.plan ?: return
        if (payload.protocolVersion != PlanWire.PLAN_PROTOCOL_VERSION ||
            payload.schemaVersion != PlanWire.PLAN_SCHEMA_VERSION
        ) {
            return
        }
        when (payload.type) {
            PlanWire.TYPE_PLAN_PUBLISHED -> applyPublished(pairId, body)
            PlanWire.TYPE_PLAN_PROGRESS -> applyProgress(pairId, body)
        }
    }

    private suspend fun applyPublished(pairId: String, body: PlanBody) {
        val store = storeFactory.open() ?: return
        val dao = store.partnerPlanDao()
        store.withWriteTransaction<Unit> {
            val existing = dao.get(body.planId)
            // Never let a partner overwrite a plan this device authored, and never project a
            // plan onto a different pair than the one its chain belongs to.
            if (existing?.authored == true) return@withWriteTransaction
            if (existing != null && (existing.pairId != pairId || existing.revision >= body.revision)) {
                return@withWriteTransaction
            }
            if (existing == null) {
                dao.insertPlan(
                    PartnerPlan(
                        planId = body.planId,
                        pairId = pairId,
                        authored = false,
                        title = body.title,
                        revision = body.revision,
                        updatedAtMs = body.updatedAtMs,
                    ),
                )
            } else {
                dao.updatePlan(
                    existing.copy(
                        title = body.title,
                        revision = body.revision,
                        updatedAtMs = body.updatedAtMs,
                    ),
                )
            }
            val previous = dao.tasks(body.planId).associateBy { it.taskId }
            val keep = body.tasks.map { it.taskId }
            if (keep.isEmpty()) dao.deleteAllTasks(body.planId) else dao.deleteTasksNotIn(body.planId, keep)
            body.tasks.forEach { task ->
                val old = previous[task.taskId]
                if (old == null) {
                    dao.insertTask(
                        PartnerPlanTask(
                            planId = body.planId,
                            taskId = task.taskId,
                            taskOrder = task.order,
                            text = task.text,
                        ),
                    )
                } else {
                    // Keep the checked state; apply the author's new wording and order.
                    dao.updateTask(old.copy(taskOrder = task.order, text = task.text))
                }
            }
            if (dao.tasks(body.planId).any { it.done }) dao.markProgressPending(body.planId)
        }
    }

    private suspend fun applyProgress(pairId: String, body: PlanBody) {
        val store = storeFactory.open() ?: return
        val dao = store.partnerPlanDao()
        store.withWriteTransaction<Unit> {
            val plan = dao.get(body.planId) ?: return@withWriteTransaction
            if (!plan.authored || plan.pairId != pairId || plan.revision != body.revision) {
                return@withWriteTransaction
            }
            dao.clearDone(body.planId)
            body.doneTaskIds.forEach { taskId ->
                dao.setTaskDone(body.planId, taskId, done = true, atMs = body.updatedAtMs)
            }
        }
    }
}
