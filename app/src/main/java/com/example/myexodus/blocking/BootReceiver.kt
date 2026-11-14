package com.example.myexodus.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myexodus.BuildConfig
import com.example.myexodus.vpn.VpnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Plan §8 Step 9: fast-path snapshot load synchronously (small on-disk cache read), then the
 * authoritative rebuild asynchronously. Also starts the app-block foreground service (§8.1)
 * when protection is enabled — the FGS's startup recompute covers auto-started restricted
 * apps after boot. No UI, no DB work in the receiver itself.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var publisher: RulesSnapshotPublisher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        // Debug-only: BOOT_COMPLETED delivery is flaky on some OEMs (issuetracker 471573182,
        // incl. Infinix/TECNO) — the device matrix needs to see this fire (2026-08-28 research).
        if (BuildConfig.DEBUG) Log.i(TAG, "${intent.action} received")
        publisher.loadFastPath()
        // goAsync: without it the platform may kill the process the moment onReceive returns,
        // silently dropping the authoritative reload and the post-boot tunnel ensure (the
        // fast-path snapshot alone would leave website rules unenforced until the next app
        // open). The budget is the receiver's own; the work is one small DB read.
        val pending = goAsync()
        scope.launch {
            try {
                publisher.loadFromDb()
                // #26: same SSOT as the in-app pass — the publisher's url rules (the blocking
                // policy itself). Runs after the authoritative load so a rule edit that predates
                // the reboot is seen.
                runCatching {
                    val hasRules = publisher.snapshot.urlRules.isNotEmpty()
                    VpnLifecycle.ensure(context.applicationContext, hasRules)
                }.onFailure { Log.w(TAG, "tunnel ensure-pass failed", it) }
            } finally {
                pending.finish()
            }
        }
        EnforcementForegroundService.start(context)
    }

    private companion object {
        const val TAG = "MyExodusBoot"
    }
}
