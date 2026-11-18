package com.example.myexodus.accountability.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One shared in-flight [mint], awaited with a per-caller timeout.
 *
 * Why the mint runs in its own scope instead of the caller simply wrapping it in a timeout:
 * cancelling `signInAnonymously()` mid-flight frees the next caller to start a second
 * concurrent sign-in, which mints a second anonymous account (device-proven 2026-10-01). A
 * timed-out caller here only abandons its wait; every later caller joins the same flight
 * until it settles, and a success still lands in the SDK's `currentUser` for the next call.
 *
 * Not thread-safe on its own: callers must serialize (the resolver's SIGN_IN_LOCK does).
 */
internal class SingleFlightMint<T>(
    private val scope: CoroutineScope,
    private val mint: suspend () -> T?,
) {
    private var flight: Deferred<T?>? = null

    /** Joins the active flight or starts one, waiting at most [timeoutMs]. Null = the mint
     *  produced null, or this wait expired while the flight keeps running ([active]). */
    suspend fun awaitOrNull(timeoutMs: Long): T? {
        val current = flight?.takeIf { it.isActive }
            ?: scope.async { mint() }.also { flight = it }
        return withTimeoutOrNull(timeoutMs) { current.await() }
    }

    /** True while the last mint has not settled — a timed-out [awaitOrNull] is still running. */
    val active: Boolean get() = flight?.isActive == true
}
