package com.example.myexodus.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.account.AuthCoordinator
import com.example.myexodus.account.AuthState
import com.example.myexodus.backup.BackupKeyException
import com.example.myexodus.backup.BackupKeyFailure
import com.example.myexodus.backup.BackupKeyManager
import com.example.myexodus.backup.BackupKeyManager.Unlock
import com.example.myexodus.backup.BackupPair
import com.example.myexodus.backup.BackupProgress
import com.example.myexodus.backup.BackupRestoreMark
import com.example.myexodus.backup.BackupRestoreMarkStore
import com.example.myexodus.backup.BackupRestores
import com.example.myexodus.backup.BackupUploadException
import com.example.myexodus.backup.BackupUploadFailure
import com.example.myexodus.backup.BackupUploads
import com.example.myexodus.backup.PassphrasePolicy
import com.example.myexodus.backup.RemoteBundle
import com.example.myexodus.backup.RestorePreview
import com.example.myexodus.backup.RestoreProgress
import com.example.myexodus.backup.RestoreSummary
import com.example.myexodus.di.IoDispatcher
import com.example.myexodus.di.WorkDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Release 2 (account plan §6.4/§6.5/§8.5): the backup-key pane. Holds the loaded bundle
 * revision, drives create/change/rotate through [BackupKeyManager] and keeps the recovery
 * mnemonic in memory only until the user acknowledges it. The KDF runs off the main thread.
 */
