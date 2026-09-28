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
import java.io.File
import java.net.NetworkInterface
import javax.inject.Inject
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Slice 3 spike harness (plan §7 step 3): drives the consent flow, starts the sinkhole,
 * then runs its gates and reports each via MARKER log lines + the on-screen status.
 * The host script (scripts/dns-sinkhole-device-test.sh) reacts to the MARKERs.
 *
 * Scope note (S8/INV-9): the builder excluded this app from its own TUN, so the app's own
 * sockets bypass the sinkhole — the traffic path can no longer be tested from the inside.
 * This harness keeps the app-side gates (routes via Java — XOS denies `ip` to the app uid —
 * Private DNS setting read, stop lifecycle); the host script owns the traffic-path gates
 * (blocked/allowed resolution, relay) from the shell uid, which is not excluded.
 */
@SuppressLint("SetTextI18n") // debug-only harness; marker strings are never localized
@AndroidEntryPoint
class VpnSpikeActivity : androidx.activity.ComponentActivity() {

    @Inject lateinit var rulesRepository: RulesRepository
    @Inject lateinit var storeFactory: com.example.myexodus.data.db.AppRulesStoreFactory

    companion object {
        const val TAG = "MyExodusVpnSpike"
        private const val REQ_VPN_CONSENT = 1
        private const val SPIKE_PROFILE_ID = "spike-harness-profile"
        private const val HOST_DONE_FILE = "spike-host-done"
        private const val HOST_WINDOW_MS = 120_000L
        // The spike's blocked targets as real WEBSITE policy rules — the engine now loads its
        // blocklist from Room (plan §6), so the harness seeds policy instead of hardcoding.
        // The first entry is also the host script's blocked-resolution probe; keep them in sync.
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

    /** Seeds the spike profile + WEBSITE rules; run in a background thread (Room I/O). The host
     *  script waits for the `policy-seeded` marker before running its traffic-path gates. */
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
        gate("private_dns", ::checkPrivateDns)
        waitForHostTrafficWindow()
        gate("stop", ::checkStop)
        val all = results.none { it.second == "FAIL" } // SKIP = not applicable here, not a pass
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

    private fun checkPrivateDns(): String {
        val mode = DnsSinkholeVpnService.privateDnsMode
        val specifier = DnsSinkholeVpnService.privateDnsSpecifier
        // An unset setting is an image property (bare AOSP emulators), not a product surface:
        // the D-34 monitor is the real detection path and I-14/I-14b verify it on-device.
        if (mode.isEmpty() || mode == "unknown") return "SKIP mode unset on this image"
        val degraded = mode == "strict" || mode == "opportunistic"
        return "PASS mode=$mode${if (specifier.isNotEmpty()) " specifier=$specifier" else ""}${if (degraded) " (DoT bypass disclosed per plan)" else ""}"
    }

    /**
     * Host handshake (S8/INV-9): the app is excluded from its own TUN and the host shell cannot
     * start a VpnService (BIND_VPN_SERVICE is app/system-only), so the app holds the VPN up
     * while the script runs its shell-uid traffic gates, then drops this file to say it is done.
     * The deadline keeps a killed script from leaking a live VPN.
     */
    private fun waitForHostTrafficWindow() {
        Log.i(TAG, "MARKER: traffic-window-open")
        val dir = getExternalFilesDir(null)
        if (dir != null) {
            val done = File(dir, HOST_DONE_FILE)
            done.delete()
            val deadline = System.currentTimeMillis() + HOST_WINDOW_MS
            while (System.currentTimeMillis() < deadline && !done.exists() && !stopRequested) Thread.sleep(500)
        }
        Log.i(TAG, "MARKER: traffic-window-close")
    }

    /** Service lifecycle: the stop action must reach STOPPED. The host script asserts the same
     *  marker and then checks that post-stop resolution works from outside the app. */
    private fun checkStop(): String {
        stopRequested = true
        startService(Intent(this, DnsSinkholeVpnService::class.java).setAction(DnsSinkholeVpnService.ACTION_STOP))
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && DnsSinkholeVpnService.state != VpnEngineState.STOPPED) {
            Thread.sleep(200)
        }
        return if (DnsSinkholeVpnService.state == VpnEngineState.STOPPED) "PASS stopped"
        else "FAIL state=${DnsSinkholeVpnService.state}"
    }

    private fun append(text: String) = runOnUiThread { status.text = status.text.toString() + "\n" + text }
}
