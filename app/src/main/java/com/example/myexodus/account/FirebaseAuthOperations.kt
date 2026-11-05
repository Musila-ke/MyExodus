package com.example.myexodus.account

import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Account plan §4.1/§8.3: the Firebase Auth implementation behind [AuthOperations]. Holds no
 * state of its own — the coordinator owns transitions — and maps every SDK failure onto the
 * generic [AuthFailure] vocabulary here, once, so no caller branch ever differentiates
 * "wrong password" from "no such account" (email enumeration protection).
 */
class FirebaseAuthOperations(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthOperations {

    override fun currentIdentity(): AuthIdentity? = auth.currentUser?.toIdentity()

    override fun observeIdentity(onChange: (AuthIdentity?) -> Unit): () -> Unit {
        val listener = FirebaseAuth.AuthStateListener { onChange(it.currentUser?.toIdentity()) }
        auth.addAuthStateListener(listener)
        return { auth.removeAuthStateListener(listener) }
    }

    override suspend fun createWithEmail(email: String, password: String): AuthIdentity =
        mapped {
            auth.createUserWithEmailAndPassword(email, password).await().user
                ?.toIdentity() ?: throw AuthOperationException(AuthFailure.UNKNOWN)
        }

    override suspend fun signInWithEmail(email: String, password: String): AuthIdentity =
        mapped {
            auth.signInWithEmailAndPassword(email, password).await().user
                ?.toIdentity() ?: throw AuthOperationException(AuthFailure.UNKNOWN)
        }

    override suspend fun linkWithEmail(email: String, password: String): AuthIdentity =
        mapped {
            requireUser().linkWithCredential(EmailAuthProvider.getCredential(email, password))
                .await().user?.toIdentity() ?: throw AuthOperationException(AuthFailure.UNKNOWN)
        }

    override suspend fun signInWithGoogle(idToken: String): AuthIdentity =
        mapped {
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .await().user?.toIdentity() ?: throw AuthOperationException(AuthFailure.UNKNOWN)
        }

    override suspend fun linkWithGoogle(idToken: String): AuthIdentity =
        mapped {
            requireUser().linkWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .await().user?.toIdentity() ?: throw AuthOperationException(AuthFailure.UNKNOWN)
        }

    override suspend fun sendVerificationEmail(): AuthIdentity = mapped {
        val user = requireUser()
        user.sendEmailVerification().await()
        user.toIdentity()
    }

    /** Never an existence oracle: "no such user" is reported to the caller as success. */
    override suspend fun sendPasswordReset(email: String): Unit = mapped {
        runCatching { auth.sendPasswordResetEmail(email).await() }
            .onFailure { if (it !is FirebaseAuthInvalidUserException) throw it }
        Unit
    }

    /**
     * After verification or reauthentication the server independently checks the Auth record;
     * forcing the token refresh makes the client state agree with what the server will read.
     */
    override suspend fun reloadAndRefreshToken(): AuthIdentity = mapped {
        val user = requireUser()
        user.reload().await()
        user.getIdToken(true).await()
        user.toIdentity()
    }

    override suspend fun reauthenticateWithEmail(email: String, password: String): Unit = mapped {
        val user = requireUser()
        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
        user.getIdToken(true).await()
        Unit
    }

    override suspend fun reauthenticateWithGoogle(idToken: String): Unit = mapped {
        val user = requireUser()
        user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await()
        user.getIdToken(true).await()
        Unit
    }

    override suspend fun signOut() {
        auth.signOut()
    }

    private fun requireUser(): FirebaseUser =
        auth.currentUser ?: throw AuthOperationException(AuthFailure.NOT_SIGNED_IN)

    private suspend fun <T> mapped(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuthOperationException) {
        throw e
    } catch (e: FirebaseAuthUserCollisionException) {
        throw AuthOperationException(AuthFailure.CREDENTIAL_ALREADY_REGISTERED, e)
    } catch (e: FirebaseAuthException) {
        throw AuthOperationException(classify(e), e)
    } catch (e: FirebaseNetworkException) {
        throw AuthOperationException(AuthFailure.NETWORK, e)
    } catch (e: FirebaseException) {
        // e.g. an App Check / API-key rejection: not evidence the credentials were wrong.
        throw AuthOperationException(AuthFailure.UNKNOWN, e)
    } catch (e: Exception) {
        throw AuthOperationException(AuthFailure.UNKNOWN, e)
    }

    companion object {
        /**
         * SDK types/codes from the installed Android SDK (plan §8.3: never copy web SDK error
         * assumptions). Anything unrecognized stays [AuthFailure.UNKNOWN] — never [is it
         * "invalid credentials"], so an unknown error cannot masquerade as a definitive answer.
         */
        internal fun classify(e: FirebaseException): AuthFailure {
            return when (e) {
                is FirebaseAuthUserCollisionException -> AuthFailure.CREDENTIAL_ALREADY_REGISTERED
                is FirebaseAuthWeakPasswordException -> AuthFailure.WEAK_PASSWORD
                is FirebaseAuthRecentLoginRequiredException -> AuthFailure.REQUIRES_RECENT_LOGIN
                is FirebaseAuthInvalidCredentialsException,
                is FirebaseAuthInvalidUserException,
                -> AuthFailure.INVALID_CREDENTIALS
                is FirebaseAuthException -> e.authCode()
                is FirebaseNetworkException -> AuthFailure.NETWORK
                else -> AuthFailure.UNKNOWN
            }
        }

        private fun FirebaseAuthException.authCode(): AuthFailure = when (getErrorCode()) {
            "ERROR_CREDENTIAL_ALREADY_IN_USE",
            "ERROR_EMAIL_ALREADY_IN_USE",
            -> AuthFailure.CREDENTIAL_ALREADY_REGISTERED
            "ERROR_WEAK_PASSWORD" -> AuthFailure.WEAK_PASSWORD
            "ERROR_INVALID_EMAIL" -> AuthFailure.INVALID_EMAIL
            "ERROR_INVALID_CREDENTIAL",
            "ERROR_WRONG_PASSWORD",
            "ERROR_USER_NOT_FOUND",
            "ERROR_USER_DISABLED",
            "ERROR_INVALID_TOKEN",
            -> AuthFailure.INVALID_CREDENTIALS
            "ERROR_TOO_MANY_REQUESTS" -> AuthFailure.TOO_MANY_REQUESTS
            "ERROR_OPERATION_NOT_ALLOWED" -> AuthFailure.PROVIDER_UNAVAILABLE
            "ERROR_REQUIRES_RECENT_LOGIN" -> AuthFailure.REQUIRES_RECENT_LOGIN
            "ERROR_NETWORK_REQUEST_FAILED" -> AuthFailure.NETWORK
            else -> AuthFailure.UNKNOWN
        }
    }
}

private fun FirebaseUser.toIdentity(): AuthIdentity = AuthIdentity(
    uid = uid,
    isAnonymous = isAnonymous,
    email = email,
    emailVerified = isEmailVerified,
    providerIds = providerData.mapNotNull { it.providerId }.toSet(),
)
