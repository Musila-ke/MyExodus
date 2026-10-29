package com.example.myexodus.accountability.serialization

import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import java.util.UUID

/**
 * Versioned partner-accountability event body (plan Step 3, §8.1). This is the only shape
 * that may travel for the new event-delivery feature; it is a closed allowlist, not a
 * superset of detector objects.
 *
 * There is deliberately no free-text field except [AccountabilityEvent.eventId], which is
 * format-checked as a canonical UUID. That is the minimizer: a raw URL, hostname, package
 * name, app label, notification line, location, or arbitrary JSON value has no field to
 * occupy, and [decodeOrNull] rejects unknown keys rather than ignoring them.
 *
 * Recipient relationship and delivery state are local/relay metadata (§8.1 last paragraph),
 * never part of this body. [EventDeliveryState] names the sender-side states Step 4 persists;
 * it is not serialized here.
 */
enum class AccountabilityEventKind(val wire: String) {
    ACCOUNTABILITY_EVENT("ACCOUNTABILITY_EVENT"),
    MONITORING_STATUS("MONITORING_STATUS"),
    GAP_MARKER("GAP_MARKER"),
    RULE_CONTROL("RULE_CONTROL"),
    ;

    companion object {
        fun fromWire(value: String): AccountabilityEventKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The five normalized categories required by plan §2 FR2, plus the internal gap marker.
 * Consent is scoped per category (Step 6); a category is the coarsest description of *what
 * happened* and carries no target identity.
 */
enum class AccountabilityEventCategory(val wire: String, val kind: AccountabilityEventKind) {
    BLOCKED_APP_ATTEMPT("BLOCKED_APP_ATTEMPT", AccountabilityEventKind.ACCOUNTABILITY_EVENT),
    BLOCKED_DOMAIN_ATTEMPT("BLOCKED_DOMAIN_ATTEMPT", AccountabilityEventKind.ACCOUNTABILITY_EVENT),
    MONITORING_PERMISSION_CHANGED("MONITORING_PERMISSION_CHANGED", AccountabilityEventKind.MONITORING_STATUS),
    MONITORING_COMPONENT_UNAVAILABLE("MONITORING_COMPONENT_UNAVAILABLE", AccountabilityEventKind.MONITORING_STATUS),
    DEVICE_STATUS_CHANGED("DEVICE_STATUS_CHANGED", AccountabilityEventKind.MONITORING_STATUS),
    GAP_MARKER("GAP_MARKER", AccountabilityEventKind.GAP_MARKER),
    RULE_CHANGED("RULE_CHANGED", AccountabilityEventKind.RULE_CONTROL),
    ;

    companion object {
        fun fromWire(value: String): AccountabilityEventCategory? = entries.firstOrNull { it.wire == value }
    }
}

enum class MonitoringState(val wire: String) {
    ACTIVE("ACTIVE"),
    DEGRADED("DEGRADED"),
    UNAVAILABLE("UNAVAILABLE"),
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        fun fromWire(value: String): MonitoringState? = entries.firstOrNull { it.wire == value }
    }
}

/** Coarse outbox age for a monitoring snapshot — a bucket, never a raw timestamp delta. */
enum class OutboxAgeBucket(val wire: String) {
    UNDER_1H("UNDER_1H"),
    ONE_TO_SIX_HOURS("ONE_TO_SIX_HOURS"),
    SIX_TO_TWENTY_FOUR_HOURS("SIX_TO_TWENTY_FOUR_HOURS"),
    OVER_TWENTY_FOUR_HOURS("OVER_TWENTY_FOUR_HOURS"),
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        fun fromWire(value: String): OutboxAgeBucket? = entries.firstOrNull { it.wire == value }
    }
}

/** Closed rule-lifecycle vocabulary; the rule id rides as a UUID, never as free text. */
enum class RuleChange(val wire: String) {
    CREATED("CREATED"),
    ASSIGNED("ASSIGNED"),
    UNASSIGNED("UNASSIGNED"),
    UPDATED("UPDATED"),
    DISABLED("DISABLED"),
    DELETED("DELETED"),
    ;

    companion object {
        fun fromWire(value: String): RuleChange? = entries.firstOrNull { it.wire == value }
    }
}

