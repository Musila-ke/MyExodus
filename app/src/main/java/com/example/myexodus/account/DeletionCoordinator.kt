package com.example.myexodus.account

import android.content.Context
import com.example.myexodus.data.db.LocalDatabaseMigration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the pane shows while a deletion or local removal runs. */
sealed interface DeletionState {
    data object Idle : DeletionState
    data object Working : DeletionState
    /**
     * The server accepted the request (or the local removal was journalled). When
     * [signOutBlocked] is true the queued writes stopped the sign-out, but the local purge is
     * already durable and runs as soon as this session ends.
     */
    data class Accepted(val signOutBlocked: Boolean) : DeletionState
    /** Nothing was requested or deleted; the operation can be retried. */
    data class Failed(val failure: DeletionFailure) : DeletionState
}

/** The provider proof §6.8.1 requires before a deletion request is submitted. */
sealed interface DeletionCredential {
    data class Password(val value: String) : DeletionCredential
    data class Google(val idToken: String) : DeletionCredential
}

/**
 * Account plan §6.8/§6.9.3: the deletion choreography. Provider reauthentication happens first
 * (which also force-refreshes the ID token, so `auth_time` is fresh for the server's five-minute
 * window), then the callable, then the durable local-purge marker, then the ordinary sign-out
 * teardown — the relaunch it triggers executes the purge before any store opens.
 */
@Singleton
class DeletionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthCoordinator,
    private val api: DeletionApi,
    private val localData: DeletionLocalData,
    private val relauncher: Relauncher,
) {
    private val _state = MutableStateFlow<DeletionState>(DeletionState.Idle)
    val state: StateFlow<DeletionState> = _state.asStateFlow()

    suspend fun deleteAccount(phrase: String, credential: DeletionCredential) {
        if (_state.value == DeletionState.Working) return
        _state.value = DeletionState.Working
        when (credential) {
            is DeletionCredential.Password -> auth.reauthenticateWithEmail(credential.value)
            is DeletionCredential.Google -> auth.reauthenticateWithGoogle(credential.idToken)
        }
        val active = auth.state.value as? AuthState.PersistentActive
        if (active == null) {
            // The provider call failed; its own overlay is redundant once this card speaks.
            auth.dismissTransient()
            _state.value = DeletionState.Failed(DeletionFailure.REAUTH_FAILED)
            return
        }
        request(active.uid) { api.requestAccountDeletion(phrase) }
    }

    /** §6.8.9: the guest path is double-confirmed in the UI; the server checks the phrase. */
    suspend fun deleteGuestData(phrase: String) {
        if (_state.value == DeletionState.Working) return
        val uid = (auth.state.value as? AuthState.GuestActive)?.uid ?: return
        request(uid) { api.requestGuestDeletion(phrase) }
    }

    /**
     * §8.7: the server already accepted; only the local sign-out is outstanding.
     */
    suspend fun finishAcceptedSignOut(discardQueuedWrites: Boolean) {
        if (discardQueuedWrites) auth.approvePendingWriteDiscard()
        auth.dismissTransient()
        auth.signOut()
        _state.value = DeletionState.Accepted(auth.state.value !is AuthState.SignedOut)
    }

    /**
     * §6.9.3 while signed in: the account stays in the cloud, only this device's copy goes.
     * Nothing is journalled until the sign-out is actually allowed — a refused teardown must
     * never leave a purge marker behind a live session.
     */
    suspend fun removeLocalData() {
        if (_state.value == DeletionState.Working) return
        val uid = (auth.state.value as? AuthState.PersistentActive)?.uid ?: return
        _state.value = DeletionState.Working
        localData.markPending(uid)
        auth.signOut()
        if (auth.state.value is AuthState.SignedOut) {
            _state.value = DeletionState.Accepted(signOutBlocked = false)
        } else {
            localData.clearPending(uid)
            auth.dismissTransient()
            _state.value = DeletionState.Failed(DeletionFailure.PENDING_WRITES)
        }
    }

    /** §6.9.3 while signed out: the retained scope is the only thing left to name. */
    suspend fun removeRetainedLocalData() {
        if (_state.value == DeletionState.Working) return
        // Never the journal fallback: that is the install's live local store, not a signed-out account.
        val hash = LocalDatabaseMigration.activeScopeHash(context) ?: return
        _state.value = DeletionState.Working
        localData.markPendingHash(hash)
        localData.purgePendingFiles()
        localData.finishPendingPurge()
        _state.value = DeletionState.Accepted(signOutBlocked = false)
        // A marker that survived a failed delete retries at the next start; the relaunch is
        // needed either way — the stores this process opened are the ones just erased.
        relauncher.relaunch()
    }

    suspend fun removalInfo(): LocalRemovalInfo = localData.removalInfo()

    fun reset() {
        _state.value = DeletionState.Idle
    }

    private suspend fun request(uid: String, call: suspend () -> String) {
        _state.value = DeletionState.Working
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeletionException) {
            _state.value = DeletionState.Failed(e.failure)
            return
        } catch (_: Exception) {
            _state.value = DeletionState.Failed(DeletionFailure.UNKNOWN)
            return
        }
        // Written before the sign-out relaunch; the next launch purges before any store opens.
        localData.markPending(uid)
        auth.signOut()
        if (auth.state.value is AuthState.SignedOut) {
            _state.value = DeletionState.Accepted(signOutBlocked = false)
        } else {
            auth.dismissTransient()
            _state.value = DeletionState.Accepted(signOutBlocked = true)
        }
    }
}
