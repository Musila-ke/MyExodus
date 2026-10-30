package com.example.myexodus.backup

import kotlin.coroutines.cancellation.CancellationException

/**
 * Account plan §6.4 item 8 / §9: what a guest import may do given the target account's cloud
 * pointer. Import may only touch a target database whose logical state is the current cloud
 * snapshot, or a target with no cloud snapshot at all.
 */
sealed interface ImportCloudVerdict {
    data object NoCloudSnapshot : ImportCloudVerdict

    /** The local database is the current snapshot (restored or committed here). */
    data object Materialized : ImportCloudVerdict

    /** A snapshot exists that this device has not restored; import must wait for it. */
    data class RestoreRequired(val remote: BackupPair) : ImportCloudVerdict

    /** §6.4 item 8: rollback/equivocation is not resolvable by merge — refuse until investigated. */
    data class HardStop(val failure: BackupUploadFailure) : ImportCloudVerdict

    /** The pointer or the anchors could not be read; never import on an unverified base. */
    data object Unavailable : ImportCloudVerdict
}

fun interface ImportCloudGuard {
    suspend fun check(uid: String): ImportCloudVerdict
}

/** Reads the pointer once, persists the highest observed pair, and compares the materialized one. */
class ManifestImportCloudGuard(
    private val manifest: BackupManifestApi,
    private val anchors: RemoteBackupAnchorStore,
) : ImportCloudGuard {

    override suspend fun check(uid: String): ImportCloudVerdict = try {
        val pointer = manifest.current(uid) ?: return ImportCloudVerdict.NoCloudSnapshot
        val stored = anchors.read()
        when (val advance = BackupHighWater.advance(stored.highWater, pointer)) {
            is BackupHighWater.Advance.Rollback ->
                ImportCloudVerdict.HardStop(BackupUploadFailure.REMOTE_BACKUP_ROLLBACK)
            is BackupHighWater.Advance.Equivocation ->
                ImportCloudVerdict.HardStop(BackupUploadFailure.REMOTE_BACKUP_EQUIVOCATION)
            is BackupHighWater.Advance.Accepted -> {
                if (advance.highWater != stored.highWater) anchors.recordObserved(advance.highWater)
                if (stored.materialized == pointer) ImportCloudVerdict.Materialized
                else ImportCloudVerdict.RestoreRequired(pointer)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ImportCloudVerdict.Unavailable
    }
}
