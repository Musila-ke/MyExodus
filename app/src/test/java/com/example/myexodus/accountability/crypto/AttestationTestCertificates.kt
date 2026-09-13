package com.example.myexodus.accountability.crypto

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Test scaffolding: EC keypairs, self-signed certs, and synthetic Android KeyDescription
 * extensions (attestationChallenge at ASN1 index 4) — the signing-key certs used by the
 * pairing protocol must carry a challenge, since [AttestationVerifier.embeddedChallengeOf]
 * re-extracts it at exchange time.
 */
object AttestationTestCertificates {

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun selfSigned(keyPair: KeyPair): X509Certificate = build(keyPair, null)

    fun selfSignedWithChallenge(keyPair: KeyPair, challenge: ByteArray): X509Certificate = build(keyPair, challenge)

    private fun build(keyPair: KeyPair, challenge: ByteArray?): X509Certificate {
        val name = X500Name("CN=test-device")
        val builder = X509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            Date(System.currentTimeMillis() - 1000),
            Date(System.currentTimeMillis() + 86_400_000),
            name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        )
        challenge?.let {
            // The KeyDescription IS the extension value (no extra octet-string layer):
            // X509Certificate.getExtensionValue already wraps the value in an OCTET STRING,
            // so a second wrapper would hide the sequence from the parser (caught in tests).
            builder.addExtension(
                ASN1ObjectIdentifier(AttestationVerifier.KEY_DESCRIPTION_OID),
                false,
                keyDescription(it),
            )
        }
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    /**
     * KeyDescription: SEQUENCE { attestationVersion, attestationSecurityLevel,
     * keymasterVersion, keymasterSecurityLevel, attestationChallenge, uniqueId,
     * softwareEnforced, teeEnforced }. The software-enforced list mirrors real keymaster
     * encodings (caught on-device 2026-08-30): purpose is [1] EXPLICIT SET OF INTEGER and
     * origin is the [702] context tag — a DER SET, not a sequence, on the wire.
     */
    private fun keyDescription(challenge: ByteArray): DERSequence = DERSequence(
        arrayOf(
            ASN1Integer(3),                    // attestationVersion
            ASN1Enumerated(1),                 // attestationSecurityLevel = TEE
            ASN1Integer(3),                    // keymasterVersion
            ASN1Enumerated(1),                 // keymasterSecurityLevel = TEE
            DEROctetString(challenge),         // attestationChallenge (index 4)
            DEROctetString(ByteArray(0)),      // uniqueId
            DERSequence(arrayOf(DERTaggedObject(true, 1, DERSet(ASN1Integer(2))), DERTaggedObject(true, 702, ASN1Integer(0)))),
            DERSequence(emptyArray()),         // teeEnforced
        ),
    )

    fun randomChallenge(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
}
