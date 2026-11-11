package com.example.myexodus.applock

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** §8.8: how long a trusted, app-initiated external activity may return without a new prompt. */
internal const val EXPECTED_EXTERNAL_WINDOW_MS = 60_000L

/**
 * Process-local lock state (plan §4.1). Never persists UNLOCKED and never holds an Activity.
 * Phase 2 owns the shell/lifecycle half; the authenticator and the atomic lease gate plug into
 * [publishUnlocked] in Phases 3-4, and migration states are driven by the Phase 5 journal.
 */
@Singleton
class AppLockCoordinator @Inject constructor(statusStore: AppLockStatusStore) {

    private val _state = MutableStateFlow(statusStore.startupState())
    val state: StateFlow<AppLockState> = _state.asStateFlow()

    /** Incremented on every session revocation; a prompt or lease from an older generation is void. */
    var sessionGeneration: Long = 0L
        private set

    /** Replaced on cancel, lock and prompt replacement so late callbacks cannot publish access. */
    var attemptId: Long = 0L
        private set

    private var expectedExternal: ExpectedExternalOperation? = null

    private var credentialChallenge: CredentialChallenge? = null

    /**
     * Publisher boundary (used by the Phase 4 authenticator and Phase 3 provider only): the
     * vault was opened and verified for the current generation. Phase 2 has no vault yet, so
     * no production caller exists; an unverified boolean callback must never call this.
     */
    internal fun publishUnlocked() {
        // §4.2: only the verified end of OPENING may publish UNLOCKED. A screen-off that
        // already moved the state to LOCKED must not be overwritten by a late open result.
        if (_state.value == AppLockState.OPENING) _state.value = AppLockState.UNLOCKED
    }

    /** §8.12: journal reconciliation at process start replaces the constructor's status read. */
    internal fun adoptRecoveredState(state: AppLockState) {
        if (_state.value == AppLockState.UNLOCKED) return
        _state.value = state
    }

    /** Non-unlock transitions for the controller's flows; UNLOCKED has one owner above. */
    internal fun publish(state: AppLockState) {
        if (state == AppLockState.UNLOCKED) return
        _state.value = state
    }

    /** §8.11: the disable commit is durable; the feature is off. */
    internal fun publishDisabled() {
        _state.value = AppLockState.DISABLED
    }

    /**
     * §8.8: the API 26-29 Keyguard sheet is a listed trusted external activity, but it stops the
     * host mid-attempt (AUTHENTICATING for unlock, UNLOCKED for disable), so it takes its own
     * attempt-scoped token instead of the unlocked-session one.
     */
    internal fun beginCredentialChallenge() {
        val current = _state.value
        if (current != AppLockState.AUTHENTICATING && current != AppLockState.UNLOCKED) return
        credentialChallenge = CredentialChallenge(
            attempt = attemptId,
            startedAtElapsed = SystemClock.elapsedRealtime(),
        )
    }

    /** Starts a new authentication attempt; callbacks from older attempts are rejected. */
    internal fun beginAttempt(): Long {
        credentialChallenge = null
        attemptId++
        return attemptId
    }

    internal fun isAttemptCurrent(attempt: Long): Boolean = attempt == attemptId

    /** §8.8: voids the current attempt without changing the state (an existing session remains). */
    internal fun abandonAttempt() {
        credentialChallenge = null
        attemptId++
    }

    /** Ends an attempt with a terminal state; never DISABLED-overriding and never UNLOCKED. */
    internal fun concludeAttempt(target: AppLockState) {
        credentialChallenge = null
        attemptId++
        if (target == AppLockState.UNLOCKED || _state.value == AppLockState.DISABLED) return
        _state.value = target
    }

