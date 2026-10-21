package com.example.myexodus.accountability.serialization

import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType

/**
 * The event payload carried inside the encrypted envelope (§6.2.1): NFC-normalized and
 * encoded as deterministic CBOR BEFORE encryption. Control events (bilateral deletion,
 * §10; disable approval, AD-07) travel the same channel with a different type.
 *
 * [requestEventId] / [pairingEpoch] (AD-07) are the §8 Step 4 `DisableApprovalResponse`
 * contract: the response references the persisted request event by id and carries the
 * partnership epoch so a response is rejected after a re-pair. Encoded only when non-null,
 * so activity/deletion payloads keep their exact prior bytes (hash-chain stability).
 *
 * [expectedPreviousSequence] / [expectedPreviousHash] are the AD-08b Step 11 repair request
 * contract: a [`TYPE_SYNC_REQUEST`] carries the requester's last known sequence + hash for
 * the *sender's* chain so the returned segment is provable against the exact chain link, not
 * a bare sequence number. Also absent-when-unset (byte stability for every other type).
 *
 * Step 13 disable-approval flow (§8 Step 12, FR2): a [`TYPE_APPROVAL_REQUEST`] carries the
 * requesting user's message and its [pairingEpoch]; the answering [`TYPE_APPROVAL_RESPONSE`]
 * references the request by [requestEventId] plus [pairingEpoch] (AD-07) and carries the
 * [decision]. The request's own event id is deliberately NOT self-reported — the responder
 * derives it canonically from the envelope's (senderKeyId, sequence), which the sender
 * cannot spoof.
 *
 * Partner-visible activity layer (plan §4.6/§8.5, Appendix A.2): the five [`ACTIVITY_TYPES`]
 * add [protocolVersion], [schemaVersion], and the [stateAfter] aggregate. All three are
 * encoded only when non-null — every pre-existing type keeps byte-identical encoding, and
 * the five new types carry a fixed version pair the receiver dispatches on (FR-17).
 * [text] doubles as the motivational message body for [`TYPE_MOTIVATIONAL_MESSAGE`]
 * (compose-time bound: [MAX_ACTIVITY_TEXT_LENGTH]).
 */
