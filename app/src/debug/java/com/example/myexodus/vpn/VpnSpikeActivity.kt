package com.example.myexodus.vpn

import android.annotation.SuppressLint
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import java.net.NetworkInterface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Random
import javax.inject.Inject
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Slice 3 spike harness (plan §7 step 3): drives the consent flow, starts the sinkhole,
 * then runs the seven gates and reports each via MARKER log lines + the on-screen status.
 * The host script (scripts/dns-sinkhole-device-test.sh) reacts to the MARKERs.
 */
@SuppressLint("SetTextI18n") // debug-only harness; marker strings are never localized
@AndroidEntryPoint
class VpnSpikeActivity : androidx.activity.ComponentActivity() {

    @Inject lateinit var rulesRepository: RulesRepository
    @Inject lateinit var storeFactory: com.example.myexodus.data.db.AppRulesStoreFactory

    companion object {
        const val TAG = "MyExodusVpnSpike"
        private const val REQ_VPN_CONSENT = 1
        private const val DNS_V4 = "8.8.8.8"
        private const val DNS_V6 = "2001:4860:4860::8888"
        private const val HTTPS_TARGET = "https://example.com"
        private const val DNS_TARGET = "example.com"
        private const val BLOCKED_TARGET = "blocked.example.com"
        private const val SPIKE_PROFILE_ID = "spike-harness-profile"
        // The spike's blocked targets as real WEBSITE policy rules — the engine now loads its
        // blocklist from Room (plan §6), so the harness seeds policy instead of hardcoding.
        private val SPIKE_DOMAINS = listOf("blocked.example.com", "betting.example.com", "gambling.example.com")
    }

