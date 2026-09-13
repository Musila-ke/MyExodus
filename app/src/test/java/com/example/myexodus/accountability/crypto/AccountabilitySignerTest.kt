package com.example.myexodus.accountability.crypto

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountabilitySignerTest {

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun derSignature(kp: java.security.KeyPair, data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(kp.private)
        s.update(data)
        return s.sign()
    }

    @Test
    fun verify_acceptsLowS_signature() {
        val kp = keyPair()
        val data = "StrictMode-Accountability-V1 payload".toByteArray()
        assertTrue(AccountabilitySigner().verify(kp.public, data, EcdsaLowS.normalize(derSignature(kp, data))))
    }

    @Test
    fun verify_rejectsHighS_signature() {
        val kp = keyPair()
        val data = "StrictMode-Accountability-V1 payload".toByteArray()
        val der = EcdsaLowS.normalize(derSignature(kp, data))
        val (r, s) = EcdsaLowS.decode(der)
        val n = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
        // Flip the low-S into its high-S twin (N-S); the enforcer must reject it (§10).
        val highS = EcdsaLowS.encode(r, n.subtract(s))
        assertFalse(EcdsaLowS.isLowS(highS))
        assertFalse(AccountabilitySigner().verify(kp.public, data, highS))
    }

    @Test
    fun verify_rejectsTamperedPayload() {
        val kp = keyPair()
        val data = "StrictMode-Accountability-V1 payload".toByteArray()
        assertFalse(AccountabilitySigner().verify(kp.public, "tampered".toByteArray(), derSignature(kp, data)))
    }

    @Test
    fun verify_rejectsSignatureFromOtherKey() {
        val kp = keyPair()
        val other = keyPair()
        val data = "StrictMode-Accountability-V1 payload".toByteArray()
        assertFalse(AccountabilitySigner().verify(kp.public, data, derSignature(other, data)))
    }
}
