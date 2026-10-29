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
    private val functions: FirebaseFunctions,
) : ViewModel() {

    val state: StateFlow<AuthState> = coordinator.state
    val events: Flow<AuthEvent> = coordinator.events

    private val google = GoogleCredentialProvider(BuildConfig.GOOGLE_WEB_CLIENT_ID)
    val googleAvailable: Boolean = google.available

    suspend fun requestGoogleCredential(context: Context): GoogleCredentialOutcome =
        google.requestCredential(context)

    fun createAccount(email: String, password: String) = launchAuthenticated {
        coordinator.createAccount(email, password)
    }

    fun signInWithEmail(email: String, password: String) = launchAuthenticated {
        coordinator.signInWithEmail(email, password)
    }

    fun continueWithGoogle(idToken: String) = launchAuthenticated {
        coordinator.continueWithGoogle(idToken)
    }

    fun sendVerificationEmail() = viewModelScope.launch { coordinator.sendVerificationEmail() }

    fun refreshVerification() = launchAuthenticated { coordinator.refreshVerification() }

    fun sendPasswordReset(email: String) = viewModelScope.launch { coordinator.sendPasswordReset(email) }

    fun reauthenticateWithEmail(password: String) = launchAuthenticated {
        coordinator.reauthenticateWithEmail(password)
    }

    fun reauthenticateWithGoogle(idToken: String) = launchAuthenticated {
        coordinator.reauthenticateWithGoogle(idToken)
    }

    fun signOut() = viewModelScope.launch { coordinator.signOut() }

    fun requireReauth(operation: AccountOperation) = coordinator.requireReauth(operation)

    fun dismissTransient() = coordinator.dismissTransient()

    private fun launchAuthenticated(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        if (coordinator.state.value is AuthState.PersistentActive) {
            runCatching { AccountCallables.initializeUserProfile(functions) }
        }
    }
}
