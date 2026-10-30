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
import com.example.myexodus.backup.BackupUploadException
import com.example.myexodus.backup.BackupUploadFailure
import com.example.myexodus.backup.BackupUploads
import com.example.myexodus.backup.PassphrasePolicy
import com.example.myexodus.backup.RemoteBundle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    val authState: StateFlow<AuthState> = auth.state

    private var remote: RemoteBundle? = null
    private var job: Job? = null
    private var backupJob: Job? = null
    // Held only while one backup flow runs: the ABK unwrap secret and the pointer the user
    // explicitly confirmed. Never persisted, dropped on every terminal outcome.
    private var pendingUnlock: Unlock? = null
    private var pendingReplace: BackupPair? = null

    init {
        refresh()
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
                val result = withContext(Dispatchers.Default) {
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
                withContext(Dispatchers.Default) { block(uid) }
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
            val loaded = withContext(Dispatchers.IO) { manager.load(uid) }
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
    }
}
