package com.example.myexodus.accountability.enrollment

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentTranscriptTest {

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test
    fun transcript_isOrderIndependent() {
        val a = EnrollmentTranscript.pubKeyBytes(keyPair().public)
        val b = EnrollmentTranscript.pubKeyBytes(keyPair().public)
        assertEquals(
            EnrollmentTranscript.transcript("pair-1", a, b).toList(),
            EnrollmentTranscript.transcript("pair-1", b, a).toList(),
        )
    }

    @Test
    fun transcript_bindsPairIdAndKeys() {
        val a = EnrollmentTranscript.pubKeyBytes(keyPair().public)
        val b = EnrollmentTranscript.pubKeyBytes(keyPair().public)
        assertNotEquals(
            EnrollmentTranscript.transcript("pair-1", a, b).toList(),
            EnrollmentTranscript.transcript("pair-2", a, b).toList(),
        )
        assertNotEquals(
            EnrollmentTranscript.transcript("pair-1", a, b).toList(),
            EnrollmentTranscript.transcript("pair-1", b, a.plus(1)).toList(),
        )
    }

    @Test
    fun sas_isFiveDecimalDigitsAndDeterministic() {
        val a = EnrollmentTranscript.pubKeyBytes(keyPair().public)
        val b = EnrollmentTranscript.pubKeyBytes(keyPair().public)
        val t = EnrollmentTranscript.transcript("pair-1", a, b)
        val sas = EnrollmentTranscript.sas(t)
        assertEquals(5, sas.length)
        assertTrue(sas.all { it in '0'..'9' })
        assertEquals(sas, EnrollmentTranscript.sas(t))
    }

    @Test
    fun sas_changesWithTranscript() {
        val a = EnrollmentTranscript.pubKeyBytes(keyPair().public)
        val b = EnrollmentTranscript.pubKeyBytes(keyPair().public)
        assertNotEquals(
            EnrollmentTranscript.sas(EnrollmentTranscript.transcript("pair-1", a, b)),
            EnrollmentTranscript.sas(EnrollmentTranscript.transcript("pair-2", a, b)),
        )
    }

    @Test
    fun newPairId_is16HexCharsAndUnique() {
        val p1 = EnrollmentTranscript.newPairId()
        val p2 = EnrollmentTranscript.newPairId()
        assertEquals(16, p1.length)
        assertTrue(p1.matches(Regex("[0-9a-f]{16}")))
        assertNotEquals(p1, p2)
    }
}
