package com.example.myexodus.applock.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultSecretBundleCodecTest {

    private val rules = ByteArray(32) { 1 }
    private val activity = ByteArray(32) { 2 }

    @Test
    fun `encode and decode round-trip both DEKs`() {
        val payload = VaultSecretBundleCodec.encode(VaultSecretBundle(rules, activity))
        val decoded = VaultSecretBundleCodec.decode(payload)

        assertEquals(68, payload.size)
        assertArrayEquals(rules, decoded.rulesDek)
        assertArrayEquals(activity, decoded.activityDek)
    }

    @Test
    fun `encode refuses a DEK of the wrong size`() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultSecretBundleCodec.encode(VaultSecretBundle(ByteArray(31), activity))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultSecretBundleCodec.encode(VaultSecretBundle(rules, ByteArray(33)))
        }
    }

    @Test
    fun `decode rejects any length deviation`() {
        val valid = VaultSecretBundleCodec.encode(VaultSecretBundle(rules, activity))

        assertThrows(VaultEnvelopeException::class.java) {
            VaultSecretBundleCodec.decode(ByteArray(0))
        }
        assertThrows(VaultEnvelopeException::class.java) {
            VaultSecretBundleCodec.decode(valid.copyOf(valid.size - 1))
        }
        assertThrows(VaultEnvelopeException::class.java) {
            VaultSecretBundleCodec.decode(valid + byteArrayOf(0x00))
        }
    }

    @Test
    fun `decode rejects a mis-framed DEK length`() {
        val short = ByteArray(68)
        short[1] = 31
        val long = ByteArray(68)
        long[35] = 33

        assertThrows(VaultEnvelopeException::class.java) { VaultSecretBundleCodec.decode(short) }
        assertThrows(VaultEnvelopeException::class.java) { VaultSecretBundleCodec.decode(long) }
    }
}
