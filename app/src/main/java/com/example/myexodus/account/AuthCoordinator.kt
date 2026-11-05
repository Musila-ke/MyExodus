package com.example.myexodus.account

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Account plan §4.1/§6.1/§8.3. Owns the identity state machine and the guest-upgrade rule:
 * while an anonymous session exists, a new credential is **linked** (UID and local database
 * preserved); a credential that belongs to another account surfaces the plan's neutral
 * collision guidance and never touches the target account. Signing into an existing account
 * from a guest is refused unless the guest-transition coordinator has prepared and explicitly
 * allowed the switch — pairs may only be terminated after target authentication succeeds.
 *
 * It never mutates database files or Firebase SDK state beyond Auth itself; those transitions
 * belong to LocalSessionManager (Release 1 step 10).
 */
@Singleton
class AuthCoordinator @Inject constructor(
    private val operations: AuthOperations,
    /** Plan §7 step 10/§8.7: the local teardown every identity change runs around its credential
     *  call. The default keeps the coordinator's own state machine testable without a session. */
    private val sessions: SessionTransitions = SessionTransitions.Unfenced,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Initializing)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events: Flow<AuthEvent> = _events.receiveAsFlow()

    /** The durable identity, with any transient overlay (error, reauth, authenticating) removed. */
    val durableState: AuthState get() = base

    /** The durable identity a transient overlay returns to. */
    @Volatile private var base: AuthState = AuthState.Initializing

    @Volatile private var identitySwitchAllowed = false

    init {
        base = stateOf(operations.currentIdentity())
        _state.value = base
        operations.observeIdentity { identity ->
            base = stateOf(identity)
            if (_state.value is AuthState.PersistentActive ||
                _state.value is AuthState.GuestActive ||
                _state.value is AuthState.SignedOut
            ) {
                _state.value = base
            }
        }
    }

    /**
     * Enables signing in to a different account. Only the guest-transition coordinator calls
     * this, after its encrypted journal holds a prepared transition. A prepared transition
     * survives a failed sign-in attempt — that retry is the plan's §8.4 recovery path — while a
     * completed switch, a cancellation and sign-out clear it. Pair termination never depends on
     * this flag: only finalize (with the journal's capability) can terminate.
     */
    fun setIdentitySwitchAllowed(allowed: Boolean) {
        identitySwitchAllowed = allowed
    }

    /**
     * Create-or-link. A guest is upgraded in place (link keeps its UID and scoped database);
     * a signed-out device creates the account directly.
     */
    suspend fun createAccount(email: String, password: String) = operate(AccountOperation.CREATE_ACCOUNT) {
        requireCloudReady()
        val identity = when {
            operations.currentIdentity() == null -> operations.createWithEmail(email.trim(), password)
            operations.currentIdentity()?.isAnonymous == true -> operations.linkWithEmail(email.trim(), password)
            else -> throw AuthOperationException(AuthFailure.ALREADY_SIGNED_IN)
        }
        sendVerificationQuietly()
        _events.send(AuthEvent.SignedIn(persistent = true, emailVerified = identity.emailVerified))
        identity
    }

    /** Sign in to an existing account. Refused from a guest unless a transition is prepared. */
    suspend fun signInWithEmail(email: String, password: String) = operate(AccountOperation.SIGN_IN) {
        requireSwitchPermitted()
        requireCloudReady()
        // §8.7: a guest leaving for an existing account tears the old session down first; a
        // signed-out device has no session to tear down.
        val switching = operations.currentIdentity()?.isAnonymous == true
        if (switching) startTransition(signOut = false)
        val identity = if (switching) {
            settleTransition { operations.signInWithEmail(email.trim(), password) }
        } else {
            operations.signInWithEmail(email.trim(), password)
        }
        _events.send(AuthEvent.SignedIn(persistent = true, emailVerified = identity.emailVerified))
        identity
    }

    /**
     * Google has one picker, not separate create/sign-in buttons: guest → link (the plan's
     * collision-safe upgrade), signed-out → sign in. A collision is the neutral guidance case.
     */
    suspend fun continueWithGoogle(idToken: String) = operate(AccountOperation.SIGN_IN) {
        requireCloudReady()
        val identity = when {
            operations.currentIdentity() == null -> operations.signInWithGoogle(idToken)
            operations.currentIdentity()?.isAnonymous == true -> operations.linkWithGoogle(idToken)
            else -> throw AuthOperationException(AuthFailure.ALREADY_SIGNED_IN)
        }
        _events.send(AuthEvent.SignedIn(persistent = true, emailVerified = identity.emailVerified))
        identity
    }

    /** The guest-transition path: sign in to the EXISTING account a prepared switch targets. */
    suspend fun switchToGoogle(idToken: String) = operate(AccountOperation.SIGN_IN) {
        requireSwitchPermitted()
        requireCloudReady()
        startTransition(signOut = false)
        val identity = settleTransition { operations.signInWithGoogle(idToken) }
        _events.send(AuthEvent.SignedIn(persistent = true, emailVerified = identity.emailVerified))
        identity
    }

    suspend fun sendVerificationEmail() = operate(AccountOperation.VERIFY_EMAIL) {
        if (base !is AuthState.PersistentActive) throw AuthOperationException(AuthFailure.NOT_SIGNED_IN)
        val identity = operations.sendVerificationEmail()
        _events.send(AuthEvent.EmailVerificationSent)
        identity
    }

    /** Reload the Auth record and force a token refresh before any protected call. */
    suspend fun refreshVerification() = operate(AccountOperation.VERIFY_EMAIL) {
        val identity = operations.reloadAndRefreshToken()
        if (!identity.isAnonymous && identity.emailVerified) _events.send(AuthEvent.EmailVerified)
        identity
    }

    suspend fun sendPasswordReset(email: String) = operate(AccountOperation.RESET_PASSWORD) {
        operations.sendPasswordReset(email.trim())
        _events.send(AuthEvent.PasswordResetRequested)
        null
    }

    suspend fun reauthenticateWithEmail(password: String) = operate(AccountOperation.REAUTHENTICATE) {
        val email = (base as? AuthState.PersistentActive)?.email
            ?: throw AuthOperationException(AuthFailure.NOT_SIGNED_IN)
        operations.reauthenticateWithEmail(email, password)
        val identity = operations.reloadAndRefreshToken()
        _events.send(AuthEvent.Reauthenticated)
        identity
    }

    suspend fun reauthenticateWithGoogle(idToken: String) = operate(AccountOperation.REAUTHENTICATE) {
        operations.reauthenticateWithGoogle(idToken)
        val identity = operations.reloadAndRefreshToken()
        _events.send(AuthEvent.Reauthenticated)
        identity
    }

    /** A protected operation answered `REAUTH_REQUIRED`; show the provider's reauth form. */
    fun requireReauth(operation: AccountOperation) {
        _state.value = AuthState.ReauthRequired(operation, (base as? AuthState.PersistentActive)?.email)
    }

    fun dismissTransient() {
        _state.value = base
    }

    /**
     * Firebase sign-out only. The encrypted local database is retained (plan §6.9); worker and
     * listener teardown belongs to LocalSessionManager.
     */
    suspend fun signOut() {
        if (_state.value is AuthState.Authenticating) return
        _state.value = AuthState.Authenticating(AccountOperation.SIGN_OUT)
        try {
            requireCloudReady()
            // §4.1: an ordinary sign-out flushes queued writes and removes the push mapping
            // while the UID still owns them; the local database is retained, not deleted.
            if (base is AuthState.GuestActive || base is AuthState.PersistentActive) {
                startTransition(signOut = true)
            }
            try {
                operations.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // signOut() is local-only; even an SDK failure must not trap the user in a session.
            }
            base = AuthState.SignedOut
            identitySwitchAllowed = false
            _state.value = base
            _events.send(AuthEvent.SignedOut)
            sessions.completeTransition(null)
        } catch (e: CancellationException) {
            _state.value = base
            runCatching { sessions.abortTransition() }
            throw e
        } catch (e: AuthOperationException) {
            _state.value = AuthState.AuthError(AccountOperation.SIGN_OUT, e.failure)
        }
    }

    /** Plan §8.7: the user explicitly confirmed discarding unacknowledged SDK writes. */
    fun approvePendingWriteDiscard() = sessions.approvePendingWriteDiscard()

    private suspend fun operate(operation: AccountOperation, block: suspend () -> AuthIdentity?) {
        if (_state.value is AuthState.Authenticating) return
        _state.value = AuthState.Authenticating(operation)
        try {
            val identity = block()
            if (identity != null) {
                base = stateOf(identity)
                identitySwitchAllowed = identitySwitchAllowed && identity.isAnonymous
            }
            _state.value = base
        } catch (e: CancellationException) {
            _state.value = base
            throw e
        } catch (e: AuthOperationException) {
            base = stateOf(operations.currentIdentity())
            if (e.failure == AuthFailure.CREDENTIAL_ALREADY_REGISTERED) {
                _events.send(AuthEvent.Collision)
            }
            _state.value = AuthState.AuthError(operation, e.failure)
        } catch (_: Exception) {
            base = stateOf(operations.currentIdentity())
            _state.value = AuthState.AuthError(operation, AuthFailure.UNKNOWN)
        }
    }

    private fun requireSwitchPermitted() {
        val current = operations.currentIdentity() ?: return
        if (!current.isAnonymous) throw AuthOperationException(AuthFailure.ALREADY_SIGNED_IN)
        if (!identitySwitchAllowed) throw AuthOperationException(AuthFailure.TRANSITION_REQUIRED)
    }

    /** Plan §9: the one-time legacy-cache purge blocks every Firestore-backed identity change. */
    private fun requireCloudReady() {
        if (!sessions.isCloudReady()) throw AuthOperationException(AuthFailure.CLOUD_MIGRATION_PENDING)
    }

    /** Plan §8.7: the teardown runs before the credential call; its refusal is user-facing. */
    private suspend fun startTransition(signOut: Boolean) {
        when (val start = sessions.beginTransition(signOut)) {
            TransitionStart.Started -> Unit
            is TransitionStart.Refused -> throw AuthOperationException(
                when (start.refusal) {
                    TransitionRefusal.PENDING_WRITES -> AuthFailure.PENDING_WRITES
                    TransitionRefusal.MIGRATION_BLOCKED -> AuthFailure.CLOUD_MIGRATION_PENDING
                    TransitionRefusal.ALREADY_TRANSITIONING -> AuthFailure.TRANSITION_IN_PROGRESS
                },
            )
        }
    }

    /**
     * Plan §8.4: a refused credential must leave the guest session exactly as it was — the
     * teardown is reverted and the prepared transition journal survives for the retry.
     */
    private suspend fun <T> settleTransition(block: suspend () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        runCatching { sessions.abortTransition() }
        throw failure
    }

    /** Best effort: an account exists even if the verification mail has to be re-sent later. */
    private suspend fun sendVerificationQuietly() {
        try {
            operations.sendVerificationEmail()
            _events.send(AuthEvent.EmailVerificationSent)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort by contract: an account exists even if the mail must be re-sent later.
        }
    }

    private fun stateOf(identity: AuthIdentity?): AuthState = when {
        identity == null -> AuthState.SignedOut
        identity.isAnonymous -> AuthState.GuestActive(identity.uid)
        else -> AuthState.PersistentActive(
            uid = identity.uid,
            email = identity.email,
            emailVerified = identity.emailVerified,
            providers = identity.providers,
        )
    }
}
