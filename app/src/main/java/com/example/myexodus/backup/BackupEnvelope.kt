package com.example.myexodus.backup

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Account plan §6.4 item 9 / §8.5: the fixed v1 binary envelope and its exact AAD byte layouts.
 * All integers are unsigned big-endian. Parsing is strict — unknown magic/version, a declared
 * length outside the §2.1 item 15 caps, truncation and trailing bytes are rejected before any
 * decryption state is created. The whole-object SHA-256 is deliberately not part of the object;
 * it lives only in the Firestore manifest.
 */
object BackupEnvelope {

    /** ASCII `MYEXBK01`. */
    val MAGIC = byteArrayOf(0x4D, 0x59, 0x45, 0x58, 0x42, 0x4B, 0x30, 0x31)

    const val VERSION = 1
    const val BACKUP_ID_BYTES = 16
    const val KEY_ID_BYTES = 16
    const val NONCE_BYTES = 12
    const val DEK_BYTES = 32
    /** 32-byte wrapped key plus the 16-byte AES-GCM tag. */
    const val WRAP_BYTES = 48
    const val TAG_BYTES = 16

    const val HEADER_BYTES =
        8 + 2 + 4 + BACKUP_ID_BYTES + KEY_ID_BYTES + NONCE_BYTES + NONCE_BYTES + WRAP_BYTES + 8

    /** §2.1 item 15: compressed plaintext, uncompressed serialized content and object caps. */
    const val MAX_COMPRESSED_BYTES = 20L * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024
    const val MAX_OBJECT_BYTES = 21L * 1024 * 1024

    /** §8.5 prefixes, each including the trailing NUL. */
    const val PAYLOAD_AAD_PREFIX = "MYEXODUS-BACKUP-PAYLOAD-V1\u0000"
    const val DEK_WRAP_AAD_PREFIX = "MYEXODUS-BACKUP-DEK-WRAP-V1\u0000"

    data class Header(
        val envelopeVersion: Int,
        val schemaVersion: Int,
        val backupId: ByteArray,
        val keyId: ByteArray,
        val payloadNonce: ByteArray,
        val dekWrapNonce: ByteArray,
        val wrappedDek: ByteArray,
        val payloadCiphertextLength: Long,
    ) {
        /** GCM tag included in the declared ciphertext length (§6.4 item 9). */
        val payloadPlaintextLength: Long get() = payloadCiphertextLength - TAG_BYTES
        val objectBytes: Long get() = HEADER_BYTES + payloadCiphertextLength
    }

    enum class Reason {
        BAD_MAGIC,
        UNSUPPORTED_VERSION,
        INVALID_LENGTH,
        TRUNCATED,
    }

    sealed interface ParseResult {
        data class Valid(val header: Header) : ParseResult
        data class Invalid(val reason: Reason) : ParseResult
    }

    fun payloadAad(
        uid: String,
        backupId: ByteArray,
        schemaVersion: Int,
        envelopeVersion: Int = VERSION,
    ): ByteArray {
        require(backupId.size == BACKUP_ID_BYTES) { "backup ID must be $BACKUP_ID_BYTES bytes" }
        return aad(
            prefix = PAYLOAD_AAD_PREFIX,
            uid = uid,
            parts = listOf(backupId, uint32(schemaVersion), uint16(envelopeVersion)),
        )
    }

    fun dekWrapAad(
        uid: String,
        backupId: ByteArray,
        keyId: ByteArray,
        envelopeVersion: Int = VERSION,
    ): ByteArray {
        require(backupId.size == BACKUP_ID_BYTES) { "backup ID must be $BACKUP_ID_BYTES bytes" }
        require(keyId.size == KEY_ID_BYTES) { "key ID must be $KEY_ID_BYTES bytes" }
        return aad(
            prefix = DEK_WRAP_AAD_PREFIX,
            uid = uid,
            parts = listOf(backupId, keyId, uint16(envelopeVersion)),
        )
    }

