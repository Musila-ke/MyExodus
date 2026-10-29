package com.example.myexodus.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myexodus.BuildConfig
import com.example.myexodus.account.AccountOperation
import com.example.myexodus.account.AuthCoordinator
import com.example.myexodus.account.AuthEvent
import com.example.myexodus.account.AuthState
import com.example.myexodus.account.GoogleCredentialOutcome
import com.example.myexodus.account.GoogleCredentialProvider
import com.example.myexodus.account.GuestTransitionCoordinator
import com.example.myexodus.account.CloudAccess
import com.example.myexodus.account.GuestTransitionState
import com.example.myexodus.account.LocalSessionManager
import com.example.myexodus.account.TransitionIntent
import com.example.myexodus.account.TransitionStep
import com.example.myexodus.accountability.sync.AccountCallables
import com.google.firebase.functions.FirebaseFunctions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Release 1 (account plan §8.3): the Account pane's driver. Holds no identity state of its own —
 * [AuthCoordinator] does — and owns only the two things the coordinator does not: the Google
 * credential picker (an Activity-bound UI concern) and the best-effort profile initialization
 * the plan requires after persistent authentication (§6.1 step 5). A failed profile call is
 * retried by `PairUidResolver.ensureUid()` on its next use.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val coordinator: AuthCoordinator,
    private val guestTransition: GuestTransitionCoordinator,
    private val functions: FirebaseFunctions,
    private val sessions: LocalSessionManager,
) : ViewModel() {

    val state: StateFlow<AuthState> = coordinator.state
    val events: Flow<AuthEvent> = coordinator.events
    val transition: StateFlow<GuestTransitionState> = guestTransition.state

    /** Plan §9: READY unless the one-time legacy-cache purge failed this launch. */
    val cloudAccess: StateFlow<CloudAccess> = sessions.cloudAccess

    /**
     * Plan §8.7: the action a pending-writes refusal refers to. The discard button replays it
     * after the user chose to drop the queued writes; dismissal forgets it.
     */
    private var lastAttempt: (() -> Unit)? = null

    private val google = GoogleCredentialProvider(BuildConfig.GOOGLE_WEB_CLIENT_ID)
    val googleAvailable: Boolean = google.available

    suspend fun requestGoogleCredential(context: Context): GoogleCredentialOutcome =
        google.requestCredential(context)

    fun createAccount(email: String, password: String) {
        lastAttempt = { createAccount(email, password) }
        launchAuthenticated { coordinator.createAccount(email, password) }
    }

    fun signInWithEmail(email: String, password: String) {
        lastAttempt = { signInWithEmail(email, password) }
        launchAuthenticated { coordinator.signInWithEmail(email, password) }
    }

    fun continueWithGoogle(idToken: String, switchAccount: Boolean) {
        lastAttempt = { continueWithGoogle(idToken, switchAccount) }
        launchAuthenticated {
            if (switchAccount) coordinator.switchToGoogle(idToken) else coordinator.continueWithGoogle(idToken)
        }
    }

    /** Plan §8.7: the user explicitly chose to discard queued SDK writes, then retry. */
    fun discardPendingWritesAndRetry() {
        coordinator.approvePendingWriteDiscard()
        val retry = lastAttempt
        if (retry != null) retry() else dismissTransient()
    }

    /** Plan §9: the only recovery for a failed one-time purge is a clean restart. */
    fun restartForCloudRecovery() = sessions.restart()

    fun startTransition(intent: TransitionIntent) = viewModelScope.launch {
        guestTransition.start(intent)
    }

    fun cancelTransition() = viewModelScope.launch { guestTransition.cancel() }

    fun retryTransition() {
        when (val current = guestTransition.state.value) {
            is GuestTransitionState.Failed -> when (current.step) {
                TransitionStep.PREPARE ->
                    current.intent?.let { intent -> viewModelScope.launch { guestTransition.start(intent) } }
                TransitionStep.FINALIZE -> viewModelScope.launch { guestTransition.resumeFinalize() }
                TransitionStep.CANCEL -> cancelTransition()
            }
            else -> Unit
        }
    }

    fun dismissTransition() = guestTransition.dismiss()

    fun sendVerificationEmail() = viewModelScope.launch { coordinator.sendVerificationEmail() }

    fun refreshVerification() = launchAuthenticated { coordinator.refreshVerification() }

    fun sendPasswordReset(email: String) = viewModelScope.launch { coordinator.sendPasswordReset(email) }

    fun reauthenticateWithEmail(password: String) = launchAuthenticated {
        coordinator.reauthenticateWithEmail(password)
    }

    fun reauthenticateWithGoogle(idToken: String) = launchAuthenticated {
        coordinator.reauthenticateWithGoogle(idToken)
    }

    fun signOut() {
        lastAttempt = { signOut() }
        viewModelScope.launch { coordinator.signOut() }
    }

    fun requireReauth(operation: AccountOperation) = coordinator.requireReauth(operation)

    fun dismissTransient() {
        lastAttempt = null
        coordinator.dismissTransient()
    }

    private fun launchAuthenticated(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        // Keep the attempt alive on failure: a pending-writes error's discard button replays it.
        if (coordinator.state.value !is AuthState.AuthError) lastAttempt = null
        if (coordinator.state.value is AuthState.PersistentActive) {
            runCatching { AccountCallables.initializeUserProfile(functions) }
            // A switch that raced ahead of the target profile exists retries here, after the
            // profile call; with no journal this is a no-op.
            guestTransition.resumeFinalize()
        }
    }
}
