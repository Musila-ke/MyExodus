package com.example.myexodus.applock.crypto

import android.content.Context
import com.example.myexodus.util.deleteOrWarn
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Atomic, non-backed-up persistence for the single vault envelope (plan §6.1/§8.4/§8.12).
 *
 * Staging → active is a same-directory atomic rename, so a process death leaves either the
 * previous active envelope or the complete staged one, never a mix; an interrupted `.tmp`
 * write is swept on startup. Every artifact lives under `noBackupFilesDir`: an envelope
 * restored onto another device without the original Keystore key can only ever surface as
 * RECOVERY_REQUIRED, never as a fresh installation or a successful restore.
 */
class VaultEnvelopeStore(private val root: File) {

    constructor(context: Context) : this(File(context.noBackupFilesDir, DIR))

    fun hasActive(): Boolean = activeFile().exists()

    fun hasStaging(): Boolean = stagingFile().exists()

    /** Missing file is `null`; an unreadable file throws so it is never mistaken for "never enrolled". */
    fun readActive(): ByteArray? = read(activeFile())

    fun readStaging(): ByteArray? = read(stagingFile())

    /** Durable staging write; the active envelope is untouched until [commitStaging]. */
    fun writeStaging(envelope: ByteArray) = writeDurably(stagingFile(), envelope)

    /** Same-directory atomic rename; false when nothing was staged. */
    fun commitStaging(): Boolean {
        if (!stagingFile().exists()) return false
        Files.move(
            stagingFile().toPath(),
            activeFile().toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        return true
    }

    fun discardStaging() {
        stagingFile().deleteOrWarn(TAG)
    }

    /** Removes staging and the active envelope together (pre-commit rollback / disable). */
    fun discardAll() {
        stagingFile().deleteOrWarn(TAG)
        activeFile().deleteOrWarn(TAG)
    }

    /** A `.tmp` file is an interrupted write, never a usable envelope (plan §4.2 rule 3). */
    fun sweepIncompleteWrites() {
        tmpFile(activeFile()).deleteOrWarn(TAG)
        tmpFile(stagingFile()).deleteOrWarn(TAG)
    }

    private fun read(file: File): ByteArray? = if (file.exists()) file.readBytes() else null

    private fun writeDurably(file: File, bytes: ByteArray) {
        root.mkdirs()
        val tmp = tmpFile(file)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun tmpFile(file: File) = File(root, file.name + TMP_SUFFIX)

    private fun activeFile() = File(root, ACTIVE)

    private fun stagingFile() = File(root, STAGING)

    private companion object {
        const val TAG = "VaultEnvelopeStore"
        const val DIR = "vault"
        const val ACTIVE = "vault.ve"
        const val STAGING = "vault.ve.staging"
        const val TMP_SUFFIX = ".tmp"
    }
}
