package com.example.myexodus.accountability.sos

import com.example.myexodus.accountability.crypto.EventCipher
import java.util.Base64

/**
 * SOS plan FR-7/§10.4 (Step 8): the nudge's wire envelope. Constant plaintext
 * `{"v":1,"kind":"nudge"}`, sealed under the pair CEK with AAD
 * `partner_nudge|v1|{pairId}|{nudgeId}|{senderUid}` — the same construction on both sides
 * (sender seals, recipient opens at Step 9), so the two live in one object and cannot drift.
 * Wire form is standard base64 (`nonce || ciphertext`), matching the callable's server-side
 * contract (`functions/partner-sos-logic.js` validates base64, ≤1024 chars).
 */
object NudgeEnvelope {

    /** Exactly the plan's plaintext (§6.1 step 6) — no free-form content exists in a nudge. */
    private val PLAINTEXT = """{"v":1,"kind":"nudge"}""".toByteArray(Charsets.UTF_8)

    internal fun aad(pairId: String, nudgeId: String, senderUid: String): ByteArray =
        "partner_nudge|v1|$pairId|$nudgeId|$senderUid".toByteArray(Charsets.UTF_8)

    /** Seals the constant plaintext; returns the base64 wire string [sendNudge] carries. */
    fun seal(
        cipher: EventCipher,
        cek: ByteArray,
        pairId: String,
        nudgeId: String,
        senderUid: String,
    ): String = Base64.getEncoder().encodeToString(
        cipher.encryptWithAad(cek, aad(pairId, nudgeId, senderUid), PLAINTEXT),
    )

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
}