/** Plan §8.4 sender-side states, mapped one-to-one by the Step 4 outbox. Never on the wire. */
enum class EventDeliveryState {
    PENDING,
    IN_FLIGHT,
    ACCEPTED_BY_RELAY,
    ACKNOWLEDGED_BY_RECIPIENT,
    CANCELLED_BY_REVOCATION,
    EXPIRED,
    FAILED_NEEDS_REPAIR,
}

data class AccountabilityEvent(
    val eventId: String,
    /** Monotonic within one sender-install/relationship stream; allocated transactionally. */
    val relationshipSequence: Long,
    val category: AccountabilityEventCategory,
    /** Epoch minute (UTC), rounded down. Untrusted client metadata, never a DMS basis. */
    val occurredAtMinute: Long,
    val monitoringState: MonitoringState? = null,
    val outboxAgeBucket: OutboxAgeBucket? = null,
    val latestRelationshipSequence: Long? = null,
    val missingSequenceStart: Long? = null,
    val missingSequenceEnd: Long? = null,
    val ruleId: UUID? = null,
    val ruleChange: RuleChange? = null,
) {
    val kind: AccountabilityEventKind = category.kind

    init {
        require(AccountabilityEventWire.isEventId(eventId)) { "event id is not a UUID" }
        require(relationshipSequence >= 1) { "relationship sequence must be positive" }
        require(occurredAtMinute >= 1) { "occurred-at minute must be positive" }
        when (kind) {
            AccountabilityEventKind.ACCOUNTABILITY_EVENT -> require(
                monitoringState == null && outboxAgeBucket == null &&
                    latestRelationshipSequence == null &&
                    missingSequenceStart == null && missingSequenceEnd == null &&
                    ruleId == null && ruleChange == null,
            ) { "accountability events carry no monitoring, gap, or rule fields" }

            AccountabilityEventKind.MONITORING_STATUS -> {
                requireNotNull(monitoringState) { "monitoring snapshots need a state" }
                requireNotNull(outboxAgeBucket) { "monitoring snapshots need an outbox age" }
                val latest = requireNotNull(latestRelationshipSequence) {
                    "monitoring snapshots need the latest relationship sequence"
                }
                require(latest >= relationshipSequence) { "latest sequence cannot trail this event" }
                require(missingSequenceStart == null && missingSequenceEnd == null) {
                    "monitoring snapshots carry no gap range"
                }
                require(ruleId == null && ruleChange == null) {
                    "monitoring snapshots carry no rule fields"
                }
            }

            AccountabilityEventKind.GAP_MARKER -> {
                val start = requireNotNull(missingSequenceStart) { "gap marker needs a start" }
                val end = requireNotNull(missingSequenceEnd) { "gap marker needs an end" }
                require(start >= 1 && end >= start) { "gap range is invalid" }
                require(
                    monitoringState == null && outboxAgeBucket == null &&
                        latestRelationshipSequence == null &&
                        ruleId == null && ruleChange == null,
                ) { "gap markers carry no monitoring or rule fields" }
            }

            AccountabilityEventKind.RULE_CONTROL -> {
                requireNotNull(ruleId) { "rule control events need a rule id" }
                requireNotNull(ruleChange) { "rule control events need a change" }
                require(
                    monitoringState == null && outboxAgeBucket == null &&
                        latestRelationshipSequence == null &&
                        missingSequenceStart == null && missingSequenceEnd == null,
                ) { "rule control events carry no monitoring or gap fields" }
            }
        }
    }
}

object AccountabilityEventWire {

    const val SCHEMA_VERSION = 1

    /** Hard body bound. Relay ciphertext ceiling is 4096 bytes; this leaves ample envelope room. */
    const val MAX_BODY_BYTES = 512

    private val EVENT_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private const val K_VERSION = "v"
    private const val K_EVENT_ID = "id"
    private const val K_SEQUENCE = "s"
    private const val K_KIND = "k"
    private const val K_CATEGORY = "c"
    private const val K_TIME = "t"
    private const val K_MONITORING_STATE = "ms"
    private const val K_OUTBOX_AGE = "ob"
    private const val K_LATEST_SEQUENCE = "ls"
    private const val K_MISSING_START = "gs"
    private const val K_MISSING_END = "ge"
    private const val K_RULE_ID = "rid"
    private const val K_RULE_CHANGE = "chg"

