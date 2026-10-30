package com.example.myexodus.backup

import com.example.myexodus.account.GuestSnapshot
import com.example.myexodus.account.Relauncher
import com.example.myexodus.account.SessionFence
import com.example.myexodus.account.SessionFingerprint
import com.example.myexodus.account.isEmpty
import com.example.myexodus.backup.BackupHighWater.Advance
import com.example.myexodus.backup.BackupKeyManager.Unlock
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RiskWindow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** User-visible phases of one restore (§6.6 items 4-8). */
sealed interface RestoreProgress {
    data class Downloading(val transferred: Long, val total: Long) : RestoreProgress
    data object Verifying : RestoreProgress
    data object Staging : RestoreProgress
}

/** What the confirm card shows; every count already passed relationship validation. */
data class RestoreSummary(
    val habits: Int,
    val streaks: Int,
    val milestones: Int,
    val attempts: Int,
    val sessions: Int,
    val coaching: Int,
    val riskWindows: Int,
    val hasPolicy: Boolean,
) {
    val total: Int get() = habits + streaks + milestones + attempts + sessions + coaching + riskWindows
}

/**
 * §6.6 item 8: the authenticated snapshot already written into the staging database. Nothing
 * the user owns has changed yet — [BackupRestores.apply] performs the swap, and only the swap
 * is irreversible.
 */
data class RestorePreview(
    val fingerprint: SessionFingerprint,
    val pair: BackupPair,
    val summary: RestoreSummary,
    /** §6.6 item 10: no trusted local anchor existed, so the revision is server-reported. */
    val serverReported: Boolean,
)

/** The restore surface the settings pane drives; a seam so the pane is testable without a cloud. */
interface BackupRestores {
    suspend fun prepare(
        unlock: Unlock,
        onProgress: (RestoreProgress) -> Unit = {},
    ): RestorePreview

    /** §6.6 item 8: atomic swap, completion mark, then the process relaunch. */
    suspend fun apply(preview: RestorePreview)

    /** Keep Local / Cancel: the active file was never touched beyond the staging copy. */
    suspend fun discard()
}

/**
 * Account plan §6.6 items 1-10: fetches the current committed snapshot, proves the object and
 * every decrypted byte before parsing, validates the logical graph, and writes it into a
 * staging copy of the active database. The active file is closed for the byte copy so the
 * staging database is a consistent snapshot that keeps every device-local table (rules, usage,
 * pairing, profile); only the eight logical tables are replaced. The high-water anchor is
 * compared before anything is downloaded and is recorded into the staging copy, never into the
 * active file — a discarded or failed restore can therefore never lower it.
 */
