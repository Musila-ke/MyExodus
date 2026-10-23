package com.example.myexodus.accountability.plan

import androidx.room3.withWriteTransaction
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.PartnerPlan
import com.example.myexodus.data.db.entity.PartnerPlanTask
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** One editable checklist line: [taskId] null means a line the author just typed. */
data class PlanTaskDraft(val taskId: String?, val text: String)

/**
 * Authoring side of partner plans (owner ask 2026-10-21, ADR #193). A plan is local state —
 * the checklist itself is the source of truth — and delivery is a transport concern: every
 * write that concerns a pair sets a pending flag, and `PartnerSyncCoordinator` builds the
 * signed event from the current rows at reconcile time (signing is biometric-gated, so a save
 * can never block on it). Self plans carry a null pair and never leave the phone.
 *
 * Edits bump [PartnerPlan.revision]; the recipient's checked state is matched to the revision
 * it was seen at, so a stale progress event is ignored rather than applied to a newer plan.
 *
 * Plans repeat daily (owner ask 2026-10-23): a check counts only for the local calendar day it
 * was made. Every read normalizes rows whose `done_at_ms` predates today's local midnight, so
 * the next day opens unchecked on both phones. When the copy is a paired recipient's, the
 * reset also raises `pending_progress` — the author's view must learn the day rolled over, not
 * keep yesterday's checks. An author's display-only copy and a self plan clear locally without
 * publishing anything.
 */
class PartnerPlanRepository(private val storeFactory: AppRulesStoreFactory) {

    /** Null = the store refused to open — the surface must say "can't read", never "no plans"
     *  (an empty list is a real answer). */
    suspend fun plans(): List<PartnerPlan>? =
        storeFactory.open()?.partnerPlanDao()?.allPlans()

    suspend fun tasks(planId: String): List<PartnerPlanTask> {
        val store = storeFactory.open() ?: return emptyList()
        val dao = store.partnerPlanDao()
        var resetPairedRecipient = false
        val rows = store.withWriteTransaction<List<PartnerPlanTask>> {
            val dayStart = localDayStartMillis(System.currentTimeMillis())
            if (dao.clearStaleDone(planId, dayStart) > 0) {
                val plan = dao.get(planId)
                if (plan != null && plan.pairId != null && !plan.authored) {
                    dao.markProgressPending(planId)
                    resetPairedRecipient = true
                }
            }
            dao.tasks(planId)
        }
        if (resetPairedRecipient) PartnerSyncSignals.activityEmitted.tryEmit(Unit)
        return rows
    }

    suspend fun allTasks(): List<PartnerPlanTask> {
        val store = storeFactory.open() ?: return emptyList()
        val dao = store.partnerPlanDao()
        var resetPairedRecipient = false
        val rows = store.withWriteTransaction<List<PartnerPlanTask>> {
            val dayStart = localDayStartMillis(System.currentTimeMillis())
            val stalePlans = dao.planIdsWithStaleDone(dayStart)
            if (stalePlans.isNotEmpty()) {
                dao.clearAllStaleDone(dayStart)
                stalePlans.forEach { planId ->
                    val plan = dao.get(planId)
                    if (plan != null && plan.pairId != null && !plan.authored) {
                        dao.markProgressPending(planId)
                        resetPairedRecipient = true
                    }
                }
            }
            dao.allTasks()
        }
        if (resetPairedRecipient) PartnerSyncSignals.activityEmitted.tryEmit(Unit)
        return rows
    }

    /** Creates a plan; [pairId] null = the user's own local plan. Returns the new plan id,
     *  or null when the boundary refused it (blank title / store unavailable). */
    suspend fun createPlan(pairId: String?, title: String, taskTexts: List<String>): String? {
        val cleanTitle = cleanTitle(title) ?: return null
        val cleanTasks = cleanTasks(taskTexts)
        val store = storeFactory.open() ?: return null
        val planId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        store.withWriteTransaction<Unit> {
            store.partnerPlanDao().insertPlan(
                PartnerPlan(
                    planId = planId,
                    pairId = pairId,
                    authored = true,
                    title = cleanTitle,
                    revision = 1,
                    updatedAtMs = now,
                    // A self plan is already where it is going.
                    pendingPublish = pairId != null,
                ),
            )
            cleanTasks.forEachIndexed { index, text ->
                store.partnerPlanDao().insertTask(
                    PartnerPlanTask(
                        planId = planId,
                        taskId = UUID.randomUUID().toString(),
                        taskOrder = index,
                        text = text,
                    ),
                )
            }
        }
        if (pairId != null) PartnerSyncSignals.activityEmitted.tryEmit(Unit)
        return planId
    }

