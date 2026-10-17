package com.example.myexodus.accountability.serialization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPayloadTest {

    @Test
    fun payload_roundTrips() {
        val p = EventPayload(type = EventPayload.TYPE_ACTIVITY, text = "watched youtube 12min")
        val decoded = decodePayload(encodePayload(p))
        assertEquals(p, decoded)
    }

    @Test
    fun nfcNormalization_happensBeforeEncoding() {
        val decomposed = "café" // "café" with combining acute
        val composed = "café"
        assertNotEquals(decomposed, composed)
        // Both forms produce identical ciphertext-bound bytes: the encode is NFC-normalized.
        val a = encodePayload(EventPayload(text = decomposed))
        val b = encodePayload(EventPayload(text = composed))
        assertEquals(a.toList(), b.toList())
        assertEquals("café", decodePayload(b).text)
    }

    @Test
    fun deletionRequest_typeSurvivesRoundTrip() {
        val p = EventPayload(type = EventPayload.TYPE_DELETION_REQUEST, text = "delete")
        assertEquals(EventPayload.TYPE_DELETION_REQUEST, decodePayload(encodePayload(p)).type)
    }

    /** Plan §8 Step 4 / AD-07: the DisableApprovalResponse reference fields. */
    @Test
    fun approvalReferenceFields_roundTrip() {
        val p = EventPayload(
            type = EventPayload.TYPE_APPROVAL_RESPONSE,
            text = "approved",
            requestEventId = "req-event-1",
            pairingEpoch = 3,
        )
        assertEquals(p, decodePayload(encodePayload(p)))
    }

    /** Unset fields must stay ABSENT (not null) so existing payload bytes are untouched. */
    @Test
    fun approvalReferenceFields_areOmittedWhenUnset() {
        val bytes = encodePayload(EventPayload(text = "watched youtube 12min"))
        val keys = CanonicalCbor.decodeMap(bytes).getKeys().map { it.AsString() }.toSet()
        assertEquals(setOf("ty", "tx"), keys)
        val decoded = decodePayload(bytes)
        assertEquals(null, decoded.requestEventId)
        assertEquals(null, decoded.pairingEpoch)
    }

    /** Plan §8 Step 11 / AD-08b: the repair request's anchor fields round-trip. (Field-wise:
     *  data-class equality is reference equality for the ByteArray.) */
    @Test
    fun syncRequestAnchorFields_roundTrip() {
        val p = EventPayload(
            type = EventPayload.TYPE_SYNC_REQUEST,
            text = "sync_request",
            expectedPreviousSequence = 42L,
            expectedPreviousHash = ByteArray(32) { 7 },
        )
        val decoded = decodePayload(encodePayload(p))
        assertEquals(EventPayload.TYPE_SYNC_REQUEST, decoded.type)
        assertEquals("sync_request", decoded.text)
        assertEquals(42L, decoded.expectedPreviousSequence)
        assertTrue(decoded.expectedPreviousHash!!.contentEquals(ByteArray(32) { 7 }))
    }

    @Test
    fun syncRequestAnchorFields_areOmittedWhenUnset() {
        val bytes = encodePayload(EventPayload(type = EventPayload.TYPE_SYNC_REQUEST, text = "sync_request"))
        val keys = CanonicalCbor.decodeMap(bytes).getKeys().map { it.AsString() }.toSet()
        assertEquals(setOf("ty", "tx"), keys)
        assertEquals(null, decodePayload(bytes).expectedPreviousSequence)
        assertEquals(null, decodePayload(bytes).expectedPreviousHash)
    }

    // --- Partner-visible activity layer (plan §8.5, Appendix A.2) ---------------------------

    /**
     * §8.5 byte stability, the load-bearing contract of this extension: adding the activity
     * fields must not move a single byte of any pre-existing payload type. These are literal
     * encodings captured from petero-cbor 4.5.6 canonical output — if the encoding profile or
     * the absent-when-unset discipline ever regresses, deployed hash chains break silently,
     * so the vector is pinned rather than computed.
     */
    @Test
    fun goldenVector_activityType_byteStable() {
        val bytes = encodePayload(EventPayload(type = "activity", text = "override:com.instagram.android"))
        assertEquals(
            "a2627478781e6f766572726964653a636f6d2e696e7374616772616d2e616e64726f6964627479686163746976697479",
            bytes.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun goldenVector_approvalResponse_byteStable() {
        val bytes = encodePayload(
            EventPayload(
                type = "approval_response",
                text = "please",
                requestEventId = "evt-1",
                pairingEpoch = 2,
                decision = true,
            ),
        )
        assertEquals(
            "a56270650262747866706c6561736562747971617070726f76616c5f726573706f6e736563646563f563726964656576742d31",
            bytes.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun activityFields_areOmittedWhenUnset() {
        // A new type with the activity fields unset still encodes only {ty, tx} — the
        // absent-when-unset mechanism does not depend on which type string was chosen.
        val bytes = encodePayload(EventPayload(type = EventPayload.TYPE_MILESTONE_REACHED, text = ""))
        val keys = CanonicalCbor.decodeMap(bytes).getKeys().map { it.AsString() }.toSet()
        assertEquals(setOf("ty", "tx"), keys)
    }

    @Test
    fun activityStateAfter_roundTripsForEveryType() {
        val state = ActivityStateAfter(
            daysFree = 12,
            relapseCount = 3,
            milestonesReached = listOf(7),
            coachingProgress = 4,
            eventOccurredAtMs = 1_787_000_000_000L,
        )
        EventPayload.ACTIVITY_TYPES.forEach { type ->
            val p = EventPayload(
                type = type,
                text = if (type == EventPayload.TYPE_MOTIVATIONAL_MESSAGE) "Stay strong" else "",
                protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
                schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
                stateAfter = state,
            )
            val decoded = decodePayload(encodePayload(p))
            assertEquals(type, decoded.type)
            assertEquals(state, decoded.stateAfter)
            assertEquals(EventPayload.ACTIVITY_PROTOCOL_VERSION, decoded.protocolVersion)
            assertEquals(EventPayload.ACTIVITY_SCHEMA_VERSION, decoded.schemaVersion)
            assertEquals(ActivityEvent.Valid(decoded), classifyActivity(decoded))
        }
    }

    /** Wire v2 (ADR #134): the streak's name and the relapse/milestone day counts survive the
     *  wire cycle — this is what lets the partner's feed name the streak and count its days. */
    @Test
    fun activityStateAfter_v2Fields_roundTrip() {
        val relapse = ActivityStateAfter(
            daysFree = 1,
            relapseCount = 4,
            milestonesReached = emptyList(),
            coachingProgress = 0,
            eventOccurredAtMs = 1_787_000_000_000L,
            habitLabel = "Instagram",
            endedDays = 12,
        )
        val milestone = relapse.copy(habitLabel = "Instagram", endedDays = null, milestoneDay = 30)
        listOf(relapse, milestone).forEach { state ->
            val decoded = decodePayload(
                encodePayload(
                    EventPayload(
                        type = EventPayload.TYPE_RELAPSE_RECORDED,
                        text = "",
                        protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
                        schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
                        stateAfter = state,
                    ),
                ),
            )
            assertEquals(state, decoded.stateAfter)
        }
    }

    /** The §8.5 byte-stability discipline extended to the state map: a body without v2 fields
     *  encodes exactly the v1 key set — no `hl`/`ed`/`md` materializes as null. */
    @Test
    fun activityStateAfter_withoutV2Fields_encodesTheV1KeySet() {
        val bytes = encodePayload(
            EventPayload(
                type = EventPayload.TYPE_STREAK_UPDATED,
                text = "",
                protocolVersion = 1,
                schemaVersion = 1,
                stateAfter = ActivityStateAfter(5, 1, listOf(7), 2, 42L),
            ),
        )
        val state = CanonicalCbor.decodeMap(bytes)["st"]
        assertEquals(
            setOf("df", "rc", "ms", "cp", "eo"),
            state.getKeys().map { it.AsString() }.toSet(),
        )
    }

    /** A malformed v2 field declines itself to null without rejecting the counts it annotates
     *  (§8.5: the body stays Valid) — the receiver then degrades to generic unnamed copy. */
    @Test
    fun decode_badV2Fields_declineThemselves_notTheCounts() {
        val good = encodePayload(
            EventPayload(
                type = EventPayload.TYPE_RELAPSE_RECORDED,
                text = "",
                protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
                schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
                stateAfter = ActivityStateAfter(
                    1, 4, emptyList(), 0, 42L,
                    habitLabel = "Instagram", endedDays = 12, milestoneDay = 7,
                ),
            ),
        )
        val obj = CanonicalCbor.decodeMap(good)
        val state = obj["st"]
        state.set("hl", CanonicalCbor.text(" "))
        state.set("ed", CanonicalCbor.int(-1L))
        state.set("md", CanonicalCbor.int(0L))
        val decoded = decodePayload(CanonicalCbor.encode(obj))

        val after = decoded.stateAfter!!
        assertEquals(null, after.habitLabel)
        assertEquals(null, after.endedDays)
        assertEquals(null, after.milestoneDay)
        assertEquals(4, after.relapseCount)
        assertTrue(classifyActivity(decoded) is ActivityEvent.Valid)
    }

    @Test
    fun activityStateAfter_emptyMilestones_roundTrip() {
        val state = ActivityStateAfter(
            daysFree = 0,
            relapseCount = 0,
            milestonesReached = emptyList(),
            coachingProgress = 0,
            eventOccurredAtMs = 1L,
        )
        val p = EventPayload(
            type = EventPayload.TYPE_RELAPSE_RECORDED,
            text = "",
            protocolVersion = 1,
            schemaVersion = 1,
            stateAfter = state,
        )
        assertEquals(state, decodePayload(encodePayload(p)).stateAfter)
    }

    /** FR-17: a future schema must be classified Unsupported — never Malformed, never dropped. */
    @Test
    fun classify_futureSchema_isUnsupported_notMalformed() {
        val p = EventPayload(
            type = EventPayload.TYPE_STREAK_UPDATED,
            text = "",
            protocolVersion = 2,
            schemaVersion = 1,
            stateAfter = ActivityStateAfter(1, 0, emptyList(), 0, 1L),
        )
        val classified = classifyActivity(p)
        assertTrue(classified is ActivityEvent.Unsupported)
        assertEquals(2, (classified as ActivityEvent.Unsupported).protocolVersion)
    }

    /** A version this build does implement, but an unusable body, is Malformed (§8.5 split). */
    @Test
    fun classify_missingStateAfter_isMalformed() {
        val p = EventPayload(
            type = EventPayload.TYPE_COACHING_STEP_UPDATED,
            text = "",
            protocolVersion = 1,
            schemaVersion = 1,
        )
        assertEquals(
            ActivityEvent.Malformed(EventPayload.TYPE_COACHING_STEP_UPDATED),
            classifyActivity(p),
        )
    }

    @Test
    fun classify_shippedTypes_areNotActivity() {
        listOf(
            EventPayload.TYPE_ACTIVITY,
            EventPayload.TYPE_DELETION_REQUEST,
            EventPayload.TYPE_SYNC_REQUEST,
            EventPayload.TYPE_APPROVAL_REQUEST,
            EventPayload.TYPE_APPROVAL_RESPONSE,
        ).forEach {
            assertEquals(ActivityEvent.NotActivity, classifyActivity(EventPayload(type = it, text = "")))
        }
    }

    /**
     * FR-17 forward compatibility at the decode layer: an activity-typed payload whose `st`
     * body is garbage must decode with stateAfter = null and never throw out of decodePayload —
     * the chain append pipeline must stay able to commit the (cryptographically valid) event.
     */
    @Test
    fun decode_garbageStateAfter_doesNotThrow() {
        val good = encodePayload(
            EventPayload(
                type = EventPayload.TYPE_STREAK_UPDATED,
                text = "x",
                protocolVersion = 1,
                schemaVersion = 1,
                stateAfter = ActivityStateAfter(1, 0, emptyList(), 0, 1L),
            ),
        )
        val obj = CanonicalCbor.decodeMap(good)
        obj["st"] = CanonicalCbor.text("not-a-map")
        val decoded = decodePayload(CanonicalCbor.encode(obj))
        assertEquals(null, decoded.stateAfter)
        assertEquals("the declared version decodes intact; only the body was garbage", 1, decoded.schemaVersion)
        assertTrue(classifyActivity(decoded) is ActivityEvent.Malformed)
    }

    /** §10.6 decode bound: an oversized milestone array is refused, not allocated. */
    @Test
    fun decode_oversizeMilestones_refused() {
        val p = EventPayload(
            type = EventPayload.TYPE_MILESTONE_REACHED,
            text = "",
            protocolVersion = 1,
            schemaVersion = 1,
            stateAfter = ActivityStateAfter(1, 0, List(65) { it + 1 }, 0, 1L),
        )
        // The encoder writes what it is given; the decoder is the bound.
        assertEquals(null, decodePayload(encodePayload(p)).stateAfter)
    }

    /** §8.5 unknown optional fields are ignored — a newer sender's extra key must not break us. */
    @Test
    fun decode_unknownOptionalField_isIgnored() {
        val good = encodePayload(
            EventPayload(
                type = EventPayload.TYPE_STREAK_UPDATED,
                text = "",
                protocolVersion = 1,
                schemaVersion = 1,
                stateAfter = ActivityStateAfter(5, 1, listOf(7), 2, 42L),
            ),
        )
        val obj = CanonicalCbor.decodeMap(good)
        obj["future_field"] = CanonicalCbor.text("ignore me")
        val decoded = decodePayload(CanonicalCbor.encode(obj))
        assertEquals(5, decoded.stateAfter!!.daysFree)
        assertTrue(classifyActivity(decoded) is ActivityEvent.Valid)
    }
}
