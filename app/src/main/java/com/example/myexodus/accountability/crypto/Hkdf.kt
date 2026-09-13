package com.example.myexodus.accountability.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HKDF-SHA256. `javax.crypto.HKDF` does not exist in the android-37.0 stubs
 * (javap-verified 2026-08-28), so this is hand-rolled against the RFC 5869 test vectors.
 */
object Hkdf {

    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..HASH_LEN * 255) { "HKDF length must be in 1..${HASH_LEN * 255}, was $length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var counter = 1
        var offset = 0
        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - offset)
            t.copyInto(out, offset, 0, n)
            offset += n
            counter++
        }
        return out
    }

    /** Salt omitted → zero-filled hash-length salt, per RFC 5869 §2.2. */
    fun derive(ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        derive(ByteArray(HASH_LEN), ikm, info, length)

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}
