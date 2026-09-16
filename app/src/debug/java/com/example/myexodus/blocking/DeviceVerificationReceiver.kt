package com.example.myexodus.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myexodus.data.db.entity.UserProfile
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Focus-free §12 assertions (debug only): starting [DeviceVerificationActivity] to read state
 * hijacks the foreground — the read activity's own WSC closes the very usage session under
 * measurement (device-caught 2026-09-15 on the handoff scenario: the "read" ended the accrual
 * it was measuring). A broadcast runs in-process with no window, so `usage-read` here is safe
 * to call mid-measurement.
 *
 * Host: `am broadcast -n com.example.myexodus/.blocking.DeviceVerificationReceiver -a usage-read --es pkg <pkg>`
 */
class DeviceVerificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_USAGE_READ -> {
                val pkg = intent.getStringExtra("pkg")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        readRow(context, pkg)
                    } catch (t: Throwable) {
                        Log.e(TAG, "usage-read failed pkg=$pkg", t)
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    private suspend fun readRow(context: Context, pkg: String?) {
        if (pkg == null) {
            Log.e(TAG, "usage row: --es pkg required")
            return
        }
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, EnhancedModeTestEntryPoint::class.java,
        )
        val store = entryPoint.appRulesStoreFactory().open() ?: error("store unavailable")
        val profile = store.userProfileDao().observeActive().first() ?: PROFILE
        val rows = store.dailyUsageTrackerDao().getForProfile(profile.userId)
            .filter { it.packageName == pkg }
        if (rows.isEmpty()) Log.i(TAG, "usage row pkg=$pkg ABSENT") else rows.forEach {
            Log.i(
                TAG,
                "usage row pkg=${it.packageName} used=${it.msUsedToday} anchor=${it.localDateAnchor}/${it.timezoneIdAtAnchor} " +
                    "w1=${it.warning1State} w2=${it.warning2State} blocked=${it.blockedToday}",
            )
        }
    }

    private companion object {
        const val TAG = "MyExodusVerification"
        const val ACTION_USAGE_READ = "usage-read"
        val PROFILE = UserProfile(
            userId = "device-test-profile", name = "Device Test", email = "devicetest@example.com",
            phoneNumber = null, dateOfBirth = 0L, hasAccessibilityPermission = false,
            hasUsageStatsPermission = false, hasVpnPermission = false, hasNotificationPermission = false,
            hasDeviceAdminPermission = false, devicePublicKey = null, recoveryEscrowKey = null, isActive = true,
        )
    }
}
