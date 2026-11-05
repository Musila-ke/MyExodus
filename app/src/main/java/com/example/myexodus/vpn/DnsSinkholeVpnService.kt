package com.example.myexodus.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.myexodus.BuildConfig
import com.example.myexodus.MainActivity
import com.example.myexodus.R
import com.example.myexodus.blocking.RulesSnapshotPublisher
import com.example.myexodus.blocking.SnapshotState
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.domainusage.ClientDataResult
import com.example.myexodus.domainusage.DnsQueryResult
import com.example.myexodus.domainusage.DomainRuleProvider
import com.example.myexodus.domainusage.DomainUsageEngine
import com.example.myexodus.domainusage.DomainUsageHost
import com.example.myexodus.domainusage.EngineMode
import com.example.myexodus.domainusage.FlowControl
import com.example.myexodus.domainusage.FlowOwner
import com.example.myexodus.domainusage.FlowTap
import com.example.myexodus.domainusage.OwnedBytes
import com.example.myexodus.domainusage.PassThroughTap
import com.example.myexodus.domainusage.UdpAdmission
import com.example.myexodus.notifications.NotificationTapTarget
import dagger.hilt.android.AndroidEntryPoint
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Slice 3 VPN PoC spike (plan §7 step 3, §8.4) — full-tunnel DNS sinkhole:
 *  - TUN 192.0.2.1/32 + fd00:14:14::1/128, routes 0.0.0.0/0 + ::/0, MTU 1500 (ADR #186),
 *    addDnsServer() advertising resolvers on the TUN subnet (plan §6).
 *    TUN_V4 is TEST-NET (RFC 5737), never used on real networks: on the X6532 the
 *    carrier APN (ccmni1) owns a kernel 10.0.0.0/8 route, so a 10.x TUN address
 *    (and its DNS-server traffic) is hijacked away from the TUN; 192.0.2.1 is
 *    immune to every LAN/carrier collision by reservation.
 *  - DNS design (OEM-independent, no privileged ports): the advertised resolvers
 *    (192.0.2.53 / fd00:14:14::53) are on the TUN subnet but are NOT TUN addresses,
 *    so the resolver's queries take the full-tunnel default route INTO the TUN
 *    device and hit handleDns. A query to a TUN's OWN address is delivered locally
 *    by the kernel and never traverses the TUN — and a local port-53 listener is
 *    EACCES on OEM builds with ip_unprivileged_port_start > 0 (XOS sets 1024).
 *  - UDP/53: blocked qnames answered locally with NXDOMAIN; all other queries forwarded to
 *    a real upstream resolver over protect()-ed sockets; replies re-addressed and keyed by
 *    transaction id.
 *  - Everything else (all non-53 traffic, including TCP) is forwarded byte-for-byte over
 *    protect()-ed sockets (transparent TCP relay with per-flow workers) so the device's
 *    general connectivity is preserved — the plan's hard requirement, §8.4.
 *  - Private DNS (DoT) detected via Settings.Global at start; reported, not intercepted.
 *  - onRevoke: state REVOKED + persistent notification, no auto-rebind (plan §8.4).
 *
 * Engine scope (Slice 4, plan §8.4): STOPPED/STARTING/ACTIVE/DEGRADED/FAILED/REVOKED health
 * machine classified by [EngineHealth] — "VPN interface up" and "filtering functioning" are
 * checked independently; ACTIVE requires TUN up AND both DNS loops alive AND policy loaded AND
 * no Private DNS bypass. Blocked domains come from the policy snapshot (Room WEBSITE rules via
 * [RulesSnapshotPublisher]) — see [DomainBlocklist] for matching semantics and its documented
 * ceilings (no time-window evaluation at DNS layer; path-pinned rules are the Accessibility
 * engine's job). TCP/53 and TCP/853 ride the transparent relay byte-for-byte (no TCP-DNS
 * parsing); ICMP and IP fragments are dropped. The relay's device-facing send side honours the
 * device's advertised window and retransmits unacknowledged segments ([TcpSendWindow], ADR #175);
 * the engine's inspection hold is the only device-side buffering.
 */
enum class VpnEngineState { STOPPED, STARTING, ACTIVE, DEGRADED, FAILED, REVOKED }

@AndroidEntryPoint
class DnsSinkholeVpnService : VpnService() {

    @Inject lateinit var publisher: RulesSnapshotPublisher
    @Inject lateinit var engine: DomainUsageEngine
    @Inject lateinit var ruleProvider: DomainRuleProvider
    @Inject lateinit var usageHost: DomainUsageHost
    @Inject lateinit var storeFactory: AppRulesStoreFactory

    companion object {
        const val TAG = "MyExodusVpn"
        const val ACTION_START = "com.example.myexodus.vpn.START"
        const val ACTION_STOP = "com.example.myexodus.vpn.STOP"
        const val TUN_V4 = "192.0.2.1" // TEST-NET: see class doc — a 10.x TUN collided with the carrier APN on X6532
        const val TUN_V6 = "fd00:14:14::1"
        // Advertised DNS server: on the TUN subnet but NOT a TUN address, so the resolver's
        // queries route into the TUN device (a query to a TUN's own address is delivered
        // locally instead, and binding port 53 is EACCES on XOS: ip_unprivileged_port_start=1024).
        const val DNS_V4 = "192.0.2.53"
        const val DNS_V6 = "fd00:14:14::53"
        const val UPSTREAM_DNS_V4 = "8.8.8.8"
        const val UPSTREAM_DNS_V6 = "2001:4860:4860::8888"
        const val FLOW_IDLE_SWEEP_MS = 300_000L

        /** Bounded upstream reads: an idle flow still gets its hole-repair timer run. */
        const val RELAY_READ_TIMEOUT_MS = 1_000

        /** Window-full polling slice on the relay thread (never a busy wait). */
        const val SEND_WAIT_SLICE_MS = 100L

        /** Bounded wait for the tail to be acknowledged before FIN + teardown. */
        const val FIN_DRAIN_MS = 2_000L
        const val DRAIN_POLL_MS = 50L

        /** SOS §8.13: GMS is disallowed from the TUN so FCM push delivery is never
         *  sinkholed (no runtime permission needed for a disallow entry). */
        const val GMS_PACKAGE = "com.google.android.gms"

        @Volatile var state: VpnEngineState = VpnEngineState.STOPPED; private set
        @Volatile var reason: String = ""; private set
        @Volatile var privateDnsMode: String = ""; private set
        @Volatile var privateDnsSpecifier: String = ""; private set

        private val isnRandom = Random()
    }

    private val flows = ConcurrentHashMap<String, TcpFlow>()
    private val udpFlows = ConcurrentHashMap<String, UdpFlow>()

    /** ADR #189: per-flow owner lookup, initialized on the first relayed flow after onCreate. */
    private val flowOwners by lazy { FlowOwnerClassifier(this) }
    private val pendingDns = ConcurrentHashMap<String, PendingDns>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * §4.3's two single-thread executors: `domainusage-tick` owns the ledger pass, `domainusage-db`
     * every Room touch of the feature. The executor spawns its thread on the first task, so an
     * `OFF`-mode session starts neither; both are shut down in [stopAll].
     */
    private val tickExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "domainusage-tick") }
    private val dbExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "domainusage-db") }
    private val tickDispatcher: CoroutineDispatcher = tickExecutor.asCoroutineDispatcher()
    private val dbDispatcher: CoroutineDispatcher = dbExecutor.asCoroutineDispatcher()

    @Volatile private var blocklist: DomainBlocklist = DomainBlocklist.EMPTY
    @Volatile private var dnsLoop4Alive = false
    @Volatile private var dnsLoop6Alive = false
    @Volatile private var healthCode = ""

    @Volatile private var running = false
    // Volatile so the start worker's publication and stopAll's teardown of these fields form
    // the Dekker-style handshake that [startSinkhole] and [stopAll] rely on (finding #21).
    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var tunOut: FileOutputStream? = null
    @Volatile private var dnsSocket4: DatagramSocket? = null
    @Volatile private var dnsSocket6: DatagramSocket? = null

    /** Bumped by every [stopAll]; a start worker captured it before its establish (finding #21). */
    @Volatile private var stopGeneration = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSinkhole()
            // START is accepted from any non-running terminal state: STOPPED (fresh), FAILED
            // (the retry row), REVOKED (post re-consent recovery). STARTING/ACTIVE/DEGRADED
            // are already up — a second establish would only churn the tunnel.
            else -> if (state != VpnEngineState.STARTING && state != VpnEngineState.ACTIVE &&
                state != VpnEngineState.DEGRADED
            ) {
                // Claimed here (main thread), not inside the worker: two START commands in
                // quick succession must not both spawn an establish while the first worker's
                // own assignment is still pending (finding #21).
                state = VpnEngineState.STARTING
                thread(name = "vpn-start") { startSinkhole() }
            }
        }
        // START_NOT_STICKY: no auto-restart — plan §8.4 forbids rebind loops.
        return START_NOT_STICKY
    }

    private fun startSinkhole() {
        // Generation captured before any work: a STOP that lands while this worker is
        // mid-establish bumps it, and the handshake below then unwinds instead of letting a
        // late `running = true` re-assert a tunnel no owner knows about (finding #21). The
        // STARTING state itself is claimed by onStartCommand on the main thread.
        val generation = stopGeneration
        try {
            detectPrivateDns()
            publisher.loadFastPath() // sync policy cache first; authoritative reload follows async
            rebuildBlocklist()
            scope.launch { publisher.loadFromDb() }
            val fd = Builder().apply {
                // INV-9: the app's own traffic must never enter the TUN — it would be metered
                // against its own rules and could deadlock the upstream DNS sockets.
                addDisallowedApplication(BuildConfig.APPLICATION_ID)
                // SOS §8.13: GMS stays outside the TUN so FCM push wake-ups (the nudge
                // transport) are never subject to the sinkhole. Every other app's traffic
                // remains filtered. NameNotFoundException = GMS-less device, nothing to
                // exclude.
                try {
                    addDisallowedApplication(GMS_PACKAGE)
                } catch (_: PackageManager.NameNotFoundException) {
                    // GMS-less device: nothing to exclude from the TUN.
                }
                addAddress(TUN_V4, 32)
                addAddress(TUN_V6, 128)
                addRoute("0.0.0.0", 0)
                addRoute("::", 0)
                // Advertised resolvers are on the TUN subnet but are NOT TUN addresses, so
                // queries route through the default route into the TUN device (see class doc).
                addDnsServer(DNS_V4)
                addDnsServer(DNS_V6)
                setMtu(VpnPacket.MTU)
            }.establish()
            if (fd == null) { fail("${EngineHealth.FAILED_TUN_SETUP} prepare-consent-missing"); return }
            val s4: DatagramSocket
            val s6: DatagramSocket
            try {
                s4 = DatagramSocket().also { protect(it) }
                s6 = DatagramSocket().also { protect(it) }
            } catch (e: IOException) {
                fd.closeQuietly()
                fail("${EngineHealth.FAILED_DNS_BIND} ${e.javaClass.simpleName}")
                return
            }
            tunFd = fd
            tunOut = FileOutputStream(fd.fileDescriptor)
            dnsSocket4 = s4
            dnsSocket6 = s6
            running = true
            // Dekker-style handshake with stopAll: it bumps stopGeneration before reading the
            // tunnel fields; this worker publishes them (all volatile) before reading the
            // generation — so at least one side sees the other. Only then may the loops spawn.
            if (generation != stopGeneration) {
                // Losing side: STOP raced the establish. Tear down what was published,
                // identity-checked so a newer START that already took the fields is untouched.
                if (tunFd === fd) {
                    running = false
                    tunFd = null; tunOut = null; dnsSocket4 = null; dnsSocket6 = null
                }
                fd.closeQuietly()
                s4.closeQuietly()
                s6.closeQuietly()
                return
            }
            thread(name = "vpn-tun") { pump(fd) }
            thread(name = "vpn-dns4") { dnsReceiveLoop(s4, isV6 = false) }
            thread(name = "vpn-dns6") { dnsReceiveLoop(s6, isV6 = true) }
            thread(name = "vpn-sweep") { sweepLoop() }
            launchDomainUsage()
            observePolicy()
            evaluateHealth()
            runCatching {
                val tun = NetworkInterface.getNetworkInterfaces().toList().firstOrNull { it.name.contains("tun") }
                // A-04/INV-7: interface addresses are device IPs — debug diagnostics only.
                if (BuildConfig.DEBUG) Log.i(TAG, "tun iface ${tun?.name} addresses=${tun?.inetAddresses?.toList()?.joinToString { it.hostAddress } ?: "NONE"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            fail("exception: ${e.javaClass.simpleName}")
        }
    }

    /**
     * §8 S7.3: the Engine's startup, once the TUN is established so no rule can enforce against a
     * day whose totals were never loaded. The load itself runs on `domainusage-db` and retries
     * there; until it succeeds every hook stays pass-through (fail open), so this never delays
     * the relay. Cancelled with [scope] when the service stops.
     */
    private fun launchDomainUsage() {
        scope.launch {
            engine.start(
                rules = ruleProvider.rules,
                seedRevisions = ruleProvider::seedRevisions,
                // §6.8: re-attempts the open too — the rules store can fail into recovery.
                dao = { storeFactory.open()?.ruleUsageDao() },
                host = usageHost,
                tickDispatcher = tickDispatcher,
                dbDispatcher = dbDispatcher,
            )
        }
    }

    /** Plan §6: engines reload their in-memory snapshot as policy changes. */
    private fun observePolicy() {
        scope.launch {
            publisher.state.collect { s ->
                when (s) {
                    is SnapshotState.Ready -> { rebuildBlocklist(); evaluateHealth() }
                    is SnapshotState.Degraded -> evaluateHealth() // policy lost -> DEGRADED
                    else -> Unit // Uninitialized/Loading: last snapshot still in force
                }
            }
        }
    }

    private fun rebuildBlocklist() {
        blocklist = DomainBlocklist.fromUrlPatterns(publisher.snapshot.urlRules)
        Log.i(TAG, "policy blocklist: ${blocklist.size} host rules (snapshot v${publisher.snapshot.version})")
    }

    /** Plan §8.4: never conflate "TUN up" with "filtering functioning". */
    private fun evaluateHealth() {
        if (!running) return
        val privateDnsActive = privateDnsMode.isNotEmpty() && privateDnsMode != "off" && privateDnsMode != "unknown"
        val (newState, code) = EngineHealth.decide(
            tunUp = tunFd != null,
            dnsLoopsAlive = dnsLoop4Alive && dnsLoop6Alive,
            policyLoaded = publisher.state.value !is SnapshotState.Degraded,
            privateDnsActive = privateDnsActive,
        )
        // Report on (state, code) change, not state alone: DEGRADED(DNS_LOOP_DOWN) must
        // re-classify to DEGRADED(PRIVATE_DNS_ACTIVE) once the loops come up.
        if (newState == state && code == healthCode) return
        state = newState
        healthCode = code
        reason = if (code.isEmpty()) "" else "$code private-dns=$privateDnsMode${if (privateDnsSpecifier.isNotEmpty()) " specifier=$privateDnsSpecifier" else ""}"
        Log.i(TAG, "MARKER: vpn-state $newState $code")
    }

    private fun detectPrivateDns() {
        if (Build.VERSION.SDK_INT < 28) { privateDnsMode = "unsupported(<28)"; return }
        // Literal setting keys: the constants were trimmed from the compileSdk 37 stubs
        // (see memory: compileSdk 37 stub removals). Keys are the documented values.
        privateDnsMode = Settings.Global.getString(contentResolver, "private_dns_mode") ?: "unknown"
        privateDnsSpecifier = Settings.Global.getString(contentResolver, "private_dns_specifier") ?: ""
    }

    // ---- TUN pump ----

    private fun pump(fd: ParcelFileDescriptor) {
        val buf = ByteArray(2048)
        try {
            FileInputStream(fd.fileDescriptor).use { input ->
                while (running) {
                    val n = input.read(buf)
                    if (n == -1) { Log.w(TAG, "tun read EOF"); break }
                    if (n == 0) continue // spurious zero-read on some OEM TUN kernels — not EOF
                    dispatch(buf.copyOf(n))
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "tun read failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (running) stopSinkhole()
        }
    }

    private var dnsQueriesSeen = 0

    private var pktLog = 0
    private var firstSegHex = true

    private fun dispatch(packet: ByteArray) {
        val ip = VpnPacket.parseIp(packet)
        if (ip == null) {
            if (++pktLog <= 10 && BuildConfig.DEBUG) Log.w(TAG, "tun drop parse v${packet[0].toInt() ushr 4} len=${packet.size} hex=${packet.take(48).joinToString("") { "%02x".format(it) }}")
            return
        }
        if (ip.protocol != VpnPacket.PROTO_UDP && ip.protocol != VpnPacket.PROTO_TCP && ++pktLog <= 10) {
            Log.i(TAG, "tun pkt v${ip.version} proto=${ip.protocol} len=${packet.size}")
        }
        when (ip.protocol) {
            VpnPacket.PROTO_TCP -> handleTcp(ip, packet)
            VpnPacket.PROTO_UDP -> {
                val udp = VpnPacket.parseUdp(ip, packet)
                if (udp?.dstPort == 53 && ++dnsQueriesSeen <= 5 && BuildConfig.DEBUG) {
                    Log.i(TAG, "tun dns query v${ip.version} src=${ip.src[ip.src.size - 1]} size=${packet.size}")
                }
                handleUdp(ip, packet)
            }
            // ICMP and other protocols dropped (ponytail: not needed by the spike gates)
        }
    }

    private fun writeTun(packet: ByteArray): Boolean {
        return try { tunOut?.write(packet); true } catch (e: IOException) {
            Log.w(TAG, "tun write failed: ${e.message ?: e.javaClass.simpleName}")
            false // tun closed — the pump thread notices and tears down
        }
    }

    // ---- DNS ----

    private fun handleUdp(ip: VpnPacket.IpHeader, packet: ByteArray) {
        val udp = VpnPacket.parseUdp(ip, packet) ?: return
        if (udp.dstPort == 53) { handleDns(ip, udp, packet); return }
        forwardUdp(ip, udp, packet) // non-53 UDP: byte-for-byte forward (plan §6)
    }

    /** TUN-traversing DNS (queries addressed to a non-TUN resolver, e.g. hardcoded DNS apps). */
    private fun handleDns(ip: VpnPacket.IpHeader, udp: VpnPacket.UdpHeader, packet: ByteArray) {
        val query = DnsMessages.parseQuery(packet, udp.payloadOffset) ?: return
        // Two decision sites compose here (S0 hazard 1): the policy blocklist stays authoritative
        // and answers first; the usage shaper only sees queries the blocklist did not answer, so a
        // query is never answered twice.
        if (blocklist.isBlocked(query.qname, System.currentTimeMillis())) {
            val reply = DnsMessages.buildNxDomain(query, packet, udp.payloadOffset)
            val out = VpnPacket.buildUdpPacket(ip.dst, ip.src, udp.dstPort, udp.srcPort, reply)
            val written = writeTun(out)
            // A-04/INV-7: the qname is user DNS data — debug diagnostics only.
            if (BuildConfig.DEBUG) Log.i(TAG, "nxdomain ${query.qname} v${ip.version} rule=policy written=$written")
            return
        }
        val payload = packet.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        val decision = engine.onDnsQuery(payload, payload.size)
        if (decision is DnsQueryResult.Respond) {
            val out = VpnPacket.buildUdpPacket(ip.dst, ip.src, udp.dstPort, udp.srcPort, decision.bytes, 0, decision.length)
            // INV-7: no qname/IP in this feature's logs — only sizes and outcomes.
            Log.i(TAG, "dns shaped v${ip.version} len=${decision.length} written=${writeTun(out)}")
            return
        }
        pendingDns["${ip.version}:${query.id}"] = PendingDns(ip.src, udp.srcPort, ip.dst)
        forwardUpstream(ip.isV6, packet, udp.payloadOffset, udp.payloadLength, "${ip.version}:${query.id}")
    }

    private fun forwardUpstream(isV6: Boolean, packet: ByteArray, offset: Int, length: Int, pendingKey: String) {
        val socket = (if (isV6) dnsSocket6 else dnsSocket4) ?: return
        try {
            val upstream = InetAddress.getByName(if (isV6) UPSTREAM_DNS_V6 else UPSTREAM_DNS_V4)
            socket.send(DatagramPacket(packet, offset, length, upstream, 53))
        } catch (e: IOException) {
            pendingDns.remove(pendingKey)
        }
    }

    private fun dnsReceiveLoop(socket: DatagramSocket, isV6: Boolean) {
        if (isV6) dnsLoop6Alive = true else dnsLoop4Alive = true
        evaluateHealth() // the loop is up now — may flip an early DEGRADED(DNS_LOOP_DOWN) to ACTIVE
        // EDNS0 answers can exceed any MTU-sized buffer (DNSSEC records); a short buffer truncates
        // them silently (recvfrom(2)), handing the resolver a malformed reply.
        val buf = ByteArray(VpnPacket.MAX_UDP_DATAGRAM)
        try {
            while (running) {
                val dp = DatagramPacket(buf, buf.size)
                try { socket.receive(dp) } catch (e: IOException) { break }
                if (dp.length < 12) continue
                val id = VpnPacket.readShort(buf, 0) and 0xFFFF
                val pending = pendingDns.remove("${if (isV6) 6 else 4}:$id") ?: continue
                val payload = buf.copyOf(dp.length)
                engine.onDnsResponse(payload, payload.size) // §6.2: observe the real answer first
                writeTun(VpnPacket.buildUdpPacket(pending.dnsIp, pending.deviceIp, 53, pending.devicePort, payload))
            }
        } finally {
            if (isV6) dnsLoop6Alive = false else dnsLoop4Alive = false
            if (running) evaluateHealth() // loop died while running: filtering is degraded
        }
    }

    /** One protect()-ed upstream socket per 5-tuple (ADR #70): two apps sharing a server and port
     *  get their replies back to the right requester. The Engine decides admission once per tuple. */
    private fun forwardUdp(ip: VpnPacket.IpHeader, udp: VpnPacket.UdpHeader, packet: ByteArray) {
        val key = udpKey(ip, udp)
        var flow = udpFlows[key]
        if (flow == null) {
            val candidate = UdpFlow(ip.src, udp.srcPort, ip.dst, udp.dstPort)
            val owner = if (engine.mode == EngineMode.OFF) FlowOwner.UNKNOWN
            else flowOwners.udpOwner(ip.src, udp.srcPort, ip.dst, udp.dstPort)
            when (val admission = engine.udpFlowOpened(ip.dst, udp.dstPort, candidate, owner)) {
                // §6.4/§6.10: attributed to an exhausted rule under ENFORCE. No socket is opened
                // for it; the datagram is refused with an ICMP Port-Unreachable instead of a
                // silent drop, so QUIC clients fall back to TCP within a second, not a timeout.
                UdpAdmission.Drop -> {
                    nudgePortUnreachable(ip, packet)
                    return
                }
                is UdpAdmission.Allow -> {
                    val s = DatagramSocket().also { protect(it) }
                    candidate.socket = s
                    candidate.tap = admission.tap
                    udpFlows[key] = candidate
                    thread(name = "vpn-udp-$key") { udpReceiveLoop(candidate) }
                    flow = candidate
                }
            }
        }
        val result = flow.tap.onClientData(packet, udp.payloadOffset, udp.payloadLength)
        val bytes: ByteArray
        val offset: Int
        val length: Int
        var owned: OwnedBytes? = null
        when (result) {
            // §8 S6.3: a mid-tuple Reset is the moment the rule exhausted — drop after the nudge.
            ClientDataResult.Reset -> {
                nudgePortUnreachable(ip, packet)
                return
            }
            ClientDataResult.Hold -> return // consumed (TLS inspection holds it)
            is ClientDataResult.Forward -> {
                owned = result.data
                bytes = result.data.bytes
                offset = 0
                length = result.data.length
            }
            ClientDataResult.PassThrough -> {
                bytes = packet
                offset = udp.payloadOffset
                length = udp.payloadLength
            }
        }
        try {
            flow.socket!!.send(DatagramPacket(bytes, offset, length, InetAddress.getByAddress(ip.dst), udp.dstPort))
            flow.lastActivity = System.currentTimeMillis()
        } catch (e: IOException) {
            // upstream unreachable — drop; the device retries
        } finally {
            owned?.release() // INV-13: released only after the datagram was written out
        }
    }

    /**
     * §6.10: refuse one datagram that an exhausted rule owns. Part of the TUN/relay thread's
     * normal drop path, so it may never throw or block — a construction failure is counted
     * (`icmp_build_fail`, INV-14) and falls back to the silent drop it replaced.
     */
    private fun nudgePortUnreachable(ip: VpnPacket.IpHeader, packet: ByteArray) {
        val icmp = VpnPacket.buildIcmpPortUnreachable(ip, packet)
        engine.onIcmpNudge(icmp != null && writeTun(icmp))
    }

    private fun udpKey(ip: VpnPacket.IpHeader, udp: VpnPacket.UdpHeader): String =
        "${ip.version}:${InetAddress.getByAddress(ip.src).hostAddress}:${udp.srcPort}->" +
            "${InetAddress.getByAddress(ip.dst).hostAddress}:${udp.dstPort}"

    private fun udpReceiveLoop(flow: UdpFlow) {
        // Protocol maximum, never [VpnPacket.MTU]: see [VpnPacket.MAX_UDP_DATAGRAM].
        val buf = ByteArray(VpnPacket.MAX_UDP_DATAGRAM)
        while (running) {
            val dp = DatagramPacket(buf, buf.size)
            try { flow.socket!!.receive(dp) } catch (e: IOException) { break }
            flow.tap.onServerData(dp.length)
            writeTun(VpnPacket.buildUdpPacket(flow.dstIp, flow.deviceIp, flow.dstPort, flow.devicePort, buf.copyOf(dp.length)))
        }
    }

    // ---- transparent TCP relay ----

    private fun handleTcp(ip: VpnPacket.IpHeader, packet: ByteArray) {
        val tcp = VpnPacket.parseTcp(ip, packet)
        if (tcp == null) {
            if (++pktLog <= 10 && BuildConfig.DEBUG) Log.w(TAG, "tun drop tcp-parse v${ip.version} len=${packet.size} hex=${packet.take(64).joinToString("") { "%02x".format(it) }}")
            return
        }
        val key = tcpKey(ip, tcp)
        val flow = flows[key]

        if (tcp.syn && !tcp.ackFlag) {
            if (flow != null) {
                if (BuildConfig.DEBUG) Log.i(TAG, "tcp syn retransmit key=$key payload=${tcp.payloadLength}")
                if (flow.connected.get() && tcp.payloadLength == 0) sendSynAck(flow) // SYN retransmit after connect
                return
            }
            // ISN chosen once per flow: re-randomizing it on SYN retransmit made the device
            // challenge every re-sent SYN-ACK (out-of-window SYN -> TCPSYNChallenge storm).
            val f = TcpFlow(
                key, ip.src, tcp.srcPort, ip.dst, tcp.dstPort, ip.isV6,
                tcp.seq + 1, isnRandom.nextInt(), tcp.window,
            )
            flows[key] = f
            // After the map insert: an exhausted-rule admission resets the flow, and that reset
            // must find (and remove) the flow it decided against.
            val owner = if (engine.mode == EngineMode.OFF) FlowOwner.UNKNOWN
            else flowOwners.tcpOwner(ip.src, tcp.srcPort, ip.dst, tcp.dstPort)
            f.tap = engine.tcpFlowOpened(ip.dst, tcp.dstPort, f, owner)
            if (BuildConfig.DEBUG) Log.i(TAG, "tcp syn flow $key seq=${tcp.seq} payload=${tcp.payloadLength} win=${tcp.window} hex=${packet.take(64).joinToString("") { "%02x".format(it) }}")
            thread(name = "vpn-connect-$key") { connectUpstream(f) }
            return
        }
        if (flow == null) return // never saw the SYN; the device retransmits it

        if (tcp.rst) { if (BuildConfig.DEBUG) Log.i(TAG, "tcp dev rst key=$key ack=${tcp.ack} win=${tcp.window}"); teardown(flow); return }
        // Every ACK-bearing segment carries the device's current receive window; the send side
        // reserves sequence space from it. Ignoring these is what wedged downloads on a
        // zero window (device-proven 2026-10-18, ADR #175).
        if (tcp.ackFlag) {
            flow.sendWindow.onAck(tcp.seq, tcp.ack, tcp.window, SystemClock.elapsedRealtime())
        }
        if (tcp.payloadLength > 0 && flow.connected.get()) {
            when {
                Integer.compareUnsigned(tcp.seq, flow.ackToDevice) < 0 ->
                    sendAck(flow) // device retransmit: re-ACK only, never re-forward
                Integer.compareUnsigned(tcp.seq, flow.ackToDevice) > 0 ->
                    Unit // gap: drop; the device retransmits from the lost byte
                else -> try {
                    // The Engine may hold these bytes (TLS SNI inspection) or hand back a copy to
                    // forward instead; either way the device is acked as if forwarded, so a
                    // retransmit is re-acked (never re-inspected) rather than replayed.
                    // Each forwarded segment is ACKed immediately. Nothing else acks the device on
                    // this path when the upstream stays silent (a plain upload): the piggyback in
                    // relayLoop only exists while data flows back, and the retransmit re-ACK only
                    // fires after the device's RTO. Without this the device's window advances one
                    // RTO at a time — uploads crawled at ~1.5 KB/s (PF-1, 2026-09-21).
                    when (val r = flow.tap.onClientData(packet, tcp.payloadOffset, tcp.payloadLength)) {
                        ClientDataResult.Hold -> {
                            flow.ackToDevice = tcp.seq + tcp.payloadLength
                            flow.lastActivity = System.currentTimeMillis()
                            sendAck(flow)
                        }
                        is ClientDataResult.Forward -> {
                            flow.socket!!.outputStream.write(r.data.bytes, 0, r.data.length)
                            r.data.release() // INV-13: the Baseline releases once the bytes are out
                            flow.ackToDevice = tcp.seq + tcp.payloadLength
                            flow.lastActivity = System.currentTimeMillis()
                            sendAck(flow)
                        }
                        ClientDataResult.PassThrough -> {
                            flow.socket!!.outputStream.write(packet, tcp.payloadOffset, tcp.payloadLength)
                            flow.ackToDevice = tcp.seq + tcp.payloadLength
                            flow.lastActivity = System.currentTimeMillis()
                            sendAck(flow)
                        }
                        ClientDataResult.Reset -> Unit // the Engine already RST the device
                    }
                } catch (e: IOException) { teardown(flow) }
            }
        }
        if (tcp.fin && flow.connected.get() && !flow.finSeen && Integer.compareUnsigned(tcp.seq, flow.ackToDevice) <= 0) {
            flow.finSeen = true
            flow.ackToDevice = tcp.seq + tcp.payloadLength + 1 // FIN consumes a sequence number
            flow.lastActivity = System.currentTimeMillis()
            try { flow.socket!!.shutdownOutput() } catch (e: IOException) { teardown(flow); return }
            sendAck(flow)
        }
    }

    private fun connectUpstream(flow: TcpFlow) {
        val socket = Socket()
        val dstHost = InetAddress.getByAddress(flow.dstIp).hostAddress
        if (BuildConfig.DEBUG) Log.i(TAG, "tcp connect upstream dst=$dstHost:${flow.dstPort} v6=${flow.isV6}")
        try {
            socket.tcpNoDelay = true
            protect(socket)
            socket.connect(InetSocketAddress(InetAddress.getByAddress(flow.dstIp), flow.dstPort), 5_000)
            // Bounded reads: an upstream that has gone quiet must still let the relay run its
            // retransmission timer (SocketTimeoutException in relayLoop), else a hole under a
            // tail segment would wait on read() for data that will never come.
            socket.soTimeout = RELAY_READ_TIMEOUT_MS
            if (BuildConfig.DEBUG) Log.i(TAG, "tcp connect upstream OK dst=$dstHost:${flow.dstPort}")
        } catch (e: IOException) {
            // Address family unreachable on the physical network (e.g. v4-only carrier):
            // RST the device's connect so its stack falls back to the next address
            // (RFC 8305 / libcore iterates on ConnectException) instead of hanging.
            if (BuildConfig.DEBUG) Log.w(TAG, "tcp connect upstream FAIL dst=$dstHost:${flow.dstPort} err=${e.message ?: e.javaClass.simpleName}")
            sendRst(flow)
            teardown(flow)
            return
        }
        if (flow.closed.get()) {
            // Reset while we were connecting (an exhausted-rule admission): the RST is already on
            // its way to the device, so answer nothing and leave no socket behind.
            socket.closeQuietly()
            return
        }
        flow.socket = socket
        flow.connected.set(true)
        sendSynAck(flow)
        thread(name = "vpn-relay-${flow.key}") { relayLoop(flow) }
    }

    /** Abort the device-side connect when the upstream address family is unreachable. */
    private fun sendRst(flow: TcpFlow) {
        val rst = VpnPacket.buildTcpSegment(
            src = flow.dstIp, dst = flow.deviceIp,
            srcPort = flow.dstPort, dstPort = flow.devicePort,
            seq = flow.seqToDevice, ack = flow.ackToDevice,
            rst = true, ackFlag = true,
        )
        engine.onRstNudge(writeTun(rst))
    }

    private fun sendSynAck(flow: TcpFlow) {
        // No options advertised (no MSS/WS): the device keeps its default MSS (536), safely
        // below the TUN MTU. Our ISN is chosen locally.
        // The SYN-ACK segment sits at seq=ISN (it consumes that byte); seqToDevice = ISN+1
        // is where the first data/FIN segment starts. ISN was fixed at flow creation, so a
        // SYN retransmit re-sends an identical SYN-ACK (TCP requires an identical reply).
        val synAck = VpnPacket.buildTcpSegment(
            src = flow.dstIp, dst = flow.deviceIp,
            srcPort = flow.dstPort, dstPort = flow.devicePort,
            seq = flow.seqToDevice - 1, ack = flow.ackToDevice,
            syn = true, ackFlag = true,
        )
        val written = writeTun(synAck)
        // A-04/INV-7: flow key embeds both IPs and hex is packet content — debug only.
        if (BuildConfig.DEBUG) Log.i(TAG, "tcp synack key=${flow.key} seq=${flow.seqToDevice - 1} ack=${flow.ackToDevice} ok=$written hex=${synAck.take(60).joinToString("") { "%02x".format(it) }}")
    }

    private fun sendAck(flow: TcpFlow) {
        writeTun(
            VpnPacket.buildTcpSegment(
                src = flow.dstIp, dst = flow.deviceIp,
                srcPort = flow.dstPort, dstPort = flow.devicePort,
                seq = flow.seqToDevice, ack = flow.ackToDevice,
                ackFlag = true,
            ),
        )
    }

    private fun relayLoop(flow: TcpFlow) {
        val buf = ByteArray(VpnPacket.MAX_TCP_SEGMENT)
        try {
            while (running && !flow.closed.get()) {
                val n = try {
                    flow.socket!!.inputStream.read(buf)
                } catch (e: SocketTimeoutException) {
                    // Upstream idle: run the repair timer so a hole cannot wedge the flow.
                    flow.sendWindow.dueRetransmit(SystemClock.elapsedRealtime())
                        ?.let { sendRelaySegment(flow, it, psh = false) }
                    continue
                }
                if (n <= 0) break // upstream closed -> inject FIN
                flow.tap.onServerData(n)
                var off = 0
                while (off < n) {
                    if (!running || flow.closed.get()) return
                    val now = SystemClock.elapsedRealtime()
                    val seg = flow.sendWindow.reserveSend(buf, off, n - off, SEND_WAIT_SLICE_MS, now)
                    if (seg != null) {
                        sendRelaySegment(flow, seg, psh = true)
                        off += seg.length
                        continue
                    }
                    // Window full: repair a hole, or probe a zero window so the device
                    // re-advertises it (RFC 1122 §4.2.2.17).
                    flow.sendWindow.dueRetransmit(now)?.let { sendRelaySegment(flow, it, psh = false) }
                    flow.sendWindow.reserveProbe(buf, off, now)?.let {
                        sendRelaySegment(flow, it, psh = false)
                        off += 1
                    }
                }
            }
            if (flow.closed.get()) return
            drainUnacked(flow)
            writeTun(
                VpnPacket.buildTcpSegment(
                    src = flow.dstIp, dst = flow.deviceIp,
                    srcPort = flow.dstPort, dstPort = flow.devicePort,
                    seq = flow.seqToDevice, ack = flow.ackToDevice,
                    fin = true, ackFlag = true,
                ),
            )
        } catch (e: IOException) {
            // socket closed by teardown
        } finally {
            teardown(flow)
        }
    }

    /**
     * Bounded wait for the tail to be acknowledged before FIN. Teardown follows the FIN, so a
     * hole left under it would be the same permanent wedge the flow-control fix removes: the
     * device would keep ACKing the hole and nothing would be left to retransmit.
     */
    private fun drainUnacked(flow: TcpFlow) {
        val deadline = SystemClock.elapsedRealtime() + FIN_DRAIN_MS
        while (running && !flow.closed.get() && flow.sendWindow.hasUnacked() &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            flow.sendWindow.dueRetransmit(SystemClock.elapsedRealtime())
                ?.let { sendRelaySegment(flow, it, psh = false) }
            try {
                Thread.sleep(DRAIN_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun sendRelaySegment(flow: TcpFlow, seg: TcpSendWindow.Segment, psh: Boolean) {
        val packet = VpnPacket.buildTcpSegment(
            src = flow.dstIp, dst = flow.deviceIp,
            srcPort = flow.dstPort, dstPort = flow.devicePort,
            seq = seg.seq, ack = flow.ackToDevice,
            psh = psh, ackFlag = true,
            payload = seg.payload, payloadOffset = 0, payloadLength = seg.length,
        )
        if (firstSegHex) {
            firstSegHex = false
            val base = if (flow.isV6) 40 else 20
            if (BuildConfig.DEBUG) Log.i(TAG, "tcp seghex PRE ${packet.size}B cksum=${VpnPacket.readShort(packet, base + 16)} hex=${packet.joinToString("") { "%02x".format(it) }}")
        }
        writeTun(packet)
        flow.lastActivity = System.currentTimeMillis()
    }

    private fun teardown(flow: TcpFlow) {
        if (flow.closed.getAndSet(true)) return
        flow.tap.onClosed() // exactly once: releases a held inspection buffer, leaves FlowRegistry
        flow.socket.closeQuietly()
        flows.remove(flow.key, flow) // identity-checked: a reset must not evict a same-key retry
    }

    /** Teardown close: an already-dead socket or fd is expected, not a failure. */
    private fun Closeable?.closeQuietly() {
        try {
            this?.close()
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "close failed: ${e.message}")
        }
    }

    private fun tcpKey(ip: VpnPacket.IpHeader, tcp: VpnPacket.TcpHeader): String =
        "${ip.version}:${InetAddress.getByAddress(ip.src).hostAddress}:${tcp.srcPort}->" +
            "${InetAddress.getByAddress(ip.dst).hostAddress}:${tcp.dstPort}"

    private fun sweepLoop() {
        while (running) {
            Thread.sleep(120_000)
            val now = System.currentTimeMillis()
            for (flow in flows.values) {
                if (now - flow.lastActivity > FLOW_IDLE_SWEEP_MS) teardown(flow)
            }
            udpFlows.entries.removeIf { (key, flow) ->
                if (now - flow.lastActivity > FLOW_IDLE_SWEEP_MS) {
                    flow.tap.onClosed()
                    flow.socket.closeQuietly()
                    if (BuildConfig.DEBUG) Log.d(TAG, "swept udp flow $key")
                    true
                } else false
            }
        }
    }

    // ---- lifecycle ----

    override fun onRevoke() {
        Log.w(TAG, "MARKER: vpn-state REVOKED")
        state = VpnEngineState.REVOKED
        reason = "onRevoke"
        engine.onVpnRevoked() // §14 tamper counter, before the engine's shutdown
        postRevokeNotification()
        stopAll()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun stopSinkhole() {
        if (state == VpnEngineState.STOPPED || state == VpnEngineState.REVOKED) { stopSelf(); return }
        state = VpnEngineState.STOPPED
        reason = ""
        stopAll()
        Log.i(TAG, "MARKER: vpn-state STOPPED")
    }

    private fun fail(why: String) {
        state = VpnEngineState.FAILED
        reason = why
        stopAll()
        Log.e(TAG, "MARKER: vpn-state FAILED $why")
    }

    private fun stopAll() {
        // First, before any teardown: the start worker's Dekker handshake reads this after
        // publishing the tunnel fields (finding #21).
        stopGeneration++
        running = false
        scope.cancel()
        // §6.9: the Engine's single shutdown — bounded final flush, monitor unregister and the
        // "Website limits paused" notification. Reached from onDestroy, onRevoke, STOP, fail and
        // the pump's EOF path; the Engine's lifecycle guard makes every repeat a no-op.
        engine.stop()
        tunFd.closeQuietly()
        dnsSocket4.closeQuietly()
        dnsSocket6.closeQuietly()
        for (flow in flows.values) {
            flow.tap.onClosed() // returns any held inspection buffer to the pool
            flow.socket.closeQuietly()
        }
        flows.clear()
        for (flow in udpFlows.values) {
            flow.tap.onClosed()
            flow.socket.closeQuietly()
        }
        udpFlows.clear()
        pendingDns.clear()
        tunFd = null; tunOut = null; dnsSocket4 = null; dnsSocket6 = null
        // After engine.stop()'s flush has run: releases the two threads.
        tickExecutor.shutdown()
        dbExecutor.shutdown()
        stopSelf()
    }

    /** Plan §8.4: REVOKED surfaces as a persistent notification. Suppressed without POST_NOTIFICATIONS — in-app surfacing is the Slice 4 engine's job (§9). */
    private fun postRevokeNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    "vpn_spike",
                    getString(R.string.vpn_revoked_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            nm.notify(
                1,
                NotificationCompat.Builder(this, "vpn_spike")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(getString(R.string.vpn_revoked_title))
                    .setContentText(getString(R.string.vpn_revoked_text))
                    // #26: the copy says "re-open the app to turn it back on" — the tap must
                    // land on Rules, where the activation row and consent dialog live.
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this,
                            0,
                            Intent(this, MainActivity::class.java)
                                // Data = PendingIntent identity (extras are not): without
                                // it this shares request code 0 + a bare MainActivity intent
                                // with the protection/domain-usage taps and rewrites their
                                // tab extra (owner warning 2026-10-23).
                                .setData(NotificationTapTarget.of("vpn-spike"))
                                .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_RULES)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: Exception) {
            Log.d(TAG, "revoke notification suppressed: ${e.javaClass.simpleName}")
        }
    }

    private inner class TcpFlow(
        val key: String,
        val deviceIp: ByteArray,
        val devicePort: Int,
        val dstIp: ByteArray,
        val dstPort: Int,
        val isV6: Boolean,
        initialAckToDevice: Int,
        isn: Int,
        deviceWindow: Int,
    ) : FlowControl {
        val connected = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        @Volatile var ackToDevice: Int = initialAckToDevice

        /** Send-side sequence and window state ([TcpSendWindow]); [seqToDevice] is its next seq. */
        val sendWindow = TcpSendWindow(isn, deviceWindow)
        val seqToDevice: Int get() = sendWindow.nextSeq

        @Volatile var socket: Socket? = null
        @Volatile var finSeen: Boolean = false
        @Volatile var lastActivity: Long = System.currentTimeMillis()

        /** Set by [handleTcp] on the packet thread, before the device can send payload. */
        @Volatile var tap: FlowTap = PassThroughTap

        /**
         * Baseline Contract P2(c), added by S0: RST|ACK the device at the current sequence
         * numbers, then drop the flow. Safe from any thread — the single-syscall TUN write
         * and the atomic [closed] guard are the synchronization (the baseline has no
         * per-flow owner queue to post to; direct calls are equivalent here).
         */
        override fun reset() {
            if (closed.get()) return
            sendRst(this)
            teardown(this)
        }
    }

    /**
     * Created before the socket: the Engine decides admission (and may drop the tuple outright,
     * in which case no socket is ever opened). [reset] is a no-op for UDP — a datagram path has no
     * per-flow thread to signal, so the Engine's per-datagram exhaustion check is the enforcement
     * point (see `UdpActivityTap`); the registry sweep still reaches it for symmetry.
     */
    private inner class UdpFlow(
        val deviceIp: ByteArray,
        val devicePort: Int,
        val dstIp: ByteArray,
        val dstPort: Int,
    ) : FlowControl {
        @Volatile var socket: DatagramSocket? = null
        @Volatile var tap: FlowTap = PassThroughTap
        @Volatile var lastActivity: Long = System.currentTimeMillis()

        override fun reset() = Unit
    }

    private class PendingDns(val deviceIp: ByteArray, val devicePort: Int, val dnsIp: ByteArray)
}
