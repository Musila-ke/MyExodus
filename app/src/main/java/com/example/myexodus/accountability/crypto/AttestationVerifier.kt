package com.example.myexodus.accountability.crypto

import javax.inject.Inject
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.ASN1TaggedObject
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.X509Certificate
import java.util.Arrays

/**
 * §10 Android Key Attestation verification. Verifies the signing key's certificate chain
 * (PKIX to the Google Hardware Attestation Root) and the KeyDescription extension:
 * origin GENERATED, purpose includes SIGN, securityLevel StrongBox or TrustedEnvironment.
 */
open class AttestationVerifier @Inject constructor() {

    enum class SecurityLevel { SOFTWARE, TRUSTED_ENVIRONMENT, STRONG_BOX }

    sealed interface VerificationResult {
        data class Verified(val securityLevel: SecurityLevel) : VerificationResult
        data class Rejected(val reason: String) : VerificationResult
    }

    open fun verify(chain: List<X509Certificate>, expectedChallenge: ByteArray): VerificationResult {
        if (chain.isEmpty()) return VerificationResult.Rejected("empty certificate chain")
        val leaf = chain[0]
        val keyLevel = keySecurityLevelOf(leaf)
        if (keyLevel == SecurityLevel.SOFTWARE) return VerificationResult.Rejected("key security level is SOFTWARE")

        val keyDescription = keyDescriptionOf(leaf)
            ?: return VerificationResult.Rejected("attestation extension missing or malformed")
        val challenge = challengeOf(keyDescription)
            ?: return VerificationResult.Rejected("attestation challenge missing")
        if (!Arrays.equals(challenge, expectedChallenge)) {
            return VerificationResult.Rejected("attestation challenge mismatch")
        }
        val origin = originOf(keyDescription) ?: return VerificationResult.Rejected("origin missing")
        if (origin != ORIGIN_GENERATED) return VerificationResult.Rejected("key origin is not GENERATED (was $origin)")
        val purposes = purposesOf(keyDescription)
        if (PURPOSE_SIGN !in purposes) return VerificationResult.Rejected("purpose does not include SIGN")

        if (!validateChain(chain)) return VerificationResult.Rejected("certificate chain failed PKIX validation")

        return VerificationResult.Verified(keyLevel)
    }

    companion object {
        /** Extension OID of the KeyDescription (hardware attestation extension). */
        const val KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"

        private const val ORIGIN_GENERATED = 0
        private const val PURPOSE_SIGN = 2
        private const val ATTESTATION_SECURITY_LEVEL_INDEX = 1
        private const val CHALLENGE_INDEX = 4
        private const val SOFTWARE_ENFORCED_INDEX = 6
        private const val TEE_ENFORCED_INDEX = 7
        private const val ORIGIN_TAG = 702
        private const val PURPOSE_TAG = 1

        /** Security level from the leaf's KeyDescription without full chain verification. */
        @JvmStatic
        fun keySecurityLevelOf(cert: X509Certificate): SecurityLevel {
            val desc = keyDescriptionOf(cert) ?: return SecurityLevel.SOFTWARE
            return securityLevelOf(desc, ATTESTATION_SECURITY_LEVEL_INDEX) ?: SecurityLevel.SOFTWARE
        }

        /**
         * §10: the challenge this device baked into its own signing key's KeyDescription at
         * keygen. Never persisted — re-extract from the leaf certificate when building an
         * exchange payload.
         */
        @JvmStatic
        fun embeddedChallengeOf(cert: X509Certificate): ByteArray? {
            val desc = keyDescriptionOf(cert) ?: return null
            return challengeOf(desc)
        }

        internal fun trustAnchors(): List<X509Certificate> = listOf(
            pemToCertificate(GOOGLE_ATTESTATION_ROOT_PEM),
            pemToCertificate(GOOGLE_ATTESTATION_ROOT_2_PEM),
        )

        internal fun validateChain(
            chain: List<X509Certificate>,
            anchors: List<X509Certificate> = trustAnchors(),
        ): Boolean {
            return try {
                val certFactory = CertificateFactory.getInstance("X.509")
                val path = certFactory.generateCertPath(chain)
                val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    anchors.forEachIndexed { i, cert ->
                        setCertificateEntry("trust_anchor_$i", cert)
                    }
                }
                val params = PKIXParameters(trustStore)
                params.isRevocationEnabled = false
                CertPathValidator.getInstance("PKIX").validate(path, params)
                true
            } catch (e: Exception) {
                false
            }
        }

