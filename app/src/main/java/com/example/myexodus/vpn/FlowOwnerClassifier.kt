package com.example.myexodus.vpn

import android.app.role.RoleManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.myexodus.BuildConfig
import com.example.myexodus.blocking.roleHolders
import com.example.myexodus.domainusage.FlowOwner
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the UID that owns a flow crossing the TUN and classifies it as a browser (ADR #189).
 *
 * Uses [ConnectivityManager.getConnectionOwnerUid] (API 29+), which only an active VpnService
 * may query for connections in its own tunnel; it returns [Process.INVALID_UID] when the
 * 5-tuple is not found or is not observable by this UID. Called once per flow open — never on
 * the per-chunk relay path — so the binder call costs one round trip per connection, not per
 * packet, and the browser lookup itself is cached per UID.
 *
 * Every failure direction is [FlowOwner.UNKNOWN]: below API 29, an unresolved tuple, a
 * SecurityException during teardown, or an unknown package list. Unknown flows are never
 * metered, so the worst case is under-reporting, never a false block.
 *
 * ponytail: the UID→browser cache lives for the VPN session. Installing a new browser or
 * switching the default mid-session is picked up on the next VPN start; the cost meanwhile is
 * uncounted browser time, never a wrong block.
 */
class FlowOwnerClassifier(context: Context) {

    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val packages = app.packageManager
    private val browserByUid = ConcurrentHashMap<Int, Boolean>()

    fun tcpOwner(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): FlowOwner =
        ownerOf(android.system.OsConstants.IPPROTO_TCP, srcIp, srcPort, dstIp, dstPort)

    fun udpOwner(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): FlowOwner =
        ownerOf(android.system.OsConstants.IPPROTO_UDP, srcIp, srcPort, dstIp, dstPort)

    private fun ownerOf(protocol: Int, srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): FlowOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return FlowOwner.UNKNOWN
        val cm = connectivity ?: return FlowOwner.UNKNOWN
        val uid = try {
            cm.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort),
                InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort),
            )
        } catch (e: Exception) {
            return FlowOwner.UNKNOWN
        }
        if (uid == Process.INVALID_UID) return FlowOwner.UNKNOWN

        val browser = browserByUid.computeIfAbsent(uid) { isBrowserUid(it) }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "flow owner proto=$protocol uid=$uid browser=$browser")
        }
        return if (browser) FlowOwner.BROWSER else FlowOwner.NON_BROWSER
    }

    private fun isBrowserUid(uid: Int): Boolean {
        val uidPackages = try {
            packages.getPackagesForUid(uid)
        } catch (e: Exception) {
            null
        }
        val holders = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            roleHolders(app, RoleManager.ROLE_BROWSER)
        } else {
            emptyList()
        }
        return BrowserPackages.isBrowser(uidPackages, holders)
    }

    private companion object {
        const val TAG = "MyExodusVpn"
    }
}