    private lateinit var status: TextView
    private val results = mutableListOf<Pair<String, String>>()
    private var stopRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 16f; setPadding(24, 24, 24, 24); gravity = Gravity.START }
        val button = Button(this).apply { text = "Stop VPN"; setOnClickListener { stopRequested = true; startService(Intent(this@VpnSpikeActivity, DnsSinkholeVpnService::class.java).setAction(DnsSinkholeVpnService.ACTION_STOP)) } }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(status); addView(button) })
        append("MyExodus VPN spike harness")
        Log.i(TAG, "MARKER: spike-launched")
        thread(name = "spike-policy-seed") { seedPolicy() }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            Log.i(TAG, "MARKER: vpn-consent-required")
            startActivityForResult(prepare, REQ_VPN_CONSENT)
        } else {
            startVpn()
        }
    }

    /** Seeds the spike profile + WEBSITE rules; run in a background thread (Room I/O). The
     *  dns_blocked gate retries until it sees NXDOMAIN, so it tolerates the seed + async
     *  policy reload racing the service start. */
    private fun seedPolicy() {
        val seeded = runBlocking(Dispatchers.IO) {
            try {
                val store = storeFactory.open()
                if (store == null) { Log.e(TAG, "policy seed: rules store unavailable"); return@runBlocking false }
                if (store.userProfileDao().getById(SPIKE_PROFILE_ID) == null) {
                    store.userProfileDao().insert(
                        UserProfile(
                            userId = SPIKE_PROFILE_ID,
                            name = "Spike Harness",
                            email = "",
                            phoneNumber = null,
                            dateOfBirth = 0L,
                            hasAccessibilityPermission = false,
                            hasUsageStatsPermission = false,
                            hasVpnPermission = true,
                            hasNotificationPermission = false,
                            hasDeviceAdminPermission = false,
                            devicePublicKey = null,
                            recoveryEscrowKey = null,
                            isActive = true,
                        ),
                    )
                }
                val existing = rulesRepository.getActiveRules().map { it.blockedPackage }.toSet()
                for (domain in SPIKE_DOMAINS) {
                    if (domain !in existing) rulesRepository.insertRule(profileRule(domain))
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "policy seed failed", e)
                false
            }
        }
        Log.i(TAG, "MARKER: policy-seeded success=$seeded")
    }

    private fun profileRule(domain: String): RestrictionRule = RestrictionRule(
        ruleId = "spike-$domain",
        profileId = SPIKE_PROFILE_ID,
        blockedPackage = domain,
        targetType = "WEBSITE",
        isAlwaysOn = true,
        dailyLimitMs = 0,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        timezone = "UTC",
        blockExpiresAt = Long.MAX_VALUE,
        severityLevel = "MEDIUM",
    )

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_VPN_CONSENT) {
            if (resultCode == RESULT_OK) startVpn()
            else {
                append("FAILED: VPN consent denied")
                Log.e(TAG, "MARKER: spike-done ALL=FAIL gate=vpn-consent reason=denied")
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun startVpn() {
        append("starting sinkhole...")
        startService(Intent(this, DnsSinkholeVpnService::class.java).setAction(DnsSinkholeVpnService.ACTION_START))
        thread(name = "spike-gates") { runGates() }
    }

    private fun runGates() {
        if (!waitForActive()) return
        gate("routes", ::checkRoutes)
        gate("dns_blocked", ::checkDnsBlocked)
        gate("dns_allowed", ::checkDnsAllowed)
        gate("https_200", ::checkHttps)
        gate("ipv6_sinkhole", ::checkIpv6)
        gate("private_dns", ::checkPrivateDns)
        gate("stop_restores", ::checkStop)
        val all = results.all { it.second == "PASS" }
        val detail = results.joinToString(" ") { "${it.first}=${it.second}" }
        append(if (all) "ALL GATES PASSED" else "GATES FAILED: $detail")
        Log.i(TAG, "MARKER: spike-done ALL=${if (all) "PASS" else "FAIL"} $detail")
    }

    private fun waitForActive(): Boolean {
        // DEGRADED is a running engine with a disclosed gap (e.g. Private DNS active) — the
        // private_dns gate asserts the disclosure; the other gates still run and must pass.
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            when (DnsSinkholeVpnService.state) {
                VpnEngineState.ACTIVE, VpnEngineState.DEGRADED -> return true
                VpnEngineState.FAILED -> {
                    append("FAILED: sinkhole ${DnsSinkholeVpnService.reason}")
                    Log.e(TAG, "MARKER: spike-done ALL=FAIL gate=vpn-start reason=${DnsSinkholeVpnService.reason}")
                    return false
                }
                else -> Thread.sleep(300)
            }
        }
        append("FAILED: sinkhole never became ACTIVE")
        Log.e(TAG, "MARKER: spike-done ALL=FAIL gate=vpn-start reason=timeout")
        return false
    }

    private fun gate(name: String, check: () -> String) {
        val result = try { check() } catch (e: Exception) { "FAIL ${e.javaClass.simpleName}: ${e.message}" }
        val pass = result.startsWith("PASS")
        results.add(name to if (pass) "PASS" else if (result.startsWith("SKIP")) "SKIP" else "FAIL")
        append("$name: $result")
        Log.i(TAG, "MARKER: gate $name ${if (pass) "PASS" else if (result.startsWith("SKIP")) "SKIP" else "FAIL"} $result")
    }

    // ---- gates ----

    private fun checkRoutes(): String {
        // `ip` exec is denied netlink access from the app uid on XOS — read the TUN
        // addresses via Java instead. XOS applies the TUN config asynchronously, so poll.
        var okV4 = false; var okV6 = false
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !(okV4 && okV6)) {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (ni.name == "tun0") Log.i(TAG, "tun0 mtu=${ni.mtu}")
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    when (addrs.nextElement().hostAddress) {
                        "192.0.2.1" -> okV4 = true
                        "fd00:14:14::1" -> okV6 = true
                    }
                }
            }
            if (!(okV4 && okV6)) Thread.sleep(200)
        }
        return if (okV4 && okV6) "PASS tun v4=$okV4 v6=$okV6"
        else "FAIL v4-tun=$okV4 v6-tun=$okV6"
    }

    private fun checkDnsBlocked(): String {
        val (rcode, ancount) = dnsQuery(BLOCKED_TARGET, useV6 = false, expectNxDomain = true)
        return if (rcode == 3) "PASS NXDOMAIN(rcode=3)"
        else "FAIL expected NXDOMAIN got rcode=$rcode ancount=$ancount"
    }

    private fun checkDnsAllowed(): String {
        val (rcode, ancount) = dnsQuery(DNS_TARGET, useV6 = false, expectNxDomain = false)
        return if (rcode == 0 && ancount > 0) "PASS resolved($ancount answers)"
        else "FAIL expected answers got rcode=$rcode ancount=$ancount"
    }

    private fun checkHttps(): String {
        var code = -1
        repeat(3) {
            code = httpsStatusCode(HTTPS_TARGET)
            if (code == 200) return "PASS https=$code"
            Thread.sleep(1500)
        }
        return "FAIL https=$code"
    }

    private fun checkIpv6(): String {
        // The ::/0 route sends the v6 query into the TUN regardless of upstream v6
        // availability, so this exercises the v6 parse + NXDOMAIN + checksum path.
        return try {
            val (rcode, _) = dnsQuery(BLOCKED_TARGET, useV6 = true, expectNxDomain = true)
            if (rcode == 3) "PASS v6-NXDOMAIN(rcode=3)"
            else "FAIL expected v6 NXDOMAIN got rcode=$rcode"
        } catch (e: Exception) {
            "SKIP no v6 route into tun: ${e.javaClass.simpleName}"
        }
    }

    private fun checkPrivateDns(): String {
        val mode = DnsSinkholeVpnService.privateDnsMode
        val specifier = DnsSinkholeVpnService.privateDnsSpecifier
        val detected = mode.isNotEmpty() && mode != "unknown"
        val degraded = mode == "strict" || mode == "opportunistic"
        return if (detected) "PASS mode=$mode${if (specifier.isNotEmpty()) " specifier=$specifier" else ""}${if (degraded) " (DoT bypass disclosed per plan)" else ""}"
        else "FAIL mode=$mode"
    }

    private fun checkStop(): String {
        stopRequested = true
        startService(Intent(this, DnsSinkholeVpnService::class.java).setAction(DnsSinkholeVpnService.ACTION_STOP))
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && DnsSinkholeVpnService.state != VpnEngineState.STOPPED) {
            Thread.sleep(200)
        }
        Thread.sleep(2000) // let netd restore the physical-network resolver before testing
        val code = httpsStatusCode(HTTPS_TARGET)
        return if (code == 200) "PASS stop+post-stop https=$code"
        else "FAIL post-stop https=$code"
    }

    // ---- helpers ----

    private fun dnsQuery(qname: String, useV6: Boolean, expectNxDomain: Boolean): Pair<Int, Int> {
        // Retry until a reply carries the sent tx-id AND the expected outcome: a query sent
        // before the TUN route is live escapes to the ISP's transparent DNS proxy, which
        // echoes our tx-id but answers from its own cache (e.g. a search-redirect rcode=0
        // for a blocked name) — an escaped reply must not end the gate.
        val addr = InetAddress.getByName(if (useV6) DNS_V6 else DNS_V4)
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            DatagramSocket().use { socket ->
                socket.soTimeout = 3_000
                val id = Random().nextInt(0xFFFF)
                val query = DnsMessages.buildQuery(qname, id)
                socket.send(DatagramPacket(query, query.size, addr, 53))
                val buf = ByteArray(512)
                val reply = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(reply)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val got = buf.copyOf(reply.length)
                val gotId = ((got[0].toInt() and 0xFF) shl 8) or (got[1].toInt() and 0xFF)
                if (gotId != id) continue
                val rcode = DnsMessages.rcode(got, 0)
                val ancount = DnsMessages.answerCount(got, 0)
                if (expectNxDomain) { if (rcode != 3) continue } else { if (rcode != 0 || ancount == 0) continue }
                return rcode to ancount
            }
        }
        return -1 to 0
    }

    private fun httpsStatusCode(url: String): Int {
        // Iterate addresses like a real client (Chrome/OkHttp happy-eyeballs): on a v4-only
        // carrier the resolver returns AAAA first, the relay RSTs the unreachable v6 attempt,
        // and the client must try the next address instead of giving up.
        val u = URL(url)
        val port = if (u.port == -1) 443 else u.port
        var last = -1
        val addrs = InetAddress.getAllByName(u.host)
        Log.i(TAG, "https addrs=${addrs.joinToString { it.hostAddress }}")
        for (a in addrs) {
            val t0 = System.currentTimeMillis()
            last = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(a, port), 10_000)
                    // compileSdk 37 stub trims getDefault() to SocketFactory — cast to the SSL type
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, u.host, port, true) as SSLSocket
                    ssl.soTimeout = 10_000
                    ssl.startHandshake()
                    ssl.getOutputStream().write("GET / HTTP/1.1\r\nHost: ${u.host}\r\nConnection: close\r\n\r\n".toByteArray())
                    val code = statusCode(ssl)
                    ssl.close()
                    code
                }
            } catch (e: ConnectException) {
                Log.i(TAG, "https ${a.hostAddress} ConnectException ${e.message} after ${System.currentTimeMillis() - t0}ms")
                continue // unreachable family — next address
            } catch (e: Exception) {
                Log.i(TAG, "https ${a.hostAddress} ${e.javaClass.simpleName} ${e.message} after ${System.currentTimeMillis() - t0}ms")
                -1
            }
        }
        return last
    }

    /** Parse the status code from "HTTP/1.1 200 OK" (digits at offset 9-11). */
    private fun statusCode(socket: Socket): Int {
        val buf = ByteArray(16)
        val n = socket.getInputStream().read(buf)
        if (n < 12) return -1
        val d = { i: Int -> (buf[i].toInt() and 0xFF) - 0x30 }
        return if (d(9) in 0..9 && d(10) in 0..9 && d(11) in 0..9) d(9) * 100 + d(10) * 10 + d(11) else -1
    }

    private fun exec(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).start()
        val out = p.inputStream.readBytes().decodeToString()
        val err = p.errorStream.readBytes().decodeToString()
        p.waitFor()
        return out + err
    }

    private fun append(text: String) = runOnUiThread { status.text = status.text.toString() + "\n" + text }
}
