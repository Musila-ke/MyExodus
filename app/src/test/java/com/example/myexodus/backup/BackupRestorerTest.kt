package com.example.myexodus.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.account.GuestSnapshot
import com.example.myexodus.account.Relauncher
import com.example.myexodus.account.SessionFence
import com.example.myexodus.account.SessionFingerprint
import com.example.myexodus.backup.BackupKeyManager.Unlock
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.RulesSnapshotPublisher
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.RestrictionRule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Account plan §6.6 items 2-10: the restore proves the server pointer against the local
 * high-water pair before downloading, proves the exact object bytes and the GCM tag before
 * parsing, writes only into a staging copy of the active database, and swaps the file only on
 * the user's explicit confirmation. Real Argon2id, real envelope crypto and a real staging
 * database; the fakes replace only the network, the anchors and the process relaunch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupRestorerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val uid = "uid-fixture"
    private val passphrase = "7Gx!q2-Lm9@Rv4#Tz8"
    private val backupId = "00112233445566778899aabbccddeeff"
    private val clockMs = 1_800_000_000_000L

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var rules: RulesRepository
    private lateinit var manager: BackupKeyManager
    private lateinit var remote: RemoteBundle
    private lateinit var manifest: FakeManifest
    private lateinit var anchors: FakeAnchors
    private lateinit var downloads: FakeDownloads
    private lateinit var marks: FakeMarks
    private lateinit var staging: BackupStaging
    private lateinit var restorer: BackupRestorer

    private var relaunches = 0
    private var fenceValue: SessionFingerprint? = SessionFingerprint(uid, 1)
    private var compressedBytes = 0L
    private lateinit var committed: ByteArray

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
        var snapshot: RemoteSnapshot? = null
        val restoreBegins = mutableListOf<String>()
        val restoreEnds = mutableListOf<String>()

        override suspend fun current(uid: String) = snapshot?.pair

        override suspend fun snapshot(uid: String): RemoteSnapshot? = snapshot

        override suspend fun begin(request: BeginBackupRequest): BeginBackupResult =
            error("upload path unused")

        override suspend fun extend(backupId: String): ExtendReservationResult =
            error("upload path unused")

        override suspend fun commit(
            backupId: String,
            sha256: String,
            objectBytes: Long,
        ): CommitManifestResult = error("upload path unused")

        override suspend fun beginRestore(backupId: String) {
            restoreBegins += backupId
        }

        override suspend fun endRestore(backupId: String) {
            restoreEnds += backupId
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

    private class FakeDownloads : BackupObjectDownloader {
        var served: ByteArray? = null
        val requests = mutableListOf<String>()

        override suspend fun download(
            objectPath: String,
            target: File,
            onProgress: (transferred: Long, total: Long) -> Unit,
        ) {
            requests += objectPath
            val bytes = requireNotNull(served) { "test must set the served bytes" }
            target.writeBytes(bytes)
            onProgress(0, bytes.size.toLong())
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    private class FakeMarks : BackupRestoreMarkStore {
        var mark: BackupRestoreMark? = null
        override suspend fun get(): BackupRestoreMark? = mark
        override suspend fun save(mark: BackupRestoreMark) {
            this.mark = mark
        }

        override suspend fun clear() {
            mark = null
        }
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context),
            RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        rules = RulesRepository(factory, RulesSnapshotPublisher(context, factory))
        manager = BackupKeyManager(FakeKeyApi(), FakeRevisions(), BackupWordlists(context))
        remote = manager.create(uid, passphrase).remote

        manifest = FakeManifest()
        anchors = FakeAnchors()
        downloads = FakeDownloads()
        marks = FakeMarks()
        staging = BackupStaging(temp.newFolder("staging"))
        restorer = BackupRestorer(
            keys = manager,
            manifest = manifest,
            anchors = anchors,
            staging = staging,
            downloads = downloads,
            storeFactory = factory,
            fence = SessionFence { fenceValue },
            rules = rules,
            mark = marks,
            relauncher = Relauncher { relaunches++ },
            clock = { clockMs },
        )

        val sealed = seal(cloudSnapshot())
        committed = sealed.bytes
        compressedBytes = sealed.compressedBytes
        manifest.snapshot = snapshotOf(committed)
        downloads.served = committed
    }

    @After
    fun tearDown() = recovery.resetAllData()

    private class Sealed(val bytes: ByteArray, val compressedBytes: Long)

    private suspend fun seal(snapshot: GuestSnapshot, id: String = backupId): Sealed {
        val compressed = ByteArrayOutputStream()
        val sizes = BackupPayload.write(snapshot, compressed)
        val bytes = manager.withAbk(uid, remote, Unlock.Passphrase(passphrase)) { abk ->
            ByteArrayOutputStream().also { out ->
                BackupSnapshotCipher.seal(
                    compressed = ByteArrayInputStream(compressed.toByteArray()),
                    compressedLength = sizes.compressedBytes,
                    abk = abk,
                    uid = uid,
                    backupId = id.hexBytes(),
                    keyId = remote.bundle.keyId,
                    schemaVersion = BackupPayload.VERSION,
                    out = out,
                )
            }.toByteArray()
        }
        return Sealed(bytes, sizes.compressedBytes)
    }

    private fun shaOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun snapshotOf(
        bytes: ByteArray,
        revision: Long = 4,
        sha256: String = shaOf(bytes),
    ) = RemoteSnapshot(
        pair = BackupPair(revision, sha256),
        backupId = backupId,
        objectPath = "backups/$uid/$backupId",
        generation = "generation-1",
        objectBytes = bytes.size.toLong(),
        compressedBytes = compressedBytes,
        schemaVersion = BackupPayload.VERSION,
        envelopeVersion = BackupEnvelope.VERSION,
    )

    private fun cloudSnapshot() = GuestSnapshot(
        habits = listOf(habit("h-cloud", profileId = "profile-remote")),
        streaks = emptyList(),
        milestones = emptyList(),
        attempts = emptyList(),
        sessions = emptyList(),
        coaching = listOf(
            CoachingStep(
                stepId = "step-cloud",
                profileId = "profile-remote",
                stepOrder = 1,
                actionText = "Breathe for one minute",
                associatedTriggerWindow = "",
            ),
        ),
        riskWindows = emptyList(),
        policy = null,
    )

    private fun habit(id: String, profileId: String, days: Int = 7) = HabitProgress(
        progressId = id,
        profileId = profileId,
        targetIdentifier = "Porn",
        currentCleanDaysStreak = days,
        totalRelapses = 1,
        accessAttemptsCount = 2,
        habitDurationYears = 3,
        onboardingSeverityLevel = "MODERATE",
        lastRelapseTimestamp = null,
        assignedPartnershipId = null,
        allTimeMaxStreakElapsedMs = 604_800_000L,
        isSelected = true,
    )

    private suspend fun seedLocalState(): String {
        val profile = rules.ensureActiveProfile()
        factory.open()!!.habitProgressDao().insert(habit("h-local", profile.userId, days = 1))
        rules.insertRule(
            RestrictionRule(
                ruleId = "r-local",
                profileId = profile.userId,
                blockedPackage = "com.instagram.android",
                targetType = "APP",
                isAlwaysOn = true,
                dailyLimitMs = 0L,
                timeRangeStart = null,
                timeRangeEnd = null,
                activeDaysOfWeek = "1111111",
                timezone = "UTC",
                blockExpiresAt = 0L,
                severityLevel = "HIGH",
            ),
        )
        return profile.userId
    }

    private suspend fun activeHabitIds(): List<String> =
        factory.open()!!.habitProgressDao().getAll().map { it.progressId }.sorted()

    private suspend fun activeRuleIds(): List<String> =
        factory.open()!!.restrictionRuleDao().getActiveRules().map { it.ruleId }

    private suspend fun prepare() = restorer.prepare(Unlock.Passphrase(passphrase))

    private fun failureOf(block: suspend () -> Unit): BackupUploadException =
        assertThrows(BackupUploadException::class.java) { runBlocking { block() } }

    @Test
    fun prepareStagesTheSnapshotWithoutTouchingTheActiveFile() = runBlocking {
        seedLocalState()
        staging.restoreDir("ffeeddccbbaa99887766554433221100").also {
            it.mkdirs()
            File(it, "object.bin").writeBytes(ByteArray(8))
        }

        val preview = prepare()

        assertEquals(BackupPair(4, shaOf(committed)), preview.pair)
        assertEquals(SessionFingerprint(uid, 1), preview.fingerprint)
        assertTrue("no local anchor: the revision is server-reported", preview.serverReported)
        assertEquals(1, preview.summary.habits)
        assertEquals(1, preview.summary.coaching)
        assertEquals(2, preview.summary.total)

        // The active file is untouched; the snapshot only reached the staging copy.
        assertEquals(listOf("h-local"), activeHabitIds())
        assertEquals(listOf("r-local"), activeRuleIds())
        assertTrue(factory.verifyRestoreStaging())
        assertFalse(staging.restoreObjectFile(backupId).exists())
        assertFalse(staging.restorePayloadFile(backupId).exists())
        // §6.6 item 6: another attempt's staging directory is swept.
        assertFalse(staging.restoreDir("ffeeddccbbaa99887766554433221100").exists())

        assertEquals(listOf(backupId), manifest.restoreBegins)
        assertEquals(listOf(backupId), manifest.restoreEnds)
        assertEquals(BackupPair(4, shaOf(committed)), anchors.anchors.highWater)
        assertNull("materialized only after the swap", anchors.anchors.materialized)
        assertNull(marks.mark)
        assertEquals(0, relaunches)
    }

    @Test
    fun olderServerRevisionIsRejectedBeforeAnyDownload() = runBlocking {
        val high = BackupPair(5, "sha-5")
        anchors.anchors = BackupAnchors(highWater = high)

        val failure = failureOf { prepare() }

        assertEquals(BackupUploadFailure.REMOTE_BACKUP_ROLLBACK, failure.failure)
        assertTrue(downloads.requests.isEmpty())
        assertTrue(manifest.restoreBegins.isEmpty())
        assertEquals(high, anchors.anchors.highWater)
    }

    @Test
    fun sameRevisionWithDifferentBytesIsEquivocation() = runBlocking {
        anchors.anchors = BackupAnchors(highWater = BackupPair(4, "different-sha"))

        val failure = failureOf { prepare() }

        assertEquals(BackupUploadFailure.REMOTE_BACKUP_EQUIVOCATION, failure.failure)
        assertTrue(downloads.requests.isEmpty())
        assertTrue(manifest.restoreBegins.isEmpty())
    }

    @Test
    fun transferThatDoesNotMatchTheManifestIsRejectedAndCleaned() = runBlocking {
        seedLocalState()
        downloads.served = committed.copyOf().also {
            it[it.size / 2] = (it[it.size / 2].toInt() + 1).toByte()
        }

        val failure = failureOf { prepare() }

        assertEquals(BackupUploadFailure.REMOTE_BACKUP_INVALID, failure.failure)
        assertFalse(staging.restoreDir(backupId).exists())
        assertEquals(listOf("h-local"), activeHabitIds())
        assertEquals(listOf(backupId), manifest.restoreEnds)
        assertEquals(BackupPair(4, shaOf(committed)), anchors.anchors.highWater)
    }

    @Test
    fun aCommittedObjectThatFailsTheGcmTagIsPayloadTampered() = runBlocking {
        // The server's own hash agrees with the tampered bytes: only the AEAD tag can tell.
        val tampered = committed.copyOf().also {
            it[it.size - 1] = (it[it.size - 1].toInt() + 1).toByte()
        }
        downloads.served = tampered
        manifest.snapshot = snapshotOf(tampered)

        val failure = failureOf { prepare() }

        assertEquals(BackupUploadFailure.PAYLOAD_TAMPERED, failure.failure)
        assertFalse(staging.restoreDir(backupId).exists())
        assertEquals(listOf(backupId), manifest.restoreEnds)
        assertNull(marks.mark)
    }

    @Test
    fun anAuthenticatedSnapshotWithBrokenRelationshipsIsRejectedCorrupt() = runBlocking {
        val broken = cloudSnapshot().copy(
            habits = listOf(
                habit("h-dup", profileId = "profile-remote"),
                habit("h-dup", profileId = "profile-remote"),
            ),
        )
        val sealed = seal(broken)
        downloads.served = sealed.bytes
        compressedBytes = sealed.compressedBytes
        manifest.snapshot = snapshotOf(sealed.bytes)

        val failure = failureOf { prepare() }

        assertEquals(BackupUploadFailure.PAYLOAD_CORRUPT, failure.failure)
        assertFalse(staging.restoreDir(backupId).exists())
        assertEquals(listOf(backupId), manifest.restoreEnds)
    }

    @Test
    fun knownDeviceWithAMatchingAnchorIsNotServerReported() = runBlocking {
        val pair = BackupPair(4, shaOf(committed))
        anchors.anchors = BackupAnchors(materialized = pair, highWater = pair)

        val preview = prepare()

        assertFalse(preview.serverReported)
        assertEquals(pair, anchors.anchors.highWater)
    }

    @Test
    fun applySwapsTheDatabaseAndSavesTheCompletionMark() = runBlocking {
        val profileId = seedLocalState()
        val preview = prepare()

        restorer.apply(preview)

        assertEquals(1, relaunches)
        assertEquals(
            BackupRestoreMark(uid, 4, shaOf(committed), clockMs),
            marks.mark,
        )
        val store: AppRulesStore = factory.open()!!
        assertEquals(listOf("h-cloud"), store.habitProgressDao().getAll().map { it.progressId })
        assertEquals(
            "restored rows belong to this device's active profile",
            profileId,
            store.habitProgressDao().getById("h-cloud")!!.profileId,
        )
        // Device-local tables never travel in the backup; the swap keeps them.
        assertEquals(listOf("r-local"), store.restrictionRuleDao().getActiveRules().map { it.ruleId })
        val swapped = RoomBackupAnchorStore(factory).read()
        assertEquals(preview.pair, swapped.materialized)
        assertEquals(preview.pair, swapped.highWater)
    }

    @Test
    fun discardLeavesTheActiveFileUntouched() = runBlocking {
        seedLocalState()
        prepare()

        restorer.discard()

        assertEquals(listOf("h-local"), activeHabitIds())
        assertEquals(listOf("r-local"), activeRuleIds())
        assertNull(marks.mark)
        assertEquals(0, relaunches)
    }

    @Test
    fun applyAfterTheIdentityChangedNeverSwaps() = runBlocking {
        seedLocalState()
        val preview = prepare()
        fenceValue = SessionFingerprint(uid, 2)

        val failure = failureOf { restorer.apply(preview) }

        assertEquals(BackupUploadFailure.SESSION_CHANGED, failure.failure)
        assertEquals(listOf("h-local"), activeHabitIds())
        assertNull(marks.mark)
        assertEquals(0, relaunches)
    }
}
