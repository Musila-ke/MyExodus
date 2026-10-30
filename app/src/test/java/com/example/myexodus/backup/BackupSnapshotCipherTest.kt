package com.example.myexodus.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account plan §6.4 items 8-10 / §8.5: the v1 object round-trips exactly, every mutated byte
 * that matters is rejected before the payload can be trusted, and each seal uses fresh nonces.
 */
class BackupSnapshotCipherTest {

    private val uid = "user-123"
    private val abk = (0x10..0x2f).map { it.toByte() }.toByteArray()
    private val keyId = (0xa0..0xaf).map { it.toByte() }.toByteArray()
    private val backupId = (0x00..0x0f).map { it.toByte() }.toByteArray()
    private val payload = "compressed-payload-bytes".toByteArray()

    private data class Sealed(val bytes: ByteArray, val result: BackupSnapshotCipher.Sealed)

    private fun seal(
        plain: ByteArray = payload,
        declaredLength: Long = payload.size.toLong(),
        uid: String = this.uid,
        abk: ByteArray = this.abk,
        backupId: ByteArray = this.backupId,
    ): Sealed {
        val out = ByteArrayOutputStream()
        val result = BackupSnapshotCipher.seal(
            compressed = ByteArrayInputStream(plain),
            compressedLength = declaredLength,
            abk = abk,
            uid = uid,
            backupId = backupId,
            keyId = keyId,
            schemaVersion = 12,
            out = out,
        )
        return Sealed(out.toByteArray(), result)
    }

    private fun open(
        bytes: ByteArray,
        uid: String = this.uid,
        abk: ByteArray = this.abk,
    ): Pair<BackupSnapshotCipher.OpenResult, ByteArray> {
        val staging = ByteArrayOutputStream()
        val result = BackupSnapshotCipher.open(ByteArrayInputStream(bytes), abk, uid, staging)
        return result to staging.toByteArray()
    }

    @Test
    fun roundTrip_streamsTheExactPlaintextAndReportsTheExactObjectHash() {
        val sealed = seal()

        assertEquals(BackupEnvelope.HEADER_BYTES + payload.size + BackupEnvelope.TAG_BYTES, sealed.bytes.size)
        assertEquals(sealed.bytes.size.toLong(), sealed.result.objectBytes)
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(sealed.bytes),
            sealed.result.sha256,
        )

        val (result, staging) = open(sealed.bytes)

