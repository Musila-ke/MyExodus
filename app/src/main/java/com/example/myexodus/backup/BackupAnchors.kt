package com.example.myexodus.backup

import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.RemoteBackupAnchor

/** One committed snapshot identity: revision plus the server-computed object SHA-256. */
data class BackupPair(val revision: Long, val sha256: String)

/**
 * Account plan §6.4 item 11 / §6.6 item 3: [materialized] is the cloud snapshot represented
 * by current local state; [highWater] is the highest snapshot this installation has ever seen
 * and never moves backwards.
 */
data class BackupAnchors(
    val materialized: BackupPair? = null,
    val highWater: BackupPair? = null,
)

/**
 * §6.4 item 13 / §6.6 item 3: the client-side rollback/equivocation guard. The server compares
 * the submitted base; only the device's own high-water anchor can detect a service that
 * replays an older but internally consistent snapshot. Explicit replacement may not bypass it.
 */
object BackupHighWater {

    sealed interface Advance {
        data class Accepted(val highWater: BackupPair) : Advance
        data object Rollback : Advance
        data object Equivocation : Advance
    }

    fun advance(current: BackupPair?, observed: BackupPair): Advance = when {
        current == null -> Advance.Accepted(observed)
        observed.revision < current.revision -> Advance.Rollback
        observed.revision == current.revision && observed.sha256 != current.sha256 ->
            Advance.Equivocation
        observed.revision > current.revision -> Advance.Accepted(observed)
        else -> Advance.Accepted(current)
    }
}

class BackupAnchorUnavailableException : Exception("remote backup anchors unavailable")

/** The per-UID anchor persistence seam; production is [RoomBackupAnchorStore]. */
interface RemoteBackupAnchorStore {
    suspend fun read(): BackupAnchors
    suspend fun recordCommitted(pair: BackupPair)
    suspend fun recordObserved(pair: BackupPair)
}

/**
 * Stores the anchors in the active UID-scoped SQLCipher database (§6.4 item 11). The store
 * does not resolve a UID itself: the caller has already fenced the session and opened the
 * matching scope, and a concurrent identity change is stopped by the fence before any anchor
 * write. Writes are serialized by the single backup operation; a write that would lower the
 * high-water mark fails instead of persisting.
 */
class RoomBackupAnchorStore(
    private val storeFactory: AppRulesStoreFactory,
) : RemoteBackupAnchorStore {

    override suspend fun read(): BackupAnchors {
        val row = dao().get() ?: return BackupAnchors()
        return BackupAnchors(
            materialized = pairOf(row.materializedRevision, row.materializedSha256),
            highWater = pairOf(row.highWaterRevision, row.highWaterSha256),
        )
    }

    override suspend fun recordCommitted(pair: BackupPair) {
        val current = read()
        val highWater = accepted(BackupHighWater.advance(current.highWater, pair))
        write(pair, highWater)
    }

    override suspend fun recordObserved(pair: BackupPair) {
        val current = read()
        val highWater = accepted(BackupHighWater.advance(current.highWater, pair))
        write(current.materialized, highWater)
    }

    private fun accepted(advance: BackupHighWater.Advance): BackupPair = when (advance) {
        is BackupHighWater.Advance.Accepted -> advance.highWater
        // A write path that would move the high-water mark backwards is a contract violation,
        // not a user-recoverable state; callers check the same pair before reaching here.
        BackupHighWater.Advance.Rollback -> throw BackupAnchorUnavailableException()
        BackupHighWater.Advance.Equivocation -> throw BackupAnchorUnavailableException()
    }

    private fun pairOf(revision: Long?, sha256: String?): BackupPair? {
        if (revision == null && sha256 == null) return null
        // A half pair can only come from an interrupted write or corruption; treating it as
        // "no anchor" would silently disable rollback detection, so fail closed instead.
        if (revision == null || sha256 == null) throw BackupAnchorUnavailableException()
        return BackupPair(revision, sha256)
    }

    private suspend fun write(materialized: BackupPair?, highWater: BackupPair?) {
        dao().put(
            RemoteBackupAnchor(
                materializedRevision = materialized?.revision,
                materializedSha256 = materialized?.sha256,
                highWaterRevision = highWater?.revision,
                highWaterSha256 = highWater?.sha256,
            ),
        )
    }

    private fun dao() = storeFactory.open()?.remoteBackupAnchorDao()
        ?: throw BackupAnchorUnavailableException()
}