        private fun keyDescriptionOf(cert: X509Certificate): ASN1Sequence? {
            return try {
                val extValue = cert.getExtensionValue(KEY_DESCRIPTION_OID) ?: return null
                // getExtensionValue returns a DER OCTET STRING wrapping the KeyDescription.
                val octets = ASN1OctetString.getInstance(ASN1Primitive.fromByteArray(extValue)).octets
                ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(octets))
            } catch (e: Exception) {
                null
            }
        }

        private fun securityLevelOf(desc: ASN1Sequence, index: Int): SecurityLevel? {
            val level = try {
                ASN1Enumerated.getInstance(desc.getObjectAt(index)).value.toInt()
            } catch (e: Exception) {
                return null
            }
            return when (level) {
                0 -> SecurityLevel.SOFTWARE
                1 -> SecurityLevel.TRUSTED_ENVIRONMENT
                2 -> SecurityLevel.STRONG_BOX
                else -> null
            }
        }

        private fun challengeOf(desc: ASN1Sequence): ByteArray? = try {
            ASN1OctetString.getInstance(desc.getObjectAt(CHALLENGE_INDEX)).octets
        } catch (e: Exception) {
            null
        }

        /** AuthorizationList origin: 0=GENERATED, 1=DERIVED, 2=IMPORTED, 3=UNKNOWN. */
        private fun originOf(desc: ASN1Sequence): Int? =
            taggedIntegerInEitherList(desc, ORIGIN_TAG)?.value?.toInt()

        private fun purposesOf(desc: ASN1Sequence): Set<Int> {
            val result = mutableSetOf<Int>()
            for (listIndex in listOf(SOFTWARE_ENFORCED_INDEX, TEE_ENFORCED_INDEX)) {
                val list = authorizationList(desc, listIndex) ?: continue
                for (i in 0 until list.size()) {
                    val tagged = list.getObjectAt(i) as? ASN1TaggedObject ?: continue
                    if (tagged.tagNo == PURPOSE_TAG) {
                        purposeValuesOf(tagged)?.let { result.addAll(it) }
                    }
                }
            }
            return result
        }

        /**
         * Keymaster emits the purpose tag as `[1] EXPLICIT SET OF INTEGER`; the DER parser
         * surfaces the SET as an ASN1Set, which BC's strict ASN1Sequence.getInstance(tagged,
         * true) rejects (IllegalStateException "unexpected explicit encoding", caught on the
         * X6532 2026-08-30). Read the unwrapped content and accept either collection type.
         */
        private fun purposeValuesOf(tagged: ASN1TaggedObject): List<Int>? {
            val values = mutableListOf<Int>()
            when (val content = tagged.getBaseObject()) {
                is ASN1Sequence -> for (j in 0 until content.size()) {
                    try {
                        values.add(ASN1Integer.getInstance(content.getObjectAt(j)).value.toInt())
                    } catch (e: Exception) {
                        return null
                    }
                }
                is ASN1Set -> for (j in 0 until content.size()) {
                    try {
                        values.add(ASN1Integer.getInstance(content.getObjectAt(j)).value.toInt())
                    } catch (e: Exception) {
                        return null
                    }
                }
                else -> return null
            }
            return values
        }

        private fun taggedIntegerInEitherList(desc: ASN1Sequence, tagNo: Int): ASN1Integer? {
            for (listIndex in listOf(SOFTWARE_ENFORCED_INDEX, TEE_ENFORCED_INDEX)) {
                val list = authorizationList(desc, listIndex) ?: continue
                for (i in 0 until list.size()) {
                    val tagged = list.getObjectAt(i) as? ASN1TaggedObject ?: continue
                    if (tagged.tagNo == tagNo) {
                        // Unwrap the content directly — works whether the tag is EXPLICIT or IMPLICIT.
                        return try {
                            ASN1Integer.getInstance(tagged.getBaseObject())
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }
            return null
        }

        private fun authorizationList(desc: ASN1Sequence, index: Int): ASN1Sequence? = try {
            ASN1Sequence.getInstance(desc.getObjectAt(index))
        } catch (e: Exception) {
            null
        }

        private fun pemToCertificate(pem: String): X509Certificate {
            val der = android.util.Base64.decode(
                pem.replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replace("\n", ""),
                android.util.Base64.DEFAULT,
            )
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        /**
         * Google Hardware Attestation roots pinned as PKIX anchors.
         * Root 1: serial E8FA196314D2FA18, subject serialNumber f92009e853b6b045 (2016-2026).
         * Root 2: serial 3505A0A2FDCC51E4, subject serialNumber e35d38c6897d47e8 (2018-2028);
         * added 2026-08-29 — modern devices chain to Root 2, and PKIX anchors are exempt
         * from validity checks (JDK-verified 2026-08-29), so Root 1's 2026-05-24 expiry
         * is harmless. Both verified byte-identical against two independent
         * google/android-key-attestation fixture chains.
         */
        private val GOOGLE_ATTESTATION_ROOT_PEM = """
-----BEGIN CERTIFICATE-----
MIIFYDCCA0igAwIBAgIJAOj6GWMU0voYMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNVBAUTEGY5MjAw
OWU4NTNiNmIwNDUwHhcNMTYwNTI2MTYyODUyWhcNMjYwNTI0MTYyODUyWjAbMRkwFwYDVQQFExBm
OTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHs
K7Qui8xUFmOr75gvMsd/dTEDDJdSSxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfd
nJLmN0pTy/4lj4/7tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL
/ggjnar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGqC4FSYa04
T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQoVJYnFPlXTcHYvASLu+R
hhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+OJtvsBslHZvPBKCOdT0MS+tgSOIfga+z1
Z1g7+DVagf7quvmag8jfPioyKvxnK/EgsTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgp
Zrt3i5MIlCaY504LzSRiigHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6
tUXHI/+MRPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9EaDK8
Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5UmAGMCAwEAAaOBpjCB
ozAdBgNVHQ4EFgQUNmHhAHyIBQlRi0RsR/8aTMnqTxIwHwYDVR0jBBgwFoAUNmHhAHyIBQlRi0Rs
R/8aTMnqTxIwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAYYwQAYDVR0fBDkwNzA1oDOg
MYYvaHR0cHM6Ly9hbmRyb2lkLmdvb2dsZWFwaXMuY29tL2F0dGVzdGF0aW9uL2NybC8wDQYJKoZI
hvcNAQELBQADggIBACDIw41L3KlXG0aMiS//cqrG+EShHUGo8HNsw30W1kJtjn6UBwRM6jnmiwfB
Pb8VA91chb2vssAtX2zbTvqBJ9+LBPGCdw/E53Rbf86qhxKaiAHOjpvAy5Y3m00mqC0w/Zwvju1t
wb4vhLaJ5NkUJYsUS7rmJKHHBnETLi8GFqiEsqTWpG/6ibYCv7rYDBJDcR9W62BW9jfIoBQcxUCU
JouMPH25lLNcDc1ssqvC2v7iUgI9LeoM1sNovqPmQUiG9rHli1vXxzCyaMTjwftkJLkf6724DFhu
Kug2jITV0QkXvaJWF4nUaHOTNA4uJU9WDvZLI1j83A+/xnAJUucIv/zGJ1AMH2boHqF8CY16LpsY
gBt6tKxxWH00XcyDCdW2KlBCeqbQPcsFmWyWugxdcekhYsAWyoSf818NUsZdBWBaR/OukXrNLfkQ
79IyZohZbvabO/X+MVT3rriAoKc8oE2Uws6DF+60PV7/WIPjNvXySdqspImSN78mflxDqwLqRBYk
A3I75qppLGG9rp7UCdRjxMl8ZDBld+7yvHVgt1cVzJx9xnyGCC23UaicMDSXYrB4I4WHXPGjxhZu
CuPBLTdOLU8YRvMYdEvYebWHMpvwGCF6bAx3JBpIeOQ1wDB5y0USicV3YgYGmi+NZfhA4URSh77Y
d6uuJOJENRaNVTzk
-----END CERTIFICATE-----
""".trimIndent()

        private val GOOGLE_ATTESTATION_ROOT_2_PEM = """
-----BEGIN CERTIFICATE-----
MIIFXzCCA0egAwIBAgIINQWgov3MUeQwDQYJKoZIhvcNAQELBQAwGzEZMBcGA1UEBRMQZTM1ZDM4
YzY4OTdkNDdlODAeFw0xODAzMjEwMzU1MDFaFw0yODAzMTgwMzU1MDFaMBsxGTAXBgNVBAUTEGUz
NWQzOGM2ODk3ZDQ3ZTgwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQCoQi070/6PH9BA
uJiBcTp8j5R2/Fj6kXFaSxsvUjJKRdi/FCOwUFBJfyhHiWJhga2iguIjAJuhZx5XlMj0pSY7buJi
sPqFknZhKdvfoi4C54j54D+XxCky1APVjD5uc203H+hrRlhh6x4/LTzSXWvb0YLjfOK07HvSddSR
CyKnPydI5bhyCb5QMtVKHzC4Axgx+BihwG1B4UQjOpZpXBHIFZ6EK/XBFeJX0tgrx0MCczkc1X0O
hNFKAYumCKcKyh4q2cGh7UwTRJIT4beIJVrOKDVwo3Fc50k3ICpOAc1zRGzRwupIKKGtW455KW1O
IyAtTJ4NqfIwrkQy5EJ9w/zDzjqFiDNdgTXaMtsz52jHUndfO3lzfvVdBjt2FWLRkWrFmd1tjQ6L
QQFPrSvUHo0XN5kIiOYdNlAyIwBZ51Zn/clU9he78UYAmXHdjfh04DXWgf+GGIFFdmg9tbiHwdqi
8yEcakKHL2TYNylmFJuKOkvLIS5iqEMu29OQyv+BJ1NNQtyn8o1D5f3K2a5qyoUcrL0Je2w28ByL
1JeFURO7wbzkXx9FWwH0stCs/dYq4JKJ8GxKN8aBOl/51atA1cdZAyI55B9ueAYwPevteORGDhEf
cKRaGiWJpSzIFLDmbL9uSpCAw5uqx5h1IRAX1OLi56EsVaFUedt8IqUaCkUcMQIDAQABo4GmMIGj
MB0GA1UdDgQWBBRTDkUbExXTrVzqjui8W7YUis0d6DAfBgNVHSMEGDAWgBRTDkUbExXTrVzqjui8
W7YUis0d6DAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwICBDBABgNVHR8EOTA3MDWgM6Ax
hi9odHRwczovL2FuZHJvaWQuZ29vZ2xlYXBpcy5jb20vYXR0ZXN0YXRpb24vY3JsLzANBgkqhkiG
9w0BAQsFAAOCAgEACHPElcXZEY6jItn3SnVn6gdOCvuFIrhB4J4fhxa6OlDCbxsylHHAbpyoOSJD
0R5dIBTRvz49ElfCpI9yp4l5tUOjWj2K3tMfQjhKN277jApdxbZ6MNac2u+Z3dwS+34YiiCJLVKh
VzWanXk/+9rmVnBzm7qvgan90j2SmH1oYIA3GowJL+1OWSjj6cBH1VrU7guVd7q+aCEQPhTesfbm
UkdUbM/TjUMyhf5SZ7WB8A28MnyYQcFttmGDsE1HZx4fTz8iXxjaph33alasmFUWGtG7KyzvbYUw
wUOmpPd32eT8ocx3B0Z3g+Vo2Cv2LzRRNp15FOf/Ag5HWloyIv3xrsBA2fa4xzOm3/t1Aq0ZViXL
LerKKmU/EJKo7t5gE0wtNebicNhUJaX2C+vShjfleBBwhs33a8b7dBFX/JMwU5+09N83WHRPZjCr
YuK3Uu4qANfJ+f1k+weqh6MpRxAZ6TT0OQt1XQz7rDAlq7AopfXRXI74OCNJf8d+2CPNVW8i5IHN
FjvyEz8ms1ETdw5n3uRbYIgYuCV2Pud3MCwaMTv2E0dLAqg8uJo0JYlhH7wbqidMm2ODRL5FaqWb
b2pfW0vbpCyGfyw0RQGTJAE7xVdvzfdun9HATDm6TbJFcejBdJImfwpZmDOBtDPIbsg5+QVE3lLF
6Licq13i5tIg5yQ=
-----END CERTIFICATE-----
""".trimIndent()
    }
}
