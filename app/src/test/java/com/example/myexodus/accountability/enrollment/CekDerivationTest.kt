package com.example.myexodus.accountability.enrollment

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CekDerivationTest {

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val derivation = CekDerivation()
    private val pairId = "pair-test"

    @Test
    fun bothSidesDeriveTheSameCek() {
        val a = keyPair()
        val b = keyPair()
        val cekA = derivation.deriveCek(pairId, a.private, b.public)
        val cekB = derivation.deriveCek(pairId, b.private, a.public)
        assertArrayEquals(cekA, cekB)
        assertEquals(CekDerivation.CEK_LENGTH, cekA.size)
    }

    @Test
    fun differentPartnerDerivesDifferentCek() {
        val a = keyPair()
        val b = keyPair()
        val c = keyPair()
        assertFalse(
            derivation.deriveCek(pairId, a.private, b.public)
                .contentEquals(derivation.deriveCek(pairId, a.private, c.public)),
        )
    }

    @Test
    fun differentPairDerivesDifferentCek() {
        // Same agreement key pair re-paired under a fresh pairId must not reuse the CEK:
        // ciphertext from the previous pairing would otherwise be decryptable after re-pair.
        val a = keyPair()
        val b = keyPair()
        assertFalse(
            derivation.deriveCek("pair-old", a.private, b.public)
                .contentEquals(derivation.deriveCek(pairId, a.private, b.public)),
        )
    }

    @Test
    fun pairingKey_isDeterministicDomainSeparatedAndRotatable() {
        val a = keyPair()
        val b = keyPair()
        val c = keyPair()
        // Both sides derive the same pairing key from their own CEK.
        val keyA = derivation.derivePairingKey(derivation.deriveCek(pairId, a.private, b.public))
        val keyB = derivation.derivePairingKey(derivation.deriveCek(pairId, b.private, a.public))
        assertArrayEquals(keyA, keyB)
        assertEquals(CekDerivation.PAIRING_KEY_LENGTH, keyA.size)
        // Domain separation: the pairing key is never the CEK itself.
        assertFalse(keyA.contentEquals(derivation.deriveCek(pairId, a.private, b.public)))
        // A different partner produces a different pairing key.
        assertFalse(keyA.contentEquals(derivation.derivePairingKey(derivation.deriveCek(pairId, a.private, c.public))))
    }
}
