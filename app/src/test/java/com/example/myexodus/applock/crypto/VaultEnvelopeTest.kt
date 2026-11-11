package com.example.myexodus.applock.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val APP_ID = "com.example.myexodus"
private const val DB_ID = "myexodus_rules.db"

class VaultEnvelopeTest {

    // A software key lets the real VaultKeyManager seal/open path run on the JVM; the Android
    // Keystore provider and its auth-bound specs are device-verified, not unit-tested.
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val keyManager = VaultKeyManager(keyLookup = { alias ->
        if (alias == VaultKeyManager.ALIAS_V1) key else null
    })
    private val secret = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun `seal and open round-trip the secret with a generated 12-byte IV`() {
        val sealed = keyManager.seal(keyManager.encryptionCipher(), secret, APP_ID, DB_ID)
        val envelope = VaultEnvelope.parse(sealed)

        assertEquals(VaultKeyManager.KEY_VERSION, envelope.keyVersion)
        assertEquals(VaultEnvelope.KIND_RULES_DEK, envelope.kind)
        assertEquals(VaultEnvelope.IV_BYTES, envelope.iv.size)
        assertArrayEquals(secret, keyManager.open(sealed, APP_ID, DB_ID))
    }

    @Test
    fun `a fresh seal never reuses the IV`() {
        val first = VaultEnvelope.parse(keyManager.seal(keyManager.encryptionCipher(), secret, APP_ID, DB_ID))
        val second = VaultEnvelope.parse(keyManager.seal(keyManager.encryptionCipher(), secret, APP_ID, DB_ID))

        assertFalse(first.iv.contentEquals(second.iv))
    }

    @Test
    fun `tampered ciphertext fails closed`() {
        val envelope = VaultEnvelope.parse(keyManager.seal(keyManager.encryptionCipher(), secret, APP_ID, DB_ID))
        envelope.ciphertext[0] = (envelope.ciphertext[0].toInt() xor 0x01).toByte()

        assertThrows(AEADBadTagException::class.java) {
            keyManager.open(VaultEnvelope.encode(envelope), APP_ID, DB_ID)
        }
    }

    @Test
    fun `tampered tag fails closed`() {
        val envelope = VaultEnvelope.parse(keyManager.seal(keyManager.encryptionCipher(), secret, APP_ID, DB_ID))
        val last = envelope.ciphertext.lastIndex
        envelope.ciphertext[last] = (envelope.ciphertext[last].toInt() xor 0x80).toByte()

        assertThrows(AEADBadTagException::class.java) {
            keyManager.open(VaultEnvelope.encode(envelope), APP_ID, DB_ID)
        }
    }

    @Test
    fun `tampered IV fails closed`() {
        val envelope = VaultEnvelope.parse(keyManager.seal(keyManager.encryptionCipher(), secret, APP_ID, DB_ID))
        envelope.iv[0] = (envelope.iv[0].toInt() xor 0x01).toByte()

        assertThrows(AEADBadTagException::class.java) {
            keyManager.open(VaultEnvelope.encode(envelope), APP_ID, DB_ID)
        }
    }

    @Test
    fun `AAD binds the application ID and database ID`() {
        val sealed = keyManager.seal(keyManager.encryptionCipher(), secret, APP_ID, DB_ID)

        assertThrows(AEADBadTagException::class.java) {
            keyManager.open(sealed, "com.example.other", DB_ID)
        }
        assertThrows(AEADBadTagException::class.java) {
            keyManager.open(sealed, APP_ID, "other.db")
        }
    }

    @Test
    fun `canonical AAD separates every bound field with explicit lengths`() {
        val base = VaultEnvelope.canonicalAad(APP_ID, DB_ID, 1, VaultEnvelope.KIND_RULES_DEK)

        assertFalse(base.contentEquals(VaultEnvelope.canonicalAad(APP_ID, DB_ID, 2, VaultEnvelope.KIND_RULES_DEK)))
        assertFalse(base.contentEquals(VaultEnvelope.canonicalAad(APP_ID + "x", DB_ID, 1, VaultEnvelope.KIND_RULES_DEK)))
        assertFalse(base.contentEquals(VaultEnvelope.canonicalAad(APP_ID, DB_ID + "x", 1, VaultEnvelope.KIND_RULES_DEK)))
        assertFalse(base.contentEquals(VaultEnvelope.canonicalAad(APP_ID, DB_ID, 1, 2)))
        // Length prefixes make the encoding unambiguous across field boundaries.
        assertFalse(
            VaultEnvelope.canonicalAad("ab", "c", 1, 1)
                .contentEquals(VaultEnvelope.canonicalAad("a", "bc", 1, 1)),
        )
    }

