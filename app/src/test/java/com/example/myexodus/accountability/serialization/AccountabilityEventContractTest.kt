package com.example.myexodus.accountability.serialization

import com.upokecenter.cbor.CBORObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Step 3 validation: every allowed event type round-trips, every prohibited data class has
 * no way in, and the decoder declines anything outside the closed contract.
 */
class AccountabilityEventContractTest {

    private val id = "01234567-89ab-cdef-0123-456789abcdef"
    private val now = 1_760_000_123_456L
    private val minute = Math.floorDiv(now, 60_000L)

    private fun block(category: AccountabilityEventCategory = AccountabilityEventCategory.BLOCKED_APP_ATTEMPT) =
        AccountabilityEventWire.accountability(id, 7, category, now)

    private fun snapshot(category: AccountabilityEventCategory = AccountabilityEventCategory.DEVICE_STATUS_CHANGED) =
        AccountabilityEventWire.monitoring(
            eventId = id,
            sequence = 7,
            category = category,
            occurredAtMillis = now,
            state = MonitoringState.DEGRADED,
            outboxAge = OutboxAgeBucket.ONE_TO_SIX_HOURS,
            latestSequence = 9,
        )

    private fun gap() = AccountabilityEventWire.gap(id, 7, now, missingStart = 3, missingEnd = 5)

    private val ruleUuid = UUID.fromString("89abcdef-0123-4567-89ab-cdef01234567")

    private fun ruleChange(change: RuleChange = RuleChange.ASSIGNED) =
        AccountabilityEventWire.ruleControl(
            id, 7, ruleUuid, change, now,
            RuleIdentity(label = "Example app", targetType = RuleTargetType.APP),
        )

    private fun raw(vararg entries: Pair<String, CBORObject>): ByteArray =
        CanonicalCbor.encodeMap(entries.toList())

    private fun validEntries(
        kind: AccountabilityEventKind,
        category: AccountabilityEventCategory,
    ): MutableList<Pair<String, CBORObject>> {
        val entries = mutableListOf<Pair<String, CBORObject>>(
            "v" to CanonicalCbor.int(AccountabilityEventWire.SCHEMA_VERSION.toLong()),
            "id" to CanonicalCbor.text(id),
            "s" to CanonicalCbor.int(7),
            "k" to CanonicalCbor.text(kind.wire),
            "c" to CanonicalCbor.text(category.wire),
            "t" to CanonicalCbor.int(minute),
        )
        when (kind) {
            AccountabilityEventKind.MONITORING_STATUS -> {
                entries += "ms" to CanonicalCbor.text(MonitoringState.ACTIVE.wire)
                entries += "ob" to CanonicalCbor.text(OutboxAgeBucket.UNDER_1H.wire)
                entries += "ls" to CanonicalCbor.int(9)
            }

            AccountabilityEventKind.GAP_MARKER -> {
                entries += "gs" to CanonicalCbor.int(3)
                entries += "ge" to CanonicalCbor.int(5)
            }

            AccountabilityEventKind.RULE_CONTROL -> {
                entries += "rid" to CanonicalCbor.text(ruleUuid.toString())
                entries += "chg" to CanonicalCbor.text(RuleChange.ASSIGNED.wire)
            }

            AccountabilityEventKind.ACCOUNTABILITY_EVENT -> Unit
        }
        return entries
    }

    @Test
    fun everyAllowedCategoryRoundTrips() {
        AccountabilityEventCategory.entries.forEach { category ->
            val event = when (category.kind) {
                AccountabilityEventKind.ACCOUNTABILITY_EVENT -> block(category)
                AccountabilityEventKind.MONITORING_STATUS -> snapshot(category)
                AccountabilityEventKind.GAP_MARKER -> gap()
                AccountabilityEventKind.RULE_CONTROL -> ruleChange()
            }
            assertEquals(category.kind, event.kind)
            val decoded = AccountabilityEventWire.decodeOrNull(AccountabilityEventWire.encode(event))
            assertEquals(event, decoded)
        }
        assertEquals(7, AccountabilityEventCategory.entries.size)
    }

