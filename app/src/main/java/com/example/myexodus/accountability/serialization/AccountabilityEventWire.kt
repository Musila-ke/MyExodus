package com.example.myexodus.accountability.serialization

import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import java.util.UUID

/**
 * Versioned partner-accountability event body (plan Step 3, §8.1). This is the only shape
 * that may travel for the new event-delivery feature; it is a closed allowlist, not a
 * superset of detector objects.
 *
 * The only free-text field is the bounded [RuleIdentity] label added in v2 (owner mandate
 * 2026-10-31: a partner must be told *which* rule or batch changed); it is required to be its
 * own sanitized single-line form, ≤[MAX_RULE_LABEL_CHARS], and rides only on RULE_CONTROL.
 * Everything else stays closed: a raw URL path, notification line, location, or arbitrary
 * JSON value has no field to occupy, and [decodeOrNull] rejects unknown keys rather than
 * ignoring them. Every other identity is still the format-checked UUID [AccountabilityEvent.eventId].
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

/**
 * Closed set of grants a block needs (owner directive 2026-11-10: a partner is told when a
 * shared rule loses the capability that makes it enforceable). A snapshot carries the set
 * that is *held*; the recipient derives a loss by diffing against the previous snapshot and
 * only alerts when one of its own shared rules needs the missing grant.
 */
enum class MonitoringCapability(val wire: String) {
    ACCESSIBILITY("A11Y"),
    USAGE_ACCESS("USAGE"),
    OVERLAY("OVERLAY"),
    WEBSITE_FILTER("VPN"),
    ;

