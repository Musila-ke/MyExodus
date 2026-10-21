package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One checklist line of a [PartnerPlan] (schema v17). [taskId] is minted by the author and
 * kept stable across edits, which is what lets the recipient's checked state survive a
 * published update; removing a task removes its check with it.
 */
@Entity(
    tableName = "partner_plan_task",
    primaryKeys = ["plan_id", "task_id"],
    foreignKeys = [
        ForeignKey(
            entity = PartnerPlan::class,
            parentColumns = ["plan_id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["plan_id"])],
)
data class PartnerPlanTask(
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "task_order") val taskOrder: Int,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "done") val done: Boolean = false,
    @ColumnInfo(name = "done_at_ms") val doneAtMs: Long? = null,
) {
    /** Stable identity across copies and edits is (planId, taskId); the parent covers the
     *  left half, so the composite key needs no extra unique index. */
    companion object {
        const val MAX_TITLE_LENGTH = 80
        const val MAX_TASKS = 40
        const val MAX_TEXT_LENGTH = 200
    }
}
