package com.example.myexodus.data.db

import android.content.Context
import android.util.Log
import java.io.File

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
    private val healthStore: DatabaseHealthStore,
) {

    val rulesDbFile: File get() = appContext.getDatabasePath(AppRulesStoreFactory.DB_NAME)
    val activityDbFile: File get() = appContext.getDatabasePath(ActivityLogSchema.DB_NAME)
    val legacyDbFile: File get() = appContext.getDatabasePath(LEGACY_DB_NAME)
    private val snapshotCacheFile: File get() = File(appContext.filesDir, SNAPSHOT_CACHE_NAME)

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
        snapshotCacheFile.delete()
        legacyKeyManager.resetKeyMaterial()
        activityLogKeyManager.resetKeyMaterial()
        healthStore.set(DatabaseHealth.Opening)
    }

    private fun renameCorrupt(file: File) {
        Log.w(TAG, "Recovery action: renaming corrupted database to .corrupt")
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        if (file.exists() && !file.renameTo(File(file.path + ".corrupt"))) {
            Log.e(TAG, "Failed to rename corrupted database to ${file.path}.corrupt")
        }
    }

    private fun deleteWithSidecars(file: File) {
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        File(file.path + ".corrupt").delete()
        file.delete()
    }

    private fun hasCorrupt(file: File): Boolean = File(file.path + ".corrupt").exists()

    private companion object {
        const val TAG = "MyExodusDb"
        const val LEGACY_DB_NAME = "myexodus.db"
        const val SNAPSHOT_CACHE_NAME = "rules_snapshot.json"
    }
}
