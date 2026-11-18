package com.example.myexodus.accountability.delivery

import android.content.Context
import android.util.Log
import com.example.myexodus.account.AuthCoordinator
import com.example.myexodus.account.AuthState
import com.example.myexodus.accountability.sync.PartnerNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * §6.1 step 4 signal sink and the §4.1 foreground trigger. There is no always-on service:
 * delivery starts when a producer commits a row (coalesced by the persistent wake marker),
 * when an Activity foregrounds, and on startup/periodic reconciliation. Every path is safe
 * to race — the drain's mutex serializes them and every outbox write is guarded.
 */
class PartnerDeliveryCoordinator(
    private val context: Context,
    private val outbox: ServiceEventOutbox,
    private val drain: PartnerEventDrain,
    private val ownerUid: () -> String?,
    auth: AuthCoordinator,
    /** §8.3.1: registration/rotation only while the vault-access gate is open. */
    private val registrationAllowed: () -> Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _registrationBlock = MutableStateFlow<PartnerRegistrationBlock?>(null)

    /** Last foreground drain's registration verdict; the Account pane prompts on it. Only a
     *  foreground drain may update it — the worker cannot register, so its null is not proof. */
    val registrationBlock: StateFlow<PartnerRegistrationBlock?> = _registrationBlock.asStateFlow()

    init {
        // §8.3.1 recovery: a completed reauthentication makes `auth_time` fresh, but the user
        // is already foregrounded, so no lifecycle transition would retry. Drain right away.
        scope.launch {
            var reauthSeen = false
            auth.state.collect { state ->
                when (state) {
                    is AuthState.ReauthRequired -> reauthSeen = true
                    is AuthState.PersistentActive -> if (reauthSeen) {
                        reauthSeen = false
                        if (registrationAllowed()) drainInProcess(allowRegistration = true)
                    }
                    is AuthState.GuestActive, is AuthState.SignedOut -> reauthSeen = false
                    else -> Unit
                }
            }
        }
    }

    /**
     * The recorder's post-commit kick. `requestWake` returns true only for false→true, so a
     * burst of captures while a job is already queued appends nothing.
     */
    suspend fun onEventCommitted() {
        if (outbox.requestWake()) enqueuePartnerEventSyncNow(context)
    }

    /**
     * §4.1 foreground transition. Runs in-process (no WorkManager dispatch latency) and is the
     * only drain that may register/rotate the signing key — [allowRegistration] carries the
     * §8.3.1 App Lock verdict from the caller.
     */
    suspend fun drainInProcess(allowRegistration: Boolean) {
        val result = try {
            drain.run(allowRegistration)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "foreground drain failed", e)
            return
        }
        // Plan Phase 1 diagnostics: a stalled registration must leave a trace, not just silence.
        if (result.claimed > 0 || result.registrationRequired) Log.i(TAG, "foreground drain: $result")
        if (allowRegistration) {
            _registrationBlock.value = result.registrationBlock
            // Fix A discoverability (owner 2026-11-15): the prompt also persists in the tray,
            // so a stalled registration is visible without opening Settings → Account. Every
            // actionable block counts (2026-11-18 field test: only reauth was surfaced, and a
            // silent enrollment stall went unnoticed on the partner device).
            val block = result.registrationBlock
            if (block != null && block.needsAttention) {
                PartnerNotifications.notifyRegistrationPaused(context, block)
            } else {
                PartnerNotifications.cancelRegistrationPaused(context)
            }
        }
        scheduleNextRetry(context, result.earliestNextAttemptMs)
    }

    /** Fire-and-forget foreground drain for callers that must not wait on the network (the
     *  pairing ceremony). The singleton outlives every Activity, so the scope does too. */
    fun kickForegroundDrain(allowRegistration: Boolean) {
        scope.launch { drainInProcess(allowRegistration) }
    }

    /**
     * §6.1 step 4 startup reconciliation: a crash between the wake marker write and the work
     * enqueue leaves the marker armed with nothing scheduled, which would swallow every later
     * kick. Clear it, then reschedule whenever rows are outstanding.
     */
    suspend fun reconcileOnStart() {
        outbox.clearWake()
        val uid = ownerUid() ?: return
        if (outbox.earliestNextAttempt(uid) == null) return
        enqueuePartnerEventSyncNow(context)
    }

    private companion object {
        const val TAG = "MyExodusPartnerEvents"
    }
}