@HiltViewModel
class BackupKeyViewModel @Inject constructor(
    private val manager: BackupKeyManager,
    private val uploader: BackupUploads,
    private val auth: AuthCoordinator,
    private val restores: BackupRestores,
    private val marks: BackupRestoreMarkStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @WorkDispatcher private val workDispatcher: CoroutineDispatcher,
) : ViewModel() {

    sealed interface Status {
        data object Loading : Status
        data class Unavailable(val failure: BackupKeyFailure) : Status
        data object NotSetUp : Status
        data class Configured(val revision: Long) : Status
    }

    sealed interface Operation {
        data object Idle : Operation
        data object Working : Operation
        data class Failed(val failure: BackupKeyFailure) : Operation
        /** Shown once, after the wrappers were committed and self-tested. */
        data class Reveal(val words: List<String>, val setup: Boolean) : Operation
    }

    /** §6.4 items 11-13: the snapshot-upload state machine, as the pane presents it. */
    sealed interface Backup {
        data object Idle : Backup
        data class Running(val progress: BackupProgress) : Backup
        data class Succeeded(val revision: Long) : Backup
        /** §6.6 item 3: the exact pointer a replacement must be bound to. */
        data class Replace(val current: BackupPair) : Backup
        data class Failed(val failure: BackupUploadFailure, val retryable: Boolean) : Backup
    }

    private val _status = MutableStateFlow<Status>(Status.Loading)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _operation = MutableStateFlow<Operation>(Operation.Idle)
    val operation: StateFlow<Operation> = _operation.asStateFlow()

    private val _backup = MutableStateFlow<Backup>(Backup.Idle)
    val backup: StateFlow<Backup> = _backup.asStateFlow()

    /** §6.6 items 4-9: the restore flow as the pane presents it. */
    sealed interface Restore {
        data object Idle : Restore
        data class Running(val progress: RestoreProgress) : Restore
        /** §6.6 item 8: the staged snapshot, awaiting an explicit choice; nothing is swapped yet. */
        data class Preview(
            val summary: RestoreSummary,
            val revision: Long,
            /** §6.6 item 10: a new device has no trusted anchor; the revision is server-reported. */
            val serverReported: Boolean,
        ) : Restore
        data object Applied : Restore
        data class Failed(val failure: BackupUploadFailure, val retryable: Boolean) : Restore
    }

    private val _restore = MutableStateFlow<Restore>(Restore.Idle)
    val restore: StateFlow<Restore> = _restore.asStateFlow()

    /** §6.6 item 9: the completion the relaunched process shows once, then clears. */
    private val _completedRestore = MutableStateFlow<BackupRestoreMark?>(null)
    val completedRestore: StateFlow<BackupRestoreMark?> = _completedRestore.asStateFlow()

    val authState: StateFlow<AuthState> = auth.state

    private var remote: RemoteBundle? = null
    private var job: Job? = null
    private var backupJob: Job? = null
    private var restoreJob: Job? = null
    // Held only while one backup flow runs: the ABK unwrap secret and the pointer the user
    // explicitly confirmed. Never persisted, dropped on every terminal outcome.
    private var pendingUnlock: Unlock? = null
    private var pendingReplace: BackupPair? = null
    // The staged snapshot apply() swaps in; only a Preview state ever holds one.
    private var pendingPreview: RestorePreview? = null
    private var pendingRestoreUnlock: Unlock? = null
    // §6.6 item 8: leaving the pane abandons the staged copy even though viewModelScope is dead.
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        refresh()
        viewModelScope.launch {
            _completedRestore.value = withContext(ioDispatcher) {
                runCatching { marks.get() }.getOrNull()
            }
        }
    }

    /** §8.5: a known device that sees an older revision than its anchor fails closed. */
    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _status.value = Status.Loading
            val uid = persistentUid()
            if (uid == null) {
                _status.value = Status.Unavailable(BackupKeyFailure.PERSISTENT_ACCOUNT_REQUIRED)
            } else {
                statusFromServer(uid)
            }
        }
    }

    fun checkPassphrase(passphrase: String, generated: Boolean = false): PassphrasePolicy.Result =
        manager.checkPassphrase(passphrase, generated)

    fun generatePassphrase(): String = manager.generatePassphrase()

    fun setUp(passphrase: String, generated: Boolean) = launchOperation { uid ->
        val created = manager.create(uid, passphrase, generated)
        remote = created.remote
        _status.value = Status.Configured(created.remote.revision)
        _operation.value = Operation.Reveal(created.recoveryWords, setup = true)
    }

    fun changePassphrase(
        unlock: Unlock,
        newPassphrase: String,
        generated: Boolean,
    ) = launchOperation { uid ->
        val current = remote ?: throw BackupKeyException(BackupKeyFailure.NOT_INITIALIZED)
        val updated = manager.changePassphrase(uid, current, unlock, newPassphrase, generated)
        remote = updated
        _status.value = Status.Configured(updated.revision)
    }

    /** §6.5: a lost recovery key is replaced with the current passphrase, never the old key. */
    fun rotateRecoveryKey(currentPassphrase: String) = launchOperation { uid ->
        val current = remote ?: throw BackupKeyException(BackupKeyFailure.NOT_INITIALIZED)
        val rotated = manager.rotateRecoveryKey(uid, current, currentPassphrase)
        remote = rotated.remote
        _status.value = Status.Configured(rotated.remote.revision)
        _operation.value = Operation.Reveal(rotated.recoveryWords, setup = false)
    }

    /** §6.4 items 5-13: creates one snapshot with the user's secret for this operation only. */
    fun backUpNow(unlock: Unlock) {
        pendingUnlock = unlock
        pendingReplace = null
        startBackup(unlock, replace = null)
    }

    /** §6.4 item 11: replaces exactly the cloud pointer the user was shown. */
    fun replaceCloudBackup() {
        val confirmed = (_backup.value as? Backup.Replace)?.current ?: return
        val unlock = pendingUnlock ?: return
        pendingReplace = confirmed
        startBackup(unlock, confirmed)
    }

    /** A retry reuses the secret and confirmed pointer still held by this flow. */
    fun retryBackup() {
        val unlock = pendingUnlock ?: return
        startBackup(unlock, pendingReplace)
    }

    /** The upload session survives, so the next attempt resumes instead of restarting. */
    fun cancelBackup() {
        if (_backup.value !is Backup.Running) return
        backupJob?.cancel()
        clearBackupSecrets()
        _backup.value = Backup.Idle
    }

    fun dismissBackup() {
        if (_backup.value is Backup.Running) return
        clearBackupSecrets()
        _backup.value = Backup.Idle
    }

    /** §6.6 items 2-8: fetches, proves and stages the cloud snapshot; no local data changes yet. */
    fun restoreFromCloud(unlock: Unlock) {
        pendingRestoreUnlock = unlock
        startRestore(unlock)
    }

    /** §6.6 item 8: the only irreversible step; the process relaunches on success. */
    fun confirmRestore() {
        val preview = pendingPreview ?: return
        if (_restore.value !is Restore.Preview) return
        pendingPreview = null
        restoreJob?.cancel()
        restoreJob = viewModelScope.launch {
            try {
                withContext(ioDispatcher) { restores.apply(preview) }
                pendingRestoreUnlock = null
                _restore.value = Restore.Applied
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // apply() already discarded staging and reopened the active file.
                _restore.value = Restore.Failed(
                    (e as? BackupUploadException)?.failure ?: BackupUploadFailure.STAGING_FAILED,
                    retryable = true,
                )
            }
        }
    }

    /** §6.6 item 8: Keep Local and Cancel both exit without restoring; both are non-destructive. */
    fun discardRestore() {
        if (_restore.value is Restore.Running) return
        restoreJob?.cancel()
        restoreJob = viewModelScope.launch {
            runCatching { withContext(ioDispatcher) { restores.discard() } }
            abandonRestore()
        }
    }

    /** A running restore stops; the restorer deletes every staged byte before returning. */
    fun cancelRestore() {
        if (_restore.value !is Restore.Running) return
        restoreJob?.cancel()
        restoreJob = viewModelScope.launch {
            runCatching { withContext(ioDispatcher) { restores.discard() } }
            abandonRestore()
        }
    }

    fun retryRestore() {
        val unlock = pendingRestoreUnlock ?: return
        startRestore(unlock)
    }

    fun dismissRestoreFailure() {
        pendingRestoreUnlock = null
        _restore.value = Restore.Idle
    }

    /** §6.6 item 9: the completion card is shown once per relaunch, then cleared for good. */
    fun dismissCompletedRestore() {
        if (_completedRestore.value == null) return
        _completedRestore.value = null
        viewModelScope.launch { runCatching { withContext(ioDispatcher) { marks.clear() } } }
    }

    private fun startRestore(unlock: Unlock) {
        restoreJob?.cancel()
        if (persistentUid() == null) {
            pendingRestoreUnlock = null
            _restore.value = Restore.Failed(BackupUploadFailure.SIGN_IN_REQUIRED, retryable = false)
            return
        }
        restoreJob = viewModelScope.launch {
            _restore.value = Restore.Running(RestoreProgress.Downloading(0, 0))
            try {
                // The ABK unwrap runs Argon2 and the download hashes the object: never on main.
                val preview = withContext(workDispatcher) {
                    restores.prepare(unlock) { progress ->
                        _restore.value = Restore.Running(progress)
                    }
                }
                pendingPreview = preview
                pendingRestoreUnlock = null
                _restore.value = Restore.Preview(
                    summary = preview.summary,
                    revision = preview.pair.revision,
                    serverReported = preview.serverReported,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupUploadException) {
                val retryable = e.failure in RESTORE_RETRYABLE
                if (!retryable) pendingRestoreUnlock = null
                _restore.value = Restore.Failed(e.failure, retryable)
            } catch (e: Exception) {
                pendingRestoreUnlock = null
                _restore.value = Restore.Failed(BackupUploadFailure.UNKNOWN, retryable = false)
            }
        }
    }

    private fun abandonRestore() {
        pendingPreview = null
        pendingRestoreUnlock = null
        _restore.value = Restore.Idle
    }

    /** §6.6 item 8: an abandoned confirmation never leaves a staged copy behind. */
    override fun onCleared() {
        if (pendingPreview != null) {
            cleanupScope.launch { runCatching { restores.discard() } }
        }
    }

    /** Drops the mnemonic from memory; the screen only offers this after acknowledgement. */
    fun acknowledgeReveal() {
        if (_operation.value is Operation.Reveal) _operation.value = Operation.Idle
    }

    fun dismissFailure() {
        if (_operation.value is Operation.Failed) _operation.value = Operation.Idle
    }

    /**
     * §6.4/§6.5: a callable already on the wire cannot be recalled — `call()` sends before
     * `await()` can observe cancellation. Cancellation stops waiting; the server's truth is
     * re-read so a commit that did land surfaces as configured instead of a false setup form.
     * The pre-dispatch guard lives in [BackupKeyManager].
     */
    fun cancel() {
        if (_operation.value !is Operation.Working) return
        job?.cancel()
        _operation.value = Operation.Idle
        job = viewModelScope.launch {
            val uid = persistentUid() ?: return@launch
            statusFromServer(uid)
        }
    }

    private fun startBackup(unlock: Unlock, replace: BackupPair?) {
        backupJob?.cancel()
        val bundle = remote
        val uid = persistentUid()
        if (bundle == null || uid == null) {
            clearBackupSecrets()
            _backup.value = Backup.Failed(BackupUploadFailure.SIGN_IN_REQUIRED, retryable = false)
            return
        }
        backupJob = viewModelScope.launch {
            _backup.value = Backup.Running(BackupProgress.Preparing)
            try {
                // The ABK unwrap runs Argon2: never on the main thread. Network and disk work
                // inside the uploader suspends or hops to IO itself.
                val result = withContext(workDispatcher) {
                    uploader.backup(bundle, unlock, replace) { progress ->
                        _backup.value = Backup.Running(progress)
                    }
                }
                clearBackupSecrets()
                _backup.value = Backup.Succeeded(result.revision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupUploadException) {
                val conflict = conflictPointer(e)
                if (conflict != null) {
                    _backup.value = Backup.Replace(conflict)
                } else {
                    val retryable = e.failure in RETRYABLE
                    if (!retryable) clearBackupSecrets()
                    _backup.value = Backup.Failed(e.failure, retryable)
                }
            } catch (e: Exception) {
                clearBackupSecrets()
                _backup.value = Backup.Failed(BackupUploadFailure.UNKNOWN, retryable = false)
            }
        }
    }

    /**
     * §6.4 item 11: a base/conflict answer names the pointer a replacement must carry. A
     * server-side race can answer without one, so the current pointer is read again (which
     * also raises the high-water mark) rather than guessed.
     */
    private suspend fun conflictPointer(e: BackupUploadException): BackupPair? {
        if (e.failure !in CONFLICTS) return null
        return e.current ?: runCatching { uploader.currentPointer() }.getOrNull()
    }

    private fun clearBackupSecrets() {
        pendingUnlock = null
        pendingReplace = null
    }

    private fun launchOperation(block: suspend (String) -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            _operation.value = Operation.Working
            val uid = persistentUid()
            if (uid == null) {
                _operation.value = Operation.Failed(BackupKeyFailure.PERSISTENT_ACCOUNT_REQUIRED)
                return@launch
            }
            try {
                // Argon2 is CPU-heavy and the callables suspend: neither runs on the main thread.
                withContext(workDispatcher) { block(uid) }
                if (_operation.value is Operation.Working) _operation.value = Operation.Idle
            } catch (e: BackupKeyException) {
                _operation.value = Operation.Failed(e.failure)
                // §9: a stale or missing bundle must be re-read before the user retries; the
                // retry itself always re-unlocks and re-confirms.
                when (e.failure) {
                    BackupKeyFailure.REVISION_CONFLICT,
                    BackupKeyFailure.ALREADY_INITIALIZED,
                    BackupKeyFailure.NOT_INITIALIZED,
                    BackupKeyFailure.BUNDLE_IDENTITY_CHANGED,
                    BackupKeyFailure.ROLLBACK,
                    BackupKeyFailure.INVALID_BUNDLE,
                    -> statusFromServer(uid)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun statusFromServer(uid: String) {
        try {
            val loaded = withContext(ioDispatcher) { manager.load(uid) }
            remote = loaded
            _status.value = if (loaded == null) Status.NotSetUp else Status.Configured(loaded.revision)
        } catch (e: BackupKeyException) {
            remote = null
            _status.value = Status.Unavailable(e.failure)
        }
    }

    private fun persistentUid(): String? =
        (auth.state.value as? AuthState.PersistentActive)?.uid

    private companion object {
        /** §6.4 item 11: only these answers are settled by explicit user confirmation. */
        val CONFLICTS = setOf(
            BackupUploadFailure.BACKUP_BASE_REQUIRED,
            BackupUploadFailure.REMOTE_BACKUP_AHEAD,
            BackupUploadFailure.BACKUP_CONFLICT,
        )

        /** Failures a repeat of the same operation, with the same secret, can recover from. */
        val RETRYABLE = setOf(
            BackupUploadFailure.NETWORK,
            BackupUploadFailure.BACKUP_RESERVATION_LIMIT,
            BackupUploadFailure.BACKUP_RESERVATION_EXPIRED,
            BackupUploadFailure.BACKUP_OBJECT_MISSING,
            BackupUploadFailure.BACKUP_OBJECT_MISMATCH,
        )

        /** §6.6 item 6: a damaged or tampered snapshot is never retried; the rest are. */
        val RESTORE_RETRYABLE = setOf(
            BackupUploadFailure.NETWORK,
            BackupUploadFailure.BACKUP_OBJECT_MISSING,
            BackupUploadFailure.STAGING_FAILED,
            BackupUploadFailure.LOCAL_STATE_UNAVAILABLE,
        )
    }
}
