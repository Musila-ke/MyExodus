package com.example.myexodus.account

/**
 * Account plan §7 step 10/§8.7: the identity a unit of remote work was started under — the
 * Firebase UID plus a local generation that only ever moves forward. Work captured under an
 * older fingerprint must stop without sending, even when the UID happens to match again.
 */
data class SessionFingerprint(val uid: String, val generation: Long)

/**
 * Account plan §8.7/§12.4: the one question every Firestore operation asks immediately before
 * it acts. Null means no remote work may start or continue: the session is signed out, mid
 * transition, or the one-time legacy-cache purge has not succeeded on this install.
 *
 * Production binds [LocalSessionManager]; the JVM default is unfenced so unit tests keep
 * exercising their own subject without a session.
 */
fun interface SessionFence {
    fun current(): SessionFingerprint?

    fun isCurrent(fingerprint: SessionFingerprint): Boolean = current() == fingerprint

    /**
     * Whether an anonymous session may be minted. A deliberate sign-out must not mint a fresh
     * guest under the user's feet; a live or absent-identity-but-not-signed-out session may.
     */
    fun mayMintGuest(): Boolean = current() != null

    companion object {
        /** Test default: the session is always valid and may mint. Production binds the manager. */
        val Unfenced: SessionFence = SessionFence { SessionFingerprint("unfenced", 0) }
    }
}

/** Why a plan §8.7 teardown could not start, mapped by [AuthCoordinator] to user copy. */
enum class TransitionRefusal {
    /** §8.7: pending SDK writes could not be acknowledged; discard must be explicit. */
    PENDING_WRITES,

    /** §9: the one-time legacy-cache purge failed; Firestore-backed access stays blocked. */
    MIGRATION_BLOCKED,

    /** Another transition already froze the session; the caller should simply wait. */
    ALREADY_TRANSITIONING,
}

sealed interface TransitionStart {
    data object Started : TransitionStart
    data class Refused(val refusal: TransitionRefusal) : TransitionStart
}

/**
 * Account plan §4.1/§8.7: the teardown half of an identity change. [beginTransition] freezes
 * remote work and flushes queued SDK writes **while the old identity is still current**;
 * [completeTransition] finishes the switch (database scope, process restart); [abortTransition]
 * restores the old session when the credential step failed.
 *
 * Production binds [LocalSessionManager]. The default implementation is the unfenced test seam.
 */
interface SessionTransitions {
    /** §9: Firestore-backed account access is unavailable until the one-time purge succeeds. */
    fun isCloudReady(): Boolean

    suspend fun beginTransition(signOut: Boolean): TransitionStart

    /** The identity changed (or a sign-out finished): switch the scope and restart the process. */
    fun completeTransition(newUid: String?)

    suspend fun abortTransition()

    /** §8.7: the user explicitly confirmed discarding writes the server could not acknowledge. */
    fun approvePendingWriteDiscard()

    companion object {
        val Unfenced: SessionTransitions = object : SessionTransitions {
            override fun isCloudReady(): Boolean = true
            override suspend fun beginTransition(signOut: Boolean): TransitionStart =
                TransitionStart.Started
            override fun completeTransition(newUid: String?) = Unit
            override suspend fun abortTransition() = Unit
            override fun approvePendingWriteDiscard() = Unit
        }
    }
}
