package com.example.myexodus.account

import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which call failed, so the UI knows what "Try again" retries. */
enum class TransitionStep { PREPARE, FINALIZE, CANCEL }

/** The Account pane's view of the switch (plan §8.4). */
sealed interface GuestTransitionState {
    data object Idle : GuestTransitionState
    data object Preparing : GuestTransitionState

    /** Prepared on the server; the guest may now sign in to the target account. */
    data class Prepared(val intent: TransitionIntent) : GuestTransitionState
    data object Cancelling : GuestTransitionState
    data class Finalizing(val intent: TransitionIntent) : GuestTransitionState
    data class Completed(val intent: TransitionIntent, val terminatedPairs: Int) : GuestTransitionState
    data class Failed(
        val step: TransitionStep,
        val failure: TransitionFailure,
        val intent: TransitionIntent?,
    ) : GuestTransitionState
}

/**
 * Account plan §8.4/§6.2: owns the guest→existing-account switch. The journal is written before
 * the prepare call and retained across the identity change, so a lost response, a process death
 * or a failed sign-in attempt all resume from the same id/capability. Pairs are untouchable
 * until the target session exists — only finalize terminates them, and only the target session
 * can call it.
 *
 * [attach] wires the app-level collectors: finalization must not depend on a screen being open.
 */
