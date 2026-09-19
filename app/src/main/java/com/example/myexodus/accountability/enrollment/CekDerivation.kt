package com.example.myexodus.accountability.enrollment

import com.example.myexodus.accountability.crypto.Hkdf
import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.PublicKey

/**
 * §6.1 step 5: CEK = HKDF-SHA256(ECDH(myAgreementKey, partnerAgreementPubKey), "StrictMode-CEK" || pairId).
 * Both devices derive the same 32-byte CEK; it exists in memory only (§5) and is never
 * persisted — restart re-derives it from the Keystore agreement key + the partner's public key.
 * The pairId in the HKDF info domain-separates each pair's CEK: the same agreement key pair
 * used across a re-pair derives a fresh CEK, so ciphertext from a previous pairing can never
 * decrypt under the new one.
 */
class CekDerivation {

    fun deriveCek(pairId: String, myAgreementKey: PrivateKey, partnerAgreementPubKey: PublicKey): ByteArray {
        val sharedSecret = Ecdh.generateSharedSecret("ECDH", myAgreementKey, partnerAgreementPubKey)
        return Hkdf.derive(sharedSecret, (INFO + pairId).toByteArray(Charsets.UTF_8), CEK_LENGTH)
    }

    /**
     * §10 step 2: the long-term pairing key is a domain-separated HKDF of the CEK. It is
     * the secret that gets persisted (vault) and stays rotatable — after agreement-key
     * rotation both sides re-derive it — while the CEK itself remains memory-only (§5).
     */
    fun derivePairingKey(cek: ByteArray): ByteArray =
        Hkdf.derive(cek, PAIRING_KEY_INFO.toByteArray(Charsets.UTF_8), PAIRING_KEY_LENGTH)

    /**
     * AD-12: the per-epoch session key `K_epoch = HKDF(CEK, "StrictMode-session-key" || epoch)`.
     * Epoch 0 IS the raw CEK — ciphertext written before rotation (and every stored envelope,
     * envelope tests, and the animated-QR budget) stays decryptable under its original key.
     * Deterministic on both sides, so the receiver derives exactly the sender's key.
     *
     * This bounds the exposure window of an individual session key; it is NOT forward secrecy —
     * the CEK can re-derive every epoch (AD-12, documented there).
     */
    fun deriveSessionKey(cek: ByteArray, keyEpoch: Int): ByteArray =
        if (keyEpoch == 0) {
            cek
        } else {
            val info = SESSION_KEY_INFO.toByteArray(Charsets.UTF_8) +
                ByteBuffer.allocate(4).putInt(keyEpoch).array()
            Hkdf.derive(cek, info, CEK_LENGTH)
        }

    companion object {
        const val INFO = "StrictMode-CEK"
        const val CEK_LENGTH = 32
        const val PAIRING_KEY_INFO = "StrictMode-pairing-key"
        const val PAIRING_KEY_LENGTH = 32
        const val SESSION_KEY_INFO = "StrictMode-session-key"
    }
}
