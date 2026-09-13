package com.example.myexodus.accountability.enrollment

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec

/**
 * §6.1 transcript-bound SAS. The transcript T binds the pair id to both agreement public
 * keys in a canonical order (min/max by encoded bytes), so both devices derive the same T
 * regardless of who initiated. SAS = Truncate(SHA256(T), 40 bits) shown as 5 decimal digits.
 */
object EnrollmentTranscript {

    const val DOMAIN = "StrictMode-V1"

    fun pubKeyBytes(publicKey: PublicKey): ByteArray = publicKey.encoded

    /** T = DOMAIN || pairId || min(PubA, PubB) || max(PubA, PubB) — order-independent. */
    fun transcript(pairId: String, pubKeyA: ByteArray, pubKeyB: ByteArray): ByteArray {
        val (min, max) = if (compareBytes(pubKeyA, pubKeyB) <= 0) pubKeyA to pubKeyB else pubKeyB to pubKeyA
        return DOMAIN.toByteArray(Charsets.UTF_8) + pairId.toByteArray(Charsets.UTF_8) + min + max
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

    fun newPairId(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** §10: the sender key id is hex SHA-256 of the signing public key's DER SPKI. */
    fun senderKeyId(signingPubKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signingPubKeyBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun parsePublicKey(der: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))

    private const val SAS_DIGIT_COUNT = 5
}
