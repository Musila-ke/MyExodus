package com.example.myexodus.accountability.sos

import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.serialization.CanonicalCbor
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOS plan §12.2 (Step 8): the wire envelope's exact construction — constant plaintext,
 * exact AAD string, standard base64 within the callable's ≤1024-char bound, fresh nonce per
 * seal, and a fail-closed open for every wrong-input path.
 */
class NudgeEnvelopeTest {

    private val cipher = EventCipher()
    private val cek = ByteArray(32) { (it + 1).toByte() }
    private val pairId = "pair-1"
    private val nudgeId = "nudge-1"
    private val uid = "sender-uid"

    private fun seal() = NudgeEnvelope.seal(cipher, cek, pairId, nudgeId, uid)

    @Test
    fun aad_isTheExactPlanString() {
        assertArrayEquals(
            "partner_nudge|v1|pair-1|nudge-1|sender-uid".toByteArray(Charsets.UTF_8),
            NudgeEnvelope.aad(pairId, nudgeId, uid),
        )
    }

    @Test
    fun sealOpen_roundTrip_returnsExactPlanPlaintext() {
        val opened = NudgeEnvelope.open(cipher, cek, pairId, nudgeId, uid, seal())
        assertEquals("""{"v":1,"kind":"nudge"}""", opened?.toString(Charsets.UTF_8))
    }

    @Test
    fun seal_isCanonicalStandardBase64_withinServerBound() {
        val wire = seal()
        assertTrue("must decode as standard base64", runCatching { Base64.getDecoder().decode(wire) }.isSuccess)
        assertEquals("canonical (padded, unwrapped)", wire, Base64.getEncoder().encodeToString(Base64.getDecoder().decode(wire)))
        assertTrue("server contract: ≤1024 chars", wire.length <= 1024)
        assertEquals("no URL-safe/wrapped alphabet", wire.length % 4, 0)
    }

    @Test
    fun seal_usesFreshNoncePerCall() {
        // Same key, same AAD, same plaintext — only a fresh random nonce can make these differ.
        assertFalse(seal() == seal())
    }

    @Test
    fun open_wrongPairOrNudgeOrSender_isNull() {
        val wire = seal()
        assertNull(NudgeEnvelope.open(cipher, cek, "other-pair", nudgeId, uid, wire))
        assertNull(NudgeEnvelope.open(cipher, cek, pairId, "other-nudge", uid, wire))
        assertNull(NudgeEnvelope.open(cipher, cek, pairId, nudgeId, "other-uid", wire))
    }

    @Test
    fun open_wrongKey_isNull() {
        assertNull(NudgeEnvelope.open(cipher, ByteArray(32), pairId, nudgeId, uid, seal()))
    }

    @Test
    fun open_tampered_isNull() {
        val wire = seal()
        val tampered = wire.toCharArray().also { it[20] = if (it[20] == 'A') 'B' else 'A' }.concatToString()
        assertNull(NudgeEnvelope.open(cipher, cek, pairId, nudgeId, uid, tampered))
    }

    @Test
    fun open_malformedInput_isNullNeverThrows() {
        for (bad in listOf("", "not base64!!", "AAAA", seal().substring(0, 10))) {
            assertNull("input=$bad", NudgeEnvelope.open(cipher, cek, pairId, nudgeId, uid, bad))
        }
        assertNotNull(seal())
    }

    // --- v2 message (owner mandate 2026-10-04: default + custom reach-out message) -----------

    @Test
    fun v2_roundTrip_carriesTheMessage_andV1BytesCarryNone() {
        val wire = NudgeEnvelope.seal(cipher, cek, pairId, nudgeId, uid, "I could use a check-in.")
        val opened = checkNotNull(NudgeEnvelope.open(cipher, cek, pairId, nudgeId, uid, wire))
        assertEquals("I could use a check-in.", NudgeEnvelope.messageOf(opened))

        val v1 = checkNotNull(NudgeEnvelope.open(cipher, cek, pairId, nudgeId, uid, seal()))
        assertNull("a v1 plaintext has no message", NudgeEnvelope.messageOf(v1))
    }

