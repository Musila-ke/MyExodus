package com.example.myexodus.accountability.delivery

import com.example.myexodus.accountability.serialization.CanonicalCbor
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.upokecenter.cbor.CBORObject
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §13.4 sensitive payload of a signed `RULE_CHANGE_ATTEMPT` envelope: a closed, strict codec —
 * anything it does not fully understand decodes to null instead of partially applying.
 */
class PartnerRuleChangePayloadTest {

    private val ruleId = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100")
    private val appIdentity = RuleIdentity(label = "Instagram", targetType = RuleTargetType.APP)

    @Test
    fun appIdentity_roundTrips() {
        val decoded = PartnerRuleChangePayload.decodeOrNull(
            PartnerRuleChangePayload.encode(ruleId, RuleChange.CREATED, appIdentity, 29_000_001),
        )

        assertEquals(
            PartnerRuleChangePayload.Decoded(ruleId, RuleChange.CREATED, appIdentity, 29_000_001),
            decoded,
        )
    }

    @Test
    fun batchIdentity_roundTripsWithItsItemCount() {
        val identity = RuleIdentity(label = "Social media", targetType = RuleTargetType.BATCH, itemCount = 7)

        val decoded = PartnerRuleChangePayload.decodeOrNull(
            PartnerRuleChangePayload.encode(ruleId, RuleChange.DELETED, identity, 1),
        )

        assertEquals(PartnerRuleChangePayload.Decoded(ruleId, RuleChange.DELETED, identity, 1), decoded)
    }

    @Test
    fun memberRule_roundTripsWithItsBatchLabel() {
        val identity = RuleIdentity(
            label = "TikTok",
            targetType = RuleTargetType.WEBSITE,
            batchLabel = "Social media",
        )

        val decoded = PartnerRuleChangePayload.decodeOrNull(
            PartnerRuleChangePayload.encode(ruleId, RuleChange.UPDATED, identity, 42),
        )

        assertEquals(PartnerRuleChangePayload.Decoded(ruleId, RuleChange.UPDATED, identity, 42), decoded)
    }

    @Test
    fun encodingIsCanonical_andWithinTheDeclaredBound() {
        val bytes = PartnerRuleChangePayload.encode(ruleId, RuleChange.EXPIRED, appIdentity, 42)

        assertArrayEquals(
            "deterministic CBOR: same logical payload, byte-identical wire",
            bytes,
            PartnerRuleChangePayload.encode(ruleId, RuleChange.EXPIRED, appIdentity, 42),
        )
        assertTrue(bytes.size <= PartnerRuleChangePayload.MAX_BYTES / 2)
    }

    @Test
    fun unknownKey_isRejected() {
        val bytes = CanonicalCbor.encodeMap(
            listOf(
                "v" to CanonicalCbor.int(1),
                "rid" to CanonicalCbor.text(ruleId.toString()),
                "chg" to CanonicalCbor.text("CREATED"),
                "lbl" to CanonicalCbor.text("Instagram"),
                "tgt" to CanonicalCbor.text("APP"),
                "t" to CanonicalCbor.int(42),
                "pkg" to CanonicalCbor.text("com.example.blocked"),
            ),
        )
        assertNull(PartnerRuleChangePayload.decodeOrNull(bytes))
    }

    @Test
    fun missingMandatoryField_isRejected() {
        val noTime = CanonicalCbor.encodeMap(
            listOf(
                "v" to CanonicalCbor.int(1),
                "rid" to CanonicalCbor.text(ruleId.toString()),
                "chg" to CanonicalCbor.text("CREATED"),
                "lbl" to CanonicalCbor.text("Instagram"),
                "tgt" to CanonicalCbor.text("APP"),
            ),
        )
        assertNull(PartnerRuleChangePayload.decodeOrNull(noTime))
    }

    @Test
    fun wrongVersion_orWrongType_isRejected() {
        fun payload(version: CBORObject) = CanonicalCbor.encodeMap(
            listOf(
                "v" to version,
                "rid" to CanonicalCbor.text(ruleId.toString()),
                "chg" to CanonicalCbor.text("CREATED"),
                "lbl" to CanonicalCbor.text("Instagram"),
                "tgt" to CanonicalCbor.text("APP"),
                "t" to CanonicalCbor.int(42),
            ),
        )
        assertNull(PartnerRuleChangePayload.decodeOrNull(payload(CanonicalCbor.int(2))))
        assertNull(PartnerRuleChangePayload.decodeOrNull(payload(CanonicalCbor.text("1"))))
    }

