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

    /**
     * Publisher boundary (used by the Phase 4 authenticator and Phase 3 provider only): the
     * vault was opened and verified for the current generation. Phase 2 has no vault yet, so
     * no production caller exists; an unverified boolean callback must never call this.
     */
    internal fun publishUnlocked() {
        if (_state.value != AppLockState.DISABLED) _state.value = AppLockState.UNLOCKED
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
        val token = expectedExternal
        if (token != null && isExpectedReturnValid(token)) return
        revoke()
    }

    /**
     * Host resume boundary. Consumes a pending token (single use) and keeps the session only
     * when it still matches the current generation and the 60-second window.
     */
    fun onHostResumed(): Boolean {
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

    private fun revoke() {
        if (_state.value == AppLockState.DISABLED) return
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
}

/** §8.8: same token, same generation, under 60 seconds since launch. */
internal fun externalReturnWithinWindow(startedAtElapsed: Long, nowElapsed: Long): Boolean {
    val elapsed = nowElapsed - startedAtElapsed
    return elapsed in 0..EXPECTED_EXTERNAL_WINDOW_MS
}
