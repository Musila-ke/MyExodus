package com.example.myexodus.accountability.enrollment

import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.KeyAgreement

/**
 * ECDH shared-secret derivation via the standard [KeyAgreement] API — present in
 * android-37.0 stubs and on every Android runtime since API 1.
 */
object Ecdh {

    fun generateSharedSecret(algorithm: String, myKey: PrivateKey, partnerKey: PublicKey): ByteArray {
        val instance = KeyAgreement.getInstance(algorithm)
        instance.init(myKey)
        instance.doPhase(partnerKey, true)
        return instance.generateSecret()
    }
}
