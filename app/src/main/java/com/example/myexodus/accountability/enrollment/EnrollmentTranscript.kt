package com.example.myexodus.accountability.enrollment

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec

/**
 * §6.1 transcript-bound SAS. The transcript T binds the session id to both agreement public
 * keys in a canonical order (min/max by encoded bytes), so both devices derive the same T
 * regardless of who initiated. SAS = Truncate(SHA256(T), 40 bits) shown as 5 decimal digits.
 * The session id is the random per-attempt id from the pairing token — NOT the durable
 * [durablePairIdOf], which only exists once both uids are known (AD-02).
 */
object EnrollmentTranscript {

    const val DOMAIN = "StrictMode-V1"

    fun pubKeyBytes(publicKey: PublicKey): ByteArray = publicKey.encoded

    /** T = DOMAIN || sessionId || min(PubA, PubB) || max(PubA, PubB) — order-independent. */
    fun transcript(sessionId: String, pubKeyA: ByteArray, pubKeyB: ByteArray): ByteArray {
        val (min, max) = if (compareBytes(pubKeyA, pubKeyB) <= 0) pubKeyA to pubKeyB else pubKeyB to pubKeyA
        return DOMAIN.toByteArray(Charsets.UTF_8) + sessionId.toByteArray(Charsets.UTF_8) + min + max
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val shared = minOf(a.size, b.size)
        for (i in 0 until shared) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    /** Five decimal digits from the first 40 bits of SHA256(T). */
    fun sas(transcriptBytes: ByteArray): String = buildString(SAS_DIGIT_COUNT) {
        val digest = MessageDigest.getInstance("SHA-256").digest(transcriptBytes)
        for (i in 0 until SAS_DIGIT_COUNT) append((digest[i].toInt() and 0xFF) % 10)
    }

    fun newSessionId(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * AD-02: pairId = min(uidA, uidB) + "_" + max(uidA, uidB) — computed identically in
     * functions/index.js pairIdOf. Plain lexicographic comparison, same as the JS `<`
     * operator (Firebase uids are ASCII alphanumerics, so code-unit order is unambiguous).
     */
    fun durablePairIdOf(uidA: String, uidB: String): String {
        val (min, max) = if (uidA <= uidB) uidA to uidB else uidB to uidA
        return "${min}_${max}"
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** §10: the sender key id is hex SHA-256 of the signing public key's DER SPKI. */
    fun senderKeyId(signingPubKeyBytes: ByteArray): String = sha256Hex(signingPubKeyBytes)

    fun parsePublicKey(der: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))

    private const val SAS_DIGIT_COUNT = 5
}
