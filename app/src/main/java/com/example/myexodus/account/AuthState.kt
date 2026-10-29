package com.example.myexodus.account

/**
 * Account plan §4.1: the immutable identity states [AuthCoordinator] exposes. The app is guest
 * first — [SignedOut] and [GuestActive] are both "no account", differing only in whether an
 * anonymous Firebase UID exists yet. Transient states ([Authenticating], [AuthError],
 * [ReauthRequired]) overlay a retained base identity and always resolve back to it.
 */
sealed interface AuthState {
    /** Before the first Firebase auth callback lands. */
    data object Initializing : AuthState

    /** No Firebase user at all; the next pairing/account action mints or signs in one. */
    data object SignedOut : AuthState

    /** Anonymous session — a UID exists so the local database is scoped, no account yet. */
    data class GuestActive(val uid: String) : AuthState

    /** A real account is signed in. */
    data class PersistentActive(
        val uid: String,
        val email: String?,
        val emailVerified: Boolean,
        val providers: Set<AuthProvider>,
    ) : AuthState

    data class Authenticating(val operation: AccountOperation) : AuthState

    /** A protected operation returned `REAUTH_REQUIRED`; the user must prove the provider again. */
    data class ReauthRequired(
        val operation: AccountOperation,
        val email: String?,
    ) : AuthState

    data class AuthError(
        val operation: AccountOperation,
        val failure: AuthFailure,
    ) : AuthState
}

enum class AuthProvider { PASSWORD, GOOGLE }

/** Operations the UI can drive and errors can be attributed to. */
enum class AccountOperation {
    CREATE_ACCOUNT,
    LINK_ACCOUNT,
    SIGN_IN,
    SIGN_OUT,
    VERIFY_EMAIL,
    RESET_PASSWORD,
    REAUTHENTICATE,
    DELETE_ACCOUNT,
    BACKUP,
    GUEST_TRANSITION,
}

/**
 * Client-side categories. Every user-facing string is generic by design (plan §8.3): sign-in
 * and reset failures never distinguish "wrong password" from "no such account", and
 * [CREDENTIAL_ALREADY_REGISTERED] is surfaced as the plan's neutral collision guidance, never
 * as "this email exists".
 */
enum class AuthFailure {
    INVALID_CREDENTIALS,
    CREDENTIAL_ALREADY_REGISTERED,
    WEAK_PASSWORD,
    INVALID_EMAIL,
    EMAIL_NOT_VERIFIED,
    NETWORK,
    TOO_MANY_REQUESTS,
    PROVIDER_UNAVAILABLE,
    REQUIRES_RECENT_LOGIN,
    ALREADY_SIGNED_IN,
    NOT_SIGNED_IN,
    /**
     * A guest session cannot sign into a different account directly: the guest-transition flow
     * must prepare first so pairs are terminated only after target authentication succeeds.
     */
    TRANSITION_REQUIRED,
    /**
     * Account plan §8.7: writes queued under the old identity could not be acknowledged, so the
     * switch is blocked until the user explicitly chooses to discard them.
     */
    PENDING_WRITES,
    /** Plan §9: the one-time legacy-cache purge failed; Firestore-backed changes are blocked. */
    CLOUD_MIGRATION_PENDING,
    /** Another identity change already froze the session; waiting is enough. */
    TRANSITION_IN_PROGRESS,
    SECURITY_CHECK_FAILED,
    UNKNOWN,
}

/** One-shot results the UI reports; none of them is a durable identity state. */
sealed interface AuthEvent {
    data object EmailVerificationSent : AuthEvent
    data object PasswordResetRequested : AuthEvent
    data object Collision : AuthEvent
    data object EmailVerified : AuthEvent
    data object Reauthenticated : AuthEvent
    data class SignedIn(val persistent: Boolean, val emailVerified: Boolean) : AuthEvent
    data object SignedOut : AuthEvent
}

/** A mapped failure thrown by [AuthOperations]; the coordinator converts it to [AuthState]. */
class AuthOperationException(
    val failure: AuthFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)
