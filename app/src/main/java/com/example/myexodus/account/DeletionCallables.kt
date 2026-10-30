package com.example.myexodus.account

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await

/** Plan §6.8.1: the exact phrases the server accepts; the client never invents variants. */
object DeletionPhrases {
    const val ACCOUNT = "DELETE MY ACCOUNT"
    const val GUEST = "DELETE MY DATA"
}

/** Account plan §6.8: the closed failure vocabulary the pane maps to copy. */
enum class DeletionFailure {
    NETWORK,
    SIGN_IN_REQUIRED,
    REAUTH_REQUIRED,
    /** The provider reauthentication itself did not succeed; nothing was requested. */
    REAUTH_FAILED,
    EMAIL_NOT_VERIFIED,
    /** An account deletion was requested from an anonymous session (guest flow applies). */
    PERSISTENT_ACCOUNT_REQUIRED,
    /** Guest deletion was requested by a persistent account. */
    ANONYMOUS_ONLY,
    CONFIRMATION_MISMATCH,
    INVALID_INPUT,
    DENIED,
    SECURITY_CHECK_FAILED,
    /** Sign-out was refused because queued writes could not be acknowledged (§8.7). */
    PENDING_WRITES,
    UNKNOWN,
}

class DeletionException(
    val failure: DeletionFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

/** The seam behind [DeletionCoordinator]; production is [DeletionCallables]. */
interface DeletionApi {
    /** Returns the server-side job state (`QUEUED` on first acceptance, the phase on replay). */
    suspend fun requestAccountDeletion(confirmation: String): String
    suspend fun requestGuestDeletion(confirmation: String): String
}

/**
 * Account plan §6.8.2: the two deletion callables. The request carries only the confirmation
 * phrase — the server reads the raw ID token from the call's Authorization header, so the
 * client must force-refresh before calling (the coordinator does).
 */
class DeletionCallables(
    private val functions: FirebaseFunctions,
) : DeletionApi {

    override suspend fun requestAccountDeletion(confirmation: String): String =
        state("requestAccountDeletion", confirmation)

    override suspend fun requestGuestDeletion(confirmation: String): String =
        state("requestGuestDataDeletion", confirmation)

    private suspend fun state(name: String, confirmation: String): String = mapped {
        @Suppress("UNCHECKED_CAST")
        val data = functions.getHttpsCallable(name)
            .call(mapOf("confirmation" to confirmation))
            .await().data as? Map<String, Any?>
        data?.get("state") as? String ?: throw DeletionException(DeletionFailure.UNKNOWN)
    }

    private suspend fun <T> mapped(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: FirebaseFunctionsException) {
        throw DeletionException(failureOf(e), e)
    }

    private fun failureOf(e: FirebaseFunctionsException): DeletionFailure = when (e.message) {
        "REAUTH_REQUIRED", "RAW_TOKEN_REQUIRED", "TOKEN_INVALID" -> DeletionFailure.REAUTH_REQUIRED
        "EMAIL_NOT_VERIFIED" -> DeletionFailure.EMAIL_NOT_VERIFIED
        "PERSISTENT_ACCOUNT_REQUIRED" -> DeletionFailure.PERSISTENT_ACCOUNT_REQUIRED
        "ANONYMOUS_ONLY" -> DeletionFailure.ANONYMOUS_ONLY
        "CONFIRMATION_MISMATCH" -> DeletionFailure.CONFIRMATION_MISMATCH
        "INVALID_INPUT" -> DeletionFailure.INVALID_INPUT
        "UID_MISMATCH" -> DeletionFailure.DENIED
        // App Check rejection arrives as a plain permission/unauthenticated code.
        "SECURITY_CHECK_FAILED" -> DeletionFailure.SECURITY_CHECK_FAILED
        else -> when (e.code) {
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
            FirebaseFunctionsException.Code.INTERNAL,
            -> DeletionFailure.NETWORK
            FirebaseFunctionsException.Code.UNAUTHENTICATED -> DeletionFailure.SIGN_IN_REQUIRED
            FirebaseFunctionsException.Code.PERMISSION_DENIED -> DeletionFailure.DENIED
            else -> DeletionFailure.UNKNOWN
        }
    }
}
