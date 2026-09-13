package com.example.myexodus.accountability.crypto

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcdsaLowSTest {

    private val N = BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16,
    )

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun sign(keyPair: java.security.KeyPair, data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(keyPair.private)
        s.update(data)
        return s.sign()
    }

    @Test
    fun roundTrip_derSignature_isCanonical() {
        val kp = keyPair()
        val der = sign(kp, "payload".toByteArray())
        val (r, s) = EcdsaLowS.decode(der)
        assertEquals(der.toList(), EcdsaLowS.encode(r, s).toList())
    }

    @Test
    fun normalizedSignature_isLowS_andStillVerifies() {
        val kp = keyPair()
        val data = "payload".toByteArray()
        val der = sign(kp, data)
        val normalized = EcdsaLowS.normalize(der)
        assertTrue("normalized must be low-S", EcdsaLowS.isLowS(normalized))
        // Both S and N-S verify under plain ECDSA; only the enforcer rejects high-S.
        assertTrue(plainVerify(kp.public, data, normalized))
        assertTrue(plainVerify(kp.public, data, der))
        val (r2, s2) = EcdsaLowS.decode(normalized)
        assertEquals(normalized.toList(), EcdsaLowS.encode(r2, s2).toList())
    }

    @Test
    fun highS_variant_isRejectedByIsLowS() {
        val kp = keyPair()
        val data = "payload".toByteArray()
        val der = sign(kp, data)
        // Normalize first: roughly half of raw signatures are already high-S, in which case
        // N - s is low-S again. The flip is only deterministic from a low-S input.
        val (r, s) = EcdsaLowS.decode(EcdsaLowS.normalize(der))
        val highS = EcdsaLowS.encode(r, N.subtract(s))
        assertFalse("S must be > N/2", EcdsaLowS.isLowS(highS))
        // Round-trip stays canonical.
        val (r2, s2) = EcdsaLowS.decode(highS)
        assertEquals(highS.toList(), EcdsaLowS.encode(r2, s2).toList())
    }

    @Test
    fun isLowS_acceptsTheLowS_TwinOfAHighS() {
        val kp = keyPair()
        val data = "payload".toByteArray()
        val der = sign(kp, data)
        val (r, s) = EcdsaLowS.decode(der)
        val highS = EcdsaLowS.encode(r, N.subtract(s))
        assertTrue(EcdsaLowS.isLowS(EcdsaLowS.normalize(highS)))
    }

    @Test
    fun normalize_alwaysYieldsLowS() {
        val kp = keyPair()
        val data = "payload".toByteArray()
        for (i in 0 until 20) {
            val der = sign(kp, "$data$i".toByteArray())
            assertTrue(EcdsaLowS.isLowS(EcdsaLowS.normalize(der)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_rejectsEmpty() {
        EcdsaLowS.decode(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_rejectsGarbage() {
        EcdsaLowS.decode(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_rejectsTrailingBytes() {
        val kp = keyPair()
        val der = sign(kp, "x".toByteArray())
        EcdsaLowS.decode(der + 0x00)
    }

    private fun plainVerify(publicKey: java.security.PublicKey, data: ByteArray, der: ByteArray): Boolean {
        val v = Signature.getInstance("SHA256withECDSA")
        v.initVerify(publicKey)
        v.update(data)
        return v.verify(der)
    }
}
