package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.AttestationVerifier
import com.example.myexodus.accountability.crypto.EcdsaLowS
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enrollment orchestration tests. The attestation chain itself (PKIX to the Google root) is
 * exercised on-device (§12 hardware matrix); here the verifier is stubbed to exercise the
 * flow's own logic: pairId binding, chain decoding, transcript-signature verification.
 */
class EnrollmentFlowTest {

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun signWith(keyPair: KeyPair, data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(data)
        // §10 low-S enforcement: raw JCA signatures are ~50% high-S and legitimately rejected.
        return EcdsaLowS.normalize(sig.sign())
    }

    /** Self-signed cert embedding [kp]'s public key: the flow verifies the transcript signature with chain[0].publicKey. */
    private fun certStub(kp: KeyPair): X509Certificate {
        val name = X500Name("CN=partner")
        val builder = X509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            Date(System.currentTimeMillis() - 1000),
            Date(System.currentTimeMillis() + 86_400_000),
            name,
            SubjectPublicKeyInfo.getInstance(kp.public.encoded),
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun flow(attestation: AttestationVerifier.VerificationResult = AttestationVerifier.VerificationResult.Verified(AttestationVerifier.SecurityLevel.TRUSTED_ENVIRONMENT)) =
        EnrollmentFlow(
            signer = AccountabilitySigner(),
            attestationVerifier = object : AttestationVerifier() {
                override fun verify(
                    chain: List<X509Certificate>,
                    expectedChallenge: ByteArray,
                ): AttestationVerifier.VerificationResult = attestation
            },
            cekDerivation = CekDerivation(),
        )

    private val pairId = "a1b2c3d4e5f60718"
    private val partnerKeys = keyPair()
    private val myKeys = keyPair()
    private val transcriptBytes = EnrollmentTranscript.transcript(
        pairId,
        EnrollmentTranscript.pubKeyBytes(myKeys.public),
        EnrollmentTranscript.pubKeyBytes(partnerKeys.public),
    )

    private fun payload() = EnrollmentPayload(
        pairId = pairId,
        signingChain = listOf(certStub(partnerKeys).encoded),
        attestationChallenge = ByteArray(32) { 5 },
        agreementPublicKey = EnrollmentTranscript.pubKeyBytes(partnerKeys.public),
    )

    @Test
    fun sas_agreesBetweenDevices() {
        val sasA = flow().deriveSas(pairId, myKeys.public, partnerKeys.public)
        val sasB = flow().deriveSas(pairId, partnerKeys.public, myKeys.public)
        assertEquals(sasA, sasB)
        assertEquals(5, sasA.length)
    }

    @Test
    fun verifyPartnerMaterial_acceptsValidExchange() {
        val signature = signWith(partnerKeys, transcriptBytes)
        val result = flow().verifyPartnerMaterial(pairId, payload(), transcriptBytes, signature)
        assertTrue(result is EnrollmentFlow.PartnerVerification.Verified)
        assertEquals(
            partnerKeys.public.encoded.toList(),
            (result as EnrollmentFlow.PartnerVerification.Verified).signingPublicKey.encoded.toList(),
        )
    }

    @Test
    fun verifyPartnerMaterial_rejectsTamperedSignature() {
        val signature = signWith(partnerKeys, "other".toByteArray())
        assertTrue(flow().verifyPartnerMaterial(pairId, payload(), transcriptBytes, signature) is EnrollmentFlow.PartnerVerification.Rejected)
    }

    @Test
    fun verifyPartnerMaterial_rejectsPairIdMismatch() {
        val otherPair = EnrollmentTranscript.transcript(
            "deadbeefdeadbeef",
            EnrollmentTranscript.pubKeyBytes(myKeys.public),
            EnrollmentTranscript.pubKeyBytes(partnerKeys.public),
        )
        val result = flow().verifyPartnerMaterial(pairId, payload(), transcriptBytes, signWith(partnerKeys, otherPair))
        assertTrue(result is EnrollmentFlow.PartnerVerification.Rejected)
    }

    @Test
    fun verifyPartnerMaterial_rejectsFailedAttestation() {
        val result = flow(AttestationVerifier.VerificationResult.Rejected("origin is not GENERATED"))
            .verifyPartnerMaterial(pairId, payload(), transcriptBytes, signWith(partnerKeys, transcriptBytes))
        assertTrue(result is EnrollmentFlow.PartnerVerification.Rejected)
    }

    @Test
    fun verifyPartnerMaterial_rejectsMalformedChain() {
        val bad = payload().copy(signingChain = listOf(byteArrayOf(1, 2, 3)))
        val result = flow().verifyPartnerMaterial(pairId, bad, transcriptBytes, signWith(partnerKeys, transcriptBytes))
        assertTrue(result is EnrollmentFlow.PartnerVerification.Rejected)
    }

    @Test
    fun signTranscript_returnsVerifiableSignature() {
        val flow = flow()
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(myKeys.private)
        }
        val signature = flow.signTranscript(sig, transcriptBytes)
        assertTrue(AccountabilitySigner().verify(myKeys.public, transcriptBytes, signature))
    }

    @Test
    fun verifyHardwareIdentity_acceptsAttestedPayload() {
        val result = flow().verifyHardwareIdentity(payload())
        assertTrue(result is EnrollmentFlow.PartnerVerification.Verified)
        assertEquals(
            partnerKeys.public.encoded.toList(),
            (result as EnrollmentFlow.PartnerVerification.Verified).signingPublicKey.encoded.toList(),
        )
    }

    @Test
    fun verifyHardwareIdentity_rejectsMalformedChain() {
        val bad = payload().copy(signingChain = listOf(byteArrayOf(1, 2, 3)))
        assertTrue(flow().verifyHardwareIdentity(bad) is EnrollmentFlow.PartnerVerification.Rejected)
    }

    @Test
    fun verifyHardwareIdentity_rejectsFailedAttestation() {
        val result = flow(AttestationVerifier.VerificationResult.Rejected("origin is not GENERATED"))
            .verifyHardwareIdentity(payload())
        assertTrue(result is EnrollmentFlow.PartnerVerification.Rejected)
    }

    @Test
    fun verifyTranscriptSignature_acceptsOnlyTheAttestedKey() {
        val flow = flow()
        val transcript = transcriptBytes
        assertTrue(flow.verifyTranscriptSignature(partnerKeys.public, transcript, signWith(partnerKeys, transcript)))
        assertFalse(flow.verifyTranscriptSignature(partnerKeys.public, transcript, signWith(myKeys, transcript)))
    }
}
