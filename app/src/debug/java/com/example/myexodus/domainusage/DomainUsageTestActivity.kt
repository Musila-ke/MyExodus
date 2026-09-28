package com.example.myexodus.domainusage

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import com.example.myexodus.vpn.DnsSinkholeVpnService
import com.example.myexodus.vpn.VpnEngineState
import kotlin.concurrent.thread

/**
 * §12.2 harness driver: starts the production VPN service and gets out of the way. The shell
 * cannot do this itself (uid 2000 is refused `am start-foreground-service`, `BIND_VPN_SERVICE`
 * is app/system-only — device-verified 2026-09-20), and the spike activity cannot be reused
 * because it stops the sinkhole when its own host handshake window closes.
 *
 * The activity finishes as soon as the service reports ACTIVE, so nothing of this harness
 * holds the foreground while the matrix measures traffic (the foreground service keeps the
 * process alive). `--ez stop true` stops the service instead.
 */
class DomainUsageTestActivity : androidx.activity.ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = Intent(this, DnsSinkholeVpnService::class.java)
        if (intent.getBooleanExtra("stop", false)) {
            startService(service.setAction(DnsSinkholeVpnService.ACTION_STOP))
            Log.i(TAG, "DUPROBE vpn-stop issued")
            finish()
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            Log.i(TAG, "DUPROBE vpn-consent-required")
            startActivityForResult(prepare, REQ_VPN_CONSENT)
        } else {
            startVpn(service)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_VPN_CONSENT) {
            if (resultCode == RESULT_OK) startVpn(Intent(this, DnsSinkholeVpnService::class.java))
            else Log.e(TAG, "DUPROBE vpn-start FAILED reason=consent-denied")
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun startVpn(service: Intent) {
        startService(service.setAction(DnsSinkholeVpnService.ACTION_START))
        thread(name = "duprobe-vpn-start") {
            // Monotonic, deliberately: this measures an elapsed wait, so a wall-clock jump
            // (I-9/I-10 move the clock) must not shorten or extend it. S1.4 forbids
            // currentTimeMillis outside SystemWallClock.kt; nanoTime is also the right clock.
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) {
                when (DnsSinkholeVpnService.state) {
                    VpnEngineState.ACTIVE, VpnEngineState.DEGRADED -> {
                        Log.i(TAG, "DUPROBE vpn-state=${DnsSinkholeVpnService.state}")
                        // Consent already granted is the normal path; the result-code path above
                        // runs only on a fresh install, where this activity is still foreground.
                        runOnUiThread { finish() }
                        return@thread
                    }
                    VpnEngineState.FAILED -> {
                        Log.e(TAG, "DUPROBE vpn-start FAILED reason=${DnsSinkholeVpnService.reason}")
                        runOnUiThread { finish() }
                        return@thread
                    }
                    else -> Thread.sleep(300)
                }
            }
            Log.e(TAG, "DUPROBE vpn-start FAILED reason=timeout")
            runOnUiThread { finish() }
        }
    }

    private companion object {
        const val TAG = "MyExodusVpn"
        const val REQ_VPN_CONSENT = 1
    }
}
