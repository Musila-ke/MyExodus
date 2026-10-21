package com.example.myexodus.accountability.serialization

import com.upokecenter.cbor.CBORObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ADR #193 wire contract: a plan body round-trips exactly, the `pl` key is absent when no plan
 * is carried (every pre-existing payload keeps byte-identical encoding), and a body that
 * violates identity bounds decodes to null without discarding the envelope that carried it.
 */
class PlanWireCodecTest {

    @Test
    fun roundTrip_preservesEveryField() {
        val original = PlanBody(
            planId = "plan-1",
            title = "Week 1",
            revision = 3,
            updatedAtMs = 1_800_000_000_000L,
            tasks = listOf(
                PlanTaskBody("t1", 0, "Read chapter 3"),
                PlanTaskBody("t2", 1, "Run 20 minutes"),
            ),
            doneTaskIds = listOf("t2"),
        )
        assertEquals(original, decodePlanOrNull(encodePlan(original)))
    }

    @Test
    fun absentPlan_keepsTheKeyOutOfTheEncoding() {
        val payload = EventPayload(type = EventPayload.TYPE_ACTIVITY, text = "hi")
        val map = CBORObject.DecodeFromBytes(encodePayload(payload))
        assertNull("a null plan must not be encoded at all", map["pl"])
        assertNull(decodePayload(encodePayload(payload)).plan)
    }

    @Test
    fun planPayload_roundTripsThroughTheEnvelope() {
        val body = PlanBody("plan-2", "Week 2", 1, 1_800_000_000_000L)
        val decoded = decodePayload(
            encodePayload(
                EventPayload(
                    type = PlanWire.TYPE_PLAN_PUBLISHED,
                    text = "",
                    protocolVersion = PlanWire.PLAN_PROTOCOL_VERSION,
                    schemaVersion = PlanWire.PLAN_SCHEMA_VERSION,
                    plan = body,
                ),
            ),
        )
        assertEquals(PlanWire.TYPE_PLAN_PUBLISHED, decoded.type)
        assertEquals(body, decoded.plan)
    }

    @Test
    fun invalidPlanBody_doesNotDiscardTheEnvelope() {
        val body = PlanBody("plan-3", "Week 3", 1, 1_800_000_000_000L)
        val bytes = encodePayload(
            EventPayload(
                type = PlanWire.TYPE_PLAN_PUBLISHED,
                text = "",
                protocolVersion = 1,
                schemaVersion = 1,
                plan = body,
            ),
        )
        val map = CBORObject.DecodeFromBytes(bytes)
        @Suppress("UNCHECKED_CAST")
        val planMap = map["pl"] as CBORObject
        planMap.set("rv", CanonicalCbor.int(0L))

        val decoded = decodePayload(map.EncodeToBytes())
        assertEquals(PlanWire.TYPE_PLAN_PUBLISHED, decoded.type)
        assertNull("an invalid body declines the plan, not the event", decoded.plan)
    }

    @Test
    fun identityViolations_areDeclined() {
        val valid = PlanBody("plan-4", "Week 4", 1, 1_800_000_000_000L)
        assertNull(decodePlanOrNull(CBORObject.FromObject("not a map")))
        assertNull(decodePlanOrNull(CBORObject.NewMap()))
        assertNull(
            "blank plan id",
            decodePlanOrNull(encodePlan(valid.copy(planId = ""))),
        )
        assertNull(
            "zero revision",
            decodePlanOrNull(encodePlan(valid.copy(revision = 0))),
        )
        assertNull(
            "no timestamp",
            decodePlanOrNull(encodePlan(valid.copy(updatedAtMs = 0))),
        )
        assertNull(
            "duplicate task ids",
            decodePlanOrNull(
                encodePlan(
                    valid.copy(
                        tasks = listOf(
                            PlanTaskBody("t1", 0, "a"),
                            PlanTaskBody("t1", 1, "b"),
                        ),
                    ),
                ),
            ),
        )
        val tooMany = PlanBody(
            "plan-5", "Week 5", 1, 1_800_000_000_000L,
            tasks = (0..PlanWire.MAX_TASKS).map { PlanTaskBody("t$it", it, "line") },
        )
        assertNull("over the task cap", decodePlanOrNull(encodePlan(tooMany)))
    }

    @Test
    fun longText_isClampedNotDeclined() {
        val body = PlanBody(
            "plan-6",
            "t".repeat(PlanWire.MAX_TITLE_LENGTH + 40),
            1,
            1_800_000_000_000L,
            tasks = listOf(PlanTaskBody("t1", 0, "x".repeat(PlanWire.MAX_TASK_TEXT_LENGTH + 60))),
        )
        val decoded = decodePlanOrNull(encodePlan(body))
        assertNotNull(decoded)
        assertEquals(PlanWire.MAX_TITLE_LENGTH, decoded!!.title.length)
        assertEquals(PlanWire.MAX_TASK_TEXT_LENGTH, decoded.tasks.single().text.length)
    }
}