class BackupRestorer(
    private val keys: BackupKeyManager,
    private val manifest: BackupManifestApi,
    private val anchors: RemoteBackupAnchorStore,
    private val staging: BackupStaging,
    private val downloads: BackupObjectDownloader,
    private val storeFactory: AppRulesStoreFactory,
    private val fence: SessionFence,
    private val rules: RulesRepository,
    private val mark: BackupRestoreMarkStore,
    private val relauncher: Relauncher,
    private val clock: () -> Long = System::currentTimeMillis,
) : BackupRestores {

    override suspend fun prepare(
        unlock: Unlock,
        onProgress: (RestoreProgress) -> Unit,
    ): RestorePreview {
        val fingerprint = requireFence()
        var backupId: String? = null
        var pinned = false
        var stagingStore: AppRulesStore? = null
        try {
            val remote = loadBundle(fingerprint.uid)
            val snapshot = snapshotOf(fingerprint.uid)
            if (snapshot.schemaVersion != BackupPayload.VERSION) {
                throw BackupUploadException(BackupUploadFailure.REMOTE_BACKUP_INVALID)
            }
            // §6.6 item 3: a replay of an older-but-consistent snapshot stops here, before any
            // download. A new device has no anchor and is told the revision is server-reported.
            val serverReported = guardHighWater(snapshot.pair)
            backupId = snapshot.backupId
            staging.pruneRestore(snapshot.backupId)
            val dir = staging.restoreDir(snapshot.backupId)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw BackupUploadException(BackupUploadFailure.STAGING_FAILED)
            }
            try {
                manifest.beginRestore(snapshot.backupId)
                pinned = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw if (e is BackupUploadException) e else localFailure(e)
            }

            val objectFile = staging.restoreObjectFile(snapshot.backupId)
            onProgress(RestoreProgress.Downloading(0, snapshot.objectBytes))
            try {
                downloads.download(snapshot.objectPath, objectFile) { transferred, total ->
                    onProgress(RestoreProgress.Downloading(transferred, total))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw BackupUploadException(
                    storageFailure(e, BackupUploadFailure.BACKUP_OBJECT_MISSING),
                    cause = e,
                )
            }
            onProgress(RestoreProgress.Verifying)
            withContext(Dispatchers.IO) {
                // §6.6 item 4: length and the manifest's SHA-256 over the exact object bytes.
                if (objectFile.length() != snapshot.objectBytes ||
                    objectFile.sha256Hex() != snapshot.pair.sha256
                ) {
                    throw BackupUploadException(BackupUploadFailure.REMOTE_BACKUP_INVALID)
                }
            }

            val payloadFile = staging.restorePayloadFile(snapshot.backupId)
            when (val opened = decrypt(remote, unlock, fingerprint.uid, objectFile, payloadFile)) {
                is BackupSnapshotCipher.OpenResult.Rejected -> throw BackupUploadException(
                    if (opened.reason == BackupSnapshotCipher.RejectReason.TAMPERED) {
                        BackupUploadFailure.PAYLOAD_TAMPERED
                    } else {
                        BackupUploadFailure.REMOTE_BACKUP_INVALID
                    },
                )
                is BackupSnapshotCipher.OpenResult.Opened ->
                    verifyHeader(opened.header, snapshot, remote)
            }

            val data = withContext(Dispatchers.IO) { parse(payloadFile, snapshot) }
            data.validateRestorePayload()
            if (data.isEmpty()) throw BackupUploadException(BackupUploadFailure.PAYLOAD_CORRUPT)
            // §6.6 item 6: authenticated or not, only the staging database survives this call.
            withContext(Dispatchers.IO) {
                try {
                    staging.deleteOrRetry(payloadFile)
                    staging.deleteOrRetry(objectFile)
                } catch (e: IOException) {
                    throw BackupUploadException(BackupUploadFailure.STAGING_FAILED, cause = e)
                }
            }

            val profileId = try {
                rules.ensureActiveProfile().userId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw BackupUploadException(BackupUploadFailure.LOCAL_STATE_UNAVAILABLE, cause = e)
            }
            onProgress(RestoreProgress.Staging)
            stagingStore = try {
                storeFactory.stageRestoreCopy()
            } catch (e: Exception) {
                throw BackupUploadException(BackupUploadFailure.STAGING_FAILED, cause = e)
            }
            try {
                stagingStore.writeRestoredSnapshot(data, profileId)
                // §6.6 item 3: the materialized pair travels with the snapshot it describes.
                RoomBackupAnchorStore.on(stagingStore).recordCommitted(snapshot.pair)
            } finally {
                stagingStore.close()
                stagingStore = null
            }
            if (!storeFactory.verifyRestoreStaging()) {
                throw BackupUploadException(BackupUploadFailure.STAGING_FAILED)
            }
            // The active file was closed for the copy; every other component gets its handle back.
            runCatching { storeFactory.open() }
            endRestore(snapshot.backupId)
            return RestorePreview(fingerprint, snapshot.pair, summaryOf(data), serverReported)
        } catch (e: Exception) {
            cleanupAfterFailure(backupId, pinned, stagingStore)
            throw mapFailure(e)
        }
    }

    override suspend fun apply(preview: RestorePreview) {
        requireFence(preview.fingerprint)
        if (!storeFactory.swapInRestore()) {
            runCatching { storeFactory.discardRestoreStaging() }
            runCatching { storeFactory.open() }
            throw BackupUploadException(BackupUploadFailure.STAGING_FAILED)
        }
        // §6.6 item 9: the swap kills this process, so the completion is persisted first.
        runCatching {
            mark.save(
                BackupRestoreMark(
                    uid = preview.fingerprint.uid,
                    revision = preview.pair.revision,
                    sha256 = preview.pair.sha256,
                    completedAtMs = clock(),
                ),
            )
        }
        relauncher.relaunch()
    }

    override suspend fun discard() {
        storeFactory.discardRestoreStaging()
        storeFactory.open()
    }

    private suspend fun loadBundle(uid: String): RemoteBundle = try {
        keys.load(uid) ?: throw BackupUploadException(BackupUploadFailure.NO_CLOUD_BACKUP)
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupUploadException) {
        throw e
    } catch (e: BackupKeyException) {
        throw when (e.failure) {
            BackupKeyFailure.WRONG_SECRET -> BackupUploadException(
                BackupUploadFailure.WRONG_SECRET, cause = e,
            )
            BackupKeyFailure.ROLLBACK -> BackupUploadException(
                BackupUploadFailure.REMOTE_BACKUP_INVALID, cause = e,
            )
            else -> BackupUploadException(BackupUploadFailure.UNKNOWN, cause = e)
        }
    } catch (e: Exception) {
        throw localFailure(e)
    }

    private suspend fun snapshotOf(uid: String): RemoteSnapshot = try {
        manifest.snapshot(uid) ?: throw BackupUploadException(BackupUploadFailure.NO_CLOUD_BACKUP)
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupUploadException) {
        throw e
    } catch (e: Exception) {
        throw localFailure(e)
    }

    /**
     * §6.6 item 3: never lower the high-water pair, even for an explicitly chosen snapshot.
     * Returns true when this installation had no anchor at all (§6.6 item 10).
     */
    private suspend fun guardHighWater(observed: BackupPair): Boolean {
        val stored = try {
            anchors.read()
        } catch (e: Exception) {
            throw BackupUploadException(BackupUploadFailure.LOCAL_STATE_UNAVAILABLE, cause = e)
        }
        val accepted = when (val advance = BackupHighWater.advance(stored.highWater, observed)) {
            is Advance.Accepted -> advance.highWater
            Advance.Rollback -> throw BackupUploadException(
                BackupUploadFailure.REMOTE_BACKUP_ROLLBACK, current = observed,
            )
            Advance.Equivocation -> throw BackupUploadException(
                BackupUploadFailure.REMOTE_BACKUP_EQUIVOCATION, current = observed,
            )
        }
        try {
            anchors.recordObserved(accepted)
        } catch (e: Exception) {
            throw BackupUploadException(BackupUploadFailure.LOCAL_STATE_UNAVAILABLE, cause = e)
        }
        return stored.highWater == null
    }

    private suspend fun decrypt(
        remote: RemoteBundle,
        unlock: Unlock,
        uid: String,
        objectFile: File,
        payloadFile: File,
    ): BackupSnapshotCipher.OpenResult = try {
        keys.withAbk(uid, remote, unlock) { abk ->
            withContext(Dispatchers.IO) {
                FileInputStream(objectFile).use { input ->
                    FileOutputStream(payloadFile).use { out ->
                        BackupSnapshotCipher.open(input, abk, uid, out)
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupKeyException) {
        throw when (e.failure) {
            BackupKeyFailure.WRONG_SECRET -> BackupUploadException(
                BackupUploadFailure.WRONG_SECRET, cause = e,
            )
            else -> BackupUploadException(BackupUploadFailure.UNKNOWN, cause = e)
        }
    } catch (e: IOException) {
        throw BackupUploadException(BackupUploadFailure.STAGING_FAILED, cause = e)
    }

    private fun verifyHeader(
        header: BackupEnvelope.Header,
        snapshot: RemoteSnapshot,
        remote: RemoteBundle,
    ) {
        fun invalid(): Nothing =
            throw BackupUploadException(BackupUploadFailure.REMOTE_BACKUP_INVALID)
        if (header.backupId.toLowerHex() != snapshot.backupId) invalid()
        if (!header.keyId.contentEquals(remote.bundle.keyId)) invalid()
        if (header.schemaVersion != snapshot.schemaVersion) invalid()
        if (header.envelopeVersion != snapshot.envelopeVersion) invalid()
        if (header.payloadCiphertextLength !=
            snapshot.compressedBytes + BackupEnvelope.TAG_BYTES
        ) {
            invalid()
        }
    }

    private fun parse(payloadFile: File, snapshot: RemoteSnapshot): GuestSnapshot {
        val collector = SnapshotCollector()
        try {
            FileInputStream(payloadFile).use { input ->
                BackupPayload.read(input, snapshot.compressedBytes, collector)
            }
        } catch (e: BackupPayloadException.TooLarge) {
            throw BackupUploadException(BackupUploadFailure.PAYLOAD_TOO_LARGE, cause = e)
        } catch (e: BackupPayloadException.Corrupt) {
            throw BackupUploadException(BackupUploadFailure.PAYLOAD_CORRUPT, cause = e)
        } catch (e: IOException) {
            throw BackupUploadException(BackupUploadFailure.STAGING_FAILED, cause = e)
        }
        return collector.snapshot()
    }

    private fun summaryOf(data: GuestSnapshot) = RestoreSummary(
        habits = data.habits.size,
        streaks = data.streaks.size,
        milestones = data.milestones.size,
        attempts = data.attempts.size,
        sessions = data.sessions.size,
        coaching = data.coaching.size,
        riskWindows = data.riskWindows.size,
        hasPolicy = data.policy != null,
    )

    private suspend fun endRestore(backupId: String) {
        runCatching { manifest.endRestore(backupId) }
    }

    /** §6.6 item 6: every failure path deletes what this attempt created and reopens the active file. */
    private suspend fun cleanupAfterFailure(
        backupId: String?,
        pinned: Boolean,
        stagingStore: AppRulesStore?,
    ) = withContext(NonCancellable) {
        runCatching { stagingStore?.close() }
        if (pinned && backupId != null) runCatching { manifest.endRestore(backupId) }
        runCatching { storeFactory.discardRestoreStaging() }
        runCatching { storeFactory.open() }
        if (backupId != null) runCatching { staging.restoreDir(backupId).deleteRecursively() }
    }

    private fun mapFailure(e: Throwable): BackupUploadException = when (e) {
        is BackupUploadException -> e
        is CancellationException -> throw e
        is BackupPayloadException.TooLarge ->
            BackupUploadException(BackupUploadFailure.PAYLOAD_TOO_LARGE, cause = e)
        is BackupPayloadException.Corrupt ->
            BackupUploadException(BackupUploadFailure.PAYLOAD_CORRUPT, cause = e)
        is BackupKeyException -> if (e.failure == BackupKeyFailure.WRONG_SECRET) {
            BackupUploadException(BackupUploadFailure.WRONG_SECRET, cause = e)
        } else {
            BackupUploadException(BackupUploadFailure.UNKNOWN, cause = e)
        }
        is BackupAnchorUnavailableException ->
            BackupUploadException(BackupUploadFailure.LOCAL_STATE_UNAVAILABLE, cause = e)
        else -> BackupUploadException(BackupUploadFailure.STAGING_FAILED, cause = e)
    }

    private fun localFailure(e: Exception): BackupUploadException = when (e) {
        is BackupUploadException -> e
        is BackupAnchorUnavailableException ->
            BackupUploadException(BackupUploadFailure.LOCAL_STATE_UNAVAILABLE, cause = e)
        else -> BackupUploadException(BackupUploadFailure.UNKNOWN, cause = e)
    }

    private fun requireFence(): SessionFingerprint =
        fence.current() ?: throw BackupUploadException(BackupUploadFailure.SIGN_IN_REQUIRED)

    private fun requireFence(fingerprint: SessionFingerprint) {
        if (!fence.isCurrent(fingerprint)) {
            throw BackupUploadException(BackupUploadFailure.SESSION_CHANGED)
        }
    }

    private class SnapshotCollector : SnapshotSink {
        val habits = mutableListOf<HabitProgress>()
        val streaks = mutableListOf<RecoveryStreak>()
        val milestones = mutableListOf<MilestoneEvent>()
        val attempts = mutableListOf<AttemptLog>()
        val sessions = mutableListOf<RecoverySession>()
        val coaching = mutableListOf<CoachingStep>()
        val riskWindows = mutableListOf<RiskWindow>()
        var policy: RecoveryPolicy? = null

        override fun habit(value: HabitProgress) {
            habits += value
        }

        override fun streak(value: RecoveryStreak) {
            streaks += value
        }

        override fun milestone(value: MilestoneEvent) {
            milestones += value
        }

        override fun attempt(value: AttemptLog) {
            attempts += value
        }

        override fun session(value: RecoverySession) {
            sessions += value
        }

        override fun coaching(value: CoachingStep) {
            coaching += value
        }

        override fun riskWindow(value: RiskWindow) {
            riskWindows += value
        }

        override fun policy(value: RecoveryPolicy) {
            this.policy = value
        }

        fun snapshot() = GuestSnapshot(
            habits = habits,
            streaks = streaks,
            milestones = milestones,
            attempts = attempts,
            sessions = sessions,
            coaching = coaching,
            riskWindows = riskWindows,
            policy = policy,
        )
    }
}
