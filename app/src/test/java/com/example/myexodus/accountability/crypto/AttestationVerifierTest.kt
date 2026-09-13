package com.example.myexodus.accountability.crypto

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PKIX path validation of the attestation verifier — the real gate that was previously
 * untested (unit tests mocked AttestationVerifier). Uses a synthetic root→int→leaf chain
 * (openssl-generated fixtures; Google's own fixtures are synthetic for extension parsing
 * only and never passed PKIX). Also pins both Google attestation roots in the trust store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttestationVerifierTest {

    private fun loadCert(name: String): X509Certificate {
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("attestation/$name")) {
            "missing test fixture attestation/$name"
        }.readBytes()
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    @Test
    fun validChainFromSyntheticFixturesPassesPkix() {
        // Anchors are injected so the PKIX code path is exercised without a real
        // device-issued chain (which would terminate at Google's roots).
        val chain = listOf(loadCert("leaf.pem"), loadCert("int.pem"), loadCert("root.pem"))
        assertTrue(AttestationVerifier.validateChain(chain, anchors = listOf(loadCert("root.pem"))))
    }

    @Test
    fun chainToUntrustedRootIsRejected() {
        val chain = listOf(loadCert("leaf.pem"), loadCert("int.pem"), loadCert("wrong.pem"))
        assertFalse(AttestationVerifier.validateChain(chain, anchors = listOf(loadCert("root.pem"))))
    }

    @Test
    fun bothGoogleAttestationRootsAreTrusted() {
        val anchors = AttestationVerifier.trustAnchors()
        assertEquals(2, anchors.size)
        val serials = anchors.map { it.serialNumber }.toSet()
        // Root 1 serial E8FA196314D2FA18; Root 2 serial 3505A0A2FDCC51E4 (openssl -serial).
        assertEquals(
            setOf(BigInteger("E8FA196314D2FA18", 16), BigInteger("3505A0A2FDCC51E4", 16)),
            serials,
        )
    }

    @Test
    fun embeddedChallengeOf_returnsNullWithoutKeyDescription() {
        assertNull(AttestationVerifier.embeddedChallengeOf(loadCert("leaf.pem")))
    }

    @Test
    fun embeddedChallengeOf_extractsTheChallengeBakedAtKeygen() {
        val keyPair = AttestationTestCertificates.keyPair()
        val challenge = AttestationTestCertificates.randomChallenge()
        val cert = AttestationTestCertificates.selfSignedWithChallenge(keyPair, challenge)
        assertArrayEquals(challenge, AttestationVerifier.embeddedChallengeOf(cert))
    }

    @Test
    fun purposeEncodedAsSetOf_isParsedWithoutCrashing() {
        // Keymaster emits purpose as [1] EXPLICIT SET OF INTEGER; the DER parser returns the
        // SET as an ASN1Set, and BC's strict ASN1Sequence.getInstance(tagged, true) throws
        // IllegalStateException("unexpected explicit encoding") — caught on the X6532 (2026-08-30).
        val keyPair = AttestationTestCertificates.keyPair()
        val challenge = AttestationTestCertificates.randomChallenge()
        val cert = AttestationTestCertificates.selfSignedWithChallenge(keyPair, challenge)

        val result = AttestationVerifier().verify(listOf(cert), challenge)

        // Purpose/origin parse succeeded: the only rejection left is the self-signed chain
        // failing PKIX — the old code crashed with the encoding exception before reaching it.
        val rejected = result as? AttestationVerifier.VerificationResult.Rejected
        assertTrue(rejected != null)
        assertEquals("certificate chain failed PKIX validation", checkNotNull(rejected).reason)
    }
}
