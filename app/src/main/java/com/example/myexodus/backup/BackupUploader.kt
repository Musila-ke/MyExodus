package com.example.myexodus.backup

import com.example.myexodus.account.GuestSnapshot
import com.example.myexodus.account.SessionFence
import com.example.myexodus.account.SessionFingerprint
import com.example.myexodus.account.isEmpty
import com.example.myexodus.backup.BackupHighWater.Advance
import com.example.myexodus.backup.BackupKeyManager.Unlock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The frozen logical-data snapshot the uploader serializes (§6.4 item 6). */
fun interface BackupSnapshotReader {
    suspend fun read(): GuestSnapshot
}

/** User-visible phases of one backup operation. */
sealed interface BackupProgress {
    data object Preparing : BackupProgress
    data class Uploading(val transferred: Long, val total: Long) : BackupProgress
    data object Committing : BackupProgress
}

/** The uploader surface the backup pane drives; a seam so the pane is testable without a key bundle. */
interface BackupUploads {
    suspend fun currentPointer(): BackupPair?

    suspend fun backup(
        remote: RemoteBundle,
        unlock: Unlock,
        replace: BackupPair? = null,
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupUploader.Result
}

/**
 * Account plan §6.4 items 5-14: the client half of snapshot creation. Seals the freeze-read
 * payload into the v1 envelope, deletes the compressed staging file before any upload, drives
 * `beginBackup` → resumable Storage upload → `commitBackupManifest`, and advances the local
 * anchors only after the server confirms the commit. The high-water anchor is checked against
 * the server pointer before anything is sealed, so a replayed or conflicting cloud snapshot
 * stops the operation instead of being overwritten.
 */
class BackupUploader(
    private val keys: BackupKeyManager,
    private val manifest: BackupManifestApi,
    private val anchors: RemoteBackupAnchorStore,
    private val sessions: BackupUploadSessionStore,
    private val staging: BackupStaging,
    private val uploads: BackupObjectUploader,
    private val fence: SessionFence,
    private val snapshot: BackupSnapshotReader,
    private val autoState: BackupAutoStateStore,
) : BackupUploads {

    data class Result(val revision: Long, val sha256: String, val replayed: Boolean)

    /**
     * §6.4 item 11: the exact pointer a replace confirmation must be bound to. Reading it also
     * advances the high-water mark, so a pointer this device has already rejected can never
     * later be presented as a replacement target.
     */
    override suspend fun currentPointer(): BackupPair? = try {
        observe(requireFence().uid)
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupUploadException) {
        throw e
    } catch (e: Exception) {
        throw localFailure(e)
    }

    /**
     * Creates one snapshot. [replace] non-null means the user explicitly confirmed overwriting
     * the exact cloud pointer they were shown; it never bypasses the high-water guard.
     */
    override suspend fun backup(
        remote: RemoteBundle,
        unlock: Unlock,
        replace: BackupPair?,
        onProgress: (BackupProgress) -> Unit,
    ): Result = try {
        val fingerprint = requireFence()
        val stored = anchors.read()
        if (replace == null) {
            checkBase(stored.materialized, observe(fingerprint.uid))
        } else {
            checkReplace(replace)
        }
        val data = readSnapshot()
        val base = stored.materialized
        keys.withAbk(fingerprint.uid, remote, unlock) { abk ->
            perform(fingerprint, remote.bundle.keyId, abk, data, base, replace, onProgress)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupUploadException) {
        throw e
    } catch (e: BackupPayloadException.TooLarge) {
        throw BackupUploadException(BackupUploadFailure.PAYLOAD_TOO_LARGE, cause = e)
    } catch (e: BackupKeyException) {
        throw BackupUploadException(
            if (e.failure == BackupKeyFailure.WRONG_SECRET) BackupUploadFailure.WRONG_SECRET
            else BackupUploadFailure.UNKNOWN,
            cause = e,
        )
    } catch (e: Exception) {
        throw localFailure(e)
    }

    private suspend fun perform(
        fingerprint: SessionFingerprint,
        keyId: ByteArray,
        abk: ByteArray,
        data: GuestSnapshot,
        base: BackupPair?,
        replace: BackupPair?,
        onProgress: (BackupProgress) -> Unit,
    ): Result {
        val saved = resumable(fingerprint)
        val session = saved ?: begin(fingerprint, keyId, abk, data, base, replace)
        return uploadAndCommit(fingerprint, session, resumed = saved != null, onProgress)
    }

    /** §6.4 item 11: resume only the exact operation this identity opened. */
    private suspend fun resumable(fingerprint: SessionFingerprint): BackupUploadSession? {
        val record = sessions.get() ?: return null
        val file = staging.objectFile(record.backupId)
        val usable = record.uid == fingerprint.uid &&
            record.generation == fingerprint.generation &&
            file.isFile &&
            file.length() == record.objectBytes &&
            file.sha256Hex() == record.sha256
        if (usable) return record
        // A record bound to another identity or backed by a corrupt file can never be resumed.
        sessions.clear()
        staging.sweep(null)
        return null
    }

    /** Items 6-11: freeze, serialize, seal, verify, delete the compressed staging, reserve. */
    private suspend fun begin(
        fingerprint: SessionFingerprint,
        keyId: ByteArray,
        abk: ByteArray,
        data: GuestSnapshot,
        base: BackupPair?,
        replace: BackupPair?,
    ): BackupUploadSession {
        staging.sweep(null)
        val backupId = BackupCrypto.random(BackupEnvelope.BACKUP_ID_BYTES)
        val backupIdHex = backupId.toLowerHex()
        val compressed = staging.compressedFile(backupIdHex)
        val objectFile = staging.objectFile(backupIdHex)
        // ADR #310: the automatic pass must compare the exact bytes this seal produced, not a
        // separately-read snapshot that could drift between the two reads.
        val contentDigest = MessageDigest.getInstance("SHA-256")
        val sealed = try {
            withContext(Dispatchers.IO) {
                val sizes = FileOutputStream(compressed).use { file ->
                    BackupPayload.write(data, DigestOutputStream(file, contentDigest))
                }
                val out = FileOutputStream(objectFile).use { sink ->
                    FileInputStream(compressed).use { input ->
                        BackupSnapshotCipher.seal(
                            compressed = input,
                            compressedLength = sizes.compressedBytes,
                            abk = abk,
                            uid = fingerprint.uid,
                            backupId = backupId,
                            keyId = keyId,
                            schemaVersion = BackupPayload.VERSION,
                            out = sink,
                        )
                    }
                }
                // §6.4 item 14: the object on disk is the exact object that will be committed;
                // prove length and SHA-256 before anything leaves the device.
                check(
                    out.header.payloadPlaintextLength == sizes.compressedBytes &&
                        objectFile.length() == out.objectBytes &&
                        objectFile.sha256Hex() == out.sha256.toLowerHex(),
                ) { "sealed object does not match the staged bytes" }
                out to sizes
            }
        } catch (e: CancellationException) {
            discard(compressed, objectFile)
            throw e
        } catch (e: BackupPayloadException.TooLarge) {
            discard(compressed, objectFile)
            throw BackupUploadException(BackupUploadFailure.PAYLOAD_TOO_LARGE, cause = e)
        } catch (e: Exception) {
            discard(compressed, objectFile)
            throw BackupUploadException(BackupUploadFailure.STAGING_FAILED, cause = e)
        }
        val (sealedObject, sizes) = sealed
        val sha256 = sealedObject.sha256.toLowerHex()
        val contentSha256 = contentDigest.digest().toLowerHex()

        // §6.4 item 14: the compressed plaintext-equivalent leaves the disk before the upload.
        try {
            staging.deleteOrRetry(compressed)
        } catch (e: Exception) {
            discard(objectFile)
            throw BackupUploadException(BackupUploadFailure.STAGING_FAILED, cause = e)
        }

        requireFence(fingerprint)
        val started = try {
            manifest.begin(
                BeginBackupRequest(
                    backupId = backupIdHex,
                    schemaVersion = BackupPayload.VERSION,
                    compressedBytes = sizes.compressedBytes,
                    objectBytes = sealedObject.objectBytes,
                    baseRevision = base?.revision,
                    baseSha256 = base?.sha256,
                    replaceCurrent = replace != null,
                    replaceRevision = replace?.revision,
                    replaceSha256 = replace?.sha256,
                ),
            )
        } catch (e: Exception) {
            discard(objectFile)
            throw e
        }
        if (started.backupId != backupIdHex) {
            discard(objectFile)
            throw BackupUploadException(BackupUploadFailure.UNKNOWN)
        }
        val session = BackupUploadSession(
            uid = fingerprint.uid,
            generation = fingerprint.generation,
            backupId = backupIdHex,
            objectPath = started.objectPath,
            sha256 = sha256,
            objectBytes = sealedObject.objectBytes,
            compressedBytes = sizes.compressedBytes,
            uploaded = false,
            sessionUri = null,
            contentSha256 = contentSha256,
        )
        try {
            sessions.save(session)
        } catch (e: Exception) {
            discard(objectFile)
            throw BackupUploadException(BackupUploadFailure.LOCAL_STATE_UNAVAILABLE, cause = e)
        }
        return session
    }

    private suspend fun uploadAndCommit(
        fingerprint: SessionFingerprint,
        session: BackupUploadSession,
        resumed: Boolean,
        onProgress: (BackupProgress) -> Unit,
    ): Result {
        if (!session.uploaded) {
            onProgress(BackupProgress.Preparing)
            // §8.6: a saved session may be resumed close to its 30-minute idle expiry. The
            // extension is best effort — the upload itself still reports an expired
            // reservation as retryable, and a lost extension only costs that retry.
            if (resumed) runCatching { manifest.extend(session.backupId) }
            try {
                transmit(fingerprint, session, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A finalised upload and one whose response was lost look identical from here;
                // the manifest's SHA-256 check decides before this reports failure.
                val keep = e is BackupUploadException && e.failure == BackupUploadFailure.NETWORK
                if (fence.isCurrent(fingerprint) &&
                    !(e is BackupUploadException && e.failure == BackupUploadFailure.SESSION_CHANGED)
                ) {
                    commitOrNull(fingerprint, session)?.let { return it }
                }
                if (!keep) {
                    sessions.clear()
                    discard(staging.objectFile(session.backupId))
                }
                throw e
            }
            sessions.markUploaded(session.backupId)
        }
        return commit(fingerprint, session, onProgress)
    }

    /**
     * §6.4 item 11: upload the sealed object, persisting the resumable session URI under the
     * identity it belongs to. A fence change stops the upload task (§8.7); cancellation keeps
     * whatever URI was issued so the next attempt continues instead of restarting.
     */
    private suspend fun transmit(
        fingerprint: SessionFingerprint,
        session: BackupUploadSession,
        onProgress: (BackupProgress) -> Unit,
    ) {
        requireFence(fingerprint)
        val file = staging.objectFile(session.backupId)
        if (!file.isFile) throw BackupUploadException(BackupUploadFailure.STAGING_FAILED)
        val issued = AtomicReference(session.sessionUri)
        val persisted = AtomicReference(session.sessionUri)
        onProgress(BackupProgress.Uploading(0, session.objectBytes))
        try {
            coroutineScope {
                val keeper = launch(Dispatchers.IO) {
                    while (isActive) {
                        delay(SESSION_PERSIST_MS)
                        requireFence(fingerprint)
                        val latest = issued.get() ?: continue
                        if (latest != persisted.get()) {
                            sessions.save(session.copy(sessionUri = latest))
                            persisted.set(latest)
                        }
                    }
                }
                // §8.6: keep the reservation alive through a long upload (the server accepts
                // at most one extension per 10-minute window). Best effort; expiry surfaces
                // as a retryable failure from the upload/commit itself.
                val heartbeat = launch(Dispatchers.IO) {
                    while (isActive) {
                        delay(RESERVATION_HEARTBEAT_MS)
                        runCatching { manifest.extend(session.backupId) }
                    }
                }
                try {
                    uploads.upload(
                        objectPath = session.objectPath,
                        file = file,
                        sessionUri = session.sessionUri,
                        onSession = { issued.set(it) },
                        onProgress = { transferred, total ->
                            onProgress(BackupProgress.Uploading(transferred, total))
                        },
                    )
                } finally {
                    keeper.cancel()
                    heartbeat.cancel()
                }
            }
        } catch (e: CancellationException) {
            persistUri(session, issued.get(), persisted.get())
            throw e
        } catch (e: Exception) {
            persistUri(session, issued.get(), persisted.get())
            throw if (e is BackupUploadException) e
            else BackupUploadException(
                storageFailure(e, BackupUploadFailure.BACKUP_RESERVATION_EXPIRED),
                cause = e,
            )
        }
    }

    private suspend fun commit(
        fingerprint: SessionFingerprint,
        session: BackupUploadSession,
        onProgress: (BackupProgress) -> Unit,
    ): Result {
        requireFence(fingerprint)
        onProgress(BackupProgress.Committing)
        val result = try {
            manifest.commit(session.backupId, session.sha256, session.objectBytes)
        } catch (e: BackupUploadException) {
            if (e.failure != BackupUploadFailure.NETWORK) {
                sessions.clear()
                discard(staging.objectFile(session.backupId))
            }
            throw e
        }
        // §6.4 item 13: local anchors advance only after the server confirmed the commit. The
        // guard keeps a commit that raced an identity switch from writing into the new scope.
        if (fence.isCurrent(fingerprint)) {
            anchors.recordCommitted(BackupPair(result.revision, result.sha256))
            recordAuto(uid = fingerprint.uid, contentSha256 = session.contentSha256)
        }
        sessions.clear()
        discard(staging.objectFile(session.backupId))
        return Result(result.revision, result.sha256, result.replayed)
    }

    private suspend fun commitOrNull(
        fingerprint: SessionFingerprint,
        session: BackupUploadSession,
    ): Result? = try {
        commit(fingerprint, session) {}
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun observe(uid: String): BackupPair? {
        val pointer = manifest.current(uid) ?: return null
        return when (val advance = BackupHighWater.advance(anchors.read().highWater, pointer)) {
            is Advance.Accepted -> {
                anchors.recordObserved(advance.highWater)
                pointer
            }
            Advance.Rollback -> throw BackupUploadException(
                BackupUploadFailure.REMOTE_BACKUP_ROLLBACK,
                current = pointer,
            )
            Advance.Equivocation -> throw BackupUploadException(
                BackupUploadFailure.REMOTE_BACKUP_EQUIVOCATION,
                current = pointer,
            )
        }
    }

    /** §6.4 item 11: mirrors the server's decision table so a doomed reservation is never made. */
    private fun checkBase(local: BackupPair?, server: BackupPair?) {
        val failure = when {
            local == null && server == null -> return
            local == null -> BackupUploadFailure.BACKUP_BASE_REQUIRED
            server == null -> BackupUploadFailure.REMOTE_BACKUP_ROLLBACK
            server.revision > local.revision -> BackupUploadFailure.REMOTE_BACKUP_AHEAD
            server.revision < local.revision -> BackupUploadFailure.REMOTE_BACKUP_ROLLBACK
            server.sha256 != local.sha256 -> BackupUploadFailure.REMOTE_BACKUP_EQUIVOCATION
            else -> return
        }
        throw BackupUploadException(failure, current = server)
    }

    /** §6.4 item 11 / §6.6 item 3: explicit replacement cannot bypass the high-water guard. */
    private suspend fun checkReplace(replace: BackupPair) {
        when (BackupHighWater.advance(anchors.read().highWater, replace)) {
            is Advance.Accepted -> Unit
            Advance.Rollback -> throw BackupUploadException(
                BackupUploadFailure.REMOTE_BACKUP_ROLLBACK,
                current = replace,
            )
            Advance.Equivocation -> throw BackupUploadException(
                BackupUploadFailure.REMOTE_BACKUP_EQUIVOCATION,
                current = replace,
            )
        }
    }

    private suspend fun readSnapshot(): GuestSnapshot {
        val data = try {
            snapshot.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw localFailure(e)
        }
        if (data.isEmpty()) throw BackupUploadException(BackupUploadFailure.NO_LOCAL_DATA)
        return data
    }

    private fun requireFence(): SessionFingerprint =
        fence.current() ?: throw BackupUploadException(BackupUploadFailure.SIGN_IN_REQUIRED)

    private fun requireFence(fingerprint: SessionFingerprint) {
        if (!fence.isCurrent(fingerprint)) {
            throw BackupUploadException(BackupUploadFailure.SESSION_CHANGED)
        }
    }

    private suspend fun persistUri(
        session: BackupUploadSession,
        latest: String?,
        persisted: String?,
    ) {
        if (latest == null || latest == persisted) return
        withContext(NonCancellable) {
            runCatching { sessions.save(session.copy(sessionUri = latest)) }
        }
    }

    /** ADR #310: advisory last-success state; never fails a backup the server already committed. */
    private suspend fun recordAuto(uid: String, contentSha256: String?) {
        try {
            autoState.recordSuccess(uid, contentSha256, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The record only drives the daily debounce and the pane's timestamp; it self-heals.
        }
    }

    private fun discard(vararg files: File) {
        files.forEach { runCatching { it.delete() } }
    }

    private fun localFailure(e: Exception): BackupUploadException = when (e) {
        is BackupUploadException -> e
        is BackupAnchorUnavailableException ->
            BackupUploadException(BackupUploadFailure.LOCAL_STATE_UNAVAILABLE, cause = e)
        else -> BackupUploadException(BackupUploadFailure.UNKNOWN, cause = e)
    }

    private companion object {
        /** The session URI is written to encrypted storage at most this often mid-upload. */
        const val SESSION_PERSIST_MS = 1_000L

        /** §8.6's server window: at most one extension per 10 minutes. */
        const val RESERVATION_HEARTBEAT_MS = 10 * 60 * 1_000L
    }
}
