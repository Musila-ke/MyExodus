package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.AttestationVerifier
import java.io.ByteArrayInputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * §6.1/§10 enrollment orchestration. SAS display and the BiometricPrompt are UI concerns;
 * this class owns the protocol: transcript/SAS derivation, mutual verification of the
 * partner's material (attestation chain -> origin/purpose/securityLevel + transcript
 * signature), and the CEK derivation that seals the pair.
 */
class EnrollmentFlow(
    private val signer: AccountabilitySigner,
    private val attestationVerifier: AttestationVerifier,
    private val cekDerivation: CekDerivation,
) {

    /** SAS both users compare out-of-band; identical only when both hold the same sessionId + keys. */
    fun deriveSas(
        sessionId: String,
        myAgreementPubKey: PublicKey,
        partnerAgreementPubKey: PublicKey,
    ): String {
        val t = EnrollmentTranscript.transcript(
            sessionId,
            EnrollmentTranscript.pubKeyBytes(myAgreementPubKey),
            EnrollmentTranscript.pubKeyBytes(partnerAgreementPubKey),
        )
        return EnrollmentTranscript.sas(t)
    }

    /** Updates the biometric [Signature] with the message bytes and produces the signature. */
    fun signMessage(signature: Signature, messageBytes: ByteArray): ByteArray {
        signature.update(messageBytes)
        return signer.finishSign(signature)
    }

    sealed interface PartnerVerification {
        /** Attestation verified, transcript signature valid; [signingPublicKey] is the attested key. */
        data class Verified(val signingPublicKey: PublicKey) : PartnerVerification
        data class Rejected(val reason: String) : PartnerVerification
    }

    /**
     * §10 verification of the partner's exchange material: the chain must attest (origin
     * GENERATED, purpose SIGN, StrongBox or TEE), the challenge must match, and the
     * signature must be over this session's transcript with the attested key. The signature
     * is a separate token-document field, not payload material ([EnrollmentPayload]): the
     * partner signs only after the initiator's key arrives from the token document
     * (§6.1 ordering).
     */
    fun verifyPartnerMaterial(
        expectedSessionId: String,
        payload: EnrollmentPayload,
        transcriptBytes: ByteArray,
        transcriptSignature: ByteArray,
    ): PartnerVerification {
        if (payload.sessionId != expectedSessionId) return PartnerVerification.Rejected("sessionId mismatch")
        val chain = parseChain(payload) ?: return PartnerVerification.Rejected("malformed certificate chain")
        when (val attestation = attestationVerifier.verify(chain, payload.attestationChallenge)) {
            is AttestationVerifier.VerificationResult.Rejected ->
                return PartnerVerification.Rejected("attestation: ${attestation.reason}")
            is AttestationVerifier.VerificationResult.Verified -> Unit
        }
        val signingPublicKey = chain[0].publicKey
        if (!signer.verify(signingPublicKey, transcriptBytes, transcriptSignature)) {
            return PartnerVerification.Rejected("transcript signature invalid")
        }
        return PartnerVerification.Verified(signingPublicKey)
    }

    /**
     * §10 step 2 (initiator half): attestation-only device-to-device check of the
     * initiator's payload from the token document (§3 — unchanged). The initiator cannot
     * sign before the partner's agreement key exists (§6.1 ordering); its signature is the
     * registerPairing proof, verified cryptographically by the Cloud Function (§8 Step 3).
     */
    fun verifyHardwareIdentity(payload: EnrollmentPayload): PartnerVerification {
        val chain = parseChain(payload) ?: return PartnerVerification.Rejected("malformed certificate chain")
        return when (val attestation = attestationVerifier.verify(chain, payload.attestationChallenge)) {
            is AttestationVerifier.VerificationResult.Rejected ->
                PartnerVerification.Rejected("attestation: ${attestation.reason}")
            is AttestationVerifier.VerificationResult.Verified -> PartnerVerification.Verified(chain[0].publicKey)
        }
    }

    /** §6.1 step 5: the pairwise CEK — memory only, never persisted (§5). */
    fun deriveCek(pairId: String, myAgreementKey: PrivateKey, partnerAgreementPubKey: PublicKey): ByteArray =
        cekDerivation.deriveCek(pairId, myAgreementKey, partnerAgreementPubKey)

    /** §10 step 2: the persisted long-term pairing key, domain-separated from the CEK. */
    fun derivePairingKey(cek: ByteArray): ByteArray = cekDerivation.derivePairingKey(cek)

    private fun parseChain(payload: EnrollmentPayload): List<X509Certificate>? = try {
        val factory = CertificateFactory.getInstance("X.509")
        payload.signingChain.map { factory.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
    } catch (e: Exception) {
        null
    }
}
