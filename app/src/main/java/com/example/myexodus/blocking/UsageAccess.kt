package com.example.myexodus.blocking

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** Canonical usage-access (PACKAGE_USAGE_STATS) grant check — an appop, not a runtime
 *  permission (blocking-core plan §8.2). unsafeCheckOpNoThrow is API 29+; checkOpNoThrow
 *  carries the same semantics below Q. */
fun Context.hasUsageAccess(): Boolean {
    val appOps = getSystemService(AppOpsManager::class.java)
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}
