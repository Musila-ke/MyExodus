package com.example.myexodus.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account plan §6.4 item 8 / §9: a guest import may only touch a target database that is the
 * current cloud snapshot, and rollback/equivocation is never resolvable by merging.
 */
class BackupImportGuardTest {

    private val remote = BackupPair(3, "b".repeat(64))

    private class FakeManifest(var pointer: BackupPair?) : BackupManifestApi {
        var failure: Exception? = null

        override suspend fun current(uid: String): BackupPair? {
            failure?.let { throw it }
            return pointer
        }

        override suspend fun snapshot(uid: String): RemoteSnapshot? = error("unused")
        override suspend fun begin(request: BeginBackupRequest): BeginBackupResult = error("unused")
        override suspend fun extend(backupId: String): ExtendReservationResult = error("unused")
        override suspend fun commit(backupId: String, sha256: String, objectBytes: Long): CommitManifestResult =
            error("unused")

        override suspend fun beginRestore(backupId: String) = error("unused")
        override suspend fun endRestore(backupId: String) = error("unused")
    }

    private class FakeAnchors(
        var stored: BackupAnchors = BackupAnchors(),
        private val failing: Boolean = false,
    ) : RemoteBackupAnchorStore {
        val observed = mutableListOf<BackupPair>()

        override suspend fun read(): BackupAnchors {
            if (failing) throw BackupAnchorUnavailableException()
            return stored
        }

        override suspend fun recordCommitted(pair: BackupPair) = error("the guard never commits")

        override suspend fun recordObserved(pair: BackupPair) {
            observed += pair
            stored = stored.copy(highWater = pair)
        }
    }

    @Test
    fun anAccountWithoutACloudSnapshotIsSafeToImportInto() = runTest {
        val guard = ManifestImportCloudGuard(FakeManifest(null), FakeAnchors())

        assertEquals(ImportCloudVerdict.NoCloudSnapshot, guard.check("u"))
    }

    @Test
    fun aDatabaseThatIsTheCurrentSnapshotIsSafeToImportInto() = runTest {
        val anchors = FakeAnchors(BackupAnchors(materialized = remote, highWater = remote))
        val guard = ManifestImportCloudGuard(FakeManifest(remote), anchors)

        assertEquals(ImportCloudVerdict.Materialized, guard.check("u"))
        assertTrue("the existing high-water is not rewritten", anchors.observed.isEmpty())
    }

    @Test
    fun aSnapshotThisDeviceNeverMaterializedMustBeRestoredFirst() = runTest {
        val anchors = FakeAnchors(BackupAnchors())
        val guard = ManifestImportCloudGuard(FakeManifest(remote), anchors)

        assertEquals(ImportCloudVerdict.RestoreRequired(remote), guard.check("u"))
        // Plan §6.4 item 8: the highest observed pair is persisted before deciding.
        assertEquals(listOf(remote), anchors.observed)
    }

    @Test
    fun aPointerBehindTheHighWaterIsAHardStop() = runTest {
        val anchors = FakeAnchors(BackupAnchors(highWater = BackupPair(4, "c".repeat(64))))
        val guard = ManifestImportCloudGuard(FakeManifest(remote), anchors)

        assertEquals(
            ImportCloudVerdict.HardStop(BackupUploadFailure.REMOTE_BACKUP_ROLLBACK),
            guard.check("u"),
        )
        assertTrue(anchors.observed.isEmpty())
    }

    @Test
    fun aSameRevisionWithDifferentBytesIsAHardStop() = runTest {
        val anchors = FakeAnchors(BackupAnchors(highWater = BackupPair(3, "c".repeat(64))))
        val guard = ManifestImportCloudGuard(FakeManifest(remote), anchors)

        assertEquals(
            ImportCloudVerdict.HardStop(BackupUploadFailure.REMOTE_BACKUP_EQUIVOCATION),
            guard.check("u"),
        )
    }

    @Test
    fun anUnreadablePointerFailsClosed() = runTest {
        val manifest = FakeManifest(remote).apply { failure = IllegalStateException("offline") }
        val guard = ManifestImportCloudGuard(manifest, FakeAnchors())

        assertEquals(ImportCloudVerdict.Unavailable, guard.check("u"))
    }

    @Test
    fun anUnreadableAnchorRecordFailsClosed() = runTest {
        val guard = ManifestImportCloudGuard(FakeManifest(remote), FakeAnchors(failing = true))

        assertEquals(ImportCloudVerdict.Unavailable, guard.check("u"))
    }
}
