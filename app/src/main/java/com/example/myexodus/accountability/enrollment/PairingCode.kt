package com.example.myexodus.accountability.enrollment

import java.security.SecureRandom

/**
 * §8.2 typed invitation code (plan §19.4 item 1 — V1's pairing entry point): a Crockford
 * Base32 rendering of the pairing token — 12 data characters (60 bits) + 1 mod-32 check
 * character, displayed `XXXX-XXXX-XXXX-C`. The code is a second *rendering* of the same
 * [PairingToken], not a second protocol: decoded bytes produce the same SHA-256 token
 * document id ([com.example.myexodus.accountability.sync.FirestoreSchema.pairingTokenDocumentId]),
 * so QR and typed entry converge on the identical exchange from the first Firestore read.
 *
 * Capacity: 12 characters carry 60 bits, so tokens live in a 60-bit domain — a big-endian
 * integer right-aligned in the first 8 bytes of the 32-byte token field, 24 zero bytes of
 * padding. [PairingToken.generate] draws from that domain, so every generated invitation
 * can be typed; [encode] refuses a token outside it (only foreign/hand-crafted tokens can
 * be). The 60-bit budget is plan §17 decision 17's ruling — adequate with single-use
 * consumption and the 10-minute TTL, where the plan's rejected alternative was 40 bits —
 * not an accident of the alphabet.
 *
 * Check character: `(Σ weight_i × value_i) mod 32` over the 12 data values, mapped back
 * into the same alphabet (plan §8.2 decision 18 — not Crockford's mod-37 symbol scheme).
 * The plan pins the formula but not the weights; they are the 1-based character positions
 * 1..12 (implementation choice, ADR #97). Consequence of mod 32: a single-character
 * corruption whose weighted delta lands on a multiple of 32 is undetected (e.g. +16 at
 * position 2) — the weaker detection the plan accepted in exchange for an alphanumeric
 * check character; adjacent transpositions are always detected.
 *
 * Entry normalization is plan §8.2 — uppercase, `I`/`L`→`1`, `O`→`0`, hyphens stripped —
 * plus whitespace stripped anywhere, so a code pasted from an SMS or a chat that renders
 * spaces as group separators types in as-is. Everything else must validate or the code is
 * refused: the check character is verified locally before any network call.
 */
object PairingCode {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    const val DATA_CHARS = 12
    const val CODE_CHARS = DATA_CHARS + 1

    /** Fixed display shape. */
    const val DISPLAY_FORMAT = "XXXX-XXXX-XXXX-C"

    sealed interface ParseResult {
        /** Decoded 32-byte token value — same bytes the QR path carries. */
        data class Valid(val tokenBytes: ByteArray) : ParseResult

        /** Stable reasons for UI copy: `LENGTH`, `CHARACTER`, `CHECK`. */
        data class Invalid(val reason: String) : ParseResult
    }

    /** 60 random bits (12 Crockford characters) zero-padded into the fixed 32-byte token field. */
    fun randomTokenBytes(): ByteArray {
        val value = ByteArray(8).also { SecureRandom().nextBytes(it) }
        value[0] = (value[0].toInt() and 0x0F).toByte()
        return value + ByteArray(PairingToken.TOKEN_BYTE_LENGTH - 8)
    }

    /** Throws on a token outside the typed-code domain — a programming error, never user input. */
    fun encode(token: PairingToken): String = encode(token.token)

    fun encode(tokenBytes: ByteArray): String {
        val value = typedValueOf(tokenBytes)
            ?: throw IllegalArgumentException("token is not in the typed-code domain (60 bits, zero-padded)")
        val body = String(CharArray(DATA_CHARS) { i ->
            ALPHABET[((value shr (5 * (DATA_CHARS - 1 - i))) and 0x1FL).toInt()]
        })
        return "${body.substring(0, 4)}-${body.substring(4, 8)}-${body.substring(8, 12)}-${checkCharOf(value)}"
    }

    fun parse(raw: String): ParseResult {
        val normalized = normalize(raw)
        if (normalized.length != CODE_CHARS) return ParseResult.Invalid("LENGTH")
        var value = 0L
        for (i in 0 until DATA_CHARS) {
            val digit = ALPHABET.indexOf(normalized[i])
            if (digit < 0) return ParseResult.Invalid("CHARACTER")
            value = (value shl 5) or digit.toLong()
        }
        val checkValue = ALPHABET.indexOf(normalized[DATA_CHARS])
        if (checkValue < 0) return ParseResult.Invalid("CHARACTER")
        if (checkValue != checkCharIndex(value)) return ParseResult.Invalid("CHECK")
        return ParseResult.Valid(paddedBytesOf(value))
    }

    fun normalize(raw: String): String = raw.filterNot { it.isWhitespace() }
        .uppercase()
        .replace("-", "")
        .replace("I", "1")
        .replace("L", "1")
        .replace("O", "0")

    private fun checkCharOf(value: Long): Char = ALPHABET[checkCharIndex(value)]

    private fun checkCharIndex(value: Long): Int {
        var sum = 0
        for (i in 0 until DATA_CHARS) {
            val digit = ((value shr (5 * (DATA_CHARS - 1 - i))) and 0x1FL).toInt()
            sum += (i + 1) * digit
        }
        return sum % ALPHABET.length
    }

    private fun typedValueOf(tokenBytes: ByteArray): Long? {
        if (tokenBytes.size != PairingToken.TOKEN_BYTE_LENGTH) return null
        if ((tokenBytes[0].toInt() and 0xF0) != 0) return null
        for (i in 8 until tokenBytes.size) if (tokenBytes[i].toInt() != 0) return null
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (tokenBytes[i].toLong() and 0xFFL)
        return value
    }

    private fun paddedBytesOf(value: Long): ByteArray {
        val out = ByteArray(PairingToken.TOKEN_BYTE_LENGTH)
        for (i in 0 until 8) out[i] = (value shr (8 * (7 - i))).toByte()
        return out
    }
}
