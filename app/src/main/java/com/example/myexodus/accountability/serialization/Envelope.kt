package com.example.myexodus.accountability.serialization

import com.upokecenter.cbor.CBORObject
import java.security.MessageDigest

/**
 * §6.2.5/§6.2.6 event envelope. The unsigned envelope is canonically CBOR-encoded, hashed
 * (domain string per §10), and signed by the identity key; the signature travels alongside.
 * Field names are terse to keep animated-QR frames within the 600-byte budget (§5, §15).
 */
data class UnsignedEnvelope(
    /** Hex SHA-256 of the sender's signing public key. */
    val senderKeyId: String,
    /** Key rotation epoch; 0 for the first identity key (§7.6). */
    val epoch: Int,
    val sequence: Long,
    /** Hash of the previous event in the chain; null for the first event. */
    val previousEventHash: ByteArray?,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val createdAtEpochMillis: Long,
) {

    fun canonicalBytes(): ByteArray = encodeUnsigned(this)

    companion object {
        const val VERSION = 1
    }
}

data class SignedEnvelope(
    val unsigned: UnsignedEnvelope,
    /** DER ECDSA signature over [EnvelopeKt.eventHash] of the unsigned envelope. */
    val signature: ByteArray,
)

/** §6.2.6 + §10: SHA-256 over the domain string and the canonical unsigned envelope. */
fun eventHash(unsigned: UnsignedEnvelope): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(DOMAIN_BYTES)
    digest.update(unsigned.canonicalBytes())
    return digest.digest()
}

const val ENVELOPE_DOMAIN = "StrictMode-Accountability-V1"

private val DOMAIN_BYTES: ByteArray = ENVELOPE_DOMAIN.toByteArray(Charsets.UTF_8)

private const val K_VERSION = "v"
private const val K_SENDER_KEY_ID = "k"
private const val K_EPOCH = "e"
private const val K_SEQUENCE = "s"
private const val K_PREV_HASH = "p"
private const val K_NONCE = "n"
private const val K_CIPHERTEXT = "c"
private const val K_CREATED_AT = "t"
private const val K_SIGNATURE = "sig"

fun encodeUnsigned(e: UnsignedEnvelope): ByteArray = CanonicalCbor.encodeMap(
    listOf(
        K_VERSION to CanonicalCbor.int(UnsignedEnvelope.VERSION.toLong()),
        K_SENDER_KEY_ID to CanonicalCbor.text(e.senderKeyId),
        K_EPOCH to CanonicalCbor.int(e.epoch.toLong()),
        K_SEQUENCE to CanonicalCbor.int(e.sequence),
        K_PREV_HASH to (e.previousEventHash?.let { CanonicalCbor.bytes(it) }
            ?: CBORObject.Null),
        K_NONCE to CanonicalCbor.bytes(e.nonce),
        K_CIPHERTEXT to CanonicalCbor.bytes(e.ciphertext),
        K_CREATED_AT to CanonicalCbor.int(e.createdAtEpochMillis),
    ),
)

fun encodeSigned(e: SignedEnvelope): ByteArray = CanonicalCbor.encodeMap(
    listOf(
        K_VERSION to CanonicalCbor.int(UnsignedEnvelope.VERSION.toLong()),
        K_SENDER_KEY_ID to CanonicalCbor.text(e.unsigned.senderKeyId),
        K_EPOCH to CanonicalCbor.int(e.unsigned.epoch.toLong()),
        K_SEQUENCE to CanonicalCbor.int(e.unsigned.sequence),
        K_PREV_HASH to (e.unsigned.previousEventHash?.let { CanonicalCbor.bytes(it) }
            ?: CBORObject.Null),
        K_NONCE to CanonicalCbor.bytes(e.unsigned.nonce),
        K_CIPHERTEXT to CanonicalCbor.bytes(e.unsigned.ciphertext),
        K_CREATED_AT to CanonicalCbor.int(e.unsigned.createdAtEpochMillis),
        K_SIGNATURE to CanonicalCbor.bytes(e.signature),
    ),
)

/** Decodes a received signed envelope; throws on malformed or unsupported structure. */
fun decodeSigned(bytes: ByteArray): SignedEnvelope {
    val obj = CanonicalCbor.decodeMap(bytes)
    require(obj[K_VERSION].AsInt32() == UnsignedEnvelope.VERSION) {
        "unsupported envelope version ${obj[K_VERSION].AsInt32()}"
    }
    val unsigned = UnsignedEnvelope(
        senderKeyId = obj[K_SENDER_KEY_ID].AsString(),
        epoch = obj[K_EPOCH].AsInt32(),
        sequence = obj[K_SEQUENCE].AsInt64(),
        previousEventHash = if (obj[K_PREV_HASH].isNull) null else obj[K_PREV_HASH].GetByteString(),
        nonce = obj[K_NONCE].GetByteString(),
        ciphertext = obj[K_CIPHERTEXT].GetByteString(),
        createdAtEpochMillis = obj[K_CREATED_AT].AsInt64(),
    )
    return SignedEnvelope(unsigned, obj[K_SIGNATURE].GetByteString())
}
