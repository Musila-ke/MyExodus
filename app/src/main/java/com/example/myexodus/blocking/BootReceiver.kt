package com.example.myexodus.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myexodus.BuildConfig
import com.example.myexodus.settings.EnhancedModeSettings
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
    @Inject lateinit var settings: EnhancedModeSettings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Debug-only: BOOT_COMPLETED delivery is flaky on some OEMs (issuetracker 471573182,
        // incl. Infinix/TECNO) — the device matrix needs to see this fire (2026-08-28 research).
        if (BuildConfig.DEBUG) Log.i(TAG, "BOOT_COMPLETED received")
        publisher.loadFastPath()
        scope.launch { publisher.loadFromDb() }
        if (settings.enhancedModeEnabled) {
            EnforcementForegroundService.start(context)
        }
    }

    private companion object {
        const val TAG = "MyExodusBoot"
    }
}