    @Test
    fun `parse rejects malformed envelopes`() {
        val valid = keyManager.seal(keyManager.encryptionCipher(), secret, APP_ID, DB_ID)

        assertThrows(VaultEnvelopeException::class.java) { VaultEnvelope.parse(ByteArray(0)) }
        assertThrows(VaultEnvelopeException::class.java) {
            VaultEnvelope.parse(valid.copyOf().also { it[0] = 0 })
        }
        assertThrows(VaultEnvelopeException::class.java) {
            VaultEnvelope.parse(valid.copyOf().also { it[4] = 2 })
        }
        assertThrows(VaultEnvelopeException::class.java) {
            VaultEnvelope.parse(valid.copyOf(valid.size - 1))
        }
        assertThrows(VaultEnvelopeException::class.java) {
            VaultEnvelope.parse(valid + byteArrayOf(0x09, 0x00, 0x00))
        }
    }

    @Test
    fun `parse rejects duplicate and unknown records`() {
        assertThrows(VaultEnvelopeException::class.java) {
            VaultEnvelope.parse(
                raw(
                    record(0x01, byteArrayOf(0, 0, 0, 1)),
                    record(0x01, byteArrayOf(0, 0, 0, 1)),
                    record(0x02, byteArrayOf(1)),
                    record(0x03, ByteArray(12)),
                    record(0x04, ByteArray(32)),
                ),
            )
        }
        assertThrows(VaultEnvelopeException::class.java) {
            VaultEnvelope.parse(
                raw(
                    record(0x07, ByteArray(1)),
                    record(0x01, byteArrayOf(0, 0, 0, 1)),
                    record(0x02, byteArrayOf(1)),
                    record(0x03, ByteArray(12)),
                    record(0x04, ByteArray(32)),
                ),
            )
        }
    }

    @Test
    fun `parse rejects wrong lengths, unknown kind and unsupported key version`() {
        assertThrows(VaultEnvelopeException::class.java) {
            VaultEnvelope.parse(
                raw(
                    record(0x01, byteArrayOf(0, 0, 0, 1)),
                    record(0x02, byteArrayOf(1)),
                    record(0x03, ByteArray(11)),
                    record(0x04, ByteArray(32)),
                ),
            )
        }
        assertThrows(VaultEnvelopeException::class.java) {
            VaultEnvelope.parse(
                raw(
                    record(0x01, byteArrayOf(0, 0, 0, 1)),
                    record(0x02, byteArrayOf(3)),
                    record(0x03, ByteArray(12)),
                    record(0x04, ByteArray(32)),
                ),
            )
        }
        assertThrows(VaultEnvelopeException::class.java) {
            keyManager.open(
                raw(
                    record(0x01, byteArrayOf(0, 0, 0, 9)),
                    record(0x02, byteArrayOf(1)),
                    record(0x03, ByteArray(12)),
                    record(0x04, ByteArray(32)),
                ),
                APP_ID,
                DB_ID,
            )
        }
        assertThrows(VaultEnvelopeException::class.java) {
            VaultEnvelope.parse(
                raw(
                    record(0x01, byteArrayOf(0, 0, 0, 1)),
                    record(0x02, byteArrayOf(1)),
                    record(0x03, ByteArray(12)),
                    record(0x04, ByteArray(15)),
                ),
            )
        }
    }

    @Test
    fun `a vault DEK bundle envelope round-trips under KIND_VAULT_DEKS`() {
        val payload = ByteArray(68) { (it * 5 + 1).toByte() }

        val sealed = keyManager.seal(
            keyManager.encryptionCipher(),
            payload,
            APP_ID,
            DB_ID,
            kind = VaultEnvelope.KIND_VAULT_DEKS,
        )

        assertEquals(VaultEnvelope.KIND_VAULT_DEKS, VaultEnvelope.parse(sealed).kind)
        assertArrayEquals(payload, keyManager.open(sealed, APP_ID, DB_ID))
    }

    @Test
    fun `the kind is bound into the AAD`() {
        val sealed = keyManager.seal(keyManager.encryptionCipher(), secret, APP_ID, DB_ID)
        val swapped = VaultEnvelope.encode(
            VaultEnvelope.parse(sealed).copy(kind = VaultEnvelope.KIND_VAULT_DEKS),
        )

        assertThrows(AEADBadTagException::class.java) {
            keyManager.open(swapped, APP_ID, DB_ID)
        }
    }

    @Test
    fun `a sealed secret is exactly one SQLCipher DEK`() {
        val sealed = keyManager.seal(keyManager.encryptionCipher(), secret, APP_ID, DB_ID)

        assertEquals(32, keyManager.open(sealed, APP_ID, DB_ID).size)
        assertTrue(secret.contentEquals(keyManager.open(sealed, APP_ID, DB_ID)))
    }

    private fun record(tag: Int, payload: ByteArray): ByteArray =
        byteArrayOf(tag.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload

    private fun raw(vararg records: ByteArray): ByteArray =
        byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(), 1) +
            records.reduce { acc, next -> acc + next }
}
