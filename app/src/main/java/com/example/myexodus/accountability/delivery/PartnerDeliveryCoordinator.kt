package com.example.myexodus.accountability.delivery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
