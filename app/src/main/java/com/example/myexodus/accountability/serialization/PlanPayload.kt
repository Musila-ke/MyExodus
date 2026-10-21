package com.example.myexodus.accountability.serialization

import com.example.myexodus.data.db.entity.PartnerPlanTask
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType

/**
 * Partner-plan bodies on the accountability wire (owner ask 2026-10-21, ADR #193). Two types
 * ride the shipped encrypted chain: `plan_published` (the author's snapshot at a revision) and
 * `plan_progress` (the recipient's checked-off set at the revision it received). Both carry the
 * same [PlanBody]; the type decides how the receiver applies it.
 *
 * Revisions are the idempotence key, not sequence numbers: applying is "newer revision wins,
 * everything else is a no-op", so redelivery and reordering cannot roll a plan back. The
 * [plan] key is absent-when-null so every pre-existing payload keeps byte-identical encoding
 * (hash-chain stability), and a body that fails these bounds decodes to null — the envelope
 * still appends; only the plan surface declines it (§10.6 bounded strings).
 */
object PlanWire {

    const val TYPE_PLAN_PUBLISHED = "plan_published"
    const val TYPE_PLAN_PROGRESS = "plan_progress"

    val PLAN_TYPES: Set<String> = setOf(TYPE_PLAN_PUBLISHED, TYPE_PLAN_PROGRESS)

    const val PLAN_PROTOCOL_VERSION = 1
    const val PLAN_SCHEMA_VERSION = 1

    const val MAX_PLAN_ID_LENGTH = 64
    const val MAX_TASK_ID_LENGTH = 64
    const val MAX_TITLE_LENGTH = PartnerPlanTask.MAX_TITLE_LENGTH
    const val MAX_TASKS = PartnerPlanTask.MAX_TASKS
    const val MAX_TASK_TEXT_LENGTH = PartnerPlanTask.MAX_TEXT_LENGTH
}

data class PlanTaskBody(
    val taskId: String,
    val order: Int,
    val text: String,
)

data class PlanBody(
    val planId: String,
    val title: String,
    val revision: Int,
    val updatedAtMs: Long,
    /** Author snapshot only; empty on a progress body. */
    val tasks: List<PlanTaskBody> = emptyList(),
    /** Recipient progress only; ignored on a published body. */
    val doneTaskIds: List<String> = emptyList(),
)

private const val K_PLAN_ID = "id"
private const val K_PLAN_TITLE = "ti"
private const val K_PLAN_REVISION = "rv"
private const val K_PLAN_UPDATED_AT = "ua"
private const val K_PLAN_TASKS = "tk"
private const val K_PLAN_DONE = "dn"
private const val K_TASK_ID = "id"
private const val K_TASK_ORDER = "o"
private const val K_TASK_TEXT = "tx"

fun encodePlan(plan: PlanBody): CBORObject = CBORObject.NewMap().also { m ->
    m.set(K_PLAN_ID, CanonicalCbor.text(plan.planId))
    m.set(K_PLAN_TITLE, CanonicalCbor.text(plan.title))
    m.set(K_PLAN_REVISION, CanonicalCbor.int(plan.revision.toLong()))
    m.set(K_PLAN_UPDATED_AT, CanonicalCbor.int(plan.updatedAtMs))
    m.set(
        K_PLAN_TASKS,
        CBORObject.NewArray().also { arr ->
            plan.tasks.forEach { t ->
                arr.Add(
                    CBORObject.NewMap().also { tm ->
                        tm.set(K_TASK_ID, CanonicalCbor.text(t.taskId))
                        tm.set(K_TASK_ORDER, CanonicalCbor.int(t.order.toLong()))
                        tm.set(K_TASK_TEXT, CanonicalCbor.text(t.text))
                    },
                )
            }
        },
    )
    m.set(
        K_PLAN_DONE,
        CBORObject.NewArray().also { arr -> plan.doneTaskIds.forEach { arr.Add(CanonicalCbor.text(it)) } },
    )
}

/**
 * Null = structurally invalid or over a bound; the caller declines the body without touching
 * the chain event that carried it. Text fields are clamped rather than declined — one long
 * line must not discard a whole plan — while identity fields (ids) are strict, because a
 * truncated id would point at a different row.
 */
fun decodePlanOrNull(obj: CBORObject): PlanBody? = try {
    if (obj.type != CBORType.Map) {
        null
    } else {
        val id = obj[K_PLAN_ID].AsString()
        val title = obj[K_PLAN_TITLE].AsString().trim()
        val revision = obj[K_PLAN_REVISION].AsInt32()
        val updatedAt = obj[K_PLAN_UPDATED_AT].AsInt64()
        val tasks = obj[K_PLAN_TASKS]
        val done = obj[K_PLAN_DONE]
        if (id.isEmpty() || id.length > PlanWire.MAX_PLAN_ID_LENGTH ||
            title.isEmpty() ||
            revision < 1 ||
            updatedAt <= 0 ||
            tasks == null || tasks.type != CBORType.Array || tasks.size() > PlanWire.MAX_TASKS ||
            done == null || done.type != CBORType.Array || done.size() > PlanWire.MAX_TASKS
        ) {
            null
        } else {
            val parsed = tasks.getValues().mapNotNull { t ->
                if (t.type != CBORType.Map) return@mapNotNull null
                val taskId = t[K_TASK_ID].AsString()
                val order = t[K_TASK_ORDER].AsInt32()
                val text = t[K_TASK_TEXT].AsString().trim()
                if (taskId.isEmpty() || taskId.length > PlanWire.MAX_TASK_ID_LENGTH ||
                    order < 0 || text.isEmpty()
                ) {
                    null
                } else {
                    PlanTaskBody(
                        taskId = taskId,
                        order = order,
                        text = text.take(PlanWire.MAX_TASK_TEXT_LENGTH),
                    )
                }
            }
            val doneIds = done.getValues().mapNotNull { d ->
                if (d.type != CBORType.TextString) return@mapNotNull null
                d.AsString().takeIf { it.isNotEmpty() && it.length <= PlanWire.MAX_TASK_ID_LENGTH }
            }
            // A task list that silently dropped a member is corrupt identity, not a long string.
            if (parsed.size != tasks.size() ||
                parsed.map { it.taskId }.toSet().size != parsed.size
            ) {
                null
            } else {
                PlanBody(
                    planId = id,
                    title = title.take(PlanWire.MAX_TITLE_LENGTH),
                    revision = revision,
                    updatedAtMs = updatedAt,
                    tasks = parsed,
                    doneTaskIds = doneIds,
                )
            }
        }
    }
} catch (e: RuntimeException) {
    null
}
