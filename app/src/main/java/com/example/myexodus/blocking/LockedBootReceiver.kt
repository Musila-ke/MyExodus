package com.example.myexodus.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myexodus.BuildConfig
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Plan §8.5: restores enforcement from the Device-Protected-Storage snapshot before first
 * user unlock (LOCKED_BOOT_COMPLETED fires pre-unlock; BOOT_COMPLETED only after). Room is
 * credential-protected and unreachable here, so this publishes the DE copy as Ready — the
 * post-unlock authoritative load (BootReceiver → loadFromDb) supersedes it and rewrites the
 * DE copy (reconciliation). No DB work, no UI, nothing async: the read is a small file read.
 */
@AndroidEntryPoint
class LockedBootReceiver : BroadcastReceiver() {

    @Inject lateinit var publisher: RulesSnapshotPublisher

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (BuildConfig.DEBUG) Log.i(TAG, "LOCKED_BOOT_COMPLETED received (pre-unlock)")
        val restored = publisher.loadDeviceProtected()
        if (restored == null) Log.i(TAG, "No device-protected snapshot to restore")
    }

    private companion object {
        const val TAG = "MyExodusLockedBoot"
    }
}