data class EventPayload(
    val type: String = TYPE_ACTIVITY,
    val text: String,
    val requestEventId: String? = null,
    val pairingEpoch: Int? = null,
    val expectedPreviousSequence: Long? = null,
    val expectedPreviousHash: ByteArray? = null,
    /** Approval response only: true = approved, false = rejected. */
    val decision: Boolean? = null,
    /** Activity types only: wire protocol version (FR-17). Absent for every shipped type. */
    val protocolVersion: Int? = null,
    /** Activity types only: body schema version (FR-17). Absent for every shipped type. */
    val schemaVersion: Int? = null,
    /** Activity types only: partner-visible aggregate after the local state transition (§4.6). */
    val stateAfter: ActivityStateAfter? = null,
    /** Plan types only (ADR #193): the plan snapshot or the recipient's progress set.
     *  Absent on every other type, so their encodings stay byte-identical. */
    val plan: PlanBody? = null,
) {
    companion object {
        const val TYPE_ACTIVITY = "activity"
        const val TYPE_DELETION_REQUEST = "deletion_request"

        /** AD-08/AD-08b: "please replay your chain after (sequence, hash)". */
        const val TYPE_SYNC_REQUEST = "sync_request"

        /** §8 Step 12: the disable-approval request/response pair (AD-07). */
        const val TYPE_APPROVAL_REQUEST = "approval_request"
        const val TYPE_APPROVAL_RESPONSE = "approval_response"

        /** §2.1 FR-01, Appendix A.2: the five partner-visible activity event types. */
        const val TYPE_STREAK_UPDATED = "streak_updated"
        const val TYPE_RELAPSE_RECORDED = "relapse_recorded"
        const val TYPE_MILESTONE_REACHED = "milestone_reached"
        const val TYPE_COACHING_STEP_UPDATED = "coaching_step_updated"
        const val TYPE_MOTIVATIONAL_MESSAGE = "motivational_message"

        val ACTIVITY_TYPES: Set<String> = setOf(
            TYPE_STREAK_UPDATED,
            TYPE_RELAPSE_RECORDED,
            TYPE_MILESTONE_REACHED,
            TYPE_COACHING_STEP_UPDATED,
            TYPE_MOTIVATIONAL_MESSAGE,
        )

        /** Wire v2 (ADR #134): the kinds that concern one habit — exactly these carry
         *  [ActivityStateAfter.habitLabel]. */
        val HABIT_ANCHORED_ACTIVITY_TYPES: Set<String> = setOf(
            TYPE_STREAK_UPDATED,
            TYPE_RELAPSE_RECORDED,
            TYPE_MILESTONE_REACHED,
        )

        const val ACTIVITY_PROTOCOL_VERSION = 1
        /** v2 (ADR #134): `stateAfter` gained optional `habitLabel` / `endedDays` /
         *  `milestoneDay` — absent-when-null, so a v1-shaped body keeps byte-identical
         *  encoding ([SUPPORTED_SCHEMA_VERSIONS] keeps v1 events receivable). */
        const val ACTIVITY_SCHEMA_VERSION = 2

        /** Every body schema this build understands. Emitters write [ACTIVITY_SCHEMA_VERSION];
         *  receivers accept both — a v1 event (pre-2026-10-04) is complete without a habit
         *  name, and declining it as UNSUPPORTED would silently hide history on upgrade. */
        val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(1, ACTIVITY_SCHEMA_VERSION)

        /** Req 15 composer bound (§10.6 bounded strings; the encoder does not enforce it —
         *  compose-time does, and decode-side projection refuses anything over it). */
        const val MAX_ACTIVITY_TEXT_LENGTH = 280

        /** Wire bound for [ActivityStateAfter.habitLabel] (§10.6 bounded strings): a
         *  longer-than-this label is declined to null at decode; the event itself still
         *  applies — the name is the optional part, never the state. */
        const val MAX_HABIT_LABEL_LENGTH = 80
    }
}

/** §8.5 receive-side dispatch over the [ActivityEvent] states. */
fun classifyActivity(payload: EventPayload): ActivityEvent = when {
    payload.type !in EventPayload.ACTIVITY_TYPES -> ActivityEvent.NotActivity
    payload.protocolVersion == null || payload.schemaVersion == null ->
        ActivityEvent.Malformed(payload.type)
    payload.protocolVersion != EventPayload.ACTIVITY_PROTOCOL_VERSION ||
        payload.schemaVersion !in EventPayload.SUPPORTED_SCHEMA_VERSIONS ->
        ActivityEvent.Unsupported(payload.type, payload.protocolVersion, payload.schemaVersion)
    payload.stateAfter == null -> ActivityEvent.Malformed(payload.type)
    else -> ActivityEvent.Valid(payload)
}

/**
 * §8.5 decode-side outcome: MALFORMED and UNSUPPORTED_VERSION are separate states (FR-17)
 * and neither is ever silently discarded — the chain still appends the event (it is
 * cryptographically valid); only the partner projection declines to apply it.
 */
sealed interface ActivityEvent {
    data class Valid(val payload: EventPayload) : ActivityEvent

    /** One of the five types carrying a version pair this build does not implement. */
    data class Unsupported(
        val type: String,
        val protocolVersion: Int?,
        val schemaVersion: Int?,
    ) : ActivityEvent

    /** One of the five types but the body is missing or uninterpretable. */
    data class Malformed(val type: String) : ActivityEvent

    /** A shipped non-activity type — travels this codec untouched. */
    data object NotActivity : ActivityEvent
}

/**
 * §4.6 `stateAfter`: the partner-visible aggregate after the sender's local transition.
 * Applied wholesale to the receiver projection (§8.10 — no delta arithmetic), so duplicate
 * delivery cannot double-count.
 */
