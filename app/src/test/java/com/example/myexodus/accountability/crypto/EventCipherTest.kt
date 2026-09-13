package com.example.myexodus.accountability.crypto

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCipherTest {

    private val cek = ByteArray(32) { it.toByte() }
    private val cipher = EventCipher()

    @Test
    fun roundTrip_encryptDecrypt() {
        val plaintext = "secret payload".toByteArray()
        val ct = cipher.encrypt(cek, 5, plaintext)
        assertFalse(ct.contentEquals(plaintext))
        assertArrayEquals(plaintext, cipher.decrypt(cek, 5, ct))
    }

    @Test
    fun nonce_isDeterministicPerSequence() {
        assertArrayEquals(cipher.nonce(cek, 7), cipher.nonce(cek, 7))
        // Different sequences give different nonces (chain monotonicity => no reuse, §5).
        assertTrue(!cipher.nonce(cek, 7).contentEquals(cipher.nonce(cek, 8)))
        assertTrue(!cipher.nonce(cek, 7).contentEquals(cipher.nonce(ByteArray(32), 7)))
    }

    @Test
    fun nonce_hasExpectedLength() {
        assertEquals(12, cipher.nonce(cek, 1).size)
    }

    @Test
    fun tamperedCiphertext_throwsAEADBadTag() {
        val ct = cipher.encrypt(cek, 1, "payload".toByteArray())
        val tampered = ct.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            cipher.decrypt(cek, 1, tampered)
        }
    }

    @Test
    fun wrongSequence_throws() {
        val ct = cipher.encrypt(cek, 1, "payload".toByteArray())
        assertThrows(AEADBadTagException::class.java) {
            cipher.decrypt(cek, 2, ct)
        }
    }

    @Test
    fun wrongKey_throws() {
        val ct = cipher.encrypt(cek, 1, "payload".toByteArray())
        assertThrows(AEADBadTagException::class.java) {
            cipher.decrypt(ByteArray(32), 1, ct)
        }
    }
}
