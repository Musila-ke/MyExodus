package com.example.myexodus.applock.crypto

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** A sealed blob that does not strictly match the v1 envelope format (plan §8.4). */
class VaultEnvelopeException(message: String) : Exception(message)

/**
 * Versioned AES-GCM envelope (plan §8.4). The encrypted payload is the SQLCipher DEK; the
 * Keystore-generated IV travels with it. The canonical AAD binds the application ID, database
 * ID, envelope version, key version and kind with explicit lengths, so an envelope cannot be
 * replayed across apps, databases, aliases or versions even if the ciphertext is copied.
 *
 * Format: `"MXVE"`, one format-version byte, then tagged records (tag u8, length u16,
 * big-endian) in any order: 0x01 key version (u32), 0x02 kind (u8), 0x03 IV (12 bytes),
 * 0x04 ciphertext incl. GCM tag (>= 16 bytes). Strict parse: known magic/version only, each
 * tag exactly once, exact lengths, no trailing bytes.
 */
object VaultEnvelope {

    const val FORMAT_VERSION: Int = 1

    /** Wrapped secret is the rules-store SQLCipher DEK. */
    const val KIND_RULES_DEK: Int = 1

    /**
     * Wrapped secret is the vault's SQLCipher DEK bundle (rules + activity log), so one
     * envelope costs one prompt-authorized encrypt and one prompt-authorized decrypt (§8.4).
     */
    const val KIND_VAULT_DEKS: Int = 2

    private val SUPPORTED_KINDS = setOf(KIND_RULES_DEK, KIND_VAULT_DEKS)

    const val IV_BYTES: Int = 12
    const val GCM_TAG_BITS: Int = 128

    private const val KEY_VERSION_BYTES = 4
    private const val KIND_BYTES = 1
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8

    private const val TAG_KEY_VERSION = 0x01
    private const val TAG_KIND = 0x02
    private const val TAG_IV = 0x03
    private const val TAG_CIPHERTEXT = 0x04

    private val MAGIC = byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte())
    private const val AAD_DOMAIN = "MyExodusVaultEnvelope"

    data class Envelope(
        val keyVersion: Int,
        val kind: Int,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    fun encode(envelope: Envelope): ByteArray {
        require(envelope.keyVersion > 0) { "key version must be positive" }
        require(envelope.kind in SUPPORTED_KINDS) { "unsupported kind ${envelope.kind}" }
        require(envelope.iv.size == IV_BYTES) { "IV must be $IV_BYTES bytes" }
        require(envelope.ciphertext.size >= GCM_TAG_BYTES) { "ciphertext must carry the GCM tag" }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.write(MAGIC)
            data.writeByte(FORMAT_VERSION)
            writeRecord(data, TAG_KEY_VERSION, ByteBuffer.allocate(KEY_VERSION_BYTES).putInt(envelope.keyVersion).array())
            writeRecord(data, TAG_KIND, byteArrayOf(envelope.kind.toByte()))
            writeRecord(data, TAG_IV, envelope.iv)
            writeRecord(data, TAG_CIPHERTEXT, envelope.ciphertext)
        }
        return out.toByteArray()
    }

    /** Strict parse; every rejection is a [VaultEnvelopeException], never a partial result. */
    fun parse(encoded: ByteArray): Envelope {
        if (encoded.size < MAGIC.size + 1) throw VaultEnvelopeException("truncated header")
        for (i in MAGIC.indices) {
            if (encoded[i] != MAGIC[i]) throw VaultEnvelopeException("bad magic")
        }
        val version = encoded[MAGIC.size].toInt() and 0xFF
        if (version != FORMAT_VERSION) throw VaultEnvelopeException("unsupported format version $version")

        var keyVersion: Int? = null
        var kind: Int? = null
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        var index = MAGIC.size + 1
        while (index < encoded.size) {
            if (index + 3 > encoded.size) throw VaultEnvelopeException("truncated record header")
            val tag = encoded[index].toInt() and 0xFF
            val length = ((encoded[index + 1].toInt() and 0xFF) shl 8) or (encoded[index + 2].toInt() and 0xFF)
            index += 3
            if (index + length > encoded.size) throw VaultEnvelopeException("truncated record payload")
            val payload = encoded.copyOfRange(index, index + length)
            index += length
            when (tag) {
                TAG_KEY_VERSION -> {
                    if (keyVersion != null) throw VaultEnvelopeException("duplicate key version")
                    if (length != KEY_VERSION_BYTES) throw VaultEnvelopeException("bad key version length")
                    keyVersion = ByteBuffer.wrap(payload).int
                }
                TAG_KIND -> {
                    if (kind != null) throw VaultEnvelopeException("duplicate kind")
                    if (length != KIND_BYTES) throw VaultEnvelopeException("bad kind length")
                    kind = payload[0].toInt() and 0xFF
                }
                TAG_IV -> {
                    if (iv != null) throw VaultEnvelopeException("duplicate IV")
                    if (length != IV_BYTES) throw VaultEnvelopeException("IV must be $IV_BYTES bytes")
                    iv = payload
                }
                TAG_CIPHERTEXT -> {
                    if (ciphertext != null) throw VaultEnvelopeException("duplicate ciphertext")
                    if (length < GCM_TAG_BYTES) throw VaultEnvelopeException("ciphertext must carry the GCM tag")
                    ciphertext = payload
                }
                else -> throw VaultEnvelopeException("unknown record tag $tag")
            }
        }
        val resolvedKeyVersion = keyVersion ?: throw VaultEnvelopeException("missing key version")
        val resolvedKind = kind ?: throw VaultEnvelopeException("missing kind")
        if (resolvedKeyVersion <= 0) throw VaultEnvelopeException("key version must be positive")
        if (resolvedKind !in SUPPORTED_KINDS) throw VaultEnvelopeException("unsupported kind $resolvedKind")
        return Envelope(
            keyVersion = resolvedKeyVersion,
            kind = resolvedKind,
            iv = iv ?: throw VaultEnvelopeException("missing IV"),
            ciphertext = ciphertext ?: throw VaultEnvelopeException("missing ciphertext"),
        )
    }

    /**
     * Fixed byte order (big-endian) and explicit UTF-8/length prefixes; no `.toString()`.
     * Identical input bytes are required for both encryption and decryption.
     */
    fun canonicalAad(
        applicationId: String,
        databaseId: String,
        keyVersion: Int,
        kind: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            writeSizedUtf8(data, AAD_DOMAIN)
            data.writeByte(FORMAT_VERSION)
            data.writeInt(keyVersion)
            data.writeByte(kind)
            writeSizedUtf8(data, applicationId)
            writeSizedUtf8(data, databaseId)
        }
        return out.toByteArray()
    }

    private fun writeRecord(data: DataOutputStream, tag: Int, payload: ByteArray) {
        require(payload.size <= 0xFFFF) { "record too long" }
        data.writeByte(tag)
        data.writeShort(payload.size)
        data.write(payload)
    }

    private fun writeSizedUtf8(data: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "field too long" }
        data.writeShort(bytes.size)
        data.write(bytes)
    }
}