data class ActivityStateAfter(
    /** Completed clean days of the sender's current streak (elapsed / day). */
    val daysFree: Int,
    val relapseCount: Int,
    /** Achieved milestone days of the *current* streak, ascending (e.g. [7, 30]). */
    val milestonesReached: List<Int>,
    /** Count of the sender's active coaching items (summary only — never the text). */
    val coachingProgress: Int,
    /** Sender wall-clock; display chronology only, never a sync cursor (§9.6). */
    val eventOccurredAtMs: Long,
    /** v2 (ADR #134): the sender's own name for the habit this event concerns — the partner
     *  sees *which* streak, not just a number. Null on v1 bodies and profile-anchored kinds;
     *  travels only when the streak is shared (the consent gate suppresses the whole event). */
    val habitLabel: String? = null,
    /** v2, relapse rows only: the ordinal day-count of the streak that just ended (the same
     *  number the sender's own relapse card shows). */
    val endedDays: Int? = null,
    /** v2, milestone rows only: the day reached (e.g. 30). */
    val milestoneDay: Int? = null,
)

private const val K_TYPE = "ty"
private const val K_TEXT = "tx"
private const val K_REQUEST_EVENT_ID = "rid"
private const val K_PAIRING_EPOCH = "pe"
private const val K_EXPECTED_PREV_SEQUENCE = "ps"
private const val K_EXPECTED_PREV_HASH = "ph"
private const val K_DECISION = "dec"
private const val K_PROTOCOL_VERSION = "pv"
private const val K_SCHEMA_VERSION = "sv"
private const val K_STATE_AFTER = "st"
private const val K_PLAN = "pl"
private const val K_DAYS_FREE = "df"
private const val K_RELAPSE_COUNT = "rc"
private const val K_MILESTONES = "ms"
private const val K_COACHING_PROGRESS = "cp"
private const val K_EVENT_OCCURRED_AT = "eo"
private const val K_HABIT_LABEL = "hl"
private const val K_ENDED_DAYS = "ed"
private const val K_MILESTONE_DAY = "md"

/** Defensive decode bounds (§8.5/§10.6): a malicious peer must not be able to make the
 *  receiver allocate unbounded state through a well-signed envelope. */
private const val MAX_MILESTONE_ENTRIES = 64

fun encodePayload(p: EventPayload): ByteArray = CanonicalCbor.encodeMap(
    buildList {
        add(K_TYPE to CanonicalCbor.text(p.type))
        add(K_TEXT to CanonicalCbor.text(p.text))
        p.requestEventId?.let { add(K_REQUEST_EVENT_ID to CanonicalCbor.text(it)) }
        p.pairingEpoch?.let { add(K_PAIRING_EPOCH to CanonicalCbor.int(it.toLong())) }
        p.expectedPreviousSequence?.let { add(K_EXPECTED_PREV_SEQUENCE to CanonicalCbor.int(it)) }
        p.expectedPreviousHash?.let { add(K_EXPECTED_PREV_HASH to CanonicalCbor.bytes(it)) }
        p.decision?.let { add(K_DECISION to CanonicalCbor.bool(it)) }
        p.protocolVersion?.let { add(K_PROTOCOL_VERSION to CanonicalCbor.int(it.toLong())) }
        p.schemaVersion?.let { add(K_SCHEMA_VERSION to CanonicalCbor.int(it.toLong())) }
        p.stateAfter?.let { add(K_STATE_AFTER to encodeStateAfter(it)) }
        p.plan?.let { add(K_PLAN to encodePlan(it)) }
    },
)

fun decodePayload(bytes: ByteArray): EventPayload {
    val obj = CanonicalCbor.decodeMap(bytes)
    return EventPayload(
        type = obj[K_TYPE].AsString(),
        text = obj[K_TEXT].AsString(),
        requestEventId = obj.get(K_REQUEST_EVENT_ID)?.takeIf { !it.isNull }?.AsString(),
        pairingEpoch = obj.get(K_PAIRING_EPOCH)?.takeIf { !it.isNull }?.AsInt32(),
        expectedPreviousSequence = obj.get(K_EXPECTED_PREV_SEQUENCE)?.takeIf { !it.isNull }?.AsInt64(),
        expectedPreviousHash = obj.get(K_EXPECTED_PREV_HASH)?.takeIf { !it.isNull }?.GetByteString(),
        decision = obj.get(K_DECISION)?.takeIf { !it.isNull }?.AsBoolean(),
        protocolVersion = obj.get(K_PROTOCOL_VERSION)?.takeIf { !it.isNull }?.let(::intOrNull),
        schemaVersion = obj.get(K_SCHEMA_VERSION)?.takeIf { !it.isNull }?.let(::intOrNull),
        // Activity bodies never fail the whole decode (§8.5/FR-17): an uninterpretable or
        // future-schema body leaves stateAfter null and classifyActivity() separates
        // Malformed from Unsupported without rejecting the chain event that carries it.
        stateAfter = obj.get(K_STATE_AFTER)?.takeIf { !it.isNull }?.let(::decodeStateAfterOrNull),
        plan = obj.get(K_PLAN)?.takeIf { !it.isNull }?.let(::decodePlanOrNull),
    )
}

