package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.serialization.CanonicalCbor

/**
 * §10 pairing QR content: the single-use pairing token (step 1) only. The initiator's
 * enrollment exchange material (step 2 — the authenticated half) does NOT fit in a QR: a
 * real attestation chain (leaf + intermediates) makes the payload ~5 KB, over the QR v40
 * binary ceiling (2953 B) — caught on-device on the X6532 (2026-08-30). The payload rides
 * in the token document instead, read by the partner from the relay; possession of the
 * token (QR) remains the sole gate (§10 step 2). The wrapper sessionId must match the
 * token's, or the content is rejected before anything touches the relay.
 */
object PairingCodec {

    private const val K_TOKEN = "t"
    private const val K_SESSION = "s"

    fun encode(token: PairingToken): ByteArray = encodeWithSessionId(token, token.sessionId)

    /** Test seam for the sessionId-mismatch rejection (QR claiming a different session than its token). */
    internal fun encodeWithSessionId(token: PairingToken, sessionId: String): ByteArray = CanonicalCbor.encodeMap(
        listOf(
            K_TOKEN to CanonicalCbor.bytes(encodePairingToken(token)),
            K_SESSION to CanonicalCbor.text(sessionId),
        ),
    )

    data class QrContent(val token: PairingToken, val sessionId: String)

    fun decode(bytes: ByteArray): QrContent {
        val obj = CanonicalCbor.decodeMap(bytes)
        val token = decodePairingToken(obj[K_TOKEN].GetByteString())
        val sessionId = obj[K_SESSION].AsString()
        require(token.sessionId == sessionId) { "token and content disagree on session id" }
        return QrContent(token, sessionId)
    }
}
