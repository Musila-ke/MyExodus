package com.example.myexodus.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.account.GuestSnapshot
import com.example.myexodus.account.SessionFence
import com.example.myexodus.account.SessionFingerprint
import com.example.myexodus.backup.BackupKeyManager.Unlock
import com.example.myexodus.data.db.entity.CoachingStep
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account plan §6.4 items 5-14 / §8.6: the snapshot pipeline stops before any reservation when
 * the server pointer conflicts with this device's anchors, seals and proves the exact object
 * that will be committed, deletes the compressed staging before upload, and advances local
 * anchors only after the commit is confirmed. Real Argon2id and real file staging; the fakes
 * replace only the network, the clock-free pointer store and the fence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupUploaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val uid = "uid-fixture"
    private val passphrase = "7Gx!q2-Lm9@Rv4#Tz8"

    private lateinit var stagingRoot: File
    private lateinit var manifest: FakeManifest
    private lateinit var anchors: FakeAnchors
    private lateinit var sessions: FakeSessions
    private lateinit var uploads: FakeUploads
    private lateinit var autoState: FakeAutoState
    private lateinit var staging: BackupStaging
    private lateinit var uploader: BackupUploads
    private lateinit var remote: RemoteBundle
    private var snapshot = nonEmptySnapshot()
    private var fenceValue: SessionFingerprint? = SessionFingerprint(uid, 1)
    private var activeFence = SessionFingerprint(uid, 1)

    private class FakeKeyApi : BackupKeyApi {
        var stored: RemoteBundle? = null
        override suspend fun read(uid: String): RemoteBundle? = stored
        override suspend fun initialize(bundle: BackupKeyBundle): RemoteBundle {
            val committed = RemoteBundle(1, bundle)
            stored = committed
            return committed
        }

        override suspend fun update(expectedRevision: Long, bundle: BackupKeyBundle): RemoteBundle =
            error("unused")
    }

    private class FakeRevisions : BackupRevisionStore {
        private val revisions = mutableMapOf<String, Long>()
        override suspend fun highest(uid: String): Long? = revisions[uid]
        override suspend fun record(uid: String, revision: Long) {
            revisions[uid] = maxOf(revisions[uid] ?: 0L, revision)
        }
    }

    private class FakeManifest : BackupManifestApi {
        var pointer: BackupPair? = null
        var commitRevision = 1L
        val begins = mutableListOf<BeginBackupRequest>()
        val commits = mutableListOf<Pair<String, String>>()
        var commitCalls = 0
        var extendCalls = 0

        override suspend fun current(uid: String): BackupPair? = pointer

        override suspend fun snapshot(uid: String): RemoteSnapshot? = null

        override suspend fun beginRestore(backupId: String) = Unit

        override suspend fun endRestore(backupId: String) = Unit

        override suspend fun begin(request: BeginBackupRequest): BeginBackupResult {
            begins += request
            return BeginBackupResult(
                backupId = request.backupId,
                objectPath = "backups/uid-fixture/${request.backupId}.bin",
                expiresAtMs = 0,
                replayed = false,
            )
        }

        override suspend fun extend(backupId: String): ExtendReservationResult {
            extendCalls++
            return ExtendReservationResult(0, true)
        }

        override suspend fun commit(
            backupId: String,
            sha256: String,
            objectBytes: Long,
        ): CommitManifestResult {
            commitCalls++
            commits += backupId to sha256
            val pair = BackupPair(commitRevision, sha256)
            pointer = pair
            return CommitManifestResult(commitRevision, sha256, replayed = false)
        }
    }

    private class FakeAnchors(initial: BackupAnchors = BackupAnchors()) : RemoteBackupAnchorStore {
        var anchors = initial

        override suspend fun read(): BackupAnchors = anchors

        override suspend fun recordCommitted(pair: BackupPair) {
            anchors = BackupAnchors(materialized = pair, highWater = highest(anchors.highWater, pair))
        }

        override suspend fun recordObserved(pair: BackupPair) {
            anchors = anchors.copy(highWater = highest(anchors.highWater, pair))
        }

        private fun highest(current: BackupPair?, observed: BackupPair) =
            if (current == null || observed.revision > current.revision) observed else current
    }

    private class FakeSessions : BackupUploadSessionStore {
        var session: BackupUploadSession? = null

        override suspend fun get(): BackupUploadSession? = session

        override suspend fun save(session: BackupUploadSession) {
            this.session = session
        }

        override suspend fun markUploaded(backupId: String) {
            session?.let { if (it.backupId == backupId) session = it.copy(uploaded = true) }
        }

        override suspend fun clear() {
            session = null
        }
    }

    private class FakeAutoState : BackupAutoStateStore {
        var uid: String? = null
        var contentSha256: String? = null
        var atEpochMillis: Long? = null

        override suspend fun read(uid: String) = BackupAutoRecord(contentSha256, atEpochMillis)

        override suspend fun recordSuccess(uid: String, contentSha256: String?, atEpochMillis: Long) {
            this.uid = uid
            this.contentSha256 = contentSha256
            this.atEpochMillis = atEpochMillis
        }
    }

    private class FakeUploads : BackupObjectUploader {
        var failWith: Exception? = null
        var onUpload: (() -> Unit)? = null
        var onSent: (() -> Unit)? = null
        var lastSessionUri: String? = null
        var uploadedBytes = -1L
        var uploadedSha: String? = null
        val objectPaths = mutableListOf<String>()

        override suspend fun upload(
            objectPath: String,
            file: File,
            sessionUri: String?,
            onSession: (String) -> Unit,
            onProgress: (transferred: Long, total: Long) -> Unit,
        ) {
            objectPaths += objectPath
            lastSessionUri = sessionUri
            onUpload?.invoke()
            check(file.isFile) { "sealed object must exist at upload time" }
            uploadedBytes = file.length()
            uploadedSha = file.sha256Hex()
            onSession("session-$objectPath")
            onProgress(0, file.length())
            onProgress(file.length(), file.length())
            onSent?.invoke()
            failWith?.let { throw it }
        }
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = BackupKeyManager(FakeKeyApi(), FakeRevisions(), BackupWordlists(context))
        remote = manager.create(uid, passphrase).remote
        manifest = FakeManifest()
        anchors = FakeAnchors()
        sessions = FakeSessions()
        uploads = FakeUploads()
        autoState = FakeAutoState()
        stagingRoot = temp.newFolder("staging")
        staging = BackupStaging(stagingRoot)
        activeFence = SessionFingerprint(uid, 1)
        fenceValue = activeFence
        uploader = BackupUploader(
            keys = manager,
            manifest = manifest,
            anchors = anchors,
            sessions = sessions,
            staging = staging,
            uploads = uploads,
            fence = SessionFence { fenceValue },
            snapshot = { snapshot },
            autoState = autoState,
        )
    }

    private fun nonEmptySnapshot() = GuestSnapshot(
        habits = emptyList(),
        streaks = emptyList(),
        milestones = emptyList(),
        attempts = emptyList(),
        sessions = emptyList(),
        coaching = listOf(
            CoachingStep(
                stepId = "step-1",
                profileId = "profile-1",
                stepOrder = 1,
                actionText = "Breathe for one minute",
                associatedTriggerWindow = "",
            ),
        ),
        riskWindows = emptyList(),
        policy = null,
    )

    private suspend fun backup(replace: BackupPair? = null) =
        uploader.backup(remote, Unlock.Passphrase(passphrase), replace)

    private fun failureOf(block: suspend () -> Unit): BackupUploadException =
        assertThrows(BackupUploadException::class.java) { runBlocking { block() } }

    private fun compressedFiles() =
        stagingRoot.listFiles().orEmpty().filter { it.name.startsWith("compressed-") }

    @Test
    fun firstBackup_sealsTheExactObjectAndAdvancesAnchorsAfterCommit() = runBlocking {
        uploads.onUpload = { assertTrue(compressedFiles().isEmpty()) }

        val result = backup()

        val begin = manifest.begins.single()
        assertNull(begin.baseRevision)
        assertNull(begin.baseSha256)
        assertFalse(begin.replaceCurrent)
        assertTrue(begin.objectBytes > 0)
        assertTrue(begin.compressedBytes > 0)
        assertEquals(begin.objectBytes, uploads.uploadedBytes)
        assertEquals(
            listOf("backups/uid-fixture/${begin.backupId}.bin"),
            uploads.objectPaths,
        )

        // The committed hash is the hash of the bytes that were actually staged and uploaded.
        assertEquals(uploads.uploadedSha, result.sha256)
        assertEquals(result.sha256, manifest.commits.single().second)
        assertEquals(BackupPair(result.revision, result.sha256), anchors.anchors.materialized)
        assertEquals(anchors.anchors.materialized, anchors.anchors.highWater)

        assertNull(sessions.session)
        assertFalse(staging.objectFile(begin.backupId).exists())
        assertTrue(compressedFiles().isEmpty())
        // A fresh reservation is not extended; the heartbeat only fires on a long upload.
        assertEquals(0, manifest.extendCalls)

        // ADR #310: the change detector is the SHA-256 of the compressed plaintext, recorded
        // only after the server confirmed the commit.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        BackupPayload.write(snapshot, java.security.DigestOutputStream(java.io.ByteArrayOutputStream(), digest))
        assertEquals(uid, autoState.uid)
        assertEquals(digest.digest().joinToString("") { "%02x".format(it) }, autoState.contentSha256)
        assertNotNull(autoState.atEpochMillis)
    }

    @Test
    fun serverAheadOfLocalBaseStopsBeforeAnyReservation() = runBlocking {
        val mine = BackupPair(1, "sha-1")
        anchors.anchors = BackupAnchors(materialized = mine, highWater = mine)
        manifest.pointer = BackupPair(2, "server-sha")

        val failure = failureOf { backup() }

        assertEquals(BackupUploadFailure.REMOTE_BACKUP_AHEAD, failure.failure)
        assertEquals(BackupPair(2, "server-sha"), failure.current)
        assertTrue(manifest.begins.isEmpty())
        assertTrue(uploads.objectPaths.isEmpty())
        assertEquals(BackupPair(2, "server-sha"), anchors.anchors.highWater)
    }

    @Test
    fun serverPointerWithNoLocalAnchorRequiresABase() = runBlocking {
        manifest.pointer = BackupPair(1, "server-sha")

        val failure = failureOf { backup() }

        assertEquals(BackupUploadFailure.BACKUP_BASE_REQUIRED, failure.failure)
        assertTrue(manifest.begins.isEmpty())
    }

    @Test
    fun localAnchorWithNoServerPointerIsARollback() = runBlocking {
        val anchor = BackupPair(2, "sha-2")
        anchors.anchors = BackupAnchors(materialized = anchor, highWater = anchor)

        val failure = failureOf { backup() }

        assertEquals(BackupUploadFailure.REMOTE_BACKUP_ROLLBACK, failure.failure)
        assertTrue(manifest.begins.isEmpty())
    }

    @Test
    fun olderServerPointerIsRejectedByTheHighWaterGuard() = runBlocking {
        val high = BackupPair(5, "sha-5")
        anchors.anchors = BackupAnchors(materialized = high, highWater = high)
        manifest.pointer = BackupPair(4, "sha-4")

        val failure = failureOf { backup() }

        assertEquals(BackupUploadFailure.REMOTE_BACKUP_ROLLBACK, failure.failure)
        assertEquals(BackupPair(4, "sha-4"), failure.current)
        assertTrue(manifest.begins.isEmpty())
    }

    @Test
    fun sameRevisionWithDifferentBytesIsEquivocation() = runBlocking {
        val high = BackupPair(5, "sha-5")
        anchors.anchors = BackupAnchors(materialized = high, highWater = high)
        manifest.pointer = BackupPair(5, "different")

        val failure = failureOf { backup() }

        assertEquals(BackupUploadFailure.REMOTE_BACKUP_EQUIVOCATION, failure.failure)
        assertTrue(manifest.begins.isEmpty())
    }

    @Test
    fun explicitReplaceBindsTheExactConfirmedPointer() = runBlocking {
        val anchor = BackupPair(3, "sha-3")
        anchors.anchors = BackupAnchors(materialized = anchor, highWater = anchor)
        val current = BackupPair(4, "sha-4")
        manifest.pointer = current
        manifest.commitRevision = 5

        val result = backup(replace = current)

        val begin = manifest.begins.single()
        assertTrue(begin.replaceCurrent)
        assertEquals(current.revision, begin.replaceRevision)
        assertEquals(current.sha256, begin.replaceSha256)
        assertEquals(5, result.revision)
        assertEquals(BackupPair(5, result.sha256), anchors.anchors.materialized)
        assertEquals(BackupPair(5, result.sha256), anchors.anchors.highWater)
    }

    @Test
    fun replaceCannotTargetAPointerBelowTheHighWater() = runBlocking {
        val high = BackupPair(3, "sha-3")
        anchors.anchors = BackupAnchors(materialized = high, highWater = high)

        val failure = failureOf { backup(replace = BackupPair(2, "sha-2")) }

        assertEquals(BackupUploadFailure.REMOTE_BACKUP_ROLLBACK, failure.failure)
        assertTrue(manifest.begins.isEmpty())
    }

    @Test
    fun lostUploadResponseStillCommitsTheBytesAlreadyOnTheServer() = runBlocking {
        uploads.failWith = BackupUploadException(BackupUploadFailure.NETWORK)

        val result = backup()

        // The manifest's own hash check decides: the bytes did arrive, so the backup landed.
        assertEquals(1, manifest.commitCalls)
        assertEquals(BackupPair(result.revision, result.sha256), anchors.anchors.materialized)
        assertNull(sessions.session)
        assertTrue(stagingRoot.listFiles().orEmpty().none { it.name.startsWith("object-") })
    }

    @Test
    fun identityChangeDuringUploadStopsBeforeCommit() = runBlocking {
        uploads.onSent = { fenceValue = SessionFingerprint(uid, 2) }

        val failure = failureOf { backup() }

        assertEquals(BackupUploadFailure.SESSION_CHANGED, failure.failure)
        assertEquals(0, manifest.commitCalls)
        assertNull(anchors.anchors.materialized)
    }

    @Test
    fun emptyLocalStateIsNeverUploadedOverACloudBackup() = runBlocking {
        snapshot = GuestSnapshot(
            habits = emptyList(),
            streaks = emptyList(),
            milestones = emptyList(),
            attempts = emptyList(),
            sessions = emptyList(),
            coaching = emptyList(),
            riskWindows = emptyList(),
            policy = null,
        )

        val failure = failureOf { backup() }

        assertEquals(BackupUploadFailure.NO_LOCAL_DATA, failure.failure)
        assertTrue(manifest.begins.isEmpty())
        assertTrue(uploads.objectPaths.isEmpty())
    }

    @Test
    fun savedSessionResumesWithoutANewReservation() = runBlocking {
        val backupId = "00112233445566778899aabbccddeeff"
        val file = staging.objectFile(backupId)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(64) { it.toByte() })
        sessions.session = BackupUploadSession(
            uid = uid,
            generation = activeFence.generation,
            backupId = backupId,
            objectPath = "backups/$uid/$backupId.bin",
            sha256 = file.sha256Hex(),
            objectBytes = file.length(),
            compressedBytes = 32,
            uploaded = false,
            sessionUri = "https://upload.example/session-old",
        )
        manifest.commitRevision = 2

        val result = backup()

        assertTrue(manifest.begins.isEmpty())
        // §8.6: a saved session is extended before the resume, because it may be close to
        // the 30-minute idle expiry.
        assertEquals(1, manifest.extendCalls)
        assertEquals("https://upload.example/session-old", uploads.lastSessionUri)
        assertNull(sessions.session)
        assertEquals(2, result.revision)
        assertFalse(file.exists())
    }

    @Test
    fun savedSessionBoundToAnotherIdentityIsDiscarded() = runBlocking {
        val staleId = "ffeeddccbbaa99887766554433221100"
        staging.objectFile(staleId).writeBytes(ByteArray(8))
        sessions.session = BackupUploadSession(
            uid = "someone-else",
            generation = 9,
            backupId = staleId,
            objectPath = "backups/someone-else/$staleId.bin",
            sha256 = "sha-stale",
            objectBytes = 8,
            compressedBytes = 4,
            uploaded = false,
            sessionUri = null,
        )

        backup()

        // The foreign record and its object are gone; this backup made a fresh reservation.
        assertEquals(1, manifest.begins.size)
        assertFalse(staging.objectFile(staleId).exists())
    }
}
