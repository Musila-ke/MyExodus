package com.example.myexodus.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myexodus.BuildConfig
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Plan §8.5: restores enforcement from the Device-Protected-Storage snapshot before first
 * user unlock (LOCKED_BOOT_COMPLETED fires pre-unlock; BOOT_COMPLETED only after). Room is
 * credential-protected and unreachable here, so this publishes the DE copy as Ready — the
 * post-unlock authoritative load (BootReceiver → loadFromDb) supersedes it and rewrites the
 * DE copy (reconciliation). No DB work, no UI: the read is a small file read.
 *
 * Plan §8 Step 9 adds the deferred-reconciliation mark below (DE prefs only — never Room,
 * never the Keystore, Decision 8). Recorded deviation, source-verified 2026-09-15: the
 * plan's pre-unlock WorkManager re-enqueue is impossible on WorkManager 2.11.2 (credential-
 * encrypted workdb, every WM component `directBootAware="false"`); the re-enqueue runs
 * post-unlock in MyExodusApp's reconciliation block.
 */
@AndroidEntryPoint
class LockedBootReceiver : BroadcastReceiver() {

    @Inject lateinit var publisher: RulesSnapshotPublisher
    @Inject lateinit var bootReconciliation: BootReconciliationStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (BuildConfig.DEBUG) Log.i(TAG, "LOCKED_BOOT_COMPLETED received (pre-unlock)")
        val restored = publisher.loadDeviceProtected()
        if (restored == null) Log.i(TAG, "No device-protected snapshot to restore")
        // Step 9: mark this boot for post-unlock reconciliation. The coroutine is owned by
        // this onReceive and torn down the moment the pending result finishes — never a bare
        // app-lifetime scope (plan §8 Step 9).
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                bootReconciliation.markPending(systemBootCount(context))
            } catch (t: Exception) {
                // Self-healing: the cold-start BOOT_COUNT comparison reconciles regardless.
                Log.w(TAG, "Reconciliation mark failed", t)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "MyExodusLockedBoot"
    }
}
