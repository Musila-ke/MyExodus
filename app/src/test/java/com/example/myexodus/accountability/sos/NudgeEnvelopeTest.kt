package com.example.myexodus.accountability.sos

import com.example.myexodus.accountability.crypto.EventCipher
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
}