    private val ALL_KEYS = setOf(
        K_VERSION, K_EVENT_ID, K_SEQUENCE, K_KIND, K_CATEGORY, K_TIME,
        K_MONITORING_STATE, K_OUTBOX_AGE, K_LATEST_SEQUENCE, K_MISSING_START, K_MISSING_END,
        K_RULE_ID, K_RULE_CHANGE,
    )

    fun newEventId(): String = UUID.randomUUID().toString()

    fun isEventId(value: String): Boolean = EVENT_ID.matches(value)

    private fun canonicalUuidOrNull(value: String): UUID? =
        if (EVENT_ID.matches(value)) UUID.fromString(value) else null

    /** Epoch millis → the coarse minute the wire carries. Floor, so the time never moves later. */
    fun minuteOf(epochMillis: Long): Long = Math.floorDiv(epochMillis, 60_000L)

    fun accountability(
        eventId: String,
        sequence: Long,
        category: AccountabilityEventCategory,
        occurredAtMillis: Long,
    ): AccountabilityEvent {
        require(category.kind == AccountabilityEventKind.ACCOUNTABILITY_EVENT) {
            "not an accountability category"
        }
        return AccountabilityEvent(eventId, sequence, category, minuteOf(occurredAtMillis))
    }

    fun monitoring(
        eventId: String,
        sequence: Long,
        category: AccountabilityEventCategory,
        occurredAtMillis: Long,
        state: MonitoringState,
        outboxAge: OutboxAgeBucket,
        latestSequence: Long,
    ): AccountabilityEvent {
        require(category.kind == AccountabilityEventKind.MONITORING_STATUS) {
            "not a monitoring category"
        }
        return AccountabilityEvent(
            eventId = eventId,
            relationshipSequence = sequence,
            category = category,
            occurredAtMinute = minuteOf(occurredAtMillis),
            monitoringState = state,
            outboxAgeBucket = outboxAge,
            latestRelationshipSequence = latestSequence,
        )
    }

    fun ruleControl(
        eventId: String,
        sequence: Long,
        ruleId: UUID,
        change: RuleChange,
        occurredAtMillis: Long,
    ): AccountabilityEvent = AccountabilityEvent(
        eventId = eventId,
        relationshipSequence = sequence,
        category = AccountabilityEventCategory.RULE_CHANGED,
        occurredAtMinute = minuteOf(occurredAtMillis),
        ruleId = ruleId,
        ruleChange = change,
    )

    fun gap(
        eventId: String,
        sequence: Long,
        occurredAtMillis: Long,
        missingStart: Long,
        missingEnd: Long,
    ): AccountabilityEvent = AccountabilityEvent(
        eventId = eventId,
        relationshipSequence = sequence,
        category = AccountabilityEventCategory.GAP_MARKER,
        occurredAtMinute = minuteOf(occurredAtMillis),
        missingSequenceStart = missingStart,
        missingSequenceEnd = missingEnd,
    )

    fun encode(event: AccountabilityEvent): ByteArray {
        val entries = mutableListOf<Pair<String, CBORObject>>(
            K_VERSION to CanonicalCbor.int(SCHEMA_VERSION.toLong()),
            K_EVENT_ID to CanonicalCbor.text(event.eventId),
            K_SEQUENCE to CanonicalCbor.int(event.relationshipSequence),
            K_KIND to CanonicalCbor.text(event.kind.wire),
            K_CATEGORY to CanonicalCbor.text(event.category.wire),
            K_TIME to CanonicalCbor.int(event.occurredAtMinute),
        )
        event.monitoringState?.let { entries += K_MONITORING_STATE to CanonicalCbor.text(it.wire) }
        event.outboxAgeBucket?.let { entries += K_OUTBOX_AGE to CanonicalCbor.text(it.wire) }
        event.latestRelationshipSequence?.let { entries += K_LATEST_SEQUENCE to CanonicalCbor.int(it) }
        event.missingSequenceStart?.let { entries += K_MISSING_START to CanonicalCbor.int(it) }
        event.missingSequenceEnd?.let { entries += K_MISSING_END to CanonicalCbor.int(it) }
        event.ruleId?.let { entries += K_RULE_ID to CanonicalCbor.text(it.toString()) }
        event.ruleChange?.let { entries += K_RULE_CHANGE to CanonicalCbor.text(it.wire) }
        return CanonicalCbor.encodeMap(entries)
    }

