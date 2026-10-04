package com.example.myexodus.accountability.sos

import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.serialization.CanonicalCbor
import com.example.myexodus.accountability.serialization.EventPayload
import com.upokecenter.cbor.CBORType
import java.util.Base64

/**
 * SOS plan FR-7/§10.4 (Step 8): the nudge's wire envelope. Version 1 is the plan's constant
 * plaintext `{"v":1,"kind":"nudge"}`; version 2 (owner mandate 2026-10-04) adds the optional
 * message inside the encryption — deterministic CBOR `{v:2, kind:"nudge", text:...}`, NFC-
 * normalized like every other encrypted payload (CanonicalCbor). The server still sees only
 * opaque base64: it validates length (≤1024 chars), never structure.
 *
 * Sealed under the pair CEK with AAD `partner_nudge|v1|{pairId}|{nudgeId}|{senderUid}` — the
 * same construction on both sides (sender seals, recipient opens), so versioning lives inside
 * the ciphertext and the AAD stays fixed. Mixed versions interoperate both ways: a v2
 * envelope opens under the shipped v1 receiver (it null-checks the plaintext only, as it
 * always did), and a v1 envelope has no message ([messageOf] returns null). Wire form is
 * standard base64 (`nonce || ciphertext`), matching the callable's server-side contract
 * (`functions/partner-sos-logic.js` validates base64, ≤1024 chars).
 */
object NudgeEnvelope {

    /** Exactly the plan's plaintext (§6.1 step 6) — the v1 form, and the no-message fallback. */
    private val PLAINTEXT = """{"v":1,"kind":"nudge"}""".toByteArray(Charsets.UTF_8)

    /** Compose-time cap for the message — the same bound the motivational-message composer
     *  applies (EventPayload.MAX_ACTIVITY_TEXT_LENGTH). */
    const val MAX_TEXT_CHARS = EventPayload.MAX_ACTIVITY_TEXT_LENGTH

    /** Wire-byte ceiling for the message: 1024 base64 chars = 768 raw bytes, minus the
     *  12-byte nonce, 16-byte GCM tag and CBOR overhead — 700 leaves honest headroom for
     *  multi-byte text (a 280-char CJK message is ~840 raw text bytes, so it clamps). */
    private const val MAX_TEXT_BYTES = 700

    internal fun aad(pairId: String, nudgeId: String, senderUid: String): ByteArray =
        "partner_nudge|v1|$pairId|$nudgeId|$senderUid".toByteArray(Charsets.UTF_8)

    /**
     * Seals the envelope; returns the base64 wire string [sendNudge] carries. A null/blank
     * [text] seals the v1 constant (no message exists on the wire); a non-blank one is
     * NFC-normalized, trimmed and bounded before sealing as v2.
     */
    fun seal(
        cipher: EventCipher,
        cek: ByteArray,
        pairId: String,
        nudgeId: String,
        senderUid: String,
        text: String? = null,
    ): String {
        val plaintext = boundedText(text)?.let(::v2Plaintext) ?: PLAINTEXT
        return Base64.getEncoder().encodeToString(
            cipher.encryptWithAad(cek, aad(pairId, nudgeId, senderUid), plaintext),
        )
    }

    /**
     * Opens a stored nudge envelope (Step 9 recipient side). Returns the plaintext bytes, or
     * null on any malformed/tampered/wrong-AAD input — the caller must never crash or render
     * anything from a failed open.
     */
    fun open(
        cipher: EventCipher,
        cek: ByteArray,
        pairId: String,
        nudgeId: String,
        senderUid: String,
        envelope: String,
    ): ByteArray? = runCatching {
        cipher.decryptWithAad(cek, aad(pairId, nudgeId, senderUid), Base64.getDecoder().decode(envelope))
    }.getOrNull()

    /**
     * The optional message inside an opened plaintext: the v2 text (sanitized — control
     * characters stripped, trimmed, re-bounded; the partner's client is a trust boundary),
     * or null for a v1 envelope / anything unparsable. Never throws.
     */
    fun messageOf(plaintext: ByteArray): String? = runCatching {
        val map = CanonicalCbor.decode(plaintext)
        if (map.type != CBORType.Map) return null
        if (map["v"]?.AsInt32() != 2 || map["kind"]?.AsString() != "nudge") return null
        val text = map["text"]?.AsString() ?: return null
        text.filterNot { it.isISOControl() }.trim().take(MAX_TEXT_CHARS).takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun v2Plaintext(text: String): ByteArray = CanonicalCbor.encodeMap(
        listOf(
            "v" to CanonicalCbor.int(2),
            "kind" to CanonicalCbor.text("nudge"),
            "text" to CanonicalCbor.text(text),
        ),
    )

    /** NFC-normalized, trimmed, char-capped, then byte-capped to the wire budget. */
    private fun boundedText(raw: String?): String? {
        val normalized = CanonicalCbor.nfc(raw ?: return null).trim().take(MAX_TEXT_CHARS)
        if (normalized.isEmpty()) return null
        var end = normalized.length
        while (end > 0 && normalized.substring(0, end).toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) end--
        // Never split a surrogate pair at the byte boundary.
        if (end in 1 until normalized.length && Character.isHighSurrogate(normalized[end - 1])) end--
        return normalized.take(end).ifEmpty { null }
    }
}