    /**
     * §8.8: call immediately before launching a trusted external OS activity from an unlocked
     * session. Single use; issuing a new token replaces any earlier one.
     */
    fun beginExpectedExternalOperation(reason: String) {
        if (_state.value != AppLockState.UNLOCKED) return
        expectedExternal = ExpectedExternalOperation(
            reason = reason,
            startedAtElapsed = SystemClock.elapsedRealtime(),
            generation = sessionGeneration,
        )
    }

    /**
     * Host stop boundary. A configuration change never locks. A real app switch locks unless a
     * live expected-external token covers it; the stopped host is already offscreen, and
     * FLAG_SECURE covers the recents snapshot, so no extra concealment state is kept.
     */
    fun onHostStopped(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        credentialChallenge?.let { challenge ->
            if (isCredentialChallengeValid(challenge)) return
            revoke()
            return
        }
        val token = expectedExternal
        if (token != null && isExpectedReturnValid(token)) return
        revoke()
    }

    /**
     * Host resume boundary. Consumes a pending token (single use) and keeps the session only
     * when it still matches the current generation and the 60-second window.
     */
    fun onHostResumed(): Boolean {
        credentialChallenge?.let { challenge ->
            credentialChallenge = null
            if (isCredentialChallengeValid(challenge)) return true
            revoke()
            return false
        }
        val token = expectedExternal ?: return _state.value == AppLockState.UNLOCKED
        expectedExternal = null
        if (isExpectedReturnValid(token)) return true
        revoke()
        return false
    }

    /** §8.8/§4.2 rule 6: screen-off revokes the session and any in-flight unlock attempt. */
    fun onScreenOff() {
        expectedExternal = null
        revoke()
    }

    /** Manual lock ignores every external-operation exemption. */
    fun lockManually() {
        expectedExternal = null
        revoke()
    }

    private fun isExpectedReturnValid(token: ExpectedExternalOperation): Boolean =
        _state.value == AppLockState.UNLOCKED &&
            token.generation == sessionGeneration &&
            externalReturnWithinWindow(
                startedAtElapsed = token.startedAtElapsed,
                nowElapsed = SystemClock.elapsedRealtime(),
            )

    private fun isCredentialChallengeValid(challenge: CredentialChallenge): Boolean =
        challenge.attempt == attemptId &&
            externalReturnWithinWindow(
                startedAtElapsed = challenge.startedAtElapsed,
                nowElapsed = SystemClock.elapsedRealtime(),
            )

    private fun revoke() {
        credentialChallenge = null
        // §4.2: RECOVERY_REQUIRED keeps its evidence until the user retries; SETUP_REQUIRED
        // has no envelope yet, so a background stop returns to the prior valid state.
        when (_state.value) {
            AppLockState.DISABLED, AppLockState.RECOVERY_REQUIRED -> return
            AppLockState.SETUP_REQUIRED -> {
                attemptId++
                _state.value = AppLockState.DISABLED
                return
            }
            // §8.7/§9: a journaled migration reaches its safe point; lifecycle events conceal
            // the UI (the gate already does) but never abandon ENROLLING/DISABLING half-way.
            AppLockState.ENROLLING, AppLockState.DISABLING -> return
            else -> Unit
        }
        attemptId++
        if (_state.value == AppLockState.UNLOCKED) sessionGeneration++
        // LOCKING is published before LOCKED so a collector never observes an unlocked session
        // that is already draining; Phase 3 holds LOCKING across the lease drain.
        _state.value = AppLockState.LOCKING
        _state.value = AppLockState.LOCKED
    }

    private data class ExpectedExternalOperation(
        val reason: String,
        val startedAtElapsed: Long,
        val generation: Long,
    )

    private data class CredentialChallenge(
        val attempt: Long,
        val startedAtElapsed: Long,
    )
}

/** §8.8: same token, same generation, under 60 seconds since launch. */
internal fun externalReturnWithinWindow(startedAtElapsed: Long, nowElapsed: Long): Boolean {
    val elapsed = nowElapsed - startedAtElapsed
    return elapsed in 0..EXPECTED_EXTERNAL_WINDOW_MS
}
