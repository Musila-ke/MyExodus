package com.example.myexodus.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.account.AuthCoordinator
import com.example.myexodus.account.AuthState
import com.example.myexodus.backup.BackupKeyException
import com.example.myexodus.backup.BackupKeyFailure
import com.example.myexodus.backup.BackupKeyManager
import com.example.myexodus.backup.BackupKeyManager.Unlock
import com.example.myexodus.backup.PassphrasePolicy
import com.example.myexodus.backup.RemoteBundle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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

    private val _status = MutableStateFlow<Status>(Status.Loading)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _operation = MutableStateFlow<Operation>(Operation.Idle)
    val operation: StateFlow<Operation> = _operation.asStateFlow()

    val authState: StateFlow<AuthState> = auth.state

    private var remote: RemoteBundle? = null
    private var job: Job? = null

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
}
