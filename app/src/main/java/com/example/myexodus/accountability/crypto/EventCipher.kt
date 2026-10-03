package com.example.myexodus.accountability.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * §6.2.3/§6.2.4 AES-256-GCM over the pairwise CEK with deterministic nonces:
 * `nonce = HKDF-SHA256(CEK, "nonce" || sequence)[0..12]` (§5: eliminates RNG-state-loss
 * nonce reuse — nonce uniqueness is guaranteed by chain sequence monotonicity).
 * The CEK itself is derived in memory only (§5) and never persisted.
 *
 * [encryptWithAad]/[decryptWithAad] (SOS plan §10.4, Step 8 / D5) are the one additive
 * extension: a standalone seal with a fresh random nonce and caller-supplied AAD for
 * messages that are not chain events. Every method above is byte-for-byte unchanged.
 */
class EventCipher {

    fun nonce(cek: ByteArray, sequence: Long): ByteArray {
        val info = "nonce".toByteArray(Charsets.UTF_8) + ByteBuffer.allocate(8).putLong(sequence).array()
        return Hkdf.derive(cek, info, NONCE_LENGTH)
    }

    /** Encrypts [plaintext]; returns the raw GCM ciphertext (tag appended). */
    fun encrypt(cek: ByteArray, sequence: Long, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_BITS, nonce(cek, sequence)))
        return cipher.doFinal(plaintext)
    }

    /** Throws [javax.crypto.AEADBadTagException] on tampered ciphertext. */
    fun decrypt(cek: ByteArray, sequence: Long, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_BITS, nonce(cek, sequence)))
        return cipher.doFinal(ciphertext)
    }

    /**
     * SOS §10.4 (Step 8): one AES-256-GCM seal over the pair CEK with a fresh random nonce
     * and caller AAD, returned as `nonce || ciphertext` (tag appended). Used by the nudge
     * envelope, which is not a chain event and carries no sequence; the AAD binds the sealed
     * bytes to their logical message (pair, id, sender), and the version travels inside the
     * AAD string, so a tampered version fails the tag check.
     */
    fun encryptWithAad(cek: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /** Inverse of [encryptWithAad]; throws on a short buffer, AAD mismatch or tampering. */
    fun decryptWithAad(cek: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_LENGTH))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, NONCE_LENGTH, sealed.size - NONCE_LENGTH)
    }

    companion object {
        const val NONCE_LENGTH = 12
        private const val TAG_BITS = 128

        // Thread-safe; one instance avoids a fresh seed read per call.
        private val random = SecureRandom()
    }
}