private fun encodeStateAfter(s: ActivityStateAfter): CBORObject = CBORObject.NewMap().also { m ->
    // Insertion order is irrelevant: the deterministic encoder sorts keys canonically.
    m.set(K_DAYS_FREE, CanonicalCbor.int(s.daysFree.toLong()))
    m.set(K_RELAPSE_COUNT, CanonicalCbor.int(s.relapseCount.toLong()))
    m.set(
        K_MILESTONES,
        CBORObject.NewArray().also { arr ->
            s.milestonesReached.forEach { arr.Add(CanonicalCbor.int(it.toLong())) }
        },
    )
    m.set(K_COACHING_PROGRESS, CanonicalCbor.int(s.coachingProgress.toLong()))
    m.set(K_EVENT_OCCURRED_AT, CanonicalCbor.int(s.eventOccurredAtMs))
    s.habitLabel?.let { m.set(K_HABIT_LABEL, CanonicalCbor.text(it)) }
    s.endedDays?.let { m.set(K_ENDED_DAYS, CanonicalCbor.int(it.toLong())) }
    s.milestoneDay?.let { m.set(K_MILESTONE_DAY, CanonicalCbor.int(it.toLong())) }
}

private fun decodeStateAfterOrNull(obj: CBORObject): ActivityStateAfter? = try {
    val milestones = obj.get(K_MILESTONES)
    if (obj.type != CBORType.Map ||
        milestones == null || milestones.isNull ||
        milestones.type != CBORType.Array || milestones.size() > MAX_MILESTONE_ENTRIES
    ) {
        null
    } else {
        ActivityStateAfter(
            daysFree = obj[K_DAYS_FREE].AsInt32(),
            relapseCount = obj[K_RELAPSE_COUNT].AsInt32(),
            milestonesReached = milestones.getValues().map { it.AsInt32() },
            coachingProgress = obj[K_COACHING_PROGRESS].AsInt32(),
            eventOccurredAtMs = obj[K_EVENT_OCCURRED_AT].AsInt64(),
            // Wire v2 optionals (ADR #134): the name/ordinal annotates the state — a bad
            // value declines itself to null without rejecting the counts it rides on.
            habitLabel = textOrNull(obj.get(K_HABIT_LABEL))
                ?.takeIf { it.isNotBlank() && it.length <= EventPayload.MAX_HABIT_LABEL_LENGTH },
            endedDays = obj.get(K_ENDED_DAYS)?.takeIf { !it.isNull }?.let(::intOrNull)
                ?.takeIf { it >= 0 },
            milestoneDay = obj.get(K_MILESTONE_DAY)?.takeIf { !it.isNull }?.let(::intOrNull)
                ?.takeIf { it > 0 },
        )
    }
} catch (e: RuntimeException) {
    null
}

/** `AsInt32` throws when the wire value does not fit — a null-safe narrowing for version
 *  fields, whose only meaningful values are small. */
private fun intOrNull(obj: CBORObject): Int? = try {
    obj.AsInt32()
} catch (e: RuntimeException) {
    null
}

/** Wrong-type or absent optional text degrades to null — never rejects the state it rides on. */
private fun textOrNull(obj: CBORObject?): String? = try {
    if (obj == null || obj.isNull) null else obj.AsString()
} catch (e: RuntimeException) {
    null
}