    internal fun writeHeader(out: OutputStream, header: Header) {
        require(header.backupId.size == BACKUP_ID_BYTES)
        require(header.keyId.size == KEY_ID_BYTES)
        require(header.payloadNonce.size == NONCE_BYTES)
        require(header.dekWrapNonce.size == NONCE_BYTES)
        require(header.wrappedDek.size == WRAP_BYTES)
        require(header.payloadCiphertextLength in TAG_BYTES.toLong()..(MAX_COMPRESSED_BYTES + TAG_BYTES))
        val data = DataOutputStream(out)
        data.write(MAGIC)
        data.writeShort(header.envelopeVersion)
        data.writeInt(header.schemaVersion)
        data.write(header.backupId)
        data.write(header.keyId)
        data.write(header.payloadNonce)
        data.write(header.dekWrapNonce)
        data.write(header.wrappedDek)
        data.writeLong(header.payloadCiphertextLength)
        data.flush()
    }

    /** Reads exactly one header; a short read is [Reason.TRUNCATED], never a partial header. */
    fun readHeader(input: InputStream): ParseResult {
        val bytes = ByteArray(HEADER_BYTES)
        var read = 0
        while (read < HEADER_BYTES) {
            val n = input.read(bytes, read, HEADER_BYTES - read)
            if (n < 0) return ParseResult.Invalid(Reason.TRUNCATED)
            read += n
        }
        return parseHeader(bytes)
    }

    fun parseHeader(bytes: ByteArray): ParseResult {
        if (bytes.size != HEADER_BYTES) return ParseResult.Invalid(Reason.INVALID_LENGTH)
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return ParseResult.Invalid(Reason.BAD_MAGIC)
        }
        if (u16(bytes, 8) != VERSION) return ParseResult.Invalid(Reason.UNSUPPORTED_VERSION)
        var offset = 10
        val schemaVersion = u32(bytes, offset)
        offset += 4
        val backupId = bytes.copyOfRange(offset, offset + BACKUP_ID_BYTES)
        offset += BACKUP_ID_BYTES
        val keyId = bytes.copyOfRange(offset, offset + KEY_ID_BYTES)
        offset += KEY_ID_BYTES
        val payloadNonce = bytes.copyOfRange(offset, offset + NONCE_BYTES)
        offset += NONCE_BYTES
        val dekWrapNonce = bytes.copyOfRange(offset, offset + NONCE_BYTES)
        offset += NONCE_BYTES
        val wrappedDek = bytes.copyOfRange(offset, offset + WRAP_BYTES)
        offset += WRAP_BYTES
        val length = u64(bytes, offset) ?: return ParseResult.Invalid(Reason.INVALID_LENGTH)
        if (length < TAG_BYTES || length > MAX_COMPRESSED_BYTES + TAG_BYTES) {
            return ParseResult.Invalid(Reason.INVALID_LENGTH)
        }
        if (HEADER_BYTES + length > MAX_OBJECT_BYTES) {
            return ParseResult.Invalid(Reason.INVALID_LENGTH)
        }
        return ParseResult.Valid(
            Header(
                envelopeVersion = VERSION,
                schemaVersion = schemaVersion,
                backupId = backupId,
                keyId = keyId,
                payloadNonce = payloadNonce,
                dekWrapNonce = dekWrapNonce,
                wrappedDek = wrappedDek,
                payloadCiphertextLength = length,
            ),
        )
    }

    private fun aad(prefix: String, uid: String, parts: List<ByteArray>): ByteArray {
        val uidBytes = uid.toByteArray(Charsets.UTF_8)
        require(uidBytes.isNotEmpty() && uidBytes.size <= 0xFFFF) { "UID length out of range" }
        val out = ByteArrayOutputStream()
        out.write(prefix.toByteArray(Charsets.US_ASCII))
        out.write((uidBytes.size ushr 8) and 0xFF)
        out.write(uidBytes.size and 0xFF)
        out.write(uidBytes)
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun uint16(value: Int): ByteArray = byteArrayOf(
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun uint32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /** UInt32 as its bit pattern; schema versions are non-negative in practice. */
    private fun u32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** Null when the field does not fit a signed Long or is absurd for a v1 object. */
    private fun u64(bytes: ByteArray, offset: Int): Long? {
        if (bytes[offset].toInt() and 0x80 != 0) return null
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}

/** §8.6 wire identity: backup IDs are 32 lowercase hex chars and SHA-256 values 64. */
internal fun ByteArray.toLowerHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(digits[value ushr 4]).append(digits[value and 0x0F])
    }
    return out.toString()
}
