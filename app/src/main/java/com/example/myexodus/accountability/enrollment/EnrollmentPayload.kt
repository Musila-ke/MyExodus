package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.serialization.CanonicalCbor
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType

/**
 * §6.1 step 4 exchange material: what each device sends to the partner for mutual
 * verification — the signing certificate chain (attestation), the challenge bound to it,
 * and the agreement public key for ECDH. Transcript signatures are NOT here: the partner's
 * rides the token document's response fields; the initiator's is folded into its
 * registerPairing proof instead (a proof signature over the transcript hash — the
 * function verifies it, §8 step 3).
 */
data class EnrollmentPayload(
    /** The pairing session id this material belongs to (token's, not the durable pair id). */
    val sessionId: String,
    /** Leaf-first X.509 chain (DER); leaf carries the KeyDescription (§10). */
    val signingChain: List<ByteArray>,
    val attestationChallenge: ByteArray,
    val agreementPublicKey: ByteArray,
    /** §19.5 item 3 self-chosen label, already sanitized via [PartnerLabel]; null = none. */
    val label: String? = null,
)

private const val K_SESSION = "s"
private const val K_CHAIN = "c"
private const val K_CHALLENGE = "h"
private const val K_AGREEMENT = "a"
private const val K_LABEL = "l"

fun encodeEnrollmentPayload(p: EnrollmentPayload): ByteArray {
    val chain = CBORObject.NewArray()
    for (cert in p.signingChain) chain.Add(CanonicalCbor.bytes(cert))
    val entries = mutableListOf(
        K_SESSION to CanonicalCbor.text(p.sessionId),
        K_CHAIN to chain,
        K_CHALLENGE to CanonicalCbor.bytes(p.attestationChallenge),
        K_AGREEMENT to CanonicalCbor.bytes(p.agreementPublicKey),
    )
    // Omitted when absent, so a label-less payload encodes byte-identically to the pre-label
    // format (older builds' fixture vectors stay valid bit-for-bit).
    p.label?.let { entries += K_LABEL to CanonicalCbor.text(it) }
    return CanonicalCbor.encodeMap(entries)
}

fun decodeEnrollmentPayload(bytes: ByteArray): EnrollmentPayload {
    val obj = CanonicalCbor.decodeMap(bytes)
    val chainObj = obj[K_CHAIN]
    require(chainObj.type == CBORType.Array) { "enrollment chain is not an array" }
    val chain = (0 until chainObj.size()).map { chainObj[it].GetByteString() }
    // Missing key = a pre-label sender's payload; a non-text value is malformed and dropped
    // the same way (the label is presentation only — it must never fail an enrollment).
    val label = obj[K_LABEL]?.takeIf { it.type == CBORType.TextString }?.AsString()?.let(PartnerLabel::sanitize)
    return EnrollmentPayload(
        sessionId = obj[K_SESSION].AsString(),
        signingChain = chain,
        attestationChallenge = obj[K_CHALLENGE].GetByteString(),
        agreementPublicKey = obj[K_AGREEMENT].GetByteString(),
        label = label,
    )
}
