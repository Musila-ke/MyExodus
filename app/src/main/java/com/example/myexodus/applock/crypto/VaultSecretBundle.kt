package com.example.myexodus.applock.crypto

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Plaintext payload sealed under [VaultEnvelope.KIND_VAULT_DEKS]: one 256-bit SQLCipher DEK
 * per vault database, length-framed. Only the rules and activity-log stores are in the v1
 * vault (inventory D1/D2); the plain accountability store is operational and never bundled.
 *
 * Callers own the byte arrays and must zero them (`Arrays.fill(dek, 0)`) once keying is done.
 */
data class VaultSecretBundle(val rulesDek: ByteArray, val activityDek: ByteArray)

object VaultSecretBundleCodec {

    const val DEK_BYTES: Int = 32

    fun encode(bundle: VaultSecretBundle): ByteArray {
        require(bundle.rulesDek.size == DEK_BYTES) { "rules DEK must be $DEK_BYTES bytes" }
        require(bundle.activityDek.size == DEK_BYTES) { "activity DEK must be $DEK_BYTES bytes" }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeShort(bundle.rulesDek.size)
            data.write(bundle.rulesDek)
            data.writeShort(bundle.activityDek.size)
            data.write(bundle.activityDek)
        }
        return out.toByteArray()
    }

    /** Strict decode; any deviation is a [VaultEnvelopeException], never a partial bundle. */
    fun decode(payload: ByteArray): VaultSecretBundle {
        if (payload.size != HEADER_BYTES + 2 * DEK_BYTES) {
            throw VaultEnvelopeException("bad secret bundle length")
        }
        DataInputStream(payload.inputStream()).use { data ->
            val rules = readDek(data)
            val activity = readDek(data)
            if (data.available() != 0) throw VaultEnvelopeException("trailing bundle bytes")
            return VaultSecretBundle(rules, activity)
        }
    }

    private const val HEADER_BYTES = 4

    private fun readDek(data: DataInputStream): ByteArray {
        val length = data.readUnsignedShort()
        if (length != DEK_BYTES) throw VaultEnvelopeException("bad DEK length $length")
        return ByteArray(length).also { data.readFully(it) }
    }
}
