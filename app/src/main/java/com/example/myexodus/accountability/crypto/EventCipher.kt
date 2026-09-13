package com.example.myexodus.accountability.crypto

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * §6.2.3/§6.2.4 AES-256-GCM over the pairwise CEK with deterministic nonces:
 * `nonce = HKDF-SHA256(CEK, "nonce" || sequence)[0..12]` (§5: eliminates RNG-state-loss
 * nonce reuse — nonce uniqueness is guaranteed by chain sequence monotonicity).
 * The CEK itself is derived in memory only (§5) and never persisted.
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

    companion object {
        const val NONCE_LENGTH = 12
        private const val TAG_BITS = 128
    }
}