    @Test
    fun v2_blankOrWhitespaceText_sealsTheV1Constant() {
        for (blank in listOf(null, "", "   ", "\n\t")) {
            val opened = NudgeEnvelope.open(cipher, cek, pairId, nudgeId, uid, NudgeEnvelope.seal(cipher, cek, pairId, nudgeId, uid, blank))
            assertEquals("blank=$blank", """{"v":1,"kind":"nudge"}""", opened?.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun v2_messageIsNfcNormalized_andTrimmed() {
        // "e" + combining acute must travel composed (U+00E9), like every encrypted payload.
        val wire = NudgeEnvelope.seal(cipher, cek, pairId, nudgeId, uid, "  cafe\u0301  ")
        val opened = checkNotNull(NudgeEnvelope.open(cipher, cek, pairId, nudgeId, uid, wire))
        assertEquals("caf\u00e9", NudgeEnvelope.messageOf(opened))
    }

    @Test
    fun v2_messageIsCharCappedAtTheComposerBound() {
        val wire = NudgeEnvelope.seal(cipher, cek, pairId, nudgeId, uid, "x".repeat(400))
        val opened = checkNotNull(NudgeEnvelope.open(cipher, cek, pairId, nudgeId, uid, wire))
        assertEquals(NudgeEnvelope.MAX_TEXT_CHARS, checkNotNull(NudgeEnvelope.messageOf(opened)).length)
    }

    @Test
    fun v2_maxLengthMultibyteMessage_staysWithinTheServerBase64Bound() {
        // 280 CJK chars = 840 raw text bytes > the 700-byte budget: seal clamps by bytes so
        // the wire can never exceed the callable's ≤1024-char base64 contract.
        val wire = NudgeEnvelope.seal(cipher, cek, pairId, nudgeId, uid, "漢".repeat(280))
        assertTrue("server contract: ≤1024 chars, got ${wire.length}", wire.length <= 1024)
        val opened = checkNotNull(NudgeEnvelope.open(cipher, cek, pairId, nudgeId, uid, wire))
        assertEquals(233, checkNotNull(NudgeEnvelope.messageOf(opened)).length)
    }

    @Test
    fun v2_byteClamp_neverSplitsASurrogatePair() {
        // 233 CJK (699 bytes) + emoji: the byte budget runs out exactly between an emoji's
        // surrogate halves, so the clamp must back off to the CJK preface — never a half pair.
        val wire = NudgeEnvelope.seal(cipher, cek, pairId, nudgeId, uid, "漢".repeat(233) + "😀".repeat(10))
        assertTrue(wire.length <= 1024)
        val opened = checkNotNull(NudgeEnvelope.open(cipher, cek, pairId, nudgeId, uid, wire))
        val message = checkNotNull(NudgeEnvelope.messageOf(opened))
        assertEquals(233, message.length)
        assertEquals("no lone surrogate survives", 233, message.codePointCount(0, message.length))
    }

    @Test
    fun messageOf_hostilePlaintext_isNullNeverThrows() {
        for (bad in listOf(
            ByteArray(0),
            """{"v":1,"kind":"nudge"}""".toByteArray(Charsets.UTF_8), // the v1 JSON constant fails CBOR decode
            byteArrayOf(0x7B, 0x22), // truncated JSON
            CanonicalCbor.encodeMap(
                listOf("v" to CanonicalCbor.int(1), "kind" to CanonicalCbor.text("nudge")),
            ), // wrong version, no text
            CanonicalCbor.encodeMap(
                listOf("v" to CanonicalCbor.int(2), "kind" to CanonicalCbor.text("other"), "text" to CanonicalCbor.text("x")),
            ), // wrong kind
        )) {
            assertNull("plaintext=${bad.toList()}", NudgeEnvelope.messageOf(bad))
        }
    }

    @Test
    fun messageOf_receiverSideSanitizesHostileText() {
        val hostile = CanonicalCbor.encodeMap(
            listOf(
                "v" to CanonicalCbor.int(2),
                "kind" to CanonicalCbor.text("nudge"),
                "text" to CanonicalCbor.text("hello\u0000\u0007\nworld"),
            ),
        )
        assertEquals("control characters are stripped, text survives", "helloworld", NudgeEnvelope.messageOf(hostile))
    }
}
