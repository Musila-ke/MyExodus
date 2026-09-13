package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.serialization.CanonicalCbor
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType

/**
 * §6.1 step 4 exchange material: what each device sends to the partner for mutual
 * verification — the signing certificate chain (attestation), the challenge bound to it,
 * and the agreement public key for ECDH. Transcript signatures are NOT here: the
 * initiator's rides the pair document via confirmPairing, the partner's via
 * confirmPartnerSignature (the partner signs only after the initiator's key arrives from
 * the redemption, §6.1 ordering — and the QR cannot carry the initiator's chain anyway,
 * see [PairingCodec]).
 */
data class EnrollmentPayload(
    val pairId: String,
    /** Leaf-first X.509 chain (DER); leaf carries the KeyDescription (§10). */
    val signingChain: List<ByteArray>,
    val attestationChallenge: ByteArray,
    val agreementPublicKey: ByteArray,
)

private const val K_PAIR = "p"
private const val K_CHAIN = "c"
private const val K_CHALLENGE = "h"
private const val K_AGREEMENT = "a"

fun encodeEnrollmentPayload(p: EnrollmentPayload): ByteArray {
    val chain = CBORObject.NewArray()
    for (cert in p.signingChain) chain.Add(CanonicalCbor.bytes(cert))
    return CanonicalCbor.encodeMap(
        listOf(
            K_PAIR to CanonicalCbor.text(p.pairId),
            K_CHAIN to chain,
            K_CHALLENGE to CanonicalCbor.bytes(p.attestationChallenge),
            K_AGREEMENT to CanonicalCbor.bytes(p.agreementPublicKey),
        ),
    )
}

fun decodeEnrollmentPayload(bytes: ByteArray): EnrollmentPayload {
    val obj = CanonicalCbor.decodeMap(bytes)
    val chainObj = obj[K_CHAIN]
    require(chainObj.type == CBORType.Array) { "enrollment chain is not an array" }
    val chain = (0 until chainObj.size()).map { chainObj[it].GetByteString() }
    return EnrollmentPayload(
        pairId = obj[K_PAIR].AsString(),
        signingChain = chain,
        attestationChallenge = obj[K_CHALLENGE].GetByteString(),
        agreementPublicKey = obj[K_AGREEMENT].GetByteString(),
    )
}
