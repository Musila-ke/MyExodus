package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.AttestationTestCertificates
import com.example.myexodus.accountability.crypto.EcdsaLowS
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8 Step 3 proof-format contract. The canonical message is a cross-language byte
 * contract: functions/proof.js signs/verifies the identical string (its node suite
 * asserts the same literal) — any divergence here breaks every pairing at the function.
 */
class PairingProofTest {

    @Test
    fun canonicalMessage_isTheExactCrossLanguageByteContract() {
        val hashA = "a".repeat(64)
        val hashB = "b".repeat(64)
        val message = PairingProof.canonicalMessageOf("u1", "u2", hashA, hashB, 1)
        assertEquals(
            listOf("MyExodus-Pairing-Proof-v1", "1", "u1", "u2", hashA, hashB).joinToString("\n"),
            String(message, Charsets.UTF_8),
        )
        assertEquals("MyExodus-Pairing-Proof-v1", PairingProof.DOMAIN) // functions/proof.js PROOF_DOMAIN
        assertEquals(1, PairingProof.PROTOCOL_VERSION) // SUPPORTED_PROTOCOL_VERSIONS = [1]
    }

    @Test
    fun aProofBuiltLikeThePairingFlow_verifiesUnderTheSameRulesTheFunctionApplies() {
        val keys = AttestationTestCertificates.keyPair()
        val ownUid = "uid-a"
        val partnerUid = "uid-b"
        val transcriptHash = EnrollmentTranscript.sha256Hex("transcript".toByteArray())
        val publicKeyHash = EnrollmentTranscript.senderKeyId(keys.public.encoded)
        val message = PairingProof.canonicalMessageOf(ownUid, partnerUid, publicKeyHash, transcriptHash, 1)
        val signature = Signature.getInstance("SHA256withECDSA")
            .apply { initSign(keys.private) }
            .let { it.update(message); EcdsaLowS.normalize(it.sign()) }

        // The two rules proof.js enforces before accepting: key hash binds the SPKI, and
        // the signature verifies over the canonical message with that key.
        assertEquals(publicKeyHash, EnrollmentTranscript.senderKeyId(EnrollmentTranscript.parsePublicKey(keys.public.encoded).encoded))
        assertTrue(AccountabilitySigner().verify(keys.public, message, signature))
        assertFalse(
            "any signed field change invalidates the signature",
            AccountabilitySigner().verify(
                keys.public,
                PairingProof.canonicalMessageOf(ownUid, partnerUid, publicKeyHash, "f".repeat(64), 1),
                signature,
            ),
        )
    }
}
