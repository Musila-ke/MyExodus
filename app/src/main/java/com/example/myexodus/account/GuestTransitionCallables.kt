package com.example.myexodus.account

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await

/* Server states (account plan §4.2). The client only branches on these exact strings. */
internal const val SERVER_PREPARED = "PREPARED"
internal const val SERVER_EXPIRED_UNFINALIZED = "EXPIRED_UNFINALIZED"
internal const val SERVER_CANCELLED = "CANCELLED"

data class PrepareOutcome(val state: String)

data class FinalizeOutcome(
    val terminatedPairIds: List<String>,
    val cleanupJobId: String?,
)

/**
 * Account plan §8.4: every server answer maps onto this closed vocabulary. The capability and
 * its hash never appear here, in messages or in logs.
 */
enum class TransitionFailure {
    NETWORK,
    DENIED,
    SIGN_IN_REQUIRED,
    NOT_ENABLED,
    ALREADY_PREPARED,
    RATE_LIMITED,
    FINALIZED,
    CANCELLED,
    NOT_FOUND,
    OWNED_BY_ANOTHER_ACCOUNT,
    INVALID_CAPABILITY,
    NOT_OWNER,
    ANONYMOUS_ONLY,
    PERSISTENT_ACCOUNT_REQUIRED,
    PROFILE_NOT_ACTIVE,
    ACCOUNT_DELETING,
    CONFLICT,
    INTENT_MISMATCH,
    REAUTH_REQUIRED,
    SECURITY_CHECK_FAILED,
    /** The encrypted journal exists but cannot be read — the capability is unrecoverable here. */
    JOURNAL_UNREADABLE,
    /** §6.2.3: the guest snapshot could not be encrypted and staged, so the switch is refused. */
    QUARANTINE_FAILED,
    /** Finalization succeeded but the staged copy could not be bound to the target UID. */
    QUARANTINE_UNSAVED,
    UNKNOWN,
}

class GuestTransitionException(
    val failure: TransitionFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

/** The seam behind [GuestTransitionCoordinator]; production is [GuestTransitionCallables]. */
interface GuestTransitionApi {
    suspend fun prepare(
        transitionId: String,
        capability: String,
        intent: TransitionIntent,
    ): PrepareOutcome

    suspend fun finalize(
        transitionId: String,
        capability: String,
        intent: TransitionIntent,
    ): FinalizeOutcome

    suspend fun cancel(transitionId: String, capability: String)
}

/**
 * Account plan §8.4: the three guest-transition callables. The region matches
 * [com.example.myexodus.accountability.sync.PairingRegistration.REGION] by the shared
 * [FirebaseFunctions] instance; App Check is installed app-wide before any call.
 */
class GuestTransitionCallables(
    private val functions: FirebaseFunctions,
) : GuestTransitionApi {

    override suspend fun prepare(
        transitionId: String,
        capability: String,
        intent: TransitionIntent,
    ): PrepareOutcome = mapped {
        val data = call(
            "prepareGuestTransition",
            mapOf(
                "transitionId" to transitionId,
                "capability" to capability,
                "intent" to intent.name,
            ),
        )
        PrepareOutcome(
            state = data["state"] as? String
                ?: throw GuestTransitionException(TransitionFailure.UNKNOWN),
        )
    }

    override suspend fun finalize(
        transitionId: String,
        capability: String,
        intent: TransitionIntent,
    ): FinalizeOutcome = mapped {
        val data = call(
            "finalizeGuestTransition",
            mapOf(
                "transitionId" to transitionId,
                "capability" to capability,
                "intent" to intent.name,
            ),
        )
        FinalizeOutcome(
            terminatedPairIds = (data["terminatedPairIds"] as? List<*>)
                ?.filterIsInstance<String>().orEmpty(),
            cleanupJobId = data["cleanupJobId"] as? String,
        )
    }

    override suspend fun cancel(transitionId: String, capability: String) {
        mapped {
            call("cancelGuestTransition", mapOf("transitionId" to transitionId, "capability" to capability))
        }
    }

    private suspend fun call(name: String, data: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return functions.getHttpsCallable(name).call(data).await().data as? Map<String, Any?>
            ?: emptyMap()
    }

    private suspend fun <T> mapped(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: FirebaseFunctionsException) {
        throw GuestTransitionException(failureOf(e), e)
    }

    private fun failureOf(e: FirebaseFunctionsException): TransitionFailure = when (e.message) {
        "REAUTH_REQUIRED" -> TransitionFailure.REAUTH_REQUIRED
        "GUEST_TRANSITION_DISABLED" -> TransitionFailure.NOT_ENABLED
        "TRANSITION_ALREADY_PREPARED" -> TransitionFailure.ALREADY_PREPARED
        "TRANSITION_RATE_LIMITED" -> TransitionFailure.RATE_LIMITED
        "TRANSITION_FINALIZED" -> TransitionFailure.FINALIZED
        "TRANSITION_CANCELLED" -> TransitionFailure.CANCELLED
        "TRANSITION_NOT_FOUND" -> TransitionFailure.NOT_FOUND
        "TRANSITION_OWNED_BY_ANOTHER_ACCOUNT" -> TransitionFailure.OWNED_BY_ANOTHER_ACCOUNT
        "INVALID_CAPABILITY" -> TransitionFailure.INVALID_CAPABILITY
        "NOT_TRANSITION_OWNER" -> TransitionFailure.NOT_OWNER
        "ANONYMOUS_ONLY" -> TransitionFailure.ANONYMOUS_ONLY
        "PERSISTENT_ACCOUNT_REQUIRED" -> TransitionFailure.PERSISTENT_ACCOUNT_REQUIRED
        "PROFILE_NOT_ACTIVE" -> TransitionFailure.PROFILE_NOT_ACTIVE
        "ACCOUNT_DELETING" -> TransitionFailure.ACCOUNT_DELETING
        "TRANSITION_ID_CONFLICT" -> TransitionFailure.CONFLICT
        "INTENT_MISMATCH" -> TransitionFailure.INTENT_MISMATCH
        else -> when (e.code) {
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
            FirebaseFunctionsException.Code.INTERNAL,
            -> TransitionFailure.NETWORK
            FirebaseFunctionsException.Code.UNAUTHENTICATED -> TransitionFailure.SIGN_IN_REQUIRED
            FirebaseFunctionsException.Code.PERMISSION_DENIED -> TransitionFailure.DENIED
            else -> TransitionFailure.UNKNOWN
        }
    }
}
