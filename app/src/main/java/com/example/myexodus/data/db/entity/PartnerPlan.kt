package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One accountability plan (schema v17, owner ask 2026-10-21): a short checklist a partner
 * writes for the other user ("read chapter 3, gym, pray") and that the recipient checks off.
 *
 * Both sides keep the same [planId] with their own copy of the row: the author's copy has
 * [authored] = true, the recipient's false. Edits bump [revision]; delivery and progress are
 * revision-matched, so a stale snapshot can never roll a newer plan back. [pairId] is null
 * for the user's own private plan — it never leaves the device.
 *
 * [pendingPublish] / [pendingProgress] are the journal-free outbox: the plan state itself is
 * the source of truth, so the reconcile loop builds a payload from the current rows and
 * clears the flag only when the revision it sent is still current.
 */
@Entity(
    tableName = "partner_plan",
    indices = [Index(value = ["pair_id"])],
)
data class PartnerPlan(
    @PrimaryKey @ColumnInfo(name = "plan_id") val planId: String,
    /** Which pair this copy belongs to; null = the user's own, local-only plan. */
    @ColumnInfo(name = "pair_id") val pairId: String?,
    @ColumnInfo(name = "authored") val authored: Boolean,
    @ColumnInfo(name = "title") val title: String,
    /** Monotonic per plan: a published edit bumps it, recipient progress keeps it. */
    @ColumnInfo(name = "revision") val revision: Int,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    /** Author copies: a newer revision is waiting for the next signed sync pass. */
    @ColumnInfo(name = "pending_publish") val pendingPublish: Boolean = false,
    /** Recipient copies: the checked-off set is waiting to be sent back to the author. */
    @ColumnInfo(name = "pending_progress") val pendingProgress: Boolean = false,
)
