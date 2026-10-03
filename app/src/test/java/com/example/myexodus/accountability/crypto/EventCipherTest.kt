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

    // --- SOS §10.4/D5: the AAD seal used by the nudge envelope -----------------------------

    private val aad = "partner_nudge|v1|p|n|u".toByteArray(Charsets.UTF_8)

    @Test
    fun aad_roundTrip() {
        val plaintext = """{"v":1,"kind":"nudge"}""".toByteArray()
        val sealed = cipher.encryptWithAad(cek, aad, plaintext)
        assertArrayEquals(plaintext, cipher.decryptWithAad(cek, aad, sealed))
    }

    @Test
    fun aad_layout_isNonceThenCiphertext() {
        val plaintext = "abcd".toByteArray()
        val sealed = cipher.encryptWithAad(cek, aad, plaintext)
        assertEquals(12 + plaintext.size + 16, sealed.size)
    }

    @Test
    fun aad_mismatch_throwsAEADBadTag() {
        val sealed = cipher.encryptWithAad(cek, aad, "payload".toByteArray())
        assertThrows(AEADBadTagException::class.java) {
            cipher.decryptWithAad(cek, "partner_nudge|v1|p|n|X".toByteArray(), sealed)
        }
    }

    @Test
    fun aad_tamperedCiphertext_throws() {
        val sealed = cipher.encryptWithAad(cek, aad, "payload".toByteArray())
        val tampered = sealed.copyOf().also { it[sealed.size - 1] = (it[sealed.size - 1].toInt() xor 0x01).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            cipher.decryptWithAad(cek, aad, tampered)
        }
    }

    @Test
    fun aad_usesFreshRandomNoncePerSeal() {
        val first = cipher.encryptWithAad(cek, aad, "same".toByteArray())
        val second = cipher.encryptWithAad(cek, aad, "same".toByteArray())
        assertFalse("same plaintext+AAD must still produce different ciphertext (fresh nonce)", first.contentEquals(second))
        assertFalse(first.copyOf(12).contentEquals(second.copyOf(12)))
    }
}