    /**
     * Null = not this contract: unknown key, wrong type, unknown enum value, missing or
     * forbidden field for the kind, out-of-range number, or oversized bytes. Never throws and
     * never partially applies. Callers decline the body while leaving the carrying envelope
     * untouched (the chain event is still cryptographically valid).
     */
    fun decodeOrNull(bytes: ByteArray): AccountabilityEvent? {
        if (bytes.isEmpty() || bytes.size > MAX_BODY_BYTES) return null
        val map = try {
            CanonicalCbor.decodeMap(bytes)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val keys = map.keys.mapNotNull { key ->
            if (key.type == CBORType.TextString) key.AsString() else null
        }.toSet()
        if (keys.size != map.keys.size || !ALL_KEYS.containsAll(keys)) return null

        val version = map.integerOrNull(K_VERSION) ?: return null
        if (version != SCHEMA_VERSION.toLong()) return null
        val eventId = map.textOrNull(K_EVENT_ID) ?: return null
        val sequence = map.integerOrNull(K_SEQUENCE) ?: return null
        val kind = map.textOrNull(K_KIND)
            ?.let(AccountabilityEventKind::fromWire) ?: return null
        val category = map.textOrNull(K_CATEGORY)
            ?.let(AccountabilityEventCategory::fromWire) ?: return null
        val time = map.integerOrNull(K_TIME) ?: return null

        val state = if (keys.contains(K_MONITORING_STATE)) {
            map.textOrNull(K_MONITORING_STATE)?.let(MonitoringState::fromWire) ?: return null
        } else {
            null
        }
        val outboxAge = if (keys.contains(K_OUTBOX_AGE)) {
            map.textOrNull(K_OUTBOX_AGE)?.let(OutboxAgeBucket::fromWire) ?: return null
        } else {
            null
        }
        val latest = if (keys.contains(K_LATEST_SEQUENCE)) {
            map.integerOrNull(K_LATEST_SEQUENCE) ?: return null
        } else {
            null
        }
        val missingStart = if (keys.contains(K_MISSING_START)) {
            map.integerOrNull(K_MISSING_START) ?: return null
        } else {
            null
        }
        val missingEnd = if (keys.contains(K_MISSING_END)) {
            map.integerOrNull(K_MISSING_END) ?: return null
        } else {
            null
        }
        val ruleId = if (keys.contains(K_RULE_ID)) {
            map.textOrNull(K_RULE_ID)?.let(::canonicalUuidOrNull) ?: return null
        } else {
            null
        }
        val ruleChange = if (keys.contains(K_RULE_CHANGE)) {
            map.textOrNull(K_RULE_CHANGE)?.let(RuleChange::fromWire) ?: return null
        } else {
            null
        }

        val expected = when (kind) {
            AccountabilityEventKind.ACCOUNTABILITY_EVENT -> {
                setOf(K_VERSION, K_EVENT_ID, K_SEQUENCE, K_KIND, K_CATEGORY, K_TIME)
            }

            AccountabilityEventKind.MONITORING_STATUS -> {
                setOf(
                    K_VERSION, K_EVENT_ID, K_SEQUENCE, K_KIND, K_CATEGORY, K_TIME,
                    K_MONITORING_STATE, K_OUTBOX_AGE, K_LATEST_SEQUENCE,
                )
            }

            AccountabilityEventKind.GAP_MARKER -> {
                setOf(
                    K_VERSION, K_EVENT_ID, K_SEQUENCE, K_KIND, K_CATEGORY, K_TIME,
                    K_MISSING_START, K_MISSING_END,
                )
            }

            AccountabilityEventKind.RULE_CONTROL -> {
                setOf(
                    K_VERSION, K_EVENT_ID, K_SEQUENCE, K_KIND, K_CATEGORY, K_TIME,
                    K_RULE_ID, K_RULE_CHANGE,
                )
            }
        }
        if (keys != expected) return null

        return try {
            AccountabilityEvent(
                eventId = eventId,
                relationshipSequence = sequence,
                category = category,
                occurredAtMinute = time,
                monitoringState = state,
                outboxAgeBucket = outboxAge,
                latestRelationshipSequence = latest,
                missingSequenceStart = missingStart,
                missingSequenceEnd = missingEnd,
                ruleId = ruleId,
                ruleChange = ruleChange,
            ).takeIf { it.kind == kind }
        } catch (e: IllegalArgumentException) {
            null
        }
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
