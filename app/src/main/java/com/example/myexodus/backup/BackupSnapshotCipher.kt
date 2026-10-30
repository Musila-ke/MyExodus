package com.example.myexodus.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Account plan §6.4 items 8-10 / §8.5: a snapshot is one random DEK sealing the compressed
 * payload, and that DEK sealed under the ABK. The two GCM operations use fresh nonces and
 * distinct AAD contexts, so a DEK wrapper can never be replayed as a payload block. Encryption
 * is streamed into the caller's staging sink; the whole-object SHA-256 is computed over the
 * exact bytes written and is returned for the manifest, never embedded in the object.
 *
 * Decryption writes only to a caller-provided private staging sink and returns [Opened] only
 * after the final tag verifies. Until then the sink content is unauthenticated and the caller
 * must delete it on every [Rejected] outcome (plan §6.6 item 5).
 */
object BackupSnapshotCipher {

    data class Sealed(
        val header: BackupEnvelope.Header,
        val objectBytes: Long,
        val sha256: ByteArray,
    )

    sealed interface OpenResult {
        data class Opened(val header: BackupEnvelope.Header) : OpenResult
        data class Rejected(val reason: RejectReason) : OpenResult
    }

    enum class RejectReason {
        BAD_MAGIC,
        UNSUPPORTED_VERSION,
        INVALID_LENGTH,
        TRUNCATED,
        TRAILING_BYTES,
        TAMPERED,
    }

    /**
     * Seals exactly [compressedLength] bytes from [compressed] into [out].
     *
     * @param abk the account backup key already unwrapped for this operation
     * @param backupId 16 random bytes chosen for this snapshot
     * @param keyId the current key bundle's stable ABK ID
     */
    fun seal(
        compressed: InputStream,
        compressedLength: Long,
        abk: ByteArray,
        uid: String,
        backupId: ByteArray,
        keyId: ByteArray,
        schemaVersion: Int,
        out: OutputStream,
        dek: ByteArray = BackupCrypto.random(BackupEnvelope.DEK_BYTES),
    ): Sealed {
        require(abk.size == BackupCrypto.ABK_BYTES) { "ABK must be ${BackupCrypto.ABK_BYTES} bytes" }
        require(backupId.size == BackupEnvelope.BACKUP_ID_BYTES) { "backup ID must be 16 bytes" }
        require(keyId.size == BackupCrypto.KEY_ID_BYTES) { "key ID must be 16 bytes" }
        require(dek.size == BackupEnvelope.DEK_BYTES) { "DEK must be 32 bytes" }
        require(compressedLength in 0..BackupEnvelope.MAX_COMPRESSED_BYTES) {
            "compressed payload exceeds the ${BackupEnvelope.MAX_COMPRESSED_BYTES}-byte cap"
        }
        val payloadNonce = BackupCrypto.random(BackupEnvelope.NONCE_BYTES)
        val dekWrapNonce = BackupCrypto.random(BackupEnvelope.NONCE_BYTES)
        val wrappedDek = BackupCrypto.wrap(
            dek,
            abk,
            dekWrapNonce,
            BackupEnvelope.dekWrapAad(uid, backupId, keyId),
        )
        val header = BackupEnvelope.Header(
            envelopeVersion = BackupEnvelope.VERSION,
            schemaVersion = schemaVersion,
            backupId = backupId,
            keyId = keyId,
            payloadNonce = payloadNonce,
            dekWrapNonce = dekWrapNonce,
            wrappedDek = wrappedDek,
            payloadCiphertextLength = compressedLength + BackupEnvelope.TAG_BYTES,
        )
        val digest = MessageDigest.getInstance("SHA-256")
        val digestOut = DigestOutputStream(out, digest)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(dek, "AES"),
            GCMParameterSpec(128, payloadNonce),
        )
        cipher.updateAAD(
            BackupEnvelope.payloadAad(uid, backupId, schemaVersion, header.envelopeVersion),
        )
        try {
            BackupEnvelope.writeHeader(digestOut, header)
            sealPayload(compressed, compressedLength, cipher, digestOut)
            digestOut.flush()
        } finally {
            dek.fill(0)
        }
        return Sealed(
            header = header,
            objectBytes = header.objectBytes,
            sha256 = digest.digest(),
        )
    }

    /**
     * Decrypts one envelope from [input] into [staging].
     *
     * [staging] receives raw GCM output as it is produced; it must stay unreadable by app code
     * and be deleted by the caller on any [Rejected] result.
     */
    fun open(
        input: InputStream,
        abk: ByteArray,
        uid: String,
        staging: OutputStream,
    ): OpenResult {
        require(abk.size == BackupCrypto.ABK_BYTES) { "ABK must be ${BackupCrypto.ABK_BYTES} bytes" }
        val header = when (val parsed = BackupEnvelope.readHeader(input)) {
            is BackupEnvelope.ParseResult.Invalid -> return OpenResult.Rejected(
                when (parsed.reason) {
                    BackupEnvelope.Reason.BAD_MAGIC -> RejectReason.BAD_MAGIC
                    BackupEnvelope.Reason.UNSUPPORTED_VERSION -> RejectReason.UNSUPPORTED_VERSION
                    BackupEnvelope.Reason.INVALID_LENGTH -> RejectReason.INVALID_LENGTH
                    BackupEnvelope.Reason.TRUNCATED -> RejectReason.TRUNCATED
                },
            )
            is BackupEnvelope.ParseResult.Valid -> parsed.header
        }
        val dek = BackupCrypto.unwrap(
            header.wrappedDek,
            abk,
            header.dekWrapNonce,
            BackupEnvelope.dekWrapAad(uid, header.backupId, header.keyId, header.envelopeVersion),
        ) ?: return OpenResult.Rejected(RejectReason.TAMPERED)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(dek, "AES"),
                GCMParameterSpec(128, header.payloadNonce),
            )
            cipher.updateAAD(
                BackupEnvelope.payloadAad(
                    uid, header.backupId, header.schemaVersion, header.envelopeVersion,
                ),
            )
            val buffer = ByteArray(BUFFER_BYTES)
            var remaining = header.payloadCiphertextLength
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) return OpenResult.Rejected(RejectReason.TRUNCATED)
                remaining -= n
                cipher.update(buffer, 0, n)?.let(staging::write)
            }
            if (input.read() != -1) return OpenResult.Rejected(RejectReason.TRAILING_BYTES)
            val tail = try {
                cipher.doFinal()
            } catch (e: GeneralSecurityException) {
                return OpenResult.Rejected(RejectReason.TAMPERED)
            }
            staging.write(tail)
            staging.flush()
            return OpenResult.Opened(header)
        } finally {
            dek.fill(0)
        }
    }

    private fun sealPayload(
        compressed: InputStream,
        compressedLength: Long,
        cipher: Cipher,
        out: OutputStream,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var remaining = compressedLength
        while (remaining > 0) {
            val n = compressed.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("payload shorter than its declared length")
            remaining -= n
            cipher.update(buffer, 0, n)?.let(out::write)
        }
        if (compressed.read() != -1) throw IOException("payload longer than its declared length")
        out.write(cipher.doFinal())
    }

    private const val BUFFER_BYTES = 64 * 1024
}
