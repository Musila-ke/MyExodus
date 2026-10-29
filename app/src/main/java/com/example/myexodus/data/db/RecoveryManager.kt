package com.example.myexodus.data.db

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The only component permitted to delete or rename database files. Covers the plain rules
 * store, the encrypted activity log store, and the pre-split combined database that the
 * migration consumes. Deletion happens only after failure classification and, for live
 * databases, explicit user confirmation via [resetAllData] — except confirmed corruption,
 * which preserves a renamed .corrupt copy instead of deleting.
 */
class RecoveryManager(
    private val appContext: Context,
    private val legacyKeyManager: DatabaseKeyManager,
    private val activityLogKeyManager: ActivityLogKeyManager,
    private val rulesStoreKeyManager: RulesStoreKeyManager,
    private val healthStore: DatabaseHealthStore,
) {

    val rulesDbFile: File get() =
        appContext.getDatabasePath(LocalDatabaseMigration.rulesDbName(appContext))
    val activityDbFile: File get() =
        appContext.getDatabasePath(LocalDatabaseMigration.activityDbName(appContext))
    val legacyDbFile: File get() = appContext.getDatabasePath(LEGACY_DB_NAME)
    private val snapshotCacheFile: File get() = File(appContext.filesDir, SNAPSHOT_CACHE_NAME)

    /** Plaintext backup kept until the encrypted store has opened on a later cold start. */
    val rulesDbBackupFile: File get() = File(rulesDbFile.path + CONVERSION_BACKUP_SUFFIX)

    /**
     * Plan §8.5's pre-unlock cache lives as a second copy of the CE snapshot cache under
     * device-protected storage. The reset contract ("destroys … the rules snapshot cache")
     * covers both, or a user-confirmed wipe leaves the restricted-package list on disk and
     * the next locked boot enforces pre-reset rules (finding #11, 2026-10-16 — §8.5's
     * reconciliation only overwrites it once the app authoritatively publishes again).
     * Same two-step idiom as [RulesSnapshotPublisher]: there is no single-call getter.
     */
    private val deviceProtectedSnapshotCacheFile: File
        get() {
            val de = if (appContext.isDeviceProtectedStorage) appContext
                     else appContext.createDeviceProtectedStorageContext()
            return File(de.filesDir, SNAPSHOT_CACHE_NAME)
        }

    /** Room-built encrypted database while the conversion is in flight. */
    val rulesDbTempFile: File get() = File(rulesDbFile.path + CONVERSION_TEMP_SUFFIX)

    fun checkOrphanedCorruptFiles() {
        val anyOrphan =
            hasCorrupt(rulesDbFile) || hasCorrupt(activityDbFile) || hasCorrupt(legacyDbFile)
        if (anyOrphan) {
            Log.w(TAG, "Recovery action: orphaned .corrupt file found on startup")
            healthStore.set(DatabaseHealth.RecoverableDataFound)
        }
    }

    fun hasOrphanedCorruptFiles(): Boolean =
        hasCorrupt(rulesDbFile) || hasCorrupt(activityDbFile) || hasCorrupt(legacyDbFile)

    /** Confirmed corruption of the plain rules store: preserve the file, drop WAL sidecars. */
    fun renameCorruptRules() = renameCorrupt(rulesDbFile)

    fun discardCorruptFiles() {
        File(rulesDbFile.path + ".corrupt").delete()
        File(activityDbFile.path + ".corrupt").delete()
        File(legacyDbFile.path + ".corrupt").delete()
        if (healthStore.health.value == DatabaseHealth.RecoverableDataFound) {
            healthStore.set(DatabaseHealth.Ready)
        }
    }

    /** User-confirmed reset: destroys all stores, keys, and the rules snapshot cache. */
    fun resetAllData() {
        Log.w(TAG, "Recovery action: user-confirmed reset of all database data")
        for (file in listOf(rulesDbFile, activityDbFile, legacyDbFile)) {
            deleteWithSidecars(file)
        }
        cleanupConversionArtifacts()
        snapshotCacheFile.delete()
        deviceProtectedSnapshotCacheFile.delete()
        legacyKeyManager.resetKeyMaterial()
        activityLogKeyManager.resetKeyMaterial()
        rulesStoreKeyManager.resetKeyMaterial()
        // The seven-day rollback copies hold pre-wipe data; a user-confirmed reset destroys them
        // with the live stores (a no-op when no UID scope was ever activated).
        LocalDatabaseMigration.discardRetainedSources(appContext)
        healthStore.set(DatabaseHealth.Opening)
    }

    // --- Plaintext→encrypted conversion (§8 Step 0). All renames/deletes stay in this class;
    // the conversion routine only reads rows and writes the temp destination.

    fun hasConversionBackup(): Boolean = rulesDbBackupFile.exists()

    /** Copies the (already checkpointed) plaintext rules file to `.bak` before any swap. */
    fun backupPlaintextRules(): Boolean = try {
        rulesDbFile.copyTo(rulesDbBackupFile, overwrite = true)
        Log.i(TAG, "Conversion backup written to ${rulesDbBackupFile.name}")
        true
    } catch (e: IOException) {
        Log.e(TAG, "Conversion backup copy failed", e)
        false
    }

    /**
     * Replaces the plaintext rules file with the verified encrypted temp file. The plaintext
     * sidecars go first (the caller checkpointed, so they hold no unmerged data); the swap
     * itself is a same-directory rename, atomic on Linux.
     */
    fun swapInEncryptedRules(): Boolean {
        deleteSidecars(rulesDbFile)
        var renamed = rulesDbTempFile.renameTo(rulesDbFile)
        if (!renamed) {
            // Unusual for a same-directory rename; the .bak makes the delete window safe.
            rulesDbFile.delete()
            renamed = rulesDbTempFile.renameTo(rulesDbFile)
        }
        if (!renamed) {
            Log.e(TAG, "Encrypted swap rename failed; plaintext backup retained")
            return false
        }
        deleteSidecars(rulesDbTempFile)
        Log.i(TAG, "Recovery action: encrypted rules database swapped into place")
        return true
    }

    /** Two-launch confirmation: drop the plaintext backup after a later cold start opened the encrypted store. */
    fun confirmConversionBackup() {
        Log.i(TAG, "Recovery action: conversion confirmed; removing plaintext backup")
        cleanupConversionArtifacts()
    }

    /** Encrypted store failed to open: put the untouched plaintext database back. */
    fun restorePlaintextFromBackup(): Boolean {
        if (!hasConversionBackup()) return false
        Log.w(TAG, "Recovery action: restoring plaintext database from conversion backup")
        deleteWithSidecars(rulesDbFile)
        deleteConversionTemp()
        val restored = rulesDbBackupFile.renameTo(rulesDbFile)
        if (!restored) Log.e(TAG, "Restore from conversion backup failed")
        return restored
    }

    fun deleteConversionTemp() {
        rulesDbTempFile.delete()
        deleteSidecars(rulesDbTempFile)
    }

    private fun cleanupConversionArtifacts() {
        deleteConversionTemp()
        rulesDbBackupFile.delete()
        deleteSidecars(rulesDbBackupFile)
    }

    private fun renameCorrupt(file: File) {
        Log.w(TAG, "Recovery action: renaming corrupted database to .corrupt")
        deleteSidecars(file)
        if (file.exists() && !file.renameTo(File(file.path + ".corrupt"))) {
            Log.e(TAG, "Failed to rename corrupted database to ${file.path}.corrupt")
        }
    }

    private fun deleteWithSidecars(file: File) {
        deleteSidecars(file)
        File(file.path + ".corrupt").delete()
        file.delete()
    }

    private fun deleteSidecars(file: File) {
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        File(file.path + "-journal").delete()
    }

    private fun hasCorrupt(file: File): Boolean = File(file.path + ".corrupt").exists()

    private companion object {
        const val TAG = "MyExodusDb"
        const val LEGACY_DB_NAME = "myexodus.db"
        const val SNAPSHOT_CACHE_NAME = "rules_snapshot.json"
        const val CONVERSION_BACKUP_SUFFIX = ".bak"
        const val CONVERSION_TEMP_SUFFIX = ".tmp"
    }
}
