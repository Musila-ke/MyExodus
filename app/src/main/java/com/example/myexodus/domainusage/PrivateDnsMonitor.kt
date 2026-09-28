package com.example.myexodus.domainusage

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap

/**
 * §8 S7.5 / D-34 (API 28+): strict Private DNS is a blind spot this feature cannot see through —
 * the DNS answers it uses as fallback evidence never traverse the TUN. Status is tracked *per
 * network* so it can never outlive the network it was observed on: every callback recomputes the
 * effective value from the currently-tracked set rather than holding a sticky global flag.
 *
 * Strict means `isPrivateDnsActive() && getPrivateDnsServerName() != null` — the name is only
 * reported for strict mode (opportunistic DoT has no configured name and is not a bypass).
 */
@RequiresApi(28)
class PrivateDnsMonitor(
    private val context: Context,
    private val onStrictChanged: (Boolean) -> Unit,
) {

    private val strictByNetwork = ConcurrentHashMap<Network, Boolean>()
    private val lock = Any()
    private var lastReported = false

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            strictByNetwork[network] =
                linkProperties.isPrivateDnsActive && linkProperties.privateDnsServerName != null
            recompute()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            // Capabilities alone do not carry the Private DNS state; recompute so a network that
            // never delivers link properties cannot stay unaccounted for in [strictByNetwork].
            recompute()
        }

        override fun onLost(network: Network) {
            strictByNetwork.remove(network)
            recompute()
        }
    }

    /** §6.8: register on successful start; returns the unregister action (idempotent). */
    fun start(): () -> Unit {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return NO_OP_UNREGISTER
        return try {
            cm.registerNetworkCallback(request, callback)
            val unregister: () -> Unit = { runCatching { cm.unregisterNetworkCallback(callback) } }
            unregister
        } catch (_: Exception) {
            // Registration failing must not stop the Engine: the banner is a warning, not a gate.
            // The class name is deliberately not interpolated — S-5 keeps this package's logs
            // counter-only, and the S1.4 CI check enforces that by forbidding interpolation.
            Log.w(TAG, "private-dns monitor not registered")
            NO_OP_UNREGISTER
        }
    }

    private fun recompute() {
        val strict = strictByNetwork.values.any { it }
        synchronized(lock) {
            if (strict == lastReported) return
            lastReported = strict
        }
        onStrictChanged(strict)
    }

    private companion object {
        const val TAG = "MyExodusVpn"
        val NO_OP_UNREGISTER: () -> Unit = {}
    }
}