    /** Author edit: one revision bump publishes the whole snapshot; lines the author kept keep
     *  their ids (and any progress already reported for them). False when the plan is not this
     *  device's to edit. */
    suspend fun updatePlan(planId: String, title: String, drafts: List<PlanTaskDraft>): Boolean {
        val cleanTitle = cleanTitle(title) ?: return false
        val cleanDrafts = drafts.mapNotNull { draft ->
            val text = draft.text.trim().take(PartnerPlanTask.MAX_TEXT_LENGTH)
            text.takeIf { it.isNotEmpty() }?.let { PlanTaskDraft(draft.taskId, it) }
        }.distinctBy { it.taskId }
        val store = storeFactory.open() ?: return false
        val dao = store.partnerPlanDao()
        var updated = false
        var paired = false
        store.withWriteTransaction<Unit> {
            val plan = dao.get(planId) ?: return@withWriteTransaction
            if (!plan.authored) return@withWriteTransaction
            paired = plan.pairId != null
            dao.updatePlan(
                plan.copy(
                    title = cleanTitle,
                    revision = plan.revision + 1,
                    updatedAtMs = System.currentTimeMillis(),
                    pendingPublish = plan.pairId != null,
                ),
            )
            val existing = dao.tasks(planId).associateBy { it.taskId }
            val keep = cleanDrafts.mapNotNull { it.taskId }
            if (keep.isEmpty()) dao.deleteAllTasks(planId) else dao.deleteTasksNotIn(planId, keep)
            cleanDrafts.forEachIndexed { index, draft ->
                val old = draft.taskId?.let(existing::get)
                if (old == null) {
                    dao.insertTask(
                        PartnerPlanTask(
                            planId = planId,
                            taskId = UUID.randomUUID().toString(),
                            taskOrder = index,
                            text = draft.text,
                        ),
                    )
                } else {
                    dao.updateTask(old.copy(taskOrder = index, text = draft.text))
                }
            }
            updated = true
        }
        if (updated && paired) PartnerSyncSignals.activityEmitted.tryEmit(Unit)
        return updated
    }

    /** Recipient (or self-plan owner) checks a line. Author copies of a SHARED plan are
     *  display-only: progress reaches the author from the recipient's device, never from the
     *  author's own view. A self plan has no recipient device, so its owner checks locally. */
    suspend fun setTaskDone(planId: String, taskId: String, done: Boolean): Boolean {
        val store = storeFactory.open() ?: return false
        val dao = store.partnerPlanDao()
        var changed = false
        var paired = false
        store.withWriteTransaction<Unit> {
            val plan = dao.get(planId) ?: return@withWriteTransaction
            if (plan.authored && plan.pairId != null) return@withWriteTransaction
            val now = System.currentTimeMillis()
            if (dao.setTaskDone(planId, taskId, done, if (done) now else null) == 0) {
                return@withWriteTransaction
            }
            paired = plan.pairId != null
            if (paired) dao.markProgressPending(planId)
            changed = true
        }
        if (changed && paired) PartnerSyncSignals.activityEmitted.tryEmit(Unit)
        return changed
    }

    private fun cleanTitle(title: String): String? =
        title.trim().take(PartnerPlanTask.MAX_TITLE_LENGTH).takeIf { it.isNotEmpty() }

    private fun cleanTasks(texts: List<String>): List<String> =
        texts.map { it.trim().take(PartnerPlanTask.MAX_TEXT_LENGTH) }
            .filter { it.isNotEmpty() }
            .take(PartnerPlanTask.MAX_TASKS)
}

/** First instant of [nowMs]'s local calendar day — the daily reset boundary. `atStartOfDay`
 *  rather than midnight arithmetic so a DST gap at 00:00 lands on the real day start. */
internal fun localDayStartMillis(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(nowMs)
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
