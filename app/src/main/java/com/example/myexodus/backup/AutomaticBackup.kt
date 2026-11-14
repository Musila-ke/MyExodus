package com.example.myexodus.backup

import com.example.myexodus.account.SessionFence
import com.example.myexodus.account.isEmpty
import com.example.myexodus.backup.BackupKeyManager.Unlock
import com.example.myexodus.data.db.VaultLockedException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ADR #310: one automatic backup pass (owner ruling 2026-11-01). Runs without any user secret,
 * only while signed in with a configured bundle and a Keystore-wrapped ABK adopted from a prior
 * unlock, and only when the current data would not produce the payload this phone last
 * committed. Conflicts never replace anything: a snapshot this device has not materialized stops
 * the pass and waits for the user.
 */
class AutomaticBackup(
    private val keys: BackupKeyManager,
    private val uploads: BackupUploads,
    private val fence: SessionFence,
    private val state: BackupAutoStateStore,
    private val deviceKey: BackupAbk,
    private val snapshot: BackupSnapshotReader,
) {

    sealed interface Outcome {
        data object NotSignedIn : Outcome
        data object NotConfigured : Outcome
        /** Waits for one manual passphrase/recovery unlock, which re-adopts the key. */
        data object DeviceKeyMissing : Outcome
        /** Nothing changed, or there is no local data to snapshot. */
        data object Unchanged : Outcome
        data class Uploaded(val revision: Long) : Outcome
        data class Failed(val retryable: Boolean) : Outcome
    }

    suspend fun run(): Outcome {
        val fingerprint = fence.current() ?: return Outcome.NotSignedIn
        val remote = try {
            keys.load(fingerprint.uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupKeyException) {
            return Outcome.Failed(retryable = e.failure == BackupKeyFailure.NETWORK)
        } ?: return Outcome.NotConfigured
        if (!deviceKey.has(fingerprint.uid)) return Outcome.DeviceKeyMissing
        val content = try {
            contentDigest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultLockedException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Failed(retryable = true)
        }
        val record = try {
            state.read(fingerprint.uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Failed(retryable = true)
        }
        if (content == null || record.contentSha256 == content) return Outcome.Unchanged
        return try {
            Outcome.Uploaded(uploads.backup(remote, Unlock.DeviceKey).revision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultLockedException) {
            throw e
        } catch (e: BackupUploadException) {
            if (e.failure == BackupUploadFailure.WRONG_SECRET) Outcome.DeviceKeyMissing
            else Outcome.Failed(retryable = e.failure in RETRYABLE)
        } catch (e: BackupKeyException) {
            Outcome.Failed(retryable = e.failure == BackupKeyFailure.NETWORK)
        } catch (e: Exception) {
            Outcome.Failed(retryable = true)
        }
    }

    /**
     * SHA-256 of the exact compressed payload a backup would seal, so no upload happens when
     * the bytes would be identical. Null when the local snapshot is empty: the uploader refuses
     * an empty snapshot, so there is nothing for the automatic pass to do either.
     */
    private suspend fun contentDigest(): String? = withContext(Dispatchers.IO) {
        val data = snapshot.read()
        if (data.isEmpty()) return@withContext null
        val digest = MessageDigest.getInstance("SHA-256")
        BackupPayload.write(data, DigestOutputStream(Discard, digest))
        digest.digest().toLowerHex()
    }

    /** `OutputStream.nullOutputStream()` is API 33; this is the same sink on every supported API. */
    private object Discard : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private companion object {
        /** Transient answers a later pass can recover from; everything else waits for the user. */
        val RETRYABLE = setOf(
            BackupUploadFailure.NETWORK,
            BackupUploadFailure.BACKUP_RESERVATION_LIMIT,
            BackupUploadFailure.BACKUP_RESERVATION_EXPIRED,
            BackupUploadFailure.BACKUP_OBJECT_MISSING,
            BackupUploadFailure.BACKUP_OBJECT_MISMATCH,
            BackupUploadFailure.STAGING_FAILED,
            BackupUploadFailure.LOCAL_STATE_UNAVAILABLE,
        )
    }
}
