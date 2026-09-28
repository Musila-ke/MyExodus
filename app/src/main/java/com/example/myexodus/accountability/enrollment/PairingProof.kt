package com.example.myexodus.accountability.enrollment

/**
 * §8 Step 3 pairing proof: the signed registration statement each device submits to the
 * registerPairing Cloud Function. The function activates `/pairings/{pairId}` only when
 * both proofs arrive within its 5-minute window and their [transcriptHash] values match,
 * binding the cloud authorization record to the actual SAS transcript rather than trusting
 * two independent uid claims.
 *
 * The canonical message mirrors functions/proof.js byte-for-byte — same field order, same
 * "\n" join, same domain string. If the format changes, bump the domain in BOTH places
 * (never silently reorder).
 */
data class PairingProof(
    val ownUid: String,
    val partnerUid: String,
    /** Hex SHA-256 of own signing key's DER SPKI ([EnrollmentTranscript.senderKeyId]). */
    val publicKeyHash: String,
    /** Hex SHA-256 of the session transcript ([EnrollmentTranscript.sha256Hex]). */
    val transcriptHash: String,
    val protocolVersion: Int,
    /** Own signing key, DER SubjectPublicKeyInfo — verification is impossible without it. */
    val signingPublicKey: ByteArray,
    /** DER ECDSA (JCA) over [canonicalMessageOf] with the biometric-gated signing key. */
    val signature: ByteArray,
    /**
     * Own attestation chain, DER-encoded, leaf first ([signingPublicKey]'s certificate),
     * up to the Google hardware root. Server-side attestation verification (owner
     * directive 2026-09-27) validates it against the pinned roots and checks the leaf key
     * equals [signingPublicKey]; not part of the signed message.
     */
    val attestationChain: List<ByteArray>,
    /**
     * The challenge embedded in the leaf KeyDescription. The server re-extracts it from
     * the certificate and demands equality — a cheap tie between chain and proof.
     */
    val attestationChallenge: ByteArray,
    /**
     * §9 envelope field, not part of the signed message: the idempotency key the server
     * caches a committed result under. A retry of the same logical submission must reuse
     * the same requestId — the replay cache is what keeps a stale retry from re-executing
     * a transition (notably: from resurrecting a since-terminated pairing). Reusing one
     * with a different payload is rejected server-side (`REQUEST_ID_REUSED`).
     */
    val requestId: String,
) {

    /**
     * Equality is identity here: two proofs are never compared by value (each carries
     * fresh signature bytes), so the default array-identity equals is not a hazard.
     */
    companion object {
        const val DOMAIN = "MyExodus-Pairing-Proof-v1"
        const val PROTOCOL_VERSION = 1

        /** The exact signed bytes: DOMAIN \n version \n ownUid \n partnerUid \n publicKeyHash \n transcriptHash. */
        fun canonicalMessageOf(
            ownUid: String,
            partnerUid: String,
            publicKeyHash: String,
            transcriptHash: String,
            protocolVersion: Int,
        ): ByteArray = listOf(
            DOMAIN,
            protocolVersion.toString(),
            ownUid,
            partnerUid,
            publicKeyHash,
            transcriptHash,
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }
}
