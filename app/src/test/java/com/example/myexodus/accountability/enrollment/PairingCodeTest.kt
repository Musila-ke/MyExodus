package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.sync.FirestoreSchema
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §12.1 typed invitation code (plan §19.4 item 1, §8.2): Crockford Base32 encode/decode,
 * normalization + alias mapping, and the mod-32 alphanumeric check character on both valid
 * and corrupted inputs — the plan's own §12 test list, verbatim.
 */
class PairingCodeTest {

    private fun bytesOf(code: String): ByteArray {
        val parsed = PairingCode.parse(code)
        assertTrue("expected a valid code, got $parsed", parsed is PairingCode.ParseResult.Valid)
        return (parsed as PairingCode.ParseResult.Valid).tokenBytes
    }

    private fun reasonOf(code: String): String {
        val parsed = PairingCode.parse(code)
        assertTrue("expected an invalid code, got $parsed", parsed is PairingCode.ParseResult.Invalid)
        return (parsed as PairingCode.ParseResult.Invalid).reason
    }

    @Test
    fun roundTrip_returnsTheIdenticalTokenBytes() {
        repeat(200) {
            val bytes = PairingCode.randomTokenBytes()
            assertArrayEquals(bytes, bytesOf(PairingCode.encode(bytes)))
        }
    }

    @Test
    fun displayShape_isThreeHyphenatedGroupsPlusCheck() {
        val code = PairingCode.encode(PairingCode.randomTokenBytes())
        assertTrue(
            "expected XXXX-XXXX-XXXX-C shape, got $code",
            code.matches(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]")),
        )
        val body = code.replace("-", "")
        assertEquals(PairingCode.CODE_CHARS, body.length)
        // Crockford alphabet only — I, L, O, U never appear in a rendered code.
        assertTrue(body.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
    }

    @Test
    fun deterministicVector_pinsAlphabetEncodingAndCheckWeights() {
        // Digits 1..12: Σ (position × value) = Σ i² = 650 ≡ 10 (mod 32) → check char 'A'.
        val parsed = PairingCode.parse("1234-5678-9ABC-A")
        assertTrue(parsed is PairingCode.ParseResult.Valid)
        assertEquals(
            "1234-5678-9ABC-A",
            PairingCode.encode((parsed as PairingCode.ParseResult.Valid).tokenBytes),
        )
    }

    @Test
    fun normalization_mapsAliasesAndStripsSeparators() {
        val bytes = PairingCode.randomTokenBytes()
        val canonical = PairingCode.encode(bytes).replace("-", "")
        // lowercase; I and L both alias 1, O aliases 0; stray hyphens and pad spaces disappear.
        for (alias in listOf('i', 'l')) {
            val noisy = "  " + canonical.lowercase().replace('1', alias).replace('0', 'o') + " "
            assertArrayEquals(bytes, bytesOf(noisy))
        }
        // Hyphens and whitespace are both group separators — anywhere, not just at the ends.
        assertArrayEquals(bytes, bytesOf(canonical.replace("", "-")))
        assertArrayEquals(bytes, bytesOf(canonical.replace("", " ")))
        assertArrayEquals(bytes, bytesOf(" \t${canonical}\n "))
    }

    @Test
    fun corruptedCheckCharacter_isRejectedOnSite() {
        // 'A' (index 10) → 'B' (11): the weighted sum is unchanged, the check mismatches.
        assertEquals("CHECK", reasonOf("1234-5678-9ABC-B"))
    }

    @Test
    fun corruptedDataCharacter_isRejectedOnSite() {
        // Position 1 carries weight 1 — every ±delta of a single digit changes the sum mod 32.
        assertEquals("CHECK", reasonOf("2234-5678-9ABC-A"))
    }

    @Test
    fun transposedAdjacentDigits_areDetected() {
        // Positions 5/6 of 1..12 swapped: Σ becomes 649 ≡ 9 (mod 32) ≠ 10.
        assertEquals("CHECK", reasonOf("1234-6578-9ABC-A"))
    }

    @Test
    fun weightedMultipleOf32_isTheDocumentedBlindSpot() {
        // Plan §8.2 decision 18 accepts this: the mod-32 weighted sum misses a +16 change
        // at position 2 (2×16 = 32 ≡ 0). '2'(2)+16 → 'J'(18) keeps check char 'A' valid —
        // bytesOf itself asserts Valid, so this pins the acceptance as deliberate.
        val original = bytesOf("1234-5678-9ABC-A")
        val corrupted = bytesOf("1J34-5678-9ABC-A")
        assertFalse(
            "expected the +16 corruption to yield different token bytes",
            original.contentEquals(corrupted),
        )
    }

    @Test
    fun wrongLengths_areRejected() {
        assertEquals("LENGTH", reasonOf(""))
        assertEquals("LENGTH", reasonOf("1234-5678-9ABC")) // missing check
        assertEquals("LENGTH", reasonOf("1234-5678-9ABC-AB"))
        assertEquals("LENGTH", reasonOf("----"))
    }

    @Test
    fun nonAlphabetCharacters_areRejected() {
        assertEquals("CHARACTER", reasonOf("1234-5678-9ABU-A")) // U is excluded
        assertEquals("CHARACTER", reasonOf("1234-5678-9AB*-A"))
        assertEquals("CHARACTER", reasonOf("1234-5678-9ABC-!")) // check char must be alphanumeric
    }

    @Test
    fun encode_refusesTokensOutsideTheTypedCodeDomain() {
        // A full-entropy 32-byte token cannot be rendered in 12 characters.
        val fullEntropy = ByteArray(PairingToken.TOKEN_BYTE_LENGTH) { it.toByte() }
        assertThrows(IllegalArgumentException::class.java) { PairingCode.encode(fullEntropy) }
        // Non-zero padding is outside the domain too.
        val padded = PairingCode.randomTokenBytes().also { it[10] = 1 }
        assertThrows(IllegalArgumentException::class.java) { PairingCode.encode(padded) }
        // So is a value with the top nibble set (more than 60 bits).
        val tooBig = PairingCode.randomTokenBytes().also { it[0] = 0x10 }
        assertThrows(IllegalArgumentException::class.java) { PairingCode.encode(tooBig) }
        assertThrows(IllegalArgumentException::class.java) { PairingCode.encode(ByteArray(16)) }
    }

    @Test
    fun generatedTokens_areAlwaysInTheTypedDomain() {
        repeat(50) {
            val token = PairingToken.generate(1_700_000_000_000L)
            assertEquals(0, token.token[0].toInt() and 0xF0)
            assertTrue(token.token.drop(8).all { it == 0.toByte() })
            PairingCode.encode(token) // must not throw — every generated invite is typeable
        }
    }

    @Test
    fun parsedCode_yieldsTheSameDocumentIdAsTheToken() {
        // The property that makes typed entry a rendering, not a second protocol.
        val token = PairingToken.generate(1_700_000_000_000L)
        val fromCode = bytesOf(PairingCode.encode(token))
        assertEquals(
            FirestoreSchema.pairingTokenDocumentId(token),
            FirestoreSchema.pairingTokenDocumentId(fromCode),
        )
    }
}
