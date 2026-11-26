package com.example.myexodus.accountability.delivery

import com.example.myexodus.accountability.serialization.CanonicalCbor
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.serialization.RuleSchedule
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import java.util.UUID

/**
 * Sensitive payload of one `RULE_CHANGE_ATTEMPT` envelope (plan §13.4): a signed mailbox
 * document carries only opaque ciphertext, so the rule id, change, bounded identity, schedule
 * snapshots and occurrence minute travel inside the E2EE payload and the recipient projection
 * rebuilds the feed row the legacy chain path wrote.
 *
 * Decode contract: every key this contract knows is strict — mandatory per shape, and a wrong
 * type or an undecodable value rejects the whole body rather than partially applying it.
 * Unknown keys are ignored on purpose (OPD #48): an additive field from a newer sender must
 * not make this receiver lose the row. The tolerance only protects receivers that already
 * ship it — a pre-tolerance decoder rejects any extra key and the mailbox still receipts the
 * body as DELIVERED, silently dropping the row — so [RuleSchedule]-carrying bodies may only
 * be emitted once partner devices run a build with this decoder (the schedule keys shipped in
 * the same release as this tolerance; a lagging receiver loses rows until it updates).
 */
object PartnerRuleChangePayload {
    const val VERSION = 1

    /** Covers the worst case of two 64-char labels written as 4-byte UTF-8. */
    const val MAX_BYTES = 1_024

    private const val K_VERSION = "v"
    private const val K_RULE_ID = "rid"
    private const val K_CHANGE = "chg"
    private const val K_LABEL = "lbl"
    private const val K_TARGET = "tgt"
    private const val K_ITEMS = "cnt"
    private const val K_BATCH = "bat"
    private const val K_TIME = "t"
    private const val K_SCHEDULE = "sch"
    private const val K_PREVIOUS_SCHEDULE = "prv"

    private val UUID_TEXT =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    data class Decoded(
        val ruleId: UUID,
        val change: RuleChange,
        val identity: RuleIdentity,
        val occurredAtMinute: Long,
        /** The rule's schedule when the change was journaled; null on pre-schedule senders. */
        val schedule: RuleSchedule? = null,
        /** For UPDATED rows: the schedule before the edit, so the receiver can diff. */
        val previousSchedule: RuleSchedule? = null,
    )

    fun encode(
        ruleId: UUID,
        change: RuleChange,
        identity: RuleIdentity,
        occurredAtMinute: Long,
        schedule: RuleSchedule? = null,
        previousSchedule: RuleSchedule? = null,
    ): ByteArray {
        require(occurredAtMinute >= 1) { "occurred-at minute must be positive" }
        val entries = mutableListOf<Pair<String, CBORObject>>(
            K_VERSION to CanonicalCbor.int(VERSION.toLong()),
            K_RULE_ID to CanonicalCbor.text(ruleId.toString()),
            K_CHANGE to CanonicalCbor.text(change.wire),
            K_LABEL to CanonicalCbor.text(identity.label),
            K_TARGET to CanonicalCbor.text(identity.targetType.wire),
            K_TIME to CanonicalCbor.int(occurredAtMinute),
        )
        identity.itemCount?.let { entries += K_ITEMS to CanonicalCbor.int(it) }
        identity.batchLabel?.let { entries += K_BATCH to CanonicalCbor.text(it) }
        schedule?.let { entries += K_SCHEDULE to CanonicalCbor.text(it.encode()) }
        previousSchedule?.let { entries += K_PREVIOUS_SCHEDULE to CanonicalCbor.text(it.encode()) }
        return CanonicalCbor.encodeMap(entries)
    }

    /** Null = not this contract (wrong key type, missing/forbidden field, bad value, or a
     *  known key whose value is outside it). Unknown keys are ignored (see class KDoc).
     *  Never throws and never partially applies. */
    fun decodeOrNull(bytes: ByteArray): Decoded? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val map = try {
            CanonicalCbor.decodeMap(bytes)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val keys = map.keys.mapNotNull { key ->
            if (key.type == CBORType.TextString) key.AsString() else null
        }.toSet()
        if (keys.size != map.keys.size) return null
        if (map.integerOrNull(K_VERSION) != VERSION.toLong()) return null

        val ruleId = map.textOrNull(K_RULE_ID)
            ?.takeIf(UUID_TEXT::matches)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val change = map.textOrNull(K_CHANGE)?.let(RuleChange::fromWire) ?: return null
        val label = map.textOrNull(K_LABEL) ?: return null
        val target = map.textOrNull(K_TARGET)?.let(RuleTargetType::fromWire) ?: return null
        val time = map.integerOrNull(K_TIME) ?: return null
        if (time < 1) return null
        val itemCount = if (keys.contains(K_ITEMS)) {
            map.integerOrNull(K_ITEMS) ?: return null
        } else {
            null
        }
        val batchLabel = if (keys.contains(K_BATCH)) {
            map.textOrNull(K_BATCH) ?: return null
        } else {
            null
        }
        val identity = runCatching {
            RuleIdentity(label, target, itemCount, batchLabel)
        }.getOrNull() ?: return null
        // Known keys stay strict: a present-but-undecodable schedule rejects the body.
        val schedule = if (keys.contains(K_SCHEDULE)) {
            RuleSchedule.decodeOrNull(map.textOrNull(K_SCHEDULE) ?: return null) ?: return null
        } else {
            null
        }
        val previousSchedule = if (keys.contains(K_PREVIOUS_SCHEDULE)) {
            RuleSchedule.decodeOrNull(map.textOrNull(K_PREVIOUS_SCHEDULE) ?: return null) ?: return null
        } else {
            null
        }
        return Decoded(ruleId, change, identity, time, schedule, previousSchedule)
    }

    private fun CBORObject.integerOrNull(key: String): Long? =
        this[key]?.takeIf { it.type == CBORType.Integer }?.let {
            try {
                it.AsInt64()
            } catch (e: ArithmeticException) {
                null
            }
        }

    private fun CBORObject.textOrNull(key: String): String? =
        this[key]?.takeIf { it.type == CBORType.TextString }?.AsString()
}
