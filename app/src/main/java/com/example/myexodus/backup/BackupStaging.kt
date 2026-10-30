package com.example.myexodus.backup

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Account plan §6.4 items 7 and 14: the app-private staging area for the compressed payload and
 * the sealed object. [root] must be under `noBackupFilesDir`, which Android never includes in
 * Auto Backup or device-to-device transfer. Files are named by backup ID so the persisted
 * operation journal can name exactly what an active operation still needs.
 */
class BackupStaging(private val root: File) {

    fun compressedFile(backupId: String): File = stagedFile("$COMPRESSED_PREFIX$backupId.bin")

    fun objectFile(backupId: String): File = stagedFile("$OBJECT_PREFIX$backupId.bin")

    // A fresh install has no staging directory yet; FileOutputStream does not create parents.
    private fun stagedFile(name: String): File {
        root.mkdirs()
        return File(root, name)
    }

    /** §6.6 item 4: the restore download and its decrypted payload live in one directory. */
    fun restoreDir(backupId: String): File = File(root, "$RESTORE_PREFIX$backupId")

    fun restoreObjectFile(backupId: String): File = File(restoreDir(backupId), "object.bin")

    fun restorePayloadFile(backupId: String): File = File(restoreDir(backupId), "payload.bin")

    /**
     * §6.6 item 6: an interrupted restore's files are unauthenticated or incomplete by
     * construction (the snapshot only lives in memory), so only the operation naming [keepBackupId]
     * survives. Best effort, like [sweep].
     */
    fun pruneRestore(keepBackupId: String?) {
        val keep = keepBackupId?.let { restoreDir(it).name }
        root.listFiles()?.forEach { file ->
            if (!file.name.startsWith(RESTORE_PREFIX) || file.name == keep) return@forEach
            file.deleteRecursively()
        }
    }

    /**
     * §6.4 item 14: the serialized/compressed staging file must be gone before the upload starts.
     * One retry covers a transient FS error; a file that survives both attempts fails the
     * operation instead of leaving readable data behind unnoticed.
     */
    fun deleteOrRetry(file: File) {
        if (!file.exists()) return
        if (file.delete() || !file.exists()) return
        if (file.delete() || !file.exists()) return
        throw IOException("staged file could not be deleted")
    }

    /**
     * §6.4 item 14: startup sweep. Keeps the object of the active operation (a resumable upload
     * needs it) and any authenticated restore staging, which §6.6 item 6 owns under
     * [RESTORE_PREFIX]. Everything else is stale by construction. Best effort: a failed delete
     * is retried on the next start.
     */
    fun sweep(keepBackupId: String?) {
        val keep = keepBackupId?.let(::objectFile)?.name
        root.listFiles()?.forEach { file ->
            if (file.name == keep || file.name.startsWith(RESTORE_PREFIX)) return@forEach
            file.delete()
        }
    }

    /** §6.9.3: local removal discards every staged artifact, the active operation included. */
    fun wipe() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        root.delete()
    }

    companion object {
        /** Reserved for §6.6 restore staging; never deleted by the backup sweep. */
        const val RESTORE_PREFIX = "restore-"
        private const val OBJECT_PREFIX = "object-"
        private const val COMPRESSED_PREFIX = "compressed-"
    }
}

/** §6.4 item 14: the startup sweep consults the persisted operation journal for its keep-set. */
@Singleton
class BackupStagingSweeper @Inject constructor(
    private val sessions: BackupUploadSessionStore,
    private val staging: BackupStaging,
) {
    suspend fun sweep() {
        val keep = runCatching { sessions.get()?.backupId }.getOrNull()
        staging.sweep(keep)
        // A restore never resumes across process death: its decrypted snapshot was in memory.
        staging.pruneRestore(null)
    }
}

/** SHA-256 over the exact file bytes, lowercase hex (§8.6 wire form). */
internal fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toLowerHex()
}