    @Test
    fun ruleChangesCarryAUuidAndAClosedChangeOnly() {
        val event = ruleChange()
        assertEquals(AccountabilityEventCategory.RULE_CHANGED, event.category)
        assertEquals(AccountabilityEventKind.RULE_CONTROL, event.kind)
        assertEquals(event, AccountabilityEventWire.decodeOrNull(AccountabilityEventWire.encode(event)))

        val entries = validEntries(AccountabilityEventKind.RULE_CONTROL, AccountabilityEventCategory.RULE_CHANGED)
        assertNull(
            "a rule id must be a canonical UUID",
            AccountabilityEventWire.decodeOrNull(
                raw(*entries.map { if (it.first == "rid") "rid" to CanonicalCbor.text("com.secret.bank") else it }.toTypedArray()),
            ),
        )
        assertNull(
            "a change must come from the closed enum",
            AccountabilityEventWire.decodeOrNull(
                raw(*entries.map { if (it.first == "chg") "chg" to CanonicalCbor.text("WEAKENED") else it }.toTypedArray()),
            ),
        )
        assertNull(
            "both rule fields are required",
            AccountabilityEventWire.decodeOrNull(raw(*entries.filterNot { it.first == "rid" }.toTypedArray())),
        )
        assertNull(
            "a block event may not carry rule fields",
            AccountabilityEventWire.decodeOrNull(
                raw(
                    *validEntries(AccountabilityEventKind.ACCOUNTABILITY_EVENT, AccountabilityEventCategory.BLOCKED_APP_ATTEMPT).toTypedArray(),
                    "rid" to CanonicalCbor.text(ruleUuid.toString()),
                ),
            ),
        )
        assertNull(
            "a rule event may not carry monitoring fields",
            AccountabilityEventWire.decodeOrNull(
                raw(*entries.toTypedArray(), "ms" to CanonicalCbor.text(MonitoringState.ACTIVE.wire)),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AccountabilityEventWire.accountability(id, 1, AccountabilityEventCategory.RULE_CHANGED, now)
        }
    }

    @Test
    fun timeIsRoundedDownToTheMinute() {
        val event = block()
        assertEquals(minute, event.occurredAtMinute)
        assertEquals(minute - 1, AccountabilityEventWire.minuteOf(minute * 60_000L - 1))
        assertEquals(minute, AccountabilityEventWire.minuteOf(minute * 60_000L))
    }

    @Test
    fun encodingIsDeterministicAndBounded() {
        val first = AccountabilityEventWire.encode(snapshot())
        val second = AccountabilityEventWire.encode(snapshot())
        assertArrayEquals(first, second)
        assertTrue(first.size <= AccountabilityEventWire.MAX_BODY_BYTES)
        assertNull(AccountabilityEventWire.decodeOrNull(ByteArray(AccountabilityEventWire.MAX_BODY_BYTES + 1)))
    }

    @Test
    fun prohibitedDataFieldsHaveNoWayIn() {
        val event = block()
        val prohibited = listOf(
            "url" to "https://secret.example/path",
            "host" to "secret.example",
            "domain" to "secret.example",
            "pkg" to "com.secret.bank",
            "package" to "com.secret.bank",
            "label" to "Secret Bank",
            "text" to "notification: code 1234",
            "notification" to "code 1234",
            "json" to "{\"raw\":true}",
            "lat" to "1.234",
            "lon" to "2.345",
            "location" to "Nairobi",
        )
        prohibited.forEach { (key, value) ->
            val bytes = raw(
                *validEntries(event.kind, event.category).toTypedArray(),
                key to CanonicalCbor.text(value),
            )
            assertNull("$key must be rejected", AccountabilityEventWire.decodeOrNull(bytes))
        }
    }

    @Test
    fun eventIdIsAUuidOnlySoItCannotCarryText() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountabilityEventWire.accountability("https://secret.example", 1, AccountabilityEventCategory.BLOCKED_DOMAIN_ATTEMPT, now)
        }
        val bytes = raw(*validEntries(AccountabilityEventKind.ACCOUNTABILITY_EVENT, AccountabilityEventCategory.BLOCKED_APP_ATTEMPT)
            .map { if (it.first == "id") "id" to CanonicalCbor.text("com.secret.bank") else it }
            .toTypedArray())
        assertNull(AccountabilityEventWire.decodeOrNull(bytes))
        assertTrue(AccountabilityEventWire.isEventId(AccountabilityEventWire.newEventId()))
    }

    @Test
    fun noFieldCanCarryFreeText() {
        val stringFields = AccountabilityEvent::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.name }
        assertEquals(listOf("eventId"), stringFields)
    }

    @Test
    fun unknownVersionKindCategoryOrEnumIsDeclined() {
        val entries = validEntries(
            AccountabilityEventKind.ACCOUNTABILITY_EVENT,
            AccountabilityEventCategory.BLOCKED_APP_ATTEMPT,
        )
        assertNull(
            AccountabilityEventWire.decodeOrNull(
                raw(
                    *entries.map {
                        if (it.first == "v") "v" to CanonicalCbor.int(AccountabilityEventWire.SCHEMA_VERSION + 1L) else it
                    }.toTypedArray(),
                ),
            ),
        )
        assertNull(
            AccountabilityEventWire.decodeOrNull(
                raw(*entries.map { if (it.first == "k") "k" to CanonicalCbor.text("SOMETHING") else it }.toTypedArray()),
            ),
        )
        assertNull(
            AccountabilityEventWire.decodeOrNull(
                raw(*entries.map { if (it.first == "c") "c" to CanonicalCbor.text("UNKNOWN_CATEGORY") else it }.toTypedArray()),
            ),
        )
        assertNull(
            AccountabilityEventWire.decodeOrNull(
                raw(
                    *(validEntries(AccountabilityEventKind.MONITORING_STATUS, AccountabilityEventCategory.DEVICE_STATUS_CHANGED)
                        .map { if (it.first == "ms") "ms" to CanonicalCbor.text("ON_FIRE") else it }
                        .toTypedArray()),
                ),
            ),
        )
    }

    @Test
    fun wrongTypesAreDeclined() {
        val entries = validEntries(
            AccountabilityEventKind.ACCOUNTABILITY_EVENT,
            AccountabilityEventCategory.BLOCKED_APP_ATTEMPT,
        )
        assertNull(
            AccountabilityEventWire.decodeOrNull(
                raw(*entries.map { if (it.first == "s") "s" to CanonicalCbor.text("seven") else it }.toTypedArray()),
            ),
        )
        assertNull(
            AccountabilityEventWire.decodeOrNull(
                raw(*entries.map { if (it.first == "t") "t" to CanonicalCbor.text("noon") else it }.toTypedArray()),
            ),
        )
        assertNull(
            AccountabilityEventWire.decodeOrNull(
                raw(*entries.map { if (it.first == "c") "c" to CanonicalCbor.int(1) else it }.toTypedArray()),
            ),
        )
    }

    @Test
    fun kindAndCategoryMustAgree() {
        val entries = validEntries(
            AccountabilityEventKind.MONITORING_STATUS,
            AccountabilityEventCategory.BLOCKED_APP_ATTEMPT,
        )
        assertNull(AccountabilityEventWire.decodeOrNull(raw(*entries.toTypedArray())))

        val gapWithBlockCategory = validEntries(
            AccountabilityEventKind.GAP_MARKER,
            AccountabilityEventCategory.BLOCKED_DOMAIN_ATTEMPT,
        )
        assertNull(AccountabilityEventWire.decodeOrNull(raw(*gapWithBlockCategory.toTypedArray())))
    }

    @Test
    fun requiredAndForbiddenFieldsFollowTheKind() {
        val blockEntries = validEntries(
            AccountabilityEventKind.ACCOUNTABILITY_EVENT,
            AccountabilityEventCategory.BLOCKED_APP_ATTEMPT,
        )
        assertNull(
            "a block event may not carry a monitoring snapshot",
            AccountabilityEventWire.decodeOrNull(
                raw(*blockEntries.toTypedArray(), "ms" to CanonicalCbor.text(MonitoringState.ACTIVE.wire)),
            ),
        )
        assertNull(
            "a block event may not carry a gap range",
            AccountabilityEventWire.decodeOrNull(
                raw(*blockEntries.toTypedArray(), "gs" to CanonicalCbor.int(1)),
            ),
        )
        assertNull(
            "a monitoring snapshot needs its state",
            AccountabilityEventWire.decodeOrNull(
                raw(
                    *validEntries(AccountabilityEventKind.MONITORING_STATUS, AccountabilityEventCategory.DEVICE_STATUS_CHANGED)
                        .filterNot { it.first == "ms" }
                        .toTypedArray(),
                ),
            ),
        )
        assertNull(
            "a gap marker needs its range",
            AccountabilityEventWire.decodeOrNull(
                raw(
                    *validEntries(AccountabilityEventKind.GAP_MARKER, AccountabilityEventCategory.GAP_MARKER)
                        .filterNot { it.first == "ge" }
                        .toTypedArray(),
                ),
            ),
        )
        assertNull(
            "a category may not arrive under another kind's field set",
            AccountabilityEventWire.decodeOrNull(
                raw(*validEntries(AccountabilityEventKind.MONITORING_STATUS, AccountabilityEventCategory.BLOCKED_DOMAIN_ATTEMPT).toTypedArray()),
            ),
        )
        assertNotNull(AccountabilityEventWire.decodeOrNull(raw(*blockEntries.toTypedArray())))
    }

    @Test
    fun outOfRangeNumbersAreDeclined() {
        val blockEntries = validEntries(
            AccountabilityEventKind.ACCOUNTABILITY_EVENT,
            AccountabilityEventCategory.BLOCKED_APP_ATTEMPT,
        )
        assertNull(
            AccountabilityEventWire.decodeOrNull(
                raw(*blockEntries.map { if (it.first == "s") "s" to CanonicalCbor.int(0) else it }.toTypedArray()),
            ),
        )
        assertNull(
            AccountabilityEventWire.decodeOrNull(
                raw(*blockEntries.map { if (it.first == "t") "t" to CanonicalCbor.int(0) else it }.toTypedArray()),
            ),
        )
        val monitoring = validEntries(
            AccountabilityEventKind.MONITORING_STATUS,
            AccountabilityEventCategory.DEVICE_STATUS_CHANGED,
        )
        assertNull(
            "the latest sequence cannot trail the event's own sequence",
            AccountabilityEventWire.decodeOrNull(
                raw(*monitoring.map { if (it.first == "ls") "ls" to CanonicalCbor.int(6) else it }.toTypedArray()),
            ),
        )
        val gapEntries = validEntries(
            AccountabilityEventKind.GAP_MARKER,
            AccountabilityEventCategory.GAP_MARKER,
        )
        assertNull(
            AccountabilityEventWire.decodeOrNull(
                raw(*gapEntries.map { if (it.first == "ge") "ge" to CanonicalCbor.int(2) else it }.toTypedArray()),
            ),
        )
    }

    @Test
    fun malformedBytesAreDeclinedWithoutThrowing() {
        assertNull(AccountabilityEventWire.decodeOrNull(ByteArray(0)))
        assertNull(AccountabilityEventWire.decodeOrNull(byteArrayOf(0x01, 0x02, 0x03)))
        assertNull(AccountabilityEventWire.decodeOrNull(CanonicalCbor.encode(CanonicalCbor.int(1))))
    }

    @Test
    fun deliveryStatesMatchTheOutboxContract() {
        assertEquals(
            listOf(
                "PENDING",
                "IN_FLIGHT",
                "ACCEPTED_BY_RELAY",
                "ACKNOWLEDGED_BY_RECIPIENT",
                "CANCELLED_BY_REVOCATION",
                "EXPIRED",
                "FAILED_NEEDS_REPAIR",
            ),
            EventDeliveryState.entries.map { it.name },
        )
    }
}
