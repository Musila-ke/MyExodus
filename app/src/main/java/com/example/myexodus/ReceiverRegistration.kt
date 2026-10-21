package com.example.myexodus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build

/**
 * Registers a runtime receiver for a system-sent broadcast. NOT_EXPORTED (API 33+) keeps
 * third-party apps from spoofing the action. On 26-32 the flags parameter predates the
 * exported constants, so RECEIVER_EXPORTED is passed for shape alone — its value (2) is
 * ignored there, which keeps every path explicitly flagged.
 */
@Suppress("InlinedApi")
fun Context.registerSystemReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }
}
