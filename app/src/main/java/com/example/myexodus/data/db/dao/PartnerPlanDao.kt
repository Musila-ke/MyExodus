package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.example.myexodus.data.db.entity.PartnerPlan
import com.example.myexodus.data.db.entity.PartnerPlanTask

@Dao
interface PartnerPlanDao {

    /** Every plan on this device (both directions, plus private ones), newest edit first. */
    @Query("SELECT * FROM partner_plan ORDER BY updated_at_ms DESC")
    suspend fun allPlans(): List<PartnerPlan>

    /** All checklist lines in one read — the Plans surface is small (≤40 lines per plan) and
     *  needs every plan's tasks to render its summaries, so one read beats N per-plan reads. */
    @Query("SELECT * FROM partner_plan_task ORDER BY plan_id, task_order ASC")
    suspend fun allTasks(): List<PartnerPlanTask>

    @Query("SELECT * FROM partner_plan WHERE plan_id = :planId")
    suspend fun get(planId: String): PartnerPlan?

    @Query("SELECT * FROM partner_plan_task WHERE plan_id = :planId ORDER BY task_order ASC")
    suspend fun tasks(planId: String): List<PartnerPlanTask>

    @Insert
    suspend fun insertPlan(plan: PartnerPlan)

    @Insert
    suspend fun insertTask(task: PartnerPlanTask)

    @Update
    suspend fun updatePlan(plan: PartnerPlan)

    @Update
    suspend fun updateTask(task: PartnerPlanTask)

    /** Author edit: tasks the author removed are deleted, taking their check state with them. */
    @Query("DELETE FROM partner_plan_task WHERE plan_id = :planId AND task_id NOT IN (:keep)")
    suspend fun deleteTasksNotIn(planId: String, keep: List<String>): Int

    @Query("DELETE FROM partner_plan_task WHERE plan_id = :planId")
    suspend fun deleteAllTasks(planId: String)

    @Query("DELETE FROM partner_plan WHERE plan_id = :planId")
    suspend fun deletePlan(planId: String)

    @Query(
        "UPDATE partner_plan_task SET done = :done, " +
            "done_at_ms = CASE WHEN :done = 1 THEN COALESCE(done_at_ms, :atMs) ELSE NULL END " +
            "WHERE plan_id = :planId AND task_id = :taskId",
    )
    suspend fun setTaskDone(planId: String, taskId: String, done: Boolean, atMs: Long?): Int

    /** Wholesale progress application (idempotent): clear, then re-mark the delivered set. */
    @Query("UPDATE partner_plan_task SET done = 0, done_at_ms = NULL WHERE plan_id = :planId")
    suspend fun clearDone(planId: String)

    @Query("UPDATE partner_plan SET pending_progress = 1 WHERE plan_id = :planId AND authored = 0")
    suspend fun markProgressPending(planId: String): Int

    @Query(
        "SELECT * FROM partner_plan WHERE pair_id = :pairId AND authored = 1 " +
            "AND pending_publish = 1 ORDER BY updated_at_ms ASC LIMIT :limit",
    )
    suspend fun pendingPublishForPair(pairId: String, limit: Int): List<PartnerPlan>

    @Query(
        "SELECT * FROM partner_plan WHERE pair_id = :pairId AND authored = 0 " +
            "AND pending_progress = 1 ORDER BY updated_at_ms ASC LIMIT :limit",
    )
    suspend fun pendingProgressForPair(pairId: String, limit: Int): List<PartnerPlan>

    /** Guarded clear: an edit made while the event was being signed leaves the new revision
     *  pending instead of silently dropping it. */
    @Query(
        "UPDATE partner_plan SET pending_publish = 0 " +
            "WHERE plan_id = :planId AND revision = :revision AND updated_at_ms = :updatedAtMs",
    )
    suspend fun clearPublishPending(planId: String, revision: Int, updatedAtMs: Long): Int

    /** Progress is per revision; a newer publish re-marks it after applying. */
    @Query("UPDATE partner_plan SET pending_progress = 0 WHERE plan_id = :planId AND revision = :revision")
    suspend fun clearProgressPending(planId: String, revision: Int): Int

    /** Unpair prune (§8.17): a plan shared on a retired pair has no correspondent left. */
    @Query("DELETE FROM partner_plan WHERE pair_id IN (:pairIds)")
    suspend fun deleteForPairs(pairIds: List<String>)
}