        assertTrue(result is BackupSnapshotCipher.OpenResult.Opened)
        val header = (result as BackupSnapshotCipher.OpenResult.Opened).header
        assertArrayEquals(backupId, header.backupId)
        assertArrayEquals(keyId, header.keyId)
        assertEquals(12, header.schemaVersion)
        assertEquals(BackupEnvelope.VERSION, header.envelopeVersion)
        assertEquals(payload.size.toLong(), header.payloadPlaintextLength)
        assertArrayEquals(payload, staging)
    }

    @Test
    fun everySealUsesFreshNoncesAndABackupIdChosenForThatSnapshot() {
        val first = seal(backupId = BackupCrypto.random(16))
        val second = seal(backupId = BackupCrypto.random(16))

        assertFalse(first.result.header.backupId.contentEquals(second.result.header.backupId))
        assertFalse(first.result.header.payloadNonce.contentEquals(second.result.header.payloadNonce))
        assertFalse(first.result.header.dekWrapNonce.contentEquals(second.result.header.dekWrapNonce))
        assertFalse(
            first.result.header.payloadNonce.contentEquals(first.result.header.dekWrapNonce),
        )
    }

    @Test
    fun headerFieldsAreReadableBeforeAnyDecryption() {
        val sealed = seal()

        val parsed = BackupEnvelope.readHeader(ByteArrayInputStream(sealed.bytes))

        assertTrue(parsed is BackupEnvelope.ParseResult.Valid)
        assertEquals(
            sealed.result.header.payloadCiphertextLength,
            (parsed as BackupEnvelope.ParseResult.Valid).header.payloadCiphertextLength,
        )
    }

    @Test
    fun wrongAbkAndWrongUidAreBothRejectedAsTampering() {
        val sealed = seal()
        val otherAbk = BackupCrypto.random(BackupCrypto.ABK_BYTES)

        assertEquals(
            BackupSnapshotCipher.OpenResult.Rejected(BackupSnapshotCipher.RejectReason.TAMPERED),
            open(sealed.bytes, abk = otherAbk).first,
        )
        assertEquals(
            BackupSnapshotCipher.OpenResult.Rejected(BackupSnapshotCipher.RejectReason.TAMPERED),
            open(sealed.bytes, uid = "other-uid").first,
        )
    }

    @Test
    fun everyAuthenticatedHeaderFieldIsBound() {
        val sealed = seal()

        fun flipped(offset: Int): BackupSnapshotCipher.OpenResult {
            val copy = sealed.bytes.copyOf()
            copy[offset] = (copy[offset].toInt() xor 0x01).toByte()
            return open(copy).first
        }

        // schema version (10..13), backup ID (14..29), key ID (30..45),
        // payload nonce (46..57), DEK-wrap nonce (58..69), wrapped DEK (70..117)
        listOf(13, 14, 30, 46, 58, 70, 117).forEach { offset ->
            assertEquals(
                "offset $offset must be authenticated",
                BackupSnapshotCipher.OpenResult.Rejected(BackupSnapshotCipher.RejectReason.TAMPERED),
                flipped(offset),
            )
        }
        // Header version is validated strictly, not tag-verified.
        assertEquals(
            BackupSnapshotCipher.OpenResult.Rejected(
                BackupSnapshotCipher.RejectReason.UNSUPPORTED_VERSION,
            ),
            flipped(9),
        )
    }

    @Test
    fun ciphertextTamperingIsRejected() {
        val sealed = seal()

        val lastByte = sealed.bytes.copyOf()
        lastByte[lastByte.size - 1] = (lastByte.last().toInt() xor 0x01).toByte()
        assertEquals(
            BackupSnapshotCipher.OpenResult.Rejected(BackupSnapshotCipher.RejectReason.TAMPERED),
            open(lastByte).first,
        )

        val middle = sealed.bytes.copyOf()
        middle[BackupEnvelope.HEADER_BYTES + 3] =
            (middle[BackupEnvelope.HEADER_BYTES + 3].toInt() xor 0x01).toByte()
        assertEquals(
            BackupSnapshotCipher.OpenResult.Rejected(BackupSnapshotCipher.RejectReason.TAMPERED),
            open(middle).first,
        )
    }

    @Test
    fun truncationAndTrailingBytesAreRejected() {
        val sealed = seal()

        assertEquals(
            BackupSnapshotCipher.OpenResult.Rejected(BackupSnapshotCipher.RejectReason.TRUNCATED),
            open(sealed.bytes.copyOf(BackupEnvelope.HEADER_BYTES - 1)).first,
        )
        assertEquals(
            BackupSnapshotCipher.OpenResult.Rejected(BackupSnapshotCipher.RejectReason.TRUNCATED),
            open(sealed.bytes.copyOf(sealed.bytes.size - 1)).first,
        )
        assertEquals(
            BackupSnapshotCipher.OpenResult.Rejected(
                BackupSnapshotCipher.RejectReason.TRAILING_BYTES,
            ),
            open(sealed.bytes + byteArrayOf(0)).first,
        )
    }

    @Test
    fun declaredLengthMustMatchTheSourceExactly() {
        assertThrows(IOException::class.java) {
            seal(declaredLength = payload.size + 1L)
        }
        assertThrows(IOException::class.java) {
            seal(declaredLength = payload.size - 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            seal(declaredLength = BackupEnvelope.MAX_COMPRESSED_BYTES + 1)
        }
    }

    @Test
    fun emptyPlaintextIsStructurallyValidButCarriesNoPayload() {
        val sealed = seal(plain = ByteArray(0), declaredLength = 0)

        val (result, staging) = open(sealed.bytes)

        assertTrue(result is BackupSnapshotCipher.OpenResult.Opened)
        assertEquals(0L, (result as BackupSnapshotCipher.OpenResult.Opened).header.payloadPlaintextLength)
        assertArrayEquals(ByteArray(0), staging)
        assertNotEquals(0, sealed.bytes.size)
    }
}
