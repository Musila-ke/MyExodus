package com.example.myexodus.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.registerSystemReceiver

/**
 * Owner ruling 2026-11-01: an uninstalled app's block is hidden, never deleted — the rule
 * stays stored and active, so reinstalling the app brings the block back unchanged. Website
 * rules have no package to install and stay visible.
 */
internal fun visibleRules(
    rules: List<RestrictionRule>,
    installedPackages: Set<String>,
): List<RestrictionRule> = rules.filter {
    it.targetType != "APP" || it.blockedPackage in installedPackages
}

/**
 * Launcher packages the app can see (the same query the app picker uses), re-read whenever a
 * package is installed or removed while the caller is composed. Replacement flows emit REMOVED
 * with EXTRA_REPLACING and then ADDED, so the re-query is correct for updates too.
 */
@Composable
internal fun rememberInstalledAppPackages(): Set<String> {
    val context = LocalContext.current
    var packages by remember { mutableStateOf(launchablePackages(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                packages = launchablePackages(context)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        context.registerSystemReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return packages
}

private fun launchablePackages(context: Context): Set<String> =
    context.packageManager
        .queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        )
        .mapTo(HashSet()) { it.activityInfo.packageName }
