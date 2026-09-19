package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.serialization.CanonicalCbor
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import java.security.SecureRandom

/**
 * §10 pairing protocol step 1: a single-use, time-limited token the initiator renders as
 * QR. The token itself is never the ongoing credential — the exchange consumes it and the
 * pair's registered device identities take over (step 2). Fresh token per pairing attempt:
 * there is no "recover the old pairing" path (step 5).
 *
 * Fields are public data; the token's secrecy is the 256-bit [token] itself, which the
 * relay never stores — only SHA-256(token) becomes the Firestore document id, so a token
 * document is unaddressable without the token.
 */
data class PairingToken(
    /** 32 random bytes, base64url-encoded when shown in the QR. */
    val token: ByteArray,
    /** Random per-attempt transcription-binding id (NOT the durable pair id, AD-02). */
    val sessionId: String,
    /** Epoch millis; the relay refuses exchanges past this (§10 step 1 time-limited). */
    val expiresAtEpochMillis: Long,
) {
    fun isExpired(nowEpochMillis: Long): Boolean = nowEpochMillis > expiresAtEpochMillis

    companion object {
        /** §10 "time-limited" — 10 minutes; a fresh token is trivially re-issued on expiry. */
        const val TTL_MILLIS = 10 * 60 * 1000L
        const val TOKEN_BYTE_LENGTH = 32

        fun generate(nowEpochMillis: Long): PairingToken = PairingToken(
            token = ByteArray(TOKEN_BYTE_LENGTH).also { SecureRandom().nextBytes(it) },
            sessionId = EnrollmentTranscript.newSessionId(),
            expiresAtEpochMillis = nowEpochMillis + TTL_MILLIS,
        )
    }
}

private const val K_TOKEN = "tk"
private const val K_SESSION_ID = "s"
private const val K_EXPIRES = "e"

fun encodePairingToken(t: PairingToken): ByteArray = CanonicalCbor.encodeMap(
    listOf(
        K_TOKEN to CanonicalCbor.bytes(t.token),
        K_SESSION_ID to CanonicalCbor.text(t.sessionId),
        K_EXPIRES to CanonicalCbor.int(t.expiresAtEpochMillis),
    ),
)

fun decodePairingToken(bytes: ByteArray): PairingToken {
    val obj = CanonicalCbor.decodeMap(bytes)
    val token = obj[K_TOKEN]
    require(token.type == CBORType.ByteString) { "token is not a byte string" }
    val tokenBytes = token.GetByteString()
    require(tokenBytes.size == PairingToken.TOKEN_BYTE_LENGTH) { "token has wrong length" }
    return PairingToken(
        token = tokenBytes,
        sessionId = obj[K_SESSION_ID].AsString(),
        expiresAtEpochMillis = obj[K_EXPIRES].AsInt64Value(),
    )
}