    @Test
    fun nonCanonicalRuleId_isRejected() {
        val upper = CanonicalCbor.encodeMap(
            listOf(
                "v" to CanonicalCbor.int(1),
                "rid" to CanonicalCbor.text(ruleId.toString().uppercase()),
                "chg" to CanonicalCbor.text("CREATED"),
                "lbl" to CanonicalCbor.text("Instagram"),
                "tgt" to CanonicalCbor.text("APP"),
                "t" to CanonicalCbor.int(42),
            ),
        )
        assertNull(PartnerRuleChangePayload.decodeOrNull(upper))
    }

    @Test
    fun valueOutsideTheContract_isRejected() {
        fun payload(change: String, target: String) = CanonicalCbor.encodeMap(
            listOf(
                "v" to CanonicalCbor.int(1),
                "rid" to CanonicalCbor.text(ruleId.toString()),
                "chg" to CanonicalCbor.text(change),
                "lbl" to CanonicalCbor.text("Instagram"),
                "tgt" to CanonicalCbor.text(target),
                "t" to CanonicalCbor.int(42),
            ),
        )
        assertNull(PartnerRuleChangePayload.decodeOrNull(payload("RESURRECTED", "APP")))
        assertNull(PartnerRuleChangePayload.decodeOrNull(payload("CREATED", "PHONE")))
    }

    @Test
    fun identityRuleViolations_areRejected() {
        fun payload(vararg extra: Pair<String, CBORObject>) = CanonicalCbor.encodeMap(
            listOf(
                "v" to CanonicalCbor.int(1),
                "rid" to CanonicalCbor.text(ruleId.toString()),
                "chg" to CanonicalCbor.text("CREATED"),
                "lbl" to CanonicalCbor.text("Instagram"),
                "tgt" to CanonicalCbor.text("APP"),
                "t" to CanonicalCbor.int(42),
            ) + extra.toList(),
        )
        assertNull(
            "item count belongs to batch rows only",
            PartnerRuleChangePayload.decodeOrNull(payload("cnt" to CanonicalCbor.int(3))),
        )
        assertNull(
            "an unsanitized label must not decode",
            PartnerRuleChangePayload.decodeOrNull(
                CanonicalCbor.encodeMap(
                    listOf(
                        "v" to CanonicalCbor.int(1),
                        "rid" to CanonicalCbor.text(ruleId.toString()),
                        "chg" to CanonicalCbor.text("CREATED"),
                        "lbl" to CanonicalCbor.text("line one\nline two"),
                        "tgt" to CanonicalCbor.text("APP"),
                        "t" to CanonicalCbor.int(42),
                    ),
                ),
            ),
        )
    }

    @Test
    fun nonPositiveMinute_isRejectedOnBothSides() {
        assertNull(
            PartnerRuleChangePayload.decodeOrNull(
                CanonicalCbor.encodeMap(
                    listOf(
                        "v" to CanonicalCbor.int(1),
                        "rid" to CanonicalCbor.text(ruleId.toString()),
                        "chg" to CanonicalCbor.text("CREATED"),
                        "lbl" to CanonicalCbor.text("Instagram"),
                        "tgt" to CanonicalCbor.text("APP"),
                        "t" to CanonicalCbor.int(0),
                    ),
                ),
            ),
        )
        assertTrue(
            runCatching {
                PartnerRuleChangePayload.encode(ruleId, RuleChange.CREATED, appIdentity, 0)
            }.isFailure,
        )
    }

    @Test
    fun malformedOrOversizedBytes_areRejected() {
        assertNull(PartnerRuleChangePayload.decodeOrNull(ByteArray(0)))
        assertNull(PartnerRuleChangePayload.decodeOrNull(byteArrayOf(0x7f)))
        assertNull(PartnerRuleChangePayload.decodeOrNull(ByteArray(PartnerRuleChangePayload.MAX_BYTES + 1)))
    }
}