    companion object {
        fun fromWire(value: String): MonitoringCapability? = entries.firstOrNull { it.wire == value }

        /** Canonical stored/encoded form: sorted wire names, comma-separated; "" = none held. */
        fun encodeSet(values: Set<MonitoringCapability>): String =
            values.sortedBy { it.wire }.joinToString(",") { it.wire }

        /** Null for an unknown grant — the caller treats the whole set as untrustworthy. */
        fun decodeSetOrNull(value: String): Set<MonitoringCapability>? {
            if (value.isEmpty()) return emptySet()
            val parsed = value.split(',').map { fromWire(it) ?: return null }
            return parsed.toSet()
        }
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

/** Closed rule-lifecycle vocabulary; the rule id rides as a UUID, never as free text.
 *  [EXPIRED] is a natural end of the rule's set duration, distinct from the owner switching
 *  it off: the partner is told the block has ended, not that it was turned off. */
enum class RuleChange(val wire: String) {
    CREATED("CREATED"),
    ASSIGNED("ASSIGNED"),
    UNASSIGNED("UNASSIGNED"),
    UPDATED("UPDATED"),
    DISABLED("DISABLED"),
    DELETED("DELETED"),
    EXPIRED("EXPIRED"),
    ;

    companion object {
        fun fromWire(value: String): RuleChange? = entries.firstOrNull { it.wire == value }
    }
}

/** Closed rule-target vocabulary for the bounded identity below. */
enum class RuleTargetType(val wire: String) {
    APP("APP"),
    WEBSITE("WEBSITE"),
    BATCH("BATCH"),
    ;

    companion object {
        fun fromWire(value: String): RuleTargetType? = entries.firstOrNull { it.wire == value }
    }
}

/** Hard caps for the bounded identity (owner mandate 2026-10-31). */
const val MAX_RULE_LABEL_CHARS = 64
const val MAX_BATCH_ITEMS = 2_000

/**
 * Bounded, sanitized identity for a rule-lifecycle event. Owner mandate 2026-10-31: a partner
 * is never told only "a restriction rule changed" — the copy must name the app, website, or
 * batch, and a batch carries its item count. [label] is the name the owner already sees
 * (app label, website host, or batch name); [batchLabel] names the batch a member rule
 * belongs to; [itemCount] is set on BATCH rows only.
 *
 * This is the one deliberate free-text opening in the closed body: the constructor requires
 * the value to be its own sanitized single-line form (no control characters, collapsed
 * whitespace, ≤[MAX_RULE_LABEL_CHARS]), so a raw URL path, notification line, or injected
 * newline still has nowhere to hide.
 */
data class RuleIdentity(
    val label: String,
    val targetType: RuleTargetType,
    val itemCount: Long? = null,
    val batchLabel: String? = null,
) {
    init {
        require(label.isNotBlank() && label == AccountabilityEventWire.sanitizeRuleLabel(label)) {
            "rule label is not a sanitized single line"
        }
        if (targetType == RuleTargetType.BATCH) {
            require(itemCount != null && itemCount in 1..MAX_BATCH_ITEMS.toLong()) {
                "batch rows need a bounded item count"
            }
            require(batchLabel == null) { "a batch row carries no parent batch label" }
        } else {
            require(itemCount == null) { "item count belongs to batch rows only" }
            batchLabel?.let {
                require(it.isNotBlank() && it == AccountabilityEventWire.sanitizeRuleLabel(it)) {
                    "batch label is not a sanitized single line"
                }
            }
        }
    }

    companion object {
        /** Identity for a single rule-shaped target: the display label this device shows
         *  (sanitized, falling back to the raw target), its contract target type, and the
         *  batch name when the rule belongs to one. Batch rows build their own identity. */
        fun of(
            targetType: String,
            target: String,
            label: String,
            batchName: String? = null,
        ): RuleIdentity {
            val resolved = AccountabilityEventWire.sanitizeRuleLabel(label)
                .ifBlank { AccountabilityEventWire.sanitizeRuleLabel(target) }
            return RuleIdentity(
                label = resolved.ifBlank { "Restriction rule" },
                targetType = if (targetType == "APP") RuleTargetType.APP else RuleTargetType.WEBSITE,
                batchLabel = batchName?.let(AccountabilityEventWire::sanitizeRuleLabel)
                    ?.takeIf { it.isNotBlank() },
            )
        }
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
    /** Bounded name of what changed; null only on a schema-v1 body (pre-2026-10-31 sender). */
    val ruleIdentity: RuleIdentity? = null,
    /** Grants held when the snapshot was taken; null on pre-v3 bodies. */
    val capabilities: Set<MonitoringCapability>? = null,
    /** The body version this event encodes as; decode sets it to what arrived. */
    val schemaVersion: Int = AccountabilityEventWire.SCHEMA_VERSION,
) {
    val kind: AccountabilityEventKind = category.kind

    init {
        require(AccountabilityEventWire.isEventId(eventId)) { "event id is not a UUID" }
        require(relationshipSequence >= 1) { "relationship sequence must be positive" }
        require(occurredAtMinute >= 1) { "occurred-at minute must be positive" }
        require(schemaVersion in 1..AccountabilityEventWire.SCHEMA_VERSION) {
            "event schema version is not a live contract"
        }
        // A v3 monitoring body always states the held grants; a pre-v3 body never does.
        if (kind == AccountabilityEventKind.MONITORING_STATUS) {
            require((schemaVersion >= 3) == (capabilities != null)) {
                "capabilities ride only on v3 monitoring bodies"
            }
        } else {
            require(capabilities == null) { "only monitoring snapshots carry capabilities" }
        }
        when (kind) {
            AccountabilityEventKind.ACCOUNTABILITY_EVENT -> require(
                monitoringState == null && outboxAgeBucket == null &&
                    latestRelationshipSequence == null &&
                    missingSequenceStart == null && missingSequenceEnd == null &&
                    ruleId == null && ruleChange == null && ruleIdentity == null,
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
                require(ruleId == null && ruleChange == null && ruleIdentity == null) {
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
                        ruleId == null && ruleChange == null && ruleIdentity == null,
                ) { "gap markers carry no monitoring or rule fields" }
            }

            AccountabilityEventKind.RULE_CONTROL -> {
                requireNotNull(ruleId) { "rule control events need a rule id" }
                requireNotNull(ruleChange) { "rule control events need a change" }
                require((schemaVersion >= 2) == (ruleIdentity != null)) {
                    "rule identity rides only on v2+ rule control bodies"
                }
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

    /** v2 adds the bounded [RuleIdentity] to RULE_CONTROL (owner mandate 2026-10-31); v3 adds
     *  the held-grant set to MONITORING_STATUS (owner directive 2026-11-10). Older versions
     *  still decode so a queued pre-upgrade journal row is never stranded. */
    const val SCHEMA_VERSION = 3

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
    private const val K_RULE_LABEL = "lbl"
    private const val K_RULE_TARGET = "tgt"
    private const val K_RULE_ITEMS = "cnt"
    private const val K_RULE_BATCH = "bat"
    private const val K_CAPABILITIES = "caps"

    private val ALL_KEYS = setOf(
        K_VERSION, K_EVENT_ID, K_SEQUENCE, K_KIND, K_CATEGORY, K_TIME,
        K_MONITORING_STATE, K_OUTBOX_AGE, K_LATEST_SEQUENCE, K_MISSING_START, K_MISSING_END,
        K_RULE_ID, K_RULE_CHANGE, K_RULE_LABEL, K_RULE_TARGET, K_RULE_ITEMS, K_RULE_BATCH,
        K_CAPABILITIES,
    )

    private val WHITESPACE = Regex("\\s+")

    /** The single-line, ≤[MAX_RULE_LABEL_CHARS] form a rule identity may carry: control and
     *  line-separator characters become spaces, runs collapse, the result is trimmed. */
    fun sanitizeRuleLabel(raw: String): String {
        val single = buildString(raw.length) {
            raw.forEach { ch ->
                append(if (ch.isISOControl() || ch.code == 0x2028 || ch.code == 0x2029) ' ' else ch)
            }
        }
        return single.replace(WHITESPACE, " ").trim().take(MAX_RULE_LABEL_CHARS).trim()
    }

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
        capabilities: Set<MonitoringCapability>? = null,
        // A snapshot that cannot state its grants degrades to v2 — it must never encode as a
        // v3 body claiming an empty set.
        schemaVersion: Int = if (capabilities == null) 2 else SCHEMA_VERSION,
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
            capabilities = capabilities,
            schemaVersion = schemaVersion,
        )
    }

    fun ruleControl(
        eventId: String,
        sequence: Long,
        ruleId: UUID,
        change: RuleChange,
        occurredAtMillis: Long,
        identity: RuleIdentity,
    ): AccountabilityEvent = AccountabilityEvent(
        eventId = eventId,
        relationshipSequence = sequence,
        category = AccountabilityEventCategory.RULE_CHANGED,
        occurredAtMinute = minuteOf(occurredAtMillis),
        ruleId = ruleId,
        ruleChange = change,
        ruleIdentity = identity,
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
            K_VERSION to CanonicalCbor.int(event.schemaVersion.toLong()),
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
        event.capabilities?.let {
            entries += K_CAPABILITIES to CanonicalCbor.text(MonitoringCapability.encodeSet(it))
        }
        event.ruleId?.let { entries += K_RULE_ID to CanonicalCbor.text(it.toString()) }
        event.ruleChange?.let { entries += K_RULE_CHANGE to CanonicalCbor.text(it.wire) }
        event.ruleIdentity?.let { identity ->
            entries += K_RULE_LABEL to CanonicalCbor.text(identity.label)
            entries += K_RULE_TARGET to CanonicalCbor.text(identity.targetType.wire)
            identity.itemCount?.let { entries += K_RULE_ITEMS to CanonicalCbor.int(it) }
            identity.batchLabel?.let { entries += K_RULE_BATCH to CanonicalCbor.text(it) }
        }
        require(event.kind != AccountabilityEventKind.RULE_CONTROL || event.ruleIdentity != null) {
            "rule control bodies carry an identity"
        }
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
        // Older bodies still decode: a journal row queued before an upgrade must not strand.
        if (version < 1L || version > SCHEMA_VERSION.toLong()) return null
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
        val capabilities = if (keys.contains(K_CAPABILITIES)) {
            if (version < 3L) return null
            map.textOrNull(K_CAPABILITIES)
                ?.let(MonitoringCapability::decodeSetOrNull) ?: return null
        } else {
            null
        }
        val identityKeys = setOf(K_RULE_LABEL, K_RULE_TARGET, K_RULE_ITEMS, K_RULE_BATCH)
        val ruleIdentity = if (keys.any(identityKeys::contains)) {
            if (version < 2L) return null
            val label = map.textOrNull(K_RULE_LABEL) ?: return null
            val target = map.textOrNull(K_RULE_TARGET)
                ?.let(RuleTargetType::fromWire) ?: return null
            val count = if (keys.contains(K_RULE_ITEMS)) {
                map.integerOrNull(K_RULE_ITEMS) ?: return null
            } else {
                null
            }
            val batch = if (keys.contains(K_RULE_BATCH)) {
                map.textOrNull(K_RULE_BATCH) ?: return null
            } else {
                null
            }
            runCatching { RuleIdentity(label, target, count, batch) }.getOrNull() ?: return null
        } else {
            null
        }

        val expected = when (kind) {
            AccountabilityEventKind.ACCOUNTABILITY_EVENT -> {
                setOf(K_VERSION, K_EVENT_ID, K_SEQUENCE, K_KIND, K_CATEGORY, K_TIME)
            }

            AccountabilityEventKind.MONITORING_STATUS -> {
                buildSet {
                    addAll(
                        listOf(
                            K_VERSION, K_EVENT_ID, K_SEQUENCE, K_KIND, K_CATEGORY, K_TIME,
                            K_MONITORING_STATE, K_OUTBOX_AGE, K_LATEST_SEQUENCE,
                        ),
                    )
                    if (version >= 3L) add(K_CAPABILITIES)
                }
            }

            AccountabilityEventKind.GAP_MARKER -> {
                setOf(
                    K_VERSION, K_EVENT_ID, K_SEQUENCE, K_KIND, K_CATEGORY, K_TIME,
                    K_MISSING_START, K_MISSING_END,
                )
            }

            AccountabilityEventKind.RULE_CONTROL -> {
                if (version >= 2L) {
                    val identity = ruleIdentity ?: return null
                    buildSet {
                        addAll(
                            listOf(
                                K_VERSION, K_EVENT_ID, K_SEQUENCE, K_KIND, K_CATEGORY, K_TIME,
                                K_RULE_ID, K_RULE_CHANGE, K_RULE_LABEL, K_RULE_TARGET,
                            ),
                        )
                        when {
                            identity.targetType == RuleTargetType.BATCH -> add(K_RULE_ITEMS)
                            identity.batchLabel != null -> add(K_RULE_BATCH)
                        }
                    }
                } else {
                    setOf(
                        K_VERSION, K_EVENT_ID, K_SEQUENCE, K_KIND, K_CATEGORY, K_TIME,
                        K_RULE_ID, K_RULE_CHANGE,
                    )
                }
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
                ruleIdentity = ruleIdentity,
                capabilities = capabilities,
                schemaVersion = version.toInt(),
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
