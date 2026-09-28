package com.example.myexodus.accountability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb control for [DebugBackend] (exported for `am broadcast` — the shell cannot reach a
 * non-exported receiver, same rule as DeviceVerificationReceiver):
 *   `am broadcast -a com.example.myexodus.debug.SET_BACKEND --es host 10.0.2.2`
 *   `am broadcast -a com.example.myexodus.debug.CLEAR_BACKEND`
 * The wiring applies at process start (provider init), so restart the app process after.
 */
class DebugBackendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(DebugBackend.PREFS, Context.MODE_PRIVATE).edit()
        when (intent.action) {
            ACTION_SET -> {
                val host = intent.getStringExtra("host").orEmpty()
                if (host.isEmpty()) {
                    Log.w(TAG, "set-backend needs --es host <ip>")
                    return
                }
                prefs.putString(DebugBackend.KEY_HOST, host).apply()
                Log.i(TAG, "debug backend host=$host — restart the app process to apply")
            }
            ACTION_CLEAR -> {
                prefs.remove(DebugBackend.KEY_HOST).apply()
                Log.i(TAG, "debug backend cleared — restart the app process to apply")
            }
        }
    }

    companion object {
        private const val TAG = "MyExodusDebugBackend"
        const val ACTION_SET = "com.example.myexodus.debug.SET_BACKEND"
        const val ACTION_CLEAR = "com.example.myexodus.debug.CLEAR_BACKEND"
    }
}