@Singleton
class GuestTransitionCoordinator @Inject constructor(
    private val auth: AuthCoordinator,
    private val journal: GuestTransitionJournal,
    private val api: GuestTransitionApi,
    private val stager: GuestDataStager,
    private val quarantine: GuestQuarantine,
) {
    private val _state = MutableStateFlow<GuestTransitionState>(GuestTransitionState.Idle)
    val state: StateFlow<GuestTransitionState> = _state.asStateFlow()

    private val gate = Mutex()
    private val random = SecureRandom()

    fun attach(scope: CoroutineScope) {
        scope.launch {
            auth.state.collect { current ->
                when (current) {
                    is AuthState.PersistentActive -> finalizeIfPrepared(current.uid)
                    is AuthState.GuestActive -> restorePrepared()
                    else -> Unit
                }
            }
        }
        scope.launch {
            auth.events.collect { event ->
                if (event !is AuthEvent.Reauthenticated) return@collect
                // The coordinator sets PersistentActive right after the event; wait for it so
                // the retried finalize runs under the refreshed token, not Authenticating.
                val settled = auth.state.first {
                    it is AuthState.PersistentActive || it is AuthState.SignedOut
                }
                (settled as? AuthState.PersistentActive)?.let { finalizeIfPrepared(it.uid) }
            }
        }
    }

    /**
     * The user confirmed the disclosure. A fresh preparation persists the journal before the
     * call; a retained one replays the same id/capability (idempotent server-side).
     */
    suspend fun start(intent: TransitionIntent) = gate.withLock {
        // The durable identity, not the transient overlay: a retry after a refused sign-in
        // (AuthError on top of the guest) must remain possible — that is the §8.4 path.
        val guest = auth.durableState as? AuthState.GuestActive ?: return@withLock
        _state.value = GuestTransitionState.Preparing
        val record = when (val load = journal.load()) {
            JournalLoad.Unreadable -> {
                // Never clear it: a transient Keystore failure would otherwise destroy the only
                // copy of a capability the server still honours.
                fail(TransitionStep.PREPARE, TransitionFailure.JOURNAL_UNREADABLE, intent)
                return@withLock
            }
            is JournalLoad.Present -> load.record
            JournalLoad.Absent -> freshRecord(guest.uid, intent).also(journal::save)
        }
        // A prepared record is already bound to its intent server-side; the parameter only
        // applies to a record the server has not accepted yet.
        val chosen = if (record.prepared) record else record.copy(intent = intent).also(journal::save)
        // §6.2.3: the encrypted snapshot exists before any network call, for both intents,
        // while the guest session still owns the database. A failure refuses the switch.
        if (!stager.stage(guest.uid, chosen.transitionId, chosen.intent)) {
            fail(TransitionStep.PREPARE, TransitionFailure.QUARANTINE_FAILED, chosen.intent)
            return@withLock
        }
        try {
            prepareAndArm(chosen)
        } catch (e: GuestTransitionException) {
            fail(TransitionStep.PREPARE, e.failure, chosen.intent)
        }
    }

    /** Stay a guest. Final and retryable: the journal survives until the server confirms. */
    suspend fun cancel() = gate.withLock {
        val record = (journal.load() as? JournalLoad.Present)?.record
        if (record == null || !record.prepared) {
            journal.clear()
            disarm()
            return@withLock
        }
        _state.value = GuestTransitionState.Cancelling
        try {
            api.cancel(record.transitionId, record.capability)
            journal.clear()
            disarm()
        } catch (e: GuestTransitionException) {
            when (e.failure) {
                // No record left to cancel (never landed, or already finalized/cancelled).
                TransitionFailure.NOT_FOUND,
                TransitionFailure.FINALIZED,
                TransitionFailure.CANCELLED,
                -> {
                    journal.clear()
                    disarm()
                }
                else -> fail(TransitionStep.CANCEL, e.failure, record.intent)
            }
        }
    }

    /** Retry a failed finalization from the target session (also the post-reauth entry point). */
    suspend fun resumeFinalize() {
        val target = (auth.durableState as? AuthState.PersistentActive)?.uid ?: return
        finalizeIfPrepared(target)
    }

    /** Clears a terminal card; the journal (and thus a retry) is untouched. */
    fun dismiss() {
        when (_state.value) {
            is GuestTransitionState.Completed, is GuestTransitionState.Failed ->
                _state.value = GuestTransitionState.Idle
            else -> Unit
        }
    }

    private suspend fun prepareAndArm(record: GuestTransitionRecord) {
        var current = record
        var outcome = api.prepare(current.transitionId, current.capability, current.intent)
        if (outcome.state == SERVER_CANCELLED) {
            // A lost cancel response freed the server lease; the journal's own intent is the
            // user's standing choice, so one fresh preparation replaces the dead record.
            journal.clear()
            current = freshRecord(current.guestUid, current.intent)
            journal.save(current)
            outcome = api.prepare(current.transitionId, current.capability, current.intent)
        }
        if (outcome.state != SERVER_PREPARED && outcome.state != SERVER_EXPIRED_UNFINALIZED) {
            throw GuestTransitionException(TransitionFailure.UNKNOWN)
        }
        journal.save(current.copy(prepared = true))
        auth.setIdentitySwitchAllowed(true)
        _state.value = GuestTransitionState.Prepared(current.intent)
    }

    private suspend fun finalizeIfPrepared(targetUid: String) = gate.withLock {
        val record = (journal.load() as? JournalLoad.Present)?.record ?: return@withLock
        if (!record.prepared || record.guestUid == targetUid) return@withLock
        if (record.targetUid != targetUid) journal.save(record.copy(targetUid = targetUid))
        _state.value = GuestTransitionState.Finalizing(record.intent)
        try {
            val outcome = api.finalize(record.transitionId, record.capability, record.intent)
            finishFinalized(record, targetUid, outcome.terminatedPairIds.size)
        } catch (e: GuestTransitionException) {
            when (e.failure) {
                // §8.4: an elapsed lease needs a fresh target authentication, not a cancel.
                TransitionFailure.REAUTH_REQUIRED -> {
                    _state.value = GuestTransitionState.Prepared(record.intent)
                    auth.requireReauth(AccountOperation.GUEST_TRANSITION)
                }
                // The earlier call landed; only the local binding and the journal remain.
                TransitionFailure.FINALIZED -> finishFinalized(record, targetUid, 0)
                else -> fail(TransitionStep.FINALIZE, e.failure, record.intent)
            }
        }
    }

    /**
     * §8.4 step 6: binding the staged copy to the target UID is what makes it importable.
     * The journal survives until that binding is durable, so a binding retry replays the
     * idempotent finalize rather than losing the only path to the guest's data.
     */
    private suspend fun finishFinalized(
        record: GuestTransitionRecord,
        targetUid: String,
        terminatedPairs: Int,
    ) {
        when (record.intent) {
            TransitionIntent.IMPORT ->
                if (!quarantine.markFinalized(record.guestUid, record.transitionId, targetUid)) {
                    fail(TransitionStep.FINALIZE, TransitionFailure.QUARANTINE_UNSAVED, record.intent)
                    return
                }
            // §6.2.7: discard deletes the staged copy only after finalization succeeded.
            TransitionIntent.DISCARD ->
                quarantine.deleteFor(record.guestUid, record.transitionId)
        }
        journal.clear()
        disarm()
        _state.value = GuestTransitionState.Completed(record.intent, terminatedPairs)
    }

    private suspend fun restorePrepared() = gate.withLock {
        val record = (journal.load() as? JournalLoad.Present)?.record ?: return@withLock
        if (!record.prepared) return@withLock
        auth.setIdentitySwitchAllowed(true)
        if (_state.value is GuestTransitionState.Idle || _state.value is GuestTransitionState.Preparing) {
            _state.value = GuestTransitionState.Prepared(record.intent)
        }
    }

    private fun disarm() {
        auth.setIdentitySwitchAllowed(false)
        _state.value = GuestTransitionState.Idle
    }

    private fun fail(step: TransitionStep, failure: TransitionFailure, intent: TransitionIntent?) {
        _state.value = GuestTransitionState.Failed(step, failure, intent)
    }

    private fun freshRecord(guestUid: String, intent: TransitionIntent) = GuestTransitionRecord(
        transitionId = token(16),
        capability = token(32),
        intent = intent,
        guestUid = guestUid,
        prepared = false,
    )

    /** §8.4 shapes: 16 bytes → 22 chars, 32 bytes → 43 chars, both unpadded base64url. */
    private fun token(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(bytes).also(random::nextBytes))
}
