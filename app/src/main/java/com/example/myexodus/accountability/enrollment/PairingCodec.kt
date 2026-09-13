package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.serialization.CanonicalCbor

/**
 * §10 pairing QR content: the single-use pairing token (step 1) only. The initiator's
 * enrollment exchange material (step 2 — the authenticated half) does NOT fit in a QR: a
 * real attestation chain (leaf + intermediates) makes the payload ~5 KB, over the QR v40
 * binary ceiling (2953 B) — caught on-device on the X6532 (2026-08-30). The payload rides
 * in the token document instead, returned by the redemption transaction; possession of the
 * token (QR) remains the sole gate (§10 step 2). The wrapper pairId must match the token's,
 * or the content is rejected before anything touches the relay.
 */
object PairingCodec {

    private const val K_TOKEN = "t"
    private const val K_PAIR = "p"

    fun encode(token: PairingToken): ByteArray = encodeWithPairId(token, token.pairId)

    /** Test seam for the pairId-mismatch rejection (QR claiming a different pair than its token). */
    internal fun encodeWithPairId(token: PairingToken, pairId: String): ByteArray = CanonicalCbor.encodeMap(
        listOf(
            K_TOKEN to CanonicalCbor.bytes(encodePairingToken(token)),
            K_PAIR to CanonicalCbor.text(pairId),
        ),
    )

    data class QrContent(val token: PairingToken, val pairId: String)

    fun decode(bytes: ByteArray): QrContent {
        val obj = CanonicalCbor.decodeMap(bytes)
        val token = decodePairingToken(obj[K_TOKEN].GetByteString())
        val pairId = obj[K_PAIR].AsString()
        require(token.pairId == pairId) { "token and content disagree on pair id" }
        return QrContent(token, pairId)
    }
}
