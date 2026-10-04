package com.example.myexodus.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * Product activation for the website engine (the app-owned VPN). The tunnel is what blocks
 * website rules AND applies their daily limits, so it must be running whenever at least one
 * live website rule exists and the user has granted the one-time system consent — and it
 * must not burn battery when no website rule exists. Until 2026-10-04 the tunnel only ever
 * started from debug harnesses; this is the production path.
 *
 * Call sites (all idempotent, all routed through [ensure]):
 * - [com.example.myexodus.MainActivity.onResume] — app foreground, the same boundary the
 *   other refresh passes use.
 * - [com.example.myexodus.ui.rules.RulesScreen] — the moment rules are created/removed or
 *   the screen comes back from the consent dialog.
 * - [com.example.myexodus.blocking.BootReceiver] — boot and `install -r` (both kill the
 *   process; without this the tunnel would stay down until the app was next opened).
 *
 * Consent is the platform's own dialog ([VpnService.prepare]): never launched
 * automatically — the user reaches it through the explicit "turn on" row in Rules. A
 * `prepare()` that returns non-null means no consent on file; [ensure] then starts nothing
 * (the row is the only way in). Revocation (`onRevoke`) clears the platform's consent, so
 * the same row naturally resurfaces after a revoke.
 */
object VpnLifecycle {

    /** What [ensure] should do, given the tunnel's current state. */
    enum class Action { START, STOP, NONE }

    /**
     * The whole policy, pure: start when there is something to enforce (at least one live
     * website rule — block-only or limited, the tunnel serves both) and consent is on file;
     * stop when nothing is left to enforce. REVOKED is a startable state: revoke clears the
     * platform's consent, so nothing starts without a fresh grant — but once the user has
     * re-granted (the dialog the Rules row walks them through), this is what brings the
     * tunnel back. Without it a revoke→re-grant cycle dead-ends: the row disappears the
     * moment consent is on file again (device-found 2026-10-04), while the sticky REVOKED
     * state kept every start site a no-op.
     */
    fun decide(hasRules: Boolean, consented: Boolean, state: VpnEngineState): Action = when {
        hasRules && consented && (state == VpnEngineState.STOPPED || state == VpnEngineState.FAILED ||
            state == VpnEngineState.REVOKED) -> Action.START
        !hasRules && (state == VpnEngineState.STARTING || state == VpnEngineState.ACTIVE ||
            state == VpnEngineState.DEGRADED) -> Action.STOP
        else -> Action.NONE
    }

    /** True when the platform already holds this app's VPN consent (no dialog needed). */
    fun consented(context: Context): Boolean = runCatching {
        VpnService.prepare(context) == null
    }.getOrDefault(false)

    /** The system consent dialog to launch from an Activity, or null when already consented. */
    fun consentIntent(context: Context): Intent? = runCatching {
        VpnService.prepare(context)
    }.getOrNull()

    /** Run [decide] against live state and carry out the verdict. Never throws. */
    fun ensure(context: Context, hasRules: Boolean) {
        val action = runCatching {
            decide(hasRules, consented(context), DnsSinkholeVpnService.state)
        }.getOrElse {
            Log.w(TAG, "ensure skipped: ${it.javaClass.simpleName}")
            return
        }
        when (action) {
            Action.START -> {
                Log.i(TAG, "starting website tunnel (live website rules present)")
                start(context)
            }
            Action.STOP -> {
                Log.i(TAG, "stopping website tunnel (no live website rules left)")
                stop(context)
            }
            Action.NONE -> Unit
        }
    }

    fun start(context: Context) {
        runCatching {
            context.startService(
                Intent(context, DnsSinkholeVpnService::class.java)
                    .setAction(DnsSinkholeVpnService.ACTION_START),
            )
        }.onFailure { Log.w(TAG, "start failed: ${it.javaClass.simpleName}") }
    }

    fun stop(context: Context) {
        runCatching {
            context.startService(
                Intent(context, DnsSinkholeVpnService::class.java)
                    .setAction(DnsSinkholeVpnService.ACTION_STOP),
            )
        }.onFailure { Log.w(TAG, "stop failed: ${it.javaClass.simpleName}") }
    }

    private const val TAG = "MyExodusVpnLifecycle"
}
